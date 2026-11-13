package dev.smartcodereview.provider;

import dev.smartcodereview.model.Model.Comment;
import dev.smartcodereview.model.Model.NewComment;
import dev.smartcodereview.model.Model.PrRef;
import dev.smartcodereview.model.Model.PullRequestSummary;
import dev.smartcodereview.model.Model.Snapshot;
import dev.smartcodereview.model.Model.User;

import java.util.List;
import java.util.Optional;

/**
 * A code host (GitHub, GitLab; later Bitbucket, Azure DevOps). Everything above this interface is
 * provider-agnostic. "Pull request" is used for GitLab merge requests as well.
 */
public interface ReviewProvider {

    /** Short id used in URLs and in the data directory, e.g. "github". */
    String id();

    /** Display name, e.g. "GitLab". */
    String label();

    /** Base URL of the web UI, e.g. https://gitlab.com. */
    String host();

    /** True when credentials are configured, i.e. the user and their open PRs can be listed. */
    boolean hasCredentials();

    User currentUser();

    /** Open PRs authored by the user or waiting for the user's review. */
    List<PullRequestSummary> openPullRequests();

    /** Recognises a PR web URL of this host. */
    Optional<PrRef> parseUrl(String url);

    /** Fetches PR metadata and the full diff. */
    Snapshot fetchSnapshot(PrRef ref);

    /** Content of a file at a commit, empty if missing/binary/too large. */
    Optional<String> fileContent(PrRef ref, String path, String sha);

    List<Comment> comments(PrRef ref);

    /** Posts immediately (no pending review). The snapshot provides commit SHAs and old file paths. */
    Comment postComment(PrRef ref, Snapshot snapshot, NewComment comment);
}
