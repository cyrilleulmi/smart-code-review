# SmartCodeReview data directory - guide for agents

This directory is the complete local state of SmartCodeReview. Everything is plain, pretty-printed JSON
or text. The app re-reads files on every request, so your edits show up in the UI immediately.

## Layout

```
AGENTS.md              this file (rewritten on every app start)
rules.default.json     built-in classification rules (rewritten on start; reference only)
rules.json             OPTIONAL custom rules; takes precedence; edits trigger re-analysis
settings.json          switched-off categories {"disabledCategories": [...]}: hidden, their places need no ok
reviews/<provider>/<owner>/<repo>/<number>/      provider: github | gitlab; nested GitLab groups: group~sub;
                                                   number 0 = the whole repository (every file counts as new)
  snapshot.json        PR metadata + parsed diff (frozen when the PR was first opened)
  sources/<path>       new version of each analysed file
  sources-base/<path>  old version (at the PR's base commit) of each changed test file, under its OLD path
                       (renamed files); only for the Spec-Diff (`specs[]`); none for number 0
  sources-related/<path>  existing, unchanged test files (at the PR head) that most likely test the changed code,
                       selected by name; only for `existingTests[]`; none for number 0
  related.json         which existing test files were selected and why (same as `analysis.json` → `relatedTests`)
  structure.json       cache: repository directories, file paths (`files`) and API contract `operations` at the head
  analysis.json        derived: segments, categories, flows (safe to delete; recomputed)
  review.json          THE ONLY REAL STATE: which changed lines are marked "ok" (per place)
  comments.json        cache of the last comment fetch (GitHub or GitLab)
  commits.json         derived: the PR's commits (oldest first) and `lines` path -> line key -> commit sha
                       (added line: commit that last wrote it; deleted line: commit that removed it), and
                       `findings[]` (Commit-Bezug der Tests, see below)
  blame/<path>.json    cache: git blame of a file at the PR head (`ranges` of new-file lines -> sha)
```

## Line keys

`N<n>` = line n of the NEW file (added line), `O<n>` = line n of the OLD file (deleted line).
Only changed lines can be marked. Valid keys per segment: `analysis.json` → `segments[].lineKeys`.

## Reading a review

1. `analysis.json` → `categories[]` (Quick wins, Datenflüsse, Einzelne Änderungen, Tests, Styles & Assets)
   → `groups[].segmentIds`.
2. `segments[]` → `path`, `symbol`, `category`, `group`, `reason`, `lineKeys`, `lines` (display lines incl.
   context; `refs` on call sites point to called segments).
3. `flows[]` → `steps[]` in call order (`depth`, `parentSegmentId`) and `edges[]` (`from` → `to`, call-site `line`).
4. `segments[].places` → every group id that shows the segment (e.g. `quick-wins/mapper`, `flows/flow-2`).
   A segment at several places needs one ok per place.
5. `tests[]` → per changed test case: `subjects` (components under test) and `covers` (reached segments,
   `direct` or `via` another segment, `line` = call site in the test, `mocked` = the segment belongs to a collaborator
   the test mocks: only the mock runs, the walk stops there; `http` = the way starts with a real HTTP request of the
   test (HTTP-Tests: RestAssured, MockMvc, supertest, Playwright `request.put`, `cy.request` … to a changed endpoint,
   also through same-file helpers and base paths like `@TestHTTPEndpoint`; never with a mocked HTTP backend), then
   the endpoint is `direct` with `endpoint` = its request, e.g. PUT /api/players/{}, and what it calls is indirect).
   `subjects[].how` = name | call | http. `segments[].testedBy` is the reverse (without mocked).
   `smells[]` (Test-Smells, pattern-based, rules section `testSmells`; absent in older analyses, empty for none) = signs
   that the test is worth less than it looks: `kind` (no-assertion | weak-only = only existence/truthiness checks |
   focused = fit/it.only, CI would skip all other tests | sleep = fixed wait, not with fake timers | mocks-subject =
   the test mocks or spies the unit it tests | disabled = a NEW test that is switched off), `label` + `hint` (German),
   `line` + `code` (where it shows, null for the whole test). A smell is worth a review comment: the test proves less
   than its name says.
   `profile` (Test-Steckbrief, pattern-based, rules section `testProfile`; null in older analyses): `targets`
   (names of the changed code it calls directly or reaches directly over HTTP, else the unit from the file name), `scenario` (error = expects a
   failure | edge = null/empty/limit values in the inputs of the code under test: arguments of calls to the targets and of HTTP requests, parameter sources - not fixtures or expected values | normal) with `scenarioBy` (code | name) and `scenarioReason` (the
   matched text), `assertions` (count in the test body) and `assertionKinds` ({equality, exception, interaction,
   existence, http, snapshot, other: count}), `mocks` (replaced collaborators in the test, its setup and enclosing
   classes/suites; "HTTP" = mocked backend), `level` (unit | integration | e2e) with `levelBy` (path | code) and
   `levelReason`, `parameterized` / `cases` (rows of @ParameterizedTest sources, it.each tables, Go table tests;
   null = unknown) / `casesSource`. Read error and edge tests first; tests without assertions check nothing.
   `coverage[]` is the view from the code (static; changed tests plus the existing tests loaded for the change, see
   `existingTests[]` below): one entry per testable
   changed function (function/method/component outside tests whose category has `needsTests`, default true;
   false for quick wins, tests, styles, config, API contracts; trivial getters/setters whose body is only
   `return x;` / `this.x = x;` are always left out). `status`: `direct` (a changed test
   calls it), `indirect` (only reached via other changed code), `existing` (no changed test reaches it, an existing
   unchanged test does), `none` (no test reaches it); `directTests` / `indirectTests` = test segment ids;
   `existingTests` = ids of `existingTests[]` reaching it (also for direct/indirect: old tests running through the
   change that may need adapting); reaches of a mock never count, `mockedTests` = tests that only reach a mock of it
   (a function only reached mocked is `none`); `branches` = decision points in the whole function
   (if, case, catch, for, while, &&, ||, ternary ?); `changedLines` = non-blank, non-brace changed lines;
   `risk` = changedLines × (1 + branches). Sorted: `none`, `indirect`, `existing`, `direct`, each by risk
   descending. Missing (null) in analyses of older versions. `findings[]` (Fall- und Effekt-Abgleich, null in
   older analyses) = what the code can do against what the tests that really reach it check (changed + existing,
   direct or indirect, not mocked; rules section `caseCheck`): `kind` exception (`throw new X(`, `throwError(` …;
   checked when a reaching test expects the type, any error, or the mapped HTTP status, e.g. NotFoundException →
   404) | boundary (comparison with an integer or a same-file constant; `covered` = integer inputs of the reaching
   tests, `missing` = boundary values k-1/k resp. k/k+1 no test feeds in) | effect (changed line sets a field,
   persists, sends a message or calls HTTP; checked when an assertion names the field, verifies the call or reads
   stored data again) | branches (information only: more decision points than reaching tests, never checked);
   `line`, `code`, `label` (German), `subject` (type, compared value, field or call), `checked`, `checkedBy` (test
   ids). An unchecked exception/boundary/effect is a case no test proves: worth a review comment.
   `existingTests[]` (Bestehende Tests; null in older analyses, empty for number 0) = test cases of existing,
   unchanged test files that reach changed code, linked like changed tests (name reference + import evidence,
   helpers transparent, HTTP requests to changed endpoints): `id` (e1, …), `path`, `name`, `line` (in the file at the PR head), `reason`,
   `covers[]`, `profile`. They are NOT segments: nothing to mark, not in the diff. `relatedTests` = the selection:
   `limit`, `candidates`, `truncated` (the limit cut it), `listIncomplete` (the host cut the file list), `files[]`
   with `path`, `reason` (mirror = named after the changed file, in the mirrored test directory or next to it |
   name = same name elsewhere, nearest | prefix = name starts with the changed file's name), `sources` (the changed
   files it was selected for), `loaded`. Rules section `relatedTests` (`limit`, `mirrors`).
   Mock-Grenzen (null in older analyses): `mockWarnings[]` = tests that mock changed code - a mocked collaborator
   has a changed function and the test stubs or names that member (rules `testProfile.mockMembers`): `test` (segment
   id or `e1`), `testName`, `path`, `line` (of the stub), `segmentId` + `member` (the mocked changed function),
   `collaborator`. Such a test pins the OLD behaviour of that function: check it first. `flowCoverage[]` = per flow
   (`flowId`, same order as `flows`) `steps[]` (`segmentId`, `status` tested | mocked | none, `tests`, `mockedBy`)
   and `edges[]` (`from`, `to`, `status` tested = one test crosses the call | separate = both ends tested, no
   single test crosses it, e.g. the REST hop | partial = one end tested | none, `tests` = crossing tests; for a REST
   edge both sides of the contract: `clientTests` = tests that really reach the calling end and expect a request
   fitting the endpoint - HttpTestingController.expectOne, cy.intercept, page.route, nock, msw, WireMock - and
   `serverTests` = tests that send an HTTP request to the endpoint; both non-empty = checked from both sides, even
   though no single test crosses it; empty for call edges and in older analyses).
   `caseMatrices[].boundaries[]` (null in older analyses) = boundaries in the code the matrix's tests reach:
   `segmentId`, `subject`, `label` (e.g. 1…99), `values` (all boundary values), `missing` (not an input of this matrix).
   `ruleChecks[]` (Erwartungs-Regeln; null in older analyses, empty for number 0) = rules "if X changes, expect a
   test of form Y" (rules section `testRules`) that apply to a changed segment: `ruleId`, `label` (German),
   `segmentId`, `line` + `code` (the trigger line), `trigger` (matched text or endpoint, e.g. DELETE
   /api/players/{}/jersey-number), `met`, `metBy` (test ids), `tests` (the tests considered: reaching it for real,
   also over HTTP - not with a mocked HTTP backend), `hint` (German text for the
   missing test). Built in: auth-denied (changed authorisation needs a 401/403 test), endpoint-http (new endpoint
   needs a test over HTTP), validation-boundary (changed validation needs an error/edge test),
   transaction-integration (changed @Transactional needs an integration test), listener-test (changed message
   listener needs a test that calls it). An unmet check is a missing test: worth a review comment.
   `setupImpacts[]` (Wirkung von Setup und Testdaten; null in older analyses, empty for number 0) = changed setup
   blocks, fixtures and test helpers with the tests they affect, also tests NOT in the diff: `segmentId`, `kind`
   (setup = beforeEach & co. or a JUnit @BeforeEach/@AfterAll/... method, affects every test of its suite/class incl.
   nested ones and subclasses | fixture = top-level constant, or field/suite variable with a value | helper =
   function, method or class of test code that is no test), `name`, `line`, `affected[]` (`path`, `name`, `line`,
   `segmentId` = null when the test has no changed lines, `status` added | changed | renamed | unchanged, `existing`
   = in a loaded existing test file), `unchangedCount`. Found statically by name (directly, in the setup that runs
   for the test, or through other helpers/constants/fields; other files need import evidence), rules section
   `setupImpact.supportPaths` for test-support files. An unchanged affected test silently changed its meaning: check
   that it still asserts what its name says.
   `specs[]` (Spec-Diff) = per changed test file (`path`, `previousPath`, file `status`) the test names as an
   outline, old against new version: `entries[]` with `name`, `suites` (enclosing suites, outermost first; JUnit:
   test class and @Nested classes), `kind` (suite | test), `status` (added | removed | changed | renamed |
   unchanged), `previousName` (renamed), `segmentId` (null for removed and untouched entries), `line` (new file),
   `oldLine` (base version), `depth` (indentation). Matched by full path, renames = same text apart from
   whitespace and the name. `baseAvailable: false` = the old version could not be loaded, only the new side is
   listed. Entries in new file order, removed ones after their previous old sibling. Null in older analyses.
   Changed expectations (pattern-based, rules `assertions` / `weakAssertions` / `disabledTests`): `expectations[]`
   per matched entry with `kind` (changed = same subject, other expected value/matcher | weakened = to a matcher
   that only checks existence/truthiness, or removed while only such remain | removed | added | disabled |
   enabled = the test or an enclosing suite was switched off/on), `oldText` / `newText` (the assertion statement,
   or the switching line), `oldLine` / `newLine` (null where that side does not exist); empty for added/removed
   entries and without base. `assertionsBefore` / `assertionsAfter` = assertion statements in the test body
   (null for suites and missing sides); `disabled` = switched off now (xit, it.skip, @Disabled, t.Skip, ...).
   A test that now expects something else or less is how tests get bent to fit broken code: check these first.
   `caseMatrices[]` (Fallmatrix, pattern-based, rules section `caseMatrix`; null in older analyses, empty for the
   whole repository) = near-identical tests of a changed test file (`kind: similar`: same suite, same input code
   once literals are masked, assertions may differ) and parameterised tests with ≥ 2 literal rows (`kind:
   parameterized`: @ValueSource/@CsvSource/…, it.each, Go table tests) as a table input → expected. `id` (m1, …),
   `path`, `title` (suite › shared call or test name), `template` (code of the first row, slot ‹n› = column n),
   `columns[]` with `label`, `role` (input | expected), `values` (input columns: distinct covered values, sorted),
   `rows[]` with `name`, `segmentId` (null = unchanged test), `line`, `values` (one per column, as written; null =
   not checked), `duplicateOf` (index of an earlier row with the same inputs = the same case tested twice),
   `contradicts` (that duplicate expects something else). Duplicates and contradictions are worth a comment.
6. `architecture.style` = "hexagonal" when the repo uses ports & adapters, "layered" for domain / application /
   adapter packages without ports; then `segments[].layer` is domain | application | usecase | adapter.
   `segments[].frontend` marks web frontend code. `flows[].edges[].kind` = "http" for REST calls from the
   frontend into a backend endpoint (literal URLs, or generated OpenAPI clients matched by operationId; `via`
   then ends with `(operationId)`). Flow titles start with the trigger when known (`JMS-Listener: …`, `PUT /x: …`).
   Cross-cutting categories: `api-contract`, `security`, `messaging`, `transactions`, `persistence`, `runtime`.
   `reviews/…/structure.json` holds the repository directories, file paths and the API contract `operations`.
7. `components[]` / `componentEdges[]`: changed files grouped into components and the calls between them.

## Marking lines as ok

Preferred while the app runs (no write conflicts):

```
POST http://localhost:8080/api/prs/<provider>/<owner>/<repo>/<number>/marks
{"lines":[{"path":"src/a.ts","key":"N12"}],"ok":true,"scope":"group:quick-wins/übersetzungen","by":"agent:<your-name>"}
```

Without `place` a mark applies at all places of the line. To mark one place only, add `"place": "flows/flow-2"`.

Or edit `review.json` directly:

```json
{ "schemaVersion": 1, "pr": "github/owner/repo/12", "headSha": "…",
  "ok": { "src/a.ts": ["N12", "N13"] },
  "okAt": { "flows/flow-2": { "src/b.ts": ["N40"] } },
  "log": [ { "at": "<ISO-8601>", "by": "agent:<your-name>", "action": "ok", "scope": "<what you marked>", "count": 2 } ] }
```

Rules for direct edits: read first, write the whole file atomically (temp file + rename), keep keys sorted,
only use keys from `lineKeys`, always append a `log` entry with `"by": "agent:<name>"`.
`ok` = ok at all places of the line; `okAt[place]` = ok at that place only. When a line is ok at all its places,
move it to `ok` and remove it from `okAt`.

Line keys are only valid for the snapshot in `headSha`. `POST …/reload` with new commits carries the marks to the
new keys (unchanged lines keep them, changed lines are open again) and writes `reload` into review.json:
`fromHeadSha`, `toHeadSha`, `kept`, `dropped`, and `newLines` (path -> keys new or changed since the last review).
Leave `reload` as it is when you edit the file; to review only what is new, mark the keys in `newLines`.

## Other endpoints

* `GET  /api/prs` – open PRs/MRs (authored by the user or review requested) of all configured hosts
* `GET  /api/resolve?url=<PR or MR web URL>` – turns a GitHub/GitLab URL into provider/owner/repo/number
* `GET  /api/prs/<provider>/<owner>/<repo>/<number>` – bundle (also creates the files above)
* `POST …/reanalyse` – recompute analysis.json (e.g. after editing rules.json)
* `GET  …/commits` – commits.json (computed on first call); to review commit by commit, mark the line keys
  that `lines` assigns to one sha. `findings[]` (Commit-Bezug der Tests; recomputed at every call from the stored
  attribution and the current analysis; null in older files, empty for number 0) = what the ORDER of the commits
  says about the tests: `kind` expectation-later (an assertion of a test written in this PR was changed by a later
  commit than the test's declaration line - `level` warn when that commit changes only test files or its title
  adjusts tests like "fix tests", else info) | fix-without-test (warn: commit title is a fix, it changes source code
  and no test file) | logic-without-test (info: commit changes testable code = `coverage[]` and no test file, and no
  later commit changes a test reaching it); `sha`, `title` (the commit), `path`, `lineKey` (where it shows; null if
  none of its lines is left), `segmentId`, `testSegmentId` (expectation-later), `detail` (German). Merge commits,
  side-branch commits (`mergedVia`) and commits without a known diff are skipped. Patterns: rules section
  `commitChecks` (`fixTitles`, `testAdjustTitles`, `notCode`). A warn finding is worth a look: was the test bent to
  the code, does the fix need a test?
* `GET  …/blame?path=<changed file>` – git blame at the PR head
* `GET  …/status` – where the PR stands on the host (approvals, `mergeable`, `blocker`); 204 where not supported
* `PUT  …/approval` with `{"approved":true}` – approves the PR IMMEDIATELY (`false` revokes); GitLab only
* `POST …/merge` with `{}` – merges the PR IMMEDIATELY and irreversibly; GitLab only. Never approve or merge unless
  the user asked for exactly that
* `PUT  …/threads/{thread}/resolved` with `{"resolved":true}` – resolves a GitLab thread IMMEDIATELY (`false` opens it
  again); `thread` and `resolved` are fields of each comment (`resolved` null: the thread cannot be resolved)
* `GET  …/comments`, `POST …/comments` – comments; POST publishes IMMEDIATELY on GitHub/GitLab:
  inline `{"path":"src/a.ts","line":12,"side":"RIGHT","body":"…"}`, reply `{"inReplyTo":123,"body":"…"}`,
  general `{"body":"…"}`. For unchanged context lines also send `oldLine` and `newLine` (GitLab needs both).
  Only comment when the user asked you to.
