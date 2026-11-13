import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { Comment, DisplayLine, LineMark, Segment } from './api';
import { highlightLines } from './highlight';
import { Icon, SymbolBadge } from './icon';
import { ReviewStore } from './review.store';

interface Thread {
  root: Comment;
  replies: Comment[];
}

/**
 * One review segment as an IntelliJ-like code block: gutter with ok-checkbox, old/new line numbers,
 * diff colours, syntax highlighting, inline GitHub comments, and call-site chips linking to other blocks.
 */
@Component({
  selector: 'app-code-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, SymbolBadge],
  templateUrl: './code-block.html',
  styleUrl: './code-block.scss',
  host: {
    '[class.done]': 'allOk()',
    '[attr.data-segment]': 'segment().id',
  },
})
export class CodeBlock {
  protected readonly store = inject(ReviewStore);

  readonly segment = input.required<Segment>();
  /** Step number in a flow (shown as a badge), if any. */
  readonly step = input<number | null>(null);
  /** Text like "aufgerufen von ① update · Zeile 76". */
  readonly calledBy = input<string | null>(null);
  readonly stepOf = input<Record<string, number>>({});
  readonly refClick = output<string>();
  readonly calledByClick = output<void>();

  protected readonly expanded = signal(false);
  protected readonly editorKey = signal<string | null>(null);
  protected readonly replyTo = signal<number | null>(null);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  private lastClicked: number | null = null;

  protected readonly html = computed(() => highlightLines(this.segment().lines, this.segment().language));

  protected readonly fileName = computed(() => {
    const p = this.segment().path;
    return p.substring(p.lastIndexOf('/') + 1);
  });
  protected readonly dirName = computed(() => {
    const p = this.segment().path;
    return p.includes('/') ? p.substring(0, p.lastIndexOf('/') + 1) : '';
  });

  protected readonly progress = computed(() => this.store.progressOf([this.segment().id]));
  protected readonly allOk = computed(() => {
    const p = this.progress();
    return p.total > 0 && p.ok === p.total;
  });
  protected readonly collapsed = computed(() => this.store.collapseDone() && this.allOk() && !this.expanded());

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
    return !!line.key && this.store.isOk(this.segment().path, line.key);
  }

  protected showThreads(line: DisplayLine): boolean {
    return !!line.key && (line.own || line.type === 'ctx') && this.threads().has(line.key);
  }

  protected toggleLine(index: number, event: MouseEvent): void {
    const lines = this.segment().lines;
    const line = lines[index];
    if (!line.own || !line.key) {
      return;
    }
    const target = !this.isOk(line);
    let marks: LineMark[] = [{ path: this.segment().path, key: line.key }];
    if (event.shiftKey && this.lastClicked != null) {
      const [from, to] = [Math.min(this.lastClicked, index), Math.max(this.lastClicked, index)];
      marks = lines.slice(from, to + 1).filter((l) => l.own && l.key).map((l) => ({ path: this.segment().path, key: l.key! }));
    }
    this.lastClicked = index;
    this.store.mark(marks, target, marks.length > 1 ? `range:${this.segment().id}` : 'line');
  }

  protected toggleBlock(event: Event): void {
    event.stopPropagation();
    this.store.toggle(this.store.linesOf([this.segment().id]), `segment:${this.segment().id}`);
  }

  protected headerClick(): void {
    if (this.allOk() && this.store.collapseDone()) {
      this.expanded.set(!this.expanded());
    }
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
