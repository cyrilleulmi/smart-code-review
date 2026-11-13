import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  Comment,
  LineMark,
  LogEntry,
  Me,
  NewComment,
  PrKey,
  PullRequestSummary,
  ReviewBundle,
  ReviewState,
  Segment,
  TestInfo,
  errorMessage,
  prLabel,
  providerInfo,
} from './api';

export type Selection =
  | { kind: 'overview' }
  | { kind: 'category'; id: string }
  | { kind: 'group'; id: string }
  | { kind: 'flow'; id: string }
  | { kind: 'file'; path: string };

export type PrFilter = 'all' | 'authored' | 'review-requested';

export interface Progress {
  ok: number;
  total: number;
}

/**
 * A segment as shown at one place (group id such as "quick-wins/mapper" or "flows/flow-2"). place null means
 * "everywhere" (file view, totals): a line only counts as ok there when it is ok at all its places.
 */
export interface Slot {
  segmentId: string;
  place: string | null;
}

export interface PlaceInfo {
  id: string;
  /** e.g. "Quick wins › Mapper", "Flow: update → validate" */
  label: string;
  categoryId: string;
  selection: Selection;
}

const SEP = '\u0000';

/** Central UI state (signals). Marks are applied optimistically and confirmed by the backend. */
@Injectable({ providedIn: 'root' })
export class ReviewStore {
  private readonly api = inject(Api);

  readonly me = signal<Me | null>(null);
  readonly prs = signal<PullRequestSummary[]>([]);
  readonly prsLoading = signal(false);
  readonly prFilter = signal<PrFilter>('all');
  readonly bundle = signal<ReviewBundle | null>(null);
  readonly loading = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly toast = signal<string | null>(null);
  /** lines ok at all their places ("path\0key") */
  readonly ok = signal<ReadonlySet<string>>(new Set());
  /** place -> lines ok at that place only */
  readonly okAt = signal<ReadonlyMap<string, ReadonlySet<string>>>(new Map());
  readonly comments = signal<Comment[]>([]);
  readonly log = signal<LogEntry[]>([]);
  readonly selection = signal<Selection>({ kind: 'overview' });
  readonly collapseDone = signal(true);
  /** "Collapse all" / "expand all" of the current view; code blocks follow it. Reset on navigation. */
  readonly collapseCommand = signal<{ collapsed: boolean; seq: number } | null>(null);
  /** Segment to scroll to after the next render (e.g. clicked in a flow diagram). */
  readonly focusSegment = signal<string | null>(null);

  readonly prKey = computed<PrKey | null>(() => {
    const pr = this.bundle()?.pr;
    return pr ? { provider: pr.provider, owner: pr.owner, repo: pr.repo, number: pr.number } : null;
  });

  /** Label, sigil (#/!) and noun of the open PR's code host. */
  readonly provider = computed(() => providerInfo(this.bundle()?.pr.provider));

  readonly segments = computed(() => {
    const map = new Map<string, Segment>();
    for (const s of this.bundle()?.analysis.segments ?? []) {
      map.set(s.id, s);
    }
    return map;
  });

  /** "path\0key" -> places of the line (= places of its segment) */
  private readonly linePlaces = computed(() => {
    const map = new Map<string, string[]>();
    for (const s of this.bundle()?.analysis.segments ?? []) {
      for (const k of s.lineKeys) {
        map.set(s.path + SEP + k, s.places ?? []);
      }
    }
    return map;
  });

  /** All places (groups) by id, with a readable label and where to navigate. */
  readonly places = computed(() => {
    const map = new Map<string, PlaceInfo>();
    for (const c of this.bundle()?.analysis.categories ?? []) {
      for (const g of c.groups) {
        const fileGroup = c.id === 'logic' || c.id === 'tests';
        const label = g.flowId ? `Flow: ${g.label}` : `${c.label} › ${fileGroup ? g.label.substring(g.label.lastIndexOf('/') + 1) : g.label}`;
        const selection: Selection = g.flowId ? { kind: 'flow', id: g.flowId } : { kind: 'group', id: g.id };
        map.set(g.id, { id: g.id, label, categoryId: c.id, selection });
      }
    }
    return map;
  });

  /** Test infos by test segment id. */
  readonly tests = computed(() => {
    const map = new Map<string, TestInfo>();
    for (const t of this.bundle()?.analysis.tests ?? []) {
      map.set(t.segmentId, t);
    }
    return map;
  });

  readonly filteredPrs = computed(() => {
    const f = this.prFilter();
    return f === 'all' ? this.prs() : this.prs().filter((p) => p.roles.includes(f));
  });

  readonly totalProgress = computed(() => this.progressOf(this.slotsFor({ kind: 'overview' })));

  private toastTimer: ReturnType<typeof setTimeout> | undefined;

  init(): void {
    this.api.me().subscribe({
      next: (me) => this.me.set(me),
      error: (e) => this.error.set(errorMessage(e)),
    });
    this.loadPrs();
    const fromHash = parseHash(location.hash);
    if (fromHash) {
      this.open(fromHash.pr, fromHash.selection);
    }
    window.addEventListener('focus', () => this.refreshState());
  }

  loadPrs(): void {
    this.prsLoading.set(true);
    this.api.prs().subscribe({
      next: (prs) => {
        this.prs.set(prs);
        this.prsLoading.set(false);
      },
      error: (e) => {
        this.prsLoading.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  open(pr: PrKey, selection: Selection = { kind: 'overview' }): void {
    this.loading.set(`Lade ${prLabel(pr)} …`);
    this.error.set(null);
    this.api.bundle(pr).subscribe({
      next: (b) => this.applyBundle(b, selection),
      error: (e) => {
        this.loading.set(null);
        this.error.set(errorMessage(e));
      },
    });
  }

  /** Opens a PR/MR by its web URL (GitHub or GitLab, also ones not in the list). */
  openUrl(url: string): void {
    this.error.set(null);
    this.api.resolve(url.trim()).subscribe({
      next: (ref) => this.open(ref),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  reload(): void {
    const key = this.prKey();
    if (!key) {
      return;
    }
    this.loading.set(`Lade ${this.provider().noun} neu von ${this.provider().label} …`);
    this.api.reload(key).subscribe({
      next: (b) => {
        this.applyBundle(b, this.selection());
        this.notify('Snapshot neu geladen und analysiert');
      },
      error: (e) => {
        this.loading.set(null);
        this.error.set(errorMessage(e));
      },
    });
  }

  reanalyse(): void {
    const key = this.prKey();
    if (!key) {
      return;
    }
    this.loading.set('Analysiere …');
    this.api.reanalyse(key).subscribe({
      next: (b) => {
        this.applyBundle(b, this.selection());
        this.notify('Analyse neu berechnet');
      },
      error: (e) => {
        this.loading.set(null);
        this.error.set(errorMessage(e));
      },
    });
  }

  close(): void {
    this.bundle.set(null);
    this.comments.set([]);
    this.ok.set(new Set());
    this.okAt.set(new Map());
    history.replaceState(null, '', location.pathname);
  }

  private applyBundle(b: ReviewBundle, selection: Selection): void {
    this.bundle.set(b);
    this.applyState(b.state);
    this.loading.set(null);
    this.select(this.isValidSelection(selection, b) ? selection : { kind: 'overview' });
    this.loadComments();
  }

  private isValidSelection(sel: Selection, b: ReviewBundle): boolean {
    switch (sel.kind) {
      case 'overview':
        return true;
      case 'category':
        return b.analysis.categories.some((c) => c.id === sel.id);
      case 'group':
        return b.analysis.categories.some((c) => c.groups.some((g) => g.id === sel.id));
      case 'flow':
        return b.analysis.flows.some((f) => f.id === sel.id);
      case 'file':
        return b.files.some((f) => f.path === sel.path);
    }
  }

  setCollapsedAll(collapsed: boolean): void {
    this.collapseCommand.set({ collapsed, seq: (this.collapseCommand()?.seq ?? 0) + 1 });
  }

  select(selection: Selection): void {
    this.selection.set(selection);
    this.collapseCommand.set(null);
    const key = this.prKey();
    if (key) {
      history.replaceState(null, '', '#' + formatHash(key, selection));
    }
  }

  /** Navigates to a segment at another place than the current one (its first place, else its file). */
  reveal(segmentId: string): void {
    const seg = this.segments().get(segmentId);
    if (!seg) {
      return;
    }
    const current = this.currentPlace();
    const place = seg.places.find((p) => p !== current) ?? seg.places[0];
    if (place) {
      this.goToPlace(place, segmentId);
    } else {
      this.focusSegment.set(segmentId);
      this.select({ kind: 'file', path: seg.path });
    }
  }

  goToPlace(place: string, segmentId: string): void {
    const info = this.places().get(place);
    if (info) {
      this.focusSegment.set(segmentId);
      this.select(info.selection);
    }
  }

  /** The place (group id) the editor currently shows, if it shows exactly one group or flow. */
  currentPlace(): string | null {
    const sel = this.selection();
    if (sel.kind === 'group') return sel.id;
    if (sel.kind === 'flow') return 'flows/' + sel.id;
    return null;
  }

  /** The segments a selection shows, each at the place it is shown at. */
  slotsFor(sel: Selection): Slot[] {
    const b = this.bundle();
    if (!b) return [];
    const cats = b.analysis.categories;
    const groupSlots = (groups: { id: string; segmentIds: string[] }[]) =>
      groups.flatMap((g) => g.segmentIds.map((segmentId) => ({ segmentId, place: g.id })));
    switch (sel.kind) {
      case 'overview':
        return b.analysis.segments.map((s) => ({ segmentId: s.id, place: null }));
      case 'category':
        return groupSlots(cats.find((c) => c.id === sel.id)?.groups ?? []);
      case 'group':
        return groupSlots(cats.flatMap((c) => c.groups).filter((g) => g.id === sel.id));
      case 'flow':
        return groupSlots(cats.flatMap((c) => c.groups).filter((g) => g.flowId === sel.id));
      case 'file':
        return b.analysis.segments
          .filter((s) => s.path === sel.path)
          .sort((x, y) => (x.startLine ?? lineOf(x.lineKeys[0])) - (y.startLine ?? lineOf(y.lineKeys[0])))
          .map((s) => ({ segmentId: s.id, place: null }));
    }
  }

  // ------------------------------------------------------------- marks

  /** Is the line ok at this place? place null: ok at all its places. */
  isOkAt(path: string, key: string, place: string | null): boolean {
    const k = path + SEP + key;
    if (this.ok().has(k)) {
      return true;
    }
    const places = this.linePlaces().get(k) ?? [];
    if (place == null || !places.includes(place)) {
      return places.length > 1 && places.every((p) => this.okAt().get(p)?.has(k));
    }
    return places.length > 1 && !!this.okAt().get(place)?.has(k);
  }

  /** How many of the line's places are ok (for lines shown at several places). */
  okCount(path: string, key: string): { ok: number; of: number } {
    const k = path + SEP + key;
    const places = this.linePlaces().get(k) ?? [];
    if (this.ok().has(k)) {
      return { ok: places.length, of: places.length };
    }
    return { ok: places.filter((p) => this.okAt().get(p)?.has(k)).length, of: places.length };
  }

  /** Lines of the slots; every slot (segment at a place) counts on its own. */
  progressOf(slots: Iterable<Slot>): Progress {
    const segs = this.segments();
    let total = 0;
    let done = 0;
    const seen = new Set<string>();
    for (const slot of slots) {
      const id = slot.segmentId + SEP + slot.place;
      const s = segs.get(slot.segmentId);
      if (seen.has(id) || !s) {
        continue;
      }
      seen.add(id);
      total += s.lineKeys.length;
      for (const k of s.lineKeys) {
        if (this.isOkAt(s.path, k, slot.place)) {
          done++;
        }
      }
    }
    return { ok: done, total };
  }

  linesOf(slots: Iterable<Slot>): LineMark[] {
    const segs = this.segments();
    const result: LineMark[] = [];
    const seen = new Set<string>();
    for (const slot of slots) {
      const s = segs.get(slot.segmentId);
      if (!s || seen.has(slot.segmentId + SEP + slot.place)) {
        continue;
      }
      seen.add(slot.segmentId + SEP + slot.place);
      for (const key of s.lineKeys) {
        result.push({ path: s.path, key, place: slot.place });
      }
    }
    return result;
  }

  /** Marks the lines as ok, or - if all of them already are - removes the mark (toggle). */
  toggle(lines: LineMark[], scope: string): void {
    const allOk = lines.length > 0 && lines.every((l) => this.isOkAt(l.path, l.key, l.place));
    this.mark(lines, !allOk, scope);
  }

  /** Optimistic version of the backend logic (ReviewService.mark): one ok per place, then "ok everywhere". */
  mark(lines: LineMark[], ok: boolean, scope: string): void {
    const key = this.prKey();
    if (!key || lines.length === 0) {
      return;
    }
    const full = new Set(this.ok());
    const at = new Map([...this.okAt()].map(([p, set]) => [p, new Set(set)]));
    const atSet = (p: string) => at.get(p) ?? at.set(p, new Set()).get(p)!;
    for (const l of lines) {
      const k = l.path + SEP + l.key;
      const places = this.linePlaces().get(k) ?? [];
      if (l.place == null || places.length <= 1 || !places.includes(l.place)) {
        ok ? full.add(k) : full.delete(k);
        places.forEach((p) => at.get(p)?.delete(k));
        continue;
      }
      if (full.has(k) && ok) {
        continue;
      }
      if (full.has(k)) {
        full.delete(k);
        places.filter((p) => p !== l.place).forEach((p) => atSet(p).add(k));
      } else if (ok) {
        atSet(l.place).add(k);
      } else {
        at.get(l.place)?.delete(k);
      }
      if (places.every((p) => at.get(p)?.has(k))) {
        full.add(k);
        places.forEach((p) => at.get(p)?.delete(k));
      }
    }
    this.ok.set(full);
    this.okAt.set(at);
    this.api.mark(key, lines, ok, scope).subscribe({
      next: (state) => this.applyState(state),
      error: (e) => {
        this.error.set('Markierung nicht gespeichert: ' + errorMessage(e));
        this.refreshState();
      },
    });
  }

  /** Picks up changes made outside the UI (e.g. by an agent editing review.json). */
  refreshState(): void {
    const key = this.prKey();
    if (key) {
      this.api.state(key).subscribe({ next: (s) => this.applyState(s) });
    }
  }

  private applyState(state: ReviewState): void {
    const set = new Set<string>();
    for (const [path, keys] of Object.entries(state.ok ?? {})) {
      for (const k of keys) {
        set.add(path + SEP + k);
      }
    }
    const at = new Map<string, Set<string>>();
    for (const [place, byPath] of Object.entries(state.okAt ?? {})) {
      const keys = new Set<string>();
      for (const [path, list] of Object.entries(byPath)) {
        list.forEach((k) => keys.add(path + SEP + k));
      }
      at.set(place, keys);
    }
    this.ok.set(set);
    this.okAt.set(at);
    this.log.set(state.log ?? []);
  }

  // ------------------------------------------------------------- comments

  loadComments(): void {
    const key = this.prKey();
    if (key) {
      this.api.comments(key).subscribe({ next: (c) => this.comments.set(c) });
    }
  }

  postComment(comment: NewComment, done: (ok: boolean) => void): void {
    const key = this.prKey();
    if (!key) {
      return;
    }
    this.api.postComment(key, comment).subscribe({
      next: (c) => {
        this.comments.set([...this.comments(), c]);
        this.notify(`Kommentar auf ${this.provider().label} veröffentlicht`);
        done(true);
      },
      error: (e) => {
        this.error.set('Kommentar fehlgeschlagen: ' + errorMessage(e));
        done(false);
      },
    });
  }

  notify(message: string): void {
    this.toast.set(message);
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toast.set(null), 2600);
  }
}

function lineOf(key: string | undefined): number {
  return key ? Number(key.substring(1)) : 0;
}

// ------------------------------------------------------------- hash routing: #/github/owner/repo/12/flow/flow-1

function formatHash(pr: PrKey, sel: Selection): string {
  const base = `/${pr.provider}/${pr.owner}/${pr.repo}/${pr.number}`;
  switch (sel.kind) {
    case 'overview':
      return base;
    case 'file':
      return `${base}/file/${encodeURIComponent(sel.path)}`;
    default:
      return `${base}/${sel.kind}/${encodeURIComponent(sel.id)}`;
  }
}

function parseHash(hash: string): { pr: PrKey; selection: Selection } | null {
  const parts = hash.replace(/^#\/?/, '').split('/');
  if (parts.length < 4 || !/^\d+$/.test(parts[3])) {
    return null;
  }
  const pr = { provider: parts[0], owner: parts[1], repo: parts[2], number: Number(parts[3]) };
  const kind = parts[4];
  const value = parts[5] ? decodeURIComponent(parts[5]) : '';
  let selection: Selection = { kind: 'overview' };
  if (kind === 'file' && value) {
    selection = { kind: 'file', path: value };
  } else if ((kind === 'category' || kind === 'group' || kind === 'flow') && value) {
    selection = { kind, id: value };
  }
  return { pr, selection };
}
