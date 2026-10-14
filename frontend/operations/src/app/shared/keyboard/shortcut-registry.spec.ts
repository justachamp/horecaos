import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Modal } from '../ui/modal';
import {
  CHORD_WINDOW_MS,
  Shortcut,
  ShortcutRegistry,
  ShortcutScope,
  modifierCap,
} from './shortcut-registry';

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

  describe('a chord a screen asks for by name (batch 21, row X.1)', () => {
    it('answers Ctrl+Enter and Cmd+Enter to a shortcut that opted in, and to nothing else', () => {
      const create = vi.fn();
      const plain = vi.fn();
      registry.register(
        scope(
          'new-order',
          shortcut(['Enter'], create, { modifier: 'ctrlOrCmd', inField: true }),
          shortcut(['r'], plain),
        ),
      );
      const field = document.createElement('input');

      expect(registry.dispatch(key('Enter', { ctrlKey: true }, field))).toBe(true);
      expect(registry.dispatch(key('Enter', { metaKey: true }, field))).toBe(true);
      expect(create).toHaveBeenCalledTimes(2);
      // The same keys with a modifier the shortcut did not name are not its keys.
      expect(registry.dispatch(key('Enter', { ctrlKey: true, shiftKey: true }, field))).toBe(false);
      expect(registry.dispatch(key('Enter', { ctrlKey: true, altKey: true }, field))).toBe(false);
      expect(registry.dispatch(key('Enter', {}, field))).toBe(false);
      // And a shortcut that named no modifier still does not answer Ctrl+R: that is the browser's reload.
      expect(registry.dispatch(key('r', { ctrlKey: true }))).toBe(false);
      expect(plain).not.toHaveBeenCalled();
      expect(create).toHaveBeenCalledTimes(2);
    });

    it('does not run a modified chord inside a field unless it says it is meant to', () => {
      const save = vi.fn();
      registry.register(scope('hours', shortcut(['s'], save, { modifier: 'ctrlOrCmd' })));
      const field = document.createElement('input');

      expect(registry.dispatch(key('s', { ctrlKey: true }, field))).toBe(false);
      expect(registry.dispatch(key('S', { ctrlKey: true }))).toBe(true);
      expect(save).toHaveBeenCalledOnce();
    });

    it('matches Alt+arrows and the Alt+digit row by position, because Alt+1 prints another character on a Mac', () => {
      const down = vi.fn();
      const jump = vi.fn();
      registry.register(
        scope(
          'hours',
          shortcut(['ArrowDown'], down, { modifier: 'alt', inField: true }),
          shortcut(['1'], jump, { modifier: 'alt', codes: ['Digit1'] }),
        ),
      );

      expect(
        registry.dispatch(key('ArrowDown', { altKey: true }, document.createElement('input'))),
      ).toBe(true);
      expect(registry.dispatch(key('ArrowDown'))).toBe(false);
      expect(registry.dispatch(key('¡', { altKey: true, code: 'Digit1' }))).toBe(true);
      expect(registry.dispatch(key('1', { altKey: true }))).toBe(true);
      expect(registry.dispatch(key('1'))).toBe(false);
      expect(registry.dispatch(key('¡', { altKey: true, ctrlKey: true, code: 'Digit1' }))).toBe(
        false,
      );
      expect(down).toHaveBeenCalledOnce();
      expect(jump).toHaveBeenCalledTimes(2);
    });

    it('lets a screen use Shift+X as its own key, apart from x, and never for a shortcut that did not ask', () => {
      const lower = vi.fn();
      const upper = vi.fn();
      registry.register(
        scope('dispatch', shortcut(['x'], lower), shortcut(['x'], upper, { shift: true })),
      );

      expect(registry.dispatch(key('X', { shiftKey: true }))).toBe(true);
      expect(upper).toHaveBeenCalledOnce();
      expect(lower).not.toHaveBeenCalled();
      expect(registry.dispatch(key('x'))).toBe(true);
      expect(lower).toHaveBeenCalledOnce();
      expect(upper).toHaveBeenCalledOnce();
      expect(registry.dispatch(key('X', { shiftKey: true, ctrlKey: true }))).toBe(false);
    });

    it('keeps a named key and its Shift form apart too: ↓ moves, Shift+↓ extends, and a screen that asked only for ↓ is not asked', () => {
      const move = vi.fn();
      const extend = vi.fn();
      registry.register(scope('menus', shortcut(['ArrowDown'], move)));

      expect(registry.dispatch(key('ArrowDown', { shiftKey: true }))).toBe(false);
      expect(move).not.toHaveBeenCalled();
      expect(registry.dispatch(key('ArrowDown'))).toBe(true);
      expect(move).toHaveBeenCalledOnce();

      registry.register(scope('menus-extend', shortcut(['ArrowDown'], extend, { shift: true })));
      expect(registry.dispatch(key('ArrowDown', { shiftKey: true }))).toBe(true);
      expect(extend).toHaveBeenCalledOnce();
      expect(move).toHaveBeenCalledOnce();
    });

    it('asks for the right cap on each platform', () => {
      const cap = modifierCap();
      expect(['Ctrl', '⌘']).toContain(cap);
    });
  });

  describe('a two-key chord, «g» then «l»', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    function chord(run: () => void, extra: Partial<Shortcut> = {}): Shortcut {
      return {
        keys: [],
        sequence: ['g', 'l'],
        caps: ['g', 'l'],
        label: () => 'Go to locations',
        run,
        ...extra,
      };
    }

    it('runs on the second key, takes both keys from the page, and nothing runs on the first', () => {
      const go = vi.fn();
      registry.register(scope('shell', chord(go)));
      const first = key('g');
      const second = key('l');

      expect(registry.dispatch(first)).toBe(true);
      expect(first.defaultPrevented).toBe(true);
      expect(go).not.toHaveBeenCalled();
      expect(registry.pendingChord).toBe('g');
      expect(registry.dispatch(second)).toBe(true);
      expect(second.defaultPrevented).toBe(true);
      expect(go).toHaveBeenCalledOnce();
      expect(registry.pendingChord).toBeNull();
    });

    it('forgets the first key after the window, so a later «l» is only an «l»', () => {
      const go = vi.fn();
      const plainL = vi.fn();
      registry.register(scope('shell', chord(go), shortcut(['l'], plainL)));

      registry.dispatch(key('g'));
      vi.advanceTimersByTime(CHORD_WINDOW_MS + 1);
      expect(registry.pendingChord).toBeNull();
      registry.dispatch(key('l'));

      expect(go).not.toHaveBeenCalled();
      expect(plainL).toHaveBeenCalledOnce();
    });

    it('disarms on a key that completes nothing and handles that key as itself', () => {
      const go = vi.fn();
      const other = vi.fn();
      registry.register(scope('shell', chord(go), shortcut(['j'], other)));

      registry.dispatch(key('g'));
      registry.dispatch(key('j'));
      registry.dispatch(key('l'));

      expect(other).toHaveBeenCalledOnce();
      expect(go).not.toHaveBeenCalled();
    });

    it('does not arm while the operator is typing or a dialog is open', () => {
      const go = vi.fn();
      registry.register(scope('shell', chord(go)));

      expect(registry.dispatch(key('g', {}, document.createElement('input')))).toBe(false);
      expect(registry.pendingChord).toBeNull();
      registry.dispatch(key('l', {}, document.createElement('input')));
      expect(go).not.toHaveBeenCalled();

      const stray = document.createElement('div');
      stray.setAttribute('aria-modal', 'true');
      document.body.appendChild(stray);
      try {
        expect(registry.dispatch(key('g'))).toBe(false);
        expect(registry.pendingChord).toBeNull();
      } finally {
        stray.remove();
      }
    });

    it('leaves «g» to a single-key shortcut that wants it, rather than waiting for a second key', () => {
      const go = vi.fn();
      const single = vi.fn();
      registry.register(scope('shell', chord(go)));
      registry.register(scope('screen', shortcut(['g'], single)));

      expect(registry.dispatch(key('g'))).toBe(true);
      expect(single).toHaveBeenCalledOnce();
      expect(registry.pendingChord).toBeNull();
    });

    it('does not arm for a chord whose screen says it cannot act, and does not treat Shift+G as g', () => {
      const go = vi.fn();
      registry.register(scope('shell', chord(go, { enabled: () => false })));

      expect(registry.dispatch(key('g'))).toBe(false);
      expect(registry.pendingChord).toBeNull();

      registry.register(scope('shell', chord(go)));
      expect(registry.dispatch(key('G', { shiftKey: true }))).toBe(false);
      expect(registry.dispatch(key('g', { ctrlKey: true }))).toBe(false);
      expect(registry.pendingChord).toBeNull();
    });
  });
});
