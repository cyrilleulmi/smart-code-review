package dev.smartcodereview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smartcodereview.analysis.Analyzer;
import dev.smartcodereview.analysis.CodeText;
import dev.smartcodereview.analysis.FileView;
import dev.smartcodereview.analysis.Languages;
import dev.smartcodereview.analysis.OpenApi;
import dev.smartcodereview.analysis.Rules;
import dev.smartcodereview.analysis.Specs;
import dev.smartcodereview.commits.Attribution;
import dev.smartcodereview.commits.CommitChecks;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Account;
import dev.smartcodereview.model.Model.ApiOperation;
import dev.smartcodereview.model.Model.Analysis;
import dev.smartcodereview.model.Model.Blame;
import dev.smartcodereview.model.Model.Commit;
import dev.smartcodereview.model.Model.CommitFinding;
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
import dev.smartcodereview.model.Model.RelatedTestFile;
import dev.smartcodereview.model.Model.RelatedTests;
import dev.smartcodereview.model.Model.ReviewBundle;
import dev.smartcodereview.model.Model.RepoStructure;
import dev.smartcodereview.model.Model.ReviewState;
import dev.smartcodereview.model.Model.Settings;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;
import dev.smartcodereview.model.Model.WholeFile;
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.provider.ReviewProvider;
import dev.smartcodereview.repo.RepositorySnapshot;
import dev.smartcodereview.review.LineAcceptance;
import dev.smartcodereview.review.MarkCarry;
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
import java.util.Objects;
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

    /** Repositories of all providers with credentials (per provider: own ones first, most recently active first). */
    public List<Model.RepositorySummary> repositories(String query) {
        List<Model.RepositorySummary> result = new ArrayList<>();
        for (ReviewProvider p : providerBeans) {
            if (!p.hasCredentials()) {
                continue;
            }
            try {
                result.addAll(p.repositories(query));
            } catch (ProviderException e) {
                Log.warnf("%s: %s", p.label(), e.getMessage());
            }
        }
        return result;
    }

    /** Open PRs of one repository (errors propagate: the repository was chosen explicitly). */
    public List<PullRequestSummary> repositoryPullRequests(String provider, String owner, String repo) {
        return withProgress(provider(provider).repositoryPullRequests(owner, repo));
    }

    /** Free-text search of open PRs over all providers with credentials. */
    public List<PullRequestSummary> searchPullRequests(String query) {
        List<PullRequestSummary> result = new ArrayList<>();
        for (ReviewProvider p : providerBeans) {
            if (!p.hasCredentials()) {
                continue;
            }
            try {
                result.addAll(withProgress(p.searchPullRequests(query)));
            } catch (ProviderException e) {
                Log.warnf("%s: %s", p.label(), e.getMessage());
            }
        }
        result.sort(Comparator.comparing(PullRequestSummary::updatedAt).reversed());
        return result;
    }

    private List<PullRequestSummary> withProgress(List<PullRequestSummary> list) {
        List<PullRequestSummary> result = new ArrayList<>();
        for (PullRequestSummary s : list) {
            PrRef ref = new PrRef(s.provider(), s.owner(), s.repo(), s.number());
            result.add(new PullRequestSummary(s.provider(), s.owner(), s.repo(), s.number(), s.title(), s.author(),
                    s.url(), s.draft(), s.createdAt(), s.updatedAt(), s.roles(), progress(ref).orElse(null)));
        }
        return result;
    }

    /** One entry per provider: the signed-in user, or why there is none. */
    public List<Account> accounts() {
        List<Account> result = new ArrayList<>();
        for (ReviewProvider p : providerBeans) {
            Boolean cookie = p.cookieSet().orElse(null);
            if (!p.hasCredentials()) {
                result.add(new Account(p.id(), p.label(), p.host(), null, "Kein Token konfiguriert", cookie));
                continue;
            }
            try {
                result.add(new Account(p.id(), p.label(), p.host(), p.currentUser(), null, cookie));
            } catch (ProviderException e) {
                result.add(new Account(p.id(), p.label(), p.host(), null, e.getMessage(), cookie));
            }
        }
        result.sort(Comparator.comparing((Account a) -> a.user() == null).thenComparing(Account::provider));
        return result;
    }

    /** Replaces the session cookies of an SSO proxy in front of a provider (in memory, until the next start). */
    public void setCookie(String provider, String cookie) {
        provider(provider).setCookie(cookie);
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

    /**
     * Fetches the PR again. When it has moved on (new commits, rebase), the marks are carried to the line keys of the
     * new snapshot ({@link MarkCarry}): unchanged lines keep their marks, new and changed lines are open.
     */
    public ReviewBundle reload(PrRef ref) {
        synchronized (lock(ref)) {
            Optional<Snapshot> before = store.read(snapshotFile(ref), Snapshot.class);
            Optional<Analysis> beforeAnalysis = store.read(analysisFile(ref), Analysis.class);
            Snapshot snapshot = fetch(ref);
            Analysis analysis = analyse(ref, snapshot);
            if (before.isPresent() && Files.exists(stateFile(ref)) && moved(before.get(), snapshot)) {
                ReviewState carried = MarkCarry.carry(state(ref), before.get(), beforeAnalysis.orElse(null), snapshot,
                        analysis, Instant.now().toString());
                if (carried.log().size() > MAX_LOG_ENTRIES) {
                    List<LogEntry> log = carried.log().subList(carried.log().size() - MAX_LOG_ENTRIES, carried.log().size());
                    carried = new ReviewState(carried.schemaVersion(), carried.pr(), carried.headSha(), carried.ok(),
                            List.copyOf(log), carried.okAt(), carried.reload());
                }
                store.write(stateFile(ref), carried);
                Log.infof("Carried marks of %s from %s to %s: %d kept, %d dropped", ref.key(),
                        before.get().pr().headSha(), snapshot.pr().headSha(), carried.reload().kept(), carried.reload().dropped());
            }
            return toBundle(ref, snapshot, analysis);
        }
    }

    /** Line keys change when the head or the base moved. */
    private static boolean moved(Snapshot before, Snapshot after) {
        return !Objects.equals(before.pr().headSha(), after.pr().headSha())
                || !Objects.equals(before.pr().baseSha(), after.pr().baseSha());
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
        Map<String, String> baseSources = baseSources(ref, snapshot);
        RepoStructure structure = structure(ref, snapshot);
        RelatedTests related = relatedTests(ref, snapshot, structure);
        Map<String, String> relatedSources = new HashMap<>();
        if (related != null) {
            Path dir = store.relatedSourcesDir(ref);
            related.files().stream().filter(RelatedTestFile::loaded).forEach(f ->
                    store.readText(DataStore.resolveInside(dir, f.path())).ifPresent(s -> relatedSources.put(f.path(), s)));
        }
        long t0 = System.currentTimeMillis();
        Analysis analysis = new Analyzer(rules()).analyze(snapshot, sources, structure, baseSources, related, relatedSources);
        Log.infof("Analysed %s in %d ms: %d segments, %d flows, %d existing tests", ref.key(), System.currentTimeMillis() - t0,
                analysis.segments().size(), analysis.flows().size(), analysis.existingTests().size());
        store.write(analysisFile(ref), analysis);
        return analysis;
    }

    /**
     * Old versions of the changed test files (Spec-Diff), by their path in the base version. Missing ones (older
     * snapshots, an earlier failure) are fetched once here; a file that still cannot be loaded is left out and the
     * outline shows only its new side.
     */
    private Map<String, String> baseSources(PrRef ref, Snapshot snapshot) {
        Map<String, String> result = new HashMap<>();
        Path dir = store.baseSourcesDir(ref);
        List<DiffFile> wanted = wantedBaseSources(ref, snapshot, rules());
        List<DiffFile> missing = wanted.stream()
                .filter(f -> !Files.exists(DataStore.resolveInside(dir, Specs.basePath(f)))).toList();
        if (!missing.isEmpty()) {
            try {
                fetchBaseSources(ref, snapshot, missing);
            } catch (ProviderException e) {
                Log.warnf("Could not fetch base versions of test files of %s: %s", ref.key(), e.getMessage());
            }
        }
        for (DiffFile f : wanted) {
            String path = Specs.basePath(f);
            store.readText(DataStore.resolveInside(dir, path)).ifPresent(s -> result.put(path, s));
        }
        return result;
    }

    /** Changed test files whose base version the Spec-Diff needs; none for a whole repository (it has no base). */
    private static List<DiffFile> wantedBaseSources(PrRef ref, Snapshot snapshot, Rules rules) {
        if (ref.number() == 0 || snapshot.pr().baseSha() == null || snapshot.pr().baseSha().isBlank()) {
            return List.of();
        }
        return snapshot.files().stream().filter(f -> Specs.needsBase(rules, f)).toList();
    }

    /**
     * Bestehende Tests: the existing test files most likely testing the changed code ({@link
     * dev.smartcodereview.analysis.RelatedTests}), selected from the repository's file list and loaded at the PR head
     * into sources-related/ - at the first analysis after loading the PR, and for older snapshots at their next
     * analysis; a file that cannot be loaded is logged and skipped (tried again at the next analysis). The selection is
     * written to related.json. null for a whole repository (all files are part of it already).
     */
    private RelatedTests relatedTests(PrRef ref, Snapshot snapshot, RepoStructure structure) {
        if (ref.number() == 0) {
            return null;
        }
        Rules rules = rules();
        dev.smartcodereview.analysis.RelatedTests.Selection selection = dev.smartcodereview.analysis.RelatedTests
                .select(rules, snapshot.files(), structure.files() == null ? List.of() : structure.files());
        Path dir = store.relatedSourcesDir(ref);
        List<String> missing = selection.files().stream().map(RelatedTestFile::path)
                .filter(p -> !Files.exists(DataStore.resolveInside(dir, p))).toList();
        if (!missing.isEmpty()) {
            try {
                fetchFiles(ref, missing, snapshot.pr().headSha(), dir);
            } catch (ProviderException e) {
                Log.warnf("Could not fetch existing test files of %s: %s", ref.key(), e.getMessage());
            }
        }
        List<RelatedTestFile> files = selection.files().stream()
                .map(f -> new RelatedTestFile(f.path(), f.reason(), f.sources(), Files.exists(DataStore.resolveInside(dir, f.path()))))
                .toList();
        RelatedTests related = new RelatedTests(rules.relatedTests.limit(), selection.candidates(), selection.truncated(),
                structure.truncated(), files);
        store.write(prDirFile(ref, "related.json"), related);
        return related;
    }

    /** At most this many API contracts are read per repository. */
    private static final int MAX_CONTRACTS = 10;

    /**
     * Repository directories, file paths and API contract operations at the PR head (architecture detection, links
     * through generated API clients, existing tests); fetched once (again when read by an older version), empty if
     * unavailable.
     */
    private RepoStructure structure(PrRef ref, Snapshot snapshot) {
        Path file = prDirFile(ref, "structure.json");
        return store.read(file, RepoStructure.class)
                .filter(s -> s.operations() != null && (s.files() != null || ref.number() == 0)).orElseGet(() -> {
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
                s = new RepoStructure(s.directories(), s.truncated(), contracts, operations,
                        s.files() == null ? List.of() : s.files());
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
        store.deleteTree(store.baseSourcesDir(ref)); // the base may have moved (rebase, new target branch)
        store.deleteTree(store.relatedSourcesDir(ref)); // existing tests are re-selected and loaded for the new head
        try {
            Files.deleteIfExists(prDirFile(ref, "related.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        fetchBaseSources(ref, snapshot, wantedBaseSources(ref, snapshot, rules()));
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
        fetchFiles(ref, wanted.stream().map(DiffFile::path).toList(), snapshot.pr().headSha(), store.sourcesDir(ref));
    }

    /** Old versions of changed test files at the PR's base commit, stored under sources-base/ (old path). */
    private void fetchBaseSources(PrRef ref, Snapshot snapshot, List<DiffFile> wanted) {
        if (wanted.isEmpty()) {
            return;
        }
        fetchFiles(ref, wanted.stream().map(Specs::basePath).toList(), snapshot.pr().baseSha(), store.baseSourcesDir(ref));
    }

    /** Fetches files at a commit into dir (same relative paths); a file that fails is logged and skipped. */
    private void fetchFiles(PrRef ref, List<String> paths, String sha, Path dir) {
        ReviewProvider provider = provider(ref.provider());
        Semaphore limit = new Semaphore(8);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (String path : paths) {
                tasks.add(pool.submit(() -> {
                    limit.acquireUninterruptibly();
                    try {
                        provider.fileContent(ref, path, sha)
                                .ifPresent(text -> store.writeText(DataStore.resolveInside(dir, path), text));
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
                        s.log() == null ? List.of() : s.log(), s.okAt() == null ? Map.of() : s.okAt(), s.reload()))
                .orElseGet(() -> {
                    String headSha = store.read(snapshotFile(ref), Snapshot.class).map(s -> s.pr().headSha()).orElse(null);
                    return new ReviewState(Model.SCHEMA_VERSION, ref.key(), headSha, new TreeMap<>(), new ArrayList<>(), new TreeMap<>(), null);
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
            ReviewState next = new ReviewState(Model.SCHEMA_VERSION, ref.key(), headSha, out, log, outAt, current.reload());
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
        return MarkCarry.sortedKeys(keys);
    }

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

    /** Resolves a thread of comments on the code host or opens it again; answers with the comments as they are now. */
    public List<Comment> resolveThread(PrRef ref, String thread, boolean resolved) {
        if (ref.number() == 0) {
            throw new ProviderException(400, "Beim ganzen Repository gibt es keinen Pull Request, an den Kommentare gehen könnten");
        }
        ReviewProvider provider = provider(ref.provider());
        provider.resolveThread(ref, thread, resolved);
        List<Comment> comments = provider.comments(ref);
        store.write(prDirFile(ref, "comments.json"), comments);
        return comments;
    }

    // ------------------------------------------------------------------ approve & merge

    /** Where the PR stands on the code host; empty for a repository and for hosts without approve/merge here. */
    public Optional<Model.PrStatus> status(PrRef ref) {
        return ref.number() == 0 ? Optional.empty() : provider(ref.provider()).status(ref);
    }

    /** Approves the PR at the head that was loaded (or revokes the approval); answers with the status afterwards. */
    public Model.PrStatus approve(PrRef ref, boolean approved) {
        requirePullRequest(ref);
        ReviewProvider provider = provider(ref.provider());
        // revoking needs no reviewed head: it takes back what was said about an older one too
        provider.approve(ref, approved ? reviewedHead(ref, provider) : null, approved);
        return provider.status(ref).orElseThrow();
    }

    /** Merges the PR at the head that was loaded; answers with the status afterwards. */
    public Model.PrStatus merge(PrRef ref, Model.MergeOptions options) {
        requirePullRequest(ref);
        ReviewProvider provider = provider(ref.provider());
        provider.merge(ref, reviewedHead(ref, provider), options);
        return provider.status(ref).orElseThrow();
    }

    private static void requirePullRequest(PrRef ref) {
        if (ref.number() == 0) {
            throw new ProviderException(400, "Ein ganzes Repository lässt sich weder genehmigen noch mergen");
        }
    }

    /**
     * The head commit of the snapshot - what the user has seen. Refuses when the PR has newer commits on the host:
     * nobody should approve or merge code the tool has not shown.
     */
    private String reviewedHead(PrRef ref, ReviewProvider provider) {
        String now = provider.status(ref)
                .orElseThrow(() -> new ProviderException(400, provider.label() + " kann hier nicht genehmigen oder mergen"))
                .headSha();
        String loaded = store.read(snapshotFile(ref), Snapshot.class)
                .orElseThrow(() -> new ProviderException(409, "PR zuerst öffnen")).pr().headSha();
        if (now != null && !now.equals(loaded)) {
            throw new ProviderException(409, "Es gibt neue Commits (" + now.substring(0, Math.min(7, now.length()))
                    + "), die noch nicht geladen sind. Zuerst neu laden.");
        }
        return loaded;
    }

    // ------------------------------------------------------------------ commits & blame

    /**
     * The PR's commits and which commit made each changed line (commits.json; recomputed when the head or the
     * attribution algorithm changed). Commit diffs never change, so they are fetched only here. On a provider
     * error the last result is returned if there is one, else an empty result carrying the error. The findings
     * (Commit-Bezug der Tests) are recomputed at every call from the attribution and the current analysis, so they
     * follow a new analysis or new rules without fetching the commits again; the file is rewritten when they changed.
     */
    public Commits commits(PrRef ref) {
        Snapshot snapshot = snapshot(ref);
        Path file = prDirFile(ref, "commits.json");
        synchronized (locks.computeIfAbsent(ref.key() + "#commits", k -> new Object())) {
            Commits attributed = attributed(ref, snapshot, file);
            List<CommitFinding> findings = commitFindings(ref, snapshot, attributed);
            if (Objects.equals(findings, attributed.findings())) {
                return attributed;
            }
            Commits result = attributed.withFindings(findings);
            if (result.error() == null && snapshot.pr().headSha().equals(result.headSha())) {
                store.write(file, result);
            }
            return result;
        }
    }

    /**
     * Commit-Bezug der Tests ({@link CommitChecks}) for the current analysis; empty for a whole repository, without
     * commits, and when the analysis cannot be had (logged - the commits stay usable).
     */
    private List<CommitFinding> commitFindings(PrRef ref, Snapshot snapshot, Commits commits) {
        if (ref.number() == 0 || commits.commits().isEmpty() || !snapshot.pr().headSha().equals(commits.headSha())) {
            return List.of();
        }
        try {
            Analysis analysis;
            synchronized (lock(ref)) {
                analysis = currentAnalysis(ref, snapshot);
            }
            Map<String, String> sources = new HashMap<>();
            Path dir = store.sourcesDir(ref);
            if (analysis.tests() != null) {
                analysis.tests().stream().map(Model.TestInfo::path).distinct().forEach(p ->
                        store.readText(DataStore.resolveInside(dir, p)).ifPresent(s -> sources.put(p, s)));
            }
            return CommitChecks.find(rules(), commits, analysis, sources);
        } catch (RuntimeException e) {
            Log.warnf("Commit findings of %s not available: %s", ref.key(), e.getMessage());
            return List.of();
        }
    }

    /** The attribution part of commits.json: the stored one if it is current, else computed now (commits() stores it). */
    private Commits attributed(PrRef ref, Snapshot snapshot, Path file) {
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
            return result; // written with its findings by commits()
        } catch (ProviderException e) {
            Log.warnf("Commits of %s not available: %s", ref.key(), e.getMessage());
            return cached.orElseGet(() -> new Commits(Attribution.VERSION, snapshot.pr().headSha(), Instant.now().toString(),
                    List.of(), Map.of(), Map.of(), e.getMessage()));
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

    /**
     * A changed file as a whole at the PR head, with the deleted lines woven in (code view "Ganze Klassen"). Its
     * content comes from sources/; a file the analysis did not need is fetched once.
     */
    public WholeFile wholeFile(PrRef ref, String path) {
        Snapshot snapshot = snapshot(ref);
        DiffFile f = snapshot.files().stream().filter(x -> x.path().equals(path)).findFirst()
                .orElseThrow(() -> new ProviderException(404, "Datei ist nicht Teil des Pull Requests: " + path));
        String sha = snapshot.pr().headSha();
        String source = "";
        if (!"removed".equals(f.status())) {
            Path file = DataStore.resolveInside(store.sourcesDir(ref), path);
            if (!Files.isRegularFile(file)) {
                fetchFiles(ref, List.of(path), sha, store.sourcesDir(ref));
            }
            source = store.readText(file)
                    .orElseThrow(() -> new ProviderException(404, "Inhalt der Datei ist nicht verfügbar: " + path));
        }
        return new WholeFile(path, sha, FileView.whole(CodeText.of(source).lines, f.hunks()));
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
