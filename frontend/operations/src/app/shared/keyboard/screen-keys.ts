import {
  ApplicationRef,
  DestroyRef,
  ElementRef,
  EnvironmentInjector,
  createComponent,
  inject,
} from '@angular/core';

import { I18n, Locale } from '../../core/i18n/i18n';
import { ConfirmDialog, ConfirmTone } from '../ui/confirm-dialog';
import { ShortcutRegistry, ShortcutScope } from './shortcut-registry';

/**
 * What a screen's keys file is given: the screen's own element, the language, and a way to ask a question
 * with the keyboard. It deliberately gives no component instance.
 *
 * **Why the keys reach a screen through its DOM.** The operations specs (`orders.md` §5.8, `couriers.md` §3,
 * `staff-and-access.md`, `brands-and-locations.md`, `catalog.md`, `statistics.md`) each carry a Keyboard
 * table, and the screens they describe are edited by several people at once. A key that is a method call
 * into a page component puts a line in every page for every key; a key that finds the control the screen
 * already draws (`data-testid="dispatch-card-select"`) and presses it puts one line in the page -- the
 * registration -- and nothing else, and it can never offer an action the button would not have: a disabled
 * button is not pressed, a button that is not drawn is not there. The cost is that a screen's test ids are a
 * contract the keys rely on; each keys file's spec drives them against the real markup.
 */
export interface ScreenKeys {
  readonly host: HTMLElement;
  locale(): Locale;
  /** Asks a yes/no question in the console's own confirm dialog; focus starts on the cancelling button. */
  confirm(question: ConfirmQuestion): Promise<boolean>;
}

export interface ConfirmQuestion {
  readonly title: string;
  readonly body?: string;
  readonly confirmLabel: string;
  readonly cancelLabel: string;
  readonly tone?: ConfirmTone;
}

/**
 * Registers a screen's keys for as long as the calling component lives. Called from a field initialiser of the
 * page component (an injection context): that line is the whole of a page's diff.
 */
export function registerScreenKeys(build: (screen: ScreenKeys) => ShortcutScope): void {
  const registry = inject(ShortcutRegistry);
  const host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  const destroyRef = inject(DestroyRef);
  const i18n = inject(I18n);
  const environment = inject(EnvironmentInjector);
  const application = inject(ApplicationRef);

  const screen: ScreenKeys = {
    host,
    locale: () => i18n.locale(),
    confirm: (question) => askConfirm(environment, application, question),
  };
  registry.register(build(screen), destroyRef);
}

function askConfirm(
  environment: EnvironmentInjector,
  application: ApplicationRef,
  question: ConfirmQuestion,
): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const dialog = createComponent(ConfirmDialog, { environmentInjector: environment });
    dialog.setInput('title', question.title);
    dialog.setInput('body', question.body ?? null);
    dialog.setInput('confirmLabel', question.confirmLabel);
    dialog.setInput('cancelLabel', question.cancelLabel);
    dialog.setInput('tone', question.tone ?? 'default');
    let settled = false;
    const finish = (answer: boolean): void => {
      if (settled) {
        return;
      }
      settled = true;
      dialog.destroy();
      resolve(answer);
    };
    dialog.instance.confirm.subscribe(() => finish(true));
    dialog.instance.cancel.subscribe(() => finish(false));
    application.attachView(dialog.hostView);
    document.body.appendChild(dialog.location.nativeElement);
    dialog.changeDetectorRef.detectChanges();
  });
}

// ------------------------------------------------------------------------------------------ DOM helpers

/** The visible elements under `host` matching `selector`, in document order. */
export function itemsOf(host: HTMLElement, selector: string): HTMLElement[] {
  return [...host.querySelectorAll<HTMLElement>(selector)];
}

/** The item that holds focus, or contains it. */
export function currentItem(host: HTMLElement, selector: string): HTMLElement | null {
  const active = document.activeElement;
  if (!(active instanceof HTMLElement)) {
    return null;
  }
  const item = active.closest<HTMLElement>(selector);
  return item !== null && host.contains(item) ? item : null;
}

/**
 * Moves focus one item along, stopping at the ends (a list that wraps loses the operator's place). From no item
 * it starts at the top going down and at the bottom going up. An item that is not natively focusable (a table
 * row, a card) becomes programmatically focusable -- `tabindex="-1"` keeps it out of the Tab order while letting
 * the arrow keys land on it, which is how a row cursor is built.
 */
export function moveFocus(host: HTMLElement, selector: string, delta: 1 | -1): HTMLElement | null {
  const items = itemsOf(host, selector);
  if (items.length === 0) {
    return null;
  }
  const current = currentItem(host, selector);
  const index = current === null ? -1 : items.indexOf(current);
  const target =
    index < 0
      ? delta === 1
        ? 0
        : items.length - 1
      : Math.min(items.length - 1, Math.max(0, index + delta));
  const item = items[target];
  if (!item.hasAttribute('tabindex')) {
    item.setAttribute('tabindex', '-1');
  }
  item.focus();
  item.scrollIntoView?.({ block: 'nearest' });
  return item;
}

/** The enabled control `selector` finds inside `item`, or null. A disabled control is not a control a key may press. */
export function controlIn(item: ParentNode, selector: string): HTMLElement | null {
  const control = item.querySelector<HTMLElement>(selector);
  if (control === null) {
    return null;
  }
  if (control instanceof HTMLButtonElement || control instanceof HTMLInputElement) {
    return control.disabled ? null : control;
  }
  return control.getAttribute('aria-disabled') === 'true' ? null : control;
}

/** Presses the control if it is there and enabled; returns whether it was. */
export function press(item: ParentNode, selector: string): boolean {
  const control = controlIn(item, selector);
  if (control === null) {
    return false;
  }
  control.click();
  return true;
}

/** Puts the cursor in the first matching field under `root`. */
export function focusFirst(root: ParentNode, selector: string): boolean {
  const field = root.querySelector<HTMLElement>(selector);
  if (field === null) {
    return false;
  }
  field.focus();
  return true;
}

/** The selector of anything a person types into or presses: what «the pane's first field» means. */
export const FIELD_SELECTOR =
  'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled]), ' +
  'button:not([disabled]), [tabindex="0"]';

/**
 * Runs `then` with the element `selector` finds under `host` once the screen has drawn it. A key that opens
 * something (a selection that reveals the bulk bar) cannot focus it in the same tick -- the screen draws on its
 * next turn -- so this looks again for a short while and gives up quietly: a control that never appeared is a
 * screen that did not offer it, not an error.
 */
export function whenPresent(
  host: HTMLElement,
  selector: string,
  then: (element: HTMLElement) => void,
  attempts = 10,
): void {
  const element = host.querySelector<HTMLElement>(selector);
  if (element !== null) {
    then(element);
    return;
  }
  if (attempts > 0) {
    setTimeout(() => whenPresent(host, selector, then, attempts - 1), 20);
  }
}
