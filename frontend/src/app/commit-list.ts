import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Commit } from './api';
import { Icon } from './icon';
import { ReviewStore } from './review.store';

interface Row {
  commit: Commit;
  no: number;
  lines: number;
  progress: { ok: number; total: number };
  /** merge commit that carries the changes of this side-branch commit */
  via: Commit | null;
}

/**
 * The commits of the PR (oldest first). Clicking one shows only its changes in all views; the ✓ marks all
 * of its lines. Used in the review tool window and on the overview.
 */
@Component({
  selector: 'app-commit-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    @let c = store.commits();
    @if (!c) {
      <p class="note">@if (store.commitsLoading()) {<span class="spinner"></span> Lade Commits …} @else {Keine Commits geladen.}</p>
    } @else if (c.error && !c.commits.length) {
      <p class="note err">Commits nicht verfügbar: {{ c.error }}
        <a href="javascript:void 0" (click)="store.loadCommits()">Erneut versuchen</a></p>
    } @else {
      <div class="list" role="listbox" [class.compact]="compact()">
        <div class="row all" role="option" [class.selected]="!store.commitFilter()" (click)="store.setCommitFilter(null)"
             title="Alle Änderungen des Pull Requests zeigen">
          <app-icon name="layers" [size]="14" class="ico" />
          <span class="title">Alle Commits</span>
          <span class="meta">{{ c.commits.length }}</span>
        </div>
        @for (r of rows(); track r.commit.sha) {
          @let p = r.progress;
          <div class="row" role="option" [class.selected]="store.commitFilter() === r.commit.sha" [class.empty]="r.lines === 0"
               [class.done]="p.total > 0 && p.ok === p.total" (click)="pick(r)" [title]="tooltip(r)">
            <span class="no">{{ r.no }}</span>
            <span class="sha">{{ r.commit.sha.substring(0, 7) }}</span>
            <span class="body">
              <span class="title">{{ r.commit.title }}</span>
              @if (!compact()) {
                <span class="meta">
                  {{ r.commit.author ?? r.commit.authorLogin ?? '?' }} · {{ date(r.commit.date) }}
                  · <span class="add">+{{ r.commit.additions }}</span> <span class="del">−{{ r.commit.deletions }}</span>
                  · {{ r.commit.files.length }} {{ r.commit.files.length === 1 ? 'Datei' : 'Dateien' }}
                  @if (r.via) {
                    · via Merge {{ r.via.sha.substring(0, 7) }}
                  } @else if (r.lines === 0) {
                    · nichts mehr im Diff
                  }
                </span>
              } @else if (r.via) {
                <span class="meta">via {{ r.via.sha.substring(0, 7) }}</span>
              }
            </span>
            @if (p.total > 0) {
              @if (p.ok === p.total) {
                <app-icon name="check" [size]="13" class="okicon" />
              } @else {
                <span class="cnt">{{ p.ok }}/{{ p.total }}</span>
              }
              <button type="button" class="mark" (click)="mark(r, $event)"
                      [title]="p.ok === p.total ? 'Markierung entfernen' : 'Alle Zeilen dieses Commits als ok markieren'">
                <app-icon name="check" [size]="12" />
              </button>
            }
            @if (r.commit.url) {
              <a class="mark" [href]="r.commit.url" target="_blank" rel="noopener" (click)="$event.stopPropagation()"
                 [title]="'Auf ' + store.provider().label + ' öffnen'"><app-icon name="external" [size]="12" /></a>
            }
          </div>
        }
      </div>
      @if (c.error) {
        <p class="note err">Nicht aktualisiert: {{ c.error }}</p>
      }
    }
  `,
  styles: `
    :host { display: block; }
    .note { margin: 10px 14px; color: var(--fg-muted); font-size: 12px; display: flex; align-items: center; gap: 6px; }
    .note.err { color: var(--del-fg); display: block; }
    .list { display: flex; flex-direction: column; padding: 4px 0; }
    .row {
      display: flex; align-items: center; gap: 7px; min-height: 26px; margin: 0 6px; padding: 3px 8px;
      border-radius: 5px; cursor: pointer; font-size: 12.5px;
    }
    .list:not(.compact) .row { min-height: 40px; }
    .row:hover { background: var(--bg-hover); }
    .row.selected { background: var(--bg-selected); }
    .row.empty { opacity: 0.55; }
    .row.done .title { color: var(--fg-muted); }
    .row.all { font-weight: 600; }
    .row.all .title { flex: 1; }
    .ico { color: var(--fg-muted); }
    .no {
      display: inline-grid; place-items: center; min-width: 18px; height: 18px; padding: 0 4px; border-radius: 9px;
      background: var(--bg-hover); color: var(--fg-muted); font-size: 10.5px; font-weight: 600; flex: none;
    }
    .row.selected .no { background: var(--accent); color: var(--fg); }
    .sha { font-family: var(--font-code); font-size: 11.5px; color: var(--accent-fg); flex: none; }
    .body { display: flex; flex-direction: column; min-width: 0; flex: 1; }
    .compact .body { flex-direction: row; gap: 8px; align-items: baseline; }
    .title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--fg); }
    .meta { color: var(--fg-dim); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .compact .meta { flex: none; }
    .add { color: var(--add-fg); }
    .del { color: var(--del-fg); }
    .cnt { font-size: 11px; color: var(--fg-muted); font-variant-numeric: tabular-nums; flex: none; }
    .okicon { color: var(--ok); }
    .mark {
      display: inline-grid; place-items: center; width: 20px; height: 20px; border: 0; border-radius: 4px;
      background: none; color: var(--fg-muted); cursor: pointer; opacity: 0; flex: none;
    }
    .row:hover .mark { opacity: 1; }
    .mark:hover { background: var(--bg-selected-soft); color: var(--fg); }
  `,
})
export class CommitList {
  protected readonly store = inject(ReviewStore);
  /** one line per commit (tool window); otherwise with statistics */
  readonly compact = input(false);

  protected readonly rows = computed<Row[]>(() => {
    const c = this.store.commits();
    const bySha = this.store.commitBySha();
    const counts = this.store.commitLineCount();
    return (c?.commits ?? []).map((commit, i) => {
      const via = c?.mergedVia?.[commit.sha];
      return {
        commit,
        no: i + 1,
        lines: counts.get(commit.sha) ?? 0,
        progress: this.store.commitProgress(commit.sha),
        via: via ? bySha.get(via) ?? null : null,
      };
    });
  });

  protected pick(r: Row): void {
    if (r.via && r.lines === 0) {
      this.store.setCommitFilter(r.via.sha);
      this.store.notify(`Die Änderungen von ${r.commit.sha.substring(0, 7)} stecken im Merge-Commit ${r.via.sha.substring(0, 7)}`);
      return;
    }
    this.store.setCommitFilter(this.store.commitFilter() === r.commit.sha ? null : r.commit.sha);
  }

  protected mark(r: Row, event: Event): void {
    event.stopPropagation();
    this.store.toggle(this.store.commitLines(r.commit.sha), `commit:${r.commit.sha.substring(0, 12)}`);
  }

  protected tooltip(r: Row): string {
    const parts = [r.commit.message.trim(), '', `${r.commit.sha}`, `${r.commit.author ?? ''} · ${this.date(r.commit.date)}`];
    if (r.lines === 0 && !r.via) {
      parts.push('', 'Keine Änderung dieses Commits ist im Diff des Pull Requests sichtbar (später überschrieben oder zurückgenommen).');
    }
    if (r.via) {
      parts.push('', `Kommt über den Merge-Commit ${r.via.sha.substring(0, 7)} in den Branch; seine Zeilen sind dort zugeordnet.`);
    }
    return parts.join('\n');
  }

  protected date(iso: string | null): string {
    return iso ? new Date(iso).toLocaleString('de-CH', { dateStyle: 'short', timeStyle: 'short' }) : '';
  }
}
