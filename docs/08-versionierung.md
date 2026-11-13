# Versionierung und Releases

## Schema

SmartCodeReview folgt [Semantic Versioning](https://semver.org/lang/de/) `MAJOR.MINOR.PATCH`. Solange die Hauptversion `0` ist (PoC), gilt:

| Teil | Wann erhöhen | Beispiel |
|---|---|---|
| MINOR (`0.x.0`) | Neue Funktionen, neue Felder in den JSON-Dateien oder der API, neue Analyse-Logik | Tests ↔ Code (0.3.0) |
| PATCH (`0.3.x`) | Fehlerkorrekturen ohne neue Funktionen und ohne Formatänderung | falsch erkannte Funktionsgrenze |
| MAJOR (`1.0.0`) | Erstes «stabiles» Release, oder später: ein **inkompatibles** Dateiformat (Feld umbenannt/entfernt) | – |

Das Dateiformat (`review.json`, `analysis.json`, API) ist der Vertrag mit Agenten: Felder werden **ergänzt, nicht umbenannt**. Ein Bruch davon wäre ein Major-Release.

## Wo die Version steht

| Ort | Rolle |
|---|---|
| `backend/pom.xml` → `<version>` | **Quelle.** Quarkus gibt sie als `quarkus.application.version` weiter: Statusleiste im UI, `GET /api/me` → `version`. |
| `frontend/package.json` | Wird beim Release mitgezogen (nur Information). |
| Git-Tag `vX.Y.Z` | Markiert den Release-Commit. |
| Docker-Image | `culmi/smart-code-review:X.Y.Z` (unveränderlich) und `:latest` (zeigt auf das letzte Release); Label `org.opencontainers.image.version`. |
| `CHANGELOG.md` | Was sich geändert hat, pro Version. |

Zwischen zwei Releases steht in `pom.xml` die **letzte veröffentlichte** Version; neue Einträge sammeln sich im Changelog unter `## [Unreleased]`.

## Release machen

1. In `CHANGELOG.md` den Abschnitt `## [Unreleased]` in `## [X.Y.Z] – JJJJ-MM-TT` umbenennen, darüber einen neuen leeren `## [Unreleased]` anlegen, Vergleichslinks unten nachführen. Committen.
2. In Git Bash (Docker muss laufen und bei Docker Hub angemeldet sein: `docker login`):

   ```bash
   ./release.sh X.Y.Z            # oder: ./release.sh X.Y.Z --no-push
   ```

Das Skript prüft (Branch `main`, sauberer Arbeitsstand, Tag noch frei, Changelog-Abschnitt vorhanden), setzt die Version, baut das native Image (inkl. Unit-Tests), committet «Release X.Y.Z», setzt den Tag `vX.Y.Z` und pusht Git und beide Image-Tags. Schlägt der Image-Build fehl, wird die Versionsänderung zurückgenommen und nichts committet.

## Bisherige Versionen

Siehe [CHANGELOG.md](../CHANGELOG.md). Die Tags `v0.1.0` und `v0.2.0` wurden nachträglich auf die damaligen Commits gesetzt; auf Docker Hub gibt es Images ab `0.2.0`.
