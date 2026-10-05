import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Modal } from '../ui/modal';
import { Shortcut, ShortcutRegistry, ShortcutScope } from './shortcut-registry';

function key(
  value: string,
  init: KeyboardEventInit = {},
  target: HTMLElement = document.body,
): KeyboardEvent {
  const event = new KeyboardEvent('keydown', {
    key: value,
    bubbles: true,
    cancelable: true,
    ...init,
  });
  Object.defineProperty(event, 'target', { value: target });
  return event;
}

function scope(id: string, ...shortcuts: Shortcut[]): ShortcutScope {
  return { id, title: () => id, shortcuts };
}

function shortcut(keys: string[], run: () => void, extra: Partial<Shortcut> = {}): Shortcut {
  return { keys, caps: keys, label: () => keys.join('/'), run, ...extra };
}

@Component({
  selector: 'q-host',
  imports: [Modal],
  template: `<q-modal title="Open"><span>body</span></q-modal>`,
})
class DialogHost {}

describe('ShortcutRegistry', () => {
  let registry: ShortcutRegistry;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    registry = TestBed.inject(ShortcutRegistry);
  });

  it('runs the shortcut a key names and takes the key from the browser', () => {
    const run = vi.fn();
    registry.register(scope('board', shortcut(['r'], run)));
    const event = key('r');

    expect(registry.dispatch(event)).toBe(true);

    expect(run).toHaveBeenCalledOnce();
    expect(event.defaultPrevented).toBe(true);
  });

  it('does nothing, and leaves the key to the browser, when no shortcut names it', () => {
    registry.register(scope('board', shortcut(['r'], vi.fn())));
    const event = key('z');

    expect(registry.dispatch(event)).toBe(false);
    expect(event.defaultPrevented).toBe(false);
  });

  it('never claims a key pressed with Ctrl, Cmd or Alt: those belong to the browser', () => {
    const run = vi.fn();
    registry.register(scope('board', shortcut(['r'], run)));

    expect(registry.dispatch(key('r', { ctrlKey: true }))).toBe(false);
    expect(registry.dispatch(key('r', { metaKey: true }))).toBe(false);
    expect(registry.dispatch(key('r', { altKey: true }))).toBe(false);
    expect(run).not.toHaveBeenCalled();
  });

  it('keeps a letter and its Shift form apart: a is not Shift+A, but ? is Shift+/ and still works', () => {
    const letter = vi.fn();
    const sheet = vi.fn();
    registry.register(scope('board', shortcut(['a'], letter), shortcut(['?'], sheet)));

    expect(registry.dispatch(key('A', { shiftKey: true }))).toBe(false);
    expect(registry.dispatch(key('a', { shiftKey: true }))).toBe(false);
    expect(registry.dispatch(key('?', { shiftKey: true }))).toBe(true);
    expect(letter).not.toHaveBeenCalled();
    expect(sheet).toHaveBeenCalledOnce();
  });

  describe('while the operator is typing', () => {
    it('leaves a letter to the text field it was typed into', () => {
      const run = vi.fn();
      registry.register(scope('board', shortcut(['a'], run)));
      const search = document.createElement('input');
      search.type = 'search';

      expect(registry.dispatch(key('a', {}, search))).toBe(false);
      expect(registry.dispatch(key('a', {}, document.createElement('textarea')))).toBe(false);
      expect(registry.dispatch(key('a', {}, document.createElement('select')))).toBe(false);
      expect(run).not.toHaveBeenCalled();
    });

    it('fires a shortcut that says it is meant to, such as F2 from inside a search box', () => {
      const run = vi.fn();
      registry.register(scope('shell', shortcut(['F2'], run, { inField: true })));
      const search = document.createElement('input');

      expect(registry.dispatch(key('F2', {}, search))).toBe(true);
      expect(run).toHaveBeenCalledOnce();
    });

    it('still fires on a checkbox, which takes no typed text', () => {
      const run = vi.fn();
      registry.register(scope('board', shortcut(['j'], run)));
      const box = document.createElement('input');
      box.type = 'checkbox';

      expect(registry.dispatch(key('j', {}, box))).toBe(true);
    });
  });

  it('leaves Space and Enter to a button, a link or a checkbox, which activate themselves', () => {
    const run = vi.fn();
    registry.register(scope('board', shortcut([' '], run)));
    const box = document.createElement('input');
    box.type = 'checkbox';

    expect(registry.dispatch(key(' ', {}, document.createElement('button')))).toBe(false);
    expect(registry.dispatch(key(' ', {}, document.createElement('a')))).toBe(false);
    expect(registry.dispatch(key(' ', {}, box))).toBe(false);
    expect(run).not.toHaveBeenCalled();

    // A focused table row is not such a control.
    expect(registry.dispatch(key(' ', {}, document.createElement('tr')))).toBe(true);
  });

  it('stands down for the screen behind a dialog, except for a key that is not about that screen', async () => {
    const board = vi.fn();
    const global = vi.fn();
    registry.register(scope('board', shortcut(['x'], board)));
    registry.register(scope('shell', shortcut(['F2'], global, { overDialog: true })));
    const fixture = TestBed.createComponent(DialogHost);
    fixture.detectChanges();

    expect(registry.dispatch(key('x'))).toBe(false);
    expect(registry.dispatch(key('F2'))).toBe(true);
    expect(board).not.toHaveBeenCalled();
    expect(global).toHaveBeenCalledOnce();

    fixture.destroy();
    // A destroyed test fixture leaves its host element behind until the test ends.
    (fixture.nativeElement as HTMLElement).remove();
    expect(registry.dispatch(key('x'))).toBe(true);
  });

  it('also stands down for a dialog that only marks itself aria-modal, which OverlayBehaviour does not know', () => {
    const run = vi.fn();
    registry.register(scope('board', shortcut(['x'], run)));
    const stray = document.createElement('div');
    stray.setAttribute('role', 'dialog');
    stray.setAttribute('aria-modal', 'true');
    document.body.appendChild(stray);
    try {
      expect(registry.dispatch(key('x'))).toBe(false);
    } finally {
      stray.remove();
    }

    expect(registry.dispatch(key('x'))).toBe(true);
    expect(run).toHaveBeenCalledOnce();
  });

  it('asks the newest scope first, and falls through when its shortcut cannot act', () => {
    const shell = vi.fn();
    const board = vi.fn();
    let boardCanAct = true;
    registry.register(scope('shell', shortcut(['n'], shell)));
    registry.register(scope('board', shortcut(['n'], board, { enabled: () => boardCanAct })));

    registry.dispatch(key('n'));
    expect(board).toHaveBeenCalledOnce();
    expect(shell).not.toHaveBeenCalled();

    boardCanAct = false;
    registry.dispatch(key('n'));
    expect(shell).toHaveBeenCalledOnce();
  });

  it('hands the event to enabled(), so a shortcut can depend on where focus is', () => {
    const run = vi.fn();
    registry.register(
      scope(
        'board',
        shortcut(['Escape'], run, {
          inField: true,
          enabled: (event) => (event.target as HTMLElement).tagName === 'INPUT',
        }),
      ),
    );

    expect(registry.dispatch(key('Escape', {}, document.createElement('div')))).toBe(false);
    expect(registry.dispatch(key('Escape', {}, document.createElement('input')))).toBe(true);
  });

  it('forgets a scope when it is unregistered, and replaces one registered twice under an id', () => {
    const first = vi.fn();
    const second = vi.fn();
    const unregister = registry.register(scope('board', shortcut(['r'], first)));
    registry.register(scope('board', shortcut(['r'], second)));

    expect(registry.scopes().map((s) => s.id)).toEqual(['board']);
    registry.dispatch(key('r'));
    expect(second).toHaveBeenCalledOnce();
    expect(first).not.toHaveBeenCalled();

    unregister();
    // The first handle's scope was already replaced; unregistering it must not remove the live one.
    expect(registry.scopes().map((s) => s.id)).toEqual(['board']);
  });

  it('lists a key an element handles natively without ever running anything for it', () => {
    registry.register(
      scope('board', { keys: ['Enter'], caps: ['Enter'], label: () => 'Open the order' }),
    );

    expect(registry.dispatch(key('Enter', {}, document.createElement('tr')))).toBe(false);
    expect(registry.scopes()[0].shortcuts[0].label()).toBe('Open the order');
  });

  it('opens and closes the cheat-sheet', () => {
    expect(registry.sheetOpen()).toBe(false);
    registry.openSheet();
    expect(registry.sheetOpen()).toBe(true);
    registry.closeSheet();
    expect(registry.sheetOpen()).toBe(false);
  });
});
