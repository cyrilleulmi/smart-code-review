package dev.smartcodereview.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Where a file sits in the architecture: is the repository organised as ports &amp; adapters (hexagonal), which
 * layer does a file belong to, is it part of a web frontend. All decided by folder and file names, plus a
 * few import patterns for the frontend - no parsing.
 */
public final class Structure {

    public static final String DOMAIN = "domain";
    public static final String APPLICATION = "application";
    public static final String USECASE = "usecase";
    public static final String ADAPTER = "adapter";

    private static final Set<String> DOMAIN_DIRS = Set.of("domain");
    private static final Set<String> USECASE_DIRS = Set.of("usecase", "usecases", "use-case", "use-cases", "use_case",
            "use_cases", "interactor", "interactors");
    private static final Set<String> APPLICATION_DIRS = Set.of("application", "port", "ports");
    private static final Set<String> ADAPTER_DIRS = Set.of("adapter", "adapters", "infrastructure", "infra");
    private static final Set<String> PORT_DIRS = Set.of("port", "ports");
    private static final Set<String> SIDE_DIRS = Set.of("in", "out", "inbound", "outbound", "primary", "secondary",
            "driving", "driven");
    /** Folders outside the code: an `infra` below `deploy/` is infrastructure as code, not an adapter layer. */
    private static final Set<String> NON_CODE_DIRS = Set.of("deploy", "deployment", "deployments", "terraform", "helm",
            "charts", "k8s", "kubernetes", "docs", "doc", ".github", ".gitlab", "node_modules", "ops", "scripts");

    private static final Pattern USECASE_NAME = Pattern.compile("(UseCase|Interactor)(Impl)?$");
    private static final Pattern ADAPTER_NAME = Pattern.compile(
            "(Adapter|Controller|Resource|RepositoryImpl|JpaRepository|Client|Gateway|Listener|Consumer|Producer|Endpoint)$");
    private static final Pattern PORT_NAME = Pattern.compile("Port$");
    private static final Pattern INBOUND = Pattern.compile(
            "(?i)/(in|inbound|web|rest|http|api|controller|controllers|primary|driving|ui|cli|graphql|grpc|errorhandling|exceptionhandling)/");
    private static final Pattern OUTBOUND = Pattern.compile(
            "(?i)/(out|outbound|persistence|repository|repositories|db|jpa|database|client|clients|external|secondary|driven|mail|storage|connector|connectors)/");
    /** Messaging adapters consume (in) or publish (out); the folder alone does not tell which. */
    private static final Pattern CONSUMER_NAME = Pattern.compile("(Listener|Consumer|Subscriber|Receiver|MessageHandler|EventHandler)$");
    private static final Pattern PUBLISHER_NAME = Pattern.compile("(Publisher|Producer|Sender|Emitter)$");
    private static final Pattern MAPPING = Pattern.compile("(?i)/(mapping|mappings|mapper|mappers|converter|converters)/|(Mapper|Converter)(Impl)?\\.\\w+$");
    private static final Pattern CONFIG = Pattern.compile("(?i)/(config|configuration|configurations|security)/|(Config|Configuration)\\.\\w+$");

    private static final Pattern FRONTEND_EXT = Pattern.compile("(?i)\\.(tsx|jsx|vue|svelte|html?)$");
    private static final Pattern SCRIPT_EXT = Pattern.compile("(?i)\\.(ts|js|mjs|mts)$");
    private static final Pattern BACKEND_PATH = Pattern.compile(
            "(?i)(^|/)(src/main/|src/test/java|server/|backend/|api/.*route\\.[jt]s$|pages/api/|functions/|lambda/)");
    private static final Pattern FRONTEND_DIR = Pattern.compile(
            "(?i)(^|/)(frontend|web|webapp|client|ui|components|pages|views|src/app|app/\\(|public)/");
    private static final Pattern FRONTEND_IMPORT = Pattern.compile(
            "from\\s+['\"](@angular/|react['\"/]|react-dom|vue['\"]|svelte|next/|@remix-run|solid-js)");

    private Structure() {
    }

    /** Ports & adapters needs an adapter (or port) layer plus a core layer (domain, use cases or application). */
    public static List<String> hexagonalEvidence(Collection<String> directories) {
        Set<String> kinds = new TreeSet<>();
        List<String> evidence = new ArrayList<>();
        for (String dir : directories) {
            String[] parts = dir.split("/");
            for (int i = 0; i < parts.length; i++) {
                String name = parts[i].toLowerCase(Locale.ROOT);
                if (NON_CODE_DIRS.contains(name)) {
                    break;
                }
                String kind = DOMAIN_DIRS.contains(name) ? DOMAIN : USECASE_DIRS.contains(name) ? USECASE
                        : PORT_DIRS.contains(name) ? "port" : APPLICATION_DIRS.contains(name) ? APPLICATION
                        : ADAPTER_DIRS.contains(name) ? ADAPTER : null;
                if (kind != null && kinds.add(kind)) {
                    evidence.add(String.join("/", java.util.Arrays.copyOf(parts, i + 1)));
                }
                // adapter/in, adapter/out: driving and driven sides are a ports & adapters vocabulary
                if (ADAPTER.equals(kind) && i + 1 < parts.length && SIDE_DIRS.contains(parts[i + 1].toLowerCase(Locale.ROOT))
                        && kinds.add("side")) {
                    evidence.add(String.join("/", java.util.Arrays.copyOf(parts, i + 2)));
                }
            }
        }
        boolean edge = kinds.contains(ADAPTER) || kinds.contains("port");
        boolean core = kinds.contains(DOMAIN) || kinds.contains(USECASE) || kinds.contains(APPLICATION);
        if (!edge || !core) {
            return List.of();
        }
        evidence.sort(Comparator.naturalOrder());
        return evidence;
    }

    /**
     * "hexagonal" when the evidence has ports, use cases or adapter/in|out (ports &amp; adapters), else "layered": domain /
     * application / adapter packages without ports, i.e. a layered (onion-style) split with adapter entry points.
     */
    public static String styleOf(List<String> evidence) {
        if (evidence.isEmpty()) {
            return null;
        }
        boolean ports = evidence.stream().map(d -> d.substring(d.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT))
                .anyMatch(n -> PORT_DIRS.contains(n) || USECASE_DIRS.contains(n) || SIDE_DIRS.contains(n));
        return ports ? "hexagonal" : "layered";
    }

    /** Layer of a file in a ports &amp; adapters project: name conventions first, then the deepest marker folder. */
    public static String layerOf(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        String stem = file.contains(".") ? file.substring(0, file.indexOf('.')) : file;
        if (USECASE_NAME.matcher(stem).find()) {
            return USECASE;
        }
        if (stem.endsWith("Service") && path.contains("/application/")) {
            return APPLICATION; // application services, also in layouts like application/domain/service
        }
        String[] dirs = path.contains("/") ? path.substring(0, path.lastIndexOf('/')).split("/") : new String[0];
        for (int i = dirs.length - 1; i >= 0; i--) {
            String d = dirs[i].toLowerCase(Locale.ROOT);
            if (ADAPTER_DIRS.contains(d)) {
                return ADAPTER;
            }
            if (USECASE_DIRS.contains(d)) {
                return USECASE;
            }
            if (APPLICATION_DIRS.contains(d)) {
                return APPLICATION;
            }
            if (DOMAIN_DIRS.contains(d)) {
                return DOMAIN;
            }
        }
        if (ADAPTER_NAME.matcher(stem).find()) {
            return ADAPTER;
        }
        if (PORT_NAME.matcher(stem).find()) {
            return APPLICATION;
        }
        return null;
    }

    /**
     * "in" (driving: REST, UI, CLI, message listeners), "out" (driven: persistence, clients, publishers),
     * "mapping" (DTO &lt;-&gt; model), "config" (transport/security configuration) or null.
     */
    public static String adapterDirection(String path) {
        String p = "/" + path;
        String file = p.substring(p.lastIndexOf('/') + 1);
        String stem = file.contains(".") ? file.substring(0, file.indexOf('.')) : file;
        if (CONSUMER_NAME.matcher(stem).find()) {
            return "in";
        }
        if (PUBLISHER_NAME.matcher(stem).find()) {
            return "out";
        }
        if (MAPPING.matcher(p).find()) {
            return "mapping";
        }
        if (CONFIG.matcher(p).find()) {
            return "config";
        }
        if (INBOUND.matcher(p).find() || p.matches(".*(Controller|Resource|Endpoint)\\.\\w+$")) {
            return "in";
        }
        if (OUTBOUND.matcher(p).find() || p.matches(".*(Repository\\w*|Client|Gateway)\\.\\w+$")) {
            return "out";
        }
        return null;
    }

    /** A file of a web frontend: component/template files, or scripts in frontend folders or with UI imports. */
    public static boolean isFrontend(String path, String source) {
        if (BACKEND_PATH.matcher(path).find()) {
            return false;
        }
        if (FRONTEND_EXT.matcher(path).find()) {
            return true;
        }
        if (!SCRIPT_EXT.matcher(path).find()) {
            return false;
        }
        return FRONTEND_DIR.matcher(path).find() || (source != null && FRONTEND_IMPORT.matcher(source).find());
    }

    /** Component key: the path without its extension, so player-view.ts/.html/.scss form one component. */
    public static String componentKey(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(0, dot) : path;
    }

    static List<String> unique(Collection<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
