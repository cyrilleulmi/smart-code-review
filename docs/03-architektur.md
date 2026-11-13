# Architektur

```
 Browser (Angular 22)                    Quarkus 3.40 (JVM-Dev-Mode oder native)                 GitHub
┌──────────────────────┐  /api/*   ┌──────────────────────────────────────────────┐   REST   ┌──────────┐
│ PR-Liste  Review-Baum│ ────────▶ │ ReviewResource ─▶ ReviewService              │ ───────▶ │ api.     │
│ Übersicht Flow-View  │ ◀──────── │                    │  ├─ ReviewProvider ──────┼────────▶ │ github.  │
│ Code-Blöcke          │   JSON    │                    │  │   └ GitHubProvider    │          │ com      │
└──────────────────────┘           │                    │  ├─ Analyzer (ohne KI)   │          └──────────┘
                                   │                    │  └─ DataStore ───────────┼──▶ ~/.smart-code-review/
                                   └──────────────────────────────────────────────┘     (JSON + Quelltexte)
```

## Backend (`backend/`, Paket `dev.smartcodereview`)

| Klasse | Aufgabe |
|---|---|
| `provider/ReviewProvider` | Schnittstelle zu einem Code-Host: offene PRs, Snapshot, Dateiinhalt, Kommentare lesen/schreiben. Alles darüber ist Provider-neutral. |
| `github/GitHubProvider` | GitHub-REST; Token aus Config/Env oder `gh auth token`. |
| `gitlab/GitLabProvider` | GitLab-REST v4 (gitlab.com oder self-managed): MRs, `/diffs` (Fallback `/changes`), Rohdateien, Discussions; Inline-Kommentare mit `position` (base/start/head SHA). |
| `provider/JsonHttp`, `provider/Tokens` | Gemeinsames JSON-über-HTTP und Token-Suche (Config → Env → CLI). |
| `diff/PatchParser` | Zerlegt GitHubs Unified-Diff pro Datei in Hunks und nummerierte Zeilen. |
| `analysis/CodeText` | Mini-Lexer: Kopie des Quelltexts, in der Strings und Kommentare ausgeblendet sind, plus Klammerpaare. |
| `analysis/SymbolExtractor` | Findet Funktionen, Methoden, Klassen, Typen und Top-Level-Konstanten mit Zeilenbereich. |
| `analysis/FileView` | Neue Dateiversion mit eingewobenen gelöschten Zeilen, um ganze Funktionen mit Diff-Farben zu zeigen. |
| `analysis/Rules` | Lädt und wertet `rules.json` aus (Pfad-Globs, Zeilenmuster, Symbolarten). |
| `analysis/Analyzer` | Segmente bilden → klassifizieren → Aufrufkanten finden → Flüsse bauen → Anzeigezeilen rendern. |
| `store/DataStore` | Datenverzeichnis, atomare JSON-Schreibvorgänge, Pfad-Sicherheit. |
| `ReviewService` | Orchestriert alles; Snapshot und Analyse werden gecacht, die Analyse wird bei geänderten Regeln oder neuer Analyzer-Version neu berechnet. |
| `api/ReviewResource` | REST-API (unten). |
| `model/Model` | Alle Datentypen als Records. Sie sind zugleich das JSON-Format der Dateien und der API. |

**Native:** Alle Modell-Records tragen `@RegisterForReflection`; GitHub-Antworten werden als `JsonNode` gelesen. `rules/*.json` und `data-dir/*` sind als Native-Ressourcen eingebunden.

## REST-API

Alle PR-Endpunkte unter `/api/prs/{provider}/{owner}/{repo}/{number}`; `provider` ist `github` oder `gitlab`, verschachtelte GitLab-Gruppen stehen als `gruppe~untergruppe` in `owner`.

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/me` | Konten pro Provider (User oder Grund, warum keiner), Datenverzeichnis |
| GET | `/api/resolve?url=…` | PR-/MR-URL von GitHub oder GitLab → `{provider, owner, repo, number}` |
| GET | `/api/prs?filter=all\|authored\|review-requested` | Offene PRs (mit lokalem Fortschritt, falls vorhanden) |
| GET | `/api/local` | PRs mit lokalen Daten (offline) |
| GET | `…/{number}` | **Bundle**: PR-Infos, Dateien, Analyse, Review-Status. Lädt den Snapshot beim ersten Mal von GitHub. |
| POST | `…/{number}/reload` | Snapshot neu von GitHub laden und neu analysieren |
| POST | `…/{number}/reanalyse` | Nur neu analysieren (z. B. nach Änderung an `rules.json`) |
| GET | `…/{number}/state` | Review-Status (`review.json`) |
| POST | `…/{number}/marks` | Zeilen markieren: `{"lines":[{"path":"a.ts","key":"N12"}],"ok":true,"scope":"group:quick-wins/übersetzungen","by":"agent:claude"}` |
| GET | `…/{number}/comments` | Kommentare von GitHub (Inline + allgemein) |
| POST | `…/{number}/comments` | Kommentar sofort veröffentlichen: inline `{path,line,side,body}` (+ `oldLine`/`newLine` für Kontextzeilen, von GitLab benötigt), Antwort `{inReplyTo,body}`, allgemein `{body}` |

Fehler kommen als `{"message": "…"}` mit passendem HTTP-Status.

## Frontend (`frontend/`)

Angular 22, standalone Komponenten, Signals, zoneless, `OnPush`.

| Datei | Aufgabe |
|---|---|
| `review.store.ts` | Zentraler Zustand (Signals), Hash-Routing, optimistische Markierungen, Neuladen des Status beim Fokus des Fensters (damit Änderungen von Agenten sofort erscheinen). |
| `app.*` | IntelliJ-Shell: Main-Toolbar, Tool-Window-Stripe, verschiebbares Tool-Fenster, Breadcrumbs, Statusleiste, Shortcuts Alt+1/Alt+2. |
| `pr-list.ts` | Tool-Fenster «Pull Requests» mit Filter und Suche. |
| `review-tree.*` | Tool-Fenster «Review»: Kategorien / Flows / Dateien als Baum mit Fortschritt und ✓-Aktion. |
| `overview.*` | PR-Übersicht: Fortschritt, vorgeschlagene Reihenfolge, Beschreibung, allgemeine Kommentare, letzte Markierungen. |
| `group-view.ts` | Liste von Code-Blöcken (Kategorie, Gruppe, Datei). |
| `flow-view.*` | Aufrufbaum-Diagramm (SVG) + Code-Blöcke in Aufrufreihenfolge, eingerückt nach Tiefe, mit Verbindungslinien von der Aufrufzeile zum aufgerufenen Block. |
| `code-block.*` | Ein Segment: Gutter mit Häkchen, alte/neue Zeilennummer, Diff-Farben, Highlighting, Inline-Kommentare, Aufruf-Chips. |
| `highlight.ts` | highlight.js; neue und alte Version werden je als zusammenhängender Text hervorgehoben (korrekt bei mehrzeiligen Kommentaren/Strings) und dann pro Zeile verteilt. |

Build-Ausgabe geht direkt nach `backend/src/main/resources/META-INF/resources`, Quarkus liefert sie aus.
