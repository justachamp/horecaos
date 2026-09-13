import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  booleanAttribute,
  computed,
  effect,
  input,
  output,
  signal,
  viewChildren,
} from '@angular/core';

/** The pill/dot tone `q-status-pill` already uses — kept identical so a host can share one status→tone mapping. */
export type TreeViewTone = 'none' | 'info' | 'success' | 'warning' | 'danger';

/** One node `q-tree-view` renders. The tree itself knows nothing about what the node *is*. */
export interface TreeViewNode {
  readonly id: string;
  /** `null` for a top-level node. A value naming no node in {@link TreeView.nodes} renders as top-level too. */
  readonly parentId: string | null;
  /** Already translated / already the tenant's own data — the tree renders it verbatim. */
  readonly label: string;
  /** Orders siblings. Ties break on {@link id} so rendering stays stable. */
  readonly sortOrder: number;
  /** A small coloured dot before the label — a status at a glance, no text. */
  readonly tone?: TreeViewTone;
  /** A trailing count badge — product count, item count, whatever the host means by it. `null`/`undefined` hides it. */
  readonly count?: number | null;
  readonly disabled?: boolean;
}

/**
 * One node should move — the host's own id order to persist, not a sortOrder
 * number this component invents. `newIndex` is the node's 0-based position
 * among {@link newParentId}'s children *after* the move, in the same
 * sortOrder-then-id order the tree itself renders siblings in.
 */
export interface TreeViewMove {
  readonly id: string;
  readonly newParentId: string | null;
  readonly newIndex: number;
}

/** A node's label was edited in place (Enter to rename). The host persists it; this component holds no text of its own. */
export interface TreeViewRename {
  readonly id: string;
  readonly name: string;
}

interface VisibleRow {
  readonly node: TreeViewNode;
  readonly depth: number;
  readonly hasChildren: boolean;
  readonly childCount: number;
  readonly expanded: boolean;
}

/**
 * Hierarchical drag-reorder with keyboard navigation, done once (design-system
 * gap `X.23`: "SortableList / TreeView with drag-reorder", named against the
 * category tree, the modifier-group→modifier→variant tree, story slides, home
 * groups, and the website menu — none of which had one until this).
 *
 * **Presentational only**, the same discipline `q-rule-list` documents for its
 * own reorder gesture: no backend, no capability, no knowledge of what a node
 * *is*. The host owns persisting {@link move} and {@link rename}; this owns
 * only the gesture.
 *
 * **Reorder two ways, because a mouse is not guaranteed** — `q-rule-list`'s
 * own reasoning, extended from a flat list to a tree: native HTML5
 * `draggable` reparents onto whatever row a node is dropped on (as that row's
 * last child) or onto the pinned root strip (top level); a keyboard operator
 * gets the full set the spec asks for instead — arrow keys move focus,
 * Left/Right collapse/expand (or, at a leaf, walk to the parent/first child),
 * `Alt+↑`/`Alt+↓` reorder among the current siblings, `Alt+←` promotes a node
 * to be its parent's own sibling, and `Enter` renames in place.
 *
 * **A node this component would drop is rendered, not hidden.** A cycle in
 * the supplied graph (host data corrupted, or a backend defect) used to mean
 * an indented-list renderer's cycle guard silently `continue`d past the
 * repeated node — invisible, no error, nothing to click. Every node reachable
 * from a root renders in the ordinary tree; anything left over (it is either
 * part of a cycle, or points at a parent that only exists inside one) renders
 * in a separate, visibly red section instead of vanishing.
 */
@Component({
  selector: 'q-tree-view',
  templateUrl: './tree-view.html',
  styleUrl: './tree-view.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TreeView {
  readonly nodes = input.required<readonly TreeViewNode[]>();
  readonly selectedId = input<string | null>(null);
  readonly busy = input(false, { transform: booleanAttribute });
  /** Already translated. The tree's own accessible name — there is no visible heading otherwise. */
  readonly ariaLabel = input<string | null>(null);
  /** Hides the pinned "top level" drop target — a tree the host never lets flatten further. */
  readonly allowReparentToRoot = input(true, { transform: booleanAttribute });
  /** Already translated. Label of the pinned root drop zone. */
  readonly rootLabel = input<string>('—');
  /** Already translated. Heading over the cyclic section, when one renders. */
  readonly cycleWarningLabel = input<string>('Cycle detected');

  readonly select = output<string>();
  readonly move = output<TreeViewMove>();
  readonly rename = output<TreeViewRename>();

  protected readonly collapsed = signal<ReadonlySet<string>>(new Set());
  protected readonly focusedId = signal<string | null>(null);
  protected readonly renamingId = signal<string | null>(null);
  protected readonly renameDraft = signal('');
  protected readonly dragId = signal<string | null>(null);
  protected readonly dragOverId = signal<string | null>(null);
  protected readonly dragOverRoot = signal(false);

  private readonly rowButtons = viewChildren<ElementRef<HTMLElement>>('row');
  private readonly renameInput = viewChildren<ElementRef<HTMLInputElement>>('renameField');

  private readonly nodeById = computed(() => {
    const byId = new Map<string, TreeViewNode>();
    for (const node of this.nodes()) {
      byId.set(node.id, node);
    }
    return byId;
  });

  private readonly childrenByParent = computed(() => {
    const byParent = new Map<string | null, TreeViewNode[]>();
    const ids = this.nodeById();
    for (const node of this.nodes()) {
      // A parentId naming no node in this set renders as a root — the same
      // leniency a corrupted or partially-loaded graph deserves over a blank
      // screen, and exactly the case a cyclic ancestor chain also produces.
      const parentKey = node.parentId !== null && ids.has(node.parentId) ? node.parentId : null;
      const siblings = byParent.get(parentKey) ?? [];
      siblings.push(node);
      byParent.set(parentKey, siblings);
    }
    for (const siblings of byParent.values()) {
      siblings.sort((a, b) => a.sortOrder - b.sortOrder || a.id.localeCompare(b.id));
    }
    return byParent;
  });

  /** This node's children, sorted — the order {@link TreeViewMove.newIndex} counts positions in. */
  protected childrenOf(parentId: string | null): readonly TreeViewNode[] {
    return this.childrenByParent().get(parentId) ?? [];
  }

  protected childCountOf(parentId: string | null): number {
    return this.childrenOf(parentId).length;
  }

  private readonly traversal = computed(() => {
    const byParent = this.childrenByParent();
    const collapsedIds = this.collapsed();
    const visible: VisibleRow[] = [];
    const visited = new Set<string>();

    const visit = (parentId: string | null, depth: number): void => {
      for (const node of byParent.get(parentId) ?? []) {
        if (visited.has(node.id)) {
          // Reached twice from valid roots: the graph itself is cyclic. Stop
          // walking this branch rather than recursing forever; the node
          // keeps its first placement and the second is left for the cyclic
          // sweep to note.
          continue;
        }
        visited.add(node.id);
        const children = byParent.get(node.id) ?? [];
        const expanded = !collapsedIds.has(node.id);
        visible.push({
          node,
          depth,
          hasChildren: children.length > 0,
          childCount: children.length,
          expanded,
        });
        if (expanded) {
          visit(node.id, depth + 1);
        }
      }
    };
    visit(null, 0);

    const cyclic = this.nodes().filter((node) => !visited.has(node.id));
    return { visible, cyclic };
  });

  protected readonly visibleRows = computed(() => this.traversal().visible);
  protected readonly cyclicNodes = computed(() => this.traversal().cyclic);

  constructor() {
    // Moves the DOM focus to whichever row `focusedId` names, the same
    // roving-tabindex mechanics `q-action-menu` documents for its own list —
    // one tab stop for the whole tree, and the arrow keys move which row it
    // lands on.
    effect(() => {
      const id = this.focusedId();
      if (id === null) {
        return;
      }
      const row = this.rowButtons().find((ref) => ref.nativeElement.dataset['nodeId'] === id);
      row?.nativeElement.focus();
    });
    effect(() => {
      if (this.renamingId() !== null) {
        this.renameInput()[0]?.nativeElement.focus();
        this.renameInput()[0]?.nativeElement.select();
      }
    });
  }

  protected isFocused(id: string): boolean {
    return this.focusedId() === id
      ? true
      : this.focusedId() === null && this.visibleRows()[0]?.node.id === id;
  }

  protected onRowFocus(id: string): void {
    this.focusedId.set(id);
  }

  protected onRowClick(node: TreeViewNode): void {
    if (node.disabled || this.busy()) {
      return;
    }
    this.focusedId.set(node.id);
    this.select.emit(node.id);
  }

  protected toggleCollapsed(id: string, event: Event): void {
    event.stopPropagation();
    this.collapsed.update((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  // ------------------------------------------------------------ keyboard

  protected onKeydown(event: KeyboardEvent): void {
    if (this.renamingId() !== null || this.busy()) {
      return;
    }
    const rows = this.visibleRows();
    const currentId = this.focusedId() ?? rows[0]?.node.id ?? null;
    if (currentId === null) {
      return;
    }
    const index = rows.findIndex((row) => row.node.id === currentId);
    if (index === -1) {
      return;
    }

    if (event.altKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
      event.preventDefault();
      this.reorder(currentId, event.key === 'ArrowUp' ? -1 : 1);
      return;
    }
    if (event.altKey && event.key === 'ArrowLeft') {
      event.preventDefault();
      this.promote(currentId);
      return;
    }

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (index < rows.length - 1) {
          this.focusedId.set(rows[index + 1].node.id);
        }
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (index > 0) {
          this.focusedId.set(rows[index - 1].node.id);
        }
        break;
      case 'ArrowRight': {
        event.preventDefault();
        const row = rows[index];
        if (row.hasChildren && !row.expanded) {
          this.toggleCollapsed(row.node.id, event);
        } else if (row.hasChildren) {
          const firstChild = rows[index + 1];
          if (firstChild && firstChild.depth === row.depth + 1) {
            this.focusedId.set(firstChild.node.id);
          }
        }
        break;
      }
      case 'ArrowLeft': {
        event.preventDefault();
        const row = rows[index];
        if (row.hasChildren && row.expanded) {
          this.toggleCollapsed(row.node.id, event);
        } else if (row.node.parentId !== null) {
          this.focusedId.set(row.node.parentId);
        }
        break;
      }
      case 'Enter':
        event.preventDefault();
        this.beginRename(currentId);
        break;
      default:
        break;
    }
  }

  private reorder(id: string, direction: -1 | 1): void {
    const node = this.nodeById().get(id);
    if (!node) {
      return;
    }
    const siblings = this.childrenOf(node.parentId);
    const at = siblings.findIndex((sibling) => sibling.id === id);
    const target = at + direction;
    if (at === -1 || target < 0 || target >= siblings.length) {
      return;
    }
    this.move.emit({ id, newParentId: node.parentId, newIndex: target });
  }

  private promote(id: string): void {
    const node = this.nodeById().get(id);
    if (!node || node.parentId === null) {
      return;
    }
    const parent = this.nodeById().get(node.parentId);
    const grandparentId = parent ? parent.parentId : null;
    const newSiblings = this.childrenOf(grandparentId);
    const parentIndex = newSiblings.findIndex((sibling) => sibling.id === node.parentId);
    const newIndex = parentIndex === -1 ? newSiblings.length : parentIndex + 1;
    this.move.emit({ id, newParentId: grandparentId, newIndex });
  }

  // ------------------------------------------------------------ rename

  protected beginRename(id: string): void {
    const node = this.nodeById().get(id);
    if (!node || node.disabled) {
      return;
    }
    this.renamingId.set(id);
    this.renameDraft.set(node.label);
  }

  protected onRenameInput(value: string): void {
    this.renameDraft.set(value);
  }

  protected confirmRename(): void {
    const id = this.renamingId();
    const name = this.renameDraft().trim();
    this.renamingId.set(null);
    if (id !== null && name !== '') {
      this.rename.emit({ id, name });
    }
  }

  protected cancelRename(): void {
    this.renamingId.set(null);
  }

  // ------------------------------------------------------------ drag and drop

  protected onDragStart(id: string, event: DragEvent): void {
    if (this.busy()) {
      event.preventDefault();
      return;
    }
    this.dragId.set(id);
    event.dataTransfer?.setData('text/plain', id);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  protected onDragOver(id: string, event: DragEvent): void {
    if (!this.canDropOn(id)) {
      return;
    }
    event.preventDefault();
    this.dragOverId.set(id);
  }

  protected onDragLeave(id: string): void {
    if (this.dragOverId() === id) {
      this.dragOverId.set(null);
    }
  }

  protected onDrop(targetId: string, event: DragEvent): void {
    event.preventDefault();
    // canDropOn reads the dragId signal itself, so it must run before that
    // signal is cleared below — clearing first would make every drop refuse
    // itself, since "nothing is being dragged" is canDropOn's own first check.
    const draggedId = this.dragId();
    const canDrop = this.canDropOn(targetId);
    this.dragId.set(null);
    this.dragOverId.set(null);
    if (draggedId === null || !canDrop) {
      return;
    }
    this.move.emit({ id: draggedId, newParentId: targetId, newIndex: this.childCountOf(targetId) });
  }

  protected onRootDragOver(event: DragEvent): void {
    if (this.dragId() === null) {
      return;
    }
    event.preventDefault();
    this.dragOverRoot.set(true);
  }

  protected onRootDragLeave(): void {
    this.dragOverRoot.set(false);
  }

  protected onRootDrop(event: DragEvent): void {
    event.preventDefault();
    const draggedId = this.dragId();
    this.dragId.set(null);
    this.dragOverRoot.set(false);
    if (draggedId === null) {
      return;
    }
    this.move.emit({ id: draggedId, newParentId: null, newIndex: this.childCountOf(null) });
  }

  protected onDragEnd(): void {
    this.dragId.set(null);
    this.dragOverId.set(null);
    this.dragOverRoot.set(false);
  }

  /** Refuses dropping a node onto itself or onto one of its own descendants — either would create a cycle. */
  private canDropOn(targetId: string): boolean {
    const draggedId = this.dragId();
    if (draggedId === null || draggedId === targetId) {
      return false;
    }
    const byId = this.nodeById();
    let current: string | null = targetId;
    const guard = new Set<string>();
    while (current !== null) {
      if (current === draggedId) {
        return false;
      }
      // `Set.add` returns the set itself, not whether the value was new —
      // the membership check has to be its own call, or a pre-existing cycle
      // elsewhere in the graph (unrelated to this drag) walks forever.
      if (guard.has(current)) {
        break;
      }
      guard.add(current);
      current = byId.get(current)?.parentId ?? null;
    }
    return true;
  }

  protected isDropTarget(id: string): boolean {
    return this.dragOverId() === id && this.canDropOn(id);
  }
}
