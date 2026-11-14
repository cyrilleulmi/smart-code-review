package dev.smartcodereview.commits;

import dev.smartcodereview.diff.PatchParser;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.Commit;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.PullRequestInfo;
import dev.smartcodereview.model.Model.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AttributionTest {

    private static Commit commit(String sha, String... parents) {
        return new Commit(sha, sha, sha, "dev", null, null, null, null, List.of(parents), 0, 0, List.of());
    }

    private static DiffFile file(String path, String status, String patch) {
        return new DiffFile(path, null, status, 0, 0, "java", false, PatchParser.parse(patch));
    }

    private static Snapshot snapshot(String head, DiffFile... files) {
        PullRequestInfo pr = new PullRequestInfo("github", "o", "r", 1, "t", "", "a", "u", false, "open",
                "main", "f", "base", head, null, null, 0, 0, files.length, null);
        return new Snapshot(Model.SCHEMA_VERSION, null, pr, List.of(files));
    }

    // base file: a b c d e (5 lines)

    @Test
    void addedLinesBelongToTheCommitThatWroteThemLast() {
        // c1: insert X after b; c2: change d -> D, append F
        DiffFile c1 = file("A.java", "modified", "@@ -2,1 +2,2 @@\n b\n+X\n");
        DiffFile c2 = file("A.java", "modified", "@@ -4,3 +4,4 @@\n c\n-d\n+D\n e\n+F\n");
        // PR diff base..head: a b X c D e F
        DiffFile pr = file("A.java", "modified", "@@ -1,5 +1,7 @@\n a\n b\n+X\n c\n-d\n+D\n e\n+F\n");

        Attribution.Result r = Attribution.compute(snapshot("c2", pr),
                List.of(commit("c1"), commit("c2", "c1")), Map.of("c1", List.of(c1), "c2", List.of(c2)));

        Map<String, String> lines = r.lines().get("A.java");
        assertEquals("c1", lines.get("N3"));
        assertEquals("c2", lines.get("N5"));
        assertEquals("c2", lines.get("N7"));
        assertEquals("c2", lines.get("O4"));
        assertEquals(List.of("c1", "c2"), r.ordered().stream().map(Commit::sha).toList());
    }

    @Test
    void lineRewrittenLaterBelongsToTheLaterCommit() {
        DiffFile c1 = file("A.java", "modified", "@@ -1,1 +1,2 @@\n a\n+x1\n");
        DiffFile c2 = file("A.java", "modified", "@@ -1,2 +1,2 @@\n a\n-x1\n+x2\n");
        DiffFile pr = file("A.java", "modified", "@@ -1,1 +1,2 @@\n a\n+x2\n");

        Attribution.Result r = Attribution.compute(snapshot("c2", pr),
                List.of(commit("c2", "c1"), commit("c1")), Map.of("c1", List.of(c1), "c2", List.of(c2)));

        assertEquals("c2", r.lines().get("A.java").get("N2"));
        assertEquals(List.of("c1", "c2"), r.ordered().stream().map(Commit::sha).toList(), "parents first");
    }

    @Test
    void sideBranchCommitsAreCarriedByTheMerge() {
        // c1 on the branch; s1 on a side branch merged by m (first parent c1, second parent s1)
        DiffFile c1 = file("A.java", "added", "@@ -0,0 +1,1 @@\n+one\n");
        DiffFile m = file("A.java", "modified", "@@ -1,1 +1,2 @@\n one\n+two\n");
        DiffFile pr = file("A.java", "added", "@@ -0,0 +1,2 @@\n+one\n+two\n");

        Attribution.Result r = Attribution.compute(snapshot("m", pr),
                List.of(commit("c1"), commit("s1"), commit("m", "c1", "s1")),
                Map.of("c1", List.of(c1), "s1", List.of(file("A.java", "modified", "@@ -1,1 +1,2 @@\n one\n+two\n")), "m", List.of(m)));

        assertEquals("c1", r.lines().get("A.java").get("N1"));
        assertEquals("m", r.lines().get("A.java").get("N2"));
        assertEquals("m", r.mergedVia().get("s1"));
    }

    @Test
    void renamesKeepTheBaseLines() {
        DiffFile c1 = new DiffFile("B.java", "A.java", "renamed", 0, 0, "java", false,
                PatchParser.parse("@@ -3,1 +3,1 @@\n-c\n+C\n"));
        DiffFile pr = new DiffFile("B.java", "A.java", "renamed", 1, 1, "java", false,
                PatchParser.parse("@@ -3,1 +3,1 @@\n-c\n+C\n"));

        Attribution.Result r = Attribution.compute(snapshot("c1", pr), List.of(commit("c1")), Map.of("c1", List.of(c1)));

        assertEquals("c1", r.lines().get("B.java").get("N3"));
        assertEquals("c1", r.lines().get("B.java").get("O3"));
    }

    @Test
    void differentlyAlignedBraceJoinsItsNeighbour() {
        // the commit inserts a method after the closing brace of the first one ...
        DiffFile c1 = file("A.java", "modified", "@@ -1,2 +1,5 @@\n a() {\n }\n+b() {\n+  x\n+}\n");
        // ... the PR diff shows the first brace as added and the last one as context
        DiffFile pr = file("A.java", "modified", "@@ -1,2 +1,5 @@\n a() {\n+}\n+b() {\n+  x\n }\n");

        Attribution.Result r = Attribution.compute(snapshot("c1", pr), List.of(commit("c1")), Map.of("c1", List.of(c1)));

        Map<String, String> lines = r.lines().get("A.java");
        assertEquals("c1", lines.get("N2"), "brace taken over from its neighbour");
        assertEquals("c1", lines.get("N3"));
        assertEquals("c1", lines.get("N4"));
    }

    @Test
    void mismatchingTextStaysUnattributed() {
        DiffFile c1 = file("A.java", "modified", "@@ -1,1 +1,2 @@\n a\n+x\n");
        DiffFile pr = file("A.java", "modified", "@@ -1,1 +1,2 @@\n a\n+something else\n");

        Attribution.Result r = Attribution.compute(snapshot("c1", pr), List.of(commit("c1")), Map.of("c1", List.of(c1)));

        assertNull(r.lines().get("A.java"));
    }

    @Test
    void missingPatchMakesTheFileUnknown() {
        DiffFile c1 = new DiffFile("A.java", null, "modified", 3, 1, "java", true, List.of());
        DiffFile c2 = file("A.java", "modified", "@@ -1,1 +1,2 @@\n a\n+x\n");
        DiffFile pr = file("A.java", "modified", "@@ -1,1 +1,2 @@\n a\n+x\n");

        Attribution.Result r = Attribution.compute(snapshot("c2", pr),
                List.of(commit("c1"), commit("c2", "c1")), Map.of("c1", List.of(c1), "c2", List.of(c2)));

        assertFalse(r.lines().containsKey("A.java"));
    }
}
