# SmartCodeReview data directory - guide for agents

This directory is the complete local state of SmartCodeReview. Everything is plain, pretty-printed JSON
or text. The app re-reads files on every request, so your edits show up in the UI immediately.

## Layout

```
AGENTS.md              this file (rewritten on every app start)
rules.default.json     built-in classification rules (rewritten on start; reference only)
rules.json             OPTIONAL custom rules; takes precedence; edits trigger re-analysis
reviews/<provider>/<owner>/<repo>/<number>/
  snapshot.json        PR metadata + parsed diff (frozen when the PR was first opened)
  sources/<path>       new version of each analysed file
  analysis.json        derived: segments, categories, flows (safe to delete; recomputed)
  review.json          THE ONLY REAL STATE: which changed lines are marked "ok"
  comments.json        cache of the last GitHub comment fetch
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

## Marking lines as ok

Preferred while the app runs (no write conflicts):

```
POST http://localhost:8080/api/prs/<provider>/<owner>/<repo>/<number>/marks
{"lines":[{"path":"src/a.ts","key":"N12"}],"ok":true,"scope":"group:quick-wins/übersetzungen","by":"agent:<your-name>"}
```

Or edit `review.json` directly:

```json
{ "schemaVersion": 1, "pr": "github/owner/repo/12", "headSha": "…",
  "ok": { "src/a.ts": ["N12", "N13"] },
  "log": [ { "at": "<ISO-8601>", "by": "agent:<your-name>", "action": "ok", "scope": "<what you marked>", "count": 2 } ] }
```

Rules for direct edits: read first, write the whole file atomically (temp file + rename), keep keys sorted,
only use keys from `lineKeys`, always append a `log` entry with `"by": "agent:<name>"`.

## Other endpoints

* `GET  /api/prs` – open PRs (authored by the user or review requested)
* `GET  /api/prs/<provider>/<owner>/<repo>/<number>` – bundle (also creates the files above)
* `POST …/reanalyse` – recompute analysis.json (e.g. after editing rules.json)
* `GET  …/comments`, `POST …/comments` – GitHub comments; POST publishes IMMEDIATELY on GitHub:
  inline `{"path":"src/a.ts","line":12,"side":"RIGHT","body":"…"}`, reply `{"inReplyTo":123,"body":"…"}`,
  general `{"body":"…"}`. Only comment when the user asked you to.
