package dev.smartcodereview.diff;

import dev.smartcodereview.model.Model.DiffLine;
import dev.smartcodereview.model.Model.Hunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the unified-diff "patch" GitHub returns per file into hunks and numbered lines. */
public final class PatchParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private PatchParser() {
    }

    public static List<Hunk> parse(String patch) {
        List<Hunk> hunks = new ArrayList<>();
        if (patch == null || patch.isEmpty()) {
            return hunks;
        }
        String header = null;
        int oldStart = 0, oldCount = 0, newStart = 0, newCount = 0;
        int oldLine = 0, newLine = 0;
        List<DiffLine> lines = null;

        for (String raw : patch.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                if (lines != null) {
                    hunks.add(new Hunk(hunks.size(), header, oldStart, oldCount, newStart, newCount, lines));
                }
                header = line;
                oldStart = Integer.parseInt(m.group(1));
                oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                newStart = Integer.parseInt(m.group(3));
                newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
                oldLine = oldStart;
                newLine = newStart;
                lines = new ArrayList<>();
                continue;
            }
            if (lines == null || line.startsWith("\\")) {
                continue; // "\ No newline at end of file"
            }
            if (line.startsWith("+")) {
                lines.add(new DiffLine("add", null, newLine, "N" + newLine, line.substring(1)));
                newLine++;
            } else if (line.startsWith("-")) {
                lines.add(new DiffLine("del", oldLine, null, "O" + oldLine, line.substring(1)));
                oldLine++;
            } else if (line.startsWith(" ") || (line.isEmpty() && (oldLine < oldStart + oldCount))) {
                lines.add(new DiffLine("ctx", oldLine, newLine, "N" + newLine, line.isEmpty() ? "" : line.substring(1)));
                oldLine++;
                newLine++;
            }
        }
        if (lines != null) {
            hunks.add(new Hunk(hunks.size(), header, oldStart, oldCount, newStart, newCount, lines));
        }
        return hunks;
    }
}
