import { DestroyRef, Injectable, Signal, signal } from '@angular/core';

import { hasOpenOverlay } from '../ui/overlay';

/**
 * One documented key. The console's keyboard scheme (operations IA, part 2:
 * "dense, sidebar, keyboard-first") lives in the specs, one table per screen
 * (`orders.md` §2.12 and §5.8, `settings.md` §1.6); this is the shape those tables
 * take in code.
 */
export interface Shortcut {
  /**
   * `KeyboardEvent.key` values that trigger it. A letter is its lower-case form and is never
   * matched with Shift held unless the shortcut says {@link shift}, so `a` is not `Shift+A`; `' '`
   * is Space. Empty for a two-key {@link sequence}, which names its own keys.
   */
  readonly keys: readonly string[];
  /**
   * Opt in to a modifier. A shortcut that names none never fires with Ctrl, Cmd or Alt held -- those
   * belong to the browser and the OS, and stay theirs everywhere a screen has not asked for one
   * chord by name:
   *
   *  - `'ctrlOrCmd'`: Ctrl on Windows and Linux, Cmd on a Mac (`Ctrl+Enter`, `Cmd+S`);
   *  - `'alt'`: Alt / Option (`Alt+↓` to reorder, `Alt+1`..`9` to jump).
   *
   * The match is exact: `Ctrl+Enter` does not answer `Ctrl+Shift+Enter` or `Ctrl+Alt+Enter`.
   */
  readonly modifier?: 'ctrlOrCmd' | 'alt';
  /**
   * Requires Shift, and lets a letter be a Shift-letter key (`Shift+X`), compared without regard to
   * case. Without it a shifted letter matches nothing; a shifted symbol (`?`) never needs it.
   */
  readonly shift?: boolean;
  /**
   * `KeyboardEvent.code` values that also trigger a modified shortcut. `Alt+1` types `¡` on a Mac, so
   * the digit row is matched by position (`Digit1`), not by what the layout prints.
   */
  readonly codes?: readonly string[];
  /**
   * A two-key chord, `['g', 'l']` for «g, then l»: the first key arms it, the second -- pressed within
   * {@link CHORD_WINDOW_MS} and with nothing else between -- runs it. Any other key disarms it and is
   * handled as itself. Letters only, no modifiers; the first key is claimed only if no single-key
   * shortcut wants it.
   */
  readonly sequence?: readonly [string, string];
  /**
   * The key caps the cheat-sheet draws for it, e.g. `['j', '↓']`. Alternatives for most shortcuts; for
   * a {@link sequence} they are the keys in order and the sheet writes «then» between them; a modified
   * chord is one cap (`'Ctrl+Enter'`, see {@link modifierCap}).
   */
  readonly caps: readonly string[];
  /**
   * What it does, in the operator's language. A function so the cheat-sheet reads it when it is
   * drawn: the language can be switched after the screen registered its keys.
   */
  readonly label: () => string;
  /**
   * Whether it can act right now. A shortcut that cannot act is not run, and the key falls
   * through to the next scope and then to the browser. Absent means always.
   */
  readonly enabled?: (event: KeyboardEvent) => boolean;
  /** Fires while focus is in a text field. Rare: F2 starts an order from a search box, Esc leaves one. */
  readonly inField?: boolean;
  /** Fires with a dialog open. Only for a key that is not about the screen behind the dialog. */
  readonly overDialog?: boolean;
  /**
   * What it does. Absent for a key an element already handles natively (`Enter` on a focused row):
   * the sheet lists it so the scheme is complete, and nothing here would run it twice.
   */
  readonly run?: (event: KeyboardEvent) => void;
}

/** A screen's (or the shell's) keys, listed together under one heading in the cheat-sheet. */
export interface ShortcutScope {
  readonly id: string;
  /** The heading over its keys in the cheat-sheet; a function for the same reason as {@link Shortcut.label}. */
  readonly title: () => string;
  readonly shortcuts: readonly Shortcut[];
}

/**
 * The console's one keyboard dispatcher and the registry the cheat-sheet reads.
 *
 * **Why a registry and not a `HostListener` per screen.** Before this the console had two
 * document-level `keydown` handlers (`shell.ts`'s F2 and the settings home's `/`), each with its
 * own idea of when it should stand down, and a cheat-sheet could not exist: nothing knew what the
 * keys were. Screens now declare their keys here for as long as they are mounted; one listener
 * (the shell's) asks {@link dispatch}; and `?` draws whatever is registered, so the sheet cannot
 * promise a key nothing handles.
 *
 * **What it refuses, once, so no screen has to.**
 *
 * - A key pressed with Ctrl, Cmd or Alt held, unless the shortcut names that modifier
 *   ({@link Shortcut.modifier}). Those belong to the browser and the OS.
 * - A key typed into a text field, a select or a contenteditable, unless the shortcut says it is
 *   meant to fire there. `a` approves an order; it must not do so while the operator types «ал»
 *   into the search box.
 * - Space and Enter on a control that activates itself (a button, a link, a checkbox).
 * - Any key while a dialog is open, unless the shortcut is not about the screen behind it. A
 *   confirm dialog on top of the order board must not let `x` open a second one.
 *
 * Scopes are searched newest first, so a screen's keys win over the shell's on the rare overlap.
 */
@Injectable({ providedIn: 'root' })
export class ShortcutRegistry {
  private readonly registered = signal<readonly ShortcutScope[]>([]);
  private readonly sheet = signal(false);
  private armed: string | null = null;
  private armedTimer: ReturnType<typeof setTimeout> | null = null;

  /** Every scope with at least one key, newest (the open screen) first -- what the cheat-sheet draws. */
  readonly scopes: Signal<readonly ShortcutScope[]> = this.registered.asReadonly();
  readonly sheetOpen: Signal<boolean> = this.sheet.asReadonly();

  /**
   * Registers a scope until the returned function is called, or until `destroyRef` is destroyed
   * when one is given. Registering an id that is already registered replaces it.
   */
  register(scope: ShortcutScope, destroyRef?: DestroyRef): () => void {
    this.registered.update((current) => [scope, ...current.filter((s) => s.id !== scope.id)]);
    const unregister = (): void =>
      this.registered.update((current) => current.filter((s) => s !== scope));
    destroyRef?.onDestroy(unregister);
    return unregister;
  }

  openSheet(): void {
    this.sheet.set(true);
  }

  closeSheet(): void {
    this.sheet.set(false);
  }

  /**
   * Runs the first registered shortcut that matches and may act. Returns whether one did, in which
   * case the event's default has been prevented.
   *
   * A key that arms a two-key chord is also "handled" (`true`, default prevented): the operator has
   * started a sequence and the key must not also be typed or scrolled.
   */
  dispatch(event: KeyboardEvent): boolean {
    if (event.defaultPrevented || event.isComposing) {
      return false;
    }
    const target = event.target instanceof HTMLElement ? event.target : null;
    const typing = target !== null && isTextEntry(target);
    const selfActivating =
      (event.key === ' ' || event.key === 'Enter') && target !== null && activatesItself(target);
    // `OverlayBehaviour` knows the dialogs built on `q-modal` and friends; the DOM check is the net under
    // any dialog that is not (a hand-written one still marked `aria-modal`, which promises assistive
    // technology the page behind it is inert -- the keyboard should agree).
    const dialogOpen = hasOpenOverlay() || document.querySelector('[aria-modal="true"]') !== null;

    const mayRun = (shortcut: Shortcut): boolean => {
      if (shortcut.run === undefined) {
        return false;
      }
      if (typing && shortcut.inField !== true) {
        return false;
      }
      // Enter and Space belong to a focused button, link or checkbox; a modified Enter does not.
      if (selfActivating && shortcut.modifier === undefined) {
        return false;
      }
      if (dialogOpen && shortcut.overDialog !== true) {
        return false;
      }
      return shortcut.enabled === undefined || shortcut.enabled(event);
    };

    // The second key of an armed chord. A key that completes nothing disarms and is handled as itself.
    const first = this.armed;
    this.disarm();
    if (first !== null && !isModified(event)) {
      const second = event.key.toLowerCase();
      for (const scope of this.registered()) {
        for (const shortcut of scope.shortcuts) {
          const sequence = shortcut.sequence;
          if (
            sequence !== undefined &&
            sequence[0] === first &&
            sequence[1] === second &&
            mayRun(shortcut)
          ) {
            event.preventDefault();
            shortcut.run?.(event);
            return true;
          }
        }
      }
    }

    for (const scope of this.registered()) {
      for (const shortcut of scope.shortcuts) {
        if (shortcut.sequence !== undefined || !matches(shortcut, event) || !mayRun(shortcut)) {
          continue;
        }
        event.preventDefault();
        shortcut.run?.(event);
        return true;
      }
    }

    // Nothing wanted the key by itself: it may be the first half of a chord.
    if (!isModified(event) && !event.shiftKey && isLetter(event.key)) {
      const lead = event.key.toLowerCase();
      for (const scope of this.registered()) {
        if (
          scope.shortcuts.some((shortcut) => shortcut.sequence?.[0] === lead && mayRun(shortcut))
        ) {
          event.preventDefault();
          this.arm(lead);
          return true;
        }
      }
    }
    return false;
  }

  /** The first key of a chord awaiting its second, or null. */
  get pendingChord(): string | null {
    return this.armed;
  }

  private arm(key: string): void {
    this.armed = key;
    this.armedTimer = setTimeout(() => this.disarm(), CHORD_WINDOW_MS);
  }

  private disarm(): void {
    this.armed = null;
    if (this.armedTimer !== null) {
      clearTimeout(this.armedTimer);
      this.armedTimer = null;
    }
  }
}

/** How long «g» waits for the key that follows it. Short enough that a stray «g» forgets itself. */
export const CHORD_WINDOW_MS = 1200;

/** Ctrl, Cmd or Alt is down. Shift alone is not a modifier in this sense: it shapes the character. */
function isModified(event: KeyboardEvent): boolean {
  return event.ctrlKey || event.metaKey || event.altKey;
}

/** Whether the event is the key combination this shortcut names (it may still decline to act). */
function matches(shortcut: Shortcut, event: KeyboardEvent): boolean {
  const wantsShift = shortcut.shift === true;
  if (shortcut.modifier === 'ctrlOrCmd') {
    if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey !== wantsShift) {
      return false;
    }
    return keyNamed(shortcut, event);
  }
  if (shortcut.modifier === 'alt') {
    if (!event.altKey || event.ctrlKey || event.metaKey || event.shiftKey !== wantsShift) {
      return false;
    }
    return keyNamed(shortcut, event);
  }
  if (isModified(event)) {
    return false;
  }
  if (wantsShift) {
    return event.shiftKey && keyNamed(shortcut, event);
  }
  if (event.shiftKey && (isLetter(event.key) || event.key.length > 1)) {
    // A Shift-letter, or Shift with a named key (↓, Enter, Tab), is another chord: it is answered only by a
    // shortcut that asked for Shift, never by the plain key's.
    return false;
  }
  return shortcut.keys.includes(event.key);
}

/** A modified or Shift-letter shortcut compares keys without case, and may also name a physical key. */
function keyNamed(shortcut: Shortcut, event: KeyboardEvent): boolean {
  const pressed = event.key.toLowerCase();
  return (
    shortcut.keys.some((key) => key.toLowerCase() === pressed) ||
    (shortcut.codes?.includes(event.code) ?? false)
  );
}

/** The cap for the Ctrl/Cmd modifier on this machine: `⌘` on a Mac, `Ctrl` elsewhere. */
export function modifierCap(): string {
  const platform = typeof navigator === 'undefined' ? '' : (navigator.platform ?? '');
  return /mac|iphone|ipad/i.test(platform) ? '⌘' : 'Ctrl';
}

function isLetter(key: string): boolean {
  return key.length === 1 && key.toLowerCase() !== key.toUpperCase();
}

/** Input types that take typed text, as opposed to a checkbox or a button wearing an `<input>`'s tag. */
const NON_TEXT_INPUT_TYPES = new Set([
  'checkbox',
  'radio',
  'button',
  'submit',
  'reset',
  'range',
  'file',
  'color',
  'image',
]);

/** True where the operator is typing, so a bare letter is text and not a command. */
export function isTextEntry(element: HTMLElement): boolean {
  if (element.isContentEditable) {
    return true;
  }
  switch (element.tagName) {
    case 'TEXTAREA':
    case 'SELECT':
      return true;
    case 'INPUT':
      return !NON_TEXT_INPUT_TYPES.has((element as HTMLInputElement).type);
    default: {
      const role = element.getAttribute('role');
      return role === 'textbox' || role === 'combobox' || role === 'searchbox';
    }
  }
}

/** A control for which Space or Enter already means "press me". */
function activatesItself(element: HTMLElement): boolean {
  const tag = element.tagName;
  return (
    tag === 'BUTTON' ||
    tag === 'A' ||
    tag === 'SUMMARY' ||
    tag === 'SELECT' ||
    tag === 'TEXTAREA' ||
    tag === 'INPUT' ||
    element.getAttribute('role') === 'button'
  );
}
