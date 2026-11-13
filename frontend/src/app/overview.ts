import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Icon } from './icon';
import { ReviewStore } from './review.store';

/** Start page of an opened PR: description, the suggested review order, general comments. */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class Overview {
  protected readonly store = inject(ReviewStore);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);

  protected readonly pr = computed(() => this.store.bundle()!.pr);
  protected readonly categories = computed(() =>
    this.store.bundle()!.analysis.categories.map((c) => {
      const ids = c.groups.flatMap((g) => g.segmentIds);
      return { ...c, segmentCount: new Set(ids).size, progress: this.store.progressOf(ids) };
    }),
  );
  protected readonly generalComments = computed(() => this.store.comments().filter((c) => c.kind === 'issue'));
  protected readonly inlineCount = computed(() => this.store.comments().filter((c) => c.kind === 'review').length);
  protected readonly log = computed(() => [...this.store.log()].reverse().slice(0, 6));

  protected percent(p: { ok: number; total: number }): number {
    return p.total === 0 ? 0 : Math.round((p.ok / p.total) * 100);
  }

  protected openCategory(id: string): void {
    const cat = this.store.bundle()!.analysis.categories.find((c) => c.id === id);
    if (id === 'flows' && cat?.groups[0]?.flowId) {
      this.store.select({ kind: 'flow', id: cat.groups[0].flowId });
    } else {
      this.store.select({ kind: 'category', id });
    }
  }

  protected input(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  protected send(): void {
    const body = this.draft().trim();
    if (!body) {
      return;
    }
    this.sending.set(true);
    this.store.postComment({ body }, (ok) => {
      this.sending.set(false);
      if (ok) {
        this.draft.set('');
      }
    });
  }

  protected time(iso: string): string {
    return new Date(iso).toLocaleString('de-CH', { dateStyle: 'short', timeStyle: 'short' });
  }
}
