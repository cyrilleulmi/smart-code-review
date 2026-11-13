import { ChangeDetectionStrategy, Component as NgComponent, computed, inject, signal } from '@angular/core';
import { Component } from './api';
import { Icon } from './icon';
import { ReviewStore } from './review.store';

interface Box {
  c: Component;
  x: number;
  y: number;
  file: string;
}

/** Columns from the outside in: who calls whom, roughly left to right. */
const AREAS: { id: string; label: string }[] = [
  { id: 'test', label: 'Tests' },
  { id: 'frontend', label: 'Frontend' },
  { id: 'adapter-in', label: 'Adapter (eingehend)' },
  { id: 'api', label: 'Schnittstelle (API)' },
  { id: 'usecase', label: 'Use Cases' },
  { id: 'application', label: 'Application' },
  { id: 'logic', label: 'Logik' },
  { id: 'adapter-out', label: 'Adapter (ausgehend)' },
  { id: 'data', label: 'Daten & Infrastruktur' },
  { id: 'domain', label: 'Domäne' },
];
const W = 196;
const H = 44;
const GAP = 64;
const ROW = 10;
const HEAD = 28;

/**
 * Overview of the whole change as components (classes / modules, template and styles included) in columns by
 * layer, with the calls between them; REST calls from the frontend into the backend are drawn dashed.
 * The focus button of a component shows only it and everything it depends on.
 */
@NgComponent({
  selector: 'app-component-diagram',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="bar">
      <span class="count">{{ boxes().length }} von {{ all().length }} Komponenten · {{ lines().length }} Verbindungen</span>
      @if (focusName(); as f) {
        <span class="focus-info"><app-icon name="focus" [size]="12" /> Fokus: <b>{{ f }}</b> und ihre Abhängigkeiten</span>
        <button type="button" class="ftool" (click)="focus.set(null)"><app-icon name="close" [size]="12" /> Fokus aufheben</button>
      }
      <span class="spacer"></span>
      @if (hasTests()) {
        <button type="button" class="ftool" [class.on]="showTests()" (click)="showTests.set(!showTests())"><app-icon name="test" [size]="12" /> Tests</button>
      }
      <button type="button" class="ftool" [class.on]="hideIsolated()" (click)="hideIsolated.set(!hideIsolated())"
              title="Komponenten ohne Aufrufbeziehung ausblenden"><app-icon name="eyeOff" [size]="12" /> Ohne Verbindung ausblenden</button>
      <span class="legend"><i class="l-call"></i> Aufruf <i class="l-http"></i> REST/HTTP</span>
    </div>
    @if (boxes().length === 0) {
      <p class="empty">Keine Komponenten mit Code-Änderungen.</p>
    } @else {
      <div class="scroll">
        <div class="canvas" [style.width.px]="size().w" [style.height.px]="size().h">
          @for (col of columns(); track col.id) {
            <div class="col-head" [style.left.px]="col.x" [style.width.px]="w">{{ col.label }}</div>
          }
          <svg [attr.width]="size().w" [attr.height]="size().h" class="edges">
            <defs>
              <marker id="cd-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M0 0 L8 4 L0 8 z" fill="currentColor" />
              </marker>
            </defs>
            @for (l of lines(); track l.key) {
              <path [attr.d]="l.d" class="edge" [class.http]="l.http" [class.hot]="hovered() === l.from || hovered() === l.to"
                    [class.dim]="!!hovered() && hovered() !== l.from && hovered() !== l.to" [style.stroke-width]="l.width" marker-end="url(#cd-arrow)">
                <title>{{ l.title }}</title>
              </path>
            }
          </svg>
          @for (b of boxes(); track b.c.id) {
            @let p = progress(b.c);
            <div class="node" role="button" tabindex="0" [style.left.px]="b.x" [style.top.px]="b.y" [style.width.px]="w" [style.height.px]="h"
                 [class.done]="p.total > 0 && p.ok === p.total" [class.root]="focus() === b.c.id" [class.dim]="!!hovered() && !related(b.c.id)"
                 [title]="b.c.paths.join('\\n')" (mouseenter)="hovered.set(b.c.id)" (mouseleave)="hovered.set(null)"
                 (click)="open(b)" (keydown.enter)="open(b)">
              <span class="txt">
                <span class="name">{{ b.c.name }}</span>
                <span class="file">{{ b.file }}</span>
              </span>
              @if (p.total > 0 && p.ok === p.total) {
                <app-icon name="check" [size]="13" class="ok" />
              } @else {
                <span class="cnt">{{ p.ok }}/{{ p.total }}</span>
              }
              <button type="button" class="focus" [class.on]="focus() === b.c.id" (click)="toggleFocus(b.c.id, $event)"
                      [title]="focus() === b.c.id ? 'Fokus aufheben' : 'Nur diese Komponente und alles, wovon sie abhängt, zeigen'">
                <app-icon name="focus" [size]="12" />
              </button>
            </div>
          }
        </div>
      </div>
    }
  `,
  styles: `
    :host { display: block; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-panel-2); overflow: hidden; }
    .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 6px 10px; background: var(--bg-panel); border-bottom: 1px solid var(--border); font-size: 12px; }
    .count { color: var(--fg-muted); }
    .spacer { flex: 1; }
    .focus-info { display: inline-flex; align-items: center; gap: 5px; color: var(--accent-fg); }
    .focus-info b { color: var(--fg); }
    .legend { display: inline-flex; align-items: center; gap: 6px; color: var(--fg-dim); font-size: 11px; margin-left: 6px; }
    .legend i { display: inline-block; width: 18px; border-top: 2px solid #6f737a; }
    .legend i.l-http { border-top: 2px dashed #c793e0; }
    .empty { color: var(--fg-muted); padding: 12px; margin: 0; }
    .scroll { overflow: auto; max-height: 560px; }
    .canvas { position: relative; margin: 10px 14px 14px; }
    .col-head { position: absolute; top: 0; height: 20px; color: var(--fg-dim); font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; text-align: center; border-bottom: 1px solid var(--border); }
    .edges { position: absolute; inset: 0; overflow: visible; color: #6f737a; }
    .edge { fill: none; stroke: #5b6170; stroke-width: 1.5; transition: stroke 0.15s, opacity 0.15s; }
    .edge.http { stroke: #c793e0; stroke-dasharray: 5 3; }
    .edge.hot { stroke: var(--accent); }
    .edge.http.hot { stroke: #e0b3f5; }
    .edge.dim { opacity: 0.25; }
    .node { position: absolute; display: flex; align-items: center; gap: 6px; padding: 0 6px 0 10px; box-sizing: border-box; background: var(--bg-editor);
      border: 1px solid #43454a; border-radius: 8px; cursor: pointer; transition: border-color 0.15s, opacity 0.15s; }
    .node:hover { border-color: var(--accent); }
    .node.done { border-color: var(--ok-border); background: var(--ok-head); }
    .node.root { border-color: var(--accent); box-shadow: 0 0 0 2px rgba(53, 116, 240, 0.3); }
    .node.dim { opacity: 0.4; }
    .txt { display: flex; flex-direction: column; min-width: 0; flex: 1; }
    .name { font: 600 12px var(--font-code); color: var(--fg); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .file { font-size: 10.5px; color: var(--fg-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cnt { font-size: 10.5px; color: var(--fg-muted); font-variant-numeric: tabular-nums; }
    .ok { color: var(--ok); }
    .focus { display: grid; place-items: center; width: 20px; height: 20px; padding: 0; border: 0; border-radius: 4px; background: transparent; color: var(--fg-dim); cursor: pointer; flex: none; }
    .focus:hover { background: var(--bg-hover); color: var(--fg); }
    .focus.on { color: var(--accent-fg); background: rgba(53, 116, 240, 0.18); }
  `,
})
export class ComponentDiagram {
  protected readonly store = inject(ReviewStore);
  protected readonly w = W;
  protected readonly h = H;
  protected readonly showTests = signal(false);
  protected readonly hideIsolated = signal(false);
  protected readonly focus = signal<string | null>(null);
  protected readonly hovered = signal<string | null>(null);

  protected readonly all = computed(() => this.store.bundle()?.analysis.components ?? []);
  protected readonly hasTests = computed(() => this.all().some((c) => c.area === 'test'));
  private readonly allEdges = computed(() => this.store.bundle()?.analysis.componentEdges ?? []);

  /** Components shown: without tests (unless switched on), focus = the component plus everything it reaches. */
  private readonly shown = computed(() => {
    let comps = this.all().filter((c) => this.showTests() || c.area !== 'test');
    const ids = new Set(comps.map((c) => c.id));
    const edges = this.allEdges().filter((e) => ids.has(e.from) && ids.has(e.to));
    const root = this.focus();
    if (root && ids.has(root)) {
      const reach = new Set([root]);
      let grew = true;
      while (grew) {
        grew = false;
        for (const e of edges) {
          if (reach.has(e.from) && !reach.has(e.to)) {
            reach.add(e.to);
            grew = true;
          }
        }
      }
      comps = comps.filter((c) => reach.has(c.id));
    } else if (this.hideIsolated()) {
      const connected = new Set(edges.flatMap((e) => [e.from, e.to]));
      comps = comps.filter((c) => connected.has(c.id));
    }
    const shownIds = new Set(comps.map((c) => c.id));
    return { comps, edges: edges.filter((e) => shownIds.has(e.from) && shownIds.has(e.to)) };
  });

  protected readonly columns = computed(() =>
    AREAS.filter((a) => this.shown().comps.some((c) => c.area === a.id)).map((a, i) => ({ ...a, x: i * (W + GAP) })),
  );

  /** Column layout; inside a column, components are ordered by the average row of their callers (fewer crossings). */
  protected readonly boxes = computed<Box[]>(() => {
    const { comps, edges } = this.shown();
    const row = new Map<string, number>();
    const boxes: Box[] = [];
    for (const col of this.columns()) {
      const members = comps.filter((c) => c.area === col.id).sort((a, b) => a.name.localeCompare(b.name));
      const weight = (c: Component) => {
        const rows = edges.filter((e) => e.to === c.id && row.has(e.from)).map((e) => row.get(e.from)!);
        return rows.length ? rows.reduce((x, y) => x + y, 0) / rows.length : Number.MAX_SAFE_INTEGER;
      };
      members.sort((a, b) => weight(a) - weight(b));
      members.forEach((c, i) => {
        row.set(c.id, i);
        const code = c.paths.find((p) => !/\.(html?|s?css|less)$/.test(p)) ?? c.paths[0];
        const file = c.paths.map((p) => p.substring(p.lastIndexOf('/') + 1)).join(' · ');
        boxes.push({ c: { ...c, paths: [code, ...c.paths.filter((p) => p !== code)] }, x: col.x, y: HEAD + i * (H + ROW), file });
      });
    }
    return boxes;
  });

  protected readonly size = computed(() => ({
    w: Math.max(...this.boxes().map((b) => b.x + W), 0) + 30,
    h: Math.max(...this.boxes().map((b) => b.y + H), 0) + 6,
  }));

  protected readonly lines = computed(() => {
    const byId = new Map(this.boxes().map((b) => [b.c.id, b]));
    return this.shown().edges
      .filter((e) => byId.has(e.from) && byId.has(e.to))
      .map((e) => {
        const a = byId.get(e.from)!;
        const b = byId.get(e.to)!;
        const y1 = a.y + H / 2;
        const y2 = b.y + H / 2;
        let d: string;
        if (b.x > a.x) {
          const x1 = a.x + W;
          d = `M ${x1} ${y1} C ${x1 + GAP / 2} ${y1}, ${b.x - GAP / 2} ${y2}, ${b.x - 3} ${y2}`;
        } else if (b.x < a.x) {
          const x1 = a.x;
          d = `M ${x1} ${y1} C ${x1 - GAP / 2} ${y1}, ${b.x + W + GAP / 2} ${y2}, ${b.x + W + 3} ${y2}`;
        } else {
          const x1 = a.x + W;
          d = `M ${x1} ${y1} C ${x1 + 34} ${y1}, ${x1 + 34} ${y2}, ${x1 + 3} ${y2}`;
        }
        const width = 1.3 + Math.min(2.5, Math.log2(e.calls));
        const title = `${a.c.name} → ${b.c.name}: ${e.calls} ${e.calls === 1 ? 'Aufruf' : 'Aufrufe'}${e.http ? ' (über REST/HTTP)' : ''}`;
        return { key: e.from + '>' + e.to, d, http: e.http, from: e.from, to: e.to, width, title };
      });
  });

  protected readonly focusName = computed(() => this.all().find((c) => c.id === this.focus())?.name ?? null);

  protected progress(c: Component) {
    return this.store.progressOf(c.segmentIds.map((segmentId) => ({ segmentId, place: null })));
  }

  protected related(id: string): boolean {
    const h = this.hovered();
    return !h || h === id || this.shown().edges.some((e) => (e.from === h && e.to === id) || (e.to === h && e.from === id));
  }

  protected toggleFocus(id: string, event: Event): void {
    event.stopPropagation();
    this.focus.set(this.focus() === id ? null : id);
  }

  protected open(b: Box): void {
    this.store.select({ kind: 'file', path: b.c.paths[0] });
  }
}
