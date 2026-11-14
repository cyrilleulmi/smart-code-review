import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ownerLabel } from './api';
import { CommitList } from './commit-list';
import { ComponentDiagram } from './component-diagram';
import { Icon } from './icon';
import { ReviewStore } from './review.store';

/** Start page of an opened PR: description, the suggested review order, general comments. */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, ComponentDiagram, CommitList],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class Overview {
  protected readonly store = inject(ReviewStore);
  protected readonly ownerLabel = ownerLabel;
  protected readonly draft = signal('');
  protected readonly sending = signal(false);

  protected readonly pr = computed(() => this.store.bundle()!.pr);
  protected readonly allCategories = computed(() =>
    this.store.bundle()!.analysis.categories.map((c) => {
      const ids = c.groups.flatMap((g) => g.segmentIds);
      const tests = c.id === 'tests' ? this.store.bundle()!.analysis.tests ?? [] : [];
      const covered = new Set(tests.flatMap((t) => t.covers.map((x) => x.segmentId))).size;
      return {
        ...c,
        segmentCount: new Set(ids).size,
        progress: this.store.progressOf(this.store.slotsFor({ kind: 'category', id: c.id })),
        stats: c.id === 'tests' && tests.length
          ? `${tests.length} Testfälle · erreichen ${covered} geänderte Codestellen`
          : `${c.groups.length} ${c.id === 'flows' ? 'Flows' : 'Gruppen'} · ${new Set(ids).size} Blöcke`,
        enabled: !this.store.disabledCategories().has(c.id),
      };
    }),
  );
  protected readonly generalComments = computed(() => this.store.comments().filter((c) => c.kind === 'issue'));
  protected readonly inlineCount = computed(() => this.store.comments().filter((c) => c.kind === 'review').length);
  /** Switched-on categories in review order; switched-off ones are listed separately. */
  protected readonly categories = computed(() => this.allCategories().filter((c) => c.enabled));
  protected readonly disabled = computed(() => this.allCategories().filter((c) => !c.enabled));
  protected readonly architecture = computed(() => this.store.bundle()!.analysis.architecture);

  protected toggle(id: string, event: Event): void {
    event.stopPropagation();
    this.store.toggleCategory(id);
  }

  /** Blocks that need more than one ok (shown at several places). */
  protected readonly multiPlaced = computed(() => this.store.bundle()!.analysis.segments.filter((s) => (s.places?.length ?? 0) > 1).length);
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
