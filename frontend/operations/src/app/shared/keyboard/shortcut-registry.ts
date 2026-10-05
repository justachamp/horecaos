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
   * matched with Shift held, so `a` is not `Shift+A`; `' '` is Space.
   */
  readonly keys: readonly string[];
  /** The key caps the cheat-sheet draws for it, e.g. `['j', '↓']`. */
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
 * - A key pressed with Ctrl, Cmd or Alt held. Those belong to the browser and the OS.
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
   */
  dispatch(event: KeyboardEvent): boolean {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) {
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

    for (const scope of this.registered()) {
      for (const shortcut of scope.shortcuts) {
        if (shortcut.run === undefined || !shortcut.keys.includes(event.key)) {
          continue;
        }
        if (isLetter(event.key) && event.shiftKey) {
          continue;
        }
        if ((typing && shortcut.inField !== true) || selfActivating) {
          continue;
        }
        if (dialogOpen && shortcut.overDialog !== true) {
          continue;
        }
        if (shortcut.enabled !== undefined && !shortcut.enabled(event)) {
          continue;
        }
        event.preventDefault();
        shortcut.run(event);
        return true;
      }
    }
    return false;
  }
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
