package dev.smartcodereview.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects the frontend to the backend across REST: finds HTTP calls with a literal URL in client code
 * (`http.put(`/api/players/${id}`, …)`, `fetch('/api/x', { method: 'POST' })`) and endpoints in server code
 * (JAX-RS `@Path` + `@PUT`, Spring `@PutMapping`, Next.js `app/api/…/route.ts`, Express `app.get('/x', …)`,
 * Go `HandleFunc("PUT /x", …)`). Paths are compared segment by segment; path parameters match anything.
 */
public final class Http {

    /** line: 1-based line of the call in the caller's file. path: normalised, parameters as "{}". */
    public record Call(String method, String path, int line) {
    }

    /** method null = any method. */
    public record Endpoint(String method, String path) {
        public String label() {
            return (method == null ? "" : method + " ") + path;
        }
    }

    private static final Pattern CLIENT_CALL = Pattern.compile(
            "\\b(?:http|httpClient|client|api|apiClient|axios|\\$http|ky|request|this)\\s*\\.\\s*(get|post|put|patch|delete)\\s*(?:<[^()]*?>)?\\s*\\(\\s*(['\"`])(.*?)\\2(\\s*\\+)?");
    private static final Pattern FETCH_CALL = Pattern.compile(
            "\\b(?:fetch|\\$fetch|ofetch|useFetch)\\s*(?:<[^()]*?>)?\\s*\\(\\s*(['\"`])(.*?)\\1(\\s*\\+)?");
    private static final Pattern FETCH_METHOD = Pattern.compile("\\bmethod\\s*:\\s*['\"`](\\w+)['\"`]");

    private static final Pattern JAXRS_METHOD = Pattern.compile("@(GET|POST|PUT|PATCH|DELETE|HEAD)\\b");
    private static final Pattern JAXRS_PATH = Pattern.compile("@Path\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern SPRING_MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete|Request)Mapping\\b(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(\\w+)");
    private static final Pattern ROUTE_REGISTRATION = Pattern.compile(
            "\\b(?:app|router|server|fastify|routes|api)\\s*\\.\\s*(get|post|put|patch|delete|all)\\s*\\(\\s*(['\"`])(/[^'\"`]*)\\2");
    private static final Pattern GO_ROUTE = Pattern.compile(
            "\\.(HandleFunc|Handle|Get|Post|Put|Patch|Delete|GET|POST|PUT|PATCH|DELETE)\\s*\\(\\s*\"(?:(GET|POST|PUT|PATCH|DELETE)\\s+)?(/[^\"]*)\"");
    private static final Pattern NEXT_ROUTE = Pattern.compile("(?:^|/)app/(.*?)/route\\.[jt]sx?$");
    private static final Pattern NEXT_PAGES_API = Pattern.compile("(?:^|/)pages/(api/.*?)(?:/index)?\\.[jt]sx?$");
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    private Http() {
    }

    // ------------------------------------------------------------------ client side

    /** HTTP calls with a literal URL on the given 0-based lines of a file. */
    public static List<Call> calls(List<String> raw, List<Integer> lines) {
        List<Call> result = new ArrayList<>();
        for (int idx : lines) {
            if (idx < 0 || idx >= raw.size()) {
                continue;
            }
            String line = raw.get(idx);
            Matcher m = CLIENT_CALL.matcher(line);
            while (m.find()) {
                String path = normaliseUrl(m.group(3), m.group(4) != null);
                if (path != null) {
                    result.add(new Call(m.group(1).toUpperCase(Locale.ROOT), path, idx + 1));
                }
            }
            m = FETCH_CALL.matcher(line);
            while (m.find()) {
                String path = normaliseUrl(m.group(2), m.group(3) != null);
                if (path != null) {
                    result.add(new Call(fetchMethod(raw, idx), path, idx + 1));
                }
            }
        }
        return result;
    }

    /** `fetch(url, { method: 'PUT' })`: the method is in the options, usually within the next lines. */
    private static String fetchMethod(List<String> raw, int idx) {
        for (int i = idx; i < Math.min(raw.size(), idx + 8); i++) {
            Matcher m = FETCH_METHOD.matcher(raw.get(i));
            if (m.find()) {
                return m.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return "GET";
    }

    /** `/api/players/${id}?x=1` -> `/api/players/{}`; `${base}/players` -> `{}/players`; host and query are dropped. */
    static String normaliseUrl(String url, boolean concatenated) {
        String u = url.replaceAll("\\$\\{[^}]*}", "{}") + (concatenated ? "{}" : "");
        u = u.replaceFirst("^https?://[^/]+", "");
        int cut = u.indexOf('?');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        cut = u.indexOf('#');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        if (!(u.startsWith("/") || u.startsWith("{}")) || !u.contains("/")) {
            return null;
        }
        return normalisePath(u);
    }

    // ------------------------------------------------------------------ server side

    /**
     * Endpoint declared by annotations on a method (JAX-RS or Spring). methodHead: the method's annotation and
     * declaration lines, classHead: those of the enclosing class (for the base path).
     */
    public static Endpoint annotated(List<String> methodHead, List<String> classHead) {
        String base = "";
        for (String l : classHead) {
            Matcher m = JAXRS_PATH.matcher(l);
            if (m.find()) {
                base = m.group(1);
                break;
            }
            m = SPRING_MAPPING.matcher(l);
            if (m.find() && m.group(2) != null) {
                Matcher s = STRING.matcher(m.group(2));
                if (s.find()) {
                    base = s.group(1);
                    break;
                }
            }
        }
        String method = null;
        String path = "";
        boolean endpoint = false;
        for (String l : methodHead) {
            Matcher m = JAXRS_METHOD.matcher(l);
            if (m.find()) {
                method = m.group(1);
                endpoint = true;
            }
            m = JAXRS_PATH.matcher(l);
            if (m.find()) {
                path = m.group(1);
            }
            m = SPRING_MAPPING.matcher(l);
            if (m.find()) {
                endpoint = true;
                if (!m.group(1).equals("Request")) {
                    method = m.group(1).toUpperCase(Locale.ROOT);
                }
                if (m.group(2) != null) {
                    Matcher s = STRING.matcher(m.group(2));
                    if (s.find()) {
                        path = s.group(1);
                    }
                    Matcher rm = REQUEST_METHOD.matcher(m.group(2));
                    if (rm.find()) {
                        method = rm.group(1).toUpperCase(Locale.ROOT);
                    }
                }
            }
        }
        return endpoint ? new Endpoint(method, normalisePath("/" + base + "/" + path)) : null;
    }

    /** Route registrations in a line: Express/Fastify `app.put('/x', …)`, Go `mux.HandleFunc("PUT /x", …)`. */
    public static List<Endpoint> registered(String line) {
        List<Endpoint> result = new ArrayList<>();
        Matcher m = ROUTE_REGISTRATION.matcher(line);
        while (m.find()) {
            String method = m.group(1).equalsIgnoreCase("all") ? null : m.group(1).toUpperCase(Locale.ROOT);
            result.add(new Endpoint(method, normalisePath(m.group(3))));
        }
        m = GO_ROUTE.matcher(line);
        while (m.find()) {
            String fn = m.group(1).toUpperCase(Locale.ROOT);
            String method = m.group(2) != null ? m.group(2) : METHODS.contains(fn) ? fn : null;
            result.add(new Endpoint(method, normalisePath(m.group(3))));
        }
        return result;
    }

    /** File-based routes: Next.js `app/api/players/[id]/route.ts` (exported GET/PUT/…) and `pages/api/…`. */
    public static Endpoint fileRoute(String path, String symbolName) {
        Matcher m = NEXT_ROUTE.matcher(path);
        if (m.find()) {
            String method = symbolName == null ? null : symbolName.toUpperCase(Locale.ROOT);
            if (method == null || !METHODS.contains(method)) {
                return null;
            }
            return new Endpoint(method, normalisePath("/" + m.group(1)));
        }
        m = NEXT_PAGES_API.matcher(path);
        if (m.find() && symbolName != null && (symbolName.equals("handler") || symbolName.equals("default"))) {
            return new Endpoint(null, normalisePath("/" + m.group(1)));
        }
        return null;
    }

    // ------------------------------------------------------------------ matching

    /**
     * Segments are compared from the end; parameters match any segment. A leading parameter of the call (a base
     * URL like `${this.baseUrl}`) may stand for several segments. At least one literal segment must match.
     */
    public static boolean matches(Call call, Endpoint endpoint) {
        if (endpoint.method() != null && !endpoint.method().equals(call.method())) {
            return false;
        }
        List<String> a = segments(call.path());
        List<String> b = segments(endpoint.path());
        int i = a.size() - 1;
        int j = b.size() - 1;
        int literal = 0;
        while (i >= 0 && j >= 0) {
            String x = a.get(i);
            String y = b.get(j);
            if (x.equals(y) && !x.equals("{}")) {
                literal++;
            } else if (!x.equals("{}") && !y.equals("{}")) {
                return false;
            }
            i--;
            j--;
        }
        if (literal == 0) {
            return false;
        }
        boolean callHasBase = !a.isEmpty() && a.getFirst().equals("{}");
        if (j >= 0 && !callHasBase) {
            return false; // the endpoint is longer than the call
        }
        for (int k = 0; k <= i; k++) {
            if (!a.get(k).equals("{}")) {
                return false; // the call has extra literal segments
            }
        }
        return true;
    }

    /** `{id}`, `{id: \\d+}`, `:id`, `[id]`, `[...slug]` -> `{}`; Next.js route groups `(group)` are dropped. */
    static String normalisePath(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty() || (s.startsWith("(") && s.endsWith(")"))) {
                continue;
            }
            if (s.contains("{}") || s.startsWith("{") || s.startsWith(":") || s.startsWith("[") || s.startsWith("<")) {
                out.add("{}");
            } else {
                out.add(s);
            }
        }
        String joined = String.join("/", out);
        return path.startsWith("{}") ? joined : "/" + joined;
    }

    private static List<String> segments(String path) {
        return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
    }
}
