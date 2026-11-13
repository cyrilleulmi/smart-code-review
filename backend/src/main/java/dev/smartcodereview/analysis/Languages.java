package dev.smartcodereview.analysis;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps file names to the language ids the frontend's syntax highlighter understands. */
public final class Languages {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"), Map.entry("mts", "typescript"),
            Map.entry("cts", "typescript"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"),
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
            Map.entry("json", "json"), Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"),
            Map.entry("html", "xml"), Map.entry("xml", "xml"), Map.entry("svg", "xml"),
            Map.entry("css", "css"), Map.entry("scss", "scss"), Map.entry("less", "less"),
            Map.entry("md", "markdown"), Map.entry("properties", "properties"),
            Map.entry("sh", "bash"), Map.entry("ps1", "powershell"), Map.entry("sql", "sql"),
            Map.entry("py", "python"), Map.entry("go", "go"), Map.entry("cs", "csharp"),
            Map.entry("dockerfile", "dockerfile"));

    /** Languages with C-like braces, for which symbols (functions, classes, ...) are extracted. */
    private static final Set<String> BRACE_LANGUAGES = Set.of("typescript", "javascript", "java", "kotlin", "csharp", "go");

    private Languages() {
    }

    public static String of(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.equals("dockerfile") || name.startsWith("dockerfile.")) {
            return "dockerfile";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "plaintext";
        }
        return BY_EXTENSION.getOrDefault(name.substring(dot + 1), "plaintext");
    }

    public static boolean hasSymbols(String language) {
        return BRACE_LANGUAGES.contains(language);
    }
}
