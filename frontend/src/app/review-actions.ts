import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { PrStatus } from './api';
import { Icon } from './icon';
import { serverText, t } from './i18n';
import { ReviewStore } from './review.store';

/** The approvals of a PR in one phrase: who approved and how many approvals are still missing. */
export function approvalText(s: Pick<PrStatus, 'approvedBy' | 'approvalsLeft'>): string {
  const by = s.approvedBy.length ? t('Genehmigt von {names}', { names: s.approvedBy.join(', ') }) : t('Noch nicht genehmigt');
  if (s.approvalsLeft <= 0) {
    return by;
  }
  return `${by} · ${s.approvalsLeft === 1 ? t('1 Genehmigung fehlt') : t('{n} Genehmigungen fehlen', { n: s.approvalsLeft })}`;
}

/**
 * Finishing a review on the code host: approve the PR (or revoke the approval) and merge it. Shows where the PR
 * stands there - approvals, pipeline, mergeable or why not. Both act at once on the host; merging asks first.
 * Neither is offered while the host has commits the tool has not loaded.
 */
@Component({
  selector: 'app-review-actions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    @if (store.status(); as s) {
      <div class="row">
        @if (s.state === 'merged') {
          <span class="state merged"><app-icon name="check" [size]="13" /> {{ t('Gemergt') }}</span>
        } @else if (s.state === 'closed') {
          <span class="state">{{ t('Geschlossen') }}</span>
        } @else {
          <button type="button" class="btn" [class.primary]="!s.approvedByMe" [disabled]="busy() || !s.canApprove || (outdated() && !s.approvedByMe)"
                  (click)="approve(!s.approvedByMe)" [title]="s.canApprove ? '' : t('Du kannst hier nicht genehmigen (z. B. den eigenen Merge Request)')">
            <app-icon name="check" [size]="13" /> {{ s.approvedByMe ? t('Genehmigung zurückziehen') : t('Genehmigen') }}
          </button>
          <button type="button" class="btn" [disabled]="busy() || !s.canMerge || !s.mergeable || outdated()" (click)="askMerge(s)"
                  [title]="!s.canMerge ? t('Dir fehlt das Recht zu mergen') : s.blocker ? serverText(s.blocker) : ''">
            <app-icon name="branch" [size]="13" /> {{ t('Mergen …') }}
          </button>
        }
        <button type="button" class="icon-btn" (click)="refresh()" [title]="t('Stand von {host} neu laden', { host: store.provider().label })"
                [attr.aria-label]="t('Stand von {host} neu laden', { host: store.provider().label })"><app-icon name="refresh" [size]="13" /></button>
        <span class="facts">
          <span>{{ approvals() }}</span>
          @if (s.pipeline) {
            <span>{{ t('Pipeline: {status}', { status: s.pipeline }) }}</span>
          }
          @if (s.state === 'open') {
            @if (s.mergeable) {
              <span class="good">{{ t('Lässt sich mergen') }}</span>
            } @else if (s.blocker) {
              <span class="blocked">{{ serverText(s.blocker) }}</span>
            }
          }
        </span>
      </div>
      @if (outdated() && s.state === 'open') {
        <p class="note warn">
          {{ t('Auf {host} gibt es neue Commits ({sha}), die hier noch nicht geladen sind. Genehmigen und mergen geht erst danach.', { host: store.provider().label, sha: s.headSha.substring(0, 7) }) }}
          <button type="button" class="link" (click)="store.reload()">{{ t('Neu laden') }}</button>
        </p>
      } @else if (s.state === 'open' && openLines() > 0) {
        <p class="note">{{ t('{open} von {total} Zeilen sind noch nicht als ok markiert.', { open: openLines(), total: store.totalProgress().total }) }}</p>
      }
      @if (confirming() && s.state === 'open') {
        <div class="confirm" role="group" [attr.aria-label]="t('Mergen')">
          <b>{{ t('{pr} in «{branch}» mergen? Das lässt sich nicht rückgängig machen.', { pr: store.provider().sigil + pr()?.number, branch: pr()?.baseRef }) }}</b>
          <label><input type="checkbox" [checked]="squash()" (change)="squash.set($any($event.target).checked)" /> {{ t('Commits zusammenfassen (Squash)') }}</label>
          <label><input type="checkbox" [checked]="removeBranch()" (change)="removeBranch.set($any($event.target).checked)" /> {{ t('Quell-Branch «{branch}» löschen', { branch: pr()?.headRef }) }}</label>
          <span class="buttons">
            <button type="button" class="btn primary" [disabled]="busy()" (click)="merge()">{{ busy() ? t('Merge läuft …') : t('Jetzt mergen') }}</button>
            <button type="button" class="btn" [disabled]="busy()" (click)="confirming.set(false)">{{ t('Abbrechen') }}</button>
          </span>
        </div>
      }
    }
  `,
  styles: `
    :host {
      display: block;
      margin-top: 14px;
      font-size: 12.5px;
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }

    .state {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 9px;
      border: 1px solid var(--border-strong);
      border-radius: 5px;
      color: var(--fg-muted);
      font-weight: 600;

      &.merged {
        border-color: var(--ok-border);
        color: var(--ok);
      }
    }

    .facts {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 14px;
      color: var(--fg-muted);

      .good {
        color: var(--ok);
      }

      .blocked {
        color: var(--warn);
      }
    }

    .icon-btn {
      display: grid;
      place-items: center;
      width: 24px;
      height: 24px;
      padding: 0;
      border: 0;
      border-radius: 5px;
      background: none;
      color: var(--fg-muted);
      cursor: pointer;

      &:hover {
        background: var(--bg-hover);
        color: var(--fg);
      }
    }

    .note {
      margin: 8px 0 0;
      color: var(--fg-muted);

      &.warn {
        color: var(--warn);
      }
    }

    .link {
      padding: 0;
      border: 0;
      background: none;
      color: var(--accent-fg);
      font: inherit;
      cursor: pointer;
    }

    .confirm {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-top: 10px;
      padding: 10px 12px;
      border: 1px solid var(--border-strong);
      border-radius: 8px;
      background: var(--bg-editor);

      label {
        display: flex;
        align-items: center;
        gap: 6px;
        color: var(--fg-muted);
        cursor: pointer;
      }

      input {
        margin: 0;
        accent-color: var(--accent);
      }

      .buttons {
        display: flex;
        gap: 8px;
        margin-top: 4px;
      }
    }
  `,
})
export class ReviewActions {
  protected readonly store = inject(ReviewStore);
  protected readonly t = t;
  protected readonly serverText = serverText;

  protected readonly busy = signal(false);
  protected readonly confirming = signal(false);
  protected readonly squash = signal(false);
  protected readonly removeBranch = signal(false);

  protected readonly pr = computed(() => this.store.bundle()?.pr ?? null);
  protected readonly approvals = computed(() => {
    const s = this.store.status();
    return s ? approvalText(s) : '';
  });
  /** The host has commits the tool has not loaded: what would be approved or merged is not what is shown. */
  protected readonly outdated = computed(() => {
    const s = this.store.status();
    const pr = this.pr();
    return !!s && !!pr && !!s.headSha && s.headSha !== pr.headSha;
  });
  protected readonly openLines = computed(() => {
    const p = this.store.totalProgress();
    return p.total - p.ok;
  });

  /** Reads the state again, with the comments: resolved threads decide whether it can be merged. */
  protected refresh(): void {
    this.store.loadComments();
    this.store.loadStatus();
  }

  protected approve(approved: boolean): void {
    this.busy.set(true);
    this.store.approve(approved, () => this.busy.set(false));
  }

  /** Opens the question before merging, with the options set as the PR is. */
  protected askMerge(s: PrStatus): void {
    this.squash.set(s.squash);
    this.removeBranch.set(s.removeSourceBranch);
    this.confirming.set(true);
  }

  protected merge(): void {
    this.busy.set(true);
    this.store.merge({ squash: this.squash(), removeSourceBranch: this.removeBranch() }, (ok) => {
      this.busy.set(false);
      if (ok) {
        this.confirming.set(false);
      }
    });
  }
}
