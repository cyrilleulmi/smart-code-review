# Changelog

Alle nennenswerten Änderungen an SmartCodeReview. Die Versionen folgen [Semantic Versioning](https://semver.org/lang/de/); wie das hier ausgelegt wird und wie man ein Release macht, steht in [docs/08-versionierung.md](docs/08-versionierung.md).

Docker-Images: `culmi/smart-code-review:<version>` und `:latest` (= letztes Release).

## [Unreleased]

### Neu
- **Ports & Adapters:** Das Repository wird anhand seiner Ordner (`domain`, `application`, `port`, `usecase`, `adapter`, `infrastructure` …) geprüft. Ist es so aufgebaut, bekommt jede Klasse eine Schicht und es gibt die Kategorien *Domäne*, *Use Cases*, *Application Layer* und *Adapter* (eingehend / ausgehend).
- **Frontend-Kategorien:** Routing & Navigation, Zustand & Stores, API-Anbindung, Formulare & Validierung, Komponenten-Logik, Templates & Markup (Regeln in `rules.json` → `aspects`).
- **Kategorien ein-/ausschalten** (Übersicht, Review-Baum). Ausgeschaltete Kategorien sind ausgeblendet und brauchen kein Ok; gespeichert in `<data>/settings.json`, API `GET/PUT /api/settings`.
- **Flows über REST:** HTTP-Aufrufe im Frontend (`http.put('/api/players/${id}')`, `fetch`, axios) werden mit Backend-Endpunkten verbunden (JAX-RS, Spring, Next.js-Route-Handler, Express, Go). Ein Flow läuft so von der Komponente über den API-Client ins Backend.
- **Komponentendiagramm** auf der Übersicht: alle geänderten Komponenten in Spalten nach Schicht, Aufrufe und REST-Aufrufe dazwischen, Fokus-Knopf pro Komponente.
- **Fokus im Flow-Diagramm:** Der Knopf im Knoten zeigt nur noch diesen Knoten und alles, was davon abhängt – im Diagramm und im Code.
- **Deutlichere Gestaltung:** Jede Verbindung in einem Flow hat ihre eigene Farbe (Diagramm, Verbindungslinie, Aufruf-Chip, «aufgerufen von»); REST-Verbindungen gestrichelt. Jeder Block und jeder Knoten zeigt seine Schicht (Frontend, API, Use Case, Application, Domäne, Daten, Test) als farbigen Streifen und Badge sowie die Sprache (TS, Java, Go, …); Frontend-Code ist zusätzlich leicht getönt. Verschachtelte Aufrufe sind stärker eingerückt, tiefe Flows scrollen seitlich. Im Komponentendiagramm haben Spalten, Knoten und Verbindungen die Farbe ihrer Schicht.

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

[Unreleased]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/cyrilleulmi/smart-code-review/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/cyrilleulmi/smart-code-review/releases/tag/v0.1.0
