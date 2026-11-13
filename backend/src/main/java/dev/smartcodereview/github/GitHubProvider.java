package dev.smartcodereview.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.smartcodereview.analysis.Languages;
import dev.smartcodereview.diff.PatchParser;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Comment;
import dev.smartcodereview.model.Model.DiffFile;
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

/** GitHub via its REST API. */
@ApplicationScoped
public class GitHubProvider implements ReviewProvider {

    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final Pattern PR_URL = Pattern.compile("^https?://github\\.com/([^/]+)/([^/]+)/pull/(\\d+).*$");

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "scr.github.api-url", defaultValue = "https://api.github.com")
    String apiUrl;

    @ConfigProperty(name = "scr.github.token")
    Optional<String> configuredToken;

    private JsonHttp http;
    private Tokens.Lazy token;

    @PostConstruct
    void init() {
        http = new JsonHttp(mapper, label());
        token = new Tokens.Lazy(() -> Tokens.first(configuredToken, "GITHUB_TOKEN", "GH_TOKEN")
                .or(() -> Tokens.fromCommand("gh", "auth", "token")));
    }

    @Override
    public String id() {
        return "github";
    }

    @Override
    public String label() {
        return "GitHub";
    }

    @Override
    public String host() {
        return "https://github.com";
    }

    @Override
    public boolean hasCredentials() {
        return token.get().isPresent();
    }

    @Override
    public User currentUser() {
        JsonNode u = getJson("/user");
        return new User(u.path("login").asText(), u.path("name").asText(null), u.path("avatar_url").asText(null));
    }

    @Override
    public Optional<PrRef> parseUrl(String url) {
        Matcher m = PR_URL.matcher(url.trim());
        return m.matches() ? Optional.of(new PrRef(id(), m.group(1), m.group(2), Integer.parseInt(m.group(3)))) : Optional.empty();
    }

    @Override
    public List<PullRequestSummary> openPullRequests() {
        Map<String, PullRequestSummary> byKey = new LinkedHashMap<>();
        searchInto(byKey, "is:pr is:open archived:false review-requested:@me", "review-requested");
        searchInto(byKey, "is:pr is:open archived:false author:@me", "authored");
        return new ArrayList<>(byKey.values());
    }

    private void searchInto(Map<String, PullRequestSummary> byKey, String query, String role) {
        JsonNode res = getJson("/search/issues?per_page=100&sort=updated&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        for (JsonNode item : res.path("items")) {
            String[] repoParts = item.path("repository_url").asText().split("/");
            String owner = repoParts[repoParts.length - 2];
            String repo = repoParts[repoParts.length - 1];
            int number = item.path("number").asInt();
            String key = owner + "/" + repo + "#" + number;
            PullRequestSummary existing = byKey.get(key);
            List<String> roles = new ArrayList<>(existing == null ? List.of() : existing.roles());
            roles.add(role);
            byKey.put(key, new PullRequestSummary(id(), owner, repo, number, item.path("title").asText(),
                    item.path("user").path("login").asText(), item.path("html_url").asText(),
                    item.path("draft").asBoolean(false), item.path("created_at").asText(),
                    item.path("updated_at").asText(), roles, null));
        }
    }

    @Override
    public Snapshot fetchSnapshot(PrRef ref) {
        String base = "/repos/" + ref.owner() + "/" + ref.repo() + "/pulls/" + ref.number();
        JsonNode pr = getJson(base);
        PullRequestInfo info = new PullRequestInfo(id(), ref.owner(), ref.repo(), ref.number(),
                pr.path("title").asText(), pr.path("body").asText(""), pr.path("user").path("login").asText(),
                pr.path("html_url").asText(), pr.path("draft").asBoolean(false), pr.path("state").asText(),
                pr.path("base").path("ref").asText(), pr.path("head").path("ref").asText(),
                pr.path("base").path("sha").asText(), pr.path("head").path("sha").asText(),
                pr.path("created_at").asText(), pr.path("updated_at").asText(),
                pr.path("additions").asInt(), pr.path("deletions").asInt(), pr.path("changed_files").asInt(), null);

        List<DiffFile> files = new ArrayList<>();
        for (int page = 1; page <= 30; page++) {
            JsonNode batch = getJson(base + "/files?per_page=100&page=" + page);
            for (JsonNode f : batch) {
                String path = f.path("filename").asText();
                String patch = f.path("patch").asText(null);
                files.add(new DiffFile(path, f.path("previous_filename").asText(null), f.path("status").asText(),
                        f.path("additions").asInt(), f.path("deletions").asInt(), Languages.of(path),
                        patch == null, PatchParser.parse(patch)));
            }
            if (batch.size() < 100) {
                break;
            }
        }
        return new Snapshot(Model.SCHEMA_VERSION, Instant.now().toString(), info, files);
    }

    @Override
    public Model.RepoStructure directories(PrRef ref, String sha) {
        JsonNode tree = getJson("/repos/" + ref.owner() + "/" + ref.repo() + "/git/trees/" + sha + "?recursive=1");
        List<String> dirs = new ArrayList<>();
        tree.path("tree").forEach(n -> {
            if ("tree".equals(n.path("type").asText())) {
                dirs.add(n.path("path").asText());
            }
        });
        return new Model.RepoStructure(dirs, tree.path("truncated").asBoolean(false));
    }

    @Override
    public Optional<String> fileContent(PrRef ref, String path, String sha) {
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20");
        HttpResponse<byte[]> res = http.send(request("/repos/" + ref.owner() + "/" + ref.repo() + "/contents/" + encodedPath + "?ref=" + sha)
                .header("Accept", "application/vnd.github.raw+json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return res.statusCode() == 200 ? Tokens.text(res.body(), MAX_FILE_BYTES) : Optional.empty();
    }

    @Override
    public List<Comment> comments(PrRef ref) {
        String repoBase = "/repos/" + ref.owner() + "/" + ref.repo();
        List<Comment> result = new ArrayList<>();
        for (JsonNode c : getJson(repoBase + "/pulls/" + ref.number() + "/comments?per_page=100")) {
            result.add(reviewComment(c));
        }
        for (JsonNode c : getJson(repoBase + "/issues/" + ref.number() + "/comments?per_page=100")) {
            result.add(issueComment(c));
        }
        result.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return result;
    }

    @Override
    public Comment postComment(PrRef ref, Snapshot snapshot, NewComment c) {
        String repoBase = "/repos/" + ref.owner() + "/" + ref.repo();
        ObjectNode body = mapper.createObjectNode().put("body", c.body());
        if (c.inReplyTo() != null) {
            return reviewComment(postJson(repoBase + "/pulls/" + ref.number() + "/comments/" + c.inReplyTo() + "/replies", body));
        }
        if (c.path() == null || c.line() == null) {
            return issueComment(postJson(repoBase + "/issues/" + ref.number() + "/comments", body));
        }
        body.put("commit_id", snapshot.pr().headSha()).put("path", c.path()).put("line", c.line())
                .put("side", c.side() == null ? "RIGHT" : c.side());
        return reviewComment(postJson(repoBase + "/pulls/" + ref.number() + "/comments", body));
    }

    private Comment reviewComment(JsonNode c) {
        Integer line = c.hasNonNull("line") ? c.get("line").asInt() : null;
        String side = c.path("side").asText("RIGHT");
        String key = line == null ? null : ("LEFT".equals(side) ? "O" : "N") + line;
        return new Comment(c.path("id").asLong(), "review", c.path("path").asText(null), line, side, key,
                c.path("body").asText(), c.path("user").path("login").asText(), c.path("user").path("avatar_url").asText(null),
                c.path("created_at").asText(), c.path("html_url").asText(),
                c.hasNonNull("in_reply_to_id") ? c.get("in_reply_to_id").asLong() : null);
    }

    private Comment issueComment(JsonNode c) {
        return new Comment(c.path("id").asLong(), "issue", null, null, null, null, c.path("body").asText(),
                c.path("user").path("login").asText(), c.path("user").path("avatar_url").asText(null),
                c.path("created_at").asText(), c.path("html_url").asText(), null);
    }

    // ---------------------------------------------------------------- http

    private JsonNode getJson(String path) {
        return http.json(request(path).header("Accept", "application/vnd.github+json").GET().build(), path);
    }

    private JsonNode postJson(String path, JsonNode body) {
        return http.json(request(path).header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json").POST(http.body(body)).build(), path);
    }

    private HttpRequest.Builder request(String path) {
        String t = token.get().orElseThrow(() -> new ProviderException(401,
                "Kein GitHub-Token gefunden. Setze GITHUB_TOKEN oder melde dich mit `gh auth login` an."));
        return HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + t)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SmartCodeReview");
    }
}
