import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { serverText, t } from './i18n';

// Mirrors backend dev.smartcodereview.model.Model

export interface User {
  login: string;
  name: string | null;
  avatarUrl: string | null;
}

export interface Account {
  provider: string;
  label: string;
  host: string;
  user: User | null;
  error: string | null;
  /** null: the host takes no session cookies of an SSO proxy; else whether a cookie is set */
  cookie?: boolean | null;
}

export interface Me {
  accounts: Account[];
  dataDir: string;
  /** running release, e.g. "0.3.0" */
  version: string;
}

export interface ProviderInfo {
  label: string;
  /** "#12" on GitHub, "!12" on GitLab */
  sigil: string;
  noun: string;
}

const PROVIDERS: Record<string, ProviderInfo> = {
  github: { label: 'GitHub', sigil: '#', noun: 'Pull Request' },
  gitlab: { label: 'GitLab', sigil: '!', noun: 'Merge Request' },
};

export function providerInfo(provider: string | null | undefined): ProviderInfo {
  return PROVIDERS[provider ?? ''] ?? { label: provider ?? '?', sigil: '#', noun: 'Pull Request' };
}

/** Nested GitLab groups travel as "group~sub" in refs and URLs; show them with slashes. */
export function ownerLabel(owner: string): string {
  return owner.replaceAll('~', '/');
}

/** e.g. "cyrilleulmi/spongecoach#13" or "gitlab-org/cli!4347"; number 0 = the whole repository */
export function prLabel(pr: { provider: string; owner: string; repo: string; number: number }): string {
  const repo = `${ownerLabel(pr.owner)}/${pr.repo}`;
  return pr.number === 0 ? `${repo} (Repository)` : `${repo}${providerInfo(pr.provider).sigil}${pr.number}`;
}

export type PrRole = 'authored' | 'review-requested' | 'involved';

export interface RepositorySummary {
  provider: string;
  owner: string;
  repo: string;
  description: string;
  url: string;
  updatedAt: string;
}

export interface PullRequestSummary {
  provider: string;
  owner: string;
  repo: string;
  number: number;
  title: string;
  author: string;
  url: string;
  draft: boolean;
  createdAt: string;
  updatedAt: string;
  roles: PrRole[];
  progress: { ok: number; total: number } | null;
}

export interface PullRequestInfo {
  provider: string;
  owner: string;
  repo: string;
  number: number;
  title: string;
  body: string;
  author: string;
  url: string;
  draft: boolean;
  state: string;
  baseRef: string;
  headRef: string;
  baseSha: string;
  headSha: string;
  createdAt: string;
  updatedAt: string;
  additions: number;
  deletions: number;
  changedFiles: number;
  startSha: string | null;
}

/** A commit (Model.Commit). additions/deletions/files: vs. its first parent (0/empty in blame results). */
export interface Commit {
  sha: string;
  title: string;
  message: string;
  author: string | null;
  authorLogin: string | null;
  avatarUrl: string | null;
  date: string | null;
  url: string | null;
  parents: string[];
  additions: number;
  deletions: number;
  files: string[];
}

/** commits.json: the PR's commits (oldest first) and which commit made each changed line. */
export interface Commits {
  version: number;
  headSha: string;
  generatedAt: string;
  commits: Commit[];
  /** path -> line key -> sha */
  lines: Record<string, Record<string, string>>;
  /** commit of a merged side branch -> merge commit its changes are attributed to */
  mergedVia: Record<string, string>;
  error: string | null;
  /** Commit-Bezug der Tests, by commit order; null from older versions */
  findings?: CommitFinding[] | null;
}

/**
 * expectation-later: an assertion of a test written in this PR was changed by a later commit; fix-without-test: a fix
 * commit changes code and no test; logic-without-test: a commit changes testable code and no test, and no later
 * commit changes a test that reaches it.
 */
export type CommitFindingKind = 'expectation-later' | 'fix-without-test' | 'logic-without-test';

/** Commit-Bezug der Tests (Model.CommitFinding): what the order of the PR's commits says about its tests. */
export interface CommitFinding {
  kind: CommitFindingKind;
  /** expectation-later is a warning when the later commit changes only tests or adjusts tests by its title */
  level: 'warn' | 'info';
  /** the commit the finding is about */
  sha: string;
  title: string;
  path: string;
  /** where it shows (the assertion line, the commit's first line in the function or file); null if none is left */
  lineKey: string | null;
  /** segment of that line */
  segmentId: string | null;
  /** the test case (expectation-later) */
  testSegmentId: string | null;
  /** German explanation */
  detail: string;
}

export interface BlameRange {
  start: number;
  end: number;
  sha: string;
}

/** git blame of a file at the PR head */
export interface Blame {
  path: string;
  sha: string;
  ranges: BlameRange[];
  commits: Commit[];
}

/** A changed file as a whole at the PR head: every line of the new version, deleted lines woven in. */
export interface WholeFile {
  path: string;
  sha: string;
  lines: DisplayLine[];
}

export interface LineRef {
  segmentId: string;
  symbol: string;
}

export interface DisplayLine {
  type: 'add' | 'del' | 'ctx' | 'gap';
  oldLine: number | null;
  newLine: number | null;
  key: string | null;
  text: string | null;
  own: boolean;
  inDiff: boolean;
  gapSize: number | null;
  refs: LineRef[] | null;
  /** on gaps: the lines the gap stands for (expandable; read by the highlighting) */
  hidden?: DisplayLine[] | null;
}

export interface Segment {
  id: string;
  path: string;
  language: string;
  kind: 'hunk' | 'symbol' | 'module';
  symbol: string | null;
  symbolKind: string | null;
  startLine: number | null;
  endLine: number | null;
  category: string;
  group: string;
  ruleId: string | null;
  reason: string;
  lineKeys: string[];
  lines: DisplayLine[];
  /** ids of all groups showing this segment ("quick-wins/mapper", "flows/flow-2", ...); each needs its own ok */
  places: string[];
  /** test segments that reach this code */
  testedBy: string[];
  /** ports & adapters layer (domain | application | usecase | adapter), null if none */
  layer: string | null;
  frontend: boolean;
}

export interface Group {
  id: string;
  label: string;
  description: string | null;
  flowId: string | null;
  segmentIds: string[];
}

/** Categories with their own UI (data flows) say so in `view`; older analyses only have the id to go by. */
export function isFlowView(c: { id: string; view?: string | null }): boolean {
  return (c.view ?? (c.id === 'flows' ? 'flow' : 'groups')) === 'flow';
}

/** The test category: groups plus the test overview and the coverage of the changed code. */
export function isTestsView(c: { view?: string | null }): boolean {
  return c.view === 'tests';
}

export interface Category {
  id: string;
  label: string;
  /** what the category is for */
  description: string;
  icon: string;
  groups: Group[];
  /** how the category is composed / split into groups (absent in analyses of older versions) */
  builds?: string | null;
  divides?: string | null;
  /** 'groups' = list of groups; other values are exceptions with their own UI ('flow', 'tests') */
  view?: string | null;
  /** 'builtin' | 'rules' (| later 'external') */
  origin?: string | null;
}

export interface FlowStep {
  segmentId: string;
  order: number;
  depth: number;
  parentSegmentId: string | null;
}

export interface FlowEdge {
  from: string;
  to: string;
  line: number | null;
  via: string;
  /** "http" = frontend REST call matched to a backend endpoint (via = "PUT /api/x/{}") */
  kind: 'call' | 'http';
}

export interface Flow {
  id: string;
  title: string;
  steps: FlowStep[];
  edges: FlowEdge[];
}

export interface TestSubject {
  name: string;
  path: string | null;
  inPr: boolean;
  /** name: naming convention; call: the test calls changed code in it; http: it sends an HTTP request to a changed endpoint in it */
  how: 'name' | 'call' | 'http';
}

export interface TestCoverage {
  segmentId: string;
  direct: boolean;
  via: string | null;
  line: number | null;
  /** only a mock of it is reached (its collaborator is mocked in the test); the walk stops there (absent in older analyses) */
  mocked?: boolean;
  /** HTTP-Tests: the way starts with a real HTTP request of the test (absent in older analyses) */
  http?: boolean;
  /** for the endpoint reached directly over HTTP: its method and path («PUT /api/players/{}»), else null */
  endpoint?: string | null;
}

export interface TestInfo {
  segmentId: string;
  name: string;
  path: string;
  subjects: TestSubject[];
  covers: TestCoverage[];
  /** Test-Steckbrief; null in analyses of older versions */
  profile: TestProfile | null;
  /** Test-Smells: signs that the test is worth less than it looks (absent in analyses of older versions) */
  smells?: TestSmell[];
}

export type TestSmellKind = 'no-assertion' | 'weak-only' | 'focused' | 'sleep' | 'mocks-subject' | 'disabled';

/** Model.TestSmell: label and hint are German texts from the rules; line/code: where it shows (null: the whole test). */
export interface TestSmell {
  kind: TestSmellKind;
  label: string;
  line: number | null;
  code: string | null;
  hint: string;
}

export type TestScenario = 'error' | 'edge' | 'normal';
export type TestLevel = 'unit' | 'integration' | 'e2e';
export type AssertionKind = 'equality' | 'exception' | 'interaction' | 'existence' | 'http' | 'snapshot' | 'other';

/**
 * Test-Steckbrief (Model.TestProfile): what a test checks, in one line - pattern-based (rules section
 * "testProfile"), deterministic.
 */
export interface TestProfile {
  /** changed code the test calls directly (symbol names), else the unit from the test file name */
  targets: string[];
  /** error = expects a failure, edge = empty/null/limit inputs, normal = everything else */
  scenario: TestScenario;
  /** where the scenario pattern matched (null for normal) */
  scenarioBy: 'code' | 'name' | null;
  /** the matched text */
  scenarioReason: string | null;
  /** assertion statements in the test body */
  assertions: number;
  /** count per kind, only kinds that occur */
  assertionKinds: Partial<Record<AssertionKind, number>>;
  /** collaborators replaced in the test or its surroundings ("HTTP" = mocked backend) */
  mocks: string[];
  level: TestLevel;
  /** null for unit */
  levelBy: 'path' | 'code' | null;
  /** the matched glob or code */
  levelReason: string | null;
  /** runs once per row of a table (@ParameterizedTest, it.each, Go table test) */
  parameterized: boolean;
  /** rows when the table is literal, else null */
  cases: number | null;
  /** @CsvSource, @ValueSource, it.each, table, ... */
  casesSource: string | null;
}

export type CoverageStatus = 'direct' | 'indirect' | 'existing' | 'none';

/**
 * Coverage of a testable changed function, seen from the code (Model.CodeCoverage), found statically:
 * direct = a changed test calls it, indirect = only reached via other changed code, existing = no changed test reaches
 * it but an existing, unchanged test does, none = no test reaches it.
 */
export interface CodeCoverage {
  segmentId: string;
  status: CoverageStatus;
  directTests: string[];
  indirectTests: string[];
  /** decision points in the whole function (if, case, catch, for, while, &&, ||, ternary) */
  branches: number;
  /** non-blank, non-brace changed lines */
  changedLines: number;
  /** changedLines × (1 + branches), for sorting */
  risk: number;
  /** ids of existing tests (Analysis.existingTests) reaching it, also for direct/indirect (absent in older analyses) */
  existingTests?: string[] | null;
  /** ids of changed and existing tests that only reach a mock of it - they do not count as coverage (absent in older analyses) */
  mockedTests?: string[] | null;
  /** Fall- und Effekt-Abgleich: what the code can do against what the reaching tests check, by line (absent in older analyses) */
  findings?: CaseFinding[] | null;
}

/** exception: an error outcome · boundary: a comparison with a number · effect: a field/persist/message/HTTP effect · branches: information */
export type CaseFindingKind = 'exception' | 'boundary' | 'effect' | 'branches';

/**
 * Fall- und Effekt-Abgleich (Model.CaseFinding): one thing a changed function can do and whether a test that really
 * reaches it checks it - pattern-based, deterministic.
 */
export interface CaseFinding {
  kind: CaseFindingKind;
  /** new-file line in the function; null for branches */
  line: number | null;
  /** that line, trimmed */
  code: string | null;
  /** «wirft NotFoundException (404)», «Grenze 1…99», «setzt jerseyNumberChangedAt», «5 Verzweigungen, 1 Test» */
  label: string;
  /** exception type, compared value, field or call; null for branches */
  subject: string | null;
  /** a reaching test checks it (boundary: every boundary value is a test input); always false for branches */
  checked: boolean;
  /** the tests that check it (segment ids of changed tests, «e1» … of existing ones) */
  checkedBy: string[];
  /** boundary: the integer inputs of the reaching tests, sorted */
  covered: string[];
  /** boundary: the boundary values no test feeds in */
  missing: string[];
}

/** Why an existing test file was loaded: named after the changed file (mirrored/same directory), elsewhere, or by prefix. */
export type RelatedReason = 'mirror' | 'name' | 'prefix';

/** An existing, unchanged test file loaded for the change (Model.RelatedTestFile). */
export interface RelatedTestFile {
  path: string;
  reason: RelatedReason;
  /** the changed files it was selected for */
  sources: string[];
  /** its content at the PR head could be loaded */
  loaded: boolean;
}

/** The existing test files selected for the change (Model.RelatedTests, related.json). */
export interface RelatedTests {
  /** at most this many files are loaded */
  limit: number;
  /** how many qualified */
  candidates: number;
  /** the limit cut the selection */
  truncated: boolean;
  /** the repository's file list was cut by the code host */
  listIncomplete: boolean;
  files: RelatedTestFile[];
}

/**
 * A test case of an existing, unchanged test file that reaches changed code (Model.ExistingTest). Not a segment: it
 * is not part of the diff, so it cannot be opened in the file view.
 */
export interface ExistingTest {
  /** e1, e2, … (CodeCoverage.existingTests) */
  id: string;
  path: string;
  name: string;
  line: number;
  reason: RelatedReason;
  covers: TestCoverage[];
  profile: TestProfile | null;
}

export type SpecStatus = 'added' | 'removed' | 'changed' | 'renamed' | 'unchanged';

/** One suite or test of a changed test file in the Spec-Diff outline (Model.SpecEntry). */
export interface SpecEntry {
  name: string;
  /** enclosing suites, outermost first (JUnit: test class and @Nested classes) */
  suites: string[];
  kind: 'suite' | 'test';
  status: SpecStatus;
  /** renamed: the old name (old suite path joined with " › " when the suites differ too) */
  previousName: string | null;
  /** segment with this suite/test when it has changed lines; null for removed and untouched entries */
  segmentId: string | null;
  /** start line in the new file / in the base version */
  line: number | null;
  oldLine: number | null;
  /** indentation in the outline */
  depth: number;
  /** changed expectations against the base version (absent in older analyses) */
  expectations?: ExpectationChange[] | null;
  /** assertion statements in the test body, base / new version (null for suites and missing sides) */
  assertionsBefore?: number | null;
  assertionsAfter?: number | null;
  /** switched off now (itself or an enclosing suite: xit, it.skip, @Disabled, t.Skip …) */
  disabled?: boolean;
}

export type ExpectationKind = 'changed' | 'weakened' | 'removed' | 'added' | 'disabled' | 'enabled';

/**
 * A changed expectation in a test (Model.ExpectationChange), pattern-based: changed = same subject, other expected
 * value or matcher; weakened = to a matcher that only checks existence/truthiness; disabled/enabled = the test or an
 * enclosing suite was switched off/on. Texts: the assertion statement (or the switching line); null where that side
 * does not exist.
 */
export interface ExpectationChange {
  kind: ExpectationKind;
  oldText: string | null;
  newText: string | null;
  oldLine: number | null;
  newLine: number | null;
}

/** The test names of a changed test file, old against new version (Model.SpecFile). */
export interface SpecFile {
  path: string;
  previousPath: string | null;
  status: string;
  /** false: the base version could not be loaded, only the new side is shown */
  baseAvailable: boolean;
  /** new file order, removed entries at their old place */
  entries: SpecEntry[];
}

export interface ArchitectureInfo {
  /** hexagonal: ports & adapters; layered: domain / application / adapter packages without ports */
  style: 'hexagonal' | 'layered' | null;
  evidence: string[];
}

export interface Component {
  id: string;
  name: string;
  paths: string[];
  /** column in the component diagram */
  area: string;
  segmentIds: string[];
}

export interface ComponentEdge {
  from: string;
  to: string;
  calls: number;
  http: boolean;
}

export interface Settings {
  disabledCategories: string[];
}

/**
 * A test that mocks code this PR changes (Model.MockWarning): it replaces a collaborator with a changed function and
 * stubs or references that member by name - it pins the old behaviour.
 */
export interface MockWarning {
  /** segment id of a changed test, or the id of an existing test ("e1") */
  test: string;
  testName: string;
  path: string;
  /** where the test or its setup stubs / references the member */
  line: number | null;
  /** the mocked changed segment */
  segmentId: string;
  member: string;
  collaborator: string;
}

/** tested: a test runs it for real · mocked: tests only reach a mock of it · none: no test reaches it */
export type StepRunStatus = 'tested' | 'mocked' | 'none';
/** tested: one test crosses the call · separate: both ends tested, but by different tests · partial: one end · none */
export type EdgeRunStatus = 'tested' | 'separate' | 'partial' | 'none';

export interface StepCoverage {
  segmentId: string;
  status: StepRunStatus;
  /** tests reaching it for real (segment ids of changed tests, "e1" … for existing ones) */
  tests: string[];
  /** tests reaching only a mock of it */
  mockedBy: string[];
}

export interface EdgeCoverage {
  from: string;
  to: string;
  status: EdgeRunStatus;
  /** tests that cross the call */
  tests: string[];
  /** REST edge: tests that reach the calling end and expect a request fitting the endpoint (absent in older analyses) */
  clientTests?: string[];
  /** REST edge: tests that send an HTTP request to the endpoint (absent in older analyses) */
  serverTests?: string[];
}

/** Mock-Grenzen of a flow: what the tests really execute, per step and per edge (Model.FlowCoverage). */
export interface FlowCoverage {
  flowId: string;
  steps: StepCoverage[];
  edges: EdgeCoverage[];
}

export interface Analysis {
  schemaVersion: number;
  generatedAt: string;
  headSha: string;
  rulesHash: string;
  segments: Segment[];
  categories: Category[];
  flows: Flow[];
  tests: TestInfo[];
  architecture: ArchitectureInfo | null;
  components: Component[];
  componentEdges: ComponentEdge[];
  /** testable changed functions: none, indirect, existing, direct, each by risk (absent in older analyses) */
  coverage?: CodeCoverage[] | null;
  /** Spec-Diff: test names of every changed test file, in path order (absent in older analyses) */
  specs?: SpecFile[] | null;
  /** Fallmatrix: similar and parameterised tests as tables input → expected, by path and line (absent in older analyses) */
  caseMatrices?: CaseMatrix[] | null;
  /** Bestehende Tests: test cases of existing, unchanged test files that reach changed code (absent in older analyses) */
  existingTests?: ExistingTest[] | null;
  /** the existing test files selected and loaded for that; null for a whole repository and older analyses */
  relatedTests?: RelatedTests | null;
  /** Mock-Grenzen: tests that mock changed code, by path and line (absent in older analyses) */
  mockWarnings?: MockWarning[] | null;
  /** Mock-Grenzen: per flow what the tests really execute (absent in older analyses) */
  flowCoverage?: FlowCoverage[] | null;
  /** Erwartungs-Regeln: rules that apply to changed code and whether a test of the expected form exists (empty for a whole repository, absent in older analyses) */
  ruleChecks?: RuleCheck[] | null;
  /** Wirkung von Setup und Testdaten: changed setup blocks, fixtures and test helpers with the tests they affect (empty for a whole repository, absent in older analyses) */
  setupImpacts?: SetupImpact[] | null;
}

/** setup: beforeEach & co., JUnit @BeforeEach/@AfterAll … · fixture: constant, field or suite variable with a value · helper: function, method, class of test code */
export type SetupImpactKind = 'setup' | 'fixture' | 'helper';

/**
 * Wirkung von Setup und Testdaten (Model.SetupImpact): a changed piece of test setup and the tests whose meaning it
 * changes - also those the diff does not show.
 */
export interface SetupImpact {
  /** the changed setup block, fixture or helper */
  segmentId: string;
  kind: SetupImpactKind;
  /** its name («CARMELA», «PlayerView › beforeEach», «TestData.player») */
  name: string;
  /** declaration line (new file) */
  line: number | null;
  /** the tests it reaches, its own file first, then by path and line */
  affected: AffectedTest[];
  /** how many of them this PR leaves unchanged - they change their meaning silently */
  unchangedCount: number;
}

/** A test reached by a SetupImpact (Model.AffectedTest). */
export interface AffectedTest {
  path: string;
  /** display name with its suites («PlayerView › jersey number › saves it») */
  name: string;
  /** start line at the PR head */
  line: number;
  /** the test's segment when it has changed lines in this PR, else null */
  segmentId: string | null;
  /** from the Spec-Diff; tests of existing test files are «unchanged» */
  status: 'added' | 'changed' | 'renamed' | 'unchanged';
  /** in an existing test file loaded for the change (not part of the PR) */
  existing: boolean;
}

/**
 * Erwartungs-Regel checked for one changed segment (Model.RuleCheck): the segment fits the rule's trigger, so a test of
 * the expected form must exist - pattern-based, deterministic (rules section «testRules»).
 */
export interface RuleCheck {
  ruleId: string;
  /** German, e.g. «Zugriffsprüfung geändert → Test für verweigerten Zugriff» */
  label: string;
  segmentId: string;
  /** trigger line (new file) */
  line: number | null;
  /** that line, trimmed */
  code: string | null;
  /** what triggered it: the matched text or the endpoint («DELETE /api/players/{}/jersey-number») */
  trigger: string | null;
  met: boolean;
  /** tests that show what the rule expects (segment ids of changed tests, «e1» … of existing ones) */
  metBy: string[];
  /** tests that were considered: reaching it for real, for an endpoint also calling it over HTTP */
  tests: string[];
  /** German text for the missing test */
  hint: string;
}

export type CaseMatrixKind = 'similar' | 'parameterized';
export type CaseColumnRole = 'input' | 'expected';

/**
 * Fallmatrix (Model.CaseMatrix): near-identical tests of a changed test file, or the rows of one parameterised test,
 * as a table input → expected - pattern-based, deterministic. template: the code of the first row with the columns as
 * slots ‹1›, ‹2›, … (slot n = columns[n-1]); columns: input columns first, then expected ones.
 */
export interface CaseMatrix {
  id: string;
  path: string;
  title: string;
  kind: CaseMatrixKind;
  template: string;
  columns: CaseColumn[];
  rows: CaseRow[];
  /** boundaries in the code its tests reach, with the values no row feeds in (absent in older analyses) */
  boundaries?: MatrixBoundary[] | null;
}

/** A boundary in the code reached by the tests of a Fallmatrix (Model.MatrixBoundary). */
export interface MatrixBoundary {
  segmentId: string;
  /** the compared value */
  subject: string;
  /** «1…99», «≥ 1» */
  label: string;
  /** boundary values (both sides of each comparison) */
  values: string[];
  /** those that no row of the matrix uses as input */
  missing: string[];
}

export interface CaseColumn {
  /** where the value goes (`putJerseyNumber(…) Arg 1`, a parameter name) or what is checked (`statusCode`) */
  label: string;
  role: CaseColumnRole;
  /** input columns: the distinct values of all rows, sorted - what is covered; empty for expected columns */
  values: string[];
}

export interface CaseRow {
  /** test name (parameterised: the display name of the case) */
  name: string;
  /** the test's segment when it has changed lines, null for unchanged tests of the file */
  segmentId: string | null;
  line: number | null;
  /** one per column, as written (strings with quotes); null = not checked by this test, «✓» = checked without a value */
  values: (string | null)[];
  /** index of an earlier row with the same inputs (same case tested twice) */
  duplicateOf: number | null;
  /** such a duplicate expects something else */
  contradicts: boolean;
}

export interface LogEntry {
  at: string;
  by: string;
  action: string;
  scope: string;
  count: number;
}

export interface ReviewState {
  schemaVersion: number;
  pr: string;
  headSha: string | null;
  ok: Record<string, string[]>;
  log: LogEntry[];
  /** place -> path -> keys that are ok at that place only */
  okAt: Record<string, Record<string, string[]>>;
  /** what the last reload with new commits did to the marks (absent before the first one) */
  reload?: ReloadInfo | null;
}

/** Marks carried from fromHeadSha to toHeadSha; newLines: path -> keys new or changed since fromHeadSha (and still open). */
export interface ReloadInfo {
  fromHeadSha: string;
  toHeadSha: string;
  at: string;
  kept: number;
  dropped: number;
  newLines: Record<string, string[]>;
}

export interface FileSummary {
  path: string;
  previousPath: string | null;
  status: string;
  additions: number;
  deletions: number;
  language: string;
}

export interface ReviewBundle {
  pr: PullRequestInfo;
  fetchedAt: string;
  dataPath: string;
  files: FileSummary[];
  analysis: Analysis;
  state: ReviewState;
}

export interface Comment {
  id: number;
  kind: 'review' | 'issue';
  path: string | null;
  line: number | null;
  side: 'LEFT' | 'RIGHT' | null;
  key: string | null;
  body: string;
  author: string;
  avatarUrl: string | null;
  createdAt: string;
  url: string;
  inReplyTo: number | null;
  /** the host's id of the thread (GitLab: discussion id); null where the host has none */
  thread?: string | null;
  /** state of the thread, the same on all its comments; null: it cannot be resolved (plain comment, or the host has no such thing) */
  resolved?: boolean | null;
}

/** Where a PR stands on the code host, read live (Model.PrStatus). */
export interface PrStatus {
  state: 'open' | 'merged' | 'closed';
  draft: boolean;
  /** head on the host now - may be newer than the loaded snapshot */
  headSha: string;
  /** logins */
  approvedBy: string[];
  approvedByMe: boolean;
  /** the user may approve, or revoke the approval */
  canApprove: boolean;
  /** required approvals still missing */
  approvalsLeft: number;
  /** the user has the right to merge */
  canMerge: boolean;
  /** the host would merge it now */
  mergeable: boolean;
  /** German text why it cannot be merged now; null when it can */
  blocker: string | null;
  /** what the PR is set to do on merge */
  squash: boolean;
  removeSourceBranch: boolean;
  /** status of the head pipeline as the host names it; null without one */
  pipeline: string | null;
}

export interface MergeOptions {
  squash: boolean;
  removeSourceBranch: boolean;
}

export interface NewComment {
  path?: string | null;
  line?: number | null;
  side?: 'LEFT' | 'RIGHT' | null;
  body: string;
  inReplyTo?: number | null;
  /** both set for context lines (GitLab needs both to anchor a comment) */
  oldLine?: number | null;
  newLine?: number | null;
}

export interface LineMark {
  path: string;
  key: string;
  /** group id the mark applies to; null = all places of the line */
  place: string | null;
}

export interface PrKey {
  provider: string;
  owner: string;
  repo: string;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  me(): Observable<Me> {
    return this.http.get<Me>('/api/me');
  }

  /** Replaces the session cookies of an SSO proxy in front of a provider (kept in memory by the backend); blank removes them. */
  setCookie(provider: string, cookie: string): Observable<Me> {
    return this.http.put<Me>(`/api/accounts/${provider}/cookie`, { cookie });
  }

  settings(): Observable<Settings> {
    return this.http.get<Settings>('/api/settings');
  }

  saveSettings(settings: Settings): Observable<Settings> {
    return this.http.put<Settings>('/api/settings', settings);
  }

  prs(): Observable<PullRequestSummary[]> {
    return this.http.get<PullRequestSummary[]>('/api/prs');
  }

  repos(q: string): Observable<RepositorySummary[]> {
    return this.http.get<RepositorySummary[]>('/api/repos', { params: { q } });
  }

  repoPrs(repo: { provider: string; owner: string; repo: string }): Observable<PullRequestSummary[]> {
    return this.http.get<PullRequestSummary[]>(`/api/repos/${repo.provider}/${repo.owner}/${repo.repo}/prs`);
  }

  searchPrs(q: string): Observable<PullRequestSummary[]> {
    return this.http.get<PullRequestSummary[]>('/api/search', { params: { q } });
  }

  resolve(url: string): Observable<PrKey> {
    return this.http.get<PrKey>('/api/resolve', { params: { url } });
  }

  bundle(pr: PrKey): Observable<ReviewBundle> {
    return this.http.get<ReviewBundle>(base(pr));
  }

  reload(pr: PrKey): Observable<ReviewBundle> {
    return this.http.post<ReviewBundle>(`${base(pr)}/reload`, null);
  }

  reanalyse(pr: PrKey): Observable<ReviewBundle> {
    return this.http.post<ReviewBundle>(`${base(pr)}/reanalyse`, null);
  }

  state(pr: PrKey): Observable<ReviewState> {
    return this.http.get<ReviewState>(`${base(pr)}/state`);
  }

  mark(pr: PrKey, lines: LineMark[], ok: boolean, scope: string): Observable<ReviewState> {
    return this.http.post<ReviewState>(`${base(pr)}/marks`, { lines, ok, scope, by: 'ui' });
  }

  comments(pr: PrKey): Observable<Comment[]> {
    return this.http.get<Comment[]>(`${base(pr)}/comments`);
  }

  commits(pr: PrKey): Observable<Commits> {
    return this.http.get<Commits>(`${base(pr)}/commits`);
  }

  wholeFile(pr: PrKey, path: string): Observable<WholeFile> {
    return this.http.get<WholeFile>(`${base(pr)}/file`, { params: { path } });
  }

  blame(pr: PrKey, path: string): Observable<Blame> {
    return this.http.get<Blame>(`${base(pr)}/blame`, { params: { path } });
  }

  /** Resolves a thread on the code host or opens it again; answers with all comments as they are now. */
  resolveThread(pr: PrKey, thread: string, resolved: boolean): Observable<Comment[]> {
    return this.http.put<Comment[]>(`${base(pr)}/threads/${encodeURIComponent(thread)}/resolved`, { resolved });
  }

  /** Approvals and mergeability; null where approving and merging is not supported (GitHub, a whole repository). */
  status(pr: PrKey): Observable<PrStatus | null> {
    return this.http.get<PrStatus | null>(`${base(pr)}/status`);
  }

  /** Approves the PR on the code host at once, or revokes the approval. */
  approve(pr: PrKey, approved: boolean): Observable<PrStatus> {
    return this.http.put<PrStatus>(`${base(pr)}/approval`, { approved });
  }

  /** Merges the PR on the code host at once. */
  merge(pr: PrKey, options: MergeOptions): Observable<PrStatus> {
    return this.http.post<PrStatus>(`${base(pr)}/merge`, options);
  }

  postComment(pr: PrKey, comment: NewComment): Observable<Comment> {
    return this.http.post<Comment>(`${base(pr)}/comments`, comment);
  }
}

function base(pr: PrKey): string {
  return `/api/prs/${pr.provider}/${pr.owner}/${pr.repo}/${pr.number}`;
}

export function errorMessage(err: unknown): string {
  const e = err as { error?: { message?: string }; message?: string; status?: number };
  if (e?.error?.message) return serverText(e.error.message);
  return e?.status === 0 ? t('Backend nicht erreichbar') : e?.message ?? t('Unbekannter Fehler');
}
