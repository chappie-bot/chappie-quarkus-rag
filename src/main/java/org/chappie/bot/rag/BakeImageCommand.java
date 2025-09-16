package org.chappie.bot.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.jboss.logging.Logger;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;


@Command(
    name = "bake-image",
    mixinStandardHelpOptions = true,
    description = "Auto-start pgvector, optionally ingest a manifest, dump DB, and bake a Docker image with the dump."
)
public class BakeImageCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(BakeImageCommand.class);

    @Option(names = "--repo-root", required = true,
            description = "Path to the *root* of the quarkus repo (the directory that contains 'docs/').")
    Path repoRoot;
    
    @Option(names = "--in", required = true, description = "Path to enriched manifest JSON (array). When provided, documents are ingested before dump.")
    Path ingestManifest;

    @Option(names = "--quarkus-version", required = true, description = "Target Quarkus version")
    String quarkusversion;

    @Option(names = "--chunk-size", defaultValue = "1000", description = "Splitter chunk size (default: ${DEFAULT-VALUE}).")
    int chunkSize;

    @Option(names = "--chunk-overlap", defaultValue = "200", description = "Splitter chunk overlap (default: ${DEFAULT-VALUE}).")
    int chunkOverlap;

    // --- Image output ---
    @Option(names = "--push", description = "Push to remote registry instead of loading to local Docker daemon.")
    boolean push;

    @Option(names = "--registry-username", description = "Registry username (used only with --push).")
    String registryUsername;

    @Option(names = "--registry-password", description = "Registry password (used only with --push).")
    String registryPassword;

    @Option(names = "--latest", description = "Tag this as the latest image")
    boolean latest;
    
    @Option(names = "--base-image", defaultValue = "pgvector/pgvector:pg16", description = "Base image for final image (default: ${DEFAULT-VALUE}).")
    String baseImageRef;

    private final ObjectMapper mapper = new ObjectMapper();
    private PostgreSQLContainer<?> container;
    private static final String DB_NAME = "postgres";
    
    @Override
    public void run() {
        long t0 = System.nanoTime();
        LOG.infof("[bake-image] bake-image started at %s", Instant.now());

        Path workDir = null;
        try {
            // 1) Start DB
            LOG.info("=== Starting pgvector with Testcontainers ===");
            this.container = new PostgreSQLContainer<>(DockerImageName.parse(this.baseImageRef))
                    .withDatabaseName(DB_NAME)
                    .withUsername("postgres")
                    .withPassword("postgres");
            this.container.start();
            String jdbcUrl = this.container.getJdbcUrl();
            String user = this.container.getUsername();
            String pass = this.container.getPassword();
            LOG.infof("[bake-image] Started: %s id=%s jdbc=%s",
                    this.baseImageRef, this.container.getContainerId(), jdbcUrl);
            

            // 2) Ingestion
            LOG.info("=== Ingesting documents into pgvector ===");
            require(Files.isDirectory(this.repoRoot), "--repo-root must be provided and point to the docs repo root");

            int embeddingDimensions = getDim();
            LOG.infof("[ingest] manifest=%s, repoRoot=%s, dims=%d, chunk=%d/%d",
                    ingestManifest, repoRoot, embeddingDimensions, chunkSize, chunkOverlap);

            DataSource ds = makeDataSource(jdbcUrl, user, pass);

            PgVectorEmbeddingStore store = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(ds)
                    .table("rag_documents")
                    .dimension(embeddingDimensions)
                    .useIndex(true)
                    .indexListSize(100)
                    .build();

            EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();

            var splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);

            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .documentSplitter(splitter)
                    .build();

            List<Map<String, Object>> manifest = readManifestArray(ingestManifest);
            int total = manifest.size();
            int processed = 0;

            for (Map<String, Object> item : manifest) {
                String rel = asText(item.get("repo_path"));
                if (rel == null) continue;
                Path adoc = repoRoot.resolve(rel).normalize();
                if (!Files.isRegularFile(adoc)) {
                    LOG.warnf("[ingest] skip (not a file): %s", adoc);
                    continue;
                }

                String text = readFile(adoc);
                Map<String, Object> meta = new LinkedHashMap<>();
                
                putIfPresent(meta, "title", asText(item.get("title")));
                putIfPresent(meta, "repo_path", rel);
                putIfPresent(meta, "docs_rel_path", asText(item.get("docs_rel_path")));
                putIfPresent(meta, "quarkus_version", asText(item.get("quarkus_version")));
                putIfPresent(meta, "categories", asText(item.get("categories")));
                putIfPresent(meta, "summary", asText(item.get("summary")));
                putIfPresent(meta, "topics", asText(item.get("topics")));
                
                String extsRaw = asText(item.get("extensions"));
                if (extsRaw != null && !extsRaw.isBlank()) {
                    List<String> exts = Arrays.stream(extsRaw.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .distinct()
                            .toList();

                    if (!exts.isEmpty()) {
                        meta.put("extensions", String.join(",", exts));
                        String padded = "," + String.join(",", exts) + ",";
                        meta.put("extensions_csv_padded", padded);
                    }
                }
                
                Document doc = Document.from(text, new Metadata(meta));
                ingestor.ingest(doc);

                processed++;
                if (processed % 25 == 0) {
                    LOG.infof("[ingest] %d / %d ...", processed, total);
                }
            }
            LOG.infof("[ingest] done: %d documents", processed);
            

            // 3) Dump database to SQL
            LOG.info("=== Dumping database ===");
            workDir = Files.createTempDirectory("rag-bake-" + System.nanoTime());
            Path initDir = Files.createDirectories(workDir.resolve("init"));
            Path dump = initDir.resolve("01-rag.sql");

            
            // dump *inside* container to /tmp/rag.sql then copy to host
            String inside = "/tmp/rag.sql";
            var result = this.container.execInContainer(
                    "bash", "-lc",
                    "PGPASSWORD=" + this.container.getPassword() +
                            " pg_dump -U " + this.container.getUsername() +
                            " -d " + DB_NAME +
                            " --no-owner --no-privileges --format=plain -f " + inside
            );
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("pg_dump failed: " + result.getStderr());
            }
            this.container.copyFileFromContainer(inside, dump.toString());
            
            LOG.infof("[bake-image] Dumped SQL -> %s", dump);

            // 4) Build and push the image with Jib
            LOG.info("=== Building image ===");
            FileEntriesLayer initLayer = FileEntriesLayer.builder()
                    .setName("initdb-sql")
                    .addEntryRecursive(initDir, AbsoluteUnixPath.get("/docker-entrypoint-initdb.d"))
                    .build();

            JibContainerBuilder jib = Jib.from(baseImageRef).addFileEntriesLayer(initLayer);

            String targetImageRef = "ghcr.io/quarkusio/chappie-ingestion-quarkus:" + quarkusversion;
            LOG.infof("[bake-image] creating image [%s]", targetImageRef);
            Containerizer containerizer;
            if (push) {
                RegistryImage registry = RegistryImage.named(targetImageRef);
                if (registryUsername != null && registryPassword != null) {
                    registry.addCredential(registryUsername, registryPassword);
                }
                containerizer = Containerizer.to(registry);
            } else {
                containerizer = Containerizer.to(DockerDaemonImage.named(targetImageRef));
            }

            if (latest) {
                LOG.info("[bake-image] also tagging :latest");
                containerizer.withAdditionalTag("latest");
            }
            
            containerizer
                .setToolName("bake-image")
                .setAllowInsecureRegistries(false);

            jib.containerize(containerizer);
            LOG.infof("[bake-image] Image ready: %s", targetImageRef);

        } catch (Exception e) {
            LOG.error("bake-image failed", e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        } finally {
            if (container != null) {
                LOG.info("[bake-image] Stopping auto-started container");
                try {
                    container.stop();
                } catch (Throwable t) {
                    LOG.warn("Failed to stop container", t);
                }
            }
            if (workDir != null) {
                try { deleteRecursive(workDir); } catch (Throwable ignore) {}
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOG.infof("[bake-image] Done in %d ms", ms);
        }
    }

    private static DataSource makeDataSource(String jdbc, String user, String pass) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(jdbc);
        ds.setUser(user);
        ds.setPassword(pass);
        return ds;
    }

    private List<Map<String, Object>> readManifestArray(Path path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return mapper.readValue(br, new TypeReference<List<Map<String, Object>>>() {});
        }
    }

    private static String readFile(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String asText(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private static void putIfPresent(Map<String, Object> map, String key, String val) {
        if (val != null && !val.isBlank()) map.put(key, val);
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new IllegalArgumentException(message);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            List<Path> paths = s.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }

    private int getDim(){
        Config c = ConfigProvider.getConfig();
        return c.getValue("quarkus.langchain4j.pgvector.dimension", Integer.class);
    }
}
