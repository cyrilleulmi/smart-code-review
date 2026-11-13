package dev.smartcodereview.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds named code units (functions, methods, classes, types, top-level constants) with their line ranges
 * in TS/JS/Java-like sources. Regex + brace matching, no real parser: good enough to answer "which function
 * does this changed line belong to?" and "which changed function references which other one?".
 */
public final class SymbolExtractor {

    /**
     * Lines are 1-based and inclusive. kind: class | interface | type | enum | function | method | component | const
     * | test (a test case) | suite (describe block) | setup (beforeEach & co.)
     */
    public record Symbol(String name, String kind, int startLine, int endLine, String container) {
        public int span() {
            return endLine - startLine;
        }

        public boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }

        public String displayName() {
            return container == null ? name : container + (isTestBlock() ? " › " : ".") + name;
        }

        public boolean isCallable() {
            return kind.equals("function") || kind.equals("method") || kind.equals("component")
                    || kind.equals("test") || kind.equals("setup");
        }

        /** Test cases, describe blocks and setup hooks: never called by code, only by the test runner. */
        public boolean isTestBlock() {
            return kind.equals("test") || kind.equals("suite") || kind.equals("setup");
        }
    }

    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "return", "function",
            "new", "throw", "else", "do", "try", "await", "yield", "case", "typeof", "delete", "void", "super", "this",
            "synchronized", "constructor", "import", "export", "from", "in", "of", "instanceof", "with");

    private static final Pattern TYPE_DECL = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:abstract\\s+)?"
                    + "(?:(?:public|private|protected|static|final|sealed|data|open|internal)\\s+)*"
                    + "(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    /** Go: `func Name(`, `func (r *Recv) Name(`, `func Name[T any](`. */
    private static final Pattern GO_FUNC = Pattern.compile(
            "^func\\s+(?:\\(\\s*(?:\\w+\\s+)?\\*?\\s*(\\w+)[^)]*\\)\\s*)?([A-Za-z_]\\w*)\\s*(?:\\[[^\\]]*\\])?\\(");
    /** Go: `type Name struct {` / `type Name interface {`. */
    private static final Pattern GO_TYPE = Pattern.compile("^type\\s+([A-Za-z_]\\w*)(?:\\[[^\\]]*\\])?\\s+(struct|interface)\\b");
    private static final Pattern TYPE_ALIAS = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:declare\\s+)?type\\s+([A-Za-z_$][\\w$]*)\\b[^=]*=");
    private static final Pattern FUNCTION_DECL = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\s*\\(");
    private static final Pattern VARIABLE_DECL = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*(.*)$");
    private static final Pattern FUNCTION_VALUE = Pattern.compile(
            "^(?:async\\s+)?(?:function\\b|\\(|[A-Za-z_$][\\w$]*\\s*=>|<[^>]*>\\s*\\()|^(?:React\\.)?(?:use(?:Callback|Memo)|memo|forwardRef)\\s*\\(");
    /** TS/JS class or object method: `async name(`, `private name<T>(`, `get name(`. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|async|override|readonly|abstract|get|set)\\s+)*\\*?([A-Za-z_$][\\w$]*)\\s*(?:<[^>()]*>)?\\s*\\(");
    /** Java/Kotlin/C#-style method with a return type: `public List<X> name(`. */
    private static final Pattern TYPED_METHOD_DECL = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|static|final|synchronized|abstract|default|override|suspend|fun|async|virtual)\\s+)*"
                    + "(?:<[^>]+>\\s+)?([A-Za-z_$][\\w$.]*(?:<[^()]*>)?(?:\\[\\])*\\??)\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    /** Object/class property holding a function: `name: async (x) => {`, `name = (x) => {`. */
    /** JS/TS test blocks: `describe('…', …)`, `it.only("…", …)`, `test.each(rows)('…', …)`. Strings are blanked, quotes stay. */
    private static final Pattern TEST_BLOCK = Pattern.compile(
            "^\\s*(describe|context|suite|it|test|specify)(?:\\.(?:only|skip|todo|concurrent|each\\b[^(]*\\([^)]*\\)))*\\s*\\(\\s*(['\"`])");
    private static final Pattern SETUP_BLOCK = Pattern.compile("^\\s*(beforeEach|beforeAll|afterEach|afterAll)\\s*\\(");
    /** JUnit annotations of a test method. */
    private static final Pattern TEST_ANNOTATION = Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    /** Go: TestXxx, BenchmarkXxx, FuzzXxx in _test.go files. */
    private static final Pattern GO_TEST_NAME = Pattern.compile("^(Test|Benchmark|Fuzz)([A-Z_]\\w*)?$");
    private static final Pattern PROPERTY_FUNCTION = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|readonly)\\s+)*([A-Za-z_$][\\w$]*)\\s*[:=]\\s*(?:async\\s+)?(?:function\\b|\\([^)]*\\)\\s*(?::[^=]+)?=>|[A-Za-z_$][\\w$]*\\s*=>)");

    private SymbolExtractor() {
    }

    public static List<Symbol> extract(CodeText text, String path) {
        List<Symbol> symbols = new ArrayList<>();
        boolean jsx = path.endsWith(".tsx") || path.endsWith(".jsx");
        if (path.endsWith(".go")) {
            return withContainers(withLeadingComments(text, extractGo(text, path.endsWith("_test.go"))));
        }
        List<String> code = text.code;

        for (int li = 0; li < code.size(); li++) {
            String line = code.get(li);
            if (line.isBlank()) {
                continue;
            }
            Matcher m;

            if ((m = TEST_BLOCK.matcher(line)).find()) {
                String kind = switch (m.group(1)) {
                    case "describe", "context", "suite" -> "suite";
                    default -> "test";
                };
                String raw = text.lines.get(li);
                int close = raw.indexOf(m.group(2), m.end());
                String name = raw.substring(m.end(), close < 0 ? raw.length() : close).strip();
                symbols.add(new Symbol(name.isEmpty() ? m.group(1) : name, kind, li + 1, text.statementEnd(li) + 1, null));
                continue;
            }
            if ((m = SETUP_BLOCK.matcher(line)).find()) {
                symbols.add(new Symbol(m.group(1), "setup", li + 1, text.statementEnd(li) + 1, null));
                continue;
            }
            if ((m = TYPE_DECL.matcher(line)).find()) {
                CodeText.BracePair body = text.firstPairFrom(li, m.end(), 5);
                if (body != null) {
                    String kind = m.group(1).equals("record") ? "class" : m.group(1);
                    symbols.add(new Symbol(m.group(2), kind, li + 1, body.closeLine() + 1, null));
                    continue;
                }
            }
            if ((m = TYPE_ALIAS.matcher(line)).find()) {
                symbols.add(new Symbol(m.group(1), "type", li + 1, text.statementEnd(li) + 1, null));
                continue;
            }
            if ((m = FUNCTION_DECL.matcher(line)).find()) {
                Symbol s = bodyAfterParams(text, li, m.end() - 1, m.group(1), kindFor(m.group(1), "function", jsx));
                if (s != null) {
                    symbols.add(s);
                    continue;
                }
            }
            if ((m = VARIABLE_DECL.matcher(line)).find()) {
                String name = m.group(1);
                String value = m.group(2).strip();
                int end = text.statementEnd(li);
                if (FUNCTION_VALUE.matcher(value).find() || value.isEmpty() && end > li) {
                    symbols.add(new Symbol(name, kindFor(name, "function", jsx), li + 1, end + 1, null));
                } else if (CodeText.indent(text.lines.get(li)) == 0) {
                    // top-level constant (config object, route table, limit, ...); local variables are not units
                    symbols.add(new Symbol(name, "const", li + 1, end + 1, null));
                }
                continue;
            }
            if ((m = PROPERTY_FUNCTION.matcher(line)).find() && !KEYWORDS.contains(m.group(1))) {
                int end = text.statementEnd(li);
                CodeText.BracePair body = text.firstPairFrom(li, m.end(), 1);
                if (body != null && body.closeLine() >= li) {
                    end = Math.max(end, body.closeLine());
                }
                symbols.add(new Symbol(m.group(1), "method", li + 1, end + 1, null));
                continue;
            }
            if ((m = TYPED_METHOD_DECL.matcher(line)).find() && (m.group(1).equals("void") || !KEYWORDS.contains(m.group(1)))
                    && !KEYWORDS.contains(m.group(2))) {
                Symbol s = bodyAfterParams(text, li, m.end() - 1, m.group(2), annotatedAsTest(text, li) ? "test" : "method");
                if (s != null) {
                    symbols.add(s);
                    continue;
                }
            }
            if ((m = METHOD_DECL.matcher(line)).find() && !KEYWORDS.contains(m.group(1))) {
                Symbol s = bodyAfterParams(text, li, m.end() - 1, m.group(1), "method");
                if (s != null) {
                    symbols.add(s);
                }
            }
        }
        return withContainers(withLeadingComments(text, symbols));
    }

    /** A JUnit test method: a test annotation on the declaration line or on the annotation lines right above it. */
    private static boolean annotatedAsTest(CodeText text, int line) {
        for (int i = line; i >= 0 && i >= line - 6; i--) {
            String c = text.code.get(i).strip();
            if (TEST_ANNOTATION.matcher(c).find()) {
                return true;
            }
            boolean annotationOrComment = c.startsWith("@") || (c.isEmpty() && !text.lines.get(i).isBlank());
            if (i < line && !annotationOrComment) {
                return false;
            }
        }
        return false;
    }

    /** Go has its own simple top-level grammar: funcs (with receiver = container) and struct/interface types. */
    private static List<Symbol> extractGo(CodeText text, boolean testFile) {
        List<Symbol> symbols = new ArrayList<>();
        for (int li = 0; li < text.code.size(); li++) {
            String line = text.code.get(li);
            Matcher m;
            if ((m = GO_FUNC.matcher(line)).find()) {
                int[] afterParams = text.skipParens(li, m.end() - 1);
                int[] stop = new int[2];
                if (afterParams != null) {
                    // return types may contain parens, e.g. `) (string, error) {`: take the first '{' at paren depth 0
                    text.textUntil(afterParams[0], afterParams[1], "{", 3, stop);
                    CodeText.BracePair body = stop[0] < 0 ? null : text.firstPairFrom(stop[0], stop[1], 0);
                    if (body != null) {
                        String kind = m.group(1) != null ? "method"
                                : testFile && GO_TEST_NAME.matcher(m.group(2)).matches() ? "test" : "function";
                        symbols.add(new Symbol(m.group(2), kind, li + 1, body.closeLine() + 1, m.group(1)));
                    }
                }
            } else if ((m = GO_TYPE.matcher(line)).find()) {
                CodeText.BracePair body = text.firstPairFrom(li, m.end(), 2);
                if (body != null) {
                    symbols.add(new Symbol(m.group(1), m.group(2).equals("struct") ? "class" : "interface", li + 1,
                            body.closeLine() + 1, null));
                }
            }
        }
        return symbols;
    }

    /** Doc comments, line comments and annotations right above a declaration belong to it. */
    private static List<Symbol> withLeadingComments(CodeText text, List<Symbol> symbols) {
        List<Symbol> result = new ArrayList<>(symbols.size());
        for (Symbol s : symbols) {
            int start = s.startLine(); // 1-based
            while (start > 1) {
                String raw = text.lines.get(start - 2).strip();
                boolean comment = !raw.isEmpty() && text.code.get(start - 2).isBlank();
                if (comment || raw.startsWith("@")) {
                    start--;
                } else {
                    break;
                }
            }
            result.add(start == s.startLine() ? s : new Symbol(s.name(), s.kind(), start, s.endLine(), s.container()));
        }
        return result;
    }

    /** Accepts `name(...) [: ReturnType] [throws X] {` and returns the symbol spanning the body. */
    private static Symbol bodyAfterParams(CodeText text, int line, int parenCol, String name, String kind) {
        int[] afterParams = text.skipParens(line, parenCol);
        if (afterParams == null) {
            return null;
        }
        int[] stop = new int[2];
        String between = text.textUntil(afterParams[0], afterParams[1], "{;=", 3, stop);
        if (stop[0] < 0 || text.code.get(stop[0]).charAt(stop[1]) != '{') {
            return null;
        }
        String b = between.strip();
        if (!(b.isEmpty() || b.startsWith(":") || b.startsWith("throws") || b.startsWith("where"))) {
            return null;
        }
        CodeText.BracePair body = text.firstPairFrom(stop[0], stop[1], 0);
        if (body == null) {
            return null;
        }
        return new Symbol(name, kind, line + 1, body.closeLine() + 1, null);
    }

    private static String kindFor(String name, String fallback, boolean jsx) {
        return jsx && Character.isUpperCase(name.charAt(0)) ? "component" : fallback;
    }

    /**
     * Sets `container` (e.g. the class name, or the describe block of a test) for nested symbols, and drops
     * "methods" that sit inside a function body - those are callbacks in object literals (`next: (x) => ...`),
     * not units of their own - as well as helper functions declared inside a test case.
     */
    private static List<Symbol> withContainers(List<Symbol> symbols) {
        List<Symbol> classes = symbols.stream().filter(s -> s.kind().equals("class")).toList();
        List<Symbol> suites = symbols.stream().filter(s -> s.kind().equals("suite")).toList();
        List<Symbol> result = new ArrayList<>();
        for (Symbol s : symbols) {
            if (s.container() != null) {
                result.add(s); // container already known (Go receiver)
                continue;
            }
            boolean inCallable = symbols.stream().anyMatch(o -> o != s && o.isCallable()
                    && o.startLine() < s.startLine() && o.endLine() >= s.endLine());
            boolean inTest = symbols.stream().anyMatch(o -> o != s && (o.kind().equals("test") || o.kind().equals("setup"))
                    && o.startLine() <= s.startLine() && o.endLine() >= s.endLine());
            if ((s.kind().equals("method") && inCallable) || (!s.isTestBlock() && inTest)) {
                continue;
            }
            if (s.isTestBlock()) {
                // all enclosing describe blocks, outermost first: "PlayerView › jersey number"
                String suite = suites.stream()
                        .filter(c -> c != s && c.startLine() <= s.startLine() && c.endLine() >= s.endLine())
                        .sorted(Comparator.comparingInt(Symbol::startLine))
                        .map(Symbol::name).reduce((a, b) -> a + " › " + b).orElse(null);
                if (suite != null) {
                    result.add(new Symbol(s.name(), s.kind(), s.startLine(), s.endLine(), suite));
                    continue;
                }
            }
            String container = classes.stream()
                    .filter(c -> c != s && c.startLine() < s.startLine() && c.endLine() >= s.endLine())
                    .min(Comparator.comparingInt(Symbol::span)).map(Symbol::name).orElse(null);
            result.add(new Symbol(s.name(), s.kind(), s.startLine(), s.endLine(), container));
        }
        return result;
    }

    /** The innermost symbol containing the (1-based) line, or null. */
    public static Symbol innermost(List<Symbol> symbols, int line) {
        Symbol best = null;
        for (Symbol s : symbols) {
            if (s.contains(line) && (best == null || s.span() < best.span())) {
                best = s;
            }
        }
        return best;
    }
}
