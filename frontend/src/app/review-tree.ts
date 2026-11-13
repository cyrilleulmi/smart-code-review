import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Icon, SymbolBadge } from './icon';
import { ReviewStore, Selection, Slot } from './review.store';

type Tab = 'categories' | 'flows' | 'files';

interface TreeNode {
  id: string;
  label: string;
  hint?: string;
  icon?: string;
  symbolKind?: string | null;
  depth: number;
  selection: Selection;
  /** what the node's progress and ✓ action cover: segments at the place they are shown at */
  slots: Slot[];
  scope: string;
  /** files tab: number of blocks that are assigned to several places */
  multi?: number;
  children?: TreeNode[];
  focus?: string;
  css?: string;
}

/** The "Review" tool window: categories, flows and files as trees with progress and bulk "ok" actions. */
@Component({
  selector: 'app-review-tree',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, SymbolBadge],
  templateUrl: './review-tree.html',
  styleUrl: './review-tree.scss',
})
export class ReviewTree {
  protected readonly store = inject(ReviewStore);
  protected readonly tab = signal<Tab>('categories');
  private readonly collapsed = signal<ReadonlySet<string>>(new Set());

  private readonly categoryTree = computed<TreeNode[]>(() => {
    const b = this.store.bundle();
    if (!b) return [];
    const segs = this.store.segments();
    return b.analysis.categories.map((c) => ({
      id: 'c:' + c.id,
      label: c.label,
      icon: c.icon,
      depth: 0,
      css: 'cat-' + c.id,
      selection: { kind: 'category', id: c.id } as Selection,
      slots: this.store.slotsFor({ kind: 'category', id: c.id }),
      scope: `category:${c.id}`,
      children: c.groups.map((g) => ({
        id: 'g:' + g.id,
        label: g.label,
        hint: g.flowId ? g.description ?? undefined : undefined,
        icon: g.flowId ? 'flow' : c.id === 'logic' || c.id === 'tests' ? 'file' : 'folder',
        depth: 1,
        selection: (g.flowId ? { kind: 'flow', id: g.flowId } : { kind: 'group', id: g.id }) as Selection,
        slots: g.segmentIds.map((segmentId) => ({ segmentId, place: g.id })),
        scope: g.flowId ? `flow:${g.flowId}` : `group:${g.id}`,
        children: g.segmentIds.map((id) => {
          const s = segs.get(id)!;
          return {
            id: `s:${g.id}:${id}`,
            label: s.symbol ?? s.path.substring(s.path.lastIndexOf('/') + 1),
            hint: s.symbol ? s.path.substring(s.path.lastIndexOf('/') + 1) : `Zeile ${s.lineKeys[0]?.substring(1) ?? ''}`,
            symbolKind: s.symbolKind,
            depth: 2,
            selection: (g.flowId ? { kind: 'flow', id: g.flowId } : { kind: 'group', id: g.id }) as Selection,
            slots: [{ segmentId: id, place: g.id }],
            scope: `segment:${id}`,
            focus: id,
          };
        }),
      })),
    }));
  });

  private readonly flowTree = computed<TreeNode[]>(() => {
    const b = this.store.bundle();
    if (!b) return [];
    const segs = this.store.segments();
    return b.analysis.flows.map((f) => ({
      id: 'f:' + f.id,
      label: f.title,
      icon: 'flow',
      depth: 0,
      selection: { kind: 'flow', id: f.id } as Selection,
      slots: f.steps.map((s) => ({ segmentId: s.segmentId, place: 'flows/' + f.id })),
      scope: `flow:${f.id}`,
      children: f.steps.map((st, i) => {
        const s = segs.get(st.segmentId)!;
        return {
          id: `fs:${f.id}:${st.segmentId}`,
          label: `${i + 1}. ${s.symbol ?? s.path}`,
          hint: s.path.substring(s.path.lastIndexOf('/') + 1),
          symbolKind: s.symbolKind,
          depth: 1 + Math.min(st.depth, 4),
          selection: { kind: 'flow', id: f.id } as Selection,
          slots: [{ segmentId: st.segmentId, place: 'flows/' + f.id }],
          scope: `segment:${st.segmentId}`,
          focus: st.segmentId,
        };
      }),
    }));
  });

  private readonly fileTree = computed<TreeNode[]>(() => {
    const b = this.store.bundle();
    if (!b) return [];
    const byFile = new Map<string, string[]>();
    const multi = new Map<string, number>();
    for (const s of b.analysis.segments) {
      byFile.set(s.path, [...(byFile.get(s.path) ?? []), s.id]);
      if ((s.places?.length ?? 0) > 1) {
        multi.set(s.path, (multi.get(s.path) ?? 0) + 1);
      }
    }
    const dirs = new Map<string, TreeNode[]>();
    for (const f of [...b.files].sort((x, y) => x.path.localeCompare(y.path))) {
      const dir = f.path.includes('/') ? f.path.substring(0, f.path.lastIndexOf('/')) : '';
      const ids = byFile.get(f.path) ?? [];
      const node: TreeNode = {
        id: 'file:' + f.path,
        label: f.path.substring(f.path.lastIndexOf('/') + 1),
        hint: `+${f.additions} −${f.deletions}`,
        icon: 'file',
        depth: 1,
        css: 'st-' + f.status,
        selection: { kind: 'file', path: f.path },
        slots: ids.map((segmentId) => ({ segmentId, place: null })),
        scope: `file:${f.path}`,
        multi: multi.get(f.path),
      };
      dirs.set(dir, [...(dirs.get(dir) ?? []), node]);
    }
    return [...dirs.entries()].map(([dir, files]) => ({
      id: 'dir:' + dir,
      label: dir || '/',
      icon: 'folder',
      depth: 0,
      selection: files.length === 1 ? files[0].selection : ({ kind: 'file', path: files[0].selection.kind === 'file' ? files[0].selection.path : '' } as Selection),
      slots: files.flatMap((f) => f.slots),
      scope: `dir:${dir}`,
      children: files,
    }));
  });

  /** The visible rows of the active tab (tree flattened, honouring collapsed nodes). */
  protected readonly rows = computed(() => {
    const roots = this.tab() === 'categories' ? this.categoryTree() : this.tab() === 'flows' ? this.flowTree() : this.fileTree();
    const out: TreeNode[] = [];
    const walk = (nodes: TreeNode[]) => {
      for (const n of nodes) {
        out.push(n);
        if (n.children?.length && this.isOpen(n)) {
          walk(n.children);
        }
      }
    };
    walk(roots);
    return out;
  });

  /** Groups in the category tab start collapsed (they can be long); categories start expanded. */
  private readonly expandedGroups = signal<ReadonlySet<string>>(new Set());

  protected isOpen(n: TreeNode): boolean {
    if (this.tab() === 'categories' && n.depth >= 1) {
      return this.expandedGroups().has(n.id);
    }
    return !this.collapsed().has(n.id);
  }

  protected toggleOpen(n: TreeNode, event: Event): void {
    event.stopPropagation();
    if (this.tab() === 'categories' && n.depth >= 1) {
      const next = new Set(this.expandedGroups());
      next.has(n.id) ? next.delete(n.id) : next.add(n.id);
      this.expandedGroups.set(next);
      return;
    }
    const next = new Set(this.collapsed());
    next.has(n.id) ? next.delete(n.id) : next.add(n.id);
    this.collapsed.set(next);
  }

  protected progress(n: TreeNode) {
    return this.store.progressOf(n.slots);
  }

  protected isSelected(n: TreeNode): boolean {
    if (n.focus) return false;
    const sel = this.store.selection();
    const t = n.selection;
    if (sel.kind !== t.kind) return false;
    if (sel.kind === 'file' && t.kind === 'file') return sel.path === t.path && n.depth > 0;
    return 'id' in sel && 'id' in t && sel.id === t.id;
  }

  protected open(n: TreeNode): void {
    if (n.id.startsWith('dir:') && n.children?.length) {
      this.toggleOpen(n, new Event('click'));
      return;
    }
    this.store.select(n.selection);
    if (n.focus) {
      this.store.focusSegment.set(n.focus);
    }
  }

  protected mark(n: TreeNode, event: Event): void {
    event.stopPropagation();
    this.store.toggle(this.store.linesOf(n.slots), n.scope);
  }

  protected setTab(t: Tab): void {
    this.tab.set(t);
  }
}
