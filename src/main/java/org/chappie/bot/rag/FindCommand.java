package org.chappie.bot.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

@Command(
    name = "find",
    mixinStandardHelpOptions = true,
    description = "Find all AsciiDoc files in a Quarkus repo checkout and emit a single JSON array manifest."
)
public class FindCommand implements Callable<Integer> {

    private static final Logger LOG = Logger.getLogger(FindCommand.class);
    
    @Option(names = "--repo-root", required = true,
            description = "Path to the *root* of the quarkus repo (the directory that contains 'docs/').")
    Path repoRoot;

    @Option(names = "--docs-subdir",
            description = "Relative docs path under root.",
            defaultValue = "docs/src/main/asciidoc")
    String docsSubdir;

    @Option(names = "--exclude",
            description = "One or more glob patterns to exclude (repeatable). Example: --exclude='**/includes/**' --exclude='**/_*.adoc'")
    List<String> excludeGlobs = new ArrayList<>();

    @Option(names = "--out",
            description = "Write JSON array to this file (default: STDOUT).")
    Path out;

    @Option(names = "--quarkus-version",
            description = "Version label to include in manifest (e.g., '3.15.1' or 'main').")
    String quarkusversion;

    private static final Pattern DOC_TITLE = Pattern.compile("^=\\s+(.+?)\\s*$"); // AsciiDoc doc title
    private static final Pattern DOCTITLE_ATTR = Pattern.compile("^:doctitle:\\s*(.+?)\\s*$"); // fallback

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public Integer call() throws Exception {
        Path docs = repoRoot.resolve(docsSubdir).normalize();
        if (!Files.isDirectory(docs)) {
            LOG.errorf("[find] No docs directory found at: %s", docs);
            return 2;
        }

        if (excludeGlobs.isEmpty()) {
            excludeGlobs.add("**/includes/**");
            excludeGlobs.add("**/_*.adoc");
        }

        List<PathMatcher> excludes = new ArrayList<>();
        FileSystem fs = FileSystems.getDefault();
        for (String g : excludeGlobs) {
            excludes.add(fs.getPathMatcher("glob:" + g));
        }

        List<Path> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(docs)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().endsWith(".adoc"))
             .filter(p -> {
                 Path rel = docs.relativize(p);
                 return excludes.stream().noneMatch(m -> m.matches(rel));
             })
             .forEach(found::add);
        }

        found.sort(Comparator.comparing(Path::toString));

        ArrayNode arr = mapper.createArrayNode();
        for (Path p : found) {
            String title = extractTitle(p);

            ObjectNode o = mapper.createObjectNode()
                .put("repo_path", repoRoot.relativize(p).toString())
                .put("docs_rel_path", docs.relativize(p).toString())
                .put("title", title)
                .put("doc_type", "adoc");

            if (quarkusversion != null && !quarkusversion.isBlank()) {
                o.put("quarkus_version", quarkusversion);
            } else {
                o.putNull("quarkus_version");
            }

            arr.add(o);
        }

        if (out != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
            mapper.writeValue(out.toFile(), arr);
        } else {
            try (Writer w = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
                mapper.writeValue(w, arr);
            }
        }

        LOG.infof("[find] Wrote %d records%n", arr.size());
        return 0;
    }

    private static String extractTitle(Path p) {
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher m = DOC_TITLE.matcher(line);
                if (m.matches()) return m.group(1).trim();
                Matcher m2 = DOCTITLE_ATTR.matcher(line);
                if (m2.matches()) return m2.group(1).trim();
                if (!line.isBlank() && !line.startsWith(":")) break;
            }
        } catch (IOException ignored) { }
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }   
}
