import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Comment, isFlowView, isTestsView, ownerLabel } from './api';
import { CommitList } from './commit-list';
import { findingSummary, findingTotals } from './commit-findings';
import { expectationTotals, expectationWarning } from './expectations';
import { ComponentDiagram } from './component-diagram';
import { Icon } from './icon';
import { locale, serverText, t } from './i18n';
import { unmetWarning } from './rule-checks';
import { countWithSmells, smellsSummary } from './test-smells';
import { countUnchangedAffected, setupTileText } from './setup-impact';
import { ReviewActions } from './review-actions';
import { ReviewStore } from './review.store';

/** Start page of an opened PR: description, the suggested review order, general comments. */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, ComponentDiagram, CommitList, ReviewActions],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class Overview {
  protected readonly store = inject(ReviewStore);
  protected readonly ownerLabel = ownerLabel;
  protected readonly t = t;
  protected readonly serverText = serverText;
  protected readonly draft = signal('');
  protected readonly sending = signal(false);

  protected readonly pr = computed(() => this.store.bundle()!.pr);
  /** Commit-Bezug der Tests: «⚠ 2 Commit-Hinweise · 4 Infos» next to the commits heading; null without findings. */
  protected readonly commitFindings = computed(() => {
    const findings = this.store.commitFindings();
    const text = findingSummary(findings);
    if (!text) return null;
    const totals = findingTotals(findings);
    return { text, warn: totals.warn > 0, commits: totals.commits };
  });
  protected readonly allCategories = computed(() =>
    this.store.bundle()!.analysis.categories.map((c) => {
      const ids = c.groups.flatMap((g) => g.segmentIds);
      const tests = isTestsView(c) ? this.store.bundle()!.analysis.tests ?? [] : [];
      const covered = new Set(tests.flatMap((x) => x.covers.map((cv) => cv.segmentId))).size;
      return {
        ...c,
        segmentCount: new Set(ids).size,
        progress: this.store.progressOf(this.store.slotsFor({ kind: 'category', id: c.id })),
        stats: tests.length
          ? t(tests.length === 1 ? '{n} Testfall · erreicht {covered} geänderte Codestellen' : '{n} Testfälle · erreichen {covered} geänderte Codestellen',
            { n: tests.length, covered })
          : [
            isFlowView(c) ? t(c.groups.length === 1 ? '{n} Flow' : '{n} Flows', { n: c.groups.length })
              : t(c.groups.length === 1 ? '{n} Gruppe' : '{n} Gruppen', { n: c.groups.length }),
            t(new Set(ids).size === 1 ? '{n} Block' : '{n} Blöcke', { n: new Set(ids).size }),
          ].join(' · '),
        tooltip: [
          c.builds ? t('Aufbau: {text}', { text: serverText(c.builds) }) : '',
          c.divides ? t('Unterteilung: {text}', { text: serverText(c.divides) }) : '',
        ].filter((x) => x).join('\n\n'),
        enabled: !this.store.disabledCategories().has(c.id),
        // tests bent to fit the code: changed/removed/weakened assertions, switched-off tests (Spec-Diff)
        warning: isTestsView(c) ? expectationWarning(expectationTotals(this.store.bundle()!.analysis.specs ?? [])) : null,
        // Erwartungs-Regeln without a test of the expected form
        rules: isTestsView(c) ? unmetWarning(this.store.ruleChecks()) : null,
        // Test-Smells: «3 Tests mit Smells»
        smells: isTestsView(c) ? smellsSummary(countWithSmells(tests)) : null,
        // Wirkung von Setup und Testdaten: unchanged tests whose setup or test data changed
        setup: isTestsView(c) ? setupTileText(countUnchangedAffected(this.store.setupImpacts())) : null,
      };
    }),
  );
  protected readonly generalComments = computed(() => this.store.comments().filter((c) => c.kind === 'issue'));
  protected readonly inlineCount = computed(() => this.store.comments().filter((c) => c.kind === 'review').length);
  /** Threads (inline and general) that can be resolved and are not yet. */
  protected readonly openThreads = computed(
    () => this.store.comments().filter((c) => c.inReplyTo == null && c.resolved === false).length);
  /** Thread whose state is being changed on the code host. */
  protected readonly resolving = signal<string | null>(null);
  /** Switched-on categories in review order; switched-off ones are listed separately. */
  protected readonly categories = computed(() => this.allCategories().filter((c) => c.enabled));
  protected readonly disabled = computed(() => this.allCategories().filter((c) => !c.enabled));
  protected readonly architecture = computed(() => this.store.bundle()!.analysis.architecture);
  /** Coverage of the changed code (static): counts per status; null when no changed function needs tests. */
  protected readonly coverage = computed(() => {
    const list = this.store.bundle()!.analysis.coverage ?? [];
    if (list.length === 0) return null;
    const n = (status: string) => list.filter((c) => c.status === status).length;
    return { total: list.length, direct: n('direct'), indirect: n('indirect'), existing: n('existing'), none: n('none'),
      rules: unmetWarning(this.store.ruleChecks()) };
  });

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

  /** Share of the lines accepted at all their places (the blue part of the ring). */
  protected percentAll(p: { all: number; total: number }): number {
    return p.total === 0 ? 0 : Math.round((p.all / p.total) * 100);
  }

  protected openCategory(id: string): void {
    const cat = this.store.bundle()!.analysis.categories.find((c) => c.id === id);
    if (cat && isFlowView(cat) && cat.groups[0]?.flowId) {
      this.store.select({ kind: 'flow', id: cat.groups[0].flowId });
    } else {
      this.store.select({ kind: 'category', id });
    }
  }

  protected input(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  /** Resolves the thread a general comment starts, or opens it again. */
  protected resolve(c: Comment): void {
    if (!c.thread || c.resolved == null || this.resolving()) {
      return;
    }
    this.resolving.set(c.thread);
    this.store.resolveThread(c.thread, !c.resolved, () => this.resolving.set(null));
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
    return new Date(iso).toLocaleString(locale(), { dateStyle: 'short', timeStyle: 'short' });
  }
}
