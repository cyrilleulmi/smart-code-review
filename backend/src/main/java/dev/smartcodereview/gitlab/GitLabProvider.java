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
import dev.smartcodereview.provider.ReviewProvider;
import dev.smartcodereview.provider.Tokens;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab (gitlab.com or self-managed) via REST API v4. Merge requests are handled as "pull requests".
 * Without a token, public projects can still be opened by URL (read-only, rate-limited by GitLab).
 * Nested namespaces are encoded as group~subgroup in {@link PrRef#owner()}.
 */
@ApplicationScoped
public class GitLabProvider implements ReviewProvider {

    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_TREE_PAGES = 30;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "scr.gitlab.url", defaultValue = "https://gitlab.com")
    String baseUrl;

    @ConfigProperty(name = "scr.gitlab.token")
    Optional<String> configuredToken;

    private JsonHttp http;
    private Tokens.Lazy token;
    private volatile String username;

    @PostConstruct
    void init() {
        baseUrl = baseUrl.replaceAll("/+$", "");
        http = new JsonHttp(mapper, label());
        token = new Tokens.Lazy(() -> Tokens.first(configuredToken, "GITLAB_TOKEN", "GL_TOKEN")
                .or(() -> Tokens.fromCommand("glab", "config", "get", "token", "--host", URI.create(baseUrl).getHost())));
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

    @Override
    public User currentUser() {
        requireToken();
        JsonNode u = getJson("/user");
        username = u.path("username").asText();
        return new User(username, u.path("name").asText(null), u.path("avatar_url").asText(null));
    }

    @Override
    public Optional<PrRef> parseUrl(String url) {
        Matcher m = Pattern.compile("^" + Pattern.quote(baseUrl) + "/(.+)/([^/]+)/-/merge_requests/(\\d+).*$").matcher(url.trim());
        return m.matches()
                ? Optional.of(new PrRef(id(), m.group(1).replace('/', '~'), m.group(2), Integer.parseInt(m.group(3))))
                : Optional.empty();
    }

    @Override
    public List<PullRequestSummary> openPullRequests() {
        requireToken();
        String me = username != null ? username : currentUser().login();
        Map<String, PullRequestSummary> byKey = new LinkedHashMap<>();
        listInto(byKey, "/merge_requests?state=opened&scope=all&per_page=100&reviewer_username="
                + URLEncoder.encode(me, StandardCharsets.UTF_8), "review-requested");
        listInto(byKey, "/merge_requests?state=opened&scope=created_by_me&per_page=100", "authored");
        return new ArrayList<>(byKey.values());
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
            List<String> roles = new ArrayList<>(existing == null ? List.of() : existing.roles());
            roles.add(role);
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
            additions += add;
            deletions += del;
            files.add(new DiffFile(path, "renamed".equals(status) ? d.path("old_path").asText() : null, status,
                    add, del, Languages.of(path), hunks.isEmpty(), hunks));
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

    /** `/diffs` (GitLab 15.7+, paginated), falling back to the older `/changes`. */
    private JsonNode diffs(String base) {
        try {
            var all = mapper.createArrayNode();
            for (int page = 1; page <= 30; page++) {
                JsonNode batch = getJson(base + "/diffs?per_page=100&page=" + page);
                batch.forEach(all::add);
                if (batch.size() < 100) {
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
        for (int page = 1; page <= MAX_TREE_PAGES; page++) {
            JsonNode items = getJson(projectPath(ref) + "/repository/tree?recursive=true&per_page=100&ref=" + sha + "&page=" + page);
            items.forEach(n -> {
                String path = n.path("path").asText();
                if ("tree".equals(n.path("type").asText())) {
                    dirs.add(path);
                } else if ("blob".equals(n.path("type").asText()) && OpenApi.isContract(path)) {
                    contracts.add(path);
                }
            });
            if (items.size() < 100) {
                return new Model.RepoStructure(dirs, false, contracts, null);
            }
        }
        return new Model.RepoStructure(dirs, true, contracts, null);
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
        for (JsonNode note : discussion.path("notes")) {
            if (note.path("system").asBoolean(false)) {
                continue;
            }
            Comment c = toComment(note, root, ref);
            if (root == null) {
                root = c;
            }
            result.add(c);
        }
        return result;
    }

    /** root: first note of the discussion (null for the root itself); replies inherit its kind and line. */
    private Comment toComment(JsonNode note, Comment root, PrRef ref) {
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
                note.path("created_at").asText(), url, root == null ? null : root.id());
    }

    @Override
    public Comment postComment(PrRef ref, Snapshot snapshot, NewComment c) {
        requireToken();
        String base = mrPath(ref);
        ObjectNode body = mapper.createObjectNode().put("body", c.body());

        if (c.inReplyTo() != null) {
            String discussionId = discussionOf(ref, c.inReplyTo());
            JsonNode note = postJson(base + "/discussions/" + discussionId + "/notes", body);
            Comment reply = toComment(note, null, ref);
            return new Comment(reply.id(), "review", reply.path(), reply.line(), reply.side(), reply.key(), reply.body(),
                    reply.author(), reply.avatarUrl(), reply.createdAt(), reply.url(), c.inReplyTo());
        }
        if (c.path() == null || c.line() == null) {
            return toComment(postJson(base + "/notes", body), null, ref);
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
        return toComment(discussion.path("notes").get(0), null, ref);
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
        return b;
    }
}
