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
└── reviews/
    └── <github|gitlab>/<owner>/<repo>/<number>/      (GitLab-Gruppen: gruppe~untergruppe)
        ├── snapshot.json     PR-Metadaten + Diff (eingefroren)
        ├── sources/<pfad>    Quelltext der neuen Version (nur analysierte Dateien)
        ├── analysis.json     Segmente, Kategorien, Flüsse (abgeleitet, jederzeit neu berechenbar)
        ├── review.json       ◀ DEINE MARKIERUNGEN (die einzige Datei mit «echtem» Zustand)
        └── comments.json     Letzter Abruf der GitHub-Kommentare (Cache)
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
  "log": [
    { "at": "2026-11-13T09:33:06Z", "by": "agent:test", "action": "ok", "scope": "group:quick-wins/dtos-&-typen", "count": 3 }
  ]
}
```

* **Zeilenschlüssel:** `N<zeile>` = Zeile in der **neuen** Datei (hinzugefügt), `O<zeile>` = Zeile in der **alten** Datei (gelöscht). Nur geänderte Zeilen zählen.
* `ok` ist pro Datei eine sortierte Liste. Was nicht drinsteht, ist nicht ok.
* `log` ist ein Protokoll (max. 500 Einträge), damit nachvollziehbar ist, wer was markiert hat. `by` ist `ui` oder `agent:<name>`.

## `analysis.json` (Auszug)

```json
{
  "segments": [{
    "id": "s3", "path": "…/PlayerResource.java", "kind": "symbol",
    "symbol": "PlayerResource.validateJerseyNumber", "symbolKind": "method", "startLine": 195, "endLine": 203,
    "category": "logic", "group": "…/PlayerResource.java", "ruleId": null, "reason": "Teil eines Datenflusses",
    "lineKeys": ["N195", "N196", "…"],
    "lines": [{ "type": "add", "newLine": 196, "key": "N196", "text": "…", "own": true, "inDiff": true,
                "refs": [{ "segmentId": "s9", "symbol": "Player.isJerseyNumberTaken" }] }]
  }],
  "categories": [{ "id": "quick-wins", "label": "Quick wins", "groups": [{ "id": "quick-wins/übersetzungen", "segmentIds": ["s4"] }] }],
  "flows": [{ "id": "flow-1", "title": "update → validateJerseyNumber → isJerseyNumberTaken",
              "steps": [{ "segmentId": "s2", "order": 1, "depth": 0, "parentSegmentId": null }],
              "edges": [{ "from": "s2", "to": "s3", "line": 76, "via": "validateJerseyNumber" }] }]
}
```

`analysis.json` ist **abgeleitet**: löschen oder `POST …/reanalyse` berechnet sie neu. Sie wird auch automatisch neu berechnet, wenn sich `rules.json` oder die Analyzer-Version ändert (`rulesHash`).

## Wie ein Agent mitarbeiten kann

**Variante 1: Dateien.** Der Agent liest `analysis.json` (was gibt es, wie ist es gruppiert) und `sources/` (Code), und schreibt Markierungen in `review.json`. Regeln:

1. Erst lesen, dann die ganze Datei neu schreiben (atomar: in eine temporäre Datei schreiben, dann umbenennen).
2. Schlüssel nur aus `segments[].lineKeys` verwenden.
3. Einen `log`-Eintrag mit `"by": "agent:<name>"` anhängen.

**Variante 2: REST** (empfohlen, wenn das Tool läuft – keine Schreibkonflikte):

```bash
# Alle Quick wins der Gruppe "Generiert & Lockfiles" als ok markieren
curl -X POST localhost:8080/api/prs/github/OWNER/REPO/12/marks \
  -H 'Content-Type: application/json' \
  -d '{"lines":[{"path":"package-lock.json","key":"N1"}],"ok":true,"scope":"group:quick-wins/generiert-&-lockfiles","by":"agent:claude"}'
```

**Typische Aufträge an einen Agenten:**

* «Geh die Quick wins von PR 12 durch, markiere alles als ok, was wirklich trivial ist, und liste den Rest mit Begründung auf.»
* «Prüfe den Flow `flow-1` auf fehlende Fehlerbehandlung und kommentiere direkt auf GitHub» (`POST …/comments`).
* «Ergänze `rules.json` um eine Regel, die unsere `*.mapper.ts`-Dateien als Quick win erkennt.»

Die gleiche Anleitung liegt kompakt als `AGENTS.md` im Datenverzeichnis.
