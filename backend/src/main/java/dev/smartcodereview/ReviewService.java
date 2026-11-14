package dev.smartcodereview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smartcodereview.analysis.Analyzer;
import dev.smartcodereview.analysis.Languages;
import dev.smartcodereview.analysis.OpenApi;
import dev.smartcodereview.analysis.Rules;
import dev.smartcodereview.commits.Attribution;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Account;
import dev.smartcodereview.model.Model.ApiOperation;
import dev.smartcodereview.model.Model.Analysis;
import dev.smartcodereview.model.Model.Blame;
import dev.smartcodereview.model.Model.Commit;
import dev.smartcodereview.model.Model.Commits;
import dev.smartcodereview.model.Model.Comment;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.FileSummary;
import dev.smartcodereview.model.Model.LineMark;
import dev.smartcodereview.model.Model.LogEntry;
import dev.smartcodereview.model.Model.MarkRequest;
import dev.smartcodereview.model.Model.NewComment;
import dev.smartcodereview.model.Model.PrRef;
import dev.smartcodereview.model.Model.Progress;
import dev.smartcodereview.model.Model.PullRequestSummary;
import dev.smartcodereview.model.Model.ReviewBundle;
import dev.smartcodereview.model.Model.RepoStructure;
import dev.smartcodereview.model.Model.ReviewState;
import dev.smartcodereview.model.Model.Settings;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.provider.ReviewProvider;
import dev.smartcodereview.repo.RepositorySnapshot;
import dev.smartcodereview.review.LineAcceptance;
import dev.smartcodereview.store.DataStore;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/** Orchestrates provider, analysis and local storage. */
@ApplicationScoped
public class ReviewService {

    private static final int MAX_LOG_ENTRIES = 500;

    @Inject
    Instance<ReviewProvider> providerBeans;

    @Inject
    DataStore store;

    @Inject
    ObjectMapper mapper;

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ providers

    public ReviewProvider provider(String id) {
        return providerBeans.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new ProviderException(404, "Unbekannter Provider: " + id));
    }

    public List<String> providerIds() {
        return providerBeans.stream().map(ReviewProvider::id).toList();
    }

    /** Open PRs of all providers with credentials; a failing provider is logged and skipped. */
    public List<PullRequestSummary> openPullRequests() {
        List<PullRequestSummary> result = new ArrayList<>();
        for (ReviewProvider p : providerBeans) {
            if (!p.hasCredentials()) {
                continue;
            }
            try {
                for (PullRequestSummary s : p.openPullRequests()) {
                    PrRef ref = new PrRef(s.provider(), s.owner(), s.repo(), s.number());
                    result.add(new PullRequestSummary(s.provider(), s.owner(), s.repo(), s.number(), s.title(), s.author(),
                            s.url(), s.draft(), s.createdAt(), s.updatedAt(), s.roles(), progress(ref).orElse(null)));
                }
            } catch (ProviderException e) {
                Log.warnf("%s: %s", p.label(), e.getMessage());
            }
        }
        result.sort(Comparator.comparing(PullRequestSummary::updatedAt).reversed());
        return result;
    }

    /** One entry per provider: the signed-in user, or why there is none. */
    public List<Account> accounts() {
        List<Account> result = new ArrayList<>();
        for (ReviewProvider p : providerBeans) {
            if (!p.hasCredentials()) {
                result.add(new Account(p.id(), p.label(), p.host(), null, "Kein Token konfiguriert"));
                continue;
            }
            try {
                result.add(new Account(p.id(), p.label(), p.host(), p.currentUser(), null));
            } catch (ProviderException e) {
                result.add(new Account(p.id(), p.label(), p.host(), null, e.getMessage()));
            }
        }
        result.sort(Comparator.comparing((Account a) -> a.user() == null).thenComparing(Account::provider));
        return result;
    }

    /** Turns a PR/MR web URL of any provider into a reference. */
    public PrRef resolve(String url) {
        for (ReviewProvider p : providerBeans) {
            Optional<PrRef> ref = p.parseUrl(url);
            if (ref.isPresent()) {
                return ref.get();
            }
        }
        throw new ProviderException(400, "Keine bekannte Pull- oder Merge-Request-URL: " + url);
    }

    // ------------------------------------------------------------------ bundle

    public ReviewBundle bundle(PrRef ref) {
        synchronized (lock(ref)) {
            Snapshot snapshot = store.read(snapshotFile(ref), Snapshot.class).orElseGet(() -> fetch(ref));
            Analysis analysis = currentAnalysis(ref, snapshot);
            return toBundle(ref, snapshot, analysis);
        }
    }

    /** Fetches the PR again (new commits are out of scope for the PoC: marks are kept as they are). */
    public ReviewBundle reload(PrRef ref) {
        synchronized (lock(ref)) {
            Snapshot snapshot = fetch(ref);
            Analysis analysis = analyse(ref, snapshot);
            return toBundle(ref, snapshot, analysis);
        }
    }

    public ReviewBundle reanalyse(PrRef ref) {
        synchronized (lock(ref)) {
            Snapshot snapshot = store.read(snapshotFile(ref), Snapshot.class).orElseGet(() -> fetch(ref));
            return toBundle(ref, snapshot, analyse(ref, snapshot));
        }
    }

    private ReviewBundle toBundle(PrRef ref, Snapshot snapshot, Analysis analysis) {
        List<FileSummary> files = snapshot.files().stream()
                .map(f -> new FileSummary(f.path(), f.previousPath(), f.status(), f.additions(), f.deletions(), f.language()))
                .toList();
        return new ReviewBundle(snapshot.pr(), snapshot.fetchedAt(), store.prDir(ref).toString(), files, analysis, state(ref));
    }

    private Analysis currentAnalysis(PrRef ref, Snapshot snapshot) {
        Rules rules = rules();
        return store.read(analysisFile(ref), Analysis.class)
                .filter(a -> a.schemaVersion() == Model.SCHEMA_VERSION && Analyzer.fingerprint(rules).equals(a.rulesHash()))
                .orElseGet(() -> analyse(ref, snapshot));
    }

    private Analysis analyse(PrRef ref, Snapshot snapshot) {
        Map<String, String> sources = new HashMap<>();
        Path sourcesDir = store.sourcesDir(ref);
        // snapshots from older versions lack sources that newer rules want (e.g. test files): fetch them once
        List<DiffFile> missing = wantedSources(snapshot, rules()).stream()
                .filter(f -> !Files.exists(DataStore.resolveInside(sourcesDir, f.path()))).toList();
        if (!missing.isEmpty()) {
            try {
                fetchSources(ref, snapshot, missing);
            } catch (ProviderException e) {
                Log.warnf("Could not fetch missing sources of %s: %s", ref.key(), e.getMessage());
            }
        }
        for (DiffFile f : snapshot.files()) {
            store.readText(DataStore.resolveInside(sourcesDir, f.path())).ifPresent(s -> sources.put(f.path(), s));
        }
        long t0 = System.currentTimeMillis();
        Analysis analysis = new Analyzer(rules()).analyze(snapshot, sources, structure(ref, snapshot));
        Log.infof("Analysed %s in %d ms: %d segments, %d flows", ref.key(), System.currentTimeMillis() - t0,
                analysis.segments().size(), analysis.flows().size());
        store.write(analysisFile(ref), analysis);
        return analysis;
    }

    /** At most this many API contracts are read per repository. */
    private static final int MAX_CONTRACTS = 10;

    /**
     * Repository directories and API contract operations at the PR head (architecture detection, links through
     * generated API clients); fetched once, empty if unavailable.
     */
    private RepoStructure structure(PrRef ref, Snapshot snapshot) {
        Path file = prDirFile(ref, "structure.json");
        return store.read(file, RepoStructure.class).filter(s -> s.operations() != null).orElseGet(() -> {
            try {
                ReviewProvider provider = provider(ref.provider());
                String sha = snapshot.pr().headSha();
                RepoStructure s = provider.directories(ref, sha);
                List<ApiOperation> operations = new ArrayList<>();
                List<String> contracts = s.contracts() == null ? List.of() : s.contracts();
                for (String path : contracts.stream().limit(MAX_CONTRACTS).toList()) {
                    try {
                        provider.fileContent(ref, path, sha).ifPresent(text -> operations.addAll(OpenApi.operations(path, text)));
                    } catch (ProviderException e) {
                        Log.warnf("API contract %s not readable: %s", path, e.getMessage());
                    }
                }
                s = new RepoStructure(s.directories(), s.truncated(), contracts, operations);
                store.write(file, s);
                return s;
            } catch (ProviderException e) {
                Log.warnf("Repository structure of %s not available: %s", ref.key(), e.getMessage());
                return new RepoStructure(List.of(), true);
            }
        });
    }

    private Snapshot fetch(PrRef ref) {
        if (ref.number() == 0) {
            return fetchRepository(ref);
        }
        Snapshot snapshot = provider(ref.provider()).fetchSnapshot(ref);
        try {
            Files.deleteIfExists(prDirFile(ref, "structure.json")); // re-read for the new head
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<DiffFile> wanted = wantedSources(snapshot, rules());
        fetchSources(ref, snapshot, wanted);
        store.write(snapshotFile(ref), snapshot);
        Log.infof("Fetched %s: %d files, %d sources", ref.key(), snapshot.files().size(), wanted.size());
        return snapshot;
    }

    /**
     * The whole repository at the head of its default branch, as a PR in which every file is new. One archive
     * download; the directories and API contracts come from it too (structure.json).
     */
    private Snapshot fetchRepository(PrRef ref) {
        ReviewProvider provider = provider(ref.provider());
        Model.Repository repo = provider.repository(ref);
        long t0 = System.currentTimeMillis();
        byte[] archive = provider.archive(ref, repo.headSha());
        RepositorySnapshot.Result r = RepositorySnapshot.build(ref, repo, ref.owner().replace('~', '/'), archive);
        Path sourcesDir = store.sourcesDir(ref);
        List<DiffFile> wanted = wantedSources(r.snapshot(), rules());
        for (DiffFile f : wanted) {
            store.writeText(DataStore.resolveInside(sourcesDir, f.path()), r.sources().get(f.path()));
        }
        store.write(prDirFile(ref, "structure.json"), r.structure());
        store.write(snapshotFile(ref), r.snapshot());
        Log.infof("Fetched repository %s @ %s in %d ms: %d files, %d analysed", ref.key(), repo.headSha(),
                System.currentTimeMillis() - t0, r.snapshot().files().size(), wanted.size());
        return r.snapshot();
    }

    /** Files whose new version is needed to find functions: code files without a path rule, or with `symbols: true`. */
    private static List<DiffFile> wantedSources(Snapshot snapshot, Rules rules) {
        return snapshot.files().stream()
                .filter(f -> {
                    Rules.Rule r = rules.forPath(f.path());
                    return !"removed".equals(f.status()) && !f.patchMissing() && Languages.hasSymbols(f.language())
                            && (r == null || r.symbols());
                })
                .toList();
    }

    private void fetchSources(PrRef ref, Snapshot snapshot, List<DiffFile> wanted) {
        ReviewProvider provider = provider(ref.provider());
        Path sourcesDir = store.sourcesDir(ref);
        Semaphore limit = new Semaphore(8);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (DiffFile f : wanted) {
                tasks.add(pool.submit(() -> {
                    limit.acquireUninterruptibly();
                    try {
                        provider.fileContent(ref, f.path(), snapshot.pr().headSha())
                                .ifPresent(text -> store.writeText(DataStore.resolveInside(sourcesDir, f.path()), text));
                    } finally {
                        limit.release();
                    }
                }));
            }
            for (Future<?> t : tasks) {
                try {
                    t.get();
                } catch (Exception e) {
                    Log.warnf("Could not fetch a source file: %s", e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------ settings

    public Settings settings() {
        return store.read(store.root().resolve("settings.json"), Settings.class)
                .map(s -> s.disabledCategories() == null ? new Settings(List.of()) : s)
                .orElse(new Settings(List.of()));
    }

    public Settings saveSettings(Settings settings) {
        List<String> disabled = settings.disabledCategories() == null ? List.of()
                : settings.disabledCategories().stream().filter(c -> c != null && c.matches("[\\w-]+")).distinct().sorted().toList();
        Settings clean = new Settings(disabled);
        store.write(store.root().resolve("settings.json"), clean);
        return clean;
    }

    /** Places that count: those whose category is switched on (a place id starts with its category id). */
    private static List<String> effective(List<String> places, Set<String> disabled) {
        if (places == null) {
            return List.of();
        }
        return disabled.isEmpty() ? places : places.stream().filter(p -> !disabled.contains(categoryOf(p))).toList();
    }

    private static String categoryOf(String place) {
        int slash = place.indexOf('/');
        return slash < 0 ? place : place.substring(0, slash);
    }

    // ------------------------------------------------------------------ marks

    public ReviewState state(PrRef ref) {
        return store.read(stateFile(ref), ReviewState.class)
                .map(s -> s.okAt() != null && s.ok() != null && s.log() != null ? s
                        : new ReviewState(s.schemaVersion(), s.pr(), s.headSha(), s.ok() == null ? Map.of() : s.ok(),
                        s.log() == null ? List.of() : s.log(), s.okAt() == null ? Map.of() : s.okAt()))
                .orElseGet(() -> {
                    String headSha = store.read(snapshotFile(ref), Snapshot.class).map(s -> s.pr().headSha()).orElse(null);
                    return new ReviewState(Model.SCHEMA_VERSION, ref.key(), headSha, new TreeMap<>(), new ArrayList<>(), new TreeMap<>());
                });
    }

    /**
     * Applies marks. A line shown at several places (e.g. in a quick-win group and in two flows) needs an ok at
     * each place: marks with a place go to okAt[place] until all places are ok, then the line moves to ok.
     * Marks without a place (file view, agents) apply to all places at once. The rules are in {@link LineAcceptance}.
     */
    public ReviewState mark(PrRef ref, MarkRequest req) {
        synchronized (lock(ref)) {
            ReviewState current = state(ref);
            Map<String, List<String>> placesByLine = placesByLine(ref);
            Map<String, Set<String>> ok = new TreeMap<>();
            current.ok().forEach((path, keys) -> ok.put(path, new TreeSet<>(keys)));
            Map<String, Map<String, Set<String>>> okAt = new TreeMap<>();
            current.okAt().forEach((place, byPath) -> byPath.forEach((path, keys) ->
                    okAt.computeIfAbsent(place, k -> new TreeMap<>()).computeIfAbsent(path, k -> new TreeSet<>()).addAll(keys)));

            int changed = 0;
            for (LineMark m : req.lines()) {
                List<String> places = placesByLine.getOrDefault(m.path() + "\u0000" + m.key(), List.of());
                Set<String> full = ok.computeIfAbsent(m.path(), p -> new TreeSet<>());
                Set<String> at = new TreeSet<>();
                okAt.forEach((place, byPath) -> {
                    if (byPath.getOrDefault(m.path(), Set.of()).contains(m.key())) {
                        at.add(place);
                    }
                });
                LineAcceptance before = new LineAcceptance(full.contains(m.key()), at);
                LineAcceptance after = before.accept(places, m.place(), req.ok());
                boolean wasAccepted = before.status(places, m.place()) == LineAcceptance.Status.ACCEPTED;
                changed += wasAccepted != req.ok() ? 1 : 0;
                // write back: the line is either ok everywhere or ok at some places (other places' entries stay)
                if (after.full()) {
                    full.add(m.key());
                } else {
                    full.remove(m.key());
                }
                for (String p : places) {
                    removeAt(okAt, p, m.path(), m.key());
                }
                for (String p : after.at()) {
                    okAt.computeIfAbsent(p, k -> new TreeMap<>()).computeIfAbsent(m.path(), k -> new TreeSet<>()).add(m.key());
                }
            }

            Map<String, List<String>> out = new TreeMap<>();
            ok.forEach((path, keys) -> {
                if (!keys.isEmpty()) {
                    out.put(path, sortedKeys(keys));
                }
            });
            Map<String, Map<String, List<String>>> outAt = new TreeMap<>();
            okAt.forEach((place, byPath) -> byPath.forEach((path, keys) -> {
                if (!keys.isEmpty()) {
                    outAt.computeIfAbsent(place, k -> new TreeMap<>()).put(path, sortedKeys(keys));
                }
            }));
            List<LogEntry> log = new ArrayList<>(current.log());
            log.add(new LogEntry(Instant.now().toString(), req.by() == null ? "ui" : req.by(), req.ok() ? "ok" : "unok",
                    req.scope() == null ? "lines" : req.scope(), changed));
            if (log.size() > MAX_LOG_ENTRIES) {
                log = new ArrayList<>(log.subList(log.size() - MAX_LOG_ENTRIES, log.size()));
            }
            String headSha = current.headSha() != null ? current.headSha()
                    : store.read(snapshotFile(ref), Snapshot.class).map(s -> s.pr().headSha()).orElse(null);
            ReviewState next = new ReviewState(Model.SCHEMA_VERSION, ref.key(), headSha, out, log, outAt);
            store.write(stateFile(ref), next);
            return next;
        }
    }

    /** "path\0key" -> places of the segment that owns the line (current analysis, switched-on categories only). */
    private Map<String, List<String>> placesByLine(PrRef ref) {
        Map<String, List<String>> result = new HashMap<>();
        Set<String> disabled = Set.copyOf(settings().disabledCategories());
        store.read(analysisFile(ref), Analysis.class).ifPresent(a -> {
            for (Segment s : a.segments()) {
                List<String> places = effective(s.places(), disabled);
                s.lineKeys().forEach(k -> result.put(s.path() + "\u0000" + k, places));
            }
        });
        return result;
    }

    private static void removeAt(Map<String, Map<String, Set<String>>> okAt, String place, String path, String key) {
        Map<String, Set<String>> byPath = okAt.get(place);
        if (byPath != null && byPath.containsKey(path)) {
            byPath.get(path).remove(key);
        }
    }

    private static List<String> sortedKeys(Set<String> keys) {
        return keys.stream().sorted(LINE_KEY_ORDER).toList();
    }

    /** N12 before N100, O-lines after N-lines. */
    private static final Comparator<String> LINE_KEY_ORDER = Comparator
            .comparing((String k) -> k.isEmpty() ? ' ' : k.charAt(0))
            .thenComparingInt(k -> {
                try {
                    return Integer.parseInt(k.substring(1));
                } catch (RuntimeException e) {
                    return Integer.MAX_VALUE;
                }
            })
            .thenComparing(Comparator.naturalOrder());

    private Optional<Progress> progress(PrRef ref) {
        Optional<Analysis> analysis = store.read(analysisFile(ref), Analysis.class);
        if (analysis.isEmpty()) {
            return Optional.empty();
        }
        ReviewState st = state(ref);
        Set<String> disabled = Set.copyOf(settings().disabledCategories());
        int total = 0, ok = 0;
        for (Segment s : analysis.get().segments()) {
            Set<String> marked = new LinkedHashSet<>(st.ok().getOrDefault(s.path(), List.of()));
            List<String> places = effective(s.places(), disabled);
            if (s.places() != null && !s.places().isEmpty() && places.isEmpty()) {
                continue; // only shown in switched-off categories: nothing to review
            }
            for (String key : s.lineKeys()) {
                total++;
                Set<String> at = new LinkedHashSet<>();
                for (String p : places) {
                    if (st.okAt().getOrDefault(p, Map.of()).getOrDefault(s.path(), List.of()).contains(key)) {
                        at.add(p);
                    }
                }
                if (new LineAcceptance(marked.contains(key), at).status(places, null) == LineAcceptance.Status.ACCEPTED) {
                    ok++;
                }
            }
        }
        return Optional.of(new Progress(ok, total));
    }

    // ------------------------------------------------------------------ comments

    public List<Comment> comments(PrRef ref) {
        if (ref.number() == 0) {
            return List.of(); // a repository has no review comments
        }
        try {
            List<Comment> comments = provider(ref.provider()).comments(ref);
            store.write(prDirFile(ref, "comments.json"), comments);
            return comments;
        } catch (ProviderException e) {
            Log.warnf("Comments of %s not available: %s", ref.key(), e.getMessage());
            return List.of(store.read(prDirFile(ref, "comments.json"), Comment[].class).orElse(new Comment[0]));
        }
    }

    public Comment postComment(PrRef ref, NewComment comment) {
        if (ref.number() == 0) {
            throw new ProviderException(400, "Beim ganzen Repository gibt es keinen Pull Request, an den Kommentare gehen könnten");
        }
        if (comment.body() == null || comment.body().isBlank()) {
            throw new ProviderException(400, "Kommentar ist leer");
        }
        Snapshot snapshot = store.read(snapshotFile(ref), Snapshot.class)
                .orElseThrow(() -> new ProviderException(409, "PR zuerst öffnen"));
        return provider(ref.provider()).postComment(ref, snapshot, comment);
    }

    // ------------------------------------------------------------------ commits & blame

    /**
     * The PR's commits and which commit made each changed line (commits.json; recomputed when the head or the
     * attribution algorithm changed). Commit diffs never change, so they are fetched only here. On a provider
     * error the last result is returned if there is one, else an empty result carrying the error.
     */
    public Commits commits(PrRef ref) {
        Snapshot snapshot = snapshot(ref);
        Path file = prDirFile(ref, "commits.json");
        synchronized (locks.computeIfAbsent(ref.key() + "#commits", k -> new Object())) {
            Optional<Commits> cached = store.read(file, Commits.class);
            if (cached.filter(c -> c.version() == Attribution.VERSION && c.error() == null
                    && snapshot.pr().headSha().equals(c.headSha())).isPresent()) {
                return cached.get();
            }
            try {
                ReviewProvider provider = provider(ref.provider());
                // whole repository: the newest commits of the branch (older lines stay without commit; blame shows them)
                List<Commit> commits = ref.number() == 0
                        ? provider.branchCommits(ref, snapshot.pr().headSha(), REPOSITORY_COMMITS)
                        : provider.commits(ref);
                Map<String, List<DiffFile>> diffs = new ConcurrentHashMap<>();
                Semaphore limit = new Semaphore(6);
                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<?>> tasks = new ArrayList<>();
                    for (Commit c : commits) {
                        tasks.add(pool.submit(() -> {
                            limit.acquireUninterruptibly();
                            try {
                                diffs.put(c.sha(), provider.commitDiff(ref, c.sha()));
                            } finally {
                                limit.release();
                            }
                        }));
                    }
                    for (Future<?> t : tasks) {
                        try {
                            t.get();
                        } catch (Exception e) {
                            Log.warnf("Commit diff of %s not available: %s", ref.key(), e.getMessage());
                        }
                    }
                }
                long t0 = System.currentTimeMillis();
                Attribution.Result r = Attribution.compute(snapshot, commits, diffs);
                Log.infof("Attributed %s to %d commits in %d ms", ref.key(), commits.size(), System.currentTimeMillis() - t0);
                Commits result = new Commits(Attribution.VERSION, snapshot.pr().headSha(), Instant.now().toString(),
                        r.ordered(), r.lines(), r.mergedVia(), null);
                store.write(file, result);
                return result;
            } catch (ProviderException e) {
                Log.warnf("Commits of %s not available: %s", ref.key(), e.getMessage());
                return cached.orElseGet(() -> new Commits(Attribution.VERSION, snapshot.pr().headSha(), Instant.now().toString(),
                        List.of(), Map.of(), Map.of(), e.getMessage()));
            }
        }
    }

    /** Commits of the branch looked at in a whole-repository review. */
    static final int REPOSITORY_COMMITS = 50;

    /** git blame of a changed file at the PR head; cached under blame/<path>.json. */
    public Blame blame(PrRef ref, String path) {
        Snapshot snapshot = snapshot(ref);
        DiffFile f = snapshot.files().stream().filter(x -> x.path().equals(path)).findFirst()
                .orElseThrow(() -> new ProviderException(404, "Datei ist nicht Teil des Pull Requests: " + path));
        String sha = snapshot.pr().headSha();
        if ("removed".equals(f.status())) {
            return new Blame(path, sha, List.of(), List.of());
        }
        Path file = DataStore.resolveInside(store.prDir(ref).resolve("blame"), path + ".json");
        Optional<Blame> cached = store.read(file, Blame.class).filter(b -> sha.equals(b.sha()));
        if (cached.isPresent()) {
            return cached.get();
        }
        Blame blame = provider(ref.provider()).blame(ref, path, sha);
        store.write(file, blame);
        return blame;
    }

    private Snapshot snapshot(PrRef ref) {
        synchronized (lock(ref)) {
            return store.read(snapshotFile(ref), Snapshot.class).orElseGet(() -> fetch(ref));
        }
    }

    // ------------------------------------------------------------------ rules & files

    private volatile Rules cachedRules;

    public Rules rules() {
        byte[] bytes;
        Path custom = store.root().resolve("rules.json");
        try {
            if (Files.isRegularFile(custom)) {
                bytes = Files.readAllBytes(custom);
            } else {
                try (InputStream in = ReviewService.class.getResourceAsStream("/rules/default-rules.json")) {
                    bytes = in.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String hash = sha256(bytes);
        Rules r = cachedRules;
        if (r != null && r.hash.equals(hash)) {
            return r;
        }
        try {
            JsonNode root = mapper.readTree(bytes);
            cachedRules = Rules.parse(root, hash);
        } catch (Exception e) {
            throw new ProviderException(500, "rules.json ist ungültig: " + e.getMessage());
        }
        return cachedRules;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path snapshotFile(PrRef ref) {
        return prDirFile(ref, "snapshot.json");
    }

    private Path analysisFile(PrRef ref) {
        return prDirFile(ref, "analysis.json");
    }

    private Path stateFile(PrRef ref) {
        return prDirFile(ref, "review.json");
    }

    private Path prDirFile(PrRef ref, String name) {
        return store.prDir(ref).resolve(name);
    }

    private Object lock(PrRef ref) {
        return locks.computeIfAbsent(ref.key(), k -> new Object());
    }

    /** All PRs that have local data (useful for agents and offline use). */
    public List<String> localReviews() {
        Path reviews = store.root().resolve("reviews");
        if (!Files.isDirectory(reviews)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(reviews, 5)) {
            return s.filter(p -> p.getFileName().toString().equals("snapshot.json"))
                    .map(p -> reviews.relativize(p.getParent()).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
