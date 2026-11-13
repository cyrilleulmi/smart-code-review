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
  errorMessage,
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
  readonly ok = signal<ReadonlySet<string>>(new Set());
  readonly comments = signal<Comment[]>([]);
  readonly log = signal<LogEntry[]>([]);
  readonly selection = signal<Selection>({ kind: 'overview' });
  readonly collapseDone = signal(true);
  /** Segment to scroll to after the next render (e.g. clicked in a flow diagram). */
  readonly focusSegment = signal<string | null>(null);

  readonly prKey = computed<PrKey | null>(() => {
    const pr = this.bundle()?.pr;
    return pr ? { provider: pr.provider, owner: pr.owner, repo: pr.repo, number: pr.number } : null;
  });

  readonly segments = computed(() => {
    const map = new Map<string, Segment>();
    for (const s of this.bundle()?.analysis.segments ?? []) {
      map.set(s.id, s);
    }
    return map;
  });

  readonly filteredPrs = computed(() => {
    const f = this.prFilter();
    return f === 'all' ? this.prs() : this.prs().filter((p) => p.roles.includes(f));
  });

  readonly totalProgress = computed(() => this.progressOf([...this.segments().keys()]));

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
    this.loading.set(`Lade ${pr.owner}/${pr.repo}#${pr.number} …`);
    this.error.set(null);
    this.api.bundle(pr).subscribe({
      next: (b) => this.applyBundle(b, selection),
      error: (e) => {
        this.loading.set(null);
        this.error.set(errorMessage(e));
      },
    });
  }

  reload(): void {
    const key = this.prKey();
    if (!key) {
      return;
    }
    this.loading.set('Lade Pull Request neu von GitHub …');
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

  select(selection: Selection): void {
    this.selection.set(selection);
    const key = this.prKey();
    if (key) {
      history.replaceState(null, '', '#' + formatHash(key, selection));
    }
  }

  /** Navigates to a segment: its flow if it is part of one, otherwise its file. */
  reveal(segmentId: string): void {
    const b = this.bundle();
    const seg = this.segments().get(segmentId);
    if (!b || !seg) {
      return;
    }
    const current = this.selection();
    const flows = b.analysis.flows.filter((f) => f.steps.some((s) => s.segmentId === segmentId));
    const flow = flows.find((f) => !(current.kind === 'flow' && current.id === f.id)) ?? flows[0];
    this.focusSegment.set(segmentId);
    this.select(flow ? { kind: 'flow', id: flow.id } : { kind: 'file', path: seg.path });
  }

  // ------------------------------------------------------------- marks

  isOk(path: string, key: string): boolean {
    return this.ok().has(path + SEP + key);
  }

  progressOf(segmentIds: Iterable<string>): Progress {
    const ok = this.ok();
    const segs = this.segments();
    let total = 0;
    let done = 0;
    const seen = new Set<string>();
    for (const id of segmentIds) {
      if (seen.has(id)) {
        continue;
      }
      seen.add(id);
      const s = segs.get(id);
      if (!s) {
        continue;
      }
      total += s.lineKeys.length;
      for (const k of s.lineKeys) {
        if (ok.has(s.path + SEP + k)) {
          done++;
        }
      }
    }
    return { ok: done, total };
  }

  linesOf(segmentIds: Iterable<string>): LineMark[] {
    const segs = this.segments();
    const result: LineMark[] = [];
    for (const id of new Set(segmentIds)) {
      const s = segs.get(id);
      if (s) {
        for (const key of s.lineKeys) {
          result.push({ path: s.path, key });
        }
      }
    }
    return result;
  }

  /** Marks the lines as ok, or - if all of them already are - removes the mark (toggle). */
  toggle(lines: LineMark[], scope: string): void {
    const allOk = lines.length > 0 && lines.every((l) => this.isOk(l.path, l.key));
    this.mark(lines, !allOk, scope);
  }

  mark(lines: LineMark[], ok: boolean, scope: string): void {
    const key = this.prKey();
    if (!key || lines.length === 0) {
      return;
    }
    const next = new Set(this.ok());
    for (const l of lines) {
      const k = l.path + SEP + l.key;
      if (ok) {
        next.add(k);
      } else {
        next.delete(k);
      }
    }
    this.ok.set(next);
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
    this.ok.set(set);
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
        this.notify('Kommentar auf GitHub veröffentlicht');
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
