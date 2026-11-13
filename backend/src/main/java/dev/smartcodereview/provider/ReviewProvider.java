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
 * A code host (GitHub today; GitLab, Bitbucket, Azure DevOps later). Everything above this interface
 * is provider-agnostic.
 */
public interface ReviewProvider {

    /** Short id used in URLs and in the data directory, e.g. "github". */
    String id();

    User currentUser();

    /** Open PRs authored by the user or waiting for the user's review. */
    List<PullRequestSummary> openPullRequests();

    /** Fetches PR metadata and the full diff. */
    Snapshot fetchSnapshot(PrRef ref);

    /** Content of a file at a commit, empty if missing/binary/too large. */
    Optional<String> fileContent(PrRef ref, String path, String sha);

    List<Comment> comments(PrRef ref);

    /** Posts immediately (no pending review). */
    Comment postComment(PrRef ref, String headSha, NewComment comment);
}
