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

    private static final Pattern USECASE_NAME = Pattern.compile("(UseCase|Interactor)(Impl)?$");
    private static final Pattern ADAPTER_NAME = Pattern.compile(
            "(Adapter|Controller|Resource|RepositoryImpl|JpaRepository|Client|Gateway|Listener|Consumer|Producer|Endpoint)$");
    private static final Pattern PORT_NAME = Pattern.compile("Port$");
    private static final Pattern INBOUND = Pattern.compile("(?i)/(in|inbound|web|rest|http|api|controller|controllers|primary|driving|ui|cli|graphql|grpc)/");
    private static final Pattern OUTBOUND = Pattern.compile("(?i)/(out|outbound|persistence|repository|repositories|db|jpa|database|client|clients|messaging|external|secondary|driven|mail|storage)/");

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
                String kind = DOMAIN_DIRS.contains(name) ? DOMAIN : USECASE_DIRS.contains(name) ? USECASE
                        : PORT_DIRS.contains(name) ? "port" : APPLICATION_DIRS.contains(name) ? APPLICATION
                        : ADAPTER_DIRS.contains(name) ? ADAPTER : null;
                if (kind != null && kinds.add(kind)) {
                    evidence.add(String.join("/", java.util.Arrays.copyOf(parts, i + 1)));
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

    /** "in" (driving: REST, UI, CLI), "out" (driven: persistence, clients) or null. */
    public static String adapterDirection(String path) {
        String p = "/" + path;
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
