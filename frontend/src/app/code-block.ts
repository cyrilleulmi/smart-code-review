import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, TemplateRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Blame, Comment, Commit, DisplayLine, LineMark, Segment } from './api';
import { smellBadgeText, smellBadgeTitle, smellDetail, smellText, smellsByLine } from './test-smells';
import { impactBadge } from './setup-impact';
import { findingDetail, findingsByLine, lineChipText } from './case-checks';
import { laterExpectationChip, laterExpectationText, laterExpectationsByLine } from './commit-findings';
import { ruleChipText, ruleDetail, unmetByLine } from './rule-checks';
import { assertionDrop, bendsExpectations, describeChange, newlyDisabled } from './expectations';
import { flattenLines, highlightLines } from './highlight';
import { mockBadgeTitle } from './mock-boundaries';
import { HTTP_TAG, httpTitle } from './http-tests';
import { Icon, SymbolBadge } from './icon';
import { areaColor, areaLabel, areaOfSegment, langColor, langLabel } from './look';
import { ReviewStore } from './review.store';
import { PlaceBox, acceptLevel } from './accept-boxes';
import { attr, locateCalls, markHtml } from './call-sites';
import { locale, serverText, t } from './i18n';
import { diffSides, pairLines } from './split-diff';

/**
 * Scrolls to the row of a new-file line inside a rendered code block and flashes it; false when the row is not shown
 * (block collapsed, line folded into a gap).
 */
export function flashLine(block: HTMLElement, line: number): boolean {
  const row = block.querySelector<HTMLElement>(`.row[data-new="${line}"]`);
  if (!row) {
    return false;
  }
  row.scrollIntoView({ behavior: 'smooth', block: 'center' });
  row.classList.remove('flash-line');
  void row.offsetWidth;
  row.classList.add('flash-line');
  setTimeout(() => row.classList.remove('flash-line'), 1600);
  return true;
}

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

/** A block shown inside another one after the line that calls it (see CodeBlock.inline). */
export interface InlineSlot {
  key: string;
  line: number | null;
}

/** The boxes of a line in the gutter (see accept-boxes.ts), with their tooltips. */
interface ShownBoxes {
  front: { on: boolean; title: string } | null;
  rest: (PlaceBox & { title: string })[];
  all: boolean;
  completes: boolean;
}

/** One line as it is shown in a row: in the unified view the whole row, side by side its old or its new half. */
interface Cell {
  line: DisplayLine;
  /** index in lines() (marks, notes) */
  i: number;
  /** index in flat() (highlighted HTML) */
  f: number;
  /** both = unified row with both line numbers; old / new = one half of a side-by-side row */
  side: 'both' | 'old' | 'new';
  /** carries checkbox, comment button, chips and threads; false for the old half of an unchanged line (shown on both sides) */
  main: boolean;
}

/** A row of the code table: a gap, or one cell (unified) or two (side by side; null = that side is empty). */
interface ViewRow {
  /** track key: index in flat() of the gap or of the row's main line */
  f: number;
  gap: DisplayLine | null;
  open: boolean;
  unfolded: boolean;
  cells: (Cell | null)[];
}

/**
 * One review segment as an IntelliJ-like code block: gutter with ok-checkbox, old/new line numbers,
 * diff colours, syntax highlighting, inline review comments, and call-site chips linking to other blocks.
 */
@Component({
  selector: 'app-code-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, NgTemplateOutlet, SymbolBadge],
  templateUrl: './code-block.html',
  styleUrl: './code-block.scss',
  host: {
    '[class.done]': 'allOk()',
    '[class.acc-all]': 'level() === "all"',
    '[class.acc-here]': 'level() !== "all"',
    '[style.--check-w.px]': 'checkWidth()',
    '[class.annotated]': 'store.annotate()',
    '[class.split-view]': 'split()',
    '[class.fe]': 'area() === "frontend"',
    '[attr.data-segment]': 'segment().id',
    '[style.--area]': 'areaColor(area())',
  },
})
export class CodeBlock {
  protected readonly store = inject(ReviewStore);
  protected readonly t = t;
  protected readonly serverText = serverText;

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
  /**
   * Blocks to show inside this one, each after the line (new file) that calls it (flow code by line); line null or
   * a line that is not shown: after the code. They are drawn with inlineTpl, which gets the entry as its context.
   */
  readonly inline = input<readonly InlineSlot[]>([]);
  readonly inlineTpl = input<TemplateRef<{ $implicit: InlineSlot }> | null>(null);
  /** Starts collapsed (e.g. a block that is shown further up already). */
  readonly startCollapsed = input(false);
  /**
   * A block that stands for several segments - a whole file of a class in a flow (whole-class.ts): the ids of these
   * segments, the segment each line belongs to (line key -> id) and the lines they start at (new file -> id).
   * Progress, marks and the notes of the tests then cover all of them.
   */
  readonly parts = input<readonly string[] | null>(null);
  readonly owners = input<ReadonlyMap<string, string> | null>(null);
  readonly starts = input<ReadonlyMap<number, string> | null>(null);
  readonly refClick = output<string>();
  readonly calledByClick = output<void>();

  /** Collapsed/expanded by the user; null = automatic (collapsed when done and "collapse done" is on). */
  private readonly userCollapsed = signal<boolean | null>(null);
  protected readonly editorKey = signal<string | null>(null);
  protected readonly replyTo = signal<number | null>(null);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  /** Resolved threads the user opened (root comment ids); they are folded to one line otherwise. */
  private readonly openThreads = signal<ReadonlySet<number>>(new Set());
  /** Thread whose state is being changed on the code host. */
  protected readonly resolving = signal<string | null>(null);
  private lastClicked: number | null = null;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * Where the inline blocks go: after the row (index in view()) of their calling line, the rest after the code. A
   * collapsed block shows none of its lines, so all of them follow its head.
   */
  protected readonly slots = computed(() => {
    const byRow = new Map<number, InlineSlot[]>();
    const rest: InlineSlot[] = [];
    if (!this.inlineTpl()) {
      return { byRow, rest };
    }
    const rowOfLine = new Map<number, number>();
    if (!this.collapsed()) {
      this.view().forEach((row, v) => {
        const line = row.cells[row.cells.length - 1]?.line.newLine;
        if (line != null) rowOfLine.set(line, v);
      });
    }
    for (const slot of this.inline()) {
      const v = slot.line == null ? undefined : rowOfLine.get(slot.line);
      if (v == null) {
        rest.push(slot);
      } else {
        byRow.set(v, [...(byRow.get(v) ?? []), slot]);
      }
    }
    return { byRow, rest };
  });

  /** Jumps to called code: to its block inside this one when it is shown here (code by line), else via the view. */
  protected openRef(segmentId: string): void {
    const own = [...this.host.nativeElement.querySelectorAll<HTMLElement>(`app-code-block[data-segment="${segmentId}"]`)]
      .find((el) => el.parentElement?.closest('app-code-block') === this.host.nativeElement);
    if (!own) {
      this.refClick.emit(segmentId);
      return;
    }
    own.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    own.classList.remove('flash');
    void own.offsetWidth;
    own.classList.add('flash');
    setTimeout(() => own.classList.remove('flash'), 1400);
  }

  /** Gaps (by index in flat()) the user expanded. */
  protected readonly expanded = signal<ReadonlySet<number>>(new Set());
  /** All lines incl. the hidden lines of gaps, in file order. */
  protected readonly flat = computed(() => flattenLines(this.segment().lines));
  /** Highlighted over all lines (hidden ones too), indexed like flat(). */
  protected readonly html = computed(() => highlightLines(this.flat().map((f) => f.line), this.segment().language));
  /** The rows shown: f = index in flat(), unfolded = a line of an expanded gap. */
  protected readonly rows = computed(() => {
    const flat = this.flat();
    const expanded = this.expanded();
    const shown: boolean[] = [];
    const rows: { line: DisplayLine; f: number; unfolded: boolean; open: boolean }[] = [];
    flat.forEach((x, f) => {
      shown[f] = x.gap == null || (shown[x.gap] && expanded.has(x.gap));
      if (shown[f]) {
        rows.push({ line: x.line, f, unfolded: x.gap != null, open: expanded.has(f) });
      }
    });
    return rows;
  });
  /** The display lines shown (the index of a line everywhere below). */
  protected readonly lines = computed(() => this.rows().map((r) => r.line));

  /** What the block has to put side by side: removed and added lines, or only one of them (e.g. a new function). */
  private readonly sides = computed(() => diffSides(this.segment().lines));
  /** Side by side: old code left, new code right. A block with one side only stays in one column. */
  protected readonly split = computed(() => this.store.diffMode() === 'split' && this.sides() === 'both');
  /** Side by side is on but the block has only new ('new') or only removed ('old') lines; null otherwise. */
  protected readonly oneSided = computed(() => {
    const sides = this.sides();
    return this.store.diffMode() === 'split' && (sides === 'new' || sides === 'old') ? sides : null;
  });

  /** The rows as they are drawn: one cell per row (unified) or the old and the new half (side by side). */
  protected readonly view = computed<ViewRow[]>(() => {
    const rows = this.rows();
    const cell = (i: number, side: Cell['side'], main: boolean): Cell => ({ line: rows[i].line, i, f: rows[i].f, side, main });
    const gap = (i: number): ViewRow => ({ f: rows[i].f, gap: rows[i].line, open: rows[i].open, unfolded: false, cells: [] });
    if (!this.split()) {
      return rows.map((r, i) => r.line.type === 'gap' ? gap(i)
        : { f: r.f, gap: null, open: false, unfolded: r.unfolded, cells: [cell(i, 'both', true)] });
    }
    return pairLines(rows.map((r) => r.line)).map((p) => {
      const i = (p.right ?? p.left)!;
      if (rows[i].line.type === 'gap') {
        return gap(i);
      }
      // an unchanged line is on both sides: its old half shows number and code only
      const unchanged = p.left === p.right;
      return {
        f: rows[i].f, gap: null, open: false, unfolded: rows[i].unfolded,
        cells: [p.left != null ? cell(p.left, 'old', !unchanged) : null, p.right != null ? cell(p.right, 'new', true) : null],
      };
    });
  });

  /** The line a row is annotated with (git blame): side by side the new line, the removed one when there is no new. */
  private readonly blameLines = computed(() => this.view().map((r) => r.gap ?? (r.cells[r.cells.length - 1] ?? r.cells[0])!.line));

  /**
   * Side by side in a flow: colour of the call made on the new half of a row. The old half then carries a dotted
   * line in that colour, leading from the connector in the left margin over to the call.
   */
  protected leadColor(row: ViewRow): string | null {
    if (!this.split()) {
      return null;
    }
    for (const ref of row.cells[1]?.line.refs ?? []) {
      const color = this.refColor(ref.segmentId, row.cells[1]!.line);
      if (color) return color;
    }
    return null;
  }

  protected toggleGap(f: number, event: Event): void {
    event.stopPropagation();
    const next = new Set(this.expanded());
    if (next.has(f)) {
      next.delete(f);
    } else {
      next.add(f);
    }
    this.expanded.set(next);
  }

  protected readonly areaColor = areaColor;
  /** Layer of the block: frontend, api, domain, ... (null when unknown) */
  /** A whole class: the block stands for several segments. */
  protected readonly whole = computed(() => this.parts() != null);
  /** The segments the block stands for. */
  private readonly ids = computed(() => this.parts() ?? [this.segment().id]);
  private readonly ownSlots = computed(() => this.ids().map((segmentId) => ({ segmentId, place: this.place() })));
  protected readonly area = computed(() => {
    const first = this.parts()?.[0];
    return areaOfSegment((first ? this.store.segments().get(first) : undefined) ?? this.segment(), this.store.areaBySegment());
  });

  /** Whole class: the step of the flow that starts at this line. */
  protected stepAt(line: DisplayLine): { no: number | string; name: string } | null {
    const id = line.type !== 'del' && line.newLine != null ? this.starts()?.get(line.newLine) : undefined;
    const seg = id ? this.store.segments().get(id) : undefined;
    return id && seg ? { no: this.stepOf()[id] ?? '•', name: shortName(seg) } : null;
  }
  protected readonly areaText = computed(() => areaLabel(this.area()));
  protected readonly lang = computed(() => ({ label: langLabel(this.segment().language), color: langColor(this.segment().language) }));

  /**
   * The highlighted lines with their calls into other changed code marked where they are written: a bubble around
   * the called name, with the number of the step in a flow, in the colour of the connection. placed: per line
   * (index in flat()) the refs that got a bubble - the others stay chips at the end of the line.
   */
  protected readonly marked = computed(() => {
    const flat = this.flat();
    const stepOf = this.stepOf();
    const placed = new Map<number, ReadonlySet<number>>();
    const html = this.html().map((lineHtml, f) => {
      const line = flat[f].line;
      const refs = line.refs ?? [];
      if (!refs.length || line.text == null || line.type === 'gap') {
        return lineHtml;
      }
      const marks = locateCalls(line.text, refs.map((r) => r.symbol));
      if (!marks.length) {
        return lineHtml;
      }
      placed.set(f, new Set(marks.map((m) => m.ref)));
      return markHtml(lineHtml, marks.map((m) => {
        const ref = refs[m.ref];
        // the colour of the connection as a class: inline styles do not survive the sanitising of the code
        const edge = /--edge-(\d+)/.exec(this.refColor(ref.segmentId, line) ?? '')?.[1];
        const tag = (extra: string) => `<span class="call r${m.ref}${edge ? ' call-e' + edge : ''}${extra}" role="button" tabindex="0" `
          + `title="${attr(t('Springe zu {name}', { name: serverText(ref.symbol) }))}">`;
        return { start: m.start, end: m.end, open: tag('') + `<b>${stepOf[ref.segmentId] ?? '→'}</b>`, reopen: tag(' cont'), close: '</span>' };
      }));
    });
    return { html, placed };
  });

  /** The ref has its bubble at the call in the code (no chip at the end of the line). */
  protected isPlaced(f: number, ref: number): boolean {
    return this.marked().placed.get(f)?.has(ref) ?? false;
  }

  /** A click (or Enter) in the code: on the bubble of a call, jump to the called code. */
  protected codeClick(event: Event, line: DisplayLine): void {
    const call = (event.target as HTMLElement | null)?.closest?.('.call');
    const ref = line.refs?.[Number(/\br(\d+)\b/.exec(call?.className ?? '')?.[1] ?? -1)];
    if (ref) {
      event.stopPropagation();
      event.preventDefault();
      this.openRef(ref.segmentId);
    }
  }

  /** Colour of the connection of a call on a line: from the segment the line belongs to, to the called one. */
  protected refColor(segmentId: string, line: DisplayLine): string | null {
    const from = (line.key ? this.owners()?.get(line.key) : undefined) ?? this.segment().id;
    return this.edgeColors()[from + '>' + segmentId] ?? null;
  }

  protected readonly fileName = computed(() => {
    const p = this.segment().path;
    return p.substring(p.lastIndexOf('/') + 1);
  });
  protected readonly dirName = computed(() => {
    const p = this.segment().path;
    return p.includes('/') ? p.substring(0, p.lastIndexOf('/') + 1) : '';
  });

  protected readonly progress = computed(() => this.store.progressOf(this.ownSlots()));
  /** Accepted here (green) or at all places of its lines (blue). */
  protected readonly level = computed(() => acceptLevel(this.progress()));
  protected readonly allOk = computed(() => this.level() !== 'open');
  /** Lines of the block are shown at several places: "here" and "everywhere" are two steps. */
  protected readonly severalPlaces = computed(() => this.progress().multi > 0);
  protected readonly collapsed = computed(() => this.userCollapsed() ?? (this.startCollapsed() || (this.store.collapseDone() && this.allOk())));

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

  protected readonly httpTag = HTTP_TAG;

  /** For test cases: the changed code they reach (HTTP-Tests: a reach over a real request is tagged). */
  protected readonly covers = computed(() => {
    if (!this.store.testInfo()) return [];
    const info = this.store.tests().get(this.segment().id);
    const segs = this.store.segments();
    return (info?.covers ?? [])
      .filter((c) => segs.has(c.segmentId))
      .map((c) => ({
        id: c.segmentId, name: shortName(segs.get(c.segmentId)!), direct: c.direct, mocked: !!c.mocked,
        http: c.http && !c.mocked ? httpTitle(c) : null,
      }));
  });

  /** Testable changed function: is it reached by a changed test (static)? null for anything else. */
  protected readonly coverage = computed(() => {
    const c = this.store.testInfo() ? this.store.coverage().get(this.segment().id) : undefined;
    if (!c) return null;
    const existing = c.existingTests?.length ?? 0;
    const label = c.status === 'none' ? t('ohne Test') : c.status === 'indirect' ? t('nur indirekt getestet')
      : c.status === 'existing' ? t('durch bestehende Tests')
      : c.directTests.length === 1 ? t('1 Test') : t('{n} Tests', { n: c.directTests.length });
    const indirect = c.indirectTests.length;
    const tests = c.status === 'none' ? t('Kein Test erreicht diese Funktion')
      : c.status === 'indirect' ? t('Nur über anderen geänderten Code erreicht ({tests})', { tests: indirect === 1 ? t('1 Test') : t('{n} Tests', { n: indirect }) })
      : c.status === 'existing' ? t(existing === 1 ? 'Kein geänderter Test, aber {n} bestehender Test erreicht sie' : 'Kein geänderter Test, aber {n} bestehende Tests erreichen sie', { n: existing })
      : t(c.directTests.length === 1 ? '{n} Test ruft die Funktion direkt auf' : '{n} Tests rufen die Funktion direkt auf', { n: c.directTests.length })
        + (indirect ? t(', {n} indirekt', { n: indirect }) : '');
    const old = c.status !== 'existing' && existing
      ? ' · ' + t(existing === 1 ? 'dazu {n} bestehender Test' : 'dazu {n} bestehende Tests', { n: existing }) : '';
    const mocked = c.mockedTests?.length ?? 0;
    const mocks = mocked ? ' · ' + t(mocked === 1 ? 'in {n} Test nur gemockt (zählt nicht)' : 'in {n} Tests nur gemockt (zählt nicht)', { n: mocked }) : '';
    const branches = t(c.branches === 1 ? '{n} Verzweigung' : '{n} Verzweigungen', { n: c.branches });
    return { status: c.status, label, title: `${tests}${old}${mocks} · ${branches} · ${t('statisch ermittelt – Klick: Abdeckung zeigen')}` };
  });

  /**
   * Fall- und Effekt-Abgleich: line index -> chips for the open findings on that line («kein Test erwartet
   * NotFoundException», «Grenze nicht geprüft: 1, 99», «Wirkung ungeprüft: jerseyNumberChangedAt»).
   */
  protected readonly caseNotes = computed(() => {
    const result = new Map<number, { text: string; title: string }[]>();
    const byLine = mergeByLine(this.ids().map((id) => findingsByLine(this.store.testInfo() ? this.store.coverage().get(id) : undefined)));
    if (!byLine.size) {
      return result;
    }
    const testName = this.store.testName();
    this.lines().forEach((line, i) => {
      const found = line.type !== 'del' && line.newLine != null ? byLine.get(line.newLine) : undefined;
      if (found) {
        result.set(i, found.map((f) => ({ text: lineChipText(f), title: findingDetail(f, testName) + ' – ' + t('statisch ermittelt, Klick: Abdeckung zeigen') })));
      }
    });
    return result;
  });

  /**
   * Erwartungs-Regeln: line index -> chips for the unmet rules triggered on that line («Regel: Test für den
   * verweigerten Zugriff (401/403) fehlt»).
   */
  protected readonly ruleNotes = computed(() => {
    const result = new Map<number, { text: string; title: string }[]>();
    const byLine = mergeByLine(this.ids().map((id) => unmetByLine(this.store.testInfo() ? this.store.ruleChecks() : [], id)));
    if (!byLine.size) {
      return result;
    }
    const testName = this.store.testName();
    this.lines().forEach((line, i) => {
      const found = line.type !== 'del' && line.newLine != null ? byLine.get(line.newLine) : undefined;
      if (found) {
        result.set(i, found.map((c) => ({ text: ruleChipText(c), title: ruleDetail(c, testName) + ' – ' + t('statisch ermittelt, Klick: Tests zeigen') })));
      }
    });
    return result;
  });

  /**
   * Commit-Bezug: line index -> chips on assertions a later commit changed than the one that wrote the test
   * («Erwartung nachträglich geändert (406522a)»); click shows only that commit.
   */
  protected readonly laterNotes = computed(() => {
    const result = new Map<number, { text: string; title: string; sha: string; warn: boolean }[]>();
    const byLine = mergeByLine(this.ids().map((id) => laterExpectationsByLine(this.store.testInfo() ? this.store.commitFindings() : [], id)));
    if (!byLine.size) {
      return result;
    }
    this.lines().forEach((line, i) => {
      const found = line.type !== 'del' && line.newLine != null ? byLine.get(line.newLine) : undefined;
      if (found) {
        result.set(i, found.map((f) => ({
          text: laterExpectationChip(f),
          title: `${laterExpectationText(f)}\n${serverText(f.detail)}\n– ${t('Klick: nur diesen Commit zeigen')}`,
          sha: f.sha,
          warn: f.level === 'warn',
        })));
      }
    });
    return result;
  });

  /** Shows only the changes of a commit (line chip of the Commit-Bezug). */
  protected showCommit(sha: string, event: Event): void {
    event.stopPropagation();
    this.store.setCommitFilter(sha);
  }

  /** Test case with Test-Smells: header badge («Smell: keine Assertion»); null when it has none. */
  protected readonly smellBadge = computed(() => {
    const smells = (this.store.testInfo() && this.store.tests().get(this.segment().id)?.smells) || [];
    return smells.length ? { text: smellBadgeText(smells), title: smellBadgeTitle(smells) } : null;
  });

  /**
   * Changed setup block, fixture or test helper (Wirkung von Setup und Testdaten): header badge «wirkt auf 12 Tests
   * (10 unverändert)», a warning only when unchanged tests are affected; null without impact.
   */
  protected readonly setupBadge = computed(() => this.store.testInfo() ? impactBadge(this.store.setupImpactsBySegment().get(this.segment().id)) : null);

  /** Test-Smells with a line: line index -> chips at that line. */
  protected readonly smellNotes = computed(() => {
    const result = new Map<number, { text: string; title: string }[]>();
    const byLine = smellsByLine(this.store.testInfo() ? this.store.tests().get(this.segment().id)?.smells : undefined);
    if (!byLine.size) {
      return result;
    }
    this.lines().forEach((line, i) => {
      const found = line.type !== 'del' && line.newLine != null ? byLine.get(line.newLine) : undefined;
      if (found) {
        result.set(i, found.map((s) => ({ text: `Smell: ${smellText(s)}`, title: smellDetail(s) + ' – ' + t('Klick: Testübersicht zeigen') })));
      }
    });
    return result;
  });

  /** Test case that mocks changed code (Mock-Grenzen): header badge with the stubbed members; null otherwise. */
  protected readonly mockWarning = computed(() => {
    const warnings = this.store.testInfo() ? this.store.mockWarningsByTest().get(this.segment().id) : undefined;
    return warnings?.length ? { title: mockBadgeTitle(warnings) } : null;
  });

  /** Test case whose expectations changed or that is switched off (Spec-Diff): header badge; null otherwise. */
  protected readonly expectation = computed(() => {
    const e = this.store.testInfo() ? this.store.expectationsBySegment().get(this.segment().id) : undefined;
    if (!e) return null;
    const disabled = newlyDisabled(e) || !!e.disabled;
    const bends = bendsExpectations(e) || !!assertionDrop(e);
    if (!disabled && !bends) return null;
    const details = (e.expectations ?? []).map(describeChange);
    const drop = assertionDrop(e);
    if (drop) details.push(t('{before} → {after} Assertions', drop));
    if (e.disabled && !newlyDisabled(e)) details.push(t('war schon vorher deaktiviert'));
    return {
      label: disabled ? t('deaktiviert') : t('Erwartung geändert'),
      warn: newlyDisabled(e) || bends,
      title: details.join('\n') + '\n' + t('– Musterbasiert erkannt (Spec-Diff)'),
    };
  });

  protected openCoverage(event: Event): void {
    event.stopPropagation();
    this.store.openCoverage();
  }

  /** Tests that reach this code. */
  protected readonly testedBy = computed(() => {
    if (!this.store.testInfo()) return [];
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
   * Blame per row: added lines belong to the PR commit that wrote them, deleted lines to the one that
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
    const lines = this.blameLines();
    const shas = lines.map((line) => {
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
      const line = lines[i];
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
        ? new Date(c.date).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' })
        : new Date(c.date).toLocaleDateString(locale(), { day: '2-digit', month: '2-digit', year: '2-digit' });
      const ts = time(c?.date);
      return {
        sha, first, removed, no: n, url: c?.url ?? null,
        age: max > min && ts > 0 ? (ts - min) / (max - min) : 1,
        text: `${date} ${shortAuthor(c)}`.trim() || sha.substring(0, 7),
        title: [
          `${sha.substring(0, 10)}${n ? ' · ' + t('Commit {n} dieses {noun}', { n, noun: this.store.provider().noun }) : ''}`,
          `${c?.author ?? c?.authorLogin ?? '?'} · ${c?.date ? new Date(c.date).toLocaleString(locale()) : ''}`,
          removed ? t('Diese Zeile wurde in diesem Commit entfernt.') : '',
          '',
          c?.message?.trim() ?? '',
          '',
          n ? t('Klick: nur diesen Commit zeigen') : t('Klick: Commit öffnen'),
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

  /** Line index -> its boxes, one per place the line is shown at, with what a click on them does. */
  protected readonly boxes = computed(() => {
    const result = new Map<number, ShownBoxes>();
    const path = this.segment().path;
    const here = this.place();
    const names = this.store.places();
    const state = (id: string, on: boolean) => `${names.get(id)?.label ?? id}: ${on ? t('akzeptiert') : t('offen')}\n`;
    this.lines().forEach((line, i) => {
      if (!line.own || !line.key) {
        return;
      }
      const b = this.store.lineBoxes(path, line.key, here);
      if (!b.rest.length) {
        const title = b.all ? t('Akzeptiert – Klick: Markierung entfernen') : t('Als ok markieren (Shift-Klick: Bereich)');
        result.set(i, { ...b, front: { on: b.all, title }, rest: [] });
        return;
      }
      const n = b.rest.length + (b.front ? 1 : 0);
      const action = !b.all ? t('Klick: an allen {n} Orten als ok markieren', { n })
        : b.front ? t('Klick: an den anderen Orten wieder zurücknehmen') : t('Klick: Markierung an allen Orten entfernen');
      result.set(i, {
        ...b,
        front: !b.front || !here ? null : {
          on: b.front.on,
          title: state(here, b.front.on) + (b.front.on ? t('Klick: Markierung hier entfernen') : t('Klick: hier als ok markieren (Shift-Klick: Bereich)')),
        },
        rest: b.rest.map((box) => ({ ...box, title: state(box.id, box.on) + action })),
      });
    });
    return result;
  });

  /** Room for the boxes in the gutter: the front box, and smaller ones for the other places. */
  protected readonly checkWidth = computed(() => {
    let n = 1;
    this.boxes().forEach((b) => (n = Math.max(n, (b.front ? 1 : 0) + b.rest.length)));
    return n < 2 ? 22 : 25 + (n - 1) * 12;
  });

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

  /**
   * A click on the boxes of a line. The front box accepts the line at this place only (or takes that back); the
   * other boxes (rest) - and every box in a view without a place - accept it at all places at once, or take the
   * other places back. Shift-click does the same for the range since the last click.
   */
  protected toggleLine(index: number, event: MouseEvent, rest = false): void {
    const lines = this.lines();
    const line = lines[index];
    const boxes = this.boxes().get(index);
    if (!line.own || !line.key || this.otherCommit(line) || !boxes) {
      return;
    }
    const place = this.place();
    let marks: LineMark[] = [{ path: this.segment().path, key: line.key, place }];
    if (event.shiftKey && this.lastClicked != null) {
      const [from, to] = [Math.min(this.lastClicked, index), Math.max(this.lastClicked, index)];
      marks = lines.slice(from, to + 1).filter((l) => l.own && l.key && !this.otherCommit(l)).map((l) => ({ path: this.segment().path, key: l.key!, place }));
    }
    this.lastClicked = index;
    const scope = marks.length > 1 ? `range:${this.segment().id}` : 'line';
    if (rest || !boxes.front) {
      this.store.markEverywhere(marks, !boxes.all, scope);
    } else {
      this.store.mark(marks, !boxes.front.on, scope);
    }
  }

  protected toggleBlock(event: Event): void {
    event.stopPropagation();
    this.store.toggle(this.store.linesOf(this.ownSlots()), `segment:${this.segment().id}`);
  }

  /** "Block überall ok": all lines at all their places; when they all are, the other places are taken back. */
  protected toggleBlockEverywhere(event: Event): void {
    event.stopPropagation();
    this.store.toggleEverywhere(this.store.linesOf(this.ownSlots()), `segment:${this.segment().id}`);
  }

  protected openEditor(line: DisplayLine): void {
    this.replyTo.set(null);
    this.draft.set('');
    this.editorKey.set(this.editorKey() === line.key ? null : line.key);
  }

  /** An open thread shows its comments; a resolved one only after a click on its line. */
  protected threadOpen(thread: Thread): boolean {
    return !thread.root.resolved || this.openThreads().has(thread.root.id);
  }

  protected toggleThread(thread: Thread): void {
    const open = new Set(this.openThreads());
    if (!open.delete(thread.root.id)) {
      open.add(thread.root.id);
    }
    this.openThreads.set(open);
  }

  /** Resolves the thread on the code host, or opens it again; a thread just resolved folds. */
  protected resolve(thread: Thread): void {
    const id = thread.root.thread;
    if (!id || thread.root.resolved == null || this.resolving()) {
      return;
    }
    const resolved = !thread.root.resolved;
    this.resolving.set(id);
    this.store.resolveThread(id, resolved, (ok) => {
      this.resolving.set(null);
      if (ok && resolved && this.openThreads().has(thread.root.id)) {
        this.toggleThread(thread);
      }
    });
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
    return new Date(iso).toLocaleString(locale(), { dateStyle: 'short', timeStyle: 'short' });
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

/** Notes by line of several segments in one map (a block that stands for several segments). */
function mergeByLine<T>(maps: Map<number, T[]>[]): Map<number, T[]> {
  if (maps.length === 1) {
    return maps[0];
  }
  const result = new Map<number, T[]>();
  for (const map of maps) {
    map.forEach((list, line) => result.set(line, [...(result.get(line) ?? []), ...list]));
  }
  return result;
}

function shortName(s: Segment): string {
  const name = s.symbol ?? s.path.substring(s.path.lastIndexOf('/') + 1);
  return name.substring(name.lastIndexOf('.') + 1);
}
