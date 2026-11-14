# Roadmap

In grober Reihenfolge des Nutzens.

## Nächste Schritte

1. **Neue Commits** (bisher out of scope): Markierungen beim Neuladen über den Zeileninhalt (Hash + Nachbarschaft) statt über die Nummer übertragen. Was sich geändert hat, wieder als offen zeigen («seit deinem letzten Review»).
2. **REST-Verbindung erweitern** (Grundfunktion seit 0.4): URLs aus Konstanten/Environment auflösen, OpenAPI-generierte Clients, GraphQL-Operationen; Architektur-Regeln prüfen (z. B. «Domäne importiert keinen Adapter») und Verstösse als eigene Kategorie zeigen.
3. **Unveränderte Zwischenstationen** einblenden: Wenn A → X → B und nur A und B geändert sind, X als graue «Durchreiche» im Flow zeigen.
4. **Weitere Zustände** pro Zeile/Block: «Frage», «muss geändert werden», mit Notiz. Daraus einen Kommentarentwurf erzeugen.
5. **Sortiermöglichkeiten** (Auftrag «später»): nach Risiko (Zeilen × Verzweigungen × fehlende Tests), nach Schicht (UI → Service → Persistenz), nach Autor-Commit, nach Datei (GitHub-artig) – als Umschalter im Review-Fenster. Technisch eine weitere «Sicht» auf dieselben Segmente.
6. **Echter Parser** für TS/JS (TypeScript-Compiler-API in einem Node-Sidecar oder tree-sitter via JNI/WASM), wo die Heuristik an Grenzen stösst.
7. **MCP-Server** (Quarkus-MCP-Extension) mit Tools wie `list_prs`, `get_segments`, `mark_ok`, `comment`, damit Agenten nicht über Dateien oder `curl` gehen müssen.
8. **Weitere Provider:** Bitbucket, Azure DevOps; mehrere GitLab-Instanzen gleichzeitig. GitHub und GitLab sind umgesetzt (`ReviewProvider`).
9. **Review abschliessen** aus dem Tool: Approve / Request changes, optional als gesammelter Review statt Einzelkommentare.
10. **Laufzeit-Coverage** zusätzlich zur statischen Verknüpfung: `lcov.info` / JaCoCo-XML aus dem CI einlesen und pro Zeile «von Tests ausgeführt» zeigen; Aufrufe über HTTP (`given().put("/api/…")`) über die REST-Pfade (Punkt 2) mit Tests verbinden.
11. **Fokus-Zustand merken** (ausgeblendete Schritte, Position im Schritt-für-Schritt-Modus) pro PR in `review.json`, damit ein Review über mehrere Sitzungen weitergeht.
12. **Kategorien von aussen hinzufügen:** Die Kategorien sind Module mit einheitlicher Schnittstelle (`CategoryModule`, siehe [05-analyse.md](05-analyse.md#3e-kategorien-als-module)), `CategoryModules.build` nimmt zusätzliche Module bereits entgegen. Offen: woher sie kommen (Plugin-JAR, Verzeichnis, Prozess), Versionierung der Schnittstelle (`SegmentFacts` wächst nur), Anzeige der Herkunft (`origin = external`), Umgang mit Fehlern in einem fremden Modul (es darf die anderen nicht stören).
13. Markdown-Rendering für Kommentare und PR-Beschreibung, Light-Theme, Tastaturnavigation (n/p für nächsten Block, o für ok).

## Bekannte Grenzen

* Sehr grosse PRs: einzelne Flüsse bleiben gross; der Fokus des Tools sind normale Feature-PRs.
* Gleichnamige Funktionen in verschiedenen Modulen können falsch verbunden werden, wenn der Import-Beleg nicht eindeutig ist.
* Regex-Literale und exotische Syntax können Funktionsgrenzen verschieben (siehe Annahme B1).
