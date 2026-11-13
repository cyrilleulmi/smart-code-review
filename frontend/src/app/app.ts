import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, signal, untracked } from '@angular/core';
import { prLabel } from './api';
import { FlowView } from './flow-view';
import { GroupView } from './group-view';
import { Icon } from './icon';
import { Overview } from './overview';
import { PrList } from './pr-list';
import { ReviewStore, Slot } from './review.store';
import { ReviewTree } from './review-tree';

type ToolWindow = 'prs' | 'review' | null;

interface EditorContent {
  kind: 'overview' | 'group' | 'flow';
  title?: string;
  description?: string | null;
  icon?: string;
  slots?: Slot[];
  scope?: string;
  crumbs: string[];
}

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, PrList, ReviewTree, Overview, GroupView, FlowView],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly store = inject(ReviewStore);
  protected readonly tool = signal<ToolWindow>('prs');
  protected readonly toolWidth = signal(readWidth());

  protected readonly flow = computed(() => {
    const sel = this.store.selection();
    return sel.kind === 'flow' ? this.store.bundle()?.analysis.flows.find((f) => f.id === sel.id) ?? null : null;
  });

  /** What the editor area shows for the current selection. */
  protected readonly content = computed<EditorContent | null>(() => {
    const b = this.store.bundle();
    if (!b) return null;
    const sel = this.store.selection();
    const cats = b.analysis.categories;
    switch (sel.kind) {
      case 'overview':
        return { kind: 'overview', crumbs: ['Übersicht'] };
      case 'category': {
        const c = cats.find((x) => x.id === sel.id)!;
        return {
          kind: 'group', title: c.label, description: c.description, icon: c.icon,
          slots: this.store.slotsFor(sel), scope: `category:${c.id}`, crumbs: [c.label],
        };
      }
      case 'group': {
        const c = cats.find((x) => x.groups.some((g) => g.id === sel.id))!;
        const g = c.groups.find((x) => x.id === sel.id)!;
        return {
          kind: 'group', title: g.label, description: g.description ?? c.description, icon: c.id === 'logic' || c.id === 'tests' ? 'file' : c.icon,
          slots: this.store.slotsFor(sel), scope: `group:${g.id}`, crumbs: [c.label, g.label],
        };
      }
      case 'flow': {
        const f = b.analysis.flows.find((x) => x.id === sel.id)!;
        return { kind: 'flow', crumbs: ['Datenflüsse', f.title] };
      }
      case 'file': {
        const f = b.files.find((x) => x.path === sel.path);
        return {
          kind: 'group', title: sel.path, icon: 'file',
          description: f ? `${statusLabel(f.status)} · +${f.additions} −${f.deletions}` : null,
          slots: this.store.slotsFor(sel), scope: `file:${sel.path}`, crumbs: ['Dateien', ...sel.path.split('/')],
        };
      }
    }
  });

  protected readonly prLabel = prLabel;
  private dragStart: { x: number; width: number } | null = null;

  constructor() {
    this.store.init();
    // Switch to the review tool window whenever another PR has been opened.
    let lastPr: string | null = null;
    effect(() => {
      const key = this.store.prKey();
      const id = key ? prLabel(key) : null;
      if (id && id !== lastPr) {
        untracked(() => this.tool.set('review'));
      }
      lastPr = id;
    });
  }

  protected toggleTool(t: ToolWindow): void {
    this.tool.set(this.tool() === t ? null : t);
  }

  protected startDrag(event: PointerEvent): void {
    this.dragStart = { x: event.clientX, width: this.toolWidth() };
    (event.target as HTMLElement).setPointerCapture(event.pointerId);
  }

  protected drag(event: PointerEvent): void {
    if (this.dragStart) {
      this.toolWidth.set(Math.min(640, Math.max(220, this.dragStart.width + event.clientX - this.dragStart.x)));
    }
  }

  protected endDrag(): void {
    this.dragStart = null;
    try {
      localStorage.setItem('scr.toolWidth', String(this.toolWidth()));
    } catch {
      // storage unavailable - the width is just not remembered
    }
  }

  @HostListener('window:keydown', ['$event'])
  protected shortcuts(event: KeyboardEvent): void {
    if (event.altKey && event.key === '1') {
      event.preventDefault();
      this.toggleTool('prs');
    } else if (event.altKey && event.key === '2' && this.store.bundle()) {
      event.preventDefault();
      this.toggleTool('review');
    }
  }
}

function readWidth(): number {
  try {
    return Number(localStorage.getItem('scr.toolWidth')) || 330;
  } catch {
    return 330;
  }
}

function statusLabel(status: string): string {
  return ({ added: 'Neu', removed: 'Gelöscht', modified: 'Geändert', renamed: 'Umbenannt' } as Record<string, string>)[status] ?? status;
}
