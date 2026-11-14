# Persistenz und Zusammenarbeit mit Agenten

Alles, was SmartCodeReview lokal weiss, liegt als **lesbare Dateien** in einem Verzeichnis. Es gibt keine Datenbank und keinen versteckten Zustand: was in den Dateien steht, zeigt das UI.

## Datenverzeichnis

| Betrieb | Pfad |
|---|---|
| Lokal (Default) | `~/.smart-code-review/` (Windows: `C:\Users\<du>\.smart-code-review\`) |
| Anders | Env `SCR_DATA_DIR` oder Property `scr.data-dir` |
| Docker Compose | `./data/` im Repo (Volume nach `/data`) |

```
<data>/
├── AGENTS.md                 Kurzanleitung für Agenten (wird beim Start geschrieben)
├── rules.default.json        Mitgelieferte Regeln (wird beim Start überschrieben, nur zum Nachschlagen)
├── rules.json                OPTIONAL: eigene Regeln; hat Vorrang. Änderungen werden automatisch übernommen.
├── settings.json             Ausgeschaltete Kategorien {"disabledCategories": [...]} (vom UI geschrieben)
└── reviews/
    └── <github|gitlab>/<owner>/<repo>/<number>/      (GitLab-Gruppen: gruppe~untergruppe)
        ├── snapshot.json     PR-Metadaten + Diff (eingefroren)
        ├── sources/<pfad>    Quelltext der neuen Version (nur analysierte Dateien)
        ├── structure.json    Ordner des Repos am PR-Stand (für die Architektur-Erkennung, Cache)
        ├── analysis.json     Segmente, Kategorien, Flüsse, Komponenten (abgeleitet, jederzeit neu berechenbar)
        ├── review.json       ◀ DEINE MARKIERUNGEN (die einzige Datei mit «echtem» Zustand)
        ├── comments.json     Letzter Abruf der GitHub-Kommentare (Cache)
        ├── commits.json      Commits des PRs und welcher Commit welche Zeile geändert hat (abgeleitet, Cache)
        └── blame/<pfad>.json Git-Blame einer Datei am PR-Stand (Cache, beim Annotieren geholt)
```

Alle Dateien sind UTF-8, eingerückt und werden **atomar** geschrieben (temporäre Datei + Move). Das Backend liest sie **bei jedem Request neu**; das UI holt den Status neu, sobald das Browserfenster den Fokus bekommt.

## `review.json`

```json
{
  "schemaVersion": 1,
  "pr": "github/cyrilleulmi/spongecoach/13",
  "headSha": "8d7ba2e4…",
  "ok": {
    "frontend/src/app/players/player.model.ts": ["N9", "N10", "N11"],
    "backend/src/main/java/…/PlayerResource.java": ["N75", "N76", "O12"]
  },
  "okAt": {
    "quick-wins/banale-rest-aufrufe": {
      "frontend/src/app/players/player-api.service.ts": ["N29"]
    }
  },
  "log": [
    { "at": "2026-11-13T09:33:06Z", "by": "agent:test", "action": "ok", "scope": "group:quick-wins/dtos-&-typen", "count": 3 }
  ]
}
```

* **Zeilenschlüssel:** `N<zeile>` = Zeile in der **neuen** Datei (hinzugefügt), `O<zeile>` = Zeile in der **alten** Datei (gelöscht). Nur geänderte Zeilen zählen.
* `ok` ist pro Datei eine sortierte Liste der Zeilen, die **überall** ok sind. Was weder hier noch in `okAt` steht, ist nicht ok.
* `okAt` (Ort → Datei → Zeilen) hält Zeilen, die erst an **einem Teil** ihrer Orte ok sind. Die Orte einer Zeile stehen in `analysis.json` → `segments[].places`. Sobald eine Zeile an allen Orten ok ist, wandert sie nach `ok`. Zeilen mit nur einem Ort stehen immer direkt in `ok`.
* `log` ist ein Protokoll (max. 500 Einträge), damit nachvollziehbar ist, wer was markiert hat. `by` ist `ui` oder `agent:<name>`.

## `commits.json`

```json
{
  "version": 1,
  "headSha": "b4461ff2…",
  "commits": [{ "sha": "76ce6d0d…", "title": "Flow connectors: one lane per connection", "author": "Cyrille",
                "date": "2026-11-13T16:18:00Z", "parents": ["69f7bd1f…"], "additions": 68, "deletions": 22, "files": ["…"] }],
  "lines": { "frontend/src/app/flow-view.ts": { "N186": "76ce6d0d…", "O116": "69f7bd1f…" } },
  "mergedVia": { "<sha eines Commits aus einem gemergten Seitenzweig>": "<sha des Merge-Commits>" }
}
```

* `commits` stehen in Reihenfolge, die ältesten zuerst.
* `lines` ordnet die Zeilenschlüssel des PR-Diffs (dieselben wie in `lineKeys`) einem Commit zu. Hinzugefügte Zeilen gehören zum Commit, der sie zuletzt geschrieben hat, gelöschte Zeilen zu dem, der sie entfernt hat. Zeilen ohne sichere Zuordnung fehlen.
* Wird neu berechnet, wenn sich `headSha` oder der Algorithmus (`version`) ändert. Die Datei ist abgeleitet und kann gelöscht werden.

## `analysis.json` (Auszug)

```json
{
  "segments": [{
    "id": "s3", "path": "…/PlayerResource.java", "kind": "symbol",
    "symbol": "PlayerResource.validateJerseyNumber", "symbolKind": "method", "startLine": 195, "endLine": 203,
    "category": "logic", "group": "…/PlayerResource.java", "ruleId": null, "reason": "Teil eines Datenflusses",
    "lineKeys": ["N195", "N196", "…"],
    "places": ["flows/flow-1"],
    "testedBy": ["s21", "s22"],
    "lines": [{ "type": "add", "newLine": 196, "key": "N196", "text": "…", "own": true, "inDiff": true,
                "refs": [{ "segmentId": "s9", "symbol": "Player.isJerseyNumberTaken" }] }]
  }],
  "categories": [{ "id": "quick-wins", "label": "Quick wins", "groups": [{ "id": "quick-wins/übersetzungen", "segmentIds": ["s4"] }] }],
  "flows": [{ "id": "flow-1", "title": "update → validateJerseyNumber → isJerseyNumberTaken",
              "steps": [{ "segmentId": "s2", "order": 1, "depth": 0, "parentSegmentId": null }],
              "edges": [{ "from": "s2", "to": "s3", "line": 76, "via": "validateJerseyNumber" }] }],
  "tests": [{ "segmentId": "s21", "name": "PlayerResourceTest › savesJerseyNumber", "path": "…/PlayerResourceTest.java",
              "subjects": [{ "name": "PlayerResource.java", "path": "…/PlayerResource.java", "inPr": true, "how": "name" }],
              "covers": [{ "segmentId": "s2", "direct": true, "via": null, "line": 28 },
                         { "segmentId": "s3", "direct": false, "via": "s2", "line": 28 }] }]
}
```

* `places`: alle Gruppen (`categories[].groups[].id`), die den Block zeigen. Mehr als ein Ort = mehrere Oks nötig. Orte in Kategorien aus `settings.json` → `disabledCategories` zählen nicht.
* `layer` (`domain` | `application` | `usecase` | `adapter` | null) und `frontend` (true/false) pro Segment; `architecture.style` = `hexagonal`, wenn das Repo Ports & Adapters nutzt (`evidence` = Ordner).
* `flows[].edges[].kind`: `call` oder `http` (dann `via` = `PUT /api/players/{}`).
* `components[]` (`id`, `name`, `paths`, `area`, `segmentIds`) und `componentEdges[]` (`from`, `to`, `calls`, `http`): das Komponentendiagramm.
* `tests[]`: pro geändertem Testfall die getesteten Komponenten (`how`: `name` = Namenskonvention, `call` = direkt aufgerufen) und die erreichten Segmente (`direct`, sonst `via` = das Segment, über das es erreicht wird; `line` = Aufrufstelle im Test). `testedBy` ist die Rückrichtung.

`analysis.json` ist **abgeleitet**: löschen oder `POST …/reanalyse` berechnet sie neu. Sie wird auch automatisch neu berechnet, wenn sich `rules.json` oder die Analyzer-Version ändert (`rulesHash`).

## Wie ein Agent mitarbeiten kann

**Variante 1: Dateien.** Der Agent liest `analysis.json` (was gibt es, wie ist es gruppiert) und `sources/` (Code), und schreibt Markierungen in `review.json`. Regeln:

1. Erst lesen, dann die ganze Datei neu schreiben (atomar: in eine temporäre Datei schreiben, dann umbenennen).
2. Schlüssel nur aus `segments[].lineKeys` verwenden. Für «überall ok» in `ok` eintragen (und die Zeile aus allen `okAt`-Orten entfernen); für einen einzelnen Ort in `okAt[<ort>]`.
3. Einen `log`-Eintrag mit `"by": "agent:<name>"` anhängen.

**Variante 2: REST** (empfohlen, wenn das Tool läuft – keine Schreibkonflikte):

```bash
# Alle Quick wins der Gruppe "Generiert & Lockfiles" als ok markieren
curl -X POST localhost:8080/api/prs/github/OWNER/REPO/12/marks \
  -H 'Content-Type: application/json' \
  -d '{"lines":[{"path":"package-lock.json","key":"N1"}],"ok":true,"scope":"group:quick-wins/generiert-&-lockfiles","by":"agent:claude"}'

# Nur im Flow flow-2 als ok markieren (die Zeile erscheint auch als Quick win)
curl -X POST localhost:8080/api/prs/github/OWNER/REPO/12/marks \
  -H 'Content-Type: application/json' \
  -d '{"lines":[{"path":"src/api.ts","key":"N29","place":"flows/flow-2"}],"ok":true,"scope":"flow:flow-2","by":"agent:claude"}'
```

Ohne `place` gilt die Markierung an allen Orten der Zeile.

**Typische Aufträge an einen Agenten:**

* «Geh die Quick wins von PR 12 durch, markiere alles als ok, was wirklich trivial ist, und liste den Rest mit Begründung auf.»
* «Prüfe den Flow `flow-1` auf fehlende Fehlerbehandlung und kommentiere direkt auf GitHub» (`POST …/comments`).
* «Ergänze `rules.json` um eine Regel, die unsere `*.mapper.ts`-Dateien als Quick win erkennt.»
* «Welche geänderten Funktionen in PR 12 werden von keinem geänderten Test erreicht?» (`segments[]` mit `category` = `logic` und leerem `testedBy`).

Die gleiche Anleitung liegt kompakt als `AGENTS.md` im Datenverzeichnis.
