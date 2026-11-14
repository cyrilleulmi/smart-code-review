package dev.smartcodereview.repo;

import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.PrRef;
import dev.smartcodereview.model.Model.Repository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositorySnapshotTest {

    @Test
    void everyTextFileBecomesOneAddedHunk() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/a/A.java", "class A {\n  void run() {}\n}\n");
        files.put("README.md", "# Hello");
        files.put("img/logo.png", "\u0000PNG");
        byte[] tar = RepositorySnapshot.tarGz("owner-repo-abc123", files);

        RepositorySnapshot.Result r = RepositorySnapshot.build(new PrRef("github", "owner", "repo", 0),
                new Repository("main", "abc123", "https://github.com/owner/repo", "Demo", null), "owner", tar);

        List<DiffFile> out = r.snapshot().files();
        assertEquals(List.of("README.md", "img/logo.png", "src/main/java/a/A.java"), out.stream().map(DiffFile::path).toList());
        DiffFile java = out.get(2);
        assertEquals("added", java.status());
        assertEquals(3, java.additions());
        assertEquals("N3", java.hunks().getFirst().lines().get(2).key());
        assertEquals("}", java.hunks().getFirst().lines().get(2).text());
        assertTrue(out.get(1).patchMissing(), "binary file listed without content");
        assertEquals("repository", r.snapshot().pr().state());
        assertEquals("main", r.snapshot().pr().headRef());
        assertEquals(4, r.snapshot().pr().additions());
        assertTrue(r.structure().directories().containsAll(List.of("src", "src/main/java/a", "img")));
        assertFalse(r.sources().containsKey("img/logo.png"));
    }
}
