# Annahmen und Vereinfachungen

Es handelt sich um einen PoC. Die folgenden Punkte sind bewusst einfach gehalten; jeder ist ein Kandidat für später.

## Produkt

| # | Annahme / Vereinfachung | Konsequenz |
|---|---|---|
| A1 | **Ein Benutzer, lokal.** Kein Login im Tool; der GitHub-Token kommt aus `GITHUB_TOKEN`/`GH_TOKEN` oder `gh auth token`. | Wer das Tool startet, handelt mit seinem eigenen GitHub-Konto. Der Server hört nur auf `127.0.0.1`. |
| A2 | **«Ok» ist der einzige Zustand** einer Zeile (markiert / nicht markiert). | Kein «braucht Nacharbeit», kein «unklar». Das Format lässt Erweiterungen zu (siehe Roadmap). |
| A3 | **Nur geänderte Zeilen** (hinzugefügt oder gelöscht) sind markierbar. Kontextzeilen werden nur angezeigt. | Fortschritt = markierte / geänderte Zeilen. |
| A4 | **Markierungen hängen an der Zeilennummer** (`N<neu>` / `O<alt>`) des Snapshots, nicht am Inhalt. | Ändert sich der PR (neue Commits), können Markierungen auf falsche Zeilen zeigen. Neue Commits sind laut Auftrag out of scope. «Von GitHub neu laden» behält die Markierungen bewusst unverändert. |
| A5 | **Kommentare werden sofort veröffentlicht** (einzelne Review-Kommentare, kein «Pending Review»). | Kein Sammeln, kein Approve / Request changes aus dem Tool. |
| A6 | Kommentieren geht nur auf **Zeilen, die im GitHub-Diff liegen** (geänderte Zeilen und deren 3 Kontextzeilen). | Zeilen ausserhalb (z. B. aus der Funktionsansicht) haben keinen Kommentar-Knopf; GitHub würde sie ablehnen. |
| A7 | **Markdown** in Kommentaren und PR-Beschreibung wird als Text angezeigt, nicht gerendert. | Gut lesbar, aber ohne Formatierung. |
| A8 | Nur **Dark Theme** (IntelliJ New UI «Dark»). | Kein Light-Mode-Umschalter. |
| A9a | **GitLab:** «Review-Anfragen» = offene MRs mit mir als *Reviewer* (`reviewer_username`), «Von mir» = `scope=created_by_me`. MRs, bei denen ich nur *Assignee* bin, erscheinen nicht. | In Teams, die Assignees statt Reviewer nutzen, fehlen MRs in der Liste (per URL öffnen geht immer). |
| A9b | **GitLab-Gruppen** werden in Referenzen, URLs und Ordnernamen als `gruppe~untergruppe` geschrieben (z. B. `reviews/gitlab/acme~platform/shop/7/`). | Pfade bleiben eine Ebene tief und dateisystemtauglich. |
| A9c | **Ohne GitLab-Token** lassen sich öffentliche MRs lesen (per URL), aber weder auflisten noch kommentieren. Ein GitLab-Self-managed-Server pro Instanz (`SCR_GITLAB_URL`). | Mehrere GitLab-Instanzen gleichzeitig sind nicht vorgesehen. |
| A9 | **Review-Anfragen an Teams** werden über die GitHub-Suche `review-requested:@me` gefunden – nur direkte Anfragen sind sicher abgedeckt. | Team-Reviews können fehlen. |
| A10 | Pro Suche **max. 100 PRs**, pro PR **max. 3000 Dateien** (GitHub-Limit), Dateien > 1 MB werden nicht geladen. | Genügt für normale Reviews. |

## Analyse

| # | Annahme / Vereinfachung | Konsequenz |
|---|---|---|
| B1 | **Kein echter Parser**, sondern ein kleiner Lexer (Strings/Kommentare ausblenden, Klammern zählen) plus reguläre Ausdrücke. Unterstützt C-artige Sprachen: TypeScript/JavaScript, Java, Kotlin, C#, Go (grob). | Funktioniert für üblichen Code; exotische Syntax (Regex-Literale mit Klammern, verschachtelte Generics über Zeilen) kann Grenzen verschieben. |
| B2 | **Aufrufe = Namensübereinstimmung**: Funktion A «ruft» B auf, wenn der Name von B im Rumpf von A vorkommt. Über Dateigrenzen nur mit Import-Beleg (Modulpfad, Klassen- oder Funktionsname im Import); bei klassischen `<script>`-Dateien ohne Module gilt der globale Scope. | Gleichnamige Funktionen können falsch verbunden werden; dynamische Aufrufe (Reflection, DI per String, Events) werden nicht erkannt. |
| B3 | **Nur geänderte Funktionen** bilden Flüsse. Unveränderte Zwischenstationen werden nicht eingeblendet. | Ein Fluss A → (unverändert X) → B erscheint als zwei getrennte Teile. |
| B4 | **Hubs**: Wird eine Funktion von mehr als 5 geänderten Stellen aufgerufen (z. B. `cn`, `Button`), gilt sie als gemeinsames Hilfsmittel und verbindet keine Flüsse. Zusammenhänge mit mehr als 20 Schritten werden pro Einstiegspunkt geteilt. | Bei sehr grossen PRs (ganze neue Website) bleiben einzelne Flüsse trotzdem gross. |
| B5 | **Einstiegspunkt** eines Flows = Funktion ohne Aufrufer innerhalb des Flows; bei mehreren gewinnt die «äusserste» Schicht (Pfad enthält `component`, `page`, `controller`, `resource` … vor `service` vor `repository`, `client`, `utils`). | Heuristik; die Reihenfolge ist meist plausibel, aber nicht garantiert. |
| B6 | **Quick wins nach Regeln** (`rules.json`): zuerst Pfad-Regeln für ganze Dateien, dann Zeilen-Regeln pro Segment («alle geänderten Zeilen sehen aus wie Feldzuweisungen» usw.). Die erste passende Regel gewinnt. | Regeln sind bewusst konservativ; im Zweifel landet Code bei der Logik. |
| B7 | Ein Segment kann gleichzeitig **Quick win und Flow-Schritt** sein (z. B. ein banaler REST-Aufruf am Ende eines Flows). Es wird an beiden Orten gezeigt; die Markierung ist dieselbe (zeilenbasiert). | Fortschritt pro Kategorie kann Zeilen doppelt enthalten; der Gesamtfortschritt zählt jede Zeile einmal. |
| B8 | Frontend ↔ Backend werden **nicht** über REST-Pfade verbunden (`/api/players` im Angular-Service ↔ `@Path("/api/players")`). | Ein Fullstack-Feature erscheint als zwei Flüsse. Siehe Roadmap. |
| B9 | Die Quelltexte der **neuen Version** werden einmal beim Öffnen geladen (nur Dateien, die analysiert werden). Für gelöschte Dateien gibt es nur den Diff. | Gelöschte Funktionen erscheinen als Hunk-Segment ohne Namen. |

## Technik

| # | Annahme / Vereinfachung |
|---|---|
| C1 | Keine Datenbank: **eine JSON-Datei pro Aspekt** und PR, atomar geschrieben, bei jedem Request neu gelesen. Reicht für eine Person und ihre Agenten. |
| C2 | GitHub-Zugriff mit `java.net.http` und Jackson-Bäumen statt REST-Client-Generator: wenig Magie, Native-tauglich. |
| C3 | Das Angular-UI wird ins Quarkus-Jar bzw. Native-Binary eingebettet (`META-INF/resources`) und mit Hash-Routing (`#/github/owner/repo/12/flow/flow-1`) betrieben – keine Server-Routen nötig. |
| C4 | Keine Authentifizierung der lokalen API. Sie ist nur auf `127.0.0.1` erreichbar (in Docker auf `127.0.0.1:8080` gemappt). |
| C5 | Tests: der PoC wurde gegen die echten Test-PRs geprüft (siehe [06-test-prs.md](06-test-prs.md)); es gibt nur wenige Unit-Tests für Parser und Analyse. |
