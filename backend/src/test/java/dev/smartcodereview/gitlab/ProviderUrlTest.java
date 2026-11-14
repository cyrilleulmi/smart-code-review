package dev.smartcodereview.gitlab;

import dev.smartcodereview.github.GitHubProvider;
import dev.smartcodereview.model.Model.PrRef;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderUrlTest {

    @Test
    void parsesGitHubPullRequestUrls() {
        GitHubProvider github = new GitHubProvider();
        assertEquals(Optional.of(new PrRef("github", "cyrilleulmi", "spongecoach", 13)),
                github.parseUrl("https://github.com/cyrilleulmi/spongecoach/pull/13/files"));
        assertTrue(github.parseUrl("https://gitlab.com/a/b/-/merge_requests/1").isEmpty());
    }

    @Test
    void parsesGitLabMergeRequestUrlsWithNestedGroups() {
        GitLabProvider gitlab = new GitLabProvider();
        gitlab.baseUrl = "https://gitlab.com";
        assertEquals(Optional.of(new PrRef("gitlab", "gitlab-org", "cli", 4347)),
                gitlab.parseUrl("https://gitlab.com/gitlab-org/cli/-/merge_requests/4347"));
        assertEquals(Optional.of(new PrRef("gitlab", "acme~platform~web", "shop", 7)),
                gitlab.parseUrl("https://gitlab.com/acme/platform/web/shop/-/merge_requests/7/diffs"));
        assertTrue(gitlab.parseUrl("https://github.com/cyrilleulmi/spongecoach/pull/13").isEmpty());
    }

    @Test
    void repositoryUrlsMeanTheWholeRepository() {
        GitHubProvider github = new GitHubProvider();
        PrRef repo = new PrRef("github", "cyrilleulmi", "smart-code-review", 0);
        assertEquals(Optional.of(repo), github.parseUrl("https://github.com/cyrilleulmi/smart-code-review"));
        assertEquals(Optional.of(repo), github.parseUrl("https://github.com/cyrilleulmi/smart-code-review.git"));
        assertEquals(Optional.of(repo), github.parseUrl("https://github.com/cyrilleulmi/smart-code-review/tree/main/docs"));
        assertTrue(github.parseUrl("https://github.com/cyrilleulmi/smart-code-review/issues/3").isEmpty());

        GitLabProvider gitlab = new GitLabProvider();
        gitlab.baseUrl = "https://gitlab.com";
        assertEquals(Optional.of(new PrRef("gitlab", "acme~platform", "shop", 0)),
                gitlab.parseUrl("https://gitlab.com/acme/platform/shop"));
        assertEquals(Optional.of(new PrRef("gitlab", "gitlab-org", "cli", 0)),
                gitlab.parseUrl("https://gitlab.com/gitlab-org/cli/-/tree/main"));
        assertTrue(gitlab.parseUrl("https://gitlab.com/gitlab-org/cli/-/issues/12").isEmpty());
    }
}
