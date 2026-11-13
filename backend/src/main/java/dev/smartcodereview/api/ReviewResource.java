package dev.smartcodereview.api;

import dev.smartcodereview.ReviewService;
import dev.smartcodereview.model.Model.Comment;
import dev.smartcodereview.model.Model.MarkRequest;
import dev.smartcodereview.model.Model.Me;
import dev.smartcodereview.model.Model.NewComment;
import dev.smartcodereview.model.Model.PrRef;
import dev.smartcodereview.model.Model.PullRequestSummary;
import dev.smartcodereview.model.Model.ReviewBundle;
import dev.smartcodereview.model.Model.ReviewState;
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.store.DataStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
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

    @Inject
    ReviewService service;

    @Inject
    DataStore store;

    @GET
    @Path("/me")
    public Me me() {
        return new Me(service.provider("github").currentUser(), store.root().toString(), String.join(",", service.providerIds()));
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

    @ServerExceptionMapper
    public Response providerError(ProviderException e) {
        return Response.status(e.status()).entity(Map.of("message", e.getMessage())).build();
    }

    @ServerExceptionMapper
    public Response badRequest(IllegalArgumentException e) {
        return Response.status(400).entity(Map.of("message", e.getMessage())).build();
    }
}
