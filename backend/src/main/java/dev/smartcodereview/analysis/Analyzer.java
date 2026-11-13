package dev.smartcodereview.analysis;

import dev.smartcodereview.analysis.SymbolExtractor.Symbol;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Analysis;
import dev.smartcodereview.model.Model.Category;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.DiffLine;
import dev.smartcodereview.model.Model.DisplayLine;
import dev.smartcodereview.model.Model.Flow;
import dev.smartcodereview.model.Model.FlowEdge;
import dev.smartcodereview.model.Model.FlowStep;
import dev.smartcodereview.model.Model.Group;
import dev.smartcodereview.model.Model.Hunk;
import dev.smartcodereview.model.Model.LineRef;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a PR snapshot into review segments, categories and data flows - without AI:
 * <ol>
 *   <li>Every changed line is assigned to the innermost function/class/type that contains it (or to the
 *       hunk/module level), giving <em>segments</em>.</li>
 *   <li>Path rules and line-pattern rules (rules.json) mark segments as quick wins, tests, styles.</li>
 *   <li>Changed functions that reference each other (name match + import check) are connected; connected
 *       groups become <em>flows</em>, ordered from the entry point along the calls.</li>
 * </ol>
 */
public final class Analyzer {

    private static final int CONTEXT = 2;
    private static final int MAX_DISPLAY_LINES = 400;
    /** Methods that are called by frameworks, never explicitly; not used as call targets. */
    private static final Set<String> NOT_CALLED = Set.of("constructor", "render", "ngOnInit", "ngOnDestroy",
            "ngOnChanges", "ngAfterViewInit", "ngDoCheck", "toString", "equals", "hashCode", "main", "default",
            "GET", "POST", "PUT", "PATCH", "DELETE");
    /** Names too generic to link by mere mention; they need call syntax `name(`. */
    private static final Set<String> GENERIC = Set.of("get", "set", "run", "data", "value", "config", "index",
            "init", "load", "save", "update", "create", "remove", "handle", "handler", "process", "build", "parse",
            "format", "map", "filter", "reduce", "find", "fetch", "send", "close", "open", "start", "stop", "reset",
            "submit", "validate", "transform", "convert", "item", "items", "list", "result", "options", "props", "state");
    private static final Pattern IMPORT_START = Pattern.compile("^\\s*import\\b");
    private static final Pattern MODULE_SYNTAX = Pattern.compile("^\\s*export\\b|\\brequire\\s*\\(|\\bmodule\\.exports\\b");
    private static final Pattern ENTRY_LAYER = Pattern.compile("(?i)(component|page|route|controller|resource|endpoint|handler|view|screen|app/|pages/|api/)");
    private static final Pattern DEEP_LAYER = Pattern.compile("(?i)(repository|repo|dao|store|db|database|client|gateway|adapter|lib/|utils?/|helpers?/)");

    /** Bump when the analysis logic changes, so stored analyses are recomputed. */
    public static final int VERSION = 7;

    private final Rules rules;

    /** Identifies rules + analyzer version; stored in analysis.json to detect outdated analyses. */
    public static String fingerprint(Rules rules) {
        return rules.hash + "@" + VERSION;
    }

    public Analyzer(Rules rules) {
        this.rules = rules;
    }

    // ------------------------------------------------------------------ working types

    private static final class FileCtx {
        DiffFile diff;
        CodeText text;
        List<Symbol> symbols;
        FileView view;
        String importsText;
        Set<Integer> importLines; // 0-based
        boolean module; // uses import/export/require; plain scripts share one global scope
    }

    private static final class Work {
        String id;
        String kind; // hunk | symbol | module
        DiffFile file;
        FileCtx ctx; // null when the new source is not available
        Hunk hunk; // for hunk/module
        Symbol symbol;
        final List<DiffLine> changed = new ArrayList<>();
        final Map<DiffLine, Integer> anchors = new HashMap<>();
        Rules.Rule rule;
        String category;
        String group;
        String reason;
        final Map<Integer, List<LineRef>> refs = new HashMap<>();

        int sortLine() {
            return symbol != null ? symbol.startLine() : anchors.values().stream().min(Integer::compare).orElse(0);
        }

        String label() {
            if (symbol != null) {
                return symbol.displayName();
            }
            return file.path().substring(file.path().lastIndexOf('/') + 1);
        }
    }

    // ------------------------------------------------------------------ analysis

    public Analysis analyze(Snapshot snapshot, Map<String, String> sources) {
        List<Work> works = new ArrayList<>();
        for (DiffFile file : snapshot.files()) {
            segmentFile(file, sources.get(file.path()), works);
        }
        works.sort(Comparator.comparing((Work w) -> w.file.path()).thenComparingInt(Work::sortLine));
        for (int i = 0; i < works.size(); i++) {
            works.get(i).id = "s" + (i + 1);
        }

        for (Work w : works) {
            if (w.rule == null) {
                classifyByLines(w);
            }
        }

        List<FlowEdge> edges = findEdges(works);
        List<Flow> flows = buildFlows(works, edges);
        Set<String> inFlows = flows.stream().flatMap(f -> f.steps().stream()).map(FlowStep::segmentId).collect(Collectors.toSet());

        List<Segment> segments = new ArrayList<>();
        for (Work w : works) {
            if (w.category == null) {
                w.category = "logic";
                w.group = w.file.path();
                w.reason = inFlows.contains(w.id) ? "Teil eines Datenflusses" : "Kein Aufrufzusammenhang zu anderen Änderungen gefunden";
            }
            segments.add(toSegment(w));
        }
        return new Analysis(Model.SCHEMA_VERSION, Instant.now().toString(), snapshot.pr().headSha(), fingerprint(rules),
                segments, categories(works, flows, inFlows), flows);
    }

    private void segmentFile(DiffFile file, String source, List<Work> out) {
        Rules.Rule pathRule = rules.forPath(file.path());
        boolean withSymbols = pathRule == null && source != null && Languages.hasSymbols(file.language())
                && !"removed".equals(file.status());

        if (!withSymbols) {
            for (Hunk h : file.hunks()) {
                Work w = new Work();
                w.kind = "hunk";
                w.file = file;
                w.hunk = h;
                for (DiffLine l : h.lines()) {
                    if (l.changed()) {
                        w.changed.add(l);
                        w.anchors.put(l, FileView.anchorOf(l, h));
                    }
                }
                if (pathRule != null) {
                    applyRule(w, pathRule);
                }
                if (!w.changed.isEmpty()) {
                    out.add(w);
                }
            }
            return;
        }

        FileCtx ctx = new FileCtx();
        ctx.diff = file;
        ctx.text = CodeText.of(source);
        ctx.symbols = SymbolExtractor.extract(ctx.text, file.path());
        ctx.view = FileView.build(ctx.text.lines, file.hunks());
        indexImports(ctx);

        Map<Symbol, Work> bySymbol = new LinkedHashMap<>();
        Map<Integer, Work> moduleByHunk = new LinkedHashMap<>();
        for (Hunk h : file.hunks()) {
            Map<DiffLine, Symbol> owner = ownersOf(h, ctx.symbols);
            for (DiffLine l : h.lines()) {
                if (!l.changed()) {
                    continue;
                }
                int anchor = FileView.anchorOf(l, h);
                Symbol s = owner.get(l);
                Work w;
                if (s != null) {
                    w = bySymbol.computeIfAbsent(s, k -> {
                        Work n = new Work();
                        n.kind = "symbol";
                        n.symbol = k;
                        return n;
                    });
                } else {
                    w = moduleByHunk.computeIfAbsent(h.index(), k -> {
                        Work n = new Work();
                        n.kind = "module";
                        n.hunk = h;
                        return n;
                    });
                }
                w.file = file;
                w.ctx = ctx;
                w.changed.add(l);
                w.anchors.put(l, anchor);
            }
        }
        out.addAll(bySymbol.values());
        out.addAll(moduleByHunk.values());
    }

    /**
     * The symbol each changed line of a hunk belongs to (null = module level). Blank changed lines join the
     * neighbouring changed line, so an added method plus its empty separator line stay one segment.
     */
    private static Map<DiffLine, Symbol> ownersOf(Hunk h, List<Symbol> symbols) {
        Map<DiffLine, Symbol> owner = new HashMap<>();
        List<DiffLine> lines = h.lines();
        for (DiffLine l : lines) {
            if (l.changed() && !l.text().isBlank()) {
                int anchor = FileView.anchorOf(l, h);
                owner.put(l, "del".equals(l.type()) ? enclosingGap(symbols, anchor) : SymbolExtractor.innermost(symbols, anchor));
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            DiffLine l = lines.get(i);
            if (!l.changed() || !l.text().isBlank()) {
                continue;
            }
            DiffLine neighbour = null;
            for (int j = i - 1; j >= 0 && lines.get(j).changed() && neighbour == null; j--) {
                neighbour = lines.get(j).text().isBlank() ? null : lines.get(j);
            }
            for (int j = i + 1; j < lines.size() && lines.get(j).changed() && neighbour == null; j++) {
                neighbour = lines.get(j).text().isBlank() ? null : lines.get(j);
            }
            int anchor = FileView.anchorOf(l, h);
            owner.put(l, neighbour != null ? owner.get(neighbour)
                    : "del".equals(l.type()) ? enclosingGap(symbols, anchor) : SymbolExtractor.innermost(symbols, anchor));
        }
        return owner;
    }

    /** Deleted lines sit between line anchor-1 and anchor; they are inside a symbol only if it spans both. */
    private static Symbol enclosingGap(List<Symbol> symbols, int anchor) {
        Symbol best = null;
        for (Symbol s : symbols) {
            if (s.startLine() <= anchor - 1 && s.endLine() >= anchor && (best == null || s.span() < best.span())) {
                best = s;
            }
        }
        return best;
    }

    private static void indexImports(FileCtx ctx) {
        StringBuilder sb = new StringBuilder();
        Set<Integer> lines = new HashSet<>();
        List<String> code = ctx.text.code;
        List<String> raw = ctx.text.lines;
        for (int i = 0; i < code.size(); i++) {
            if (!IMPORT_START.matcher(code.get(i)).find()) {
                continue;
            }
            int j = i;
            boolean goBlock = code.get(i).strip().endsWith("("); // Go: import ( ... )
            while (j < code.size()) {
                lines.add(j);
                sb.append(raw.get(j)).append('\n');
                String c = code.get(j);
                if (goBlock ? j > i && c.strip().startsWith(")")
                        : c.contains(";") || c.contains(" from ") || (j == i && !c.contains("{")) || c.strip().startsWith("}")) {
                    break;
                }
                j++;
            }
            i = j;
        }
        ctx.importsText = sb.toString();
        ctx.importLines = lines;
        ctx.module = !lines.isEmpty() || code.stream().anyMatch(l -> MODULE_SYNTAX.matcher(l).find());
    }

    private void classifyByLines(Work w) {
        Rules.Rule r = rules.forLines(w.changed, w.symbol == null ? null : w.symbol.name(), w.symbol == null ? null : w.symbol.kind());
        if (r != null) {
            applyRule(w, r);
        }
    }

    private static void applyRule(Work w, Rules.Rule r) {
        w.rule = r;
        w.category = r.category();
        w.group = r.group();
        w.reason = r.reason();
    }

    // ------------------------------------------------------------------ call graph

    private List<FlowEdge> findEdges(List<Work> works) {
        List<Work> callees = works.stream()
                .filter(w -> "symbol".equals(w.kind) && (w.symbol.isCallable() || "const".equals(w.symbol.kind()))
                        && w.symbol.name().length() >= 3 && !NOT_CALLED.contains(w.symbol.name()))
                .toList();
        List<FlowEdge> edges = new ArrayList<>();
        Map<Work, List<Integer>> searchLines = new HashMap<>();
        for (Work a : works) {
            if (a.ctx != null) {
                searchLines.put(a, searchLinesOf(a, works));
            }
        }
        for (Work a : works) {
            List<Integer> lines = searchLines.get(a);
            if (lines == null) {
                continue;
            }
            for (Work b : callees) {
                if (a == b || (a.symbol != null && b.ctx == a.ctx && b.symbol.contains(a.symbol.startLine()) && b.symbol.span() >= a.symbol.span())) {
                    continue; // self or enclosing symbol
                }
                if (b.ctx != a.ctx && !importsFrom(a.ctx, b)) {
                    continue;
                }
                Integer line = firstReference(a, b, lines);
                if (line != null) {
                    edges.add(new FlowEdge(a.id, b.id, line, b.symbol.name()));
                    a.refs.computeIfAbsent(line, k -> new ArrayList<>()).add(new LineRef(b.id, b.label()));
                }
            }
        }
        return edges;
    }

    /**
     * 0-based lines of the caller to scan: for functions their body without imports and nested changed
     * symbols; for classes/types/module-level code only the changed lines (a class "uses" everything).
     */
    private static List<Integer> searchLinesOf(Work a, List<Work> works) {
        List<Integer> result = new ArrayList<>();
        if (a.symbol == null || !(a.symbol.isCallable() || "const".equals(a.symbol.kind()))) {
            for (DiffLine l : a.changed) {
                if (l.newLine() != null && !a.ctx.importLines.contains(l.newLine() - 1)) {
                    result.add(l.newLine() - 1);
                }
            }
            return result;
        }
        List<Symbol> nested = works.stream()
                .filter(o -> o != a && o.ctx == a.ctx && o.symbol != null && o.symbol.span() < a.symbol.span()
                        && a.symbol.contains(o.symbol.startLine()) && a.symbol.contains(o.symbol.endLine()))
                .map(o -> o.symbol).toList();
        for (int line = a.symbol.startLine(); line <= a.symbol.endLine(); line++) {
            int ln = line;
            if (a.ctx.importLines.contains(ln - 1) || nested.stream().anyMatch(n -> n.contains(ln))) {
                continue;
            }
            result.add(ln - 1);
        }
        return result;
    }

    /** Cross-file links need evidence: the caller imports the callee's module, class or name. */
    private static boolean importsFrom(FileCtx caller, Work callee) {
        if (callee.ctx == null) {
            return false;
        }
        // classic <script> files without modules: all top-level functions are global
        boolean script = "javascript".equals(caller.diff.language()) && "javascript".equals(callee.file.language());
        if (script && !caller.module && !callee.ctx.module) {
            return true;
        }
        String imports = caller.importsText;
        String path = callee.file.path();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        if (stem.equals("index")) {
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
            stem = dir.substring(dir.lastIndexOf('/') + 1);
        }
        if (Pattern.compile("[/'\"]" + Pattern.quote(stem) + "['\"./]").matcher(imports).find()) {
            return true;
        }
        // Go imports packages (directories), not files
        if ("go".equals(callee.file.language()) && path.contains("/")) {
            String dir = path.substring(0, path.lastIndexOf('/'));
            if (imports.contains("/" + dir.substring(dir.lastIndexOf('/') + 1) + "\"")) {
                return true;
            }
        }
        Set<String> names = new HashSet<>();
        names.add(callee.symbol.name());
        if (callee.symbol.container() != null) {
            names.add(callee.symbol.container());
        }
        for (String n : names) {
            if (Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(imports).find()) {
                return true;
            }
        }
        // Java/Kotlin: classes of the same package need no import
        if (("java".equals(caller.diff.language()) || "kotlin".equals(caller.diff.language())) && callee.symbol.container() != null) {
            Pattern cls = Pattern.compile("\\b" + Pattern.quote(callee.symbol.container()) + "\\b");
            return caller.text.code.stream().anyMatch(l -> cls.matcher(l).find());
        }
        return false;
    }

    /** 1-based line of the first reference to b in a's search lines; references on changed lines win. */
    private static Integer firstReference(Work a, Work b, List<Integer> lines) {
        String name = b.symbol.name();
        boolean generic = GENERIC.contains(name) || name.length() < 5;
        Pattern p = generic
                ? Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*(?:<[^>]*>)?\\(")
                : Pattern.compile("(?<![\\w$])<?" + Pattern.quote(name) + "(?![\\w$])(?!\\s*:(?!:))");
        Set<Integer> changedNew = a.changed.stream().map(DiffLine::newLine).filter(n -> n != null).collect(Collectors.toSet());
        Integer first = null;
        for (int idx : lines) {
            if (idx < 0 || idx >= a.ctx.text.code.size()) {
                continue;
            }
            int ln = idx + 1;
            if (b.ctx == a.ctx && ln == b.symbol.startLine()) {
                continue;
            }
            Matcher m = p.matcher(a.ctx.text.code.get(idx));
            if (m.find()) {
                if (changedNew.contains(ln)) {
                    return ln;
                }
                if (first == null) {
                    first = ln;
                }
            }
        }
        return first;
    }

    // ------------------------------------------------------------------ flows

    /**
     * Callees referenced from more than this many changed places are shared utilities (a Button, `cn`,
     * a logger): they would glue unrelated flows together, so calls to them do not form flows.
     */
    private static final int HUB_LIMIT = 5;
    /** Bigger connected groups are split into one flow per entry point. */
    private static final int MAX_FLOW_STEPS = 20;

    private List<Flow> buildFlows(List<Work> works, List<FlowEdge> allEdges) {
        Map<String, Work> byId = new HashMap<>();
        works.forEach(w -> byId.put(w.id, w));
        Map<String, Integer> callers = new HashMap<>();
        allEdges.forEach(e -> callers.merge(e.to(), 1, Integer::sum));
        List<FlowEdge> edges = allEdges.stream().filter(e -> callers.get(e.to()) <= HUB_LIMIT).toList();
        Map<String, String> parent = new HashMap<>();
        for (FlowEdge e : edges) {
            union(parent, e.from(), e.to());
        }
        Map<String, List<String>> components = new LinkedHashMap<>();
        for (Work w : works) {
            if (parent.containsKey(w.id)) {
                components.computeIfAbsent(find(parent, w.id), k -> new ArrayList<>()).add(w.id);
            }
        }

        List<Flow> flows = new ArrayList<>();
        for (List<String> members : components.values()) {
            boolean hasLogic = members.stream().anyMatch(id -> byId.get(id).category == null);
            if (members.size() < 2 || !hasLogic) {
                continue;
            }
            Set<String> memberSet = new HashSet<>(members);
            List<FlowEdge> flowEdges = edges.stream().filter(e -> memberSet.contains(e.from())).toList();
            Map<String, List<FlowEdge>> outgoing = new HashMap<>();
            Map<String, Integer> inDegree = new HashMap<>();
            for (FlowEdge e : flowEdges) {
                outgoing.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
                inDegree.merge(e.to(), 1, Integer::sum);
            }
            outgoing.values().forEach(l -> l.sort(Comparator.comparing(e -> e.line() == null ? 0 : e.line())));

            Comparator<String> entryOrder = Comparator.comparingInt((String id) -> layer(byId.get(id)))
                    .thenComparing(id -> byId.get(id).label());
            List<String> candidates = new ArrayList<>(members);
            candidates.sort(Comparator.comparingInt((String id) -> inDegree.getOrDefault(id, 0) == 0 ? 0 : 1).thenComparing(entryOrder));

            boolean split = members.size() > MAX_FLOW_STEPS;
            List<FlowStep> steps = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            for (String start : candidates) {
                if (visited.contains(start)) {
                    continue;
                }
                visit(start, null, 0, outgoing, visited, steps);
                if (split) {
                    addFlow(flows, steps, flowEdges, byId);
                    steps = new ArrayList<>();
                }
            }
            addFlow(flows, steps, flowEdges, byId);
        }
        flows.sort(Comparator.comparingInt((Flow f) -> -f.steps().size()));
        List<Flow> renumbered = new ArrayList<>();
        for (int i = 0; i < flows.size(); i++) {
            Flow f = flows.get(i);
            renumbered.add(new Flow("flow-" + (i + 1), f.title(), f.steps(), f.edges()));
        }
        return renumbered;
    }

    private static void addFlow(List<Flow> flows, List<FlowStep> steps, List<FlowEdge> edges, Map<String, Work> byId) {
        boolean hasLogic = steps.stream().anyMatch(s -> byId.get(s.segmentId()).category == null);
        if (steps.size() < 2 || !hasLogic) {
            return;
        }
        Set<String> ids = steps.stream().map(FlowStep::segmentId).collect(Collectors.toSet());
        List<FlowEdge> own = edges.stream().filter(e -> ids.contains(e.from()) && ids.contains(e.to())).toList();
        flows.add(new Flow("flow-" + (flows.size() + 1), flowTitle(steps, byId), List.copyOf(steps), own));
    }

    private static void visit(String id, String parentId, int depth, Map<String, List<FlowEdge>> outgoing,
                              Set<String> visited, List<FlowStep> steps) {
        visited.add(id);
        steps.add(new FlowStep(id, steps.size() + 1, depth, parentId));
        for (FlowEdge e : outgoing.getOrDefault(id, List.of())) {
            if (!visited.contains(e.to())) {
                visit(e.to(), id, depth + 1, outgoing, visited, steps);
            }
        }
    }

    private static String flowTitle(List<FlowStep> steps, Map<String, Work> byId) {
        List<String> chain = new ArrayList<>();
        String current = steps.getFirst().segmentId();
        chain.add(shortName(byId.get(current)));
        while (chain.size() < 4) {
            String cur = current;
            FlowStep next = steps.stream().filter(s -> cur.equals(s.parentSegmentId())).findFirst().orElse(null);
            if (next == null) {
                break;
            }
            chain.add(shortName(byId.get(next.segmentId())));
            current = next.segmentId();
        }
        String title = String.join(" → ", chain);
        int rest = steps.size() - chain.size();
        return rest > 0 ? title + " (+" + rest + ")" : title;
    }

    private static String shortName(Work w) {
        return w.symbol != null ? w.symbol.name() : w.label();
    }

    private static int layer(Work w) {
        String path = w.file.path();
        if (ENTRY_LAYER.matcher(path).find() || (w.symbol != null && "component".equals(w.symbol.kind()))) {
            return 0;
        }
        return DEEP_LAYER.matcher(path).find() ? 2 : 1;
    }

    private static String find(Map<String, String> parent, String x) {
        parent.putIfAbsent(x, x);
        String p = parent.get(x);
        if (!p.equals(x)) {
            p = find(parent, p);
            parent.put(x, p);
        }
        return p;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(rb, ra);
        }
    }

    // ------------------------------------------------------------------ output

    private Segment toSegment(Work w) {
        Set<String> own = w.changed.stream().map(DiffLine::key).collect(Collectors.toCollection(LinkedHashSet::new));
        List<DisplayLine> lines;
        if (w.ctx == null) {
            lines = new ArrayList<>();
            for (DiffLine l : w.hunk.lines()) {
                lines.add(new DisplayLine(l.type(), l.oldLine(), l.newLine(), l.key(), l.text(), l.changed(), true, null, null));
            }
        } else if (w.symbol != null) {
            lines = render(w, w.symbol.startLine(), w.symbol.endLine(), own);
        } else {
            int min = w.anchors.values().stream().min(Integer::compare).orElse(1);
            int max = w.anchors.values().stream().max(Integer::compare).orElse(1);
            lines = render(w, Math.max(1, min - 3), max + 3, own);
        }
        if (lines.size() > MAX_DISPLAY_LINES) {
            int hidden = lines.size() - MAX_DISPLAY_LINES;
            lines = new ArrayList<>(lines.subList(0, MAX_DISPLAY_LINES));
            lines.add(new DisplayLine("gap", null, null, null, hidden + " weitere Zeilen (zu gross für die Anzeige)", false, false, hidden, null));
        }
        Symbol s = w.symbol;
        return new Segment(w.id, w.file.path(), w.file.language(), w.kind,
                s == null ? null : s.displayName(), s == null ? null : s.kind(),
                s == null ? null : s.startLine(), s == null ? null : s.endLine(),
                w.category, w.group, w.rule == null ? null : w.rule.id(), w.reason, List.copyOf(own), lines);
    }

    /** Lines of the new file in [start, end] with deletions woven in; long unchanged stretches collapse to gaps. */
    private static List<DisplayLine> render(Work w, int start, int end, Set<String> own) {
        List<FileView.Entry> es = w.ctx.view.entries.stream().filter(e -> e.inRange(start, end)).toList();
        boolean[] keep = new boolean[es.size()];
        for (int i = 0; i < es.size(); i++) {
            FileView.Entry e = es.get(i);
            boolean important = (e.changed() && own.contains(e.key())) || (e.newLine() != null && w.refs.containsKey(e.newLine()));
            if (important) {
                for (int j = Math.max(0, i - CONTEXT); j <= Math.min(es.size() - 1, i + CONTEXT); j++) {
                    keep[j] = true;
                }
            }
        }
        if (es.size() > 0) {
            keep[0] = true;
            keep[es.size() - 1] = true;
        }
        List<DisplayLine> out = new ArrayList<>();
        int i = 0;
        while (i < es.size()) {
            if (keep[i]) {
                FileView.Entry e = es.get(i);
                out.add(new DisplayLine(e.type(), e.oldLine(), e.newLine(), e.key(), e.text(),
                        e.changed() && own.contains(e.key()), e.inDiff(), null,
                        e.newLine() == null ? null : w.refs.get(e.newLine())));
                i++;
                continue;
            }
            int j = i;
            while (j < es.size() && !keep[j]) {
                j++;
            }
            if (j - i < 3) {
                for (int k = i; k < j; k++) {
                    FileView.Entry e = es.get(k);
                    out.add(new DisplayLine(e.type(), e.oldLine(), e.newLine(), e.key(), e.text(),
                            e.changed() && own.contains(e.key()), e.inDiff(), null, null));
                }
            } else {
                out.add(new DisplayLine("gap", null, null, null, null, false, false, j - i, null));
            }
            i = j;
        }
        return out;
    }

    private List<Category> categories(List<Work> works, List<Flow> flows, Set<String> inFlows) {
        List<Category> result = new ArrayList<>();
        for (Rules.CategoryDef def : rules.categories) {
            List<Group> groups = new ArrayList<>();
            if (def.id().equals("flows")) {
                for (Flow f : flows) {
                    groups.add(new Group("flows/" + f.id(), f.title(), f.steps().size() + " Schritte", f.id(),
                            f.steps().stream().map(FlowStep::segmentId).toList()));
                }
            } else {
                Map<String, List<String>> byGroup = new LinkedHashMap<>();
                List<String> groupOrder = rules.rules.stream().map(Rules.Rule::group).distinct().toList();
                works.stream()
                        .filter(w -> def.id().equals(w.category) && !(def.id().equals("logic") && inFlows.contains(w.id)))
                        .sorted(Comparator.comparingInt((Work w) -> groupOrder.indexOf(w.group) < 0 ? 999 : groupOrder.indexOf(w.group)))
                        .forEach(w -> byGroup.computeIfAbsent(w.group, k -> new ArrayList<>()).add(w.id));
                byGroup.forEach((label, ids) -> groups.add(new Group(def.id() + "/" + slug(label), label, null, null, ids)));
            }
            if (!groups.isEmpty()) {
                result.add(new Category(def.id(), def.label(), def.description(), def.icon(), groups));
            }
        }
        return result;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9äöü/._-]+", "-").replaceAll("(^-|-$)", "");
    }
}
