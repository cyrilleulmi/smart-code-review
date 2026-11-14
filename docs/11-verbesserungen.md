# Verbesserungsanalyse

Stand: `main` nach PR #1 (0.5.0 + Unreleased). Jeder Punkt nennt einen **Beleg** im Code oder eine Messung, die **Auswirkung**, einen **Vorschlag**, den **Aufwand** (S ≤ 1 Tag, M ≤ 1 Woche, L > 1 Woche) und die **Priorität**:

* **P1**: vor dem nächsten Release angehen, das Risiko ist konkret.
* **P2**: in den nächsten Releases.
* **P3**: wenn es passt.

Neue Funktionen stehen in [07-roadmap.md](07-roadmap.md); hier geht es um Qualität, Struktur, Robustheit, Sicherheit und Betrieb des Bestehenden. Die Anforderungs-IDs (`FR-…`, `NFR-…`) beziehen sich auf [10-anforderungen.md](10-anforderungen.md).

## Übersicht

| ID | Thema | Kurz | Aufwand | Prio |
|---|---|---|---|---|
| V-01 | Qualitätssicherung | Keine CI: Tests laufen erst im Release-Build | S | P1 |
| V-02 | Qualitätssicherung | Provider ohne Vertragstests gegen aufgezeichnete Antworten | M | P1 |
| V-03 | Qualitätssicherung | `ReviewService` und REST-API ohne Tests | M | P2 |
| V-04 | Qualitätssicherung | Frontend fast ohne Tests | M | P2 |
| V-05 | Qualitätssicherung | Abhängigkeiten werden nicht automatisch aktualisiert | S | P2 |
| V-10 | Struktur | `ReviewService` hat zu viele Aufgaben | M | P2 |
| V-11 | Struktur | `Analyzer` ist mit 1305 Zeilen die grösste Klasse | L | P3 |
| V-12 | Struktur | `review.store.ts` bündelt den ganzen UI-Zustand | M | P3 |
| V-13 | Struktur | Akzeptanzregeln doppelt (Java und TypeScript) | S–M | P2 |
| V-14 | Struktur | `ProviderException` auch für Nicht-Provider-Fehler | S | P3 |
| V-20 | Sicherheit | Lokale API ohne Host-/Origin-Prüfung (DNS-Rebinding) | S | P1 |
| V-21 | Sicherheit | Pfad-Sicherheit ohne Test | S | P2 |
| V-30 | Robustheit | Antworten auf Markierungen können sich überholen | S | P1 |
| V-31 | Robustheit | Gleichzeitiges Schreiben von `review.json` durch Agent und Tool | M | P2 |
| V-32 | Robustheit | Sperre pro PR wird während Netzwerkzugriffen gehalten | M | P2 |
| V-33 | Robustheit | GitHub-Kommentare nur die ersten 100 pro Art | S | P1 |
| V-34 | Robustheit | Kein Umgang mit Rate-Limits | S–M | P2 |
| V-35 | Fachlich | Gelöschte Zeilen nach einem Merge des Basis-Branches ohne Commit | M | P3 |
| V-40 | Performance | Grosse JSON-Dateien werden bei jedem Request neu gelesen | M | P2 |
| V-41 | Performance | UI rendert alle Blöcke einer Kategorie auf einmal | M | P2 |
| V-42 | Performance | Quelltexte eines PRs: ein Request pro Datei | S–M | P3 |
| V-50 | Betrieb | Datenverzeichnis wächst unbegrenzt | S | P3 |
| V-51 | Betrieb | Docker-Image nur für amd64 | S | P2 |
| V-52 | Betrieb | Keine Beschreibung der eigenen API (OpenAPI) | S | P2 |
| V-60 | Doku | Doku und Code driften auseinander | S | P2 |

## Qualitätssicherung

### V-01 Keine CI (P1, S)

* **Beleg:** Kein `.github/workflows/` im Repo. Tests laufen nur lokal (`./mvnw test`) und im Docker-Build (`Dockerfile`: `./mvnw -B -q -Dnative package`), also erst beim Release.
* **Auswirkung:** PRs werden ohne grünen Build gemergt; PR #1 wurde gemergt, ohne dass `./mvnw test` je gelaufen war. Fehler zeigen sich erst bei `release.sh`, dann unter Zeitdruck.
* **Vorschlag:** GitHub-Actions-Workflow für jeden PR: `./mvnw -B test` (JVM, schnell), `npm ci && npm run build && npm test`. Native-Build nur auf `main` oder nachts. Branch-Schutz auf `main` mit Pflicht-Check.

### V-02 Provider ohne Vertragstests (P1, M)

* **Beleg:** Für `GitHubProvider` (379 Zeilen) und `GitLabProvider` (529 Zeilen) gibt es nur `ProviderUrlTest` (URL-Erkennung). Die Abbildung der JSON-Antworten auf `Snapshot`, `Commit`, `Blame`, `Comment` ist ungetestet. GitHub-Blame (GraphQL) und alle GitLab-Teile von Commits, Blame und Repository-Modus wurden nie live geprüft (siehe [10-anforderungen.md](10-anforderungen.md#14-rückverfolgbarkeit-und-lücken)).
* **Auswirkung:** API-Änderungen der Hosts oder Randfälle (z. B. `parent_ids` fehlt bei GitLab) fallen erst bei Benutzer:innen auf.
* **Vorschlag:** Antworten einmal aufzeichnen (JSON-Dateien unter `src/test/resources/github|gitlab/`), `JsonHttp` im Test durch einen Stub ersetzen, der sie zurückgibt. Je ein Test pro Methode von `ReviewProvider`. Optional ein manueller «Live-Smoke»-Test mit Token.

### V-03 `ReviewService` und REST-API ohne Tests (P2, M)

* **Beleg:** Keine Testklasse für `ReviewService` oder `ReviewResource`. Die Invalidierung (`schemaVersion`, `rulesHash`, `headSha`, `Attribution.VERSION`), das Zusammenführen von `ok`/`okAt` beim Schreiben und der Fortschritt sind nur indirekt über `LineAcceptance` abgedeckt.
* **Vorschlag:** `ReviewService` mit Fake-Provider und temporärem Datenverzeichnis testen (Öffnen, zweites Öffnen ohne Netz, Regeländerung → Neuberechnung, Markieren an mehreren Orten → `review.json`). Ein `@QuarkusTest` pro Endpunkt-Gruppe für Statuscodes und `{"message"}`.

### V-04 Frontend fast ohne Tests (P2, M)

* **Beleg:** Einzige Spec-Datei `acceptance.spec.ts` (5 Tests). `review.store.ts` (825 Zeilen) mit Hash-Routing, Commit-Filter und Fortschritt ist ungetestet.
* **Vorschlag:** Vitest ist eingerichtet (`npm test`). Zuerst die reinen Funktionen testen (`parseHash`/`formatHash`, `progressOf` mit Commit-Filter, `slotsFor`), dann ein paar Komponententests (`CodeBlock`: Häkchen, abgeblendete Zeilen, Blame-Spalte).

### V-05 Keine automatischen Abhängigkeits-Updates (P2, S)

* **Beleg:** Weder Renovate noch Dependabot konfiguriert. Quarkus, Angular, highlight.js und das Mandrel-Image werden nur von Hand aktualisiert.
* **Vorschlag:** Renovate (oder Dependabot) für Maven, npm und Dockerfile; zusammen mit V-01 werden Updates automatisch geprüft.

## Struktur und Wartbarkeit

### V-10 `ReviewService` hat zu viele Aufgaben (P2, M)

* **Beleg:** 686 Zeilen mit acht Verantwortungen: Provider-Auswahl und PR-Liste, Snapshot holen (PR und Repository), Quelltexte, Struktur, Analyse-Cache, Markieren und Fortschritt, Kommentare, Commits und Blame, Regeln und Einstellungen.
* **Auswirkung:** Jede neue Funktion landet hier; Sperren und Caching sind schwer zu überblicken (siehe V-32).
* **Vorschlag:** Aufteilen entlang der Dateien im Datenverzeichnis: `SnapshotService` (snapshot, sources, structure), `AnalysisService` (analysis.json, Regeln), `ReviewStateService` (review.json, Fortschritt), `CommitService` (commits.json, blame/), `CommentService`. `ReviewService` bleibt als dünne Fassade für `ReviewResource`. Das Verhalten bleibt gleich; vorher V-03 umsetzen.

### V-11 `Analyzer` ist sehr gross (P3, L)

* **Beleg:** 1305 Zeilen, rund 50 Methoden; Aufrufkanten, REST-Kanten, Flüsse, Tests, Komponenten und Anzeige in einer Klasse. Die Kategorien sind bereits als Module ausgelagert.
* **Vorschlag:** Die Pipeline-Schritte aus [09-architektur-ueberblick.md](09-architektur-ueberblick.md#7-analyse-pipeline) als eigene Klassen mit klarer Ein- und Ausgabe: `Segmenter`, `CallGraph` (inkl. Import-Prüfung), `HttpLinker`, `FlowBuilder`, `TestLinker`, `ComponentBuilder`, `Renderer`. Vorher die Ausgabe für die Test-PRs als Golden Files festhalten, damit ein Umbau beweisbar nichts ändert (`analysis.json` vorher = nachher).

### V-12 `review.store.ts` bündelt den ganzen UI-Zustand (P3, M)

* **Beleg:** 825 Zeilen: PR-Liste, Bundle, Auswahl und Routing, Markieren, Kommentare, Commits, Blame, Einstellungen.
* **Vorschlag:** Kleinere Stores mit klaren Zuständigkeiten (`PrStore`, `SelectionStore`, `MarksStore`, `CommitStore`), die sich über Signals verbinden. Hash-Routing in eine eigene, getestete Datei.

### V-13 Akzeptanzregeln doppelt (P2, S–M)

* **Beleg:** `review/LineAcceptance.java` und `frontend/src/app/acceptance.ts` müssen dieselben Regeln umsetzen (AD-07). Die Tests sind verschieden formuliert (9 bzw. 5 Fälle).
* **Auswirkung:** Kleine Abweichungen zeigen im UI einen anderen Zustand, als der Server speichert. Der Server korrigiert danach, das UI «springt».
* **Vorschlag:** Eine gemeinsame Fall-Tabelle (JSON unter `docs/` oder `testdata/`), die beide Testsuiten einlesen. So bleibt die Logik doppelt, die Erwartungen aber einmalig.

### V-14 `ProviderException` für alles (P3, S)

* **Beleg:** Auch «Kommentar ist leer» (400), «PR zuerst öffnen» (409), «Unbekannter Provider» (404) und «rules.json ist ungültig» (500) sind `ProviderException`.
* **Vorschlag:** Eine allgemeine `ApiException(status, message)`, von der `ProviderException` erbt. Rein kosmetisch für die Lesbarkeit; die API bleibt gleich.

## Sicherheit

### V-20 Lokale API ohne Host-/Origin-Prüfung (P1, S)

* **Beleg:** Die API hört auf `127.0.0.1` und hat keine Authentifizierung (C4). Es gibt keine Prüfung von `Host`- oder `Origin`-Header.
* **Auswirkung:** Eine bösartige Website kann über **DNS-Rebinding** ihren Namen auf `127.0.0.1` zeigen lassen und dann aus dem Browser heraus die API aufrufen: Markierungen ändern, PR-Inhalte lesen und vor allem **Kommentare im Namen der Person veröffentlichen** (der Token liegt im Backend). Normales CORS schützt hier nicht, weil die Anfrage scheinbar vom selben Ursprung kommt.
* **Vorschlag:** Filter, der nur `Host: localhost[:port]`, `127.0.0.1[:port]` (und konfigurierbare Namen) zulässt und bei schreibenden Methoden einen passenden `Origin` verlangt. Ein paar Zeilen in einem `@ServerRequestFilter`.

### V-21 Pfad-Sicherheit ohne Test (P2, S)

* **Beleg:** `DataStore.resolveInside` verhindert Ausbrüche aus dem Datenverzeichnis über Dateinamen aus PRs (`../../`), ist aber nicht getestet (NFR-10).
* **Vorschlag:** Unit-Test mit `../`, absoluten Pfaden, Windows-Trennzeichen und Symlinks.

## Robustheit

### V-30 Antworten auf Markierungen können sich überholen (P1, S)

* **Beleg:** `ReviewStore.mark` zeigt die Markierung sofort und übernimmt danach **jede** Server-Antwort mit `applyState`. Es gibt keine Reihenfolge-Prüfung.
* **Auswirkung:** Bei schnellen Klicks (oder Shift-Bereichen nacheinander) kann eine ältere Antwort nach einer neueren ankommen und eine gerade gesetzte Markierung im UI wieder entfernen, bis zum nächsten Fokuswechsel.
* **Vorschlag:** Laufende Nummer pro Anfrage; nur die Antwort der neuesten Anfrage anwenden. Oder Anfragen pro PR serialisieren (`concatMap`).

### V-31 Gleichzeitiges Schreiben von `review.json` (P2, M)

* **Beleg:** Die Sperre pro PR (`synchronized(lock(ref))`) gilt nur im Prozess. Agenten dürfen `review.json` laut [04-persistenz-und-agenten.md](04-persistenz-und-agenten.md) auch direkt schreiben (lesen → ändern → atomar ersetzen).
* **Auswirkung:** Schreiben Agent und UI fast gleichzeitig, gewinnt der letzte; die Markierungen des anderen gehen ohne Meldung verloren (Lost Update).
* **Vorschlag:** Feld `revision` in `review.json` (wird bei jedem Schreiben erhöht); das Backend verweigert das Überschreiben einer neueren Revision bzw. führt zusammen. Für Agenten die REST-API weiter als Standard empfehlen. Optional `FileChannel.lock()` beim Schreiben.

### V-32 Sperre während Netzwerkzugriffen (P2, M)

* **Beleg:** `bundle()`, `reload()` und `reanalyse()` holen innerhalb von `synchronized(lock(ref))` Snapshot und Quelltexte vom Host.
* **Auswirkung:** Während ein grosser PR (oder ein ganzes Repository) geladen wird, warten Markierungen und Statusabfragen desselben PRs, im schlimmsten Fall bis zu den HTTP-Timeouts (30 s pro Request).
* **Vorschlag:** Netzwerkteil ausserhalb der Sperre ausführen und nur das Schreiben der Dateien sperren. Doppelte gleichzeitige Ladevorgänge über eine `ConcurrentHashMap<key, CompletableFuture>` zusammenlegen.

### V-33 GitHub-Kommentare nur die ersten 100 (P1, S)

* **Beleg:** `GitHubProvider.comments` liest `/pulls/{n}/comments?per_page=100` und `/issues/{n}/comments?per_page=100` je **eine** Seite. GitLab paginiert (`/discussions?…&page=`).
* **Auswirkung:** Bei lebhaften PRs fehlen Kommentare im Tool, ohne Hinweis.
* **Vorschlag:** Wie bei den Dateien paginieren, bis eine Seite weniger als 100 Einträge hat.

### V-34 Kein Umgang mit Rate-Limits (P2, S–M)

* **Beleg:** `JsonHttp` wirft bei jedem Status ≥ 300 sofort eine Ausnahme. `Retry-After` und `X-RateLimit-Remaining` werden nicht ausgewertet; es gibt keinen Wiederholungsversuch. Ein ganzes Repository mit 50 Commits braucht über 50 Requests, ein PR mit vielen Dateien einen pro Datei.
* **Vorschlag:** Bei 429 bzw. GitHubs 403 mit `X-RateLimit-Remaining: 0` die Wartezeit lesen und einmal wiederholen oder eine klare Meldung «Rate-Limit bis hh:mm» geben. Verbleibende Anfragen im Log ausgeben.

### V-35 Gelöschte Zeilen nach Merge des Basis-Branches (P3, M)

* **Beleg:** Nach dem Merge von `main` in den Branch blieben bei PR #1 22 von 85 gelöschten Zeilen ohne Commit (A11). Ursache: Die alten Zeilennummern des PR-Diffs beziehen sich auf die neue Merge-Base, das Nachspielen beginnt an der alten.
* **Vorschlag:** Beim Nachspielen die Diffs der hineingemergten Basis-Commits als «Basis-Änderung» mitführen (sie sind über den zweiten Parent des Merge-Commits erreichbar) oder für gelöschte Zeilen ohne Treffer über den Zeilentext unter den entfernten Zeilen desselben Pfads suchen.

## Performance

### V-40 Grosse JSON-Dateien bei jedem Request (P2, M)

* **Beleg:** Jeder Aufruf liest die Dateien neu (C1). `mark()` liest pro Markierung `analysis.json` (für die Orte) und `settings.json`; `GET /api/prs` liest pro PR mit lokalen Daten `analysis.json` und `review.json` für den Fortschritt. `rules()` liest und hasht die Regeldatei bei jedem Aufruf (fünf Aufrufstellen in `ReviewService`, bei einer Analyse bis zu drei Mal). Im Repository-Modus ist das Bundle rund 3 MB gross.
* **Auswirkung:** Spürbare Latenz beim Markieren grosser Reviews; unnötige Last bei vielen offenen PRs.
* **Vorschlag:** Kleiner Cache im Speicher pro Datei, gültig solange `lastModifiedTime` und Grösse gleich bleiben. So bleiben Änderungen durch Agenten sofort sichtbar (Q3), aber unveränderte Dateien werden nicht neu geparst.

### V-41 UI rendert alle Blöcke auf einmal (P2, M)

* **Beleg:** Im Repository-Modus braucht die Kategorie «Einzelne Änderungen» (155 Blöcke) rund 1,5 s; jeder Block hebt den ganzen Code mit highlight.js hervor.
* **Vorschlag:** Blöcke erst rendern, wenn sie in die Nähe des sichtbaren Bereichs kommen (`IntersectionObserver` oder `@defer (on viewport)`), Highlighting pro Datei zwischenspeichern.

### V-42 Quelltexte eines PRs: ein Request pro Datei (P3, S–M)

* **Beleg:** `fetchSources` holt jede analysierte Datei einzeln (`fileContent`, max. 8 parallel). Der Repository-Modus zeigt, dass ein Archiv des Heads in einem Request kommt (2 MB für das eigene Repo).
* **Vorschlag:** Ab einer Schwelle (z. B. 50 Dateien) das Archiv des Heads laden und die nötigen Dateien daraus nehmen. Weniger Requests, weniger Rate-Limit-Risiko (V-34).

## Betrieb

### V-50 Datenverzeichnis wächst unbegrenzt (P3, S)

* **Beleg:** Jeder geöffnete PR hinterlässt `snapshot.json`, `sources/`, `analysis.json` usw.; es gibt keinen Endpunkt und keine Regel zum Aufräumen geschlossener PRs.
* **Vorschlag:** `DELETE …/{number}` (behält optional `review.json`) und im UI «Lokale Daten aufräumen» für geschlossene PRs älter als n Tage.

### V-51 Docker-Image nur für amd64 (P2, S)

* **Beleg:** README: «Natives Executable (Mandrel, linux/amd64)».
* **Auswirkung:** Auf Apple-Silicon-Macs läuft das Image emuliert und langsam (der Vorteil des Native-Starts geht verloren).
* **Vorschlag:** `docker buildx` mit `linux/amd64,linux/arm64` (Mandrel-Builder gibt es für beide) in `release.sh` bzw. in der CI.

### V-52 Keine Beschreibung der eigenen API (P2, S)

* **Beleg:** `quarkus-smallrye-openapi` ist nicht eingebunden; die API ist nur als Tabelle in [03-architektur.md](03-architektur.md#rest-api) beschrieben.
* **Vorschlag:** Extension einbinden (`/q/openapi`, Swagger UI im Dev-Mode). Agenten können die API dann selbst entdecken, und die Doku-Tabelle lässt sich daraus prüfen. Grundlage für einen späteren MCP-Server.

## Dokumentation

### V-60 Doku und Code driften (P2, S)

* **Beleg:** Viel Wissen steht nur in den Docs (Annahmen B1–B22, Dateiformat). Beispiel: Das Diagramm in `03-architektur.md` zeigte bis zu diesem Stand nur GitHub als Code-Host.
* **Vorschlag:** In der PR-Vorlage eine Checkliste «Doku / Changelog / Anforderungen nachgeführt?»; die Anforderungstabelle in [10-anforderungen.md](10-anforderungen.md) bei jedem Feature ergänzen; mit V-52 die API-Tabelle gegen `/q/openapi` prüfen.

## Empfohlene Reihenfolge

1. **Sofort (P1, alle S):** V-01 CI, V-20 Host-/Origin-Filter, V-30 Reihenfolge der Markier-Antworten, V-33 Kommentar-Paginierung. Danach V-02 Provider-Vertragstests (M).
2. **Nächste Releases:** V-03/V-04 Tests, dann V-10 Aufteilung von `ReviewService` und V-32 Sperren; V-40/V-41 Performance; V-51 arm64; V-52 OpenAPI.
3. **Später:** V-11, V-12 Umbau grosser Klassen (mit Golden Files abgesichert), V-35, V-42, V-50.
