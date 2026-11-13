package dev.smartcodereview.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import dev.smartcodereview.model.Model.DiffLine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Pattern-based classification rules (see resources/rules/default-rules.json). */
public final class Rules {

    public record CategoryDef(String id, String label, String description, String icon) {
    }

    /**
     * symbols: files matched by `paths` are still split into functions/classes (e.g. tests into test cases).
     * extras: segments classified by this rule may additionally appear in architecture/frontend categories.
     * text / fileText: (aspect rules) one pattern must occur in the segment's code / anywhere in the file.
     */
    public record Rule(String id, String category, String group, String reason, List<Pattern> paths,
                       List<Pattern> lines, List<Pattern> require, Pattern symbol, List<String> kinds, String special,
                       boolean symbols, boolean extras, List<Pattern> text, List<Pattern> fileText) {

        boolean matchesPath(String path) {
            return !paths.isEmpty() && paths.stream().anyMatch(p -> p.matcher(path).matches());
        }

        boolean isLineRule() {
            return paths.isEmpty() && (!lines.isEmpty() || !kinds.isEmpty() || special != null);
        }
    }

    /** Lines that never make a difference: blank, braces/brackets only, comments. */
    private static final Pattern TRIVIAL = Pattern.compile(
            "^\\s*$|^\\s*[{}()\\[\\];,]*\\s*$|^\\s*(//|/\\*|\\*|\\*/|#).*$|^\\s*\\}\\s*(else\\b.*)?\\{?\\s*$");

    /** Lines that look like markup (HTML/JSX): tags, closing tags, attribute lines. */
    private static final Pattern MARKUP = Pattern.compile("^\\s*(</?[A-Za-z][\\w.:-]*|/?>|\\{/\\*|[\\w:@*\\[\\]().#-]+=\\s*[\"'{]).*$");

    public final List<CategoryDef> categories;
    public final List<Rule> rules;
    /** Frontend aspect rules: give frontend segments one extra category (first match wins). */
    public final List<Rule> aspects;
    public final String hash;

    private Rules(List<CategoryDef> categories, List<Rule> rules, List<Rule> aspects, String hash) {
        this.categories = categories;
        this.rules = rules;
        this.aspects = aspects;
        this.hash = hash;
    }

    public static Rules parse(JsonNode root, String hash) {
        List<CategoryDef> categories = new ArrayList<>();
        for (JsonNode c : root.path("categories")) {
            categories.add(new CategoryDef(c.path("id").asText(), c.path("label").asText(),
                    c.path("description").asText(""), c.path("icon").asText("code")));
        }
        return new Rules(categories, rules(root.path("rules")), rules(root.path("aspects")), hash);
    }

    private static List<Rule> rules(JsonNode array) {
        List<Rule> rules = new ArrayList<>();
        for (JsonNode r : array) {
            rules.add(new Rule(r.path("id").asText(), r.path("category").asText("quick-wins"),
                    r.path("group").asText(r.path("id").asText()), r.path("reason").asText(""),
                    patterns(r.path("paths"), true), patterns(r.path("lines"), false), patterns(r.path("require"), false),
                    r.hasNonNull("symbol") ? Pattern.compile(r.get("symbol").asText()) : null,
                    strings(r.path("kinds")), r.path("special").asText(null), r.path("symbols").asBoolean(false),
                    r.path("extras").asBoolean(true), patterns(r.path("text"), false), patterns(r.path("fileText"), false)));
        }
        return rules;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }

    private static List<Pattern> patterns(JsonNode array, boolean glob) {
        List<Pattern> result = new ArrayList<>();
        for (JsonNode n : array) {
            result.add(glob ? Pattern.compile(globToRegex(n.asText())) : Pattern.compile(n.asText()));
        }
        return result;
    }

    /** `**` spans directories, `*` and `?` stay within one path segment. A leading `** /` also matches the root. */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                    sb.append("(?:.*/)?");
                    i += 2;
                } else {
                    sb.append(".*");
                    i++;
                }
            } else if (c == '*') {
                sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** First path rule that matches the file, or null. */
    public Rule forPath(String path) {
        for (Rule r : rules) {
            if (r.matchesPath(path)) {
                return r;
            }
        }
        return null;
    }

    /**
     * First segment rule that matches, or null. A rule matches when its symbol name/kind filters fit and
     * all changed lines match its line patterns (a pure "kinds" rule needs no line patterns).
     */
    public Rule forLines(Collection<DiffLine> changed, String symbolName, String symbolKind) {
        for (Rule r : rules) {
            if (!r.isLineRule()) {
                continue;
            }
            if (r.symbol() != null && (symbolName == null || !r.symbol().matcher(symbolName).find())) {
                continue;
            }
            if (!r.kinds().isEmpty()) {
                if (symbolKind == null || !r.kinds().contains(symbolKind)) {
                    continue;
                }
                if (r.lines().isEmpty()) {
                    return r;
                }
            }
            if ("same-content".equals(r.special())) {
                if (sameContent(changed)) {
                    return r;
                }
                continue;
            }
            if (allLinesMatch(r, changed)) {
                return r;
            }
        }
        return null;
    }

    /**
     * First aspect rule whose conditions all hold (each given list needs one match): path glob, symbol kind/name,
     * a pattern in the segment's code, a pattern in the whole file, special "markup" (mostly HTML/JSX lines).
     */
    public Rule aspectFor(String path, String symbolName, String symbolKind, String segmentText, String fileText,
                          Collection<DiffLine> changed) {
        for (Rule r : aspects) {
            if (!r.paths().isEmpty() && !r.matchesPath(path)) {
                continue;
            }
            if (!r.kinds().isEmpty() && (symbolKind == null || !r.kinds().contains(symbolKind))) {
                continue;
            }
            if (r.symbol() != null && (symbolName == null || !r.symbol().matcher(symbolName).find())) {
                continue;
            }
            if (!r.text().isEmpty() && r.text().stream().noneMatch(p -> p.matcher(segmentText).find())) {
                continue;
            }
            if (!r.fileText().isEmpty() && (fileText == null || r.fileText().stream().noneMatch(p -> p.matcher(fileText).find()))) {
                continue;
            }
            if ("markup".equals(r.special()) && !mostlyMarkup(changed)) {
                continue;
            }
            return r;
        }
        return null;
    }

    private static boolean mostlyMarkup(Collection<DiffLine> changed) {
        int markup = 0, code = 0;
        for (DiffLine l : changed) {
            if (TRIVIAL.matcher(l.text()).matches()) {
                continue;
            }
            if (MARKUP.matcher(l.text()).matches()) {
                markup++;
            } else {
                code++;
            }
        }
        return markup > 0 && markup >= code;
    }

    private static boolean allLinesMatch(Rule r, Collection<DiffLine> changed) {
        boolean required = r.require().isEmpty();
        boolean anyNonTrivial = false;
        for (DiffLine l : changed) {
            String t = l.text();
            if (!required && r.require().stream().anyMatch(p -> p.matcher(t).find())) {
                required = true;
            }
            if (TRIVIAL.matcher(t).matches()) {
                continue;
            }
            anyNonTrivial = true;
            if (r.lines().stream().noneMatch(p -> p.matcher(t).matches())) {
                return false;
            }
        }
        return anyNonTrivial && required;
    }

    /** Added and removed lines are the same apart from whitespace and order (reformatting, moved code). */
    private static boolean sameContent(Collection<DiffLine> changed) {
        Map<String, Integer> balance = new HashMap<>();
        int adds = 0, dels = 0;
        for (DiffLine l : changed) {
            String norm = l.text().replaceAll("\\s+", "");
            if (norm.isEmpty()) {
                continue;
            }
            if ("add".equals(l.type())) {
                adds++;
                balance.merge(norm, 1, Integer::sum);
            } else {
                dels++;
                balance.merge(norm, -1, Integer::sum);
            }
        }
        return adds > 0 && dels > 0 && balance.values().stream().allMatch(v -> v == 0);
    }

    public static boolean isTrivial(String text) {
        return TRIVIAL.matcher(text).matches();
    }
}
