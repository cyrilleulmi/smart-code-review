# Working on SmartCodeReview

PoC of a review tool: Quarkus backend (`backend/`) + Angular UI (`frontend/`). Start with `README.md`, the
design decisions are in `docs/` (German).

## Rules

- **Language:** UI texts and docs are German (Swiss spelling: «ss», never «ß»). Code, comments, commit messages: English.
- **No AI in the analysis.** Categorisation and flows are pattern-based and deterministic (`backend/.../analysis/`).
  New heuristics go into `default-rules.json` when they can be expressed as rules.
- **Bump `Analyzer.VERSION`** whenever analysis output changes, so cached `analysis.json` files are recomputed.
- **`Model.java` is the file format.** Field names are a contract with agents (see `docs/04-persistenz-und-agenten.md`
  and `backend/src/main/resources/data-dir/AGENTS.md`); extend, don't rename.
- **Native-friendly backend:** records with `@RegisterForReflection`, Jackson trees for external JSON, new classpath
  resources must be added to `quarkus.native.resources.includes`.
- **UI look:** IntelliJ New UI dark; use the CSS variables in `frontend/src/styles.scss`, no new colours.
- **Provider-neutral:** only `github/` knows about GitHub; everything else talks to `ReviewProvider`.

## Commands

```bash
cd frontend && npm run build          # writes into backend/src/main/resources/META-INF/resources
cd backend && ./mvnw quarkus:dev      # http://localhost:8080
cd backend && ./mvnw test             # unit tests (parser, symbols, analysis)
docker compose up --build             # native image
```
