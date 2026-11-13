package dev.smartcodereview.provider;

import io.quarkus.logging.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Credential lookup helpers shared by the providers. */
public final class Tokens {

    private Tokens() {
    }

    /** Resolves a token once and remembers the result (also "none"). */
    public static final class Lazy {
        private final Supplier<Optional<String>> resolver;
        private volatile Optional<String> value;

        public Lazy(Supplier<Optional<String>> resolver) {
            this.resolver = resolver;
        }

        public Optional<String> get() {
            Optional<String> v = value;
            if (v == null) {
                synchronized (this) {
                    if (value == null) {
                        value = resolver.get();
                    }
                    v = value;
                }
            }
            return v;
        }
    }

    /** The configured value, else the first non-blank environment variable. */
    public static Optional<String> first(Optional<String> configured, String... envNames) {
        Optional<String> result = configured.filter(s -> !s.isBlank());
        for (String name : envNames) {
            result = result.or(() -> Optional.ofNullable(System.getenv(name)).filter(s -> !s.isBlank()));
        }
        return result;
    }

    /** Output of a CLI such as `gh auth token`; empty when the tool is missing or fails. */
    public static Optional<String> fromCommand(String... command) {
        try {
            Process p = new ProcessBuilder(command).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty()) {
                Log.infof("Using token from `%s`", String.join(" ", command));
                return Optional.of(out);
            }
        } catch (IOException e) {
            Log.debugf("%s not available: %s", command[0], e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /** Bytes as UTF-8 text, empty for binary content (NUL byte) or files above maxBytes. */
    public static Optional<String> text(byte[] body, int maxBytes) {
        if (body.length > maxBytes) {
            return Optional.empty();
        }
        for (int i = 0; i < Math.min(body.length, 8000); i++) {
            if (body[i] == 0) {
                return Optional.empty();
            }
        }
        return Optional.of(new String(body, StandardCharsets.UTF_8));
    }
}
