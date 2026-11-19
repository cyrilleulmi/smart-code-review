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
     * path and a directory name. number 0 = the whole repository at the head of its default branch, reviewed
     * like a pull request in which every file is new.
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
    public record RepositorySummary(String provider, String owner, String repo, String description, String url,
                                    String updatedAt) {
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

    /** A repository's default branch and its newest commit (to review the whole repository). */
    @RegisterForReflection
    public record Repository(String defaultBranch, String headSha, String url, String description, String headDate) {
    }

    // ---------------------------------------------------------------- commits

    /**
     * A commit. title: first line of the message. author: git author name, authorLogin: account on the code host
     * (null if unknown). date: author date (ISO). parents: full SHAs. additions/deletions/files: what the commit
     * changed compared with its first parent (0 / empty in blame results).
     */
    @RegisterForReflection
    public record Commit(String sha, String title, String message, String author, String authorLogin, String avatarUrl,
                         String date, String url, List<String> parents,
                         int additions, int deletions, List<String> files) {
    }

    /**
     * commits.json: the commits of the PR (oldest first) and which commit made each changed line of the PR diff.
     * lines: path -> line key (as in segments[].lineKeys) -> sha. An added line belongs to the commit that last
     * wrote it, a deleted line to the commit that removed it; lines that could not be attributed are missing.
     * mergedVia: commits that are not on the first-parent chain of the head (merged side branches) -> the merge
     * commit their changes are attributed to. version: of the attribution algorithm (recomputed when it changes).
     * findings: what the order of the commits says about the tests (Commit-Bezug der Tests, see CommitFinding), by
     * commit order; recomputed whenever commits.json is served, from the stored attribution and the current analysis;
     * empty for a whole repository, null in files of older versions (and before they are computed).
     */
    @RegisterForReflection
    public record Commits(int version, String headSha, String generatedAt, List<Commit> commits,
                          Map<String, Map<String, String>> lines, Map<String, String> mergedVia, String error,
                          List<CommitFinding> findings) {
        public Commits(int version, String headSha, String generatedAt, List<Commit> commits,
                       Map<String, Map<String, String>> lines, Map<String, String> mergedVia, String error) {
            this(version, headSha, generatedAt, commits, lines, mergedVia, error, null);
        }

        public Commits withFindings(List<CommitFinding> list) {
            return new Commits(version, headSha, generatedAt, commits, lines, mergedVia, error, list);
        }
    }

    /**
     * Commit-Bezug der Tests (pattern-based, rules section "commitChecks"; see commits/CommitChecks): a finding from the
     * order of the PR's commits. kind: "expectation-later" (an assertion of a test written in this PR was changed by a
     * later commit than the test's declaration line) | "fix-without-test" (a commit whose title is a fix changes source
     * code and no test file) | "logic-without-test" (a commit changes testable code - CodeCoverage - and no test file,
     * and no later commit changes a test that reaches that code). level: "warn" | "info" (expectation-later is "warn"
     * when the later commit changes only test files or its title is a test adjustment, else "info"; fix-without-test
     * always "warn", logic-without-test always "info"). sha / title: the commit the finding is about. path / lineKey:
     * where it shows (the assertion line, the first line of the commit in the changed function or file; lineKey null
     * when no line of the commit is left in the PR diff). segmentId: the segment of that line (null if none);
     * testSegmentId: the test case (expectation-later), else null. detail: German explanation.
     */
    @RegisterForReflection
    public record CommitFinding(String kind, String level, String sha, String title, String path, String lineKey,
                                String segmentId, String testSegmentId, String detail) {
    }

    /** Lines start..end (1-based, inclusive) of a file were last changed by commit sha. */
    @RegisterForReflection
    public record BlameRange(int start, int end, String sha) {
    }

    /** git blame of a file at the PR head (sha). commits: the commits the ranges refer to. */
    @RegisterForReflection
    public record Blame(String path, String sha, List<BlameRange> ranges, List<Commit> commits) {
    }

    /**
     * A changed file as a whole at the PR head (sha): every line of the new version, the deleted lines woven in.
     * Keys are those of the segments' display lines.
     */
    @RegisterForReflection
    public record WholeFile(String path, String sha, List<DisplayLine> lines) {
    }

    // ---------------------------------------------------------------- analysis

    /**
     * A line as shown in a code block. type: add | del | ctx | gap.
     * own=false means the line is changed but belongs to another segment (shown dimmed).
     * inDiff=true means GitHub accepts an inline comment on that line.
     * refs: on call sites, the segments that are called from here.
     * hidden: on gaps, the lines the gap stands for (the UI can expand them; highlighting reads across them).
     */
    @RegisterForReflection
    public record DisplayLine(String type, Integer oldLine, Integer newLine, String key, String text,
                              boolean own, boolean inDiff, Integer gapSize, List<LineRef> refs, List<DisplayLine> hidden) {

        public DisplayLine(String type, Integer oldLine, Integer newLine, String key, String text,
                           boolean own, boolean inDiff, Integer gapSize, List<LineRef> refs) {
            this(type, oldLine, newLine, key, text, own, inDiff, gapSize, refs, null);
        }
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

    /**
     * description: what the category is for. builds / divides: how it is composed and split into groups (shown in the
     * UI). view: "groups" (list of groups) or an exception with its own UI ("flow"). origin: "builtin" | "rules";
     * later "external" for categories supplied from outside. The last four are null in analyses of older versions.
     */
    @RegisterForReflection
    public record Category(String id, String label, String description, String icon, List<Group> groups,
                           String builds, String divides, String view, String origin) {
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
     * A component a test is about. how: "name" (naming convention, e.g. PlayerResourceTest -> PlayerResource),
     * "call" (the test calls changed code in it) or "http" (the test sends an HTTP request to a changed endpoint in it).
     * path is null when no file of the PR matches.
     */
    @RegisterForReflection
    public record TestSubject(String name, String path, boolean inPr, String how) {
    }

    /**
     * Changed code reached from a test. direct: called by the test (or its helpers); otherwise reached via
     * the changed segment `via`. line: call site in the test (new-file line) that leads there. mocked: the segment
     * belongs to a collaborator the test replaces (TestProfile.mocks) - the test reaches only the mock, not this code,
     * and the walk through the call graph stops there (Mock-Grenze); false in analyses of older versions.
     * http: the way there starts with a real HTTP request of the test (HTTP-Tests): directly the endpoint the request
     * hits, indirectly what that endpoint calls; false in analyses of older versions. endpoint: for the endpoint reached
     * directly over HTTP, its method and path ("PUT /api/players/{}"), else null.
     */
    @RegisterForReflection
    public record TestCoverage(String segmentId, boolean direct, String via, Integer line, boolean mocked, boolean http,
                               String endpoint) {
        public TestCoverage(String segmentId, boolean direct, String via, Integer line, boolean mocked) {
            this(segmentId, direct, via, line, mocked, false, null);
        }
    }

    /**
     * One changed test case (it/test block, @Test method, Go TestXxx) and what it exercises - found statically.
     * profile: the test in one line (Test-Steckbrief); null in analyses of older versions. smells: signs that the test
     * is worth less than it looks (see TestSmell), empty when there are none and in analyses of older versions.
     */
    @RegisterForReflection
    public record TestInfo(String segmentId, String name, String path, List<TestSubject> subjects,
                           List<TestCoverage> covers, TestProfile profile, List<TestSmell> smells) {
        public TestInfo(String segmentId, String name, String path, List<TestSubject> subjects, List<TestCoverage> covers,
                        TestProfile profile) {
            this(segmentId, name, path, subjects, covers, profile, List.of());
        }
    }

    /**
     * A test smell: a sign that a changed test case is worth less than it looks (Test-Smells, pattern-based, rules
     * section "testSmells"; see analysis/TestSmells). kind: "no-assertion" | "weak-only" (all assertions only check
     * existence/truthiness) | "focused" (fit, it.only: CI skips all other tests) | "sleep" (fixed wait) |
     * "mocks-subject" (the test replaces the unit it tests by a mock or spy) | "disabled" (a new test that is switched
     * off). label / hint: German texts from the rules. line: where it shows (new-file line), null when the smell is
     * about the whole test (or lies outside of it); code: that line, trimmed, or null.
     */
    @RegisterForReflection
    public record TestSmell(String kind, String label, Integer line, String code, String hint) {
    }

    /**
     * Test-Steckbrief: what a test case checks, in one line (pattern-based, rules section "testProfile"; see
     * analysis/TestProfiles). targets: names of the changed code it calls directly, else the unit from the test file
     * name. scenario: "error" (expects a failure) | "edge" (empty/null/limit inputs) | "normal"; scenarioBy: "code" |
     * "name" (where the pattern matched; null for normal), scenarioReason: the matched text. assertions: assertion
     * statements in the test body; assertionKinds: their count per kind (exception, http, snapshot, interaction,
     * existence, equality - the order of the rules - then other; only kinds that occur). mocks: collaborators replaced
     * in the test or its surroundings (fields, setup blocks, enclosing suites), in order of appearance, "HTTP" for a
     * mocked HTTP backend. level: "unit" |
     * "integration" | "e2e"; levelBy: "path" | "code" (null for unit), levelReason: the matched glob or text.
     * parameterized: runs once per row of a table (@ParameterizedTest, it.each, Go table test); cases: number of rows
     * when the table is literal, else null; casesSource: where the rows come from (@CsvSource, it.each, table, ...).
     */
    @RegisterForReflection
    public record TestProfile(List<String> targets, String scenario, String scenarioBy, String scenarioReason,
                              int assertions, Map<String, Integer> assertionKinds, List<String> mocks,
                              String level, String levelBy, String levelReason,
                              boolean parameterized, Integer cases, String casesSource) {
    }

    /**
     * style: "hexagonal" when the repository is organised as ports & adapters (ports or use cases next to
     * domain and adapters), "layered" for a layered/onion split with adapter packages but no ports (domain /
     * application / adapter), else null. evidence: the directories that show it.
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

    /**
     * Test coverage seen from the code (static, changed tests plus the existing tests loaded for the change): one
     * entry per testable changed function - a function/method/component outside tests whose main category needs tests
     * (rules: needsTests). status: "direct" (a changed test calls it, also via test helpers), "indirect" (only reached
     * via other changed code), "existing" (no changed test reaches it, but an existing, unchanged test does - directly
     * or indirectly), "none". directTests / indirectTests: ids of the test segments. branches: decision points
     * in the whole function (if, case, catch, for, while, &&, ||, ternary ?). changedLines: non-blank changed
     * lines. risk = changedLines × (1 + branches), for sorting. existingTests: ids of the existing tests
     * (Analysis.existingTests) that reach it, directly or indirectly - also when the status is direct/indirect (old
     * tests that run through the change and may need adapting); null in analyses of older versions. Reaches of a
     * mocked collaborator (TestCoverage.mocked) do not count for the status or these lists; mockedTests: ids of the
     * changed and existing tests that only reach a mock of it (null in analyses of older versions). findings: what the
     * code can do (error outcomes, boundaries, effects) compared with what the tests that really reach it check
     * (Fall- und Effekt-Abgleich, see CaseFinding), ordered by line; null in analyses of older versions.
     */
    @RegisterForReflection
    public record CodeCoverage(String segmentId, String status, List<String> directTests, List<String> indirectTests,
                               int branches, int changedLines, int risk, List<String> existingTests,
                               List<String> mockedTests, List<CaseFinding> findings) {
        public CodeCoverage(String segmentId, String status, List<String> directTests, List<String> indirectTests,
                            int branches, int changedLines, int risk, List<String> existingTests, List<String> mockedTests) {
            this(segmentId, status, directTests, indirectTests, branches, changedLines, risk, existingTests, mockedTests,
                    List.of());
        }
    }

    /**
     * Fall- und Effekt-Abgleich: one thing the code of a changed function can do and whether a test that really reaches
     * it (changed or existing, direct or indirect, not mocked) checks it - pattern-based, see analysis/CaseChecks.
     * kind: "exception" (an error outcome: throw new X(…), throwError, errors.New; subject = the type or null) |
     * "boundary" (comparisons of a value with a number or a same-file constant; subject = the compared value) |
     * "effect" (a changed line sets a field, persists, sends a message or calls HTTP; subject = the field or call) |
     * "branches" (information only: more decision points than reaching tests; checked is always false).
     * line: where it is (new-file line); code: that line, trimmed; label: German short text (e.g. "wirft
     * NotFoundException (404)", "Grenze 1…99", "setzt jerseyNumberChangedAt", "5 Verzweigungen, 1 Test").
     * checked: a reaching test checks it (boundary: every boundary value is among the test inputs); checkedBy: those
     * tests (segment ids of changed tests, "e1" … of existing ones). covered: for boundaries the numeric input values of
     * the reaching tests (sorted), else empty; missing: boundary values no test feeds in, else empty.
     */
    @RegisterForReflection
    public record CaseFinding(String kind, Integer line, String code, String label, String subject, boolean checked,
                              List<String> checkedBy, List<String> covered, List<String> missing) {
    }

    /**
     * An existing test file loaded because it most likely tests changed code (Bestehende Tests, see
     * analysis/RelatedTests). reason: "mirror" (test file named after a changed file, in the mirrored test directory
     * or next to it), "name" (same name elsewhere in the repository), "prefix" (in the mirrored test directory, its name
     * starts with the name of the changed file, e.g. PlayerResourceSecurityTest). sources: the changed files it was
     * selected for. loaded: its content at the PR head could be fetched (sources-related/).
     */
    @RegisterForReflection
    public record RelatedTestFile(String path, String reason, List<String> sources, boolean loaded) {
    }

    /**
     * The existing test files selected for a change (related.json, Analysis.relatedTests). limit: at most this many
     * are loaded (rules "relatedTests.limit"); candidates: how many qualified; truncated: the limit cut the selection.
     * listIncomplete: the repository's file list was cut by the code host, a matching test may be missing. files: in
     * selection order (see RelatedTests).
     */
    @RegisterForReflection
    public record RelatedTests(int limit, int candidates, boolean truncated, boolean listIncomplete,
                               List<RelatedTestFile> files) {
    }

    /**
     * A test case of an existing, unchanged test file (RelatedTests) that reaches changed code. Not a segment: nothing
     * to review or mark. id: "e1", "e2", … (referenced by CodeCoverage.existingTests). line: start line in the file at
     * the PR head. reason: why its file was loaded (RelatedTestFile.reason). covers: the changed code it reaches, as for
     * changed tests (direct, or indirect via other changed code, also over HTTP; line = call site in the test). profile:
     * Test-Steckbrief.
     */
    @RegisterForReflection
    public record ExistingTest(String id, String path, String name, int line, String reason, List<TestCoverage> covers,
                               TestProfile profile) {
    }

    /**
     * One suite or test case in the specification outline of a changed test file (Spec-Diff). name: the test/suite
     * name (describe/it text, JUnit method or class name); suites: the enclosing suites, outermost first (JUnit: the
     * test class and its @Nested classes); kind: "suite" | "test". status: "added" | "removed" | "changed" (same path,
     * changed lines inside) | "renamed" (same body under another name) | "unchanged". previousName: for renamed
     * entries the old name (the old suite path joined with " › " when the suites differ too), else null.
     * segmentId: the segment of this suite/test when it has changed lines (null for removed and untouched entries).
     * line: start line in the new file, oldLine: in the base version (null where that side does not exist).
     * depth: indentation in the outline (number of enclosing suites as shown).
     * expectations: how the assertions of this test changed against the base version (see ExpectationChange; for
     * suites only "disabled"/"enabled"; empty for added and removed entries and without base version).
     * assertionsBefore / assertionsAfter: assertion statements in the test body in the base / new version (null for
     * suites and where that side does not exist). disabled: the test or suite is switched off now (itself or an
     * enclosing suite: xit, it.skip, @Disabled, t.Skip, ...); false for removed entries.
     */
    @RegisterForReflection
    public record SpecEntry(String name, List<String> suites, String kind, String status, String previousName,
                            String segmentId, Integer line, Integer oldLine, int depth,
                            List<ExpectationChange> expectations, Integer assertionsBefore, Integer assertionsAfter,
                            boolean disabled) {
    }

    /**
     * A changed expectation in a test (pattern-based, rules "assertions" / "weakAssertions" / "disabledTests").
     * kind: "changed" (same subject, other expected value or matcher) | "weakened" (changed to a matcher that only
     * checks existence/truthiness, or removed while only such matchers remain) | "removed" | "added" | "disabled" /
     * "enabled" (the test or an enclosing suite was switched off / on again). oldText / newText: the assertion
     * statement (lines joined, trimmed) in the base / new version, for disabled/enabled the line that switches the test
     * off; oldLine / newLine: its first line in the base / new file. Each side is null where it does not exist.
     */
    @RegisterForReflection
    public record ExpectationChange(String kind, String oldText, String newText, Integer oldLine, Integer newLine) {
    }

    /**
     * The test names of one changed test file, old against new version, as an outline. status: of the file (as in
     * DiffFile). baseAvailable: false when the base version was needed but could not be loaded - then only the new
     * side is shown (no removed entries; added/changed/unchanged derived from the diff alone). entries: in new file
     * order, removed entries at their old place (after their previous sibling, else first in their suite).
     */
    @RegisterForReflection
    public record SpecFile(String path, String previousPath, String status, boolean baseAvailable, List<SpecEntry> entries) {
    }

    /**
     * Fallmatrix: similar test cases of a changed test file (or the rows of one parameterised test) as a table input →
     * expected (pattern-based, see analysis/CaseMatrices). id: "m1", "m2", … in order; path: the test file; title:
     * common suite › shared call or test name; kind: "similar" (tests whose inputs are the same code apart from literal
     * values) | "parameterized" (@ValueSource/@CsvSource, it.each, Go table test). template: the code of the first row
     * with the columns as slots ‹1›, ‹2›, … (slot n = columns[n-1]). columns: input columns first, then expected ones.
     * rows: in source order. boundaries: boundaries in the code the rows' tests reach (Fall- und Effekt-Abgleich,
     * see MatrixBoundary); empty when there are none, null in analyses of older versions.
     */
    @RegisterForReflection
    public record CaseMatrix(String id, String path, String title, String kind, String template,
                             List<CaseColumn> columns, List<CaseRow> rows, List<MatrixBoundary> boundaries) {
        public CaseMatrix(String id, String path, String title, String kind, String template,
                          List<CaseColumn> columns, List<CaseRow> rows) {
            this(id, path, title, kind, template, columns, rows, List.of());
        }
    }

    /**
     * A boundary in the code reached by the tests of a Fallmatrix (Fall- und Effekt-Abgleich). segmentId: the changed
     * function with the comparison; subject: the compared value; label: e.g. "1…99"; values: its boundary values (both
     * sides of each comparison, sorted); missing: those that are no input value of this matrix.
     */
    @RegisterForReflection
    public record MatrixBoundary(String segmentId, String subject, String label, List<String> values, List<String> missing) {
    }

    /**
     * A column of a Fallmatrix. label: where the value goes (`putJerseyNumber(…) Arg 1`, `number =`, a parameter name)
     * or, for expected columns, what is checked (`statusCode`, `expect(el.textContent)`); role: "input" | "expected";
     * values: for input columns the distinct values of all rows, sorted (numbers numerically) - what is covered; empty
     * for expected columns.
     */
    @RegisterForReflection
    public record CaseColumn(String label, String role, List<String> values) {
    }

    /**
     * A row (one test case) of a Fallmatrix. name: the test name (parameterised: the display name of the case);
     * segmentId: the test's segment when it has changed lines, else null (unchanged test of the file); line: where the
     * test (parameterised: the row) starts in the new file. values: one per column, as written in the code (strings
     * with quotes; null = the test does not check this, "✓" = checked without an expected value, e.g. assertNull).
     * duplicateOf: index of an earlier row with the same input values (same case tested twice), else null;
     * contradicts: such a duplicate expects something else for the same input.
     */
    @RegisterForReflection
    public record CaseRow(String name, String segmentId, Integer line, List<String> values, Integer duplicateOf,
                          boolean contradicts) {
    }

    /**
     * A test that mocks code this PR changes (Mock-Grenzen): it replaces a collaborator with a changed callable segment
     * and stubs or references that member by name - it pins the old behaviour of that function. test: the test's
     * segment id (changed test) or ExistingTest.id ("e1"); testName / path: of the test; line: where the test or its
     * setup stubs / references the member (rules "testProfile.mockMembers"); segmentId: the mocked changed segment;
     * member: its name; collaborator: the mocked collaborator as in TestProfile.mocks.
     */
    @RegisterForReflection
    public record MockWarning(String test, String testName, String path, Integer line, String segmentId, String member,
                              String collaborator) {
    }

    /**
     * How the tests reach one step of a flow. status: "tested" (a test reaches it really, directly or via other changed
     * code), "mocked" (only reaches of a mock of it), "none". tests: ids of the tests that reach it really (changed
     * tests: segment id, existing tests: "e1", …); mockedBy: ids of the tests that only reach a mock of it.
     */
    @RegisterForReflection
    public record StepCoverage(String segmentId, String status, List<String> tests, List<String> mockedBy) {
    }

    /**
     * How the tests cover one connection of a flow. status: "tested" (a test reaches both ends really through this
     * call, i.e. crosses it), "separate" (both ends are tested really, but no single test crosses it - typical for the
     * REST hop between frontend and backend, http edges are never crossed), "partial" (only one end is tested really),
     * "none". tests: the tests that cross it. For a REST edge (kind http) both sides of the contract: clientTests = tests
     * that really reach the calling end and expect a request fitting the endpoint (HttpTestingController.expectOne,
     * cy.intercept, …), serverTests = tests that send an HTTP request to the endpoint (HTTP-Tests); empty for call edges
     * and in analyses of older versions.
     */
    @RegisterForReflection
    public record EdgeCoverage(String from, String to, String status, List<String> tests, List<String> clientTests,
                               List<String> serverTests) {
        public EdgeCoverage(String from, String to, String status, List<String> tests) {
            this(from, to, status, tests, List.of(), List.of());
        }
    }

    /** Mock-Grenzen of a flow: per step and per edge (in the order of Flow.steps / Flow.edges) what the tests execute. */
    @RegisterForReflection
    public record FlowCoverage(String flowId, List<StepCoverage> steps, List<EdgeCoverage> edges) {
    }

    /**
     * Erwartungs-Regel checked for one changed segment (see analysis/TestRules; rules section "testRules"): the segment
     * fits the rule's trigger, so a test of the expected form must exist. ruleId / label: of the rule (label German);
     * segmentId: the changed function, method or class; line: the trigger line (new file: the changed line that matched,
     * else the endpoint's declaration line, else the first line with a matching text, else the start of the symbol);
     * code: that line, trimmed; trigger: what triggered it (the matched text, or the endpoint as "DELETE /api/x/{}").
     * met: a test shows what the rule expects; metBy: those tests (segment ids of changed tests, "e1" … of existing
     * ones). tests: the tests that were considered - changed and existing tests that reach the segment for real (not
     * only a mock of it), also over HTTP (an endpoint reached by a request of the test, HTTP-Tests). hint:
     * German text for the missing test (from the rule).
     */
    @RegisterForReflection
    public record RuleCheck(String ruleId, String label, String segmentId, Integer line, String code, String trigger,
                            boolean met, List<String> metBy, List<String> tests, String hint) {
    }

    /**
     * Wirkung von Setup und Testdaten (see analysis/SetupImpact): a changed piece of test setup and the tests whose
     * meaning it changes, also those that do not appear in the diff. segmentId: the changed segment; kind: "setup" (a
     * beforeEach/afterAll block or a JUnit @BeforeEach/@AfterAll/… method) | "fixture" (a top-level constant of a test
     * file, a field or suite-level variable with a value) | "helper" (a function, method or class of a test file or of a
     * test-support path that is no test: builders, factories, render helpers); name: its name (container.name for
     * methods); line: its declaration line (new file). affected: the tests it reaches, by path and line - a setup block
     * every test in its suite or class (incl. nested ones; a top-level block the whole file, a base class its
     * subclasses), a fixture or helper every test that references its name, directly or through other helpers, setup
     * blocks or fields that do (same file, or other test files with import evidence). unchangedCount: how many of them
     * are unchanged in this PR - the tests whose meaning changes silently.
     */
    @RegisterForReflection
    public record SetupImpact(String segmentId, String kind, String name, Integer line, List<AffectedTest> affected,
                              int unchangedCount) {
    }

    /**
     * A test reached by a SetupImpact. path / name (display name with suites) / line: of the test (new file at the PR
     * head). segmentId: the test's segment when it has changed lines in this PR, else null. status: "added" | "changed"
     * | "renamed" | "unchanged" (from the Spec-Diff where there is one; tests of existing, unchanged test files - see
     * RelatedTests - are "unchanged"). existing: the test is in an existing test file loaded for the change.
     */
    @RegisterForReflection
    public record AffectedTest(String path, String name, int line, String segmentId, String status, boolean existing) {
    }

    /**
     * coverage: testable changed functions, "none" first, then "indirect", then "direct", each by risk (highest
     * first); null in analyses of older versions. specs: the test names of every changed test file as old/new outline
     * (Spec-Diff), in path order; null in analyses of older versions. caseMatrices: similar and parameterised tests of
     * the changed test files as tables input → expected (Fallmatrix), by path and line; null in analyses of older
     * versions. existingTests: test cases of existing, unchanged test files that reach changed code (Bestehende
     * Tests), by path and line; relatedTests: which existing test files were selected and loaded for that (null for a
     * whole repository and in analyses of older versions). mockWarnings: tests that mock changed code, by path and
     * line; flowCoverage: per flow (same order as flows) which steps and edges the tests really execute (Mock-Grenzen);
     * both null in analyses of older versions. ruleChecks: the Erwartungs-Regeln that apply to changed code and whether a
     * test of the expected form exists (see RuleCheck), by segment and line; empty for a whole repository, null in
     * analyses of older versions. setupImpacts: changed setup blocks, fixtures and test helpers with the tests they
     * affect (Wirkung von Setup und Testdaten, see SetupImpact), by path and line; empty for a whole repository, null in
     * analyses of older versions.
     */
    @RegisterForReflection
    public record Analysis(int schemaVersion, String generatedAt, String headSha, String rulesHash,
                           List<Segment> segments, List<Category> categories, List<Flow> flows,
                           List<TestInfo> tests, ArchitectureInfo architecture,
                           List<Component> components, List<ComponentEdge> componentEdges,
                           List<CodeCoverage> coverage, List<SpecFile> specs, List<CaseMatrix> caseMatrices,
                           List<ExistingTest> existingTests, RelatedTests relatedTests,
                           List<MockWarning> mockWarnings, List<FlowCoverage> flowCoverage,
                           List<RuleCheck> ruleChecks, List<SetupImpact> setupImpacts) {
    }

    /**
     * Directories of the repository at the PR head (to recognise its architecture), plus the API contract files
     * found there (OpenAPI/Swagger) and the operations declared in them. operations null = read by an older version.
     * files: paths of all files (blobs) of the repository, to find existing tests of changed code; null = read by an
     * older version. truncated: the code host cut the listing (directories and files may be incomplete).
     */
    @RegisterForReflection
    public record RepoStructure(List<String> directories, boolean truncated, List<String> contracts,
                                List<ApiOperation> operations, List<String> files) {
        public RepoStructure(List<String> directories, boolean truncated) {
            this(directories, truncated, List.of(), List.of(), List.of());
        }
    }

    /**
     * One operation of an OpenAPI contract: generated clients call it by operationId, generated server
     * interfaces declare a method of that name. path includes the server base path; line/endLine: 1-based
     * range of the operation in the contract file (at the PR head).
     */
    @RegisterForReflection
    public record ApiOperation(String file, String operationId, String method, String path, int line, int endLine) {
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
     * place; once all places are ok the line moves to ok). headSha: the snapshot the line keys refer to.
     * reload: what the last reload with new commits did to the marks (null before the first one). Lives only locally.
     */
    @RegisterForReflection
    public record ReviewState(int schemaVersion, String pr, String headSha,
                              Map<String, List<String>> ok, List<LogEntry> log,
                              Map<String, Map<String, List<String>>> okAt, ReloadInfo reload) {
    }

    /**
     * A reload that brought new commits (or a rebase): the marks were carried from the line keys of fromHeadSha to
     * those of toHeadSha. A mark moves only with an unchanged line (same text, same order among the changed lines of
     * its file); marks on changed or vanished lines are dropped. kept / dropped: lines whose marks were carried /
     * lost. newLines: path -> line keys that are new or changed since fromHeadSha, plus those still open from earlier
     * reloads (the "new since my review" filter).
     */
    @RegisterForReflection
    public record ReloadInfo(String fromHeadSha, String toHeadSha, String at, int kept, int dropped,
                             Map<String, List<String>> newLines) {
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

    /**
     * kind: review (inline) | issue (general). side: RIGHT (new) | LEFT (old). thread: the host's id of the thread the
     * comment belongs to (GitLab: discussion id), null where the host has none. resolved: state of that thread, the
     * same on all its comments - null when it cannot be resolved (a plain comment, or a host without this), else
     * whether it is resolved.
     */
    @RegisterForReflection
    public record Comment(long id, String kind, String path, Integer line, String side, String key,
                          String body, String author, String avatarUrl, String createdAt, String url,
                          Long inReplyTo, String thread, Boolean resolved) {
    }

    @RegisterForReflection
    public record ResolveRequest(boolean resolved) {
    }

    /**
     * line/side address the line like GitHub does (RIGHT = new file, LEFT = old file). oldLine/newLine are
     * both set for unchanged context lines - GitLab needs both to anchor a comment there.
     */
    @RegisterForReflection
    public record NewComment(String path, Integer line, String side, String body, Long inReplyTo,
                             Integer oldLine, Integer newLine) {
    }

    // ---------------------------------------------------------------- approve & merge

    /**
     * Where a PR stands on the code host, read live for approving and merging it. state: open | merged | closed.
     * headSha: its head now (may be newer than the snapshot). approvedBy: logins. approvalsLeft: required approvals
     * still missing (0 when none are required). canApprove: the user may approve or revoke the approval. canMerge:
     * the user has the right to merge. mergeable: the host would merge it now; blocker: German text why not (null
     * when mergeable). squash, removeSourceBranch: what the PR is set to do on merge. pipeline: status of the head
     * pipeline as the host names it (success, failed, running, ...), null without one.
     */
    @RegisterForReflection
    public record PrStatus(String state, boolean draft, String headSha, List<String> approvedBy, boolean approvedByMe,
                           boolean canApprove, int approvalsLeft, boolean canMerge, boolean mergeable, String blocker,
                           boolean squash, boolean removeSourceBranch, String pipeline) {
    }

    @RegisterForReflection
    public record ApprovalRequest(boolean approved) {
    }

    /** squash, removeSourceBranch: null = as the PR is set. */
    @RegisterForReflection
    public record MergeOptions(Boolean squash, Boolean removeSourceBranch) {
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

    /**
     * One configured code host. user is null when it has no credentials (error says why). cookie: null when the host
     * takes no session cookies of an SSO proxy, else whether a cookie is set (its value is never sent to the UI).
     */
    @RegisterForReflection
    public record Account(String provider, String label, String host, User user, String error, Boolean cookie) {
    }

    /** cookie: value of the Cookie header ("name=value; name2=value2"); blank removes it. */
    @RegisterForReflection
    public record CookieRequest(String cookie) {
    }

    /** version: the running SmartCodeReview release (from pom.xml), e.g. "0.3.0". */
    @RegisterForReflection
    public record Me(List<Account> accounts, String dataDir, String version) {
    }
}
