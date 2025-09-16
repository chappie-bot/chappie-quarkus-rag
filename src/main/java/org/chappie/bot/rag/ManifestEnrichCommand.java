package org.chappie.bot.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

@Command(
    name = "manifest-enrich",
    description = "Extract :categories:, :summary:, :extensions:, :topics: from each .adoc into the manifest (JSON array in/out).",
    mixinStandardHelpOptions = true
)
public class ManifestEnrichCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(ManifestEnrichCommand.class);
    
    @Option(names = "--repo-root", required = true,
            description = "Path to the *root* of the quarkus repo (the directory that contains 'docs/').")
    Path repoRoot;
    
    @Option(names = "--in", required = true,
            description = "Input manifest (JSON array).")
    Path manifestIn;

    @Option(names = "--out", required = true,
            description = "Output manifest (JSON array).")
    Path manifestOut;

    @Option(names = "--max-scan-lines", defaultValue = "120",
            description = "How many lines to scan from top of each .adoc (default: ${DEFAULT-VALUE}).")
    int maxScanLines;

    @Option(names = "--verbose", defaultValue = "false",
            description = "Print what was extracted for each file.")
    boolean verbose;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private Charset encoding;

    // Only the four targets we care about
    private static final Pattern TARGETS =
            Pattern.compile("^\\s*:(categories|summary|extensions|topics):\\s*(.*)\\s*$");

    @Override
    public void run() {
        long start = System.nanoTime();
        encoding = Charset.forName("UTF-8");

        try {
            ArrayNode array = (ArrayNode) mapper.readTree(Files.readString(manifestIn));
            int touched = 0;

            for (JsonNode n : array) {
                if (!n.isObject()) continue;
                ObjectNode item = (ObjectNode) n;

                Path adoc = resolveAdoc(item);
                if (adoc == null || !Files.isRegularFile(adoc) || !adoc.getFileName().toString().endsWith(".adoc")) {
                    continue;
                }

                Map<String, String> attrs = scanTopAttrs(adoc, maxScanLines);

                boolean changed = false;

                changed |= putIfPresent(item, "categories", attrs.get("categories"));
                changed |= putIfPresent(item, "summary", attrs.get("summary"));
                changed |= putIfPresent(item, "extensions", attrs.get("extensions"));
                
                changed |= putIfPresent(item, "topics", attrs.get("topics"));
                
                if (verbose) LOG.infof("[enrich] " + repoRoot.relativize(adoc) + " -> " + attrs);
                if (changed) touched++;
            }

            Files.createDirectories(manifestOut.toAbsolutePath().getParent());
            mapper.writeValue(manifestOut.toFile(), array);

            long ms = (System.nanoTime() - start) / 1_000_000L;
            LOG.infof("[enrich] Enriched %d of %d records in %d ms at %s%n",
                    touched, ((ArrayNode) array).size(), ms, Instant.now());
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private Path resolveAdoc(ObjectNode item) {
        JsonNode p = item.get("repo_path");
        if (p == null || !p.isTextual()) return null;
        return repoRoot.resolve(p.asText()).normalize();
    }

    private Map<String, String> scanTopAttrs(Path adoc, int maxLines) throws IOException {
        Map<String, String> found = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(adoc, encoding)) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                if (++n > maxLines) break;

                Matcher m = TARGETS.matcher(line);
                if (m.matches()) {
                    String key = m.group(1).toLowerCase(Locale.ROOT);
                    String val = m.group(2).trim();
                    found.put(key, val);
                    if (found.size() == 4) break;
                }
            }
        }
        return found;
    }

    private boolean putIfPresent(ObjectNode obj, String field, String value) {
        if (value == null || value.isBlank()) return false;
        obj.put(field, value);
        return true;
    }
}
