import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ActionMenu, ActionMenuItem } from './action-menu';

@Component({
  selector: 'q-action-menu-host',
  imports: [ActionMenu],
  template: `
    <button type="button" id="outside">Elsewhere</button>
    <q-action-menu
      triggerLabel="More actions"
      [items]="items()"
      [open]="open()"
      (openChange)="open.set($event)"
      (select)="chosen.push($event)"
    />
  `,
})
class ActionMenuHost {
  readonly open = signal(false);
  readonly items = signal<readonly ActionMenuItem[]>([
    { id: 'print', label: 'Print' },
    { id: 'duplicate', label: 'Duplicate' },
    { id: 'cancel', label: 'Cancel order', destructive: true },
  ]);
  readonly chosen: string[] = [];
}

function key(name: string): KeyboardEvent {
  return new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true });
}

describe('ActionMenu', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<ActionMenuHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(ActionMenuHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function trigger(): HTMLButtonElement {
    return host.querySelector<HTMLButtonElement>('[data-testid="q-action-menu-trigger"]')!;
  }

  function items(): HTMLButtonElement[] {
    return [...host.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')];
  }

  function openMenu(): void {
    trigger().focus();
    trigger().click();
    fixture.detectChanges();
  }

  function tabIndices(): (string | null)[] {
    return items().map((item) => item.getAttribute('tabindex'));
  }

  it('announces itself as a menu trigger and reports whether it is open', () => {
    expect(trigger().getAttribute('aria-haspopup')).toBe('menu');
    expect(trigger().getAttribute('aria-expanded')).toBe('false');

    openMenu();

    expect(trigger().getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('[data-testid="q-action-menu-list"]')?.getAttribute('role')).toBe(
      'menu',
    );
  });

  it('opens from the keyboard with ArrowDown', () => {
    trigger().dispatchEvent(key('ArrowDown'));
    fixture.detectChanges();

    expect(fixture.componentInstance.open()).toBe(true);
  });

  it('is one tab stop: exactly one item is tabbable at a time', () => {
    openMenu();
    expect(tabIndices()).toEqual(['0', '-1', '-1']);
  });

  it('moves the single tab stop with ArrowDown, and the caret with it', () => {
    openMenu();
    items()[0].dispatchEvent(key('ArrowDown'));
    fixture.detectChanges();

    expect(tabIndices()).toEqual(['-1', '0', '-1']);
    expect(document.activeElement).toBe(items()[1]);
  });

  it('wraps from the last item to the first, so holding ArrowDown never falls out of the menu', () => {
    openMenu();
    items()[0].dispatchEvent(key('ArrowDown'));
    fixture.detectChanges();
    items()[1].dispatchEvent(key('ArrowDown'));
    fixture.detectChanges();
    expect(tabIndices()).toEqual(['-1', '-1', '0']);

    items()[2].dispatchEvent(key('ArrowDown'));
    fixture.detectChanges();

    expect(tabIndices()).toEqual(['0', '-1', '-1']);
  });

  it('wraps backwards from the first item to the last', () => {
    openMenu();
    items()[0].dispatchEvent(key('ArrowUp'));
    fixture.detectChanges();

    expect(tabIndices()).toEqual(['-1', '-1', '0']);
  });

  it('reaches both ends with Home and End', () => {
    openMenu();
    items()[0].dispatchEvent(key('End'));
    fixture.detectChanges();
    expect(tabIndices()).toEqual(['-1', '-1', '0']);

    items()[2].dispatchEvent(key('Home'));
    fixture.detectChanges();
    expect(tabIndices()).toEqual(['0', '-1', '-1']);
  });

  it('steps over a disabled item rather than parking the caret on it', () => {
    fixture.componentInstance.items.set([
      { id: 'print', label: 'Print' },
      { id: 'duplicate', label: 'Duplicate', disabled: true },
      { id: 'cancel', label: 'Cancel order' },
    ]);
    openMenu();

    items()[0].dispatchEvent(key('ArrowDown'));
    fixture.detectChanges();

    expect(tabIndices()).toEqual(['-1', '-1', '0']);
  });

  it('closes on Escape', () => {
    openMenu();
    items()[0].dispatchEvent(key('Escape'));
    fixture.detectChanges();

    expect(fixture.componentInstance.open()).toBe(false);
  });

  it('closes on a pointer press outside itself', () => {
    openMenu();

    host
      .querySelector<HTMLButtonElement>('#outside')!
      .dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.open()).toBe(false);
  });

  it('stays open on a pointer press inside itself', () => {
    openMenu();

    items()[0].dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.open()).toBe(true);
  });

  it('emits the chosen item and closes; a disabled item emits nothing', () => {
    fixture.componentInstance.items.set([
      { id: 'print', label: 'Print' },
      { id: 'duplicate', label: 'Duplicate', disabled: true },
    ]);
    openMenu();

    items()[1].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.chosen).toEqual([]);
    expect(fixture.componentInstance.open()).toBe(true);

    items()[0].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.chosen).toEqual(['print']);
    expect(fixture.componentInstance.open()).toBe(false);
  });
});
