import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { PullRequestSummary, ownerLabel, providerInfo } from './api';
import { Icon } from './icon';
import { PrFilter, ReviewStore } from './review.store';

/** The "Pull Requests" tool window: open PRs authored by me or waiting for my review. */
@Component({
  selector: 'app-pr-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="filters">
      @for (f of filters; track f.id) {
        <button type="button" [class.active]="store.prFilter() === f.id" (click)="store.prFilter.set(f.id)">
          {{ f.label }} <span class="badge">{{ count(f.id) }}</span>
        </button>
      }
    </div>
    <div class="search">
      <app-icon name="search" [size]="13" />
      <input type="search" placeholder="Filtern, PR-/MR- oder Repo-URL + Enter" [value]="query()"
             (input)="query.set($any($event.target).value)" (keydown.enter)="enter()" />
    </div>
    @if (isUrl()) {
      <button type="button" class="open-url" (click)="enter()">
        <app-icon name="arrowRight" [size]="13" /> URL öffnen
      </button>
    }
    <div class="list">
      @if (store.prsLoading() && store.prs().length === 0) {
        <p class="empty">Lade Pull Requests …</p>
      }
      @for (pr of visible(); track pr.url) {
        <button type="button" class="item" [class.selected]="isCurrent(pr)" (click)="open(pr)">
          <span class="line1">
            <app-icon [name]="pr.provider" [size]="14" [class]="pr.draft ? 'draft' : 'open'" [title]="info(pr).label" />
            <span class="title">{{ pr.title }}</span>
          </span>
          <span class="line2">
            <span class="repo" [title]="owner(pr) + '/' + pr.repo">{{ pr.repo }}</span>
            {{ pr.number === 0 ? 'ganzes Repository' : info(pr).sigil + pr.number }} · {{ pr.author }}
            @for (r of pr.roles; track r) {
              <span class="role" [class]="'role r-' + r">{{ r === 'authored' ? 'von mir' : 'Review' }}</span>
            }
            @if (pr.progress; as p) {
              <span class="prog" [title]="p.ok + '/' + p.total + ' Zeilen ok'">
                <span class="bar"><span [style.width.%]="p.total ? (p.ok / p.total) * 100 : 0"></span></span>
              </span>
            }
          </span>
        </button>
      } @empty {
        @if (!store.prsLoading()) {
          <p class="empty">Keine offenen Pull/Merge Requests. Ohne Token lassen sich öffentliche per URL öffnen.</p>
        }
      }
    </div>
  `,
  styleUrl: './pr-list.scss',
})
export class PrList {
  protected readonly store = inject(ReviewStore);
  protected readonly query = signal('');
  protected readonly filters: { id: PrFilter; label: string }[] = [
    { id: 'all', label: 'Alle' },
    { id: 'authored', label: 'Von mir' },
    { id: 'review-requested', label: 'Review' },
  ];

  protected readonly isUrl = computed(() => /^https?:\/\//.test(this.query().trim()));

  protected readonly visible = computed(() => {
    if (this.isUrl()) {
      return [];
    }
    const q = this.query().toLowerCase().trim();
    return this.store.filteredPrs().filter((p) => !q || `${ownerLabel(p.owner)}/${p.repo} ${p.title} ${p.number} ${p.provider}`.toLowerCase().includes(q));
  });

  protected info(pr: PullRequestSummary) {
    return providerInfo(pr.provider);
  }

  protected owner(pr: PullRequestSummary): string {
    return ownerLabel(pr.owner);
  }

  protected enter(): void {
    if (this.isUrl()) {
      this.store.openUrl(this.query());
      this.query.set('');
    }
  }

  protected count(f: PrFilter): number {
    return f === 'all' ? this.store.prs().length : this.store.prs().filter((p) => p.roles.includes(f)).length;
  }

  protected isCurrent(pr: PullRequestSummary): boolean {
    const k = this.store.prKey();
    return !!k && k.owner === pr.owner && k.repo === pr.repo && k.number === pr.number;
  }

  protected open(pr: PullRequestSummary): void {
    this.store.open({ provider: pr.provider, owner: pr.owner, repo: pr.repo, number: pr.number });
  }
}
