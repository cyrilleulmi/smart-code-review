package dev.smartcodereview.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * All data shapes of SmartCodeReview in one place. They are serialised 1:1 to the JSON files in the
 * data directory and to the REST API, so field names are part of the public (agent-facing) contract.
 */
public final class Model {

    private Model() {
    }

    public static final int SCHEMA_VERSION = 1;

    // ---------------------------------------------------------------- pull requests

    /**
     * Identifies a pull/merge request across providers. owner is the GitHub owner or the GitLab namespace;
     * nested GitLab groups are written with "~" instead of "/" (group~subgroup) so the ref fits in a URL
     * path and a directory name.
     */
    @RegisterForReflection
    public record PrRef(String provider, String owner, String repo, int number) {
        public String key() {
            return provider + "/" + owner + "/" + repo + "/" + number;
        }
    }

    @RegisterForReflection
    public record PullRequestSummary(
            String provider, String owner, String repo, int number,
            String title, String author, String url, boolean draft,
            String createdAt, String updatedAt,
            List<String> roles,
            Progress progress) {
    }

    @RegisterForReflection
    public record Progress(int ok, int total) {
    }

    @RegisterForReflection
    public record PullRequestInfo(
            String provider, String owner, String repo, int number,
            String title, String body, String author, String url, boolean draft, String state,
            String baseRef, String headRef, String baseSha, String headSha,
            String createdAt, String updatedAt,
            int additions, int deletions, int changedFiles,
            String startSha) {
    }

    @RegisterForReflection
    public record User(String login, String name, String avatarUrl) {
    }

    // ---------------------------------------------------------------- diff

    /** type: "add" | "del" | "ctx". key: "N<newLine>" for add/ctx, "O<oldLine>" for del. */
    @RegisterForReflection
    public record DiffLine(String type, Integer oldLine, Integer newLine, String key, String text) {
        public boolean changed() {
            return !"ctx".equals(type);
        }
    }

    @RegisterForReflection
    public record Hunk(int index, String header, int oldStart, int oldLines, int newStart, int newLines,
                       List<DiffLine> lines) {
    }

    /** status: added | modified | removed | renamed | copied | changed | unchanged */
    @RegisterForReflection
    public record DiffFile(String path, String previousPath, String status, int additions, int deletions,
                           String language, boolean patchMissing, List<Hunk> hunks) {
    }

    /** The frozen state of a PR as fetched from the provider. New commits are out of scope (see docs). */
    @RegisterForReflection
    public record Snapshot(int schemaVersion, String fetchedAt, PullRequestInfo pr, List<DiffFile> files) {
    }

    // ---------------------------------------------------------------- analysis

    /**
     * A line as shown in a code block. type: add | del | ctx | gap.
     * own=false means the line is changed but belongs to another segment (shown dimmed).
     * inDiff=true means GitHub accepts an inline comment on that line.
     * refs: on call sites, the segments that are called from here.
     */
    @RegisterForReflection
    public record DisplayLine(String type, Integer oldLine, Integer newLine, String key, String text,
                              boolean own, boolean inDiff, Integer gapSize, List<LineRef> refs) {
    }

    @RegisterForReflection
    public record LineRef(String segmentId, String symbol) {
    }

    /**
     * The unit of review: a coherent piece of a change (a hunk, a changed function, ...).
     * kind: hunk | symbol | module. lineKeys: the changed lines this segment owns (markable).
     * places: ids of all groups that show this segment (e.g. "quick-wins/mapper", "flows/flow-2"); every
     * place needs its own "ok" (see ReviewState.okAt). testedBy: ids of test segments that reach this one.
     * layer: domain | application | usecase | adapter when the project uses ports & adapters (else null).
     * frontend: the file belongs to a web frontend (Angular, React, Vue, templates, ...).
     */
    @RegisterForReflection
    public record Segment(String id, String path, String language, String kind,
                          String symbol, String symbolKind, Integer startLine, Integer endLine,
                          String category, String group, String ruleId, String reason,
                          List<String> lineKeys, List<DisplayLine> lines,
                          List<String> places, List<String> testedBy,
                          String layer, boolean frontend) {
    }

    @RegisterForReflection
    public record Group(String id, String label, String description, String flowId, List<String> segmentIds) {
    }

    @RegisterForReflection
    public record Category(String id, String label, String description, String icon, List<Group> groups) {
    }

    /** depth: distance from the entry point in the call tree; parentSegmentId: the caller (null for entries). */
    @RegisterForReflection
    public record FlowStep(String segmentId, int order, int depth, String parentSegmentId) {
    }

    /**
     * line: new-file line in the caller where the callee is referenced. kind: "call" (name reference) or
     * "http" (a frontend REST call matched to a backend endpoint; via is then e.g. "PUT /api/players/{}").
     */
    @RegisterForReflection
    public record FlowEdge(String from, String to, Integer line, String via, String kind) {
    }

    @RegisterForReflection
    public record Flow(String id, String title, List<FlowStep> steps, List<FlowEdge> edges) {
    }

    /**
     * A component a test is about. how: "name" (naming convention, e.g. PlayerResourceTest -> PlayerResource)
     * or "call" (the test calls changed code in it). path is null when no file of the PR matches.
     */
    @RegisterForReflection
    public record TestSubject(String name, String path, boolean inPr, String how) {
    }

    /**
     * Changed code reached from a test. direct: called by the test (or its helpers); otherwise reached via
     * the changed segment `via`. line: call site in the test (new-file line) that leads there.
     */
    @RegisterForReflection
    public record TestCoverage(String segmentId, boolean direct, String via, Integer line) {
    }

    /** One changed test case (it/test block, @Test method, Go TestXxx) and what it exercises - found statically. */
    @RegisterForReflection
    public record TestInfo(String segmentId, String name, String path, List<TestSubject> subjects,
                           List<TestCoverage> covers) {
    }

    /**
     * style: "hexagonal" when the repository is organised as ports & adapters (domain / application / use
     * cases / adapters), else null. evidence: the directories that show it.
     */
    @RegisterForReflection
    public record ArchitectureInfo(String style, List<String> evidence) {
    }

    /**
     * A component of the change: one class/module, i.e. the files of one name in one folder (player-view.ts,
     * .html and .scss together). area: column in the component diagram - frontend | adapter-in | api | usecase |
     * application | logic | domain | adapter-out | data | test.
     */
    @RegisterForReflection
    public record Component(String id, String name, List<String> paths, String area, List<String> segmentIds) {
    }

    /** Calls between components (aggregated segment edges); http = via a REST call. */
    @RegisterForReflection
    public record ComponentEdge(String from, String to, int calls, boolean http) {
    }

    @RegisterForReflection
    public record Analysis(int schemaVersion, String generatedAt, String headSha, String rulesHash,
                           List<Segment> segments, List<Category> categories, List<Flow> flows,
                           List<TestInfo> tests, ArchitectureInfo architecture,
                           List<Component> components, List<ComponentEdge> componentEdges) {
    }

    /** Directories of the repository at the PR head (to recognise its architecture). */
    @RegisterForReflection
    public record RepoStructure(List<String> directories, boolean truncated) {
    }

    /** User settings in <data>/settings.json. Disabled categories are hidden and need no ok. */
    @RegisterForReflection
    public record Settings(List<String> disabledCategories) {
    }

    // ---------------------------------------------------------------- local review state

    @RegisterForReflection
    public record LogEntry(String at, String by, String action, String scope, int count) {
    }

    /**
     * ok: path -> sorted line keys that are "ok" everywhere (at all places of the line). okAt: place ->
     * path -> line keys that are ok at that place only (lines shown at several places need one ok per
     * place; once all places are ok the line moves to ok). Lives only locally.
     */
    @RegisterForReflection
    public record ReviewState(int schemaVersion, String pr, String headSha,
                              Map<String, List<String>> ok, List<LogEntry> log,
                              Map<String, Map<String, List<String>>> okAt) {
    }

    /** place: the group id the mark applies to; null = at all places of the line. */
    @RegisterForReflection
    public record LineMark(String path, String key, String place) {
    }

    /** scope is free text for the log, e.g. "group:quick-wins/translations" or "line". by: "ui" or "agent:<name>". */
    @RegisterForReflection
    public record MarkRequest(List<LineMark> lines, boolean ok, String scope, String by) {
    }

    // ---------------------------------------------------------------- comments

    /** kind: review (inline) | issue (general). side: RIGHT (new) | LEFT (old). */
    @RegisterForReflection
    public record Comment(long id, String kind, String path, Integer line, String side, String key,
                          String body, String author, String avatarUrl, String createdAt, String url,
                          Long inReplyTo) {
    }

    /**
     * line/side address the line like GitHub does (RIGHT = new file, LEFT = old file). oldLine/newLine are
     * both set for unchanged context lines - GitLab needs both to anchor a comment there.
     */
    @RegisterForReflection
    public record NewComment(String path, Integer line, String side, String body, Long inReplyTo,
                             Integer oldLine, Integer newLine) {
    }

    // ---------------------------------------------------------------- api

    @RegisterForReflection
    public record FileSummary(String path, String previousPath, String status, int additions, int deletions,
                              String language) {
    }

    @RegisterForReflection
    public record ReviewBundle(PullRequestInfo pr, String fetchedAt, String dataPath, List<FileSummary> files,
                               Analysis analysis, ReviewState state) {
    }

    /** One configured code host. user is null when it has no credentials (error says why). */
    @RegisterForReflection
    public record Account(String provider, String label, String host, User user, String error) {
    }

    /** version: the running SmartCodeReview release (from pom.xml), e.g. "0.3.0". */
    @RegisterForReflection
    public record Me(List<Account> accounts, String dataDir, String version) {
    }
}
