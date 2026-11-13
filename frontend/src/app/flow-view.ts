import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { Flow, FlowStep, Segment } from './api';
import { CodeBlock } from './code-block';
import { Icon, SymbolBadge } from './icon';
import { ReviewStore } from './review.store';

interface Node {
  step: FlowStep;
  segment: Segment;
  x: number;
  y: number;
}

interface Connector {
  d: string;
  from: string;
  to: string;
}

const NODE_W = 236;
const NODE_H = 46;
const COL_GAP = 56;
const ROW_GAP = 12;
const INDENT = 28;

/**
 * A data flow: a call-tree diagram on top, below the code of each step in call order. Call sites inside
 * the code are connected to the step they call with lines in the left margin.
 */
@Component({
  selector: 'app-flow-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CodeBlock, Icon, SymbolBadge],
  templateUrl: './flow-view.html',
  styleUrl: './flow-view.scss',
})
export class FlowView {
  protected readonly store = inject(ReviewStore);
  readonly flow = input.required<Flow>();

  private readonly cards = viewChild.required<ElementRef<HTMLElement>>('cards');
  protected readonly connectors = signal<Connector[]>([]);
  protected readonly railHeight = signal(0);
  protected readonly hovered = signal<string | null>(null);
  protected readonly indent = INDENT;

  protected readonly nodes = computed<Node[]>(() => {
    const segs = this.store.segments();
    return this.flow().steps
      .filter((s) => segs.has(s.segmentId))
      .map((step, row) => ({
        step,
        segment: segs.get(step.segmentId)!,
        x: step.depth * (NODE_W + COL_GAP),
        y: row * (NODE_H + ROW_GAP),
      }));
  });

  protected readonly diagram = computed(() => {
    const nodes = this.nodes();
    const byId = new Map(nodes.map((n) => [n.step.segmentId, n]));
    const edges = this.flow().edges
      .filter((e) => byId.has(e.from) && byId.has(e.to))
      .map((e) => {
        const a = byId.get(e.from)!;
        const b = byId.get(e.to)!;
        const x1 = a.x + NODE_W;
        const y1 = a.y + NODE_H / 2;
        const x2 = b.x;
        const y2 = b.y + NODE_H / 2;
        const back = x2 <= x1;
        const d = back
          ? `M ${x1} ${y1} C ${x1 + 40} ${y1}, ${x1 + 40} ${y2}, ${b.x + NODE_W} ${y2}`
          : `M ${x1} ${y1} C ${x1 + COL_GAP / 2} ${y1}, ${x2 - COL_GAP / 2} ${y2}, ${x2 - 4} ${y2}`;
        return { d, from: e.from, to: e.to, back };
      });
    const width = Math.max(...nodes.map((n) => n.x + NODE_W), 0) + 48;
    const height = Math.max(...nodes.map((n) => n.y + NODE_H), 0) + 4;
    return { edges, width, height, nodeW: NODE_W, nodeH: NODE_H };
  });

  protected readonly stepOf = computed(() => {
    const map: Record<string, number> = {};
    this.nodes().forEach((n, i) => (map[n.step.segmentId] = i + 1));
    return map;
  });

  protected readonly progress = computed(() => this.store.progressOf(this.flow().steps.map((s) => s.segmentId)));

  constructor() {
    // Lay out the connectors once the cards are rendered (and again when they change size).
    afterRenderEffect((onCleanup) => {
      this.nodes();
      this.store.ok();
      this.store.comments();
      this.store.collapseDone();
      const host = this.cards().nativeElement;
      const observer = new ResizeObserver(() => this.layoutConnectors());
      observer.observe(host);
      this.layoutConnectors();
      onCleanup(() => observer.disconnect());
    });
    afterRenderEffect(() => {
      const target = this.store.focusSegment();
      if (target && this.stepOf()[target]) {
        this.scrollTo(target);
        this.store.focusSegment.set(null);
      }
    });
  }

  protected calledBy(node: Node): string | null {
    const parent = node.step.parentSegmentId;
    if (!parent) {
      return null;
    }
    const edge = this.flow().edges.find((e) => e.from === parent && e.to === node.step.segmentId);
    const caller = this.store.segments().get(parent);
    const name = caller?.symbol ?? caller?.path ?? parent;
    return `aufgerufen von ${this.stepOf()[parent]} ${shortName(name)}${edge?.line ? ' · Zeile ' + edge.line : ''}`;
  }

  protected shortName(name: string | null): string {
    return shortName(name ?? '');
  }

  /** Method/function name only; the class goes into the second line. */
  protected nodeName(seg: Segment): string {
    const name = seg.symbol ?? this.fileOf(seg);
    return name.substring(name.lastIndexOf('.') + 1);
  }

  protected nodeHint(seg: Segment): string {
    const sym = seg.symbol ?? '';
    const container = sym.includes('.') ? sym.substring(0, sym.lastIndexOf('.')) + ' · ' : '';
    return container + this.fileOf(seg);
  }

  protected fileOf(seg: Segment): string {
    return seg.path.substring(seg.path.lastIndexOf('/') + 1);
  }

  protected nodeProgress(id: string): { ok: number; total: number } {
    return this.store.progressOf([id]);
  }

  protected markFlow(): void {
    this.store.toggle(this.store.linesOf(this.flow().steps.map((s) => s.segmentId)), `flow:${this.flow().id}`);
  }

  protected scrollTo(segmentId: string): void {
    const el = this.cards().nativeElement.querySelector<HTMLElement>(`app-code-block[data-segment="${segmentId}"]`);
    if (!el) {
      this.store.reveal(segmentId);
      return;
    }
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el.classList.remove('flash');
    void el.offsetWidth;
    el.classList.add('flash');
    setTimeout(() => el.classList.remove('flash'), 1400);
  }

  protected scrollToParent(node: Node): void {
    if (node.step.parentSegmentId) {
      this.scrollTo(node.step.parentSegmentId);
    }
  }

  /** Draws, in the left margin, one line per call: from the call-site line to the header of the called step. */
  private layoutConnectors(): void {
    const host = this.cards().nativeElement;
    const top = host.getBoundingClientRect().top;
    const result: Connector[] = [];
    const lanes = new Map<string, number>();
    for (const edge of this.flow().edges) {
      const fromEl = host.querySelector<HTMLElement>(`app-code-block[data-segment="${edge.from}"]`);
      const toEl = host.querySelector<HTMLElement>(`app-code-block[data-segment="${edge.to}"]`);
      if (!fromEl || !toEl) {
        continue;
      }
      const lineEl = edge.line != null ? fromEl.querySelector<HTMLElement>(`.row[data-new="${edge.line}"]`) : null;
      const fromRect = (lineEl ?? fromEl.querySelector<HTMLElement>('.head') ?? fromEl).getBoundingClientRect();
      const toHead = (toEl.querySelector<HTMLElement>('.head') ?? toEl).getBoundingClientRect();
      const fromX = fromEl.getBoundingClientRect().left - host.getBoundingClientRect().left;
      const toX = toEl.getBoundingClientRect().left - host.getBoundingClientRect().left;
      const y1 = fromRect.top - top + fromRect.height / 2;
      const y2 = toHead.top - top + toHead.height / 2;
      const lane = lanes.get(edge.from) ?? lanes.size;
      lanes.set(edge.from, lane);
      const x = 6 + (lane % 4) * 5;
      const r = 6;
      const dir = y2 > y1 ? 1 : -1;
      const d = `M ${fromX} ${y1} H ${x + r} Q ${x} ${y1}, ${x} ${y1 + r * dir} V ${y2 - r * dir} Q ${x} ${y2}, ${x + r} ${y2} H ${toX - 2}`;
      result.push({ d, from: edge.from, to: edge.to });
    }
    this.railHeight.set(host.scrollHeight);
    this.connectors.set(result);
  }
}

function shortName(name: string): string {
  const parts = name.split('.');
  return parts.length > 1 && parts[parts.length - 1] ? parts.slice(-2).join('.') : name;
}
