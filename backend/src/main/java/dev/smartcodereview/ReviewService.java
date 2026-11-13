package dev.smartcodereview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smartcodereview.analysis.Analyzer;
import dev.smartcodereview.analysis.Languages;
import dev.smartcodereview.analysis.Rules;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Analysis;
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
import dev.smartcodereview.model.Model.ReviewState;
import dev.smartcodereview.model.Model.Segment;
import dev.smartcodereview.model.Model.Snapshot;
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.provider.ReviewProvider;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public List<PullRequestSummary> openPullRequests() {
        List<PullRequestSummary> result = new ArrayList<>();
        for (ReviewProvider p : providerBeans) {
            for (PullRequestSummary s : p.openPullRequests()) {
                PrRef ref = new PrRef(s.provider(), s.owner(), s.repo(), s.number());
                result.add(new PullRequestSummary(s.provider(), s.owner(), s.repo(), s.number(), s.title(), s.author(),
                        s.url(), s.draft(), s.createdAt(), s.updatedAt(), s.roles(), progress(ref).orElse(null)));
            }
        }
        return result;
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
        for (DiffFile f : snapshot.files()) {
            store.readText(DataStore.resolveInside(sourcesDir, f.path())).ifPresent(s -> sources.put(f.path(), s));
        }
        long t0 = System.currentTimeMillis();
        Analysis analysis = new Analyzer(rules()).analyze(snapshot, sources);
        Log.infof("Analysed %s in %d ms: %d segments, %d flows", ref.key(), System.currentTimeMillis() - t0,
                analysis.segments().size(), analysis.flows().size());
        store.write(analysisFile(ref), analysis);
        return analysis;
    }

    private Snapshot fetch(PrRef ref) {
        ReviewProvider provider = provider(ref.provider());
        Snapshot snapshot = provider.fetchSnapshot(ref);
        Rules rules = rules();
        Path sourcesDir = store.sourcesDir(ref);
        List<DiffFile> wanted = snapshot.files().stream()
                .filter(f -> !"removed".equals(f.status()) && !f.patchMissing() && Languages.hasSymbols(f.language())
                        && rules.forPath(f.path()) == null)
                .toList();
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
        store.write(snapshotFile(ref), snapshot);
        Log.infof("Fetched %s: %d files, %d sources", ref.key(), snapshot.files().size(), wanted.size());
        return snapshot;
    }

    // ------------------------------------------------------------------ marks

    public ReviewState state(PrRef ref) {
        return store.read(stateFile(ref), ReviewState.class).orElseGet(() -> {
            String headSha = store.read(snapshotFile(ref), Snapshot.class).map(s -> s.pr().headSha()).orElse(null);
            return new ReviewState(Model.SCHEMA_VERSION, ref.key(), headSha, new TreeMap<>(), new ArrayList<>());
        });
    }

    public ReviewState mark(PrRef ref, MarkRequest req) {
        synchronized (lock(ref)) {
            ReviewState current = state(ref);
            Map<String, TreeSet<String>> ok = new TreeMap<>();
            current.ok().forEach((path, keys) -> {
                TreeSet<String> set = new TreeSet<>(LINE_KEY_ORDER);
                set.addAll(keys);
                ok.put(path, set);
            });
            int changed = 0;
            for (LineMark m : req.lines()) {
                TreeSet<String> keys = ok.computeIfAbsent(m.path(), p -> new TreeSet<>(LINE_KEY_ORDER));
                changed += (req.ok() ? keys.add(m.key()) : keys.remove(m.key())) ? 1 : 0;
            }
            Map<String, List<String>> out = new TreeMap<>();
            ok.forEach((path, keys) -> {
                if (!keys.isEmpty()) {
                    out.put(path, List.copyOf(keys));
                }
            });
            List<LogEntry> log = new ArrayList<>(current.log());
            log.add(new LogEntry(Instant.now().toString(), req.by() == null ? "ui" : req.by(), req.ok() ? "ok" : "unok",
                    req.scope() == null ? "lines" : req.scope(), changed));
            if (log.size() > MAX_LOG_ENTRIES) {
                log = new ArrayList<>(log.subList(log.size() - MAX_LOG_ENTRIES, log.size()));
            }
            String headSha = current.headSha() != null ? current.headSha()
                    : store.read(snapshotFile(ref), Snapshot.class).map(s -> s.pr().headSha()).orElse(null);
            ReviewState next = new ReviewState(Model.SCHEMA_VERSION, ref.key(), headSha, out, log);
            store.write(stateFile(ref), next);
            return next;
        }
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
        int total = 0, ok = 0;
        for (Segment s : analysis.get().segments()) {
            List<String> marked = st.ok().getOrDefault(s.path(), List.of());
            for (String key : s.lineKeys()) {
                total++;
                if (marked.contains(key)) {
                    ok++;
                }
            }
        }
        return Optional.of(new Progress(ok, total));
    }

    // ------------------------------------------------------------------ comments

    public List<Comment> comments(PrRef ref) {
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
        if (comment.body() == null || comment.body().isBlank()) {
            throw new ProviderException(400, "Kommentar ist leer");
        }
        String headSha = store.read(snapshotFile(ref), Snapshot.class).map(s -> s.pr().headSha())
                .orElseThrow(() -> new ProviderException(409, "PR zuerst öffnen"));
        return provider(ref.provider()).postComment(ref, headSha, comment);
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
