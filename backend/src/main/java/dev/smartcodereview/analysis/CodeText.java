package dev.smartcodereview.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A source file split into lines plus a "code only" copy in which string contents and comments are
 * blanked out (same length, same columns). All heuristics work on the code-only copy so that braces or
 * names inside strings/comments do not confuse them. Also computes matching brace pairs.
 *
 * Deliberately simple: no regex literals, single/double-quoted strings end at the line end (limits the
 * damage of apostrophes in JSX text), template literals with ${...} are understood.
 */
public final class CodeText {

    public record BracePair(int openLine, int openCol, int closeLine) {
    }

    public final List<String> lines;
    public final List<String> code;
    /** For each line (0-based), the brace pair that was opened at that position; see {@link #pairAt}. */
    private final List<BracePair> pairs = new ArrayList<>();

    private CodeText(List<String> lines, List<String> code) {
        this.lines = lines;
        this.code = code;
    }

    public static CodeText of(String source) {
        List<String> lines = List.of(source.replace("\r\n", "\n").split("\n", -1));
        List<String> code = new ArrayList<>(lines.size());
        CodeText ct = new CodeText(lines, code);

        enum Mode { CODE, BLOCK_COMMENT, SQ, DQ, TEMPLATE }
        Deque<Mode> modes = new ArrayDeque<>();
        modes.push(Mode.CODE);
        // brace stack entries: {line, col, isTemplateExpr(1/0)}
        Deque<int[]> braces = new ArrayDeque<>();

        for (int li = 0; li < lines.size(); li++) {
            String s = lines.get(li);
            StringBuilder out = new StringBuilder(s.length());
            if (modes.peek() == Mode.SQ || modes.peek() == Mode.DQ) {
                modes.pop(); // single-line strings never continue
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                Mode mode = modes.peek();
                switch (mode) {
                    case BLOCK_COMMENT -> {
                        if (c == '*' && next == '/') {
                            modes.pop();
                            out.append("  ");
                            i++;
                        } else {
                            out.append(' ');
                        }
                    }
                    case SQ, DQ -> {
                        if (c == '\\') {
                            out.append(next == 0 ? " " : "  ");
                            i++;
                        } else if ((mode == Mode.SQ && c == '\'') || (mode == Mode.DQ && c == '"')) {
                            modes.pop();
                            out.append(c);
                        } else {
                            out.append(' ');
                        }
                    }
                    case TEMPLATE -> {
                        if (c == '\\') {
                            out.append(next == 0 ? " " : "  ");
                            i++;
                        } else if (c == '`') {
                            modes.pop();
                            out.append(c);
                        } else if (c == '$' && next == '{') {
                            modes.push(Mode.CODE);
                            braces.push(new int[]{li, i + 1, 1});
                            out.append("${");
                            i++;
                        } else {
                            out.append(' ');
                        }
                    }
                    case CODE -> {
                        if (c == '/' && next == '/') {
                            out.append(" ".repeat(s.length() - i));
                            i = s.length();
                        } else if (c == '/' && next == '*') {
                            modes.push(Mode.BLOCK_COMMENT);
                            out.append("  ");
                            i++;
                        } else if (c == '\'') {
                            modes.push(Mode.SQ);
                            out.append(c);
                        } else if (c == '"') {
                            modes.push(Mode.DQ);
                            out.append(c);
                        } else if (c == '`') {
                            modes.push(Mode.TEMPLATE);
                            out.append(c);
                        } else if (c == '{') {
                            braces.push(new int[]{li, i, 0});
                            out.append(c);
                        } else if (c == '}') {
                            if (!braces.isEmpty()) {
                                int[] open = braces.pop();
                                if (open[2] == 1) {
                                    modes.pop(); // back into the template literal
                                } else {
                                    ct.pairs.add(new BracePair(open[0], open[1], li));
                                }
                            }
                            out.append(c);
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            code.add(out.toString());
        }
        ct.pairs.sort((a, b) -> a.openLine() != b.openLine() ? Integer.compare(a.openLine(), b.openLine())
                : Integer.compare(a.openCol(), b.openCol()));
        return ct;
    }

    /** The first brace opened at or after (line, col); search spans maxLines lines. */
    public BracePair firstPairFrom(int line, int col, int maxLines) {
        for (BracePair p : pairs) {
            if (p.openLine() > line + maxLines) {
                return null;
            }
            if (p.openLine() > line || (p.openLine() == line && p.openCol() >= col)) {
                return p;
            }
        }
        return null;
    }

    /** Position (line, col) right after the parenthesis that closes the one opened at (line, col). */
    public int[] skipParens(int line, int col) {
        int depth = 0;
        for (int li = line; li < code.size() && li < line + 40; li++) {
            String s = code.get(li);
            for (int i = (li == line ? col : 0); i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return new int[]{li, i + 1};
                    }
                }
            }
        }
        return null;
    }

    /** Text from (line, col) up to the first of the given stop characters, spanning up to maxLines. */
    public String textUntil(int line, int col, String stops, int maxLines, int[] stopPos) {
        StringBuilder sb = new StringBuilder();
        for (int li = line; li < code.size() && li <= line + maxLines; li++) {
            String s = code.get(li);
            for (int i = (li == line ? col : 0); i < s.length(); i++) {
                char c = s.charAt(i);
                if (stops.indexOf(c) >= 0) {
                    stopPos[0] = li;
                    stopPos[1] = i;
                    return sb.toString();
                }
                sb.append(c);
            }
            sb.append(' ');
        }
        stopPos[0] = -1;
        return sb.toString();
    }

    /**
     * End line of a statement starting at line (e.g. `const x = ...`): the first line where all
     * ()[]{} are balanced again and the statement visibly ends.
     */
    public int statementEnd(int line) {
        int depth = 0;
        int indent = indent(lines.get(line));
        for (int li = line; li < code.size() && li < line + 3000; li++) {
            String s = code.get(li);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                }
            }
            if (depth <= 0) {
                String t = s.strip();
                if (t.endsWith(";") || li + 1 >= code.size()) {
                    return li;
                }
                String nextLine = nextNonBlank(li + 1);
                if (nextLine == null || (indent(nextLine) <= indent && !continues(nextLine.strip()) && !continuesAfter(t))) {
                    return li;
                }
            }
        }
        return line;
    }

    private String nextNonBlank(int from) {
        for (int i = from; i < code.size(); i++) {
            if (!code.get(i).isBlank()) {
                return code.get(i);
            }
        }
        return null;
    }

    private static boolean continues(String nextTrimmed) {
        return nextTrimmed.startsWith(".") || nextTrimmed.startsWith("?") || nextTrimmed.startsWith(":")
                || nextTrimmed.startsWith("&&") || nextTrimmed.startsWith("||") || nextTrimmed.startsWith("+")
                || nextTrimmed.startsWith("=>") || nextTrimmed.startsWith(")") || nextTrimmed.startsWith("]")
                || nextTrimmed.startsWith("}");
    }

    private static boolean continuesAfter(String trimmed) {
        return trimmed.endsWith("=") || trimmed.endsWith("=>") || trimmed.endsWith(",") || trimmed.endsWith("(")
                || trimmed.endsWith("&&") || trimmed.endsWith("||") || trimmed.endsWith("?") || trimmed.endsWith(":");
    }

    static int indent(String s) {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }
}
