# Working on SmartCodeReview

PoC of a review tool: Quarkus backend (`backend/`) + Angular UI (`frontend/`). Start with `README.md`, the
design decisions are in `docs/` (German).

## Rules

- **Language:** UI texts and docs are German (Swiss spelling: «ss», never «ß»). Code, comments, commit messages: English.
- **No AI in the analysis.** Categorisation and flows are pattern-based and deterministic (`backend/.../analysis/`).
  New heuristics go into `default-rules.json` when they can be expressed as rules.
- **Bump `Analyzer.VERSION`** whenever analysis output changes, so cached `analysis.json` files are recomputed.
- **Categories are modules.** Each category is a `CategoryModule` (`analysis/category/`) that sees only `SegmentFacts`,
  never the analyzer or another module, and describes itself (`builds`/`divides`, shown in the UI). New heuristics that
  fit a rule go into `default-rules.json`; special UIs (like flows) are declared via `view()`, never by category id in the UI.
  Accept/half-accept rules for lines at several places live only in `review/LineAcceptance` (+ its mirror `acceptance.ts`).
- **`Model.java` is the file format.** Field names are a contract with agents (see `docs/04-persistenz-und-agenten.md`
  and `backend/src/main/resources/data-dir/AGENTS.md`); extend, don't rename.
- **Native-friendly backend:** records with `@RegisterForReflection`, Jackson trees for external JSON, new classpath
  resources must be added to `quarkus.native.resources.includes`.
- **UI look:** IntelliJ New UI dark. Colours only as tokens in `frontend/src/styles.scss` (incl. the palettes `--edge-n` for
  connections, `--area-*` for layers, `--lang-*` for languages; helpers in `frontend/src/app/look.ts`), never inline hex values.
- **Provider-neutral:** only `github/` and `gitlab/` know their host; everything else talks to `ReviewProvider`.
  GitLab merge requests are "pull requests" internally; nested groups are encoded as `group~sub` in `PrRef.owner`.

## Commands

```bash
cd frontend && npm run build          # writes into backend/src/main/resources/META-INF/resources
cd backend && ./mvnw quarkus:dev      # http://localhost:8080
cd backend && ./mvnw test             # unit tests (parser, symbols, analysis)
docker compose up --build             # native image (culmi/smart-code-review)
./release.sh X.Y.Z                    # release: version, image, tag, push (Git Bash; see docs/08-versionierung.md)
```

Add user-visible changes to `CHANGELOG.md` under `## [Unreleased]` (German, like the docs).
