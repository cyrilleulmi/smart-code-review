# Konzept

Stand: 13.11.2026, Kick-off des PoC.

## Problem

Auf GitHub sieht man bei einem Pull Request alle geänderten Zeilen aller Dateien untereinander, alphabetisch nach Pfad. Wichtige Logik ist zwischen DTOs, Übersetzungen, Lockfiles und Mappern versteckt, und einen Aufrufpfad (Komponente → Service → Repository) muss man sich über mehrere Dateien selbst zusammensuchen.

## Ziel des PoC

Ein lokal startbares Werkzeug, das

1. sich mit **GitHub** verbindet (später auch GitLab, Bitbucket, Azure DevOps …),
2. **alle offenen Pull Requests** anzeigt, die *ich erstellt habe* oder bei denen *mein Review angefragt* ist (mit Filter),
3. **Kommentare** in Pull Requests erstellen kann – sie gehen **sofort** an GitHub,
4. jede geänderte Codezeile als **«ok»** markieren lässt (momentan der einzige Zustand). Kategorien, Gruppen und Flows lassen sich gesamthaft als ok markieren,
5. diese Markierungen **lokal** speichert (Dateisystem oder Docker-Volume), in einem Format, mit dem **Agenten gut arbeiten** können,
6. die Änderungen in **Kategorien** aufteilt, zuerst **«Quick wins»**, erkannt per **Muster, nicht per KI**,
7. die **Datenflüsse** zusammen zeigt: geänderte Funktionen, die sich nacheinander aufrufen, als nachvollziehbare Kette, mit klarem **Syntax-Highlighting** und **Verbindungen** zwischen Aufrufstelle und aufgerufenem Code,
8. **modern** aussieht, angelehnt an **IntelliJ** (New UI, Dark).

**Technologie:** Quarkus (native) und Angular.

**Später:** mehrere «Sortiermöglichkeiten» (andere Gruppierungen und Reihenfolgen), weitere Code-Hosts.

**Out of scope:** Umgang mit neuen Commits in einem bereits angeschauten PR.

## Entscheide aus den Rückfragen

| Frage | Entscheid |
|---|---|
| Welche PRs? | Beide: von mir erstellt **und** Review-Anfragen, mit Filter im UI |
| Wie gelangen Kommentare zu GitHub? | **Sofort posten** (kein Entwurf / Pending Review) |
| Native ohne lokales GraalVM? | Entwicklung im Quarkus-Dev-Mode (JVM); **Native-Build im Docker-Container** |
| Test-PRs | In `spongecoach`, `camperquiz` und `stv-bero-website`, als Draft, klar mit **[TEST-MR]** markiert |

## Kernbegriffe

| Begriff | Bedeutung |
|---|---|
| **Snapshot** | Eingefrorener Stand eines PRs (Metadaten, Diff, Quelltexte der neuen Version). Neue Commits werden nicht automatisch nachgeladen. |
| **Segment** | Kleinste Review-Einheit: eine geänderte Funktion/Methode, ein geänderter Typ, eine Konstante, oder ein Hunk, wenn keine Struktur erkennbar ist. |
| **Kategorie / Gruppe** | Quick wins (mit Gruppen wie «Übersetzungen», «DTOs & Typen»), Datenflüsse, Einzelne Änderungen, Tests, Styles & Assets. |
| **Flow** | Zusammenhängende Segmente, die sich gegenseitig aufrufen, geordnet ab dem Einstiegspunkt. |
| **Markierung (ok)** | Pro geänderter Zeile (`N12` = neue Zeile 12, `O7` = gelöschte alte Zeile 7), lokal gespeichert. |
