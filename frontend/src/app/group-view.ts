import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, inject, input, linkedSignal } from '@angular/core';
import { CodeBlock } from './code-block';
import { Icon } from './icon';
import { ReviewStore, Slot } from './review.store';
import { TestOverview } from './test-overview';

/** A list of code blocks: a category, a group (e.g. "Übersetzungen") or a file. */
@Component({
  selector: 'app-group-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CodeBlock, Icon, TestOverview],
  template: `
    <div class="view-head">
      <app-icon [name]="icon()" [size]="18" />
      <div class="titles">
        <h1>{{ title() }}</h1>
        @if (description()) {
          <p>{{ description() }}</p>
        }
      </div>
      <span class="spacer"></span>
      <span class="progress-text" [title]="fileView() ? 'Eine Zeile zählt, wenn sie an allen ihren Orten ok ist' : ''">
        {{ progress().ok }}/{{ progress().total }} Zeilen ok</span>
      <button type="button" class="btn" [class.success]="done()" (click)="markAll()"
              [title]="fileView() ? 'Markiert die Zeilen an allen Orten, an denen sie vorkommen' : ''">
        <app-icon name="check" [size]="14" /> {{ fileView() ? 'Alles überall als ok markieren' : 'Alles als ok markieren' }}
      </button>
    </div>
    @if (slots().length === 0) {
      <p class="empty">Keine Änderungen in dieser Auswahl.</p>
    }
    @if (testIds().length && stepIndex() === null) {
      <app-test-overview [testIds]="testIds()" (testClick)="scrollTo($event)" />
    }
    @if (slots().length > 1) {
      <div class="focus-bar">
        @if (stepIndex() === null) {
          <button type="button" class="ftool" (click)="startSteps()" title="Immer nur einen Block zeigen, mit «Ok & weiter»">
            <app-icon name="play" [size]="13" /> Einzeln durchgehen
          </button>
          <button type="button" class="ftool" (click)="store.setCollapsedAll(true)"><app-icon name="collapse" [size]="13" /> Alle einklappen</button>
          <button type="button" class="ftool" (click)="store.setCollapsedAll(false)"><app-icon name="expand" [size]="13" /> Alle ausklappen</button>
          <span class="spacer"></span>
          <span class="hint">Klick auf einen Blocktitel klappt ihn ein oder aus</span>
        } @else {
          @let i = stepIndex()!;
          <span class="step-info">Block <b>{{ i + 1 }}</b> von {{ slots().length }}</span>
          <button type="button" class="ftool" [disabled]="i === 0" (click)="step(-1)"><app-icon name="arrowLeft" [size]="13" /> Zurück</button>
          <button type="button" class="ftool primary" (click)="okAndNext()"><app-icon name="check" [size]="13" /> Ok &amp; weiter</button>
          <button type="button" class="ftool" [disabled]="i === slots().length - 1" (click)="step(1)">Weiter <app-icon name="arrowRight" [size]="13" /></button>
          <span class="spacer"></span>
          <button type="button" class="ftool" (click)="stepIndex.set(null)"><app-icon name="close" [size]="13" /> Beenden</button>
        }
      </div>
    }
    <div class="blocks">
      @for (slot of shown(); track slot.segmentId + '@' + slot.place) {
        @if (store.segments().get(slot.segmentId); as seg) {
          <app-code-block [segment]="seg" [place]="slot.place" (refClick)="openRef($event)" />
        }
      }
    </div>
  `,
  styles: `
    .blocks { display: flex; flex-direction: column; gap: 14px; padding-bottom: 40px; }
    .empty { color: var(--fg-muted); }
  `,
})
export class GroupView {
  protected readonly store = inject(ReviewStore);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly title = input.required<string>();
  readonly description = input<string | null>(null);
  readonly icon = input('code');
  readonly slots = input.required<Slot[]>();
  readonly scope = input.required<string>();
  /** File view: marks apply to all places of a line. */
  readonly fileView = input(false);

  protected readonly progress = computed(() => this.store.progressOf(this.slots()));
  /** Step-by-step mode: index of the only block shown, null = off. Reset when the content changes. */
  protected readonly stepIndex = linkedSignal<Slot[], number | null>({ source: () => this.slots(), computation: () => null });
  protected readonly shown = computed(() => {
    const i = this.stepIndex();
    return i == null ? this.slots() : this.slots().slice(i, i + 1);
  });
  protected readonly done = computed(() => this.progress().total > 0 && this.progress().ok === this.progress().total);
  /** Test cases among the shown blocks (they get an overview of what they exercise). */
  protected readonly testIds = computed(() => {
    const tests = this.store.tests();
    return [...new Set(this.slots().map((s) => s.segmentId))].filter((id) => tests.has(id));
  });

  constructor() {
    afterRenderEffect(() => {
      const target = this.store.focusSegment();
      if (target && this.scrollTo(target)) {
        this.store.focusSegment.set(null);
      }
    });
  }

  protected scrollTo(segmentId: string): boolean {
    const index = this.slots().findIndex((s) => s.segmentId === segmentId);
    if (this.stepIndex() != null && index >= 0 && this.stepIndex() !== index) {
      this.stepIndex.set(index);
      this.store.focusSegment.set(segmentId);
      return false;
    }
    const el = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>(`app-code-block[data-segment="${segmentId}"]`);
    if (!el) {
      return false;
    }
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el.classList.remove('flash');
    void el.offsetWidth;
    el.classList.add('flash');
    setTimeout(() => el.classList.remove('flash'), 1400);
    return true;
  }

  protected startSteps(): void {
    const firstOpen = this.slots().findIndex((s) => {
      const p = this.store.progressOf([s]);
      return p.ok < p.total;
    });
    this.stepIndex.set(Math.max(0, firstOpen));
  }

  protected step(delta: number): void {
    this.stepIndex.set(Math.min(this.slots().length - 1, Math.max(0, (this.stepIndex() ?? 0) + delta)));
  }

  protected okAndNext(): void {
    const slot = this.slots()[this.stepIndex() ?? 0];
    if (!slot) return;
    this.store.mark(this.store.linesOf([slot]), true, `segment:${slot.segmentId}`);
    if ((this.stepIndex() ?? 0) < this.slots().length - 1) {
      this.step(1);
    } else {
      this.stepIndex.set(null);
      this.store.notify('Alle Blöcke durchgearbeitet');
    }
  }

  protected markAll(): void {
    this.store.toggle(this.store.linesOf(this.slots()), this.scope());
  }

  /** A call-site chip outside a flow view: show the called code at its place (flow or group). */
  protected openRef(segmentId: string): void {
    if (!this.slots().some((s) => s.segmentId === segmentId)) {
      this.store.reveal(segmentId);
    } else if (!this.scrollTo(segmentId)) {
      this.store.focusSegment.set(segmentId);
    }
  }
}
