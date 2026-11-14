import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Blame, Comment, Commit, DisplayLine, LineMark, Segment } from './api';
import { highlightLines } from './highlight';
import { Icon, SymbolBadge } from './icon';
import { areaColor, areaLabel, areaOfSegment, langColor, langLabel } from './look';
import { ReviewStore } from './review.store';

/** git blame of one display line (IntelliJ "Annotate"). */
interface Annotation {
  sha: string;
  /** first line of a run of lines from the same commit: shows the text */
  first: boolean;
  /** 0 = oldest, 1 = newest commit shown in the block (shade) */
  age: number;
  /** position of a PR commit (1 = oldest), null for older history */
  no: number | null;
  /** deleted line: the commit that removed it */
  removed: boolean;
  text: string;
  title: string;
  url: string | null;
}

interface Thread {
  root: Comment;
  replies: Comment[];
}

/**
 * One review segment as an IntelliJ-like code block: gutter with ok-checkbox, old/new line numbers,
 * diff colours, syntax highlighting, inline review comments, and call-site chips linking to other blocks.
 */
@Component({
  selector: 'app-code-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, SymbolBadge],
  templateUrl: './code-block.html',
  styleUrl: './code-block.scss',
  host: {
    '[class.done]': 'allOk()',
    '[class.annotated]': 'store.annotate()',
    '[class.fe]': 'area() === "frontend"',
    '[attr.data-segment]': 'segment().id',
    '[style.--area]': 'areaColor(area())',
  },
})
export class CodeBlock {
  protected readonly store = inject(ReviewStore);

  readonly segment = input.required<Segment>();
  /** The place (group id) this block is shown at; null = file view, marks apply to all places. */
  readonly place = input<string | null>(null);
  /** Step number in a flow (shown as a badge), if any. */
  readonly step = input<number | null>(null);
  /** Text like "aufgerufen von ① update · Zeile 76". */
  readonly calledBy = input<string | null>(null);
  readonly stepOf = input<Record<string, number>>({});
  /** Colours of the connections in a flow: "from>to" -> CSS colour (call chips and "aufgerufen von"). */
  readonly edgeColors = input<Record<string, string>>({});
  /** Colour of the connection from the caller (flow view). */
  readonly calledByColor = input<string | null>(null);
  readonly refClick = output<string>();
  readonly calledByClick = output<void>();

  /** Collapsed/expanded by the user; null = automatic (collapsed when done and "collapse done" is on). */
  private readonly userCollapsed = signal<boolean | null>(null);
  protected readonly editorKey = signal<string | null>(null);
  protected readonly replyTo = signal<number | null>(null);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  private lastClicked: number | null = null;

  protected readonly html = computed(() => highlightLines(this.segment().lines, this.segment().language));

  protected readonly areaColor = areaColor;
  /** Layer of the block: frontend, api, domain, ... (null when unknown) */
  protected readonly area = computed(() => areaOfSegment(this.segment(), this.store.areaBySegment()));
  protected readonly areaText = computed(() => areaLabel(this.area()));
  protected readonly lang = computed(() => ({ label: langLabel(this.segment().language), color: langColor(this.segment().language) }));

  protected refColor(segmentId: string): string | null {
    return this.edgeColors()[this.segment().id + '>' + segmentId] ?? null;
  }

  protected readonly fileName = computed(() => {
    const p = this.segment().path;
    return p.substring(p.lastIndexOf('/') + 1);
  });
  protected readonly dirName = computed(() => {
    const p = this.segment().path;
    return p.includes('/') ? p.substring(0, p.lastIndexOf('/') + 1) : '';
  });

  protected readonly progress = computed(() => this.store.progressOf([{ segmentId: this.segment().id, place: this.place() }]));
  protected readonly allOk = computed(() => {
    const p = this.progress();
    return p.total > 0 && p.ok === p.total;
  });
  protected readonly collapsed = computed(() => this.userCollapsed() ?? (this.store.collapseDone() && this.allOk()));

  /** All places of this block with their ok state (only when there is more than one). */
  protected readonly places = computed(() => {
    const seg = this.segment();
    if ((seg.places?.length ?? 0) < 2) {
      return [];
    }
    return seg.places.map((id) => {
      const p = this.store.progressOf([{ segmentId: seg.id, place: id }]);
      return { id, label: this.store.places().get(id)?.label ?? id, done: p.total > 0 && p.ok === p.total, current: id === this.place() };
    });
  });

  /** For test cases: the changed code they reach. */
  protected readonly covers = computed(() => {
    const t = this.store.tests().get(this.segment().id);
    const segs = this.store.segments();
    return (t?.covers ?? [])
      .filter((c) => segs.has(c.segmentId))
      .map((c) => ({ id: c.segmentId, name: shortName(segs.get(c.segmentId)!), direct: c.direct }));
  });

  /** Tests that reach this code. */
  protected readonly testedBy = computed(() => {
    const segs = this.store.segments();
    return (this.segment().testedBy ?? []).filter((id) => segs.has(id)).map((id) => ({ id, name: segs.get(id)!.symbol ?? id }));
  });

  constructor() {
    // blame of the file, once annotations are switched on
    effect(() => {
      if (this.store.annotate()) {
        const path = this.segment().path;
        untracked(() => this.store.ensureBlame(path));
      }
    });
    // "collapse all" / "expand all" of the surrounding view
    effect(() => {
      const cmd = this.store.collapseCommand();
      if (cmd) {
        untracked(() => this.userCollapsed.set(cmd.collapsed));
      }
    });
  }

  /**
   * Blame per display line: added lines belong to the PR commit that wrote them, deleted lines to the one that
   * removed them, context lines to whoever changed them last (blame at the PR head).
   */
  protected readonly annotations = computed<(Annotation | null)[] | null>(() => {
    if (!this.store.annotate()) {
      return null;
    }
    const seg = this.segment();
    const entry = this.store.blames().get(seg.path);
    const blame: Blame | null = entry && entry !== 'loading' ? entry : null;
    const known = new Map<string, Commit>(blame?.commits.map((c) => [c.sha, c]) ?? []);
    const pr = this.store.commitBySha();
    const no = this.store.commitNo();
    const shas = seg.lines.map((line) => {
      if (line.type === 'gap') return null;
      const own = line.key ? this.store.commitOfLine(seg.path, line.key) : undefined;
      if (line.type !== 'ctx' || own) return own ?? null;
      return blame && line.newLine != null ? blameAt(blame, line.newLine) : null;
    });
    const commitOf = (sha: string) => pr.get(sha) ?? known.get(sha) ?? null;
    const times = shas.filter((x): x is string => !!x).map((sha) => time(commitOf(sha)?.date)).filter((t) => t > 0);
    const [min, max] = [Math.min(...times), Math.max(...times)];
    // all on one day (a fresh branch): the time tells more than the date
    const oneDay = times.length > 0 && day(min) === day(max);
    let previous: string | null = null;
    return shas.map((sha, i) => {
      const line = seg.lines[i];
      if (!sha) {
        previous = null;
        return null;
      }
      const c = commitOf(sha);
      const removed = line.type === 'del';
      const first = sha + removed !== previous;
      previous = sha + removed;
      const n = no.get(sha) ?? null;
      const date = !c?.date ? '' : oneDay
        ? new Date(c.date).toLocaleTimeString('de-CH', { hour: '2-digit', minute: '2-digit' })
        : new Date(c.date).toLocaleDateString('de-CH', { day: '2-digit', month: '2-digit', year: '2-digit' });
      const t = time(c?.date);
      return {
        sha, first, removed, no: n, url: c?.url ?? null,
        age: max > min && t > 0 ? (t - min) / (max - min) : 1,
        text: `${date} ${shortAuthor(c)}`.trim() || sha.substring(0, 7),
        title: [
          `${sha.substring(0, 10)}${n ? ` · Commit ${n} dieses ${this.store.provider().noun}` : ''}`,
          `${c?.author ?? c?.authorLogin ?? '?'} · ${c?.date ? new Date(c.date).toLocaleString('de-CH') : ''}`,
          removed ? 'Diese Zeile wurde in diesem Commit entfernt.' : '',
          '',
          c?.message?.trim() ?? '',
          '',
          n ? 'Klick: nur diesen Commit zeigen' : 'Klick: Commit öffnen',
        ].filter((x, j) => x !== '' || j === 3 || j === 5).join('\n'),
      };
    });
  });

  protected readonly blameLoading = computed(() => this.store.annotate() && this.store.blames().get(this.segment().path) === 'loading');

  protected openAnnotation(a: Annotation, event: Event): void {
    event.stopPropagation();
    if (a.no != null) {
      this.store.setCommitFilter(this.store.commitFilter() === a.sha ? null : a.sha);
    } else if (a.url) {
      window.open(a.url, '_blank', 'noopener');
    }
  }

  /** In commit view: a change made by another commit (dimmed, cannot be marked here). */
  protected otherCommit(line: DisplayLine): boolean {
    return !!this.store.commitFilter() && (line.type === 'add' || line.type === 'del') && !!line.key
      && !this.store.inCommit(this.segment().path, line.key);
  }

  /** Inline review comments of this file, grouped into threads by line key. */
  protected readonly threads = computed(() => {
    const path = this.segment().path;
    const all = this.store.comments().filter((c) => c.kind === 'review' && c.path === path);
    const byKey = new Map<string, Thread[]>();
    for (const root of all.filter((c) => c.inReplyTo == null && c.key)) {
      const list = byKey.get(root.key!) ?? [];
      list.push({ root, replies: all.filter((c) => c.inReplyTo === root.id) });
      byKey.set(root.key!, list);
    }
    return byKey;
  });

  protected isOk(line: DisplayLine): boolean {
    return !!line.key && this.store.isOkAt(this.segment().path, line.key, this.place());
  }

  /** "1/2" for lines that need an ok at several places (shown in the file view). */
  protected multi(line: DisplayLine): { ok: number; of: number } | null {
    if (!line.key || !line.own || (this.segment().places?.length ?? 0) < 2) {
      return null;
    }
    return this.store.okCount(this.segment().path, line.key);
  }

  protected goPlace(id: string, event: Event): void {
    event.stopPropagation();
    if (id !== this.place()) {
      this.store.goToPlace(id, this.segment().id);
    }
  }

  protected goSegment(id: string, event: Event): void {
    event.stopPropagation();
    this.store.reveal(id);
  }

  protected toggleCollapsed(event: Event): void {
    event.stopPropagation();
    this.userCollapsed.set(!this.collapsed());
  }

  protected showThreads(line: DisplayLine): boolean {
    return !!line.key && (line.own || line.type === 'ctx') && this.threads().has(line.key);
  }

  protected toggleLine(index: number, event: MouseEvent): void {
    const lines = this.segment().lines;
    const line = lines[index];
    if (!line.own || !line.key || this.otherCommit(line)) {
      return;
    }
    const target = !this.isOk(line);
    const place = this.place();
    let marks: LineMark[] = [{ path: this.segment().path, key: line.key, place }];
    if (event.shiftKey && this.lastClicked != null) {
      const [from, to] = [Math.min(this.lastClicked, index), Math.max(this.lastClicked, index)];
      marks = lines.slice(from, to + 1).filter((l) => l.own && l.key && !this.otherCommit(l)).map((l) => ({ path: this.segment().path, key: l.key!, place }));
    }
    this.lastClicked = index;
    this.store.mark(marks, target, marks.length > 1 ? `range:${this.segment().id}` : 'line');
  }

  protected toggleBlock(event: Event): void {
    event.stopPropagation();
    this.store.toggle(this.store.linesOf([{ segmentId: this.segment().id, place: this.place() }]), `segment:${this.segment().id}`);
  }

  protected openEditor(line: DisplayLine): void {
    this.replyTo.set(null);
    this.draft.set('');
    this.editorKey.set(this.editorKey() === line.key ? null : line.key);
  }

  protected openReply(thread: Thread): void {
    this.editorKey.set(null);
    this.draft.set('');
    this.replyTo.set(this.replyTo() === thread.root.id ? null : thread.root.id);
  }

  protected cancel(): void {
    this.editorKey.set(null);
    this.replyTo.set(null);
  }

  protected submit(line: DisplayLine | null): void {
    const body = this.draft().trim();
    if (!body || this.sending()) {
      return;
    }
    this.sending.set(true);
    const replyTo = this.replyTo();
    const comment = replyTo != null
      ? { body, inReplyTo: replyTo }
      : {
          body,
          path: this.segment().path,
          line: line!.type === 'del' ? line!.oldLine : line!.newLine,
          side: line!.type === 'del' ? ('LEFT' as const) : ('RIGHT' as const),
          oldLine: line!.oldLine,
          newLine: line!.newLine,
        };
    this.store.postComment(comment, (ok) => {
      this.sending.set(false);
      if (ok) {
        this.cancel();
      }
    });
  }

  protected keydown(event: KeyboardEvent, line: DisplayLine | null): void {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.submit(line);
    } else if (event.key === 'Escape') {
      this.cancel();
    }
  }

  protected input(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  protected time(iso: string): string {
    return new Date(iso).toLocaleString('de-CH', { dateStyle: 'short', timeStyle: 'short' });
  }
}

/** sha of the blame range containing a line of the new file */
function blameAt(blame: Blame, line: number): string | null {
  let lo = 0;
  let hi = blame.ranges.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const r = blame.ranges[mid];
    if (line < r.start) hi = mid - 1;
    else if (line > r.end) lo = mid + 1;
    else return r.sha;
  }
  return null;
}

function day(t: number): string {
  return new Date(t).toDateString();
}

function time(iso: string | null | undefined): number {
  return iso ? Date.parse(iso) || 0 : 0;
}

/** "Cyrille Ulmi" -> "Cyrille U." */
function shortAuthor(c: Commit | null): string {
  const name = (c?.author ?? c?.authorLogin ?? '').trim();
  const parts = name.split(/\s+/);
  return parts.length > 1 ? `${parts[0]} ${parts[parts.length - 1].charAt(0)}.` : name;
}

function shortName(s: Segment): string {
  const name = s.symbol ?? s.path.substring(s.path.lastIndexOf('/') + 1);
  return name.substring(name.lastIndexOf('.') + 1);
}
