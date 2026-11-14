# Changelog

Alle nennenswerten Änderungen an SmartCodeReview. Die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/); wie das hier ausgelegt wird und wie man ein Release macht, steht in [docs/08-versionierung.md](docs/08-versionierung.md).

Docker-Images: `culmi/smart-code-review:<version>` und `:latest` (= letztes Release).

## [Unreleased]

### Neu
- **Commits:** Die Commits des PR/MR stehen im Review-Fenster (Tab *Commits*) und auf der Übersicht, mit Fortschritt pro Commit. Jede geänderte Zeile ist dem Commit zugeordnet, der sie zuletzt geschrieben bzw. entfernt hat (`commits.json`, API `GET …/commits`).
- **Nur einen Commit ansehen:** Ein Klick auf einen Commit filtert alle Ansichten (Kategorien, Flows, Dateien, Übersicht) auf seine Änderungen. Änderungen anderer Commits sind im Code abgeblendet, Häkchen, «Block ok» und Fortschritt beziehen sich nur auf die Zeilen des Commits. In der Toolbar geht es mit ← → von Commit zu Commit; der Commit steht auch in der URL.
- **Ganzes Repository analysieren:** Repository-URL ins Suchfeld oder Ordner-Knopf in der Toolbar eines PRs: Alle Dateien des Default-Branches werden wie ein Pull Request analysiert, in dem jede Datei neu ist (Kategorien, Flows, Komponenten, Tests, Commit-Filter, Blame). Geladen wird ein einziges Archiv; Commits sind die letzten 50 des Branches. API: Nummer `0`, z. B. `GET /api/prs/github/owner/repo/0`.
- **Annotieren mit Git Blame:** Toolbar-Knopf blendet in allen Code-Blöcken eine Blame-Spalte ein (wie in IntelliJ): Datum bzw. Uhrzeit, Autor, Nummer des PR-Commits, ältere Zeilen blasser. Gelöschte Zeilen zeigen den Commit, der sie entfernt hat. Klick filtert auf den Commit. GitHub über GraphQL, GitLab über die Blame-API; Cache unter `blame/` (API `GET …/blame?path=…`).
- **Halb akzeptiert:** Eine Zeile, die an mehreren Orten vorkommt (mehrere Kategorien, Flows) und an einem Ort akzeptiert wurde, erscheint an den anderen Orten als «halb akzeptiert»: halbes Häkchen, Hinweis «bereits an anderem Ort akzeptiert» (Klick springt dorthin, Tooltip nennt die Orte). Mehrere akzeptierte Orte werden einzeln geführt; das Zurücknehmen an einem Ort lässt die anderen stehen. Halb akzeptiert zählt im Fortschritt noch nicht.
- **Beschreibung jeder Kategorie:** Unter dem Titel («Wie entsteht diese Kategorie?») und als Tooltip in der Übersicht steht, wie die Kategorie aufgebaut ist und wie sie in Gruppen unterteilt wird. Aus den Regeln abgeleitet, in `rules.json` pro Kategorie mit `builds` / `divides` überschreibbar.

### Geändert
- **Kategorien sind Module** mit einheitlicher Schnittstelle (`CategoryModule`); Hinzufügen oder Entfernen einer Kategorie beeinflusst die anderen nicht. Datenflüsse bleiben als Ausnahme mit eigener Ansicht (`view: "flow"`). Vorbereitet, aber noch nicht umgesetzt: Kategorien von aussen hinzufügen (siehe Roadmap).
- Die Regeln für Zeilen an mehreren Orten stehen an einer Stelle (`LineAcceptance`) statt doppelt in Backend und UI verstreut. Dateiformat von `review.json` unverändert.
- `Analyzer.VERSION` 14: Analysen enthalten pro Kategorie `builds`, `divides`, `view`, `origin`; gespeicherte Analysen werden neu berechnet.
- **Dokumentation für Architekt:innen:** Architektur-Überblick mit Diagrammen, Laufzeitsichten und Architekturentscheiden ([docs/09](docs/09-architektur-ueberblick.md)), alle Funktionen als Anforderungen mit Nachweis ([docs/10](docs/10-anforderungen.md)), belegte Verbesserungsmöglichkeiten ([docs/11](docs/11-verbesserungen.md)) und eine überarbeitete Roadmap ([docs/07](docs/07-roadmap.md)).
- **Analyse viel schneller bei vielen Segmenten:** Die Import-Prüfung zwischen zwei Dateien wird pro Datei zwischengespeichert statt für jedes Paar von Segmenten neu gerechnet (eigenes Repo als Ganzes: 29 s → 2,5 s, Ergebnis identisch).

## [0.5.0] – 2026-11-13

### Neu
- **Generierte OpenAPI-Clients:** Die OpenAPI-Verträge des Repos werden gelesen; Frontend-Aufrufe generierter Services (`playersService.updatePlayer(…)`) werden über die operationId mit dem Controller verbunden, der das generierte `…Api`-Interface implementiert (oder einem Endpunkt mit passendem Pfad). Flows laufen so auch ohne URL im Code vom Browser ins Backend.
- **Schichten mit Adaptern (Onion-Stil):** Repos mit `domain` / `application` / `adapter` ohne Ports werden als «Schichten mit Adaptern» erkannt und bekommen die Architektur-Kategorien. Adapter werden nach Rolle gruppiert: eingehend (REST, Messaging-Listener, Fehlerbehandlung), ausgehend, Mapping, Konfiguration.
- **Neue Kategorien:** *API-Verträge* (Sprünge zu Aufrufern und Implementierungen der geänderten Operation), *Sicherheit & Berechtigungen*, *Messaging & Events*, *Transaktionen & Nebenläufigkeit*, *Datenbank & Migrationen* (Migration und Entity derselben Tabelle in einer Gruppe, mit Verweisen), *Konfiguration, Build & Betrieb*. Querschnitts-Kategorien kommen aus der neuen Regel-Liste `concerns`.
- **Auslöser im Flow-Titel:** `JMS-Listener: onMessage → …`, `PUT /rest/players/{}: updatePlayer → …`.

### Geändert
- Migrationen, JPA-Entities, Repositories mit Queries, `application.properties`, Dockerfile/CI/Deployment und Abhängigkeiten sind keine Quick wins mehr. Generierte Bäume (`gen/`, `dist/`, `build/generated/`) sind es neu.
- Message-Listener in `adapter/messaging/` gelten als eingehende Adapter (vorher: ausgehend). `infra/` unter `deploy/` zählt nicht mehr als Adapter-Schicht.
- Sprünge in Dateien ohne Symbole (YAML, SQL) werden angezeigt.

## [0.4.0] – 2026-11-13

### Neu
- **Ports & Adapters:** Das Repository wird anhand seiner Ordner (`domain`, `application`, `port`, `usecase`, `adapter`, `infrastructure` …) geprüft. Ist es so aufgebaut, bekommt jede Klasse eine Schicht und es gibt die Kategorien *Domäne*, *Use Cases*, *Application Layer* und *Adapter* (eingehend / ausgehend).
- **Frontend-Kategorien:** Routing & Navigation, Zustand & Stores, API-Anbindung, Formulare & Validierung, Komponenten-Logik, Templates & Markup (Regeln in `rules.json` → `aspects`).
- **Kategorien ein-/ausschalten** (Übersicht, Review-Baum). Ausgeschaltete Kategorien sind ausgeblendet und brauchen kein Ok; gespeichert in `<data>/settings.json`, API `GET/PUT /api/settings`.
- **Flows über REST:** HTTP-Aufrufe im Frontend (`http.put('/api/players/${id}')`, `fetch`, axios) werden mit Backend-Endpunkten verbunden (JAX-RS, Spring, Next.js-Route-Handler, Express, Go). Ein Flow läuft so von der Komponente über den API-Client ins Backend.
- **Komponentendiagramm** auf der Übersicht: alle geänderten Komponenten in Spalten nach Schicht, Aufrufe und REST-Aufrufe dazwischen, Fokus-Knopf pro Komponente.
- **Fokus im Flow-Diagramm:** Der Knopf im Knoten zeigt nur noch diesen Knoten und alles, was davon abhängt – im Diagramm und im Code.
- **Deutlichere Gestaltung:** Jede Verbindung in einem Flow hat ihre eigene Farbe (Diagramm, Verbindungslinie, Aufruf-Chip, «aufgerufen von»); REST-Verbindungen gestrichelt. Jeder Block und jeder Knoten zeigt seine Schicht (Frontend, API, Use Case, Application, Domäne, Daten, Test) als farbigen Streifen und Badge sowie die Sprache (TS, Java, Go, …); Frontend-Code ist zusätzlich leicht getönt. Die Verbindungslinien links neben dem Code laufen auf eigenen Spuren (mind. 11 px Abstand, lange aussen, kurze innen, Punkt an der Aufrufstelle, Pfeil am Ziel); der Rand wächst mit der Zahl der Spuren. Verschachtelte Aufrufe sind stärker eingerückt, tiefe Flows scrollen seitlich. Im Komponentendiagramm haben Spalten, Knoten und Verbindungen die Farbe ihrer Schicht.

### Geändert
- `analysis.json`: neue Felder `architecture`, `components`, `componentEdges`, `segments[].layer`, `segments[].frontend`, `flows[].edges[].kind`. Bestehende Analysen werden neu berechnet; neue Datei `structure.json` pro PR (Ordner des Repos).
- Regeln: `aspects`, `extras`, `text`, `fileText`, `special: "markup"`.

## [0.3.0] – 2026-11-13

### Neu
- **Tests ↔ Code:** Testdateien werden in Testfälle zerlegt (`describe`/`it`/`test`, JUnit `@Test`, Go `TestXxx`). Jeder geänderte Testfall zeigt die getesteten Komponenten und die abgedeckten Codestellen (direkt oder indirekt über andere geänderte Funktionen), statisch ermittelt. Neue Testübersicht in der Kategorie *Tests*, im Code «Getestet von …» / «Testet …».
- **Mehrere Orte, mehrere Oks:** Ein Block kann in mehreren Kategorien/Flows erscheinen und braucht dann an jedem Ort ein eigenes Ok. Leiste «n Orte» im Block-Kopf mit Sprüngen; Dateiansicht zeigt pro Zeile, wie vielen Orten sie zugewiesen ist (Häkchen dort gilt für alle Orte).
- **Fokus-Werkzeuge:** Blöcke einzeln ein-/ausklappen, alle ein-/ausklappen, «Einzeln durchgehen» mit «Ok & weiter»; im Flow zusätzlich Schritte aus-/einblenden, Erledigte ausblenden und «nur dieser Zweig».
- Version in der Statusleiste und in `GET /api/me` (`version`).
- Release-Skript `release.sh` und dieser Changelog.

### Geändert
- Ein Flow pro Einstiegspunkt: gemeinsam genutzte Funktionen erscheinen in jedem Flow, der sie erreicht (in Aufrufgruppen bis 20 Funktionen).
- `review.json`: neues Feld `okAt` für Markierungen pro Ort; `POST …/marks` akzeptiert `place` pro Zeile. Bestehende Dateien bleiben gültig.
- `analysis.json`: neue Felder `segments[].places`, `segments[].testedBy`, `tests[]`. Bestehende Analysen werden automatisch neu berechnet.
- Regeln: neues Feld `symbols` für Pfad-Regeln (Testdateien werden weiter in Funktionen zerlegt).
- Das Docker-Build führt die Unit-Tests aus.

## [0.2.0] – 2026-11-13

### Neu
- **GitLab** als zweiter Provider (gitlab.com und self-managed über `SCR_GITLAB_URL`): MR-Liste, Diffs, Kommentare inkl. Inline-Kommentare. Token aus `GITLAB_TOKEN` oder `glab`.
- Pull/Merge Requests per URL öffnen (auch öffentliche GitLab-MRs ohne Token).
- Go: Funktionen, Methoden, Typen und Aufrufe über Pakete hinweg.
- Natives Docker-Image auf Docker Hub: `culmi/smart-code-review`.

## [0.1.0] – 2026-11-13

### Neu
- Erster PoC: offene GitHub-PRs (eigene und Review-Anfragen), Zerlegung in Segmente, Quick wins per Regeln (ohne KI), Datenflüsse in Aufrufreihenfolge mit Diagramm, zeilenweises «ok» lokal in JSON-Dateien für Agenten, Inline-Kommentare werden sofort veröffentlicht, IntelliJ-artiges UI.

[Unreleased]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/cyrilleulmi/smart-code-review/releases/tag/v0.1.0
