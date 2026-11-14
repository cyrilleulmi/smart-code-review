import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { Flow, FlowEdge, FlowStep, Segment } from './api';
import { CodeBlock } from './code-block';
import { Icon, SymbolBadge } from './icon';
import { EDGE_COLORS, areaColor, areaLabel, areaOfSegment, edgeColor, edgeColorIndex } from './look';
import { ReviewStore } from './review.store';

interface Node {
  step: FlowStep;
  segment: Segment;
  /** step number in the whole flow (stays the same while focusing) */
  no: number;
  /** depth relative to the focused step */
  depth: number;
  x: number;
  y: number;
}

interface Connector {
  d: string;
  from: string;
  to: string;
  /** colour index 1..8 of the connection */
  c: number;
  http: boolean;
}

const NODE_W = 236;
const NODE_H = 46;
const COL_GAP = 56;
const ROW_GAP = 12;
/** Indentation of the code blocks per call level (the cards scroll horizontally when it gets deep). */
const INDENT = 56;
/** Horizontal distance between two connector lanes in the left margin. */
const LANE = 11;
const MIN_GUTTER = 32;

/**
 * A data flow: a call-tree diagram on top, below the code of each step in call order. Call sites inside
 * the code are connected to the step they call with lines in the left margin. Steps can be hidden, the view
 * can focus on one branch, hide finished steps, or walk through the steps one by one.
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
  protected readonly railWidth = signal(0);
  /** Left margin of the code blocks: room for one lane per (overlapping) connection. */
  protected readonly gutter = signal(MIN_GUTTER);
  protected readonly markers = Array.from({ length: EDGE_COLORS }, (_, i) => i + 1);
  protected readonly areaColor = areaColor;

  /** Every connection of the flow keeps one colour: "from>to" -> CSS colour. */
  protected readonly edgeColors = computed(() => {
    const map: Record<string, string> = {};
    this.flow().edges.forEach((e, i) => (map[e.from + '>' + e.to] = edgeColor(i)));
    return map;
  });
  protected readonly hovered = signal<string | null>(null);
  protected readonly indent = INDENT;

  /** The flow's place: each step needs an ok here (a step can be part of several flows). */
  protected readonly place = computed(() => 'flows/' + this.flow().id);
  /** Steps hidden by the user (eye / focus). Reset when another flow is shown. */
  protected readonly hidden = linkedSignal<string, ReadonlySet<string>>({ source: () => this.flow().id, computation: () => new Set() });
  /** Step-by-step mode: index of the only step shown, null = off. */
  protected readonly stepIndex = linkedSignal<string, number | null>({ source: () => this.flow().id, computation: () => null });
  protected readonly hideDone = signal(false);
  /** Focused step: only it and everything it calls (directly or further down) are shown, in diagram and code. */
  protected readonly focusRoot = linkedSignal<string, string | null>({ source: () => this.flow().id, computation: () => null });

  /** The steps in this flow that the focused step reaches (all steps without focus). */
  private readonly branch = computed<ReadonlySet<string> | null>(() => {
    const root = this.focusRoot();
    if (!root) return null;
    const result = new Set([root]);
    const edges = this.flow().edges;
    let grew = true;
    while (grew) {
      grew = false;
      for (const e of edges) {
        if (result.has(e.from) && !result.has(e.to)) {
          result.add(e.to);
          grew = true;
        }
      }
    }
    return result;
  });

  protected readonly nodes = computed<Node[]>(() => {
    const segs = this.store.segments();
    const branch = this.branch();
    const steps = this.flow().steps;
    const rootDepth = branch ? steps.find((s) => s.segmentId === this.focusRoot())?.depth ?? 0 : 0;
    return steps
      .map((step, i) => ({ step, no: i + 1 }))
      .filter(({ step }) => segs.has(step.segmentId) && (!branch || branch.has(step.segmentId)))
      .map(({ step, no }, row) => {
        const depth = Math.max(0, step.depth - rootDepth);
        return { step, no, depth, segment: segs.get(step.segmentId)!, x: depth * (NODE_W + COL_GAP), y: row * (NODE_H + ROW_GAP) };
      });
  });

  protected readonly focusName = computed(() => {
    const root = this.focusRoot();
    const seg = root ? this.store.segments().get(root) : null;
    return seg ? this.nodeName(seg) : null;
  });

  protected readonly diagram = computed(() => {
    const nodes = this.nodes();
    const byId = new Map(nodes.map((n) => [n.step.segmentId, n]));
    const edges = this.flow().edges
      .map((e, i) => ({ e, c: edgeColorIndex(i) }))
      .filter(({ e }) => byId.has(e.from) && byId.has(e.to))
      .map(({ e, c }) => {
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
        const http = e.kind === 'http';
        return { d, from: e.from, to: e.to, back, http, via: e.via, c, lx: (x1 + x2) / 2, ly: (y1 + y2) / 2 - 4 };
      });
    const width = Math.max(...nodes.map((n) => n.x + NODE_W), 0) + 48;
    const height = Math.max(...nodes.map((n) => n.y + NODE_H), 0) + 4;
    return { edges, width, height, nodeW: NODE_W, nodeH: NODE_H };
  });

  protected readonly stepOf = computed(() => {
    const map: Record<string, number> = {};
    this.nodes().forEach((n) => (map[n.step.segmentId] = n.no));
    return map;
  });

  protected readonly progress = computed(() => this.store.progressOf(this.slots()));

  private readonly slots = computed(() => this.flow().steps.map((s) => ({ segmentId: s.segmentId, place: this.place() })));

  /** Segment ids whose code is shown. */
  protected readonly visible = computed<ReadonlySet<string>>(() => {
    const nodes = this.nodes();
    const i = this.stepIndex();
    if (i != null) {
      return new Set(nodes[i] ? [nodes[i].step.segmentId] : []);
    }
    const hidden = this.hidden();
    const hideDone = this.hideDone();
    return new Set(nodes.map((n) => n.step.segmentId).filter((id) => !hidden.has(id) && !(hideDone && this.isDone(id))));
  });

  protected readonly hiddenCount = computed(() => this.nodes().length - this.visible().size);

  constructor() {
    // Lay out the connectors once the cards are rendered (and again when they change size).
    afterRenderEffect((onCleanup) => {
      this.nodes();
      this.visible();
      this.gutter();
      this.store.ok();
      this.store.okAt();
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
      this.visible();
      if (target && this.stepOf()[target] && this.scrollIntoView(target)) {
        this.store.focusSegment.set(null);
      }
    });
  }

  protected isDone(id: string): boolean {
    const p = this.nodeProgress(id);
    return p.total > 0 && p.ok === p.total;
  }

  protected toggleHidden(id: string, event: Event): void {
    event.stopPropagation();
    const next = new Set(this.hidden());
    next.has(id) ? next.delete(id) : next.add(id);
    this.hidden.set(next);
    this.stepIndex.set(null);
  }

  /** Shows only this step and everything it depends on (what it calls, and what those call), hides the rest. */
  protected focusOn(id: string, event: Event): void {
    event.stopPropagation();
    this.focusRoot.set(this.focusRoot() === id ? null : id);
    this.stepIndex.set(null);
  }

  protected showAll(): void {
    this.focusRoot.set(null);
    this.hidden.set(new Set());
    this.hideDone.set(false);
    this.stepIndex.set(null);
  }

  protected startSteps(): void {
    const nodes = this.nodes();
    const firstOpen = nodes.findIndex((n) => !this.isDone(n.step.segmentId));
    this.stepIndex.set(Math.max(0, firstOpen));
  }

  protected step(delta: number): void {
    const i = this.stepIndex() ?? 0;
    this.stepIndex.set(Math.min(this.nodes().length - 1, Math.max(0, i + delta)));
  }

  /** Marks the current step as ok (here) and moves on to the next one. */
  protected okAndNext(): void {
    const node = this.nodes()[this.stepIndex() ?? 0];
    if (!node) return;
    const lines = this.store.linesOf([{ segmentId: node.step.segmentId, place: this.place() }]);
    this.store.mark(lines, true, `segment:${node.step.segmentId}`);
    if ((this.stepIndex() ?? 0) < this.nodes().length - 1) {
      this.step(1);
    } else {
      this.stepIndex.set(null);
      this.store.notify('Flow durchgearbeitet');
    }
  }

  protected calledBy(node: Node): string | null {
    const parent = node.step.parentSegmentId;
    if (!parent) {
      return null;
    }
    const edge = this.flow().edges.find((e) => e.from === parent && e.to === node.step.segmentId);
    const caller = this.store.segments().get(parent);
    const name = caller?.symbol ?? caller?.path ?? parent;
    const how = edge?.kind === 'http' ? `per HTTP ${edge.via} ` : '';
    return `${how}aufgerufen von ${this.stepOf()[parent] ?? ''} ${shortName(name)}${edge?.line ? ' · Zeile ' + edge.line : ''}`;
  }

  protected shortName(name: string | null): string {
    return shortName(name ?? '');
  }

  /** Method/function name only; the class goes into the second line. */
  protected nodeName(seg: Segment): string {
    const name = seg.symbol ?? this.fileOf(seg);
    return name.substring(name.lastIndexOf('.') + 1);
  }

  /** Layer of a step (frontend, api, domain, ...) for the coloured stripe and tag. */
  protected areaOf(seg: Segment): string | null {
    return areaOfSegment(seg, this.store.areaBySegment());
  }

  protected areaText(seg: Segment): string {
    return areaLabel(this.areaOf(seg));
  }

  protected calledByColor(node: Node): string | null {
    const parent = node.step.parentSegmentId;
    return parent ? this.edgeColors()[parent + '>' + node.step.segmentId] ?? null : null;
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
    return this.store.progressOf([{ segmentId: id, place: this.place() }]);
  }

  protected markFlow(): void {
    this.store.toggle(this.store.linesOf(this.slots()), `flow:${this.flow().id}`);
  }

  /** Jumps to a step (making it visible first); segments outside this flow are shown at their own place. */
  protected scrollTo(segmentId: string): void {
    let index = this.nodes().findIndex((n) => n.step.segmentId === segmentId);
    if (index < 0 && this.focusRoot() && this.flow().steps.some((s) => s.segmentId === segmentId)) {
      this.focusRoot.set(null); // outside the focused branch: show the whole flow again
      index = this.nodes().findIndex((n) => n.step.segmentId === segmentId);
    }
    if (index < 0) {
      this.store.reveal(segmentId);
      return;
    }
    if (!this.visible().has(segmentId)) {
      if (this.stepIndex() != null) {
        this.stepIndex.set(index);
      } else {
        const next = new Set(this.hidden());
        next.delete(segmentId);
        this.hidden.set(next);
        if (this.hideDone() && this.isDone(segmentId)) {
          this.hideDone.set(false);
        }
      }
    }
    this.store.focusSegment.set(segmentId);
  }

  private scrollIntoView(segmentId: string): boolean {
    const el = this.cards().nativeElement.querySelector<HTMLElement>(`app-code-block[data-segment="${segmentId}"]`);
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

  protected scrollToParent(node: Node): void {
    if (node.step.parentSegmentId) {
      this.scrollTo(node.step.parentSegmentId);
    }
  }

  /** Draws, in the left margin, one line per call: from the call-site line to the header of the called step. */
  /**
   * Draws, in the left margin, one line per call: from the call-site line to the header of the called step.
   * Every line gets its own lane (LANE px apart); lanes are reused where lines do not overlap vertically.
   * Long lines take the outer lanes and short ones the inner lanes, so they cross as little as possible.
   */
  private layoutConnectors(): void {
    const host = this.cards().nativeElement;
    const box = host.getBoundingClientRect();
    const scroll = host.scrollLeft;
    // screen px -> layout px (1 unless the page is scaled, e.g. a zoomed-out preview)
    const k = host.offsetWidth > 0 && box.width > 0 ? host.offsetWidth / box.width : 1;
    const items: { edge: FlowEdge; c: number; fromX: number; toX: number; y1: number; y2: number; lane: number }[] = [];
    for (const [i, edge] of this.flow().edges.entries()) {
      const fromEl = host.querySelector<HTMLElement>(`app-code-block[data-segment="${edge.from}"]`);
      const toEl = host.querySelector<HTMLElement>(`app-code-block[data-segment="${edge.to}"]`);
      if (!fromEl || !toEl) {
        continue;
      }
      const lineEl = edge.line != null ? fromEl.querySelector<HTMLElement>(`.row[data-new="${edge.line}"]`) : null;
      const fromRect = (lineEl ?? fromEl.querySelector<HTMLElement>('.head') ?? fromEl).getBoundingClientRect();
      const toHead = (toEl.querySelector<HTMLElement>('.head') ?? toEl).getBoundingClientRect();
      items.push({
        edge,
        c: edgeColorIndex(i),
        fromX: (fromEl.getBoundingClientRect().left - box.left) * k + scroll,
        toX: (toEl.getBoundingClientRect().left - box.left) * k + scroll,
        y1: (fromRect.top - box.top + fromRect.height / 2) * k,
        y2: (toHead.top - box.top + toHead.height / 2) * k,
        lane: 0,
      });
    }
    // lane assignment: longest first, each into the outermost lane that is free over its whole height
    const lanes: [number, number][][] = [];
    for (const item of [...items].sort((a, b) => Math.abs(b.y2 - b.y1) - Math.abs(a.y2 - a.y1))) {
      const lo = Math.min(item.y1, item.y2) - 8;
      const hi = Math.max(item.y1, item.y2) + 8;
      let lane = lanes.findIndex((used) => used.every(([a, b]) => b < lo || a > hi));
      if (lane < 0) {
        lane = lanes.length;
        lanes.push([]);
      }
      lanes[lane].push([lo, hi]);
      item.lane = lane;
    }
    const result: Connector[] = items.map((item) => {
      const x = 8 + item.lane * LANE;
      const r = 5;
      const { fromX, toX, y1, y2 } = item;
      const dir = y2 > y1 ? 1 : -1;
      const d = `M ${fromX - 2} ${y1} H ${x + r} Q ${x} ${y1}, ${x} ${y1 + r * dir} V ${y2 - r * dir} Q ${x} ${y2}, ${x + r} ${y2} H ${toX - 3}`;
      return { d, from: item.edge.from, to: item.edge.to, c: item.c, http: item.edge.kind === 'http' };
    });
    this.gutter.set(Math.max(MIN_GUTTER, 8 + lanes.length * LANE + 14));
    this.railHeight.set(host.scrollHeight);
    this.railWidth.set(host.scrollWidth);
    this.connectors.set(result);
  }
}

function shortName(name: string): string {
  const parts = name.split('.');
  return parts.length > 1 && parts[parts.length - 1] ? parts.slice(-2).join('.') : name;
}
