package dev.smartcodereview.commits;

import dev.smartcodereview.model.Model.Commit;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.DiffLine;
import dev.smartcodereview.model.Model.Hunk;
import dev.smartcodereview.model.Model.Snapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds out which commit made each changed line of a PR diff, without git: the diffs of the commits on the
 * first-parent chain of the head are replayed, oldest first, on a model of each file that only knows where
 * every line came from (a base line number or a commit). Added lines of the PR belong to the commit that wrote
 * them last, deleted lines to the commit that removed them. Every result is checked against the text of the PR
 * diff; lines that do not match (e.g. after the base branch was merged in) stay unattributed.
 */
public final class Attribution {

    /** Bump when the result for the same input changes (commits.json is then recomputed). */
    public static final int VERSION = 1;

    private Attribution() {
    }

    /** lines: path -> line key -> sha. mergedVia: commit off the chain -> merge commit carrying its changes. */
    public record Result(List<Commit> ordered, Map<String, Map<String, String>> lines, Map<String, String> mergedVia) {
    }

    /**
     * commits: the PR's commits in any order. diffs: sha -> files changed vs. the first parent (missing = unknown).
     * The returned commits are oldest first (parents before children) and carry their diff statistics.
     */
    public static Result compute(Snapshot snapshot, List<Commit> commits, Map<String, List<DiffFile>> diffs) {
        List<Commit> ordered = topological(commits);
        Map<String, Commit> bySha = new HashMap<>();
        ordered.forEach(c -> bySha.put(c.sha(), c));

        List<Commit> chain = firstParentChain(snapshot.pr().headSha(), ordered, bySha);
        Set<String> onChain = new HashSet<>();
        chain.forEach(c -> onChain.add(c.sha()));
        Map<String, String> mergedVia = mergedVia(chain, bySha, onChain);

        Replay replay = new Replay();
        for (Commit c : chain) {
            List<DiffFile> files = diffs.get(c.sha());
            if (files == null) {
                replay.unknownFrom(c); // without its diff nothing after it can be trusted
                break;
            }
            for (DiffFile f : files) {
                replay.apply(c.sha(), f);
            }
        }

        Map<String, Map<String, String>> lines = new TreeMap<>();
        for (DiffFile pf : snapshot.files()) {
            Map<String, String> byKey = replay.attribute(pf);
            if (!byKey.isEmpty()) {
                lines.put(pf.path(), byKey);
            }
        }

        List<Commit> withStats = ordered.stream().map(c -> withStats(c, diffs.get(c.sha()))).toList();
        return new Result(withStats, lines, mergedVia);
    }

    private static Commit withStats(Commit c, List<DiffFile> files) {
        if (files == null) {
            return c;
        }
        int add = files.stream().mapToInt(DiffFile::additions).sum();
        int del = files.stream().mapToInt(DiffFile::deletions).sum();
        return new Commit(c.sha(), c.title(), c.message(), c.author(), c.authorLogin(), c.avatarUrl(), c.date(), c.url(),
                c.parents(), add, del, files.stream().map(DiffFile::path).toList());
    }

    // ------------------------------------------------------------------ order

    /** Parents before children; otherwise the given order (the providers list commits oldest first). */
    static List<Commit> topological(List<Commit> commits) {
        Map<String, Commit> bySha = new LinkedHashMap<>();
        commits.forEach(c -> bySha.putIfAbsent(c.sha(), c));
        List<Commit> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (Commit c : bySha.values()) {
            visit(c, bySha, done, out, new HashSet<>());
        }
        return out;
    }

    private static void visit(Commit c, Map<String, Commit> bySha, Set<String> done, List<Commit> out, Set<String> path) {
        if (done.contains(c.sha()) || !path.add(c.sha())) {
            return;
        }
        for (String p : parents(c)) {
            Commit parent = bySha.get(p);
            if (parent != null) {
                visit(parent, bySha, done, out, path);
            }
        }
        done.add(c.sha());
        out.add(c);
    }

    /** From the head back along first parents, as long as the commits belong to the PR; oldest first. */
    private static List<Commit> firstParentChain(String headSha, List<Commit> ordered, Map<String, Commit> bySha) {
        Commit head = bySha.get(headSha);
        if (head == null && !ordered.isEmpty()) {
            head = ordered.getLast(); // list truncated by the host: start at the newest commit we know
        }
        Deque<Commit> chain = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (Commit c = head; c != null && seen.add(c.sha()); c = parents(c).isEmpty() ? null : bySha.get(parents(c).getFirst())) {
            chain.addFirst(c);
        }
        return new ArrayList<>(chain);
    }

    /** Commits reachable from a merge commit's other parents (side branches) are carried by that merge. */
    private static Map<String, String> mergedVia(List<Commit> chain, Map<String, Commit> bySha, Set<String> onChain) {
        Map<String, String> result = new TreeMap<>();
        for (Commit merge : chain) {
            List<String> parents = parents(merge);
            Deque<String> todo = new ArrayDeque<>(parents.subList(Math.min(1, parents.size()), parents.size()));
            while (!todo.isEmpty()) {
                String sha = todo.pop();
                Commit c = bySha.get(sha);
                if (c == null || onChain.contains(sha) || result.containsKey(sha)) {
                    continue;
                }
                result.put(sha, merge.sha());
                todo.addAll(parents(c));
            }
        }
        return result;
    }

    private static List<String> parents(Commit c) {
        return c.parents() == null ? List.of() : c.parents();
    }

    // ------------------------------------------------------------------ replay

    /** Where a line of the current file version comes from: base line `base` or commit `sha`. text: if known. */
    private record Origin(Integer base, String sha, String text) {
        Origin withText(String t) {
            return text != null ? this : new Origin(base, sha, t);
        }
    }

    /** A removed base line: the commit that removed it and the text it had. */
    private record Removal(String sha, String text) {
    }

    /**
     * One file while replaying. Lines beyond `lines` were not touched yet: they are base lines nextBase,
     * nextBase+1, ... (or unknown if the file did not exist in the base).
     */
    private static final class FileModel {
        final String basePath;
        final boolean hasBase;
        List<Origin> lines = new ArrayList<>();
        int nextBase = 1;

        FileModel(String basePath, boolean hasBase) {
            this.basePath = basePath;
            this.hasBase = hasBase;
        }

        Origin at(List<Origin> list, int index) {
            while (list.size() <= index) {
                list.add(hasBase ? new Origin(nextBase++, null, null) : new Origin(null, null, null));
            }
            return list.get(index);
        }
    }

    private static final class Replay {
        /** current path -> model */
        final Map<String, FileModel> files = new HashMap<>();
        /** base path -> base line -> removal */
        final Map<String, Map<Integer, Removal>> removed = new HashMap<>();
        /** current paths whose line positions are no longer known (diff without patch) */
        final Set<String> broken = new HashSet<>();
        boolean stopped;

        FileModel model(String path) {
            return files.computeIfAbsent(path, p -> new FileModel(p, true));
        }

        void apply(String sha, DiffFile f) {
            String path = f.path();
            String from = f.previousPath() != null ? f.previousPath() : path;
            if (!from.equals(path) && ("renamed".equals(f.status()) || "copied".equals(f.status()))) {
                FileModel moved = "copied".equals(f.status()) ? null : files.remove(from);
                files.put(path, moved != null ? moved : new FileModel(from, true));
                if (broken.remove(from) && moved != null) {
                    broken.add(path);
                }
            }
            if ("added".equals(f.status())) {
                files.put(path, new FileModel(path, false));
                broken.remove(path);
            }
            boolean pureRename = f.hunks().isEmpty() && f.additions() == 0 && f.deletions() == 0;
            if (f.patchMissing() && !pureRename) {
                broken.add(path);
                return;
            }
            if (broken.contains(path)) {
                return;
            }
            FileModel m = model(path);
            List<Origin> old = m.lines;
            List<Origin> next = new ArrayList<>();
            int cursor = 0; // index in old
            for (Hunk h : f.hunks()) {
                int before = h.oldLines() == 0 ? h.oldStart() : h.oldStart() - 1; // lines in front of the hunk
                while (cursor < before) {
                    next.add(m.at(old, cursor++));
                }
                for (DiffLine l : h.lines()) {
                    switch (l.type()) {
                        case "ctx" -> next.add(m.at(old, cursor++).withText(l.text()));
                        case "del" -> {
                            Origin o = m.at(old, cursor++);
                            if (o.base() != null) {
                                removed.computeIfAbsent(m.basePath, k -> new HashMap<>())
                                        .put(o.base(), new Removal(sha, l.text()));
                            }
                        }
                        case "add" -> next.add(new Origin(null, sha, l.text()));
                        default -> {
                        }
                    }
                }
            }
            while (cursor < old.size()) {
                next.add(old.get(cursor++));
            }
            m.lines = next;
            if ("removed".equals(f.status())) {
                files.remove(path);
            }
        }

        void unknownFrom(Commit c) {
            stopped = true;
        }

        /** Line key -> sha for the changed lines of a file of the PR diff that are explained by the replay. */
        Map<String, String> attribute(DiffFile pf) {
            Map<String, String> out = new LinkedHashMap<>();
            if (stopped) {
                return out;
            }
            FileModel m = files.get(pf.path());
            String basePath = pf.previousPath() != null ? pf.previousPath() : pf.path();
            Map<Integer, Removal> gone = removed.getOrDefault(basePath, Map.of());
            boolean positions = m != null && !broken.contains(pf.path());
            for (Hunk h : pf.hunks()) {
                for (DiffLine l : h.lines()) {
                    if ("add".equals(l.type()) && positions && l.newLine() <= m.lines.size()) {
                        Origin o = m.lines.get(l.newLine() - 1);
                        if (o.sha() != null && Objects.equals(o.text(), l.text())) {
                            out.put(l.key(), o.sha());
                        }
                    } else if ("del".equals(l.type())) {
                        Removal r = gone.get(l.oldLine());
                        if (r != null && Objects.equals(r.text(), l.text())) {
                            out.put(l.key(), r.sha());
                        }
                    }
                }
                adoptNeighbours(h.lines(), out);
            }
            return out;
        }

        /**
         * Diffs may align identical lines (a lone "}") differently than the commits did, so such a line looks
         * unchanged in a commit but changed in the PR. It belongs to the change right next to it: an unattributed
         * changed line takes the commit of the neighbouring changed line of the same kind (forwards, then back).
         */
        private static void adoptNeighbours(List<DiffLine> lines, Map<String, String> out) {
            for (int pass = 0; pass < 2; pass++) {
                boolean forward = pass == 0;
                for (int k = 0; k < lines.size(); k++) {
                    int i = forward ? k : lines.size() - 1 - k;
                    DiffLine l = lines.get(i);
                    int j = forward ? i - 1 : i + 1;
                    if (!l.changed() || out.containsKey(l.key()) || j < 0 || j >= lines.size()) {
                        continue;
                    }
                    DiffLine n = lines.get(j);
                    if (n.type().equals(l.type()) && out.containsKey(n.key())) {
                        out.put(l.key(), out.get(n.key()));
                    }
                }
            }
        }
    }
}
