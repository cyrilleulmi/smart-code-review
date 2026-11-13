import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, inject, input } from '@angular/core';
import { CodeBlock } from './code-block';
import { Icon } from './icon';
import { ReviewStore } from './review.store';

/** A list of code blocks: a category, a group (e.g. "Übersetzungen") or a file. */
@Component({
  selector: 'app-group-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CodeBlock, Icon],
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
      <span class="progress-text">{{ progress().ok }}/{{ progress().total }} Zeilen ok</span>
      <button type="button" class="btn" [class.success]="done()" (click)="markAll()">
        <app-icon name="check" [size]="14" /> Alles als ok markieren
      </button>
    </div>
    @if (segmentIds().length === 0) {
      <p class="empty">Keine Änderungen in dieser Auswahl.</p>
    }
    <div class="blocks">
      @for (id of segmentIds(); track id) {
        @if (store.segments().get(id); as seg) {
          <app-code-block [segment]="seg" (refClick)="openRef($event)" />
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
  readonly segmentIds = input.required<string[]>();
  readonly scope = input.required<string>();

  protected readonly progress = computed(() => this.store.progressOf(this.segmentIds()));
  protected readonly done = computed(() => this.progress().total > 0 && this.progress().ok === this.progress().total);

  constructor() {
    afterRenderEffect(() => {
      const target = this.store.focusSegment();
      if (!target) {
        return;
      }
      const el = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>(`app-code-block[data-segment="${target}"]`);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        this.store.focusSegment.set(null);
      }
    });
  }

  protected markAll(): void {
    this.store.toggle(this.store.linesOf(this.segmentIds()), this.scope());
  }

  /** A call-site chip outside a flow view: show the called code in its flow (or file). */
  protected openRef(segmentId: string): void {
    this.store.reveal(segmentId);
  }
}
