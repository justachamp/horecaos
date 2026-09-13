import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { TreeView, TreeViewMove, TreeViewNode, TreeViewRename } from './tree-view';

const NODES: readonly TreeViewNode[] = [
  { id: 'food', parentId: null, label: 'Food', sortOrder: 0, tone: 'success', count: 2 },
  { id: 'hot', parentId: 'food', label: 'Hot dishes', sortOrder: 0, count: 1 },
  { id: 'cold', parentId: 'food', label: 'Cold dishes', sortOrder: 1, count: 0 },
  { id: 'drinks', parentId: null, label: 'Drinks', sortOrder: 1, tone: 'warning', count: 0 },
];

@Component({
  selector: 'q-tree-view-host',
  imports: [TreeView],
  template: `
    <q-tree-view
      [nodes]="nodes()"
      [selectedId]="selectedId()"
      cycleWarningLabel="Cycle detected"
      (select)="lastSelected = $event"
      (move)="lastMove = $event"
      (rename)="lastRename = $event"
    />
  `,
})
class TreeViewHost {
  readonly nodes = signal<readonly TreeViewNode[]>(NODES);
  readonly selectedId = signal<string | null>(null);
  lastSelected: string | null = null;
  lastMove: TreeViewMove | null = null;
  lastRename: TreeViewRename | null = null;
}

describe('TreeView', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<TreeViewHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(TreeViewHost);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function rows(): HTMLElement[] {
    return [...host.querySelectorAll<HTMLElement>('[data-testid="tree-node"]')];
  }

  function rowFor(id: string): HTMLElement {
    return host.querySelector(`[data-node-id="${id}"]`) as HTMLElement;
  }

  it('renders a parent above its children, indented, with counts and a status dot', () => {
    const all = rows();
    expect(all).toHaveLength(4);
    expect(all[0].dataset['nodeId']).toBe('food');
    expect(all[1].dataset['nodeId']).toBe('hot');
    expect(all[2].dataset['nodeId']).toBe('cold');
    expect(all[3].dataset['nodeId']).toBe('drinks');

    expect(rowFor('hot').style.paddingLeft).not.toBe(rowFor('food').style.paddingLeft);
    expect(rowFor('food').querySelector('[data-testid="tree-node-count"]')?.textContent).toBe('2');
    expect(rowFor('food').querySelector('[data-testid="tree-node-dot"]')).toBeTruthy();
  });

  it('clicking a row selects it', () => {
    rowFor('hot').dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.lastSelected).toBe('hot');
  });

  it('collapsing a parent hides its children, and re-expanding shows them again', () => {
    const caret = rowFor('food').querySelector(
      '[data-testid="tree-node-caret"]',
    ) as HTMLButtonElement;
    caret.click();
    fixture.detectChanges();

    expect(rows().map((row) => row.dataset['nodeId'])).toEqual(['food', 'drinks']);

    caret.click();
    fixture.detectChanges();

    expect(rows().map((row) => row.dataset['nodeId'])).toEqual(['food', 'hot', 'cold', 'drinks']);
  });

  describe('keyboard', () => {
    it('ArrowDown moves focus to the next visible row', () => {
      rowFor('food').dispatchEvent(new MouseEvent('click', { bubbles: true }));
      rowFor('food').focus();
      rowFor('food').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }),
      );
      fixture.detectChanges();

      expect(document.activeElement).toBe(rowFor('hot'));
    });

    it('ArrowLeft collapses an expanded parent', () => {
      rowFor('food').focus();
      rowFor('food').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }),
      );
      fixture.detectChanges();

      expect(rows().map((row) => row.dataset['nodeId'])).toEqual(['food', 'drinks']);
    });

    it('Alt+ArrowDown reorders a node among its siblings and emits move', () => {
      rowFor('hot').focus();
      rowFor('hot').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true, bubbles: true }),
      );
      fixture.detectChanges();

      expect(fixture.componentInstance.lastMove).toEqual({
        id: 'hot',
        newParentId: 'food',
        newIndex: 1,
      });
    });

    it('Alt+ArrowDown at the last sibling does nothing', () => {
      rowFor('cold').focus();
      rowFor('cold').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true, bubbles: true }),
      );
      fixture.detectChanges();

      expect(fixture.componentInstance.lastMove).toBeNull();
    });

    it('Alt+ArrowLeft promotes a node to be its parent’s own sibling', () => {
      rowFor('hot').focus();
      rowFor('hot').dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowLeft', altKey: true, bubbles: true }),
      );
      fixture.detectChanges();

      // "food" sits at root index 0, so promoting "hot" out from under it
      // lands "hot" right after "food" — root index 1.
      expect(fixture.componentInstance.lastMove).toEqual({
        id: 'hot',
        newParentId: null,
        newIndex: 1,
      });
    });

    it('Enter begins a rename, and a second Enter confirms it', () => {
      rowFor('drinks').focus();
      rowFor('drinks').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      fixture.detectChanges();

      const input = host.querySelector(
        '[data-testid="tree-node-rename-input"]',
      ) as HTMLInputElement;
      expect(input).toBeTruthy();
      input.value = 'Beverages';
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastRename).toEqual({ id: 'drinks', name: 'Beverages' });
      expect(host.querySelector('[data-testid="tree-node-rename-input"]')).toBeNull();
    });

    it('Escape cancels a rename without emitting', () => {
      rowFor('drinks').focus();
      rowFor('drinks').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      fixture.detectChanges();

      const input = host.querySelector(
        '[data-testid="tree-node-rename-input"]',
      ) as HTMLInputElement;
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastRename).toBeNull();
      expect(host.querySelector('[data-testid="tree-node-rename-input"]')).toBeNull();
    });
  });

  describe('drag and drop', () => {
    // jsdom has no DragEvent global; a plain Event exercises the same
    // listeners since the component only reads `dataTransfer` through
    // optional chaining — the same shortcut `rule-list.spec.ts` documents.
    function dragEvent(type: string): Event {
      return new Event(type, { bubbles: true, cancelable: true });
    }

    it('dropping a node onto another reparents it as that node’s last child', () => {
      rowFor('drinks').dispatchEvent(dragEvent('dragstart'));
      rowFor('food').dispatchEvent(dragEvent('dragover'));
      rowFor('food').dispatchEvent(dragEvent('drop'));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastMove).toEqual({
        id: 'drinks',
        newParentId: 'food',
        newIndex: 2,
      });
    });

    it('refuses to drop a node onto its own descendant', () => {
      rowFor('food').dispatchEvent(dragEvent('dragstart'));
      rowFor('hot').dispatchEvent(dragEvent('dragover'));
      rowFor('hot').dispatchEvent(dragEvent('drop'));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastMove).toBeNull();
    });

    it('dropping onto the root zone reparents a node to top level', () => {
      rowFor('hot').dispatchEvent(dragEvent('dragstart'));
      const rootZone = host.querySelector('[data-testid="tree-view-root-zone"]') as HTMLElement;
      rootZone.dispatchEvent(dragEvent('dragover'));
      rootZone.dispatchEvent(dragEvent('drop'));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastMove).toEqual({
        id: 'hot',
        newParentId: null,
        newIndex: 2,
      });
    });
  });

  describe('a cyclic graph', () => {
    it('renders the cyclic nodes in a separate, visible section instead of silently dropping them', () => {
      // a -> b -> a: neither ever reaches a real root, so an indented-list
      // renderer whose cycle guard simply `continue`s past a repeat would
      // render neither at all.
      fixture.componentInstance.nodes.set([
        { id: 'a', parentId: 'b', label: 'A', sortOrder: 0 },
        { id: 'b', parentId: 'a', label: 'B', sortOrder: 0 },
        { id: 'safe', parentId: null, label: 'Safe', sortOrder: 0 },
      ]);
      fixture.detectChanges();

      expect(rows().map((row) => row.dataset['nodeId'])).toEqual(['safe']);
      const cycleSection = host.querySelector('[data-testid="tree-view-cycle"]');
      expect(cycleSection).toBeTruthy();
      expect(cycleSection?.textContent).toContain('Cycle detected');
      expect(cycleSection?.textContent).toContain('A');
      expect(cycleSection?.textContent).toContain('B');
    });
  });
});
