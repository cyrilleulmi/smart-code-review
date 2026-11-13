# Test-PRs

Für den PoC wurden in drei eigenen Repos **Draft-PRs** erstellt. Alle tragen **[TEST-MR]** im Titel, einen Warnhinweis in der Beschreibung und gehören **nicht gemergt**. Nach den Tests einfach schliessen und den Branch löschen (siehe unten).

| PR | Repo / Basis | Inhalt | Was es testet |
|---|---|---|---|
| [spongecoach#13](https://github.com/cyrilleulmi/spongecoach/pull/13) | `master` | Trikotnummer für Spieler: Migration, Entity, DTOs, Resource, Angular-Service, Komponente, Template, SCSS | Fullstack Quarkus + Angular; Java- und TS-Symbole; DTO-/REST-Quick-wins; zwei Flüsse |
| [camperquiz#1](https://github.com/cyrilleulmi/camperquiz/pull/1) | `master` | Joker pro Team | Vanilla-JS **ohne Module** (globale Funktionen über Dateien hinweg) |
| [stv-bero-website#2](https://github.com/cyrilleulmi/stv-bero-website/pull/2) | `wave-1/new-site` | Anmeldeschluss für Anlässe | Payload-Collection, generierte Typen, Next.js-Komponente, Helfer, Konstante im Flow |
| [stv-bero-website#1](https://github.com/cyrilleulmi/stv-bero-website/pull/1) | `main` | (bestehender, echter PR «Wave 1») | Stresstest: 294 Dateien, ca. 900 Segmente |

## Ergebnisse (Stand PoC)

**spongecoach#13**

* Quick wins → DTOs & Typen: `PlayerDetailDto`, `PlayerSummaryDto`, `PlayerUpdateRequest`, Feld in `Player`, `player.model.ts`
* Quick wins → Banale REST-Aufrufe: `PlayerApiService.setJerseyNumber`
* Flow 1: `update → validateJerseyNumber → isJerseyNumberTaken`
* Flow 2: `saveJerseyNumber → setJerseyNumber`
* Einzelne Änderungen: Migration, Template, Klassenfelder in `PlayerView`, Konstante in `PlayerResource`

**camperquiz#1**

* Flow: `renderPunkte → renderJokerButtons → canUseJoker / activateJoker`
* Flow: `confirmPunkte → applyJoker`
* Quick wins: README; Styles: CSS

**stv-bero-website#2**

* Quick wins: `payload-types.ts` (generiert), `Events`-Feld, `EventSummary`, `RegistrationStatus`, Imports
* Flow: `EventList → registrationStatus → CLOSING_SOON_MS` plus `DEADLINE_STYLE`, `formatDeadline`

**stv-bero-website#1:** Snapshot rund 15 s (194 Quelltexte), Analyse rund 5 s, 58 Flüsse. Einzelne Flüsse sind bei so einem «ganze neue Website»-PR noch gross (bis 78 Schritte), siehe Annahme B4.

## GitLab

Für GitLab gibt es (noch) kein eigenes Konto mit Test-MRs. Getestet wurde **ohne Token** gegen öffentliche Merge Requests auf gitlab.com:

| MR | Ergebnis |
|---|---|
| [gitlab-org/gitlab-vscode-extension!3511](https://gitlab.com/gitlab-org/gitlab-vscode-extension/-/merge_requests/3511) | TypeScript, 20 Dateien: Quick wins (Typen, Imports), 7 Flüsse, z. B. `parseHunk → lines → allLinesForNewSide → …`, Tests separat |
| [gitlab-org/cli!4347](https://gitlab.com/gitlab-org/cli/-/merge_requests/4347) | Go, 5 Dateien: ein Fluss über zwei Pakete (`options.run`/`runCreateProject` → `config.LocalConfigDir` → `revParseDir`), `_test.go` als Tests |

Nicht getestet (braucht Token): MR-Liste, Kommentieren. Mit Token: `GITLAB_TOKEN` setzen, Tool neu starten.

## Aufräumen

```bash
gh pr close 13 -R cyrilleulmi/spongecoach      --delete-branch
gh pr close 1  -R cyrilleulmi/camperquiz       --delete-branch
gh pr close 2  -R cyrilleulmi/stv-bero-website --delete-branch
```
