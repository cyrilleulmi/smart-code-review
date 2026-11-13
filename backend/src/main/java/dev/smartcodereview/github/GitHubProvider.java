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
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.provider.ReviewProvider;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
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
import java.util.concurrent.TimeUnit;

/** GitHub via its REST API, using plain java.net.http and Jackson trees (native-image friendly). */
@ApplicationScoped
public class GitHubProvider implements ReviewProvider {

    private static final int MAX_FILE_BYTES = 1_000_000;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "scr.github.api-url", defaultValue = "https://api.github.com")
    String apiUrl;

    @ConfigProperty(name = "scr.github.token")
    Optional<String> configuredToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile String token;

    @Override
    public String id() {
        return "github";
    }

    @Override
    public User currentUser() {
        JsonNode u = getJson("/user");
        return new User(u.path("login").asText(), u.path("name").asText(null), u.path("avatar_url").asText(null));
    }

    @Override
    public List<PullRequestSummary> openPullRequests() {
        Map<String, PullRequestSummary> byKey = new LinkedHashMap<>();
        searchInto(byKey, "is:pr is:open archived:false review-requested:@me", "review-requested");
        searchInto(byKey, "is:pr is:open archived:false author:@me", "authored");
        List<PullRequestSummary> result = new ArrayList<>(byKey.values());
        result.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return result;
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
            if (existing != null) {
                List<String> roles = new ArrayList<>(existing.roles());
                roles.add(role);
                byKey.put(key, new PullRequestSummary(existing.provider(), owner, repo, number, existing.title(),
                        existing.author(), existing.url(), existing.draft(), existing.createdAt(), existing.updatedAt(),
                        roles, null));
            } else {
                byKey.put(key, new PullRequestSummary(id(), owner, repo, number, item.path("title").asText(),
                        item.path("user").path("login").asText(), item.path("html_url").asText(),
                        item.path("draft").asBoolean(false), item.path("created_at").asText(),
                        item.path("updated_at").asText(), List.of(role), null));
            }
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
                pr.path("additions").asInt(), pr.path("deletions").asInt(), pr.path("changed_files").asInt());

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
    public Optional<String> fileContent(PrRef ref, String path, String sha) {
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20");
        HttpResponse<byte[]> res = send(request("/repos/" + ref.owner() + "/" + ref.repo() + "/contents/" + encodedPath + "?ref=" + sha)
                .header("Accept", "application/vnd.github.raw+json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200 || res.body().length > MAX_FILE_BYTES) {
            return Optional.empty();
        }
        byte[] body = res.body();
        for (int i = 0; i < Math.min(body.length, 8000); i++) {
            if (body[i] == 0) {
                return Optional.empty(); // binary
            }
        }
        return Optional.of(new String(body, StandardCharsets.UTF_8));
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
    public Comment postComment(PrRef ref, String headSha, NewComment c) {
        String repoBase = "/repos/" + ref.owner() + "/" + ref.repo();
        ObjectNode body = mapper.createObjectNode().put("body", c.body());
        if (c.inReplyTo() != null) {
            return reviewComment(postJson(repoBase + "/pulls/" + ref.number() + "/comments/" + c.inReplyTo() + "/replies", body));
        }
        if (c.path() == null || c.line() == null) {
            return issueComment(postJson(repoBase + "/issues/" + ref.number() + "/comments", body));
        }
        body.put("commit_id", headSha).put("path", c.path()).put("line", c.line())
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
        HttpResponse<String> res = send(request(path).header("Accept", "application/vnd.github+json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return readOk(path, res);
    }

    private JsonNode postJson(String path, JsonNode body) {
        HttpResponse<String> res = send(request(path).header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        return readOk(path, res);
    }

    private JsonNode readOk(String path, HttpResponse<String> res) {
        if (res.statusCode() >= 300) {
            String message = res.body();
            try {
                message = mapper.readTree(res.body()).path("message").asText(message);
            } catch (IOException ignored) {
                // keep raw body
            }
            throw new ProviderException(res.statusCode(), "GitHub " + res.statusCode() + " auf " + path + ": " + message);
        }
        try {
            return mapper.readTree(res.body());
        } catch (IOException e) {
            throw new ProviderException(502, "Ungültige Antwort von GitHub: " + e.getMessage());
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SmartCodeReview");
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new ProviderException(502, "GitHub nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(503, "Abgebrochen");
        }
    }

    /** Token order: scr.github.token / GITHUB_TOKEN / GH_TOKEN, then `gh auth token` (GitHub CLI login). */
    private String token() {
        if (token != null) {
            return token;
        }
        String t = configuredToken.filter(s -> !s.isBlank())
                .or(() -> Optional.ofNullable(System.getenv("GITHUB_TOKEN")).filter(s -> !s.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("GH_TOKEN")).filter(s -> !s.isBlank()))
                .orElseGet(GitHubProvider::tokenFromGhCli);
        if (t == null) {
            throw new ProviderException(401, "Kein GitHub-Token gefunden. Setze GITHUB_TOKEN oder melde dich mit `gh auth login` an.");
        }
        token = t;
        return t;
    }

    private static String tokenFromGhCli() {
        try {
            Process p = new ProcessBuilder("gh", "auth", "token").redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty()) {
                Log.info("Using GitHub token from `gh auth token`");
                return out;
            }
        } catch (IOException e) {
            Log.debug("GitHub CLI not available", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
