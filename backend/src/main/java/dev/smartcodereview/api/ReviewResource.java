package dev.smartcodereview.api;

import dev.smartcodereview.ReviewService;
import dev.smartcodereview.model.Model.ApprovalRequest;
import dev.smartcodereview.model.Model.Blame;
import dev.smartcodereview.model.Model.Comment;
import dev.smartcodereview.model.Model.Commits;
import dev.smartcodereview.model.Model.CookieRequest;
import dev.smartcodereview.model.Model.MarkRequest;
import dev.smartcodereview.model.Model.Me;
import dev.smartcodereview.model.Model.MergeOptions;
import dev.smartcodereview.model.Model.NewComment;
import dev.smartcodereview.model.Model.PrRef;
import dev.smartcodereview.model.Model.PrStatus;
import dev.smartcodereview.model.Model.PullRequestSummary;
import dev.smartcodereview.model.Model.ResolveRequest;
import dev.smartcodereview.model.Model.ReviewBundle;
import dev.smartcodereview.model.Model.ReviewState;
import dev.smartcodereview.model.Model.Settings;
import dev.smartcodereview.model.Model.WholeFile;
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.store.DataStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.List;
import java.util.Map;

/**
 * REST API for the UI and for agents. All PR endpoints live under /api/prs/{provider}/{owner}/{repo}/{number}.
 * Blocking calls (GitHub, file system) run on worker threads, which RESTEasy Reactive picks for non-reactive
 * return types automatically.
 */
@Path("/api")
public class ReviewResource {

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @Inject
    ReviewService service;

    @Inject
    DataStore store;

    @GET
    @Path("/me")
    public Me me() {
        return new Me(service.accounts(), store.root().toString(), version);
    }

    /**
     * Replaces the session cookies of an SSO proxy in front of a provider (they expire regularly): kept in memory
     * until the next start, never stored and never returned. A blank cookie removes them. Answers like /me.
     */
    @PUT
    @Path("/accounts/{provider}/cookie")
    public Me cookie(@PathParam("provider") String provider, CookieRequest request) {
        service.setCookie(provider, request == null ? null : request.cookie());
        return me();
    }

    /** Switched-off categories (global, <data>/settings.json): hidden in the UI, their places need no ok. */
    @GET
    @Path("/settings")
    public Settings settings() {
        return service.settings();
    }

    @PUT
    @Path("/settings")
    public Settings saveSettings(Settings settings) {
        return service.saveSettings(settings);
    }

    /** Resolves a PR/MR web URL (GitHub or GitLab) to {provider, owner, repo, number}. */
    @GET
    @Path("/resolve")
    public PrRef resolve(@QueryParam("url") String url) {
        return service.resolve(url == null ? "" : url);
    }

    /** filter: all (default) | authored | review-requested */
    @GET
    @Path("/prs")
    public List<PullRequestSummary> prs(@QueryParam("filter") String filter) {
        List<PullRequestSummary> all = service.openPullRequests();
        if (filter == null || filter.isBlank() || filter.equals("all")) {
            return all;
        }
        return all.stream().filter(p -> p.roles().contains(filter)).toList();
    }

    @GET
    @Path("/repos")
    public List<dev.smartcodereview.model.Model.RepositorySummary> repos(@QueryParam("q") String q) {
        return service.repositories(q == null ? "" : q);
    }

    @GET
    @Path("/repos/{provider}/{owner}/{repo}/prs")
    public List<PullRequestSummary> repoPrs(@PathParam("provider") String provider, @PathParam("owner") String owner,
                                            @PathParam("repo") String repo) {
        return service.repositoryPullRequests(provider, owner, repo);
    }

    @GET
    @Path("/search")
    public List<PullRequestSummary> search(@QueryParam("q") String q) {
        return q == null || q.isBlank() ? List.of() : service.searchPullRequests(q);
    }

    /** PRs with local data (snapshot) - works offline. */
    @GET
    @Path("/local")
    public List<String> local() {
        return service.localReviews();
    }

    @GET
    @Path("/prs/{provider}/{owner}/{repo}/{number}")
    public ReviewBundle bundle(@PathParam("provider") String provider, @PathParam("owner") String owner,
                               @PathParam("repo") String repo, @PathParam("number") int number) {
        return service.bundle(new PrRef(provider, owner, repo, number));
    }

    @POST
    @Path("/prs/{provider}/{owner}/{repo}/{number}/reload")
    public ReviewBundle reload(@PathParam("provider") String provider, @PathParam("owner") String owner,
                               @PathParam("repo") String repo, @PathParam("number") int number) {
        return service.reload(new PrRef(provider, owner, repo, number));
    }

    @POST
    @Path("/prs/{provider}/{owner}/{repo}/{number}/reanalyse")
    public ReviewBundle reanalyse(@PathParam("provider") String provider, @PathParam("owner") String owner,
                                  @PathParam("repo") String repo, @PathParam("number") int number) {
        return service.reanalyse(new PrRef(provider, owner, repo, number));
    }

    @GET
    @Path("/prs/{provider}/{owner}/{repo}/{number}/state")
    public ReviewState state(@PathParam("provider") String provider, @PathParam("owner") String owner,
                             @PathParam("repo") String repo, @PathParam("number") int number) {
        return service.state(new PrRef(provider, owner, repo, number));
    }

    @POST
    @Path("/prs/{provider}/{owner}/{repo}/{number}/marks")
    public ReviewState mark(@PathParam("provider") String provider, @PathParam("owner") String owner,
                            @PathParam("repo") String repo, @PathParam("number") int number, MarkRequest request) {
        return service.mark(new PrRef(provider, owner, repo, number), request);
    }

    /** Commits of the PR (oldest first) and which commit made each changed line. */
    @GET
    @Path("/prs/{provider}/{owner}/{repo}/{number}/commits")
    public Commits commits(@PathParam("provider") String provider, @PathParam("owner") String owner,
                           @PathParam("repo") String repo, @PathParam("number") int number) {
        return service.commits(new PrRef(provider, owner, repo, number));
    }

    /** A changed file as a whole at the PR head, deleted lines woven in. */
    @GET
    @Path("/prs/{provider}/{owner}/{repo}/{number}/file")
    public WholeFile wholeFile(@PathParam("provider") String provider, @PathParam("owner") String owner,
                               @PathParam("repo") String repo, @PathParam("number") int number, @QueryParam("path") String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path fehlt");
        }
        return service.wholeFile(new PrRef(provider, owner, repo, number), path);
    }

    /** git blame of a changed file at the PR head. */
    @GET
    @Path("/prs/{provider}/{owner}/{repo}/{number}/blame")
    public Blame blame(@PathParam("provider") String provider, @PathParam("owner") String owner,
                       @PathParam("repo") String repo, @PathParam("number") int number, @QueryParam("path") String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path fehlt");
        }
        return service.blame(new PrRef(provider, owner, repo, number), path);
    }

    @GET
    @Path("/prs/{provider}/{owner}/{repo}/{number}/comments")
    public List<Comment> comments(@PathParam("provider") String provider, @PathParam("owner") String owner,
                                  @PathParam("repo") String repo, @PathParam("number") int number) {
        return service.comments(new PrRef(provider, owner, repo, number));
    }

    @POST
    @Path("/prs/{provider}/{owner}/{repo}/{number}/comments")
    public Comment comment(@PathParam("provider") String provider, @PathParam("owner") String owner,
                           @PathParam("repo") String repo, @PathParam("number") int number, NewComment comment) {
        return service.postComment(new PrRef(provider, owner, repo, number), comment);
    }

    /**
     * Resolves a thread of comments on the code host or opens it again; thread is {@code Comment.thread}. Answers
     * with all comments of the PR as they are now.
     */
    @PUT
    @Path("/prs/{provider}/{owner}/{repo}/{number}/threads/{thread}/resolved")
    public List<Comment> resolveThread(@PathParam("provider") String provider, @PathParam("owner") String owner,
                                       @PathParam("repo") String repo, @PathParam("number") int number,
                                       @PathParam("thread") String thread, ResolveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("resolved fehlt");
        }
        return service.resolveThread(new PrRef(provider, owner, repo, number), thread, request.resolved());
    }

    /**
     * Where the PR stands on the code host (approvals, mergeable or why not), read live. 204 without content when
     * approving and merging is not supported for it (GitHub, a whole repository).
     */
    @GET
    @Path("/prs/{provider}/{owner}/{repo}/{number}/status")
    public PrStatus status(@PathParam("provider") String provider, @PathParam("owner") String owner,
                           @PathParam("repo") String repo, @PathParam("number") int number) {
        return service.status(new PrRef(provider, owner, repo, number)).orElse(null);
    }

    /** Approves the PR on the code host as the user ({"approved":true}) or revokes the approval; at once. */
    @PUT
    @Path("/prs/{provider}/{owner}/{repo}/{number}/approval")
    public PrStatus approve(@PathParam("provider") String provider, @PathParam("owner") String owner,
                            @PathParam("repo") String repo, @PathParam("number") int number, ApprovalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("approved fehlt");
        }
        return service.approve(new PrRef(provider, owner, repo, number), request.approved());
    }

    /** Merges the PR on the code host; at once and not reversible. Body: {} or {"squash":…,"removeSourceBranch":…}. */
    @POST
    @Path("/prs/{provider}/{owner}/{repo}/{number}/merge")
    public PrStatus merge(@PathParam("provider") String provider, @PathParam("owner") String owner,
                          @PathParam("repo") String repo, @PathParam("number") int number, MergeOptions options) {
        return service.merge(new PrRef(provider, owner, repo, number), options);
    }

    @ServerExceptionMapper
    public Response providerError(ProviderException e) {
        return Response.status(e.status()).entity(Map.of("message", e.getMessage())).build();
    }

    @ServerExceptionMapper
    public Response badRequest(IllegalArgumentException e) {
        return Response.status(400).entity(Map.of("message", e.getMessage())).build();
    }
}
