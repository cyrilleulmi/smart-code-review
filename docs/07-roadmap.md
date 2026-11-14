# Roadmap

Mögliche zukünftige Funktionen, grob nach Nutzen geordnet. Verbesserungen am Bestehenden (Tests, Struktur, Sicherheit, Betrieb) stehen in [11-verbesserungen.md](11-verbesserungen.md); die umgesetzten Funktionen als Anforderungen in [10-anforderungen.md](10-anforderungen.md).

**Nutzen:** ★★★ hoch · ★★ mittel · ★ nice to have. **Aufwand:** S ≤ 1 Tag, M ≤ 1 Woche, L > 1 Woche.

## Stand der bisherigen Punkte

| # | Punkt | Stand |
|---|---|---|
| 1 | Neue Commits, Markierungen übertragen | offen, durch die Commit-Zuordnung jetzt einfacher (F-01, F-02) |
| 2 | REST-Verbindung erweitern | teilweise: generierte OpenAPI-Clients seit 0.5.0; Konstanten/Env, GraphQL und Architektur-Regeln offen (F-12, F-21) |
| 3 | Unveränderte Zwischenstationen | offen (F-13) |
| 4 | Weitere Zustände pro Zeile | offen (F-04) |
| 5 | Sortiermöglichkeiten | teilweise: «nach Commit» ist mit dem Commit-Filter umgesetzt; Risiko, Schicht, Datei offen (F-10) |
| 6 | Echter Parser | offen (F-14) |
| 7 | MCP-Server | offen (F-30) |
| 8 | Weitere Provider | GitHub und GitLab umgesetzt; Bitbucket, Azure DevOps, mehrere GitLab-Instanzen offen (F-33) |
| 9 | Review abschliessen | offen (F-05) |
| 10 | Laufzeit-Coverage | offen (F-15) |
| 11 | Fokus-Zustand merken | offen (F-06) |
| 12 | Kategorien von aussen | vorbereitet (`CategoryModules.build` nimmt Module an), Quelle offen (F-32) |
| 13 | Markdown, Light-Theme, Tastatur | offen (F-40 bis F-42) |

## Review-Ablauf

| ID | Feature | Beschreibung | Nutzen | Aufwand |
|---|---|---|---|---|
| F-01 | **Neu seit meinem letzten Review** | Beim Neuladen den alten Head merken. Über die Commit-Zuordnung zeigen, welche Zeilen aus Commits nach dem letzten Review stammen, und nur diese als offen markieren. Schalter «nur neue Commits» im Commit-Filter. | ★★★ | M |
| F-02 | **Markierungen über neue Commits retten** | Beim Neuladen Markierungen über Zeileninhalt (Hash + Nachbarschaft) und Commit-Zuordnung auf die neuen Zeilenschlüssel übertragen statt sie unverändert zu lassen (heute Annahme A4). Grundlage für F-01. | ★★★ | M |
| F-03 | **Commit-Bereich filtern** | Mehrere Commits oder «ab Commit X» statt genau einem; Mehrfachauswahl in der Commit-Liste. | ★★ | S |
| F-04 | **Weitere Zustände pro Zeile/Block** | «Frage», «muss geändert werden», mit Notiz; daraus einen Kommentarentwurf erzeugen. Format: neues Feld in `review.json` (ergänzt, nicht umbenannt). | ★★★ | M |
| F-05 | **Review abschliessen** | Approve / Request changes aus dem Tool; Kommentare optional als gesammelter Review (Pending) statt einzeln. | ★★ | M |
| F-06 | **Fokus-Zustand merken** | Ausgeblendete Schritte, Position im Schritt-für-Schritt-Modus, aktiver Commit-Filter pro PR speichern. | ★ | S |
| F-07 | **Review-Bericht** | Zusammenfassung als Markdown: Fortschritt pro Kategorie, offene Flows, ungetestete Logik, eigene Notizen. Optional als allgemeiner PR-Kommentar posten. | ★★ | S–M |
| F-08 | **Suche im PR** | Volltext- und Symbolsuche über alle Segmente; Treffer springen zum Block. | ★★ | S |
| F-09 | **Nach Autor:in filtern** | Mit Blame und Commit-Zuordnung: nur Änderungen einer Person zeigen (bei PRs mit mehreren Autor:innen). | ★ | S |

## Analyse und Sichten

| ID | Feature | Beschreibung | Nutzen | Aufwand |
|---|---|---|---|---|
| F-10 | **Weitere Sortierungen** | Umschalter im Review-Fenster: nach Risiko (Zeilen × Verzweigungen × fehlende Tests), nach Schicht (UI → Service → Persistenz), nach Datei (wie GitHub). Technisch eine weitere Sicht auf dieselben Segmente. | ★★★ | M |
| F-11 | **Risiko-Hinweise** | Logik ohne verknüpften Test, grosse Methoden, viele Aufrufer, Änderungen an Sicherheits- oder Transaktionscode: als Badge am Block und als Sortierung (F-10). | ★★★ | M |
| F-12 | **Architektur-Regeln prüfen** | Regeln wie «Domäne importiert keinen Adapter» oder «Controller ruft kein Repository direkt»; Verstösse als eigene Kategorie. Besonders wertvoll im Repository-Modus. | ★★★ | M |
| F-13 | **Unveränderte Zwischenstationen** | Bei A → X → B mit unverändertem X: X als graue «Durchreiche» im Flow zeigen, damit Flüsse nicht zerfallen (Annahme B3). Braucht Quelltexte auch ungeänderter Dateien (z. B. aus dem Archiv, siehe V-42). | ★★ | M |
| F-14 | **Echter Parser** | TypeScript-Compiler-API in einem Node-Sidecar oder tree-sitter (JNI/WASM), wo die Heuristik an Grenzen stösst (B1). | ★★ | L |
| F-15 | **Laufzeit-Coverage** | `lcov.info` / JaCoCo-XML aus der CI einlesen und pro Zeile «von Tests ausgeführt» zeigen; HTTP-Aufrufe in Tests (`given().put("/api/…")`) über die REST-Pfade mit Endpunkten verbinden. | ★★ | M |
| F-16 | **REST-Verbindung erweitern** | URLs aus Konstanten und Environment-Dateien auflösen, GraphQL-Operationen, WebSockets. | ★★ | M |
| F-17 | **Weitere Sprachen** | Python (Funktionen, pytest), Rust, PHP, Ruby: Symbole, Testfälle, Aufrufe. | ★★ | M pro Sprache |

## Repository-Modus

| ID | Feature | Beschreibung | Nutzen | Aufwand |
|---|---|---|---|---|
| F-20 | **Branch oder Tag wählen** | Nicht nur den Default-Branch, sondern einen beliebigen Ref analysieren (`…/tree/<branch>` in der URL wird heute ignoriert). | ★★ | S |
| F-21 | **Zwei Stände vergleichen** | Diff zwischen zwei Refs (z. B. zwei Releases) als «Pull Request» analysieren, ohne dass es einen PR gibt. Die Compare-API der Hosts liefert dasselbe Format wie ein PR. | ★★★ | M |
| F-22 | **Hotspots** | Änderungshäufigkeit pro Datei/Funktion aus der Commit-Historie, kombiniert mit Grösse und fehlenden Tests: Heatmap im Komponentendiagramm. | ★★ | M |
| F-23 | **Architekturdokumentation erzeugen** | Komponentendiagramm, Schichten und Flüsse eines Repos als Markdown/Mermaid exportieren. | ★★ | S–M |

## Agenten und Integration

| ID | Feature | Beschreibung | Nutzen | Aufwand |
|---|---|---|---|---|
| F-30 | **MCP-Server** | Quarkus-MCP-Extension mit Tools wie `list_prs`, `get_segments`, `get_flow`, `mark_ok`, `comment`, damit Agenten nicht über Dateien oder `curl` gehen. Baut auf V-52 (OpenAPI) auf. | ★★★ | M |
| F-31 | **«In IDE öffnen»** | Sprung vom Block an die Stelle in IntelliJ (`jetbrains://`-Link bzw. Toolbox) oder VS Code (`vscode://file/…`), wenn das Repo lokal ausgecheckt ist. | ★★ | S |
| F-32 | **Kategorien von aussen** | Module aus einem Verzeichnis oder Prozess laden (`origin = external`); fremde Module dürfen die anderen nicht stören; Schnittstelle `SegmentFacts` wächst nur. | ★★ | M–L |
| F-33 | **Weitere Provider** | Bitbucket, Azure DevOps; mehrere GitLab-Instanzen gleichzeitig. | ★★ | M pro Provider |
| F-34 | **Neue Commits melden** | Periodisch prüfen, ob ein offener PR einen neuen Head hat, und im UI anbieten, neu zu laden (zusammen mit F-01). | ★★ | S |
| F-35 | **Team-Modus** | Review-Status mehrerer Personen zusammenführen (z. B. über einen geteilten Ordner oder als versteckter PR-Kommentar). Bricht mit «ein Benutzer, lokal» (A1) und braucht ein Konzept. | ★ | L |

## Bedienung

| ID | Feature | Beschreibung | Nutzen | Aufwand |
|---|---|---|---|---|
| F-40 | **Tastaturnavigation** | `n`/`p` nächster/vorheriger Block, `o` ok, `c` kommentieren, `[`/`]` Commit wechseln. | ★★ | S |
| F-41 | **Markdown-Rendering** | Kommentare und PR-Beschreibung gerendert (sicher, ohne HTML). | ★★ | S |
| F-42 | **Light-Theme** | Zweites Token-Set in `styles.scss`, Umschalter in der Statusleiste. | ★ | S |
| F-43 | **Barrierefreiheit** | Tastaturbedienung aller Knöpfe, ARIA-Rollen im Baum und in den Diagrammen, Kontraste der Token-Paletten prüfen. | ★★ | M |
| F-44 | **Mobil lesen** | Lesemodus für schmale Bildschirme (heute nur verkleinerte Desktop-Ansicht in der Demo). | ★ | M |

## Bekannte Grenzen

* Sehr grosse PRs: einzelne Flüsse bleiben gross; der Fokus des Tools sind normale Feature-PRs.
* Gleichnamige Funktionen in verschiedenen Modulen können falsch verbunden werden, wenn der Import-Beleg nicht eindeutig ist.
* Regex-Literale und exotische Syntax können Funktionsgrenzen verschieben (siehe Annahme B1).
* Nach einem Merge des Basis-Branches bleiben einzelne gelöschte Zeilen ohne Commit (A11, V-35).
* Im Repository-Modus zählen nur die letzten 50 Commits; ältere Zeilen haben keinen Commit, nur Blame.
