# SmartCodeReview (PoC)

Code-Reviews nach **Bedeutung statt nach Dateien**. SmartCodeReview holt deine offenen Pull Requests von GitHub, zerlegt jede Änderung in sinnvolle Einheiten (Funktionen, Typen, Hunks) und sortiert sie:

1. **Quick wins** – höchstwahrscheinlich irrelevante Änderungen (generierter Code, Übersetzungen, DTOs, Mapper, banale REST-Aufrufe, Imports …) zum schnellen Abhaken. Erkannt per Muster, **ohne KI**.
2. **Datenflüsse** – geänderte Funktionen, die sich gegenseitig aufrufen, in Aufrufreihenfolge: Diagramm oben, Code darunter, die Aufrufstellen sind mit dem aufgerufenen Code verbunden.
3. **Einzelne Änderungen**, **Tests**, **Styles & Assets**.

Jede geänderte Zeile kann als **ok** markiert werden, einzeln, als Block, als Gruppe, als Flow oder als ganze Kategorie. Die Markierungen leben **nur lokal** in JSON-Dateien, die auch Agenten lesen und schreiben können. Kommentare gehen **sofort** als Inline-Kommentare an GitHub.

![Fluss-Ansicht](docs/img/flow.png)

## Schnellstart (lokal)

Voraussetzungen: JDK 21+ (getestet mit 25), Node 22+ und ein GitHub-Login (`gh auth login`) oder `GITHUB_TOKEN`.

```powershell
# Windows
./start.ps1
```

```bash
# macOS / Linux
./start.sh
```

Das Skript baut beim ersten Mal das Angular-UI und startet dann Quarkus im Dev-Mode. Danach im Browser **http://localhost:8080** öffnen.

Der GitHub-Token wird in dieser Reihenfolge gesucht: `GITHUB_TOKEN`, dann `GH_TOKEN`, dann `gh auth token`.

### Als Native-Binary in Docker

```powershell
$env:GITHUB_TOKEN = gh auth token
docker compose up --build
```

Der Build kompiliert Backend und UI im Container zu einem nativen Executable (Mandrel). Ein lokales GraalVM braucht es nicht. Die Daten liegen dann in `./data`.

### UI-Entwicklung mit Hot Reload

```bash
cd backend && ./mvnw quarkus:dev      # API auf :8080
cd frontend && npm start              # UI auf :4200, /api wird an :8080 weitergeleitet
```

## Bedienung

| Was | Wie |
|---|---|
| PR öffnen | Tool-Fenster «Pull Requests» (Alt+1), Filter *Alle / Von mir / Review* |
| Navigieren | Tool-Fenster «Review» (Alt+2): Tabs *Kategorien / Flows / Dateien* |
| Zeile ok | Häkchen im Gutter; **Shift-Klick** markiert einen Bereich |
| Block / Gruppe / Flow ok | Button «Block ok», ✓-Button im Baum (beim Überfahren), «Ganzen Flow als ok markieren» |
| Kommentar | Sprechblase im Gutter (nur auf Zeilen im Diff), Ctrl+Enter sendet sofort an GitHub |
| Aufruf folgen | Blaue Chips `→ ② saveEvent` an der Aufrufstelle, «← aufgerufen von …» im Block-Kopf |
| Erledigtes ausblenden | Toolbar-Knopf (Pfeile): vollständig markierte Blöcke einklappen |

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [docs/01-konzept.md](docs/01-konzept.md) | Anforderungen und Entscheide aus dem Kick-off |
| [docs/02-annahmen.md](docs/02-annahmen.md) | Annahmen und bewusste Vereinfachungen des PoC |
| [docs/03-architektur.md](docs/03-architektur.md) | Aufbau, REST-API, Technologie |
| [docs/04-persistenz-und-agenten.md](docs/04-persistenz-und-agenten.md) | Dateiformat im Datenverzeichnis, Zusammenarbeit mit Agenten |
| [docs/05-analyse.md](docs/05-analyse.md) | Wie Quick wins und Datenflüsse ohne KI erkannt werden; `rules.json` |
| [docs/06-test-prs.md](docs/06-test-prs.md) | Die Test-PRs in den anderen Repos und was dabei herauskommen sollte |
| [docs/07-roadmap.md](docs/07-roadmap.md) | Nächste Schritte |

## Projektstruktur

```
backend/    Quarkus 3.40 (Java 25): GitHub-Anbindung, Analyse, Datenablage, REST-API, liefert das UI aus
frontend/   Angular 22 (standalone, signals, zoneless): UI im IntelliJ-«New UI»-Look
docs/       Konzept, Annahmen, Architektur, Formate
Dockerfile  Native-Build (Node → Mandrel → ubi9-micro)
```
