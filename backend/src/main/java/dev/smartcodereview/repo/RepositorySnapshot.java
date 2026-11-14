package dev.smartcodereview.repo;

import dev.smartcodereview.analysis.Languages;
import dev.smartcodereview.analysis.OpenApi;
import dev.smartcodereview.model.Model;
import dev.smartcodereview.model.Model.ApiOperation;
import dev.smartcodereview.model.Model.DiffFile;
import dev.smartcodereview.model.Model.DiffLine;
import dev.smartcodereview.model.Model.Hunk;
import dev.smartcodereview.model.Model.PrRef;
import dev.smartcodereview.model.Model.PullRequestInfo;
import dev.smartcodereview.model.Model.RepoStructure;
import dev.smartcodereview.model.Model.Repository;
import dev.smartcodereview.model.Model.Snapshot;
import dev.smartcodereview.provider.ProviderException;
import dev.smartcodereview.provider.Tokens;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * The whole repository as a "pull request" in which every file is new: one added hunk per text file, so that
 * the normal analysis (segments, categories, flows, components, tests) runs over all the code. Built from the
 * .tar.gz archive of one commit; nothing here knows the code host.
 */
public final class RepositorySnapshot {

    /** At most this many files are taken (in path order). */
    public static final int MAX_FILES = 5000;
    /** Larger text files are listed without content (like a diff GitHub does not show). */
    public static final int MAX_LINES = 5000;
    private static final int MAX_BYTES = 1_000_000;
    private static final int MAX_CONTRACTS = 10;

    private RepositorySnapshot() {
    }

    /** sources: text of every file with content (path -> text). */
    public record Result(Snapshot snapshot, Map<String, String> sources, RepoStructure structure) {
    }

    public static Result build(PrRef ref, Repository repo, String author, byte[] tarGz) {
        Map<String, byte[]> entries = new TreeMap<>();
        TreeSet<String> dirs = new TreeSet<>();
        untar(tarGz, entries, dirs);

        List<DiffFile> files = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> contracts = new ArrayList<>();
        List<ApiOperation> operations = new ArrayList<>();
        int lines = 0;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (files.size() >= MAX_FILES) {
                break;
            }
            String path = e.getKey();
            Optional<String> text = Tokens.text(e.getValue(), MAX_BYTES);
            if (text.isPresent() && OpenApi.isContract(path) && contracts.size() < MAX_CONTRACTS) {
                contracts.add(path);
                operations.addAll(OpenApi.operations(path, text.get()));
            }
            List<String> content = text.map(RepositorySnapshot::lines).orElse(null);
            if (content == null || content.size() > MAX_LINES) {
                files.add(new DiffFile(path, null, "added", 0, 0, Languages.of(path), true, List.of()));
                continue;
            }
            List<DiffLine> diffLines = new ArrayList<>(content.size());
            for (int i = 0; i < content.size(); i++) {
                diffLines.add(new DiffLine("add", null, i + 1, "N" + (i + 1), content.get(i)));
            }
            List<Hunk> hunks = content.isEmpty() ? List.of()
                    : List.of(new Hunk(0, "@@ -0,0 +1," + content.size() + " @@", 0, 0, 1, content.size(), diffLines));
            files.add(new DiffFile(path, null, "added", content.size(), 0, Languages.of(path), false, hunks));
            sources.put(path, text.get());
            lines += content.size();
        }

        PullRequestInfo info = new PullRequestInfo(ref.provider(), ref.owner(), ref.repo(), 0,
                "Ganzes Repository", repo.description() == null ? "" : repo.description(), author, repo.url(),
                false, "repository", "", repo.defaultBranch(), null, repo.headSha(),
                repo.headDate(), repo.headDate(), lines, 0, files.size(), null);
        Snapshot snapshot = new Snapshot(Model.SCHEMA_VERSION, Instant.now().toString(), info, files);
        return new Result(snapshot, sources, new RepoStructure(new ArrayList<>(dirs), entries.size() > MAX_FILES, contracts, operations));
    }

    /** Text lines without the final line break. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        return out;
    }

    // ------------------------------------------------------------------ tar

    /**
     * Regular files and directories of a .tar.gz, paths without the archive's top folder (GitHub and GitLab
     * put everything under "<repo>-<sha>/"). Understands ustar prefixes, GNU long names and pax path records.
     */
    static void untar(byte[] tarGz, Map<String, byte[]> files, TreeSet<String> dirs) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(tarGz), 65536)) {
            byte[] header = new byte[512];
            String longName = null;
            while (readFully(in, header)) {
                if (isZero(header)) {
                    break;
                }
                String name = string(header, 0, 100);
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                String magic = string(header, 257, 6);
                if (magic.startsWith("ustar")) {
                    String prefix = string(header, 345, 155);
                    if (!prefix.isEmpty()) {
                        name = prefix + "/" + name;
                    }
                }
                byte[] data = read(in, size);
                skip(in, (512 - size % 512) % 512);
                switch (type) {
                    case 'L' -> longName = string(data, 0, data.length);
                    case 'x' -> longName = paxPath(data);
                    case 'g' -> {
                    } // global pax header (GitHub: commit id)
                    default -> {
                        String path = strip(longName != null ? longName : name);
                        longName = null;
                        if (path.isEmpty()) {
                            continue;
                        }
                        if (type == '5') {
                            dirs.add(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
                        } else if (type == '0' || type == '\0' || type == '7') {
                            files.put(path, data);
                            for (int i = path.indexOf('/'); i > 0; i = path.indexOf('/', i + 1)) {
                                dirs.add(path.substring(0, i));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new ProviderException(502, "Archiv des Repositorys nicht lesbar: " + e.getMessage());
        }
    }

    private static String strip(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(slash + 1);
    }

    private static String paxPath(byte[] data) {
        String records = new String(data, StandardCharsets.UTF_8);
        for (String record : records.split("\n")) {
            int space = record.indexOf(' ');
            if (space > 0 && record.startsWith("path=", space + 1)) {
                return record.substring(space + 6);
            }
        }
        return null;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int n = in.readNBytes(buf, 0, buf.length);
        return n == buf.length;
    }

    private static byte[] read(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE - 8) {
            throw new IOException("Eintrag zu gross");
        }
        if (size <= MAX_BYTES) {
            return in.readNBytes((int) size);
        }
        // too large to show: keep a binary marker, skip the rest
        skip(in, size);
        return new byte[] {0};
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static boolean isZero(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String string(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        String s = string(b, off, len).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    /** For tests: a .tar.gz with the given files under a top folder. */
    static byte[] tarGz(String top, Map<String, String> files) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (Map.Entry<String, String> f : files.entrySet()) {
            byte[] data = f.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] h = new byte[512];
            byte[] name = (top + "/" + f.getKey()).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, h, 0, name.length);
            byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(size, 0, h, 124, size.length);
            h[156] = '0';
            System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 5);
            raw.write(h);
            raw.write(data);
            raw.write(new byte[(512 - data.length % 512) % 512]);
        }
        raw.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (var out = new java.util.zip.GZIPOutputStream(gz)) {
            out.write(raw.toByteArray());
        }
        return gz.toByteArray();
    }
}
