package dev.smartcodereview.analysis;

import dev.smartcodereview.analysis.SymbolExtractor.Symbol;
import dev.smartcodereview.analysis.category.CategoryContext;
import dev.smartcodereview.analysis.category.CategoryInfo;
import dev.smartcodereview.analysis.category.CategoryModule;
import dev.smartcodereview.analysis.category.SegmentFacts;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Analysis;
import dev.smartcodereview.model.Model.ApiOperation;
import dev.smartcodereview.model.Model.ArchitectureInfo;
import dev.smartcodereview.model.Model.Category;
import dev.smartcodereview.model.Model.Component;
import dev.smartcodereview.model.Model.ComponentEdge;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.DiffLine;
import dev.smartcodereview.model.Model.DisplayLine;
import dev.smartcodereview.model.Model.Flow;
import dev.smartcodereview.model.Model.FlowEdge;
import dev.smartcodereview.model.Model.FlowStep;
import dev.smartcodereview.model.Model.Group;
import dev.smartcodereview.model.Model.Hunk;
import dev.smartcodereview.model.Model.LineRef;
import dev.smartcodereview.model.Model.RepoStructure;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;
import dev.smartcodereview.model.Model.TestCoverage;
import dev.smartcodereview.model.Model.TestInfo;
import dev.smartcodereview.model.Model.TestSubject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
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
 *   <li>Changed functions that reference each other (name match + import check) are connected; every entry
 *       point gets a <em>flow</em> with everything it reaches, in call order. A function reached from two entry
 *       points is part of both flows.</li>
 *   <li>Changed test cases are linked to the changed code they reach through the same call graph.</li>
 *   <li>Frontend REST calls are matched to backend endpoints (http edges), so a flow can run from a component
 *       through the API client into the backend.</li>
 *   <li>Ports &amp; adapters projects get a layer per segment (domain, use case, application, adapter); frontend
 *       segments get an aspect (routing, state, API, forms, components, templates). Both are extra categories.</li>
 *   <li>Every segment lists its <em>places</em> (all groups that show it); each place needs its own "ok".</li>
 *   <li>Changed files are combined into <em>components</em> with the calls between them (overview diagram).</li>
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
    private static final Pattern ENTRY_LAYER = Pattern.compile(
            "(?i)(component|page|route|controller|resource|endpoint|handler|view|screen|listener|consumer|messaging/|app/|pages/|api/)");
    private static final Pattern DEEP_LAYER = Pattern.compile("(?i)(repository|repo|dao|store|db|database|client|gateway|adapter|lib/|utils?/|helpers?/)");
    private static final Pattern API_LAYER = Pattern.compile("(?i)(resource|controller|endpoint|handler|route|/api/|/rest/|/web/)");
    private static final Pattern DOMAIN_DIR = Pattern.compile("(?i)/(domain|model|models|entity|entities)/");
    /** Bump when the analysis logic changes, so stored analyses are recomputed. */
    public static final int VERSION = 14;

    /** Asynchronous / framework entry points: shown as the trigger in the title of a flow that starts there. */
    private static final List<Map.Entry<Pattern, String>> TRIGGERS = List.of(
            Map.entry(Pattern.compile("@JmsListener\\b"), "JMS-Listener"),
            Map.entry(Pattern.compile("@KafkaListener\\b"), "Kafka-Listener"),
            Map.entry(Pattern.compile("@RabbitListener\\b"), "RabbitMQ-Listener"),
            Map.entry(Pattern.compile("@SqsListener\\b"), "SQS-Listener"),
            Map.entry(Pattern.compile("@Incoming\\b"), "Messaging (@Incoming)"),
            Map.entry(Pattern.compile("@(Transactional)?EventListener\\b"), "Event-Listener"),
            Map.entry(Pattern.compile("@Scheduled\\b"), "Zeitgesteuert (@Scheduled)"));
    /** A method call on some receiver: `this.api.updatePlayer(`, `inject(X).getPlayer(`, `api?.list(`. */
    private static final Pattern MEMBER_CALL = Pattern.compile("(?:([\\w$]+)|[)\\]])\\s*\\??\\.\\s*([\\w$]+)\\s*(?:<[^()]*?>)?\\s*\\(");
    /** A class that implements an interface generated from an OpenAPI contract (`implements PlayersApi`). */
    private static final Pattern IMPLEMENTS_API = Pattern.compile("(\\bimplements\\b|\\)\\s*:|\\bclass\\s+\\w+\\s*:)[^{]*\\b\\w+Api(Delegate)?\\b");
    /** Frontend imports that point at a generated API client (used when no contract file is known). */
    private static final Pattern GENERATED_CLIENT_IMPORT = Pattern.compile("['\"][^'\"]*(/gen/|/generated/|openapi|/api['\"/])");
    /** Table names in migrations: Liquibase `tableName: x` / `tableName="x"`, SQL `create/alter/... table x`. */
    private static final List<Pattern> TABLE_IN_MIGRATION = List.of(
            Pattern.compile("tableName['\"]?\\s*[:=]\\s*['\"]?(?:[\\w]+\\.)?([\\w]+)"),
            Pattern.compile("(?i)\\b(?:create|alter|drop|truncate)\\s+table\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?[\\[\"`]?(?:\\w+[\\]\"`]?\\.[\\[\"`]?)?(\\w+)"),
            Pattern.compile("(?i)\\binsert\\s+into\\s+[\\[\"`]?(?:\\w+[\\]\"`]?\\.[\\[\"`]?)?(\\w+)"),
            Pattern.compile("(?i)^\\s*update\\s+[\\[\"`]?(?:\\w+[\\]\"`]?\\.[\\[\"`]?)?(\\w+)\\s+set\\b"));
    private static final Pattern ENTITY = Pattern.compile("@Entity\\b");
    private static final Pattern TABLE_ANNOTATION = Pattern.compile("@Table\\s*\\([^)]*?name\\s*=\\s*\"(?:\\w+\\.)?(\\w+)\"|@Table\\s*\\(\\s*\"(\\w+)\"");

    private final Rules rules;
    private final List<CategoryModule> modules;

    /** Identifies rules + analyzer version; stored in analysis.json to detect outdated analyses. */
    public static String fingerprint(Rules rules) {
        return rules.hash + "@" + VERSION;
    }

    public Analyzer(Rules rules) {
        this.rules = rules;
        this.modules = CategoryModules.build(rules, List.of());
    }

    // ------------------------------------------------------------------ working types

    private static final class FileCtx {
        DiffFile diff;
        CodeText text;
        List<Symbol> symbols;
        FileView view;
        String source;
        String importsText;
        Set<Integer> importLines; // 0-based
        boolean module; // uses import/export/require; plain scripts share one global scope
        /** importsFrom results by callee (path, name, container): the same pair is asked once per segment pair */
        final Map<String, Boolean> imports = new HashMap<>();
        /** whether the code mentions a class name (Java/Kotlin same-package check) */
        final Map<String, Boolean> mentions = new HashMap<>();
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
        boolean frontend;
        String layer; // ports & adapters layer, null if none
        Rules.Rule aspect; // frontend aspect (extra category), null if none
        List<String> concerns = List.of(); // cross-cutting categories (security, messaging, ...)
        String trigger; // how a flow starting here is triggered (endpoint, JMS listener, ...), null if unknown
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
        return analyze(snapshot, sources, (RepoStructure) null);
    }

    /** directories: all directories of the repository (to recognise ports &amp; adapters); null = only the changed ones. */
    public Analysis analyze(Snapshot snapshot, Map<String, String> sources, Collection<String> directories) {
        return analyze(snapshot, sources, directories == null ? null : new RepoStructure(List.copyOf(directories), false));
    }

    /**
     * structure: directories of the repository (architecture) and the operations of its API contracts (links
     * through generated clients); null = only what the diff shows.
     */
    public Analysis analyze(Snapshot snapshot, Map<String, String> sources, RepoStructure structure) {
        Collection<String> directories = structure == null ? null : structure.directories();
        List<ApiOperation> operations = structure == null || structure.operations() == null ? List.of() : structure.operations();
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
            if (isTest(w)) {
                w.group = w.file.path(); // tests are listed per file
                if (w.symbol != null && w.symbol.kind().equals("test")) {
                    w.reason = "Testfall";
                }
            }
        }

        Set<String> dirs = new LinkedHashSet<>(directories == null ? List.of() : directories);
        for (DiffFile f : snapshot.files()) {
            for (String d = dirOf(f.path()); !d.isEmpty(); d = dirOf(d)) {
                dirs.add(d);
            }
        }
        List<String> evidence = Structure.hexagonalEvidence(dirs);
        for (Work w : works) {
            String source = sources.get(w.file.path());
            w.frontend = Structure.isFrontend(w.file.path(), source);
            if (w.ctx != null && w.symbol != null && w.symbol.isCallable()) {
                String head = String.join("\n", head(w.ctx.text.lines, w.symbol));
                TRIGGERS.stream().filter(t -> t.getKey().matcher(head).find()).findFirst().ifPresent(t -> w.trigger = t.getValue());
            }
            boolean extras = (w.rule == null || w.rule.extras()) && !isTest(w);
            if (!extras) {
                continue;
            }
            if (!evidence.isEmpty() && !w.frontend && Languages.hasSymbols(w.file.language())) {
                w.layer = Structure.layerOf(w.file.path());
            }
            if (w.frontend) {
                w.aspect = rules.aspectFor(w.file.path(), w.symbol == null ? null : w.symbol.name(),
                        w.symbol == null ? null : w.symbol.kind(), segmentText(w), source, w.changed);
            }
            w.concerns = rules.concernsFor(w.file.path(), w.frontend, w.symbol == null ? null : w.symbol.name(),
                    w.symbol == null ? null : w.symbol.kind(), concernText(w), source);
        }

        List<FlowEdge> edges = findEdges(works);
        edges.addAll(findHttpEdges(works, edges, operations));
        linkMigrations(works);
        List<Flow> flows = buildFlows(works, edges);
        Set<String> inFlows = flows.stream().flatMap(f -> f.steps().stream()).map(FlowStep::segmentId).collect(Collectors.toSet());
        for (Work w : works) {
            if (w.category == null) {
                w.category = "logic";
                w.group = w.file.path();
                w.reason = inFlows.contains(w.id) ? "Teil eines Datenflusses" : "Kein Aufrufzusammenhang zu anderen Änderungen gefunden";
            }
        }
        List<Category> categories = categories(works, flows, inFlows);
        Map<String, List<String>> places = new HashMap<>();
        for (Category c : categories) {
            for (Group g : c.groups()) {
                for (String id : g.segmentIds()) {
                    List<String> list = places.computeIfAbsent(id, k -> new ArrayList<>());
                    if (!list.contains(g.id())) {
                        list.add(g.id());
                    }
                }
            }
        }
        List<TestInfo> tests = tests(works, edges, snapshot.files());
        Map<String, List<String>> testedBy = new HashMap<>();
        for (TestInfo t : tests) {
            t.covers().forEach(c -> testedBy.computeIfAbsent(c.segmentId(), k -> new ArrayList<>()).add(t.segmentId()));
        }

        List<Segment> segments = new ArrayList<>();
        for (Work w : works) {
            segments.add(toSegment(w, places.getOrDefault(w.id, List.of()), testedBy.getOrDefault(w.id, List.of())));
        }
        ArchitectureInfo architecture = new ArchitectureInfo(Structure.styleOf(evidence), evidence);
        Map<String, String> componentOf = new HashMap<>();
        List<Component> components = components(works, !evidence.isEmpty(), componentOf);
        return new Analysis(Model.SCHEMA_VERSION, Instant.now().toString(), snapshot.pr().headSha(), fingerprint(rules),
                segments, categories, flows, tests, architecture, components, componentEdges(edges, componentOf));
    }

    /** The code of a segment as text: the whole symbol, or the changed lines of a hunk/module segment. */
    private static String segmentText(Work w) {
        if (w.ctx != null && w.symbol != null) {
            return String.join("\n", w.ctx.text.lines.subList(w.symbol.startLine() - 1, Math.min(w.symbol.endLine(), w.ctx.text.lines.size())));
        }
        return w.changed.stream().map(DiffLine::text).collect(Collectors.joining("\n"));
    }

    /**
     * The code a concern looks at: functions as a whole (with their annotations); for classes, types and
     * module code only the changed lines plus the declaration (a class "contains" everything).
     */
    private static String concernText(Work w) {
        if (w.ctx != null && w.symbol != null && (w.symbol.isCallable() || "const".equals(w.symbol.kind()))) {
            return segmentText(w);
        }
        String changed = w.changed.stream().map(DiffLine::text).collect(Collectors.joining("\n"));
        if (w.ctx != null && w.symbol != null) {
            return String.join("\n", head(w.ctx.text.lines, w.symbol)) + "\n" + changed;
        }
        return changed;
    }

    private static boolean isTest(Work w) {
        return "tests".equals(w.category);
    }

    private static boolean isTestCase(Work w) {
        return isTest(w) && w.symbol != null && w.symbol.kind().equals("test");
    }

    private void segmentFile(DiffFile file, String source, List<Work> out) {
        Rules.Rule pathRule = rules.forPath(file.path());
        boolean withSymbols = (pathRule == null || pathRule.symbols()) && source != null
                && Languages.hasSymbols(file.language()) && !"removed".equals(file.status());

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
        ctx.source = source;
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
        if (pathRule != null) {
            bySymbol.values().forEach(w -> applyRule(w, pathRule));
            moduleByHunk.values().forEach(w -> applyRule(w, pathRule));
        }
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
        Rules.Rule r = rules.forLines(w.changed, w.symbol == null ? null : w.symbol.name(), w.symbol == null ? null : w.symbol.kind(),
                w.ctx == null ? null : concernText(w), w.ctx == null ? null : w.ctx.source);
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
                        && !w.symbol.isTestBlock() && w.symbol.name().length() >= 3 && !NOT_CALLED.contains(w.symbol.name()))
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
                if (isTest(b) && !isTest(a)) {
                    continue; // production code does not call test helpers
                }
                if (b.ctx != a.ctx && !importsFrom(a.ctx, b)) {
                    continue;
                }
                Integer line = firstReference(a, b, lines);
                if (line != null) {
                    edges.add(new FlowEdge(a.id, b.id, line, b.symbol.name(), "call"));
                    a.refs.computeIfAbsent(line, k -> new ArrayList<>()).add(new LineRef(b.id, b.label()));
                }
            }
        }
        return edges;
    }

    /**
     * REST across the stack: HTTP calls in one segment matched to an endpoint in another. Calls: a literal URL
     * (`http.put('/api/x')`, `fetch`) or a generated client method named like an operationId of the API contract
     * (`this.playersService.updatePlayer(...)`). Endpoints: JAX-RS/Spring annotations, Next.js route files,
     * Express/Go route registrations, and methods named like an operationId in a class implementing a generated
     * `...Api` interface. Changed lines of the contract itself link to both sides.
     */
    private List<FlowEdge> findHttpEdges(List<Work> works, List<FlowEdge> callEdges, List<ApiOperation> operations) {
        record Target(Work work, Http.Endpoint endpoint, ApiOperation operation) {
        }
        Map<String, List<ApiOperation>> byOperationId = new HashMap<>();
        operations.forEach(o -> byOperationId.computeIfAbsent(o.operationId(), k -> new ArrayList<>()).add(o));
        List<Target> targets = new ArrayList<>();
        // methods of classes implementing generated API interfaces, by name (works without a contract, too)
        Map<String, List<Work>> implementations = new HashMap<>();
        for (Work b : works) {
            if (b.ctx == null || isTest(b)) {
                continue;
            }
            List<String> raw = b.ctx.text.lines;
            if (b.symbol != null && b.symbol.isCallable()) {
                Http.Endpoint e = Http.annotated(head(raw, b.symbol), classHead(b));
                if (e == null) {
                    e = Http.fileRoute(b.file.path(), b.symbol.name());
                }
                if (e != null) {
                    targets.add(new Target(b, e, null));
                } else if (!b.frontend && IMPLEMENTS_API.matcher(String.join(" ", classHead(b))).find()) {
                    implementations.computeIfAbsent(b.symbol.name(), k -> new ArrayList<>()).add(b);
                    for (ApiOperation o : byOperationId.getOrDefault(b.symbol.name(), List.of())) {
                        targets.add(new Target(b, new Http.Endpoint(o.method(), o.path()), o));
                        b.trigger = b.trigger != null ? b.trigger : o.method() + " " + o.path();
                    }
                }
                if (e != null && b.trigger == null) {
                    b.trigger = e.label();
                }
            }
            int from = b.symbol != null ? b.symbol.startLine() - 1 : b.anchors.values().stream().min(Integer::compare).orElse(1) - 1;
            int to = b.symbol != null ? b.symbol.endLine() - 1 : b.anchors.values().stream().max(Integer::compare).orElse(1) - 1;
            for (int i = Math.max(0, from); i <= Math.min(to, raw.size() - 1); i++) {
                for (Http.Endpoint e : Http.registered(raw.get(i))) {
                    targets.add(new Target(b, e, null));
                }
            }
        }
        List<FlowEdge> edges = new ArrayList<>();
        Map<ApiOperation, Set<Work>> callers = new HashMap<>();
        Map<ApiOperation, Set<Work>> implementers = new HashMap<>();
        targets.stream().filter(t -> t.operation() != null)
                .forEach(t -> implementers.computeIfAbsent(t.operation(), k -> new LinkedHashSet<>()).add(t.work()));
        Set<String> seen = new HashSet<>();
        Set<String> localCalls = callEdges.stream().map(e -> e.from() + ">" + e.via()).collect(Collectors.toSet());
        for (Work a : works) {
            if (a.ctx == null || isTest(a)) {
                continue;
            }
            List<Integer> lines = searchLinesOf(a, works);
            List<Http.Call> calls = new ArrayList<>(Http.calls(a.ctx.text.lines, lines));
            Map<Http.Call, ApiOperation> viaOperation = new HashMap<>();
            if (a.frontend) {
                boolean generatedClient = GENERATED_CLIENT_IMPORT.matcher(a.ctx.importsText).find();
                for (int idx : lines) {
                    if (idx < 0 || idx >= a.ctx.text.code.size()) {
                        continue;
                    }
                    Matcher m = MEMBER_CALL.matcher(a.ctx.text.code.get(idx));
                    while (m.find()) {
                        String name = m.group(2);
                        if ("this".equals(m.group(1)) || localCalls.contains(a.id + ">" + name)) {
                            continue; // own method, or a changed wrapper of the same name (that one calls the API)
                        }
                        for (ApiOperation o : byOperationId.getOrDefault(name, List.of())) {
                            Http.Call call = new Http.Call(o.method(), Http.normalisePath("{}" + o.path()), idx + 1);
                            calls.add(call);
                            viaOperation.put(call, o);
                            callers.computeIfAbsent(o, k -> new LinkedHashSet<>()).add(a);
                        }
                        // no contract known: a generated client method straight to the implementing method
                        if (!byOperationId.containsKey(name) && generatedClient) {
                            for (Work b : implementations.getOrDefault(name, List.of())) {
                                if (seen.add(a.id + ">" + b.id)) {
                                    edges.add(new FlowEdge(a.id, b.id, idx + 1, name + "()", "http"));
                                    a.refs.computeIfAbsent(idx + 1, k -> new ArrayList<>()).add(new LineRef(b.id, name + "() → " + b.label()));
                                }
                            }
                        }
                    }
                }
            }
            for (Http.Call call : calls) {
                ApiOperation op = viaOperation.get(call);
                for (Target t : targets) {
                    if (t.work() != a && t.work().file != a.file && Http.matches(call, t.endpoint()) && seen.add(a.id + ">" + t.work().id)) {
                        String via = t.endpoint().label() + (op != null ? " (" + op.operationId() + ")" : "");
                        edges.add(new FlowEdge(a.id, t.work().id, call.line(), via, "http"));
                        a.refs.computeIfAbsent(call.line(), k -> new ArrayList<>())
                                .add(new LineRef(t.work().id, via + " → " + t.work().label()));
                    }
                }
            }
        }
        linkContracts(works, callers, implementers);
        markApiCallers(works, edges);
        return edges;
    }

    /** Changed lines of an API contract jump to the (changed) callers and implementations of that operation. */
    private static void linkContracts(List<Work> works, Map<ApiOperation, Set<Work>> callers, Map<ApiOperation, Set<Work>> implementers) {
        Set<ApiOperation> all = new LinkedHashSet<>(callers.keySet());
        all.addAll(implementers.keySet());
        for (Work w : works) {
            for (ApiOperation o : all) {
                if (!o.file().equals(w.file.path())) {
                    continue;
                }
                Integer line = w.changed.stream().map(DiffLine::newLine)
                        .filter(n -> n != null && n >= o.line() && n <= o.endLine()).findFirst().orElse(null);
                if (line == null) {
                    continue;
                }
                List<LineRef> refs = w.refs.computeIfAbsent(line, k -> new ArrayList<>());
                implementers.getOrDefault(o, Set.of()).forEach(b -> refs.add(new LineRef(b.id, o.operationId() + " → " + b.label())));
                callers.getOrDefault(o, Set.of()).forEach(a -> refs.add(new LineRef(a.id, "aufgerufen von " + a.label())));
            }
        }
    }

    /** Frontend code that calls the backend belongs to "API-Anbindung" unless an earlier aspect (routing, state, forms) matched. */
    private void markApiCallers(List<Work> works, List<FlowEdge> httpEdges) {
        Rules.Rule api = rules.aspects.stream().filter(r -> r.category().equals("fe-api")).findFirst().orElse(null);
        if (api == null) {
            return;
        }
        Set<String> callers = httpEdges.stream().map(FlowEdge::from).collect(Collectors.toSet());
        for (Work w : works) {
            boolean extras = (w.rule == null || w.rule.extras()) && !isTest(w);
            if (w.frontend && extras && callers.contains(w.id)
                    && (w.aspect == null || rules.aspects.indexOf(w.aspect) > rules.aspects.indexOf(api))) {
                w.aspect = api;
            }
        }
    }

    /**
     * Schema changes and the entities they affect are one review unit: migration hunks (Liquibase/Flyway/SQL)
     * and changed JPA entities that name the same table share a group "Tabelle x" and link to each other.
     */
    private static void linkMigrations(List<Work> works) {
        Map<String, List<Work>> entities = new HashMap<>();
        for (Work w : works) {
            if (w.ctx == null || w.ctx.source == null || isTest(w) || !ENTITY.matcher(w.ctx.source).find()) {
                continue;
            }
            Matcher t = TABLE_ANNOTATION.matcher(w.ctx.source);
            String table = t.find() ? (t.group(1) != null ? t.group(1) : t.group(2))
                    : w.ctx.symbols.stream().filter(s -> s.kind().equals("class")).map(Symbol::name).findFirst().orElse(null);
            if (table != null) {
                entities.computeIfAbsent(tableKey(table), k -> new ArrayList<>()).add(w);
            }
        }
        for (Work m : works) {
            if (!"persistence".equals(m.category) || m.symbol != null || m.hunk == null) {
                continue;
            }
            String first = null;
            for (DiffLine l : m.hunk.lines()) {
                for (Pattern p : TABLE_IN_MIGRATION) {
                    Matcher x = p.matcher(l.text());
                    if (!x.find()) {
                        continue;
                    }
                    String table = x.group(1);
                    first = first == null ? table : first;
                    for (Work e : entities.getOrDefault(tableKey(table), List.of())) {
                        int line = l.newLine() != null ? l.newLine() : m.anchors.values().stream().min(Integer::compare).orElse(1);
                        addRef(m, line, new LineRef(e.id, "Entity " + e.label()));
                        int own = e.changed.stream().map(DiffLine::newLine).filter(n -> n != null).findFirst().orElse(e.sortLine());
                        addRef(e, own, new LineRef(m.id, "Migration " + m.file.path().substring(m.file.path().lastIndexOf('/') + 1)));
                        if ("persistence".equals(e.category)) {
                            e.group = "Tabelle " + table;
                        }
                    }
                }
            }
            if (first != null) {
                m.group = "Tabelle " + first;
            }
        }
    }

    private static void addRef(Work w, int line, LineRef ref) {
        List<LineRef> refs = w.refs.computeIfAbsent(line, k -> new ArrayList<>());
        if (refs.stream().noneMatch(x -> x.segmentId().equals(ref.segmentId()))) {
            refs.add(ref);
        }
    }

    /** PlayerTeam, player_team and "PLAYER_TEAM" name the same table. */
    private static String tableKey(String name) {
        return name.replace("_", "").replace("\"", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Annotation and declaration lines of a symbol (up to the line that opens its body). */
    private static List<String> head(List<String> raw, Symbol s) {
        List<String> result = new ArrayList<>();
        for (int i = s.startLine() - 1; i < Math.min(raw.size(), s.endLine()); i++) {
            result.add(raw.get(i));
            if (raw.get(i).contains("{")) {
                break;
            }
        }
        return result;
    }

    private static List<String> classHead(Work w) {
        String container = w.symbol.container();
        if (container == null) {
            return List.of();
        }
        return w.ctx.symbols.stream().filter(s -> s.kind().equals("class") && s.name().equals(container)).findFirst()
                .map(s -> head(w.ctx.text.lines, s)).orElse(List.of());
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
        // asked for every pair of segments: answer once per caller file and callee (whole repositories!)
        String key = callee.file.path() + "\u0000" + callee.symbol.name() + "\u0000" + callee.symbol.container();
        Boolean known = caller.imports.get(key);
        if (known == null) {
            known = importsFromUncached(caller, callee);
            caller.imports.put(key, known);
        }
        return known;
    }

    private static boolean importsFromUncached(FileCtx caller, Work callee) {
        // classic <script> files without modules: all top-level functions are global
        boolean script = "javascript".equals(caller.diff.language()) && "javascript".equals(callee.file.language());
        if (script && !caller.module && !callee.ctx.module) {
            return true;
        }
        String imports = caller.importsText;
        String path = callee.file.path();
        // Go: files of the same directory form one package and need no import
        if ("go".equals(caller.diff.language()) && "go".equals(callee.file.language()) && dirOf(caller.diff.path()).equals(dirOf(path))) {
            return true;
        }
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
            return caller.mentions.computeIfAbsent(callee.symbol.container(), name -> {
                Pattern cls = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
                return caller.text.code.stream().anyMatch(l -> cls.matcher(l).find());
            });
        }
        return false;
    }

    private static String dirOf(String path) {
        return path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
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
    /**
     * In bigger connected groups (e.g. a whole new app in one PR) every entry point would reach almost
     * everything; there each function is shown in one flow only (the first entry point that reaches it).
     */
    private static final int MAX_OVERLAP_STEPS = 20;
    /** How deep tests are followed into the code (test -> a -> b -> c). */
    private static final int MAX_TEST_DEPTH = 4;

    /**
     * Every entry point (a changed function no other changed function calls) gets its own flow with all it
     * reaches. Functions reached from several entry points are therefore part of several flows (only in
     * groups up to MAX_OVERLAP_STEPS); cycles without an entry point start at the best candidate (layer heuristic).
     */
    private List<Flow> buildFlows(List<Work> works, List<FlowEdge> allEdges) {
        Map<String, Work> byId = new HashMap<>();
        works.forEach(w -> byId.put(w.id, w));
        // tests are linked to the code separately (see tests()); they never form flows
        List<FlowEdge> codeEdges = allEdges.stream()
                .filter(e -> !isTest(byId.get(e.from())) && !isTest(byId.get(e.to()))).toList();
        Map<String, Integer> callers = new HashMap<>();
        codeEdges.forEach(e -> callers.merge(e.to(), 1, Integer::sum));
        List<FlowEdge> edges = codeEdges.stream().filter(e -> callers.get(e.to()) <= HUB_LIMIT).toList();
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

            Set<String> covered = new HashSet<>();
            boolean overlap = members.size() <= MAX_OVERLAP_STEPS;
            for (String start : candidates) {
                if (covered.contains(start)) {
                    continue;
                }
                List<FlowStep> steps = new ArrayList<>();
                visit(start, null, 0, outgoing, overlap ? new HashSet<>() : covered, steps);
                steps.forEach(st -> covered.add(st.segmentId()));
                addFlow(flows, steps, flowEdges, byId);
            }
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
        String trigger = byId.get(steps.getFirst().segmentId()).trigger;
        title = trigger != null ? trigger + ": " + title : title;
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

    // ------------------------------------------------------------------ tests

    /** Test file name -> name of the unit under test: PlayerResourceTest.java -> PlayerResource, a.service.spec.ts -> a.service. */
    private static final Pattern TEST_FILE = Pattern.compile(
            "^(?:test_(.+)\\.py|(.+?)(?:\\.(?:spec|test|e2e-spec|e2e)\\.[A-Za-z]+|_test\\.[A-Za-z]+|(?:Tests?|IT|Spec)\\.(?:java|kt|cs|groovy)))$");

    /**
     * For every changed test case: the changed code it reaches through calls (directly, via test helpers, or
     * indirectly via other changed code), and the components it is about (naming convention + called code).
     */
    private static List<TestInfo> tests(List<Work> works, List<FlowEdge> edges, List<DiffFile> files) {
        Map<String, Work> byId = new HashMap<>();
        works.forEach(w -> byId.put(w.id, w));
        Map<String, List<FlowEdge>> outgoing = new HashMap<>();
        for (FlowEdge e : edges) {
            if (!"http".equals(e.kind())) { // unit tests mock HTTP, they do not reach the backend
                outgoing.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            }
        }
        outgoing.values().forEach(l -> l.sort(Comparator.comparing(e -> e.line() == null ? 0 : e.line())));

        List<TestInfo> result = new ArrayList<>();
        for (Work t : works) {
            if (!isTestCase(t)) {
                continue;
            }
            Map<String, TestCoverage> covers = new LinkedHashMap<>();
            record Hop(String id, String via, Integer line, int depth) {
            }
            Deque<Hop> queue = new ArrayDeque<>();
            outgoing.getOrDefault(t.id, List.of()).forEach(e -> queue.add(new Hop(e.to(), null, e.line(), 1)));
            Set<String> seen = new HashSet<>(Set.of(t.id));
            while (!queue.isEmpty()) {
                Hop h = queue.poll();
                Work w = byId.get(h.id());
                if (w == null || !seen.add(h.id()) || isTestCase(w)) {
                    continue;
                }
                String via = h.via();
                if (!isTest(w)) { // a test helper/fixture is transparent
                    covers.put(w.id, new TestCoverage(w.id, h.via() == null, h.via(), h.line()));
                    via = w.id;
                }
                if (h.depth() < MAX_TEST_DEPTH) {
                    for (FlowEdge e : outgoing.getOrDefault(w.id, List.of())) {
                        queue.add(new Hop(e.to(), via, h.line(), h.depth() + 1));
                    }
                }
            }
            result.add(new TestInfo(t.id, t.symbol.displayName(), t.file.path(), subjects(t, covers.values(), byId, files),
                    List.copyOf(covers.values())));
        }
        return result;
    }

    private static List<TestSubject> subjects(Work test, Iterable<TestCoverage> covers, Map<String, Work> byId, List<DiffFile> files) {
        Map<String, TestSubject> subjects = new LinkedHashMap<>();
        String path = test.file.path();
        Matcher m = TEST_FILE.matcher(path.substring(path.lastIndexOf('/') + 1));
        if (m.matches()) {
            String unit = m.group(1) != null ? m.group(1) : m.group(2);
            DiffFile match = files.stream()
                    .filter(f -> !f.path().equals(path) && !TEST_FILE.matcher(fileName(f.path())).matches()
                            && stemOf(fileName(f.path())).equals(unit))
                    // player-view.spec.ts -> player-view.ts rather than player-view.scss
                    .max(Comparator.comparing((DiffFile f) -> f.language().equals(test.file.language()))
                            .thenComparing(f -> Languages.hasSymbols(f.language()))
                            .thenComparingInt(f -> commonPrefix(f.path(), path)))
                    .orElse(null);
            String name = match == null ? unit : fileName(match.path());
            subjects.put(match == null ? "?" + unit : match.path(), new TestSubject(name, match == null ? null : match.path(), match != null, "name"));
        }
        for (TestCoverage c : covers) {
            Work w = byId.get(c.segmentId());
            if (!c.direct() || subjects.containsKey(w.file.path())) {
                continue;
            }
            String name = w.symbol != null && w.symbol.container() != null ? w.symbol.container() : fileName(w.file.path());
            subjects.put(w.file.path(), new TestSubject(name, w.file.path(), true, "call"));
        }
        return List.copyOf(subjects.values());
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String stemOf(String fileName) {
        return fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
    }

    private static int commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------ output

    private Segment toSegment(Work w, List<String> places, List<String> testedBy) {
        Set<String> own = w.changed.stream().map(DiffLine::key).collect(Collectors.toCollection(LinkedHashSet::new));
        List<DisplayLine> lines;
        if (w.ctx == null) {
            lines = new ArrayList<>();
            for (DiffLine l : w.hunk.lines()) {
                lines.add(new DisplayLine(l.type(), l.oldLine(), l.newLine(), l.key(), l.text(), l.changed(), true, null,
                        l.newLine() == null ? null : w.refs.get(l.newLine())));
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
                w.category, w.group, w.rule == null ? null : w.rule.id(), w.reason, List.copyOf(own), lines,
                List.copyOf(places), List.copyOf(testedBy), w.layer, w.frontend);
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

    /** The categories of the PR: every module builds its own groups from the facts about the segments. */
    private List<Category> categories(List<Work> works, List<Flow> flows, Set<String> inFlows) {
        List<SegmentFacts> facts = works.stream().map(w -> new SegmentFacts(w.id, w.file.path(), w.category, w.group, w.layer,
                w.aspect == null ? null : w.aspect.category(), w.concerns,
                Structure.ADAPTER.equals(w.layer) ? Structure.adapterDirection(w.file.path()) : null, inFlows.contains(w.id))).toList();
        CategoryContext context = new CategoryContext(facts, flows, rules.rules.stream().map(Rules.Rule::group).distinct().toList());
        List<Category> result = new ArrayList<>();
        for (CategoryModule module : modules) {
            List<Group> groups = module.groups(context);
            if (!groups.isEmpty()) {
                CategoryInfo info = module.info();
                result.add(new Category(info.id(), info.label(), info.summary(), info.icon(), groups,
                        info.builds(), info.divides(), module.view(), module.origin()));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ components

    /**
     * Groups the changed code into components (files with the same name in the same folder) and assigns each a
     * column of the overview diagram. componentOf receives segment id -> component id.
     */
    private static List<Component> components(List<Work> works, boolean hexagonal, Map<String, String> componentOf) {
        Map<String, List<Work>> byKey = new LinkedHashMap<>();
        for (Work w : works) {
            boolean code = Languages.hasSymbols(w.file.language()) || (w.frontend && "xml".equals(w.file.language()));
            if (code && (w.rule == null || w.rule.extras() || isTest(w))) {
                byKey.computeIfAbsent(Structure.componentKey(w.file.path()), k -> new ArrayList<>()).add(w);
            }
        }
        for (Work w : works) { // styles and templates join the component of the same name
            String key = Structure.componentKey(w.file.path());
            if (byKey.containsKey(key) && !byKey.get(key).contains(w)) {
                byKey.get(key).add(w);
            }
        }
        List<Component> result = new ArrayList<>();
        byKey.forEach((key, members) -> {
            members.forEach(w -> componentOf.put(w.id, key));
            List<String> paths = Structure.unique(members.stream().map(w -> w.file.path()).toList());
            result.add(new Component(key, componentName(key, members), paths, area(members, hexagonal),
                    members.stream().map(w -> w.id).toList()));
        });
        return result;
    }

    /**
     * The class named like the file (PlayerResource.java), else the class most segments belong to, else the file
     * name (local_config). Tests keep their file name so they differ from the component they test.
     */
    private static String componentName(String key, List<Work> members) {
        String stem = key.substring(key.lastIndexOf('/') + 1);
        if (members.stream().allMatch(Analyzer::isTest) || members.stream().anyMatch(w -> w.symbol != null
                && (stem.equals(w.symbol.name()) || stem.equals(w.symbol.container())))) {
            return stem;
        }
        Map<String, Long> containers = members.stream()
                .map(w -> w.symbol == null ? null : w.symbol.kind().equals("class") ? w.symbol.name() : w.symbol.container())
                .filter(c -> c != null && !c.contains(" "))
                .collect(Collectors.groupingBy(c -> c, LinkedHashMap::new, Collectors.counting()));
        return containers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(key.substring(key.lastIndexOf('/') + 1));
    }

    private static String area(List<Work> members, boolean hexagonal) {
        if (members.stream().allMatch(Analyzer::isTest)) {
            return "test";
        }
        if (members.stream().anyMatch(w -> w.frontend)) {
            return "frontend";
        }
        String path = members.getFirst().file.path();
        String layer = members.stream().map(w -> w.layer).filter(l -> l != null).findFirst()
                .orElse(hexagonal ? Structure.layerOf(path) : null);
        if (layer != null) {
            return switch (layer) {
                case Structure.ADAPTER -> "out".equals(Structure.adapterDirection(path)) ? "adapter-out" : "adapter-in";
                case Structure.USECASE -> "usecase";
                case Structure.APPLICATION -> "application";
                default -> "domain";
            };
        }
        if (API_LAYER.matcher(path).find()) {
            return "api";
        }
        if (DOMAIN_DIR.matcher("/" + path).find()) {
            return "domain";
        }
        return DEEP_LAYER.matcher(path).find() ? "data" : "logic";
    }

    private static List<ComponentEdge> componentEdges(List<FlowEdge> edges, Map<String, String> componentOf) {
        Map<String, int[]> calls = new LinkedHashMap<>();
        Map<String, Boolean> http = new HashMap<>();
        for (FlowEdge e : edges) {
            String a = componentOf.get(e.from());
            String b = componentOf.get(e.to());
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            String k = a + "\u0000" + b;
            calls.computeIfAbsent(k, x -> new int[1])[0]++;
            http.merge(k, "http".equals(e.kind()), Boolean::logicalOr);
        }
        List<ComponentEdge> result = new ArrayList<>();
        calls.forEach((k, n) -> {
            String[] ab = k.split("\u0000");
            result.add(new ComponentEdge(ab[0], ab[1], n[0], http.get(k)));
        });
        return result;
    }
}
