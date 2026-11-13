import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { Segment } from './api';
import { Icon, SymbolBadge } from './icon';
import { ReviewStore } from './review.store';

interface CoverRow {
  segment: Segment;
  direct: boolean;
  line: number | null;
  via: string | null;
  done: boolean;
}

/**
 * For each test case: the components it is about and the changed code it reaches (statically: calls from
 * the test code, directly or through other changed functions). Every entry jumps to the code.
 */
@Component({
  selector: 'app-test-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, SymbolBadge],
  template: `
    <section class="box">
      <header>
        <app-icon name="test" [size]="15" />
        <b>Testübersicht</b>
        <span class="hint">{{ rows().length }} {{ rows().length === 1 ? 'Testfall' : 'Testfälle' }} · statisch ermittelt aus den Aufrufen im Testcode</span>
      </header>
      @for (t of rows(); track t.id) {
        <div class="test">
          <button type="button" class="name" (click)="testClick.emit(t.id)" title="Zum Testcode">
            <app-symbol-badge kind="test" /> {{ t.name }}
          </button>
          <span class="lbl">Komponenten</span>
          <div class="vals">
            @for (s of t.subjects; track s.name) {
              <button type="button" class="subject" [disabled]="!s.path" (click)="s.path && openFile(s.path)"
                      [title]="(s.how === 'name' ? 'Aus dem Namen der Testdatei' : 'Der Test ruft geänderten Code darin auf') + (s.path ? ' · ' + s.path : '')">
                <app-icon [name]="s.path ? 'file' : 'info'" [size]="12" /> {{ s.name }}
                @if (!s.inPr) {
                  <em>nicht im PR geändert</em>
                }
              </button>
            } @empty {
              <span class="none">keine erkannt</span>
            }
          </div>
          <span class="lbl">Abgedeckt</span>
          <div class="vals">
            @for (c of t.covers; track c.segment.id) {
              <button type="button" class="cover" [class.indirect]="!c.direct" [class.done]="c.done" (click)="store.reveal(c.segment.id)"
                      [title]="c.segment.path + ' – zum Code springen'">
                <app-symbol-badge [kind]="c.segment.symbolKind" />
                <span class="sym">{{ c.segment.symbol ?? fileOf(c.segment.path) }}</span>
                <span class="where">{{ fileOf(c.segment.path) }}:{{ range(c.segment) }}</span>
                <span class="how">{{ c.direct ? 'direkt' + (c.line ? ' · Testzeile ' + c.line : '') : 'über ' + c.via }}</span>
                @if (c.done) {
                  <app-icon name="check" [size]="12" class="ok" />
                }
              </button>
            } @empty {
              <span class="none">Keine geänderte Codestelle erreicht – der Test ruft nur unveränderten Code auf.</span>
            }
          </div>
        </div>
      }
    </section>
  `,
  styles: `
    .box { border: 1px solid var(--border); border-radius: 8px; background: var(--bg-panel-2); margin-bottom: 16px; overflow: hidden; }
    header { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: var(--bg-panel); border-bottom: 1px solid var(--border); color: #c793e0; }
    header b { color: var(--fg); font-weight: 600; }
    .hint { color: var(--fg-dim); font-size: 11.5px; }
    .test { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 6px 10px; padding: 10px 12px; border-bottom: 1px solid var(--border); }
    .test:last-child { border-bottom: 0; }
    .name { grid-column: 1 / -1; justify-self: start; display: inline-flex; align-items: center; gap: 6px; border: 0; background: none; padding: 0;
      color: var(--fg); font: 600 12.5px var(--font-code); cursor: pointer; text-align: left; }
    .name:hover { color: var(--accent-fg); }
    .lbl { color: var(--fg-dim); font-size: 11.5px; padding-top: 3px; }
    .vals { display: flex; flex-wrap: wrap; gap: 6px; min-width: 0; }
    .subject, .cover { display: inline-flex; align-items: center; gap: 5px; max-width: 100%; padding: 2px 8px; border: 1px solid var(--border-strong);
      border-radius: 5px; background: var(--bg-editor); color: var(--fg); font: inherit; font-size: 12px; cursor: pointer; white-space: nowrap; overflow: hidden; }
    .subject:hover:not(:disabled), .cover:hover { border-color: var(--accent); }
    .subject:disabled { cursor: default; color: var(--fg-muted); }
    .subject em { font-style: normal; color: var(--fg-dim); font-size: 11px; }
    .cover.indirect { border-style: dashed; }
    .cover.done { border-color: var(--ok-border); }
    .sym { font-family: var(--font-code); font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; }
    .where { color: var(--fg-dim); font-size: 11px; }
    .how { color: var(--fg-muted); font-size: 11px; padding-left: 4px; border-left: 1px solid var(--border); }
    .ok { color: var(--ok); }
    .none { color: var(--fg-dim); font-size: 12px; padding-top: 2px; }
  `,
})
export class TestOverview {
  protected readonly store = inject(ReviewStore);
  readonly testIds = input.required<string[]>();
  readonly testClick = output<string>();

  protected readonly rows = computed(() => {
    const segs = this.store.segments();
    const tests = this.store.tests();
    return this.testIds()
      .map((id) => tests.get(id))
      .filter((t) => !!t)
      .map((t) => ({
        id: t.segmentId,
        name: t.name,
        subjects: t.subjects,
        covers: t.covers
          .filter((c) => segs.has(c.segmentId))
          .map((c): CoverRow => {
            const segment = segs.get(c.segmentId)!;
            const via = c.via ? segs.get(c.via) : null;
            const p = this.store.progressOf([{ segmentId: segment.id, place: null }]);
            return { segment, direct: c.direct, line: c.line, via: via ? shortName(via) : null, done: p.total > 0 && p.ok === p.total };
          }),
      }));
  });

  protected openFile(path: string): void {
    this.store.select({ kind: 'file', path });
  }

  protected fileOf(path: string): string {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  protected range(s: Segment): string {
    if (s.startLine && s.endLine) {
      return s.startLine === s.endLine ? `${s.startLine}` : `${s.startLine}–${s.endLine}`;
    }
    return s.lineKeys[0]?.substring(1) ?? '';
  }
}

function shortName(s: Segment): string {
  const name = s.symbol ?? s.path.substring(s.path.lastIndexOf('/') + 1);
  return name.substring(name.lastIndexOf('.') + 1);
}
