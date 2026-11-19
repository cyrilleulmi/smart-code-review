# Architektur

Überblick für Architekt:innen (Qualitätsziele, Laufzeitsichten, Datenlebenszyklus, Entscheide): [09-architektur-ueberblick.md](09-architektur-ueberblick.md). Dieses Dokument ist die Referenz der Bausteine und der REST-API.

```
 Browser (Angular 22)                    Quarkus 3.40 (JVM-Dev-Mode oder native)                 Code-Hosts
┌──────────────────────┐  /api/*   ┌──────────────────────────────────────────────┐   REST   ┌──────────────┐
│ PR-Liste  Review-Baum│ ────────▶ │ ReviewResource ─▶ ReviewService              │ ───────▶ │ GitHub       │
│ Übersicht Flow-View  │ ◀──────── │                    │  ├─ ReviewProvider ──────┼────────▶ │ (+ GraphQL   │
│ Code-Blöcke Commits  │   JSON    │                    │  │   ├ GitHubProvider    │          │  für Blame)  │
└──────────────────────┘           │                    │  │   └ GitLabProvider ───┼────────▶ │ GitLab       │
                                   │                    │  ├─ Analyzer (ohne KI)   │          └──────────────┘
                                   │                    │  ├─ Attribution          │
                                   │                    │  └─ DataStore ───────────┼──▶ ~/.smart-code-review/
                                   └──────────────────────────────────────────────┘     (JSON + Quelltexte)
```

## Backend (`backend/`, Paket `dev.smartcodereview`)

| Klasse | Aufgabe |
|---|---|
| `provider/ReviewProvider` | Schnittstelle zu einem Code-Host: offene PRs, Snapshot, Dateiinhalt, Commits mit ihren Diffs, Blame, Kommentare lesen/schreiben. Alles darüber ist Provider-neutral. |
| `github/GitHubProvider` | GitHub-REST; Token aus Config/Env oder `gh auth token`. Blame über GraphQL (REST hat keins; GitHub Enterprise: `…/api/graphql`). |
| `gitlab/GitLabProvider` | GitLab-REST v4 (gitlab.com oder self-managed): MRs, `/diffs` (Fallback `/changes`), Rohdateien, Discussions; Inline-Kommentare mit `position` (base/start/head SHA); Commits, Commit-Diffs und `/blame`. |
| `provider/JsonHttp`, `provider/Tokens` | Gemeinsames JSON-über-HTTP und Token-Suche (Config → Env → CLI). |
| `diff/PatchParser` | Zerlegt GitHubs Unified-Diff pro Datei in Hunks und nummerierte Zeilen. |
| `analysis/CodeText` | Mini-Lexer: Kopie des Quelltexts, in der Strings und Kommentare ausgeblendet sind, plus Klammerpaare. |
| `analysis/SymbolExtractor` | Findet Funktionen, Methoden, Klassen, Typen und Top-Level-Konstanten mit Zeilenbereich. |
| `analysis/FileView` | Neue Dateiversion mit eingewobenen gelöschten Zeilen, um ganze Funktionen mit Diff-Farben zu zeigen. |
| `analysis/Structure` | Ports-&-Adapters-Erkennung aus den Ordnern, Schicht einer Datei, Frontend-Erkennung, Komponenten-Schlüssel. |
| `analysis/Http` | REST quer durch den Stack: HTTP-Aufrufe im Client-Code, Endpunkte im Server-Code, Vergleich der Pfade. |
| `analysis/Rules` | Lädt und wertet `rules.json` aus (Pfad-Globs, Zeilenmuster, Symbolarten). |
| `analysis/Analyzer` | Segmente bilden → klassifizieren → Aufrufkanten finden → Flüsse bauen (einer pro Einstiegspunkt) → Kategorien bauen lassen (Module, s. u.) → Orte pro Segment → Tests mit dem erreichten Code verknüpfen → Anzeigezeilen rendern. |
| `analysis/category/*`, `analysis/CategoryModules` | **Kategorien als Module:** jede Kategorie ist ein `CategoryModule` mit gleicher Schnittstelle (`info()` = Selbstbeschreibung, `groups(context)` = ihre Gruppen, `view()`, `origin()`). Ein Modul sieht nur `SegmentFacts`, nie den Analyzer und nie ein anderes Modul. `CategoryModules` erzeugt die Module aus den Kategorien der Regeldatei. Details in [05-analyse.md](05-analyse.md#3e-kategorien-als-module). |
| `commits/Attribution` | Ordnet jede geänderte Zeile des PR-Diffs dem Commit zu, der sie gemacht hat: spielt die Commit-Diffs entlang der First-Parent-Kette nach (siehe [05-analyse.md](05-analyse.md#7-commits)). Ohne Git, ohne KI. |
| `repo/RepositorySnapshot` | Ganzes Repository als «Pull Request», in dem jede Datei neu ist: liest das `.tar.gz` eines Commits (ein Request), baut pro Textdatei einen Hunk aus hinzugefügten Zeilen, dazu Ordner und API-Verträge (`structure.json`). |
| `store/DataStore` | Datenverzeichnis, atomare JSON-Schreibvorgänge, Pfad-Sicherheit. |
| `ReviewService` | Orchestriert alles; Snapshot und Analyse werden gecacht, die Analyse wird bei geänderten Regeln oder neuer Analyzer-Version neu berechnet. Markierungen pro Ort (`okAt`) werden zu «überall ok» (`ok`) zusammengeführt, sobald alle Orte einer Zeile ok sind; die Regeln dafür (auch für «halb akzeptiert») stehen in `review/LineAcceptance`. |
| `api/ReviewResource` | REST-API (unten). |
| `model/Model` | Alle Datentypen als Records. Sie sind zugleich das JSON-Format der Dateien und der API. |

**Native:** Alle Modell-Records tragen `@RegisterForReflection`; GitHub-Antworten werden als `JsonNode` gelesen. `rules/*.json` und `data-dir/*` sind als Native-Ressourcen eingebunden.

## REST-API

Alle PR-Endpunkte unter `/api/prs/{provider}/{owner}/{repo}/{number}`; `provider` ist `github` oder `gitlab`, verschachtelte GitLab-Gruppen stehen als `gruppe~untergruppe` in `owner`. `number` **0** ist das ganze Repository am Stand des Default-Branches (siehe [05-analyse.md](05-analyse.md#8-ganzes-repository)); dort liefern `…/comments` eine leere Liste und Kommentieren ist nicht möglich, `…/commits` nimmt die letzten 50 Commits des Branches.

| Methode | Pfad | Zweck |
|---|---|---|
| GET / PUT | `/api/settings` | Ausgeschaltete Kategorien `{"disabledCategories":["fe-templates"]}` (global, `<data>/settings.json`) |
| GET | `/api/me` | Konten pro Provider (User oder Grund, warum keiner; `cookie`: ob Session-Cookies eines SSO-Proxys gesetzt sind, `null` = der Host nimmt keine), Datenverzeichnis |
| PUT | `/api/accounts/{provider}/cookie` | `{"cookie":"name=wert; name2=wert2"}` ersetzt die Session-Cookies eines SSO-Proxys vor dem Host (nur im Arbeitsspeicher, bis zum nächsten Start; leer = entfernen). Antwort wie `/api/me`. Nur GitLab; sonst 400. |
| GET | `/api/resolve?url=…` | PR-/MR-URL von GitHub oder GitLab → `{provider, owner, repo, number}`; Repository-URL → `number` 0 |
| GET | `/api/prs?filter=all\|authored\|review-requested` | Offene PRs (mit lokalem Fortschritt, falls vorhanden) |
| GET | `/api/local` | PRs mit lokalen Daten (offline) |
| GET | `…/{number}` | **Bundle**: PR-Infos, Dateien, Analyse, Review-Status. Lädt den Snapshot beim ersten Mal von GitHub. |
| POST | `…/{number}/reload` | Snapshot neu von GitHub laden und neu analysieren |
| POST | `…/{number}/reanalyse` | Nur neu analysieren (z. B. nach Änderung an `rules.json`) |
| GET | `…/{number}/state` | Review-Status (`review.json`) |
| POST | `…/{number}/marks` | Zeilen markieren: `{"lines":[{"path":"a.ts","key":"N12","place":"flows/flow-2"}],"ok":true,"scope":"group:quick-wins/übersetzungen","by":"agent:claude"}`; ohne `place` gilt die Markierung an allen Orten der Zeile |
| GET | `…/{number}/commits` | Commits des PRs (älteste zuerst) und welcher Commit welche geänderte Zeile gemacht hat (`commits.json`) |
| GET | `…/{number}/file?path=…` | Eine geänderte Datei als Ganzes am PR-Stand: jede Zeile der neuen Version, gelöschte Zeilen eingewoben, gleiche Zeilenschlüssel wie die Segmente (Code-Ansicht «Ganze Klassen»; aus `sources/`, fehlende Datei wird einmal geholt) |
| GET | `…/{number}/blame?path=…` | Git-Blame einer geänderten Datei am PR-Stand (Cache unter `blame/`) |
| GET | `…/{number}/comments` | Kommentare von GitHub oder GitLab (Inline + allgemein). `thread`: Id des Threads beim Host (GitLab: Discussion), `resolved`: Zustand des Threads, auf allen seinen Kommentaren gleich – `null`, wenn er sich nicht auflösen lässt (einfacher Kommentar, GitHub). |
| GET | `…/{number}/status` | Stand des PRs auf dem Host, live gelesen: `state` (open, merged, closed), `headSha`, `approvedBy`, `approvedByMe`, `canApprove`, `approvalsLeft`, `canMerge`, `mergeable`, `blocker` (Grund auf Deutsch, warum er sich nicht mergen lässt), `squash`, `removeSourceBranch`, `pipeline`. 204 ohne Inhalt, wo Genehmigen und Mergen nicht unterstützt sind (GitHub, ganzes Repository). |
| PUT | `…/{number}/approval` | `{"approved":true}` genehmigt den PR sofort auf dem Host, `false` zieht die Genehmigung zurück. Antwort: neuer Stand. Genehmigt wird nur der geladene Head; hat der Host neuere Commits → 409. Nur GitLab; sonst 400. |
| POST | `…/{number}/merge` | Mergt den PR sofort (nicht umkehrbar). Body `{}` oder `{"squash":…,"removeSourceBranch":…}` (ohne Angabe: wie im PR eingestellt). Antwort: neuer Stand. Nur der geladene Head; neuere Commits oder Ablehnung durch den Host → 409 mit Grund. Nur GitLab; sonst 400. |
| PUT | `…/{number}/threads/{thread}/resolved` | `{"resolved":true}` löst einen Thread sofort auf dem Host auf, `false` öffnet ihn wieder. Antwort: alle Kommentare im neuen Stand. Nur GitLab; sonst 400. |
| POST | `…/{number}/comments` | Kommentar sofort veröffentlichen: inline `{path,line,side,body}` (+ `oldLine`/`newLine` für Kontextzeilen, von GitLab benötigt), Antwort `{inReplyTo,body}`, allgemein `{body}` |

Fehler kommen als `{"message": "…"}` mit passendem HTTP-Status.

## Frontend (`frontend/`)

Angular 22, standalone Komponenten, Signals, zoneless, `OnPush`.

| Datei | Aufgabe |
|---|---|
| `review.store.ts` | Zentraler Zustand (Signals), Hash-Routing (`#/github/o/r/12/c/<sha>/flow/flow-1`), optimistische Markierungen pro Ort («Slots» = Segment an einem Ort), Commit-Filter (filtert Slots, Fortschritt und Markierungen auf die Zeilen eines Commits), Blame-Cache, Neuladen des Status beim Fokus des Fensters (damit Änderungen von Agenten sofort erscheinen). |
| `app.*` | IntelliJ-Shell: Main-Toolbar, Tool-Window-Stripe, verschiebbares Tool-Fenster, Breadcrumbs, Statusleiste, Shortcuts Alt+1/Alt+2. |
| `pr-list.ts` | Tool-Fenster «Pull Requests» mit Filter und Suche. |
| `review-actions.ts` | Auf der Übersicht: Stand des PRs auf dem Host, «Genehmigen» / «Genehmigung zurückziehen» und «Mergen …» mit Rückfrage. |
| `account-cookie.ts` | Dialog aus der Statusleiste: Session-Cookies eines SSO-Proxys vor dem Code-Host zur Laufzeit setzen oder entfernen. |
| `review-tree.*` | Tool-Fenster «Review»: Kategorien / Flows / Dateien als Baum mit Fortschritt und ✓-Aktion, Tab *Commits*. |
| `commit-list.ts` | Commits des PRs mit Fortschritt; Klick filtert alle Ansichten auf einen Commit, ✓ markiert seine Zeilen (Review-Fenster und Übersicht). |
| `overview.*` | PR-Übersicht: Fortschritt, vorgeschlagene Reihenfolge, Beschreibung, allgemeine Kommentare, letzte Markierungen. |
| `group-view.ts` | Liste von Code-Blöcken (Kategorie, Gruppe, Datei) mit Fokus-Leiste (einzeln durchgehen, alle ein-/ausklappen). |
| `flow-view.*` | Aufrufbaum-Diagramm (SVG) + Code-Blöcke in Aufrufreihenfolge, eingerückt nach Tiefe, mit Verbindungslinien von der Aufrufzeile zum aufgerufenen Block. Schritte aus-/einblenden (einzeln oder alle ausserhalb eines Zweigs), Erledigte ausblenden, Schritt für Schritt. Diagramm und Code je auf Methoden- oder Klassenebene, das Diagramm auch mit Methoden im Rahmen ihrer Klasse (Umschalter; der Code folgt dem Diagramm, bis er eine eigene Ebene bekommt). |
| `whole-class.ts` | Code-Ansicht «Ganze Klassen» eines Flows: setzt die Schritte einer Klasse in die Zeilen ihrer ganzen Datei ein (ein Block pro Datei); `code-block` zeigt ihn über `parts`/`owners`/`starts`. |
| `flow-classes.ts` | Klassenebene eines Flows: Schritte nach Klasse (Komponente, sonst Datei) gruppieren, Tiefe der Klassen, Verbindungen zwischen Klassen zusammenfassen, Reihenfolge der Methoden in der Klassenkarte, Läufe aufeinanderfolgender Methoden einer Klasse (Klammern im Methoden-Code), Ebene des Codes zum Diagramm, Baum der Code-Ansicht «Zeile» (aufgerufener Block nach der Aufrufzeile; `code-block` setzt ihn über `inline`/`inlineTpl` nach der Zeile ein). |
| `call-sites.ts` | Aufrufstellen im Code: sucht zu den Verweisen einer Zeile (`refs`) die Stelle des Aufrufs im Zeilentext und setzt die Bubble ins hervorgehobene HTML. Nur Darstellung; welche Zeile was aufruft, kommt aus der Analyse. |
| `flow-layout.ts` | Anordnung des Flow-Diagramms: übergibt Knoten, Rahmen und Verbindungen an ELK (`elkjs`, EPL-2.0, per `import()` als eigener Chunk nachgeladen) und liefert Positionen und Kantenführung; dazu Pfade mit runden Ecken, einfache Kurven für von Hand verschobene Knoten und der Rahmen um seine Knoten. |
| `split-diff.ts` | Seite-an-Seite-Ansicht der Code-Blöcke: welche entfernte Zeile neben welcher neuen steht und ob ein Block überhaupt zwei Seiten hat. |
| `acceptance.ts` | Spiegel von `LineAcceptance.java`: Status einer Zeile pro Ort (offen / halb akzeptiert / akzeptiert) und wo sie sonst schon akzeptiert ist. Die beiden müssen gleich bleiben (je ein Test pro Seite). |
| `accept-boxes.ts` | Wie der Zustand gezeigt und geklickt wird: ein Kästchen pro Ort einer Zeile (das des gezeigten Orts vorne, die anderen akzeptieren überall), «überall akzeptieren» und zurücknehmen, Stufe offen / hier (grün) / überall (blau). Die Regeln selbst bleiben in `acceptance.ts`. |
| `look.ts` | Farben und Bezeichnungen für Schichten (Frontend, API, Domäne …), Sprachen und Verbindungen; die Farbwerte selbst sind Tokens in `styles.scss`. |
| `component-diagram.ts` | Komponentendiagramm der Übersicht: ein Rahmen pro Schicht um ihre Komponenten, angeordnet mit `flow-layout.ts` (Schichten ohne Verbindung in Reihen darunter), Aufruf- und REST-Kanten, Fokus pro Komponente, Tests/isolierte ein-/ausblenden. |
| `test-overview.ts` | Testübersicht: pro Testfall die Komponenten und die abgedeckten Codestellen (direkt / über …), mit Sprüngen. |
| `code-block.*` | Ein Segment an einem Ort: Gutter mit Häkchen (ortsbezogen), alte/neue Zeilennummer, Diff-Farben, Highlighting, Inline-Kommentare, Aufruf-Chips, Leiste mit allen Orten und Test-Verknüpfungen, einklappbar. Optional Blame-Spalte (Datum/Zeit, Autor, Nummer des PR-Commits); in der Commit-Ansicht sind Änderungen anderer Commits abgeblendet. |
| `theme.ts` | Theme des UI (Dark als Standard, Light): setzt `data-theme` auf `<html>`; die Farben selbst sind Tokens in `styles.scss`. `index.html` wendet ein gespeichertes Light Theme vor dem ersten Zeichnen an. |
| `i18n.ts` |
 Sprache des UI (Signal `lang`, Umschalter in der Toolbar): `t()` für UI-Texte mit den englischen Texten in `i18n.en.ts`, `serverText()` für Texte aus dem Backend (`i18n.server.en.ts`). Deutsch ist Quellsprache und Schlüssel. |
| `highlight.ts` |
 highlight.js; neue und alte Version werden je als zusammenhängender Text hervorgehoben (korrekt bei mehrzeiligen Kommentaren/Strings) und dann pro Zeile verteilt. |

Build-Ausgabe geht direkt nach `backend/src/main/resources/META-INF/resources`, Quarkus liefert sie aus.
