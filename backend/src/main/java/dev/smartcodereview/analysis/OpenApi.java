package dev.smartcodereview.analysis;

import dev.smartcodereview.model.Model.ApiOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the operations of an OpenAPI/Swagger contract (YAML or pretty-printed JSON) line by line - enough for
 * operationId, method, path and the line range, without a YAML parser. Generated clients and server
 * interfaces use the operationId as method name, which is how calls are linked across the stack.
 */
public final class OpenApi {

    /** Contract files by name: openapi*.yaml, swagger.json, api.openapi.yml, ... */
    private static final Pattern CONTRACT_FILE = Pattern.compile("(?i)(^|/)([\\w.-]*[.-])?(openapi|swagger)[\\w.-]*\\.(ya?ml|json)$");

    private static final Pattern KEY = Pattern.compile("^(\\s*)['\"]?([^'\"\\s:]+)['\"]?\\s*:(.*)$");
    private static final Pattern PATH_KEY = Pattern.compile("^(\\s*)['\"]?(/[^'\"]*?)['\"]?\\s*:\\s*\\{?\\s*$");
    private static final Pattern METHOD_KEY = Pattern.compile(
            "^(\\s*)['\"]?(get|put|post|delete|patch|head|options|trace)['\"]?\\s*:\\s*\\{?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPERATION_ID = Pattern.compile("['\"]?operationId['\"]?\\s*:\\s*['\"]?([\\w.$-]+)");
    private static final Pattern SERVER_URL = Pattern.compile("(?:^|[\\s{,\\[-])['\"]?url['\"]?\\s*:\\s*['\"]?([^'\"\\s,}]+)");
    private static final Pattern BASE_PATH = Pattern.compile("^\\s*['\"]?basePath['\"]?\\s*:\\s*['\"]?([^'\"\\s,]+)");

    private OpenApi() {
    }

    public static boolean isContract(String path) {
        return CONTRACT_FILE.matcher(path).find();
    }

    public static List<ApiOperation> operations(String file, String text) {
        List<String> lines = text.lines().toList();
        String base = basePath(lines);
        List<ApiOperation> result = new ArrayList<>();
        int pathsIndent = -1;
        int pathIndent = -1;
        String path = null;
        String method = null;
        String opId = null;
        int opStart = 0;
        int end = lastCodeLine(lines);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            Matcher key = KEY.matcher(line);
            int indent = indentOf(line);
            if (pathsIndent < 0) {
                if (key.matches() && key.group(2).equals("paths")) {
                    pathsIndent = indent;
                }
                continue;
            }
            boolean isKey = key.matches() || PATH_KEY.matcher(line).matches();
            boolean closes = line.trim().startsWith("}"); // JSON: the brace that closes "paths"
            if ((isKey || closes) && indent <= pathsIndent) {
                end = i; // end of the paths block
                break;
            }
            Matcher pk = PATH_KEY.matcher(line);
            if (pk.matches() && (pathIndent < 0 || indent <= pathIndent)) {
                add(result, file, base, path, method, opId, opStart, i);
                pathIndent = indent;
                path = pk.group(2);
                method = null;
                opId = null;
                continue;
            }
            Matcher mk = METHOD_KEY.matcher(line);
            if (path != null && mk.matches() && indent > pathIndent) {
                add(result, file, base, path, method, opId, opStart, i);
                method = mk.group(2).toUpperCase(Locale.ROOT);
                opId = null;
                opStart = i + 1;
                continue;
            }
            Matcher id = OPERATION_ID.matcher(line);
            if (method != null && opId == null && id.find()) {
                opId = id.group(1);
            }
        }
        add(result, file, base, path, method, opId, opStart, end);
        return result;
    }

    private static void add(List<ApiOperation> out, String file, String base, String path, String method, String opId,
                            int start, int endExclusive) {
        if (path != null && method != null && opId != null) {
            out.add(new ApiOperation(file, opId, method, Http.normalisePath(base + path), start, Math.max(start, endExclusive)));
        }
    }

    /** OpenAPI 3 `servers: - url: https://host/rest` or Swagger 2 `basePath: /rest` - only the path part. */
    private static String basePath(List<String> lines) {
        int serversIndent = -1;
        for (String line : lines) {
            Matcher key = KEY.matcher(line);
            if (key.matches()) {
                int indent = indentOf(line);
                if (serversIndent >= 0 && indent <= serversIndent) {
                    serversIndent = -1;
                }
                if (key.group(2).equals("servers")) {
                    serversIndent = indent; // the url may follow on the same line (JSON)
                }
            }
            Matcher bp = BASE_PATH.matcher(line);
            if (bp.find()) {
                return stripHost(bp.group(1));
            }
            Matcher url = SERVER_URL.matcher(line);
            if (serversIndent >= 0 && url.find()) {
                return stripHost(url.group(1));
            }
        }
        return "";
    }

    private static String stripHost(String url) {
        String p = url.replaceFirst("^[a-z][\\w+.-]*://[^/]*", "");
        return p.equals("/") ? "" : p.replaceAll("/+$", "");
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    private static int lastCodeLine(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                return i + 1;
            }
        }
        return lines.size();
    }
}
