package dev.smartcodereview.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.smartcodereview.model.Model.PrRef;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The local data directory. Plain, pretty-printed JSON and text files so that humans and agents can read
 * and edit them directly; every write is atomic (temp file + move). Files are re-read on every request,
 * so external edits show up immediately. Layout is documented in docs/04-persistenz-und-agenten.md and in
 * the AGENTS.md written into the data directory.
 */
@ApplicationScoped
public class DataStore {

    @ConfigProperty(name = "scr.data-dir")
    Optional<String> configuredDir;

    @Inject
    ObjectMapper mapper;

    private ObjectMapper pretty;
    private Path root;

    void onStart(@Observes StartupEvent ev) {
        pretty = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        root = configuredDir.filter(s -> !s.isBlank()).map(Path::of)
                .orElse(Path.of(System.getProperty("user.home"), ".smart-code-review"))
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve("reviews"));
            copyResource("/data-dir/AGENTS.md", root.resolve("AGENTS.md"));
            copyResource("/rules/default-rules.json", root.resolve("rules.default.json"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialise data directory " + root, e);
        }
        Log.infof("SmartCodeReview data directory: %s", root);
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = DataStore.class.getResourceAsStream(resource)) {
            if (in != null) {
                writeAtomic(target, in.readAllBytes());
            }
        }
    }

    public Path root() {
        return root;
    }

    public Path prDir(PrRef ref) {
        return root.resolve("reviews").resolve(safe(ref.provider())).resolve(safe(ref.owner()))
                .resolve(safe(ref.repo())).resolve(String.valueOf(ref.number()));
    }

    public Path sourcesDir(PrRef ref) {
        return prDir(ref).resolve("sources");
    }

    public <T> Optional<T> read(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), type));
        } catch (IOException e) {
            Log.warnf("Cannot read %s (%s) - ignoring it", file, e.getMessage());
            return Optional.empty();
        }
    }

    public void write(Path file, Object value) {
        try {
            writeAtomic(file, pretty.writeValueAsBytes(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<String> readText(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void writeText(Path file, String text) {
        try {
            writeAtomic(file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Resolves a repo-relative path below base without allowing it to escape. */
    public static Path resolveInside(Path base, String relative) {
        Path p = base.resolve(relative).normalize();
        if (!p.startsWith(base)) {
            throw new IllegalArgumentException("Invalid path: " + relative);
        }
        return p;
    }

    private static String safe(String segment) {
        if (!segment.matches("[A-Za-z0-9._-]+") || segment.equals("..") || segment.equals(".")) {
            throw new IllegalArgumentException("Invalid name: " + segment);
        }
        return segment;
    }
}
