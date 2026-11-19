package dev.smartcodereview.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.smartcodereview.analysis.Languages;
import dev.smartcodereview.analysis.OpenApi;
import dev.smartcodereview.diff.PatchParser;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Comment;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.DiffLine;
import dev.smartcodereview.model.Model.Hunk;
import dev.smartcodereview.model.Model.NewComment;
import dev.smartcodereview.model.Model.PrRef;
import dev.smartcodereview.model.Model.PullRequestInfo;
import dev.smartcodereview.model.Model.PullRequestSummary;
import dev.smartcodereview.model.Model.Snapshot;
import dev.smartcodereview.model.Model.User;
import dev.smartcodereview.provider.JsonHttp;
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.provider.RepoLists;
import dev.smartcodereview.provider.ReviewProvider;
import dev.smartcodereview.provider.Tokens;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab (gitlab.com or self-managed) via REST API v4. Merge requests are handled as "pull requests".
 * Without a token, public projects can still be opened by URL (read-only, rate-limited by GitLab).
 * Nested namespaces are encoded as group~subgroup in {@link PrRef#owner()}.
 * A self-managed GitLab behind an SSO proxy also needs the session cookies of the proxy (scr.gitlab.cookie).
 */
@ApplicationScoped
public class GitLabProvider implements ReviewProvider {

    private static final String GITLAB_COM = "https://gitlab.com";
    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_TREE_PAGES = 30;
    /** GitLab 16.x answers 500 for more than 30 merge request diffs per page (newer versions accept 100). */
    static final int MR_DIFFS_PER_PAGE = 30;
    private static final int MAX_MR_DIFF_PAGES = 100;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "scr.gitlab.url", defaultValue = GITLAB_COM)
    String baseUrl;

    @ConfigProperty(name = "scr.gitlab.token")
    Optional<String> configuredToken;

    @ConfigProperty(name = "scr.gitlab.cookie")
    Optional<String> configuredCookie = Optional.empty();

    private JsonHttp http;
    private Tokens.Lazy token;
    /**
     * Value of the Cookie header of every request (session cookies of an SSO proxy), null for none. Starts as
     * scr.gitlab.cookie and can be replaced while running: such cookies expire regularly.
     */
    private volatile String cookie;
    private volatile String username;

    @PostConstruct
    void init() {
        baseUrl = baseUrl.replaceAll("/+$", "");
        http = new JsonHttp(mapper, label());
        token = new Tokens.Lazy(() -> Tokens.first(configuredToken, "GITLAB_TOKEN", "GL_TOKEN")
                .or(() -> Tokens.fromCommand("glab", "config", "get", "token", "--host", URI.create(baseUrl).getHost())));
        try {
            cookie = cookieValue(configuredCookie.orElse(null));
        } catch (ProviderException e) {
            Log.warnf("Ignoring scr.gitlab.cookie: %s", e.getMessage());
        }
    }

    /**
     * The value of a Cookie header as the user gives it: trimmed, a leading "Cookie:" (copied along from the
     * browser's developer tools) removed; null when blank. Characters a header cannot carry are refused.
     */
    static String cookieValue(String raw) {
        String value = raw == null ? "" : raw.strip().replaceFirst("(?i)^cookie:\\s*", "");
        if (value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new ProviderException(400, "Das Cookie enthält Zeichen, die in einem HTTP-Header nicht erlaubt sind "
                    + "(z. B. Zeilenumbrüche).");
        }
        return value.isEmpty() ? null : value;
    }

    @Override
    public String id() {
        return "gitlab";
    }

    @Override
    public String label() {
        return "GitLab";
    }

    @Override
    public String host() {
        return baseUrl;
    }

    @Override
    public boolean hasCredentials() {
        return token.get().isPresent();
    }

    /** A proxy only stands in front of a self-managed GitLab; gitlab.com offers the cookie only when one is configured. */
    @Override
    public Optional<Boolean> cookieSet() {
        boolean set = cookie != null;
        return set || !GITLAB_COM.equals(baseUrl) ? Optional.of(set) : Optional.empty();
    }

    @Override
    public void setCookie(String value) {
        cookie = cookieValue(value);
    }

    @Override
    public User currentUser() {
        requireToken();
        JsonNode u = getJson("/user");
        username = u.path("username").asText();
        return new User(username, u.path("name").asText(null), u.path("avatar_url").asText(null));
    }

    @Override
    public Optional<PrRef> parseUrl(String url) {
        String u = url.trim();
        Matcher m = Pattern.compile("^" + Pattern.quote(baseUrl) + "/(.+)/([^/]+)/-/merge_requests/(\\d+).*$").matcher(u);
        if (m.matches()) {
            return Optional.of(new PrRef(id(), m.group(1).replace('/', '~'), m.group(2), Integer.parseInt(m.group(3))));
        }
        // project URL (whole repository): group[/sub]/project, optionally .git or /-/tree/<branch>
        Matcher r = Pattern.compile("^" + Pattern.quote(baseUrl) + "/([^?#]+?)/([^/?#]+?)(?:\\.git)?(?:/-/tree/[^?#]*)?/?(?:[?#].*)?$").matcher(u);
        if (r.matches() && !r.group(1).contains("/-") && !r.group(1).startsWith("-") && !r.group(2).equals("-")) {
            return Optional.of(new PrRef(id(), r.group(1).replace('/', '~'), r.group(2), 0));
        }
        return Optional.empty();
    }

    @Override
    public List<PullRequestSummary> openPullRequests() {
        requireToken();
        String me = username != null ? username : currentUser().login();
        Map<String, PullRequestSummary> byKey = new LinkedHashMap<>();
        listInto(byKey, "/merge_requests?state=opened&scope=all&per_page=100&reviewer_username="
                + URLEncoder.encode(me, StandardCharsets.UTF_8), "review-requested");
        listInto(byKey, "/merge_requests?state=opened&scope=created_by_me&per_page=100", "authored");
        listInto(byKey, "/merge_requests?state=opened&scope=assigned_to_me&per_page=100", "involved");
        return new ArrayList<>(byKey.values());
    }

    @Override
    public List<Model.RepositorySummary> repositories(String query) {
        requireToken();
        String q = query == null || query.isBlank() ? "" : "&search=" + enc(query.trim());
        List<Model.RepositorySummary> mine =
                projects("/projects?membership=true&simple=true&order_by=last_activity_at&per_page=50" + q);
        List<Model.RepositorySummary> visible = q.isEmpty() ? List.of()
                : projects("/projects?simple=true&order_by=last_activity_at&per_page=20" + q);
        return RepoLists.merge(mine, visible);
    }

    private List<Model.RepositorySummary> projects(String path) {
        List<Model.RepositorySummary> result = new ArrayList<>();
        for (JsonNode p : getJson(path)) {
            String full = p.path("path_with_namespace").asText();
            int slash = full.lastIndexOf('/');
            if (slash < 0) {
                continue;
            }
            result.add(new Model.RepositorySummary(id(), full.substring(0, slash).replace('/', '~'),
                    full.substring(slash + 1), p.path("description").asText(""), p.path("web_url").asText(),
                    p.path("last_activity_at").asText("")));
        }
        return result;
    }

    @Override
    public List<PullRequestSummary> repositoryPullRequests(String owner, String repo) {
        requireToken();
        String me = username != null ? username : currentUser().login();
        JsonNode list = getJson(projectPath(new PrRef(id(), owner, repo, 0))
                + "/merge_requests?state=opened&order_by=updated_at&per_page=100");
        List<PullRequestSummary> result = new ArrayList<>();
        for (JsonNode mr : list) {
            String author = mr.path("author").path("username").asText();
            List<String> roles = new ArrayList<>();
            if (author.equalsIgnoreCase(me)) {
                roles.add("authored");
            }
            for (JsonNode rv : mr.path("reviewers")) {
                if (rv.path("username").asText().equalsIgnoreCase(me)) {
                    roles.add("review-requested");
                }
            }
            result.add(new PullRequestSummary(id(), owner, repo, mr.path("iid").asInt(), mr.path("title").asText(),
                    author, mr.path("web_url").asText(), isDraft(mr), mr.path("created_at").asText(),
                    mr.path("updated_at").asText(), roles, null));
        }
        return result;
    }

    @Override
    public List<PullRequestSummary> searchPullRequests(String query) {
        requireToken();
        String me = username != null ? username : currentUser().login();
        Map<String, PullRequestSummary> byKey = new LinkedHashMap<>();
        listInto(byKey, "/merge_requests?scope=all&state=opened&per_page=50&search=" + enc(query.trim()), null);
        List<PullRequestSummary> result = new ArrayList<>();
        for (PullRequestSummary s : byKey.values()) {
            result.add(new PullRequestSummary(s.provider(), s.owner(), s.repo(), s.number(), s.title(), s.author(),
                    s.url(), s.draft(), s.createdAt(), s.updatedAt(),
                    s.author().equalsIgnoreCase(me) ? List.of("authored") : List.of(), null));
        }
        return result;
    }

    private void listInto(Map<String, PullRequestSummary> byKey, String path, String role) {
        for (JsonNode mr : getJson(path)) {
            String full = mr.path("references").path("full").asText(); // group/sub/project!12
            String projectPath = full.substring(0, full.lastIndexOf('!'));
            int slash = projectPath.lastIndexOf('/');
            String owner = projectPath.substring(0, slash).replace('/', '~');
            String repo = projectPath.substring(slash + 1);
            int iid = mr.path("iid").asInt();
            String key = projectPath + "!" + iid;
            PullRequestSummary existing = byKey.get(key);
            if ("involved".equals(role) && existing != null && !existing.roles().isEmpty()) {
                continue; // keep the list clean: "involved" only for PRs without a more specific role
            }
            List<String> roles = new ArrayList<>(existing == null ? List.of() : existing.roles());
            if (role != null && !roles.contains(role)) {
                roles.add(role);
            }
            byKey.put(key, new PullRequestSummary(id(), owner, repo, iid, mr.path("title").asText(),
                    mr.path("author").path("username").asText(), mr.path("web_url").asText(), isDraft(mr),
                    mr.path("created_at").asText(), mr.path("updated_at").asText(), roles, null));
        }
    }

    @Override
    public Snapshot fetchSnapshot(PrRef ref) {
        String base = mrPath(ref);
        JsonNode mr = getJson(base);
        JsonNode refs = mr.path("diff_refs");

        List<DiffFile> files = new ArrayList<>();
        JsonNode diffs = diffs(base);
        int additions = 0, deletions = 0;
        for (JsonNode d : diffs) {
            DiffFile f = diffFile(d);
            additions += f.additions();
            deletions += f.deletions();
            files.add(f);
        }

        PullRequestInfo info = new PullRequestInfo(id(), ref.owner(), ref.repo(), ref.number(),
                mr.path("title").asText(), mr.path("description").asText(""), mr.path("author").path("username").asText(),
                mr.path("web_url").asText(), isDraft(mr), mr.path("state").asText(),
                mr.path("target_branch").asText(), mr.path("source_branch").asText(),
                refs.path("base_sha").asText(), refs.path("head_sha").asText(mr.path("sha").asText()),
                mr.path("created_at").asText(), mr.path("updated_at").asText(),
                additions, deletions, files.size(), refs.path("start_sha").asText(null));
        return new Snapshot(Model.SCHEMA_VERSION, Instant.now().toString(), info, files);
    }

    /** One entry of a GitLab diff list (merge request or commit) as a DiffFile. */
    private static DiffFile diffFile(JsonNode d) {
        boolean deleted = d.path("deleted_file").asBoolean(false);
        String path = deleted ? d.path("old_path").asText() : d.path("new_path").asText();
        String status = d.path("new_file").asBoolean(false) ? "added" : deleted ? "removed"
                : d.path("renamed_file").asBoolean(false) ? "renamed" : "modified";
        List<Hunk> hunks = PatchParser.parse(d.path("diff").asText(""));
        int add = 0, del = 0;
        for (Hunk h : hunks) {
            for (DiffLine l : h.lines()) {
                add += "add".equals(l.type()) ? 1 : 0;
                del += "del".equals(l.type()) ? 1 : 0;
            }
        }
        boolean missing = hunks.isEmpty() || d.path("too_large").asBoolean(false) || d.path("collapsed").asBoolean(false);
        return new DiffFile(path, "renamed".equals(status) ? d.path("old_path").asText() : null, status,
                add, del, Languages.of(path), missing, hunks);
    }

    @Override
    public List<Model.Commit> commits(PrRef ref) {
        List<JsonNode> raw = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode batch = getJson(mrPath(ref) + "/commits?per_page=100&page=" + page);
            batch.forEach(raw::add);
            if (batch.size() < 100) {
                break;
            }
        }
        // the merge request endpoint may omit parent_ids; the commit endpoint has them
        boolean needDetails = raw.stream().anyMatch(c -> c.path("parent_ids").isEmpty());
        List<Model.Commit> result = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore limit = new Semaphore(6);
            List<Future<JsonNode>> details = raw.stream().map(c -> pool.submit(() -> {
                if (!needDetails) {
                    return c;
                }
                limit.acquireUninterruptibly();
                try {
                    return getJson(projectPath(ref) + "/repository/commits/" + c.path("id").asText());
                } finally {
                    limit.release();
                }
            })).toList();
            for (Future<JsonNode> f : details) {
                result.add(toCommit(ref, get(f)));
            }
        }
        Collections.reverse(result); // GitLab lists the newest first
        return result;
    }

    @Override
    public List<Model.Commit> branchCommits(PrRef ref, String sha, int max) {
        List<Model.Commit> result = new ArrayList<>();
        for (int page = 1; result.size() < max; page++) {
            JsonNode batch = getJson(projectPath(ref) + "/repository/commits?ref_name=" + sha
                    + "&per_page=" + Math.min(100, max) + "&page=" + page);
            batch.forEach(c -> result.add(toCommit(ref, c)));
            if (batch.size() < Math.min(100, max)) {
                break;
            }
        }
        return result.size() > max ? result.subList(0, max) : result;
    }

    @Override
    public Model.Repository repository(PrRef ref) {
        JsonNode p = getJson(projectPath(ref));
        String branch = p.path("default_branch").asText("main");
        JsonNode head = getJson(projectPath(ref) + "/repository/branches/" + enc(branch)).path("commit");
        return new Model.Repository(branch, head.path("id").asText(), p.path("web_url").asText(),
                p.path("description").asText(""), head.path("authored_date").asText(null));
    }

    @Override
    public byte[] archive(PrRef ref, String sha) {
        String path = projectPath(ref) + "/repository/archive.tar.gz?sha=" + sha;
        HttpResponse<byte[]> res = http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new ProviderException(res.statusCode() == 404 ? 404 : 502, "GitLab " + res.statusCode() + " auf " + path);
        }
        return res.body();
    }

    private static JsonNode get(Future<JsonNode> f) {
        try {
            return f.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof ProviderException pe ? pe : new ProviderException(502, String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(503, "Abgebrochen");
        }
    }

    private Model.Commit toCommit(PrRef ref, JsonNode c) {
        String sha = c.path("id").asText();
        String message = c.path("message").asText(c.path("title").asText(""));
        List<String> parents = new ArrayList<>();
        c.path("parent_ids").forEach(p -> parents.add(p.asText()));
        return new Model.Commit(sha, c.path("title").asText(message.lines().findFirst().orElse("")), message,
                c.path("author_name").asText(null), null, null, c.path("authored_date").asText(c.path("created_at").asText(null)),
                c.path("web_url").asText(commitUrl(ref, sha)), parents, 0, 0, List.of());
    }

    private String commitUrl(PrRef ref, String sha) {
        return host() + "/" + ref.owner().replace('~', '/') + "/" + ref.repo() + "/-/commit/" + sha;
    }

    @Override
    public List<DiffFile> commitDiff(PrRef ref, String sha) {
        List<DiffFile> files = new ArrayList<>();
        for (int page = 1; page <= 30; page++) {
            JsonNode batch = getJson(projectPath(ref) + "/repository/commits/" + sha + "/diff?per_page=100&page=" + page);
            batch.forEach(d -> files.add(diffFile(d)));
            if (batch.size() < 100) {
                break;
            }
        }
        return files;
    }

    /** GitLab returns blame as consecutive groups of lines, each with its commit. */
    @Override
    public Model.Blame blame(PrRef ref, String path, String sha) {
        JsonNode groups = getJson(projectPath(ref) + "/repository/files/" + enc(path) + "/blame?ref=" + sha);
        List<Model.BlameRange> ranges = new ArrayList<>();
        Map<String, Model.Commit> commits = new LinkedHashMap<>();
        int line = 1;
        for (JsonNode g : groups) {
            int count = g.path("lines").size();
            JsonNode c = g.path("commit");
            if (count > 0) {
                ranges.add(new Model.BlameRange(line, line + count - 1, c.path("id").asText()));
                line += count;
            }
            commits.computeIfAbsent(c.path("id").asText(), k -> toCommit(ref, c));
        }
        return new Model.Blame(path, sha, ranges, new ArrayList<>(commits.values()));
    }

    /** `/diffs` (GitLab 15.7+, paginated), falling back to the older `/changes`. */
    private JsonNode diffs(String base) {
        try {
            var all = mapper.createArrayNode();
            for (int page = 1; page <= MAX_MR_DIFF_PAGES; page++) {
                JsonNode batch = getJson(base + "/diffs?per_page=" + MR_DIFFS_PER_PAGE + "&page=" + page);
                batch.forEach(all::add);
                if (batch.size() < MR_DIFFS_PER_PAGE) {
                    break;
                }
            }
            return all;
        } catch (ProviderException e) {
            if (e.status() != 404) {
                throw e;
            }
            return getJson(base + "/changes").path("changes");
        }
    }

    /** Directories via the paginated tree API; stops after MAX_TREE_PAGES pages (marked as truncated). */
    @Override
    public Model.RepoStructure directories(PrRef ref, String sha) {
        List<String> dirs = new ArrayList<>();
        List<String> contracts = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (int page = 1; page <= MAX_TREE_PAGES; page++) {
            JsonNode items = getJson(projectPath(ref) + "/repository/tree?recursive=true&per_page=100&ref=" + sha + "&page=" + page);
            items.forEach(n -> {
                String path = n.path("path").asText();
                if ("tree".equals(n.path("type").asText())) {
                    dirs.add(path);
                } else if ("blob".equals(n.path("type").asText())) {
                    files.add(path);
                    if (OpenApi.isContract(path)) {
                        contracts.add(path);
                    }
                }
            });
            if (items.size() < 100) {
                return new Model.RepoStructure(dirs, false, contracts, null, files);
            }
        }
        return new Model.RepoStructure(dirs, true, contracts, null, files);
    }

    @Override
    public Optional<String> fileContent(PrRef ref, String path, String sha) {
        HttpResponse<byte[]> res = http.send(request(projectPath(ref) + "/repository/files/" + enc(path) + "/raw?ref=" + sha)
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return res.statusCode() == 200 ? Tokens.text(res.body(), MAX_FILE_BYTES) : Optional.empty();
    }

    @Override
    public List<Comment> comments(PrRef ref) {
        List<Comment> result = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode batch = getJson(mrPath(ref) + "/discussions?per_page=100&page=" + page);
            for (JsonNode discussion : batch) {
                result.addAll(notesOf(discussion, ref));
            }
            if (batch.size() < 100) {
                break;
            }
        }
        result.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return result;
    }

    private List<Comment> notesOf(JsonNode discussion, PrRef ref) {
        List<Comment> result = new ArrayList<>();
        Comment root = null;
        String thread = discussion.path("id").asText(null);
        Boolean resolved = resolved(discussion.path("notes"));
        for (JsonNode note : discussion.path("notes")) {
            if (note.path("system").asBoolean(false)) {
                continue;
            }
            Comment c = toComment(note, root, ref, thread, resolved);
            if (root == null) {
                root = c;
            }
            result.add(c);
        }
        return result;
    }

    /**
     * State of a thread: null when none of its notes can be resolved (a plain comment), else whether all that can
     * be are resolved - GitLab resolves and reopens the notes of a thread together.
     */
    private static Boolean resolved(Iterable<JsonNode> notes) {
        boolean resolvable = false, open = false;
        for (JsonNode note : notes) {
            if (note.path("resolvable").asBoolean(false)) {
                resolvable = true;
                open |= !note.path("resolved").asBoolean(false);
            }
        }
        return resolvable ? !open : null;
    }

    /**
     * root: first note of the discussion (null for the root itself); replies inherit its kind and line.
     * thread, resolved: id and state of the discussion.
     */
    private Comment toComment(JsonNode note, Comment root, PrRef ref, String thread, Boolean resolved) {
        JsonNode pos = note.path("position");
        boolean inline = pos.isObject() && "text".equals(pos.path("position_type").asText());
        String path = null, side = null, key = null;
        Integer line = null;
        if (inline) {
            Integer newLine = pos.hasNonNull("new_line") ? pos.get("new_line").asInt() : null;
            Integer oldLine = pos.hasNonNull("old_line") ? pos.get("old_line").asInt() : null;
            path = newLine != null ? pos.path("new_path").asText() : pos.path("old_path").asText();
            line = newLine != null ? newLine : oldLine;
            side = newLine != null ? "RIGHT" : "LEFT";
            key = line == null ? null : (newLine != null ? "N" : "O") + line;
        }
        String url = host() + "/" + ref.owner().replace('~', '/') + "/" + ref.repo() + "/-/merge_requests/"
                + ref.number() + "#note_" + note.path("id").asLong();
        if (root != null && !inline) {
            path = root.path();
            line = root.line();
            side = root.side();
            key = root.key();
        }
        String kind = inline || (root != null && "review".equals(root.kind())) ? "review" : "issue";
        return new Comment(note.path("id").asLong(), kind, path, line, side, key, note.path("body").asText(),
                note.path("author").path("username").asText(), note.path("author").path("avatar_url").asText(null),
                note.path("created_at").asText(), url, root == null ? null : root.id(), thread, resolved);
    }

    /** PUT /discussions/:id?resolved= resolves or reopens all resolvable notes of the thread. */
    @Override
    public void resolveThread(PrRef ref, String thread, boolean resolved) {
        requireToken();
        String path = mrPath(ref) + "/discussions/" + enc(thread) + "?resolved=" + resolved;
        http.json(request(path).PUT(HttpRequest.BodyPublishers.noBody()).build(), path);
    }

    @Override
    public Comment postComment(PrRef ref, Snapshot snapshot, NewComment c) {
        requireToken();
        String base = mrPath(ref);
        ObjectNode body = mapper.createObjectNode().put("body", c.body());

        if (c.inReplyTo() != null) {
            String discussionId = discussionOf(ref, c.inReplyTo());
            JsonNode note = postJson(base + "/discussions/" + discussionId + "/notes", body);
            Comment reply = toComment(note, null, ref, discussionId, resolved(List.of(note)));
            return new Comment(reply.id(), "review", reply.path(), reply.line(), reply.side(), reply.key(), reply.body(),
                    reply.author(), reply.avatarUrl(), reply.createdAt(), reply.url(), c.inReplyTo(), reply.thread(),
                    reply.resolved());
        }
        if (c.path() == null || c.line() == null) {
            return toComment(postJson(base + "/notes", body), null, ref, null, null); // a plain comment, no thread
        }

        PullRequestInfo pr = snapshot.pr();
        String oldPath = snapshot.files().stream().filter(f -> f.path().equals(c.path())).findFirst()
                .map(f -> f.previousPath() != null ? f.previousPath() : f.path()).orElse(c.path());
        ObjectNode position = body.putObject("position")
                .put("position_type", "text")
                .put("base_sha", pr.baseSha())
                .put("start_sha", pr.startSha() != null ? pr.startSha() : pr.baseSha())
                .put("head_sha", pr.headSha())
                .put("old_path", oldPath)
                .put("new_path", c.path());
        if ("LEFT".equals(c.side())) {
            position.put("old_line", c.line());
        } else {
            position.put("new_line", c.line());
            if (c.oldLine() != null) {
                position.put("old_line", c.oldLine()); // unchanged context line: GitLab wants both
            }
        }
        JsonNode discussion = postJson(base + "/discussions", body);
        return notesOf(discussion, ref).get(0);
    }

    // ---------------------------------------------------------------- approve & merge

    @Override
    public Optional<Model.PrStatus> status(PrRef ref) {
        JsonNode mr = getJson(mrPath(ref));
        JsonNode approvals = getJson(mrPath(ref) + "/approvals");
        List<String> approvedBy = new ArrayList<>();
        approvals.path("approved_by").forEach(a -> approvedBy.add(a.path("user").path("username").asText()));
        String state = switch (mr.path("state").asText()) {
            case "merged" -> "merged";
            case "closed" -> "closed";
            default -> "open"; // opened, locked (being merged)
        };
        String blocker = "open".equals(state) ? mergeBlocker(mr) : null;
        return Optional.of(new Model.PrStatus(state, isDraft(mr),
                mr.path("diff_refs").path("head_sha").asText(mr.path("sha").asText(null)), approvedBy,
                approvals.path("user_has_approved").asBoolean(false),
                approvals.path("user_has_approved").asBoolean(false) || approvals.path("user_can_approve").asBoolean(false),
                approvals.path("approvals_left").asInt(0), mr.path("user").path("can_merge").asBoolean(false),
                "open".equals(state) && blocker == null, blocker, mr.path("squash").asBoolean(false),
                mr.path("force_remove_source_branch").asBoolean(false),
                mr.path("head_pipeline").path("status").asText(null)));
    }

    /**
     * Why GitLab would not merge the merge request now (German), null when it would: detailed_merge_status
     * (GitLab 15.6+), before that only merge_status.
     */
    static String mergeBlocker(JsonNode mr) {
        String detailed = mr.path("detailed_merge_status").asText("");
        if (detailed.isEmpty()) {
            String status = mr.path("merge_status").asText("");
            return "can_be_merged".equals(status) ? null : "Lässt sich nicht mergen (GitLab: " + status + ")";
        }
        return switch (detailed) {
            case "mergeable" -> null;
            case "not_approved" -> "Es fehlen Genehmigungen";
            case "discussions_not_resolved" -> "Es sind noch Threads offen";
            case "draft_status" -> "Der Merge Request ist ein Entwurf";
            case "ci_must_pass" -> "Die Pipeline muss erfolgreich sein";
            case "ci_still_running" -> "Die Pipeline läuft noch";
            case "conflict" -> "Es gibt Merge-Konflikte";
            case "need_rebase" -> "Der Branch muss zuerst rebased werden";
            case "requested_changes" -> "Es sind Änderungen verlangt";
            case "blocked_status" -> "Ein anderer Merge Request blockiert diesen";
            case "external_status_checks" -> "Externe Status-Checks müssen bestehen";
            case "not_open" -> "Der Merge Request ist nicht offen";
            case "checking", "unchecked", "preparing" -> "GitLab prüft noch, ob sich der Merge Request mergen lässt";
            default -> "Lässt sich nicht mergen (GitLab: " + detailed + ")";
        };
    }

    @Override
    public void approve(PrRef ref, String sha, boolean approved) {
        requireToken();
        if (approved) {
            postJson(mrPath(ref) + "/approve", mapper.createObjectNode().put("sha", sha));
        } else {
            postJson(mrPath(ref) + "/unapprove", mapper.createObjectNode());
        }
    }

    @Override
    public void merge(PrRef ref, String sha, Model.MergeOptions options) {
        requireToken();
        ObjectNode body = mapper.createObjectNode().put("sha", sha);
        if (options != null && options.squash() != null) {
            body.put("squash", options.squash());
        }
        if (options != null && options.removeSourceBranch() != null) {
            body.put("should_remove_source_branch", options.removeSourceBranch());
        }
        String path = mrPath(ref) + "/merge";
        try {
            http.json(request(path).header("Content-Type", "application/json").PUT(http.body(body)).build(), path);
        } catch (ProviderException e) {
            if (e.status() != 405 && e.status() != 406 && e.status() != 422) {
                throw e;
            }
            // GitLab only says "Method Not Allowed" / "Branch cannot be merged": ask it why
            String blocker = mergeBlocker(getJson(mrPath(ref)));
            throw new ProviderException(409, "GitLab lehnt den Merge ab (" + e.status() + ")"
                    + (blocker == null ? "" : ": " + blocker));
        }
    }

    private String discussionOf(PrRef ref, long noteId) {
        for (int page = 1; page <= 10; page++) {
            JsonNode batch = getJson(mrPath(ref) + "/discussions?per_page=100&page=" + page);
            for (JsonNode d : batch) {
                for (JsonNode n : d.path("notes")) {
                    if (n.path("id").asLong() == noteId) {
                        return d.path("id").asText();
                    }
                }
            }
            if (batch.size() < 100) {
                break;
            }
        }
        throw new ProviderException(404, "Diskussion zu Kommentar " + noteId + " nicht gefunden");
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isDraft(JsonNode mr) {
        return mr.path("draft").asBoolean(mr.path("work_in_progress").asBoolean(false));
    }

    private static String projectPath(PrRef ref) {
        return "/projects/" + enc(ref.owner().replace('~', '/') + "/" + ref.repo());
    }

    private static String mrPath(PrRef ref) {
        return projectPath(ref) + "/merge_requests/" + ref.number();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void requireToken() {
        if (token.get().isEmpty()) {
            throw new ProviderException(401, "Kein GitLab-Token gefunden. Setze GITLAB_TOKEN (Scope: api) – ohne Token "
                    + "lassen sich nur öffentliche Merge Requests per URL öffnen.");
        }
    }

    private JsonNode getJson(String path) {
        return http.json(request(path).GET().build(), path);
    }

    private JsonNode postJson(String path, JsonNode body) {
        return http.json(request(path).header("Content-Type", "application/json").POST(http.body(body)).build(), path);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v4" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "SmartCodeReview");
        token.get().ifPresent(t -> b.header("PRIVATE-TOKEN", t));
        String c = cookie;
        if (c != null) {
            b.header("Cookie", c);
        }
        return b;
    }
}
