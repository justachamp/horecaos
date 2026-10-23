import { localMessages } from '../../../core/i18n/local-messages';
import {
  FIELD_SELECTOR,
  ScreenKeys,
  controlIn,
  press,
  registerScreenKeys,
} from '../../../shared/keyboard/screen-keys';
import { ShortcutScope, modifierCap } from '../../../shared/keyboard/shortcut-registry';

/**
 * New order's keys (orders.md §5.8, gap map rows `X.1` and `1.3`): the screen is used on the phone, with a
 * customer waiting, so the hands stay on the keyboard.
 *
 *  - **`Ctrl+Enter`** (`⌘+Enter` on a Mac) creates the order, from inside any field. It presses the same
 *    «Создать» button the mouse does, so it can never create an order the button would refuse: while the
 *    basket cannot be submitted the button is disabled and the key does nothing.
 *  - **`Esc`** from a field returns to the first field of the pane the cursor is in -- the one-key way back
 *    to the start of what you were typing. **`Esc` again**, with the cursor already there, leaves the screen:
 *    straight away when nothing has been entered, and after asking «Отбросить заказ?» when something has
 *    (a customer chosen, a number typed, a line in the basket). Nothing is thrown away by a single key press.
 *
 * Registered with one line in the page; the keys find the controls the page already draws (see
 * {@link ScreenKeys}).
 */
const messages = localMessages({
  ru: {
    title: 'Новый заказ',
    create: 'Создать заказ (работает и внутри поля)',
    back: 'К первому полю панели; повторно — выйти (с вопросом, если что-то введено)',
    discardTitle: 'Отбросить заказ?',
    discardBody: 'Введённое — клиент, позиции, адрес — не сохранится.',
    discard: 'Отбросить',
    keep: 'Продолжить заказ',
  },
  'uz-Latn': {
    title: 'Yangi buyurtma',
    create: 'Buyurtmani yaratish (maydon ichida ham ishlaydi)',
    back: 'Panelning birinchi maydoniga; yana bosilsa — chiqish (biror narsa kiritilgan boʻlsa, soʻrab)',
    discardTitle: 'Buyurtma bekor qilinsinmi?',
    discardBody: 'Kiritilganlar — mijoz, pozitsiyalar, manzil — saqlanmaydi.',
    discard: 'Bekor qilish',
    keep: 'Buyurtmani davom ettirish',
  },
  en: {
    title: 'New order',
    create: 'Create the order (works inside a field too)',
    back: "To the pane's first field; again to leave (asks first if anything is entered)",
    discardTitle: 'Discard this order?',
    discardBody: 'What was entered — customer, lines, address — will not be kept.',
    discard: 'Discard',
    keep: 'Keep working',
  },
});

const PANE = 'section.new-order__pane';
const SUBMIT = '[data-testid="new-order-submit"], [data-testid="new-order-submit-aggregator"]';

/** True when something has been entered that leaving would lose. */
export function newOrderIsDirty(host: HTMLElement): boolean {
  if (host.querySelector('[data-testid="new-order-selected-customer"]') !== null) {
    return true;
  }
  if (host.querySelector('[data-testid="new-order-basket"] li') !== null) {
    return true;
  }
  const phone = host.querySelector<HTMLInputElement>('[data-testid="new-order-phone-input"]');
  return phone !== null && phone.value.trim() !== '';
}

/** The first field of a pane: where `Esc` returns to. */
function firstFieldOf(pane: HTMLElement): HTMLElement | null {
  return pane.querySelector<HTMLElement>(FIELD_SELECTOR);
}

export function newOrderScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;

  return {
    id: 'new-order',
    title: () => text('title'),
    shortcuts: [
      {
        keys: ['Enter'],
        modifier: 'ctrlOrCmd',
        caps: [`${modifierCap()}+Enter`],
        label: () => text('create'),
        inField: true,
        enabled: () => controlIn(host, SUBMIT) !== null,
        run: () => void press(host, SUBMIT),
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('back'),
        inField: true,
        // A combobox that is open closes on its own Esc first; this is the key for when nothing is open.
        enabled: () => host.querySelector('[aria-expanded="true"]') === null,
        run: (event) => {
          const target = event.target instanceof HTMLElement ? event.target : null;
          const pane = target?.closest<HTMLElement>(PANE) ?? null;
          const first = pane === null ? null : firstFieldOf(pane);
          if (pane !== null && first !== null && target !== first) {
            first.focus();
            return;
          }
          if (!newOrderIsDirty(host)) {
            press(host, '.new-order__cancel');
            return;
          }
          void screen
            .confirm({
              title: text('discardTitle'),
              body: text('discardBody'),
              confirmLabel: text('discard'),
              cancelLabel: text('keep'),
              tone: 'destructive',
            })
            .then((discard) => {
              if (discard) {
                press(host, '.new-order__cancel');
              }
            });
        },
      },
    ],
  };
}

/** The page's one line: `private readonly keys = registerNewOrderKeys();`. */
export function registerNewOrderKeys(): void {
  registerScreenKeys(newOrderScope);
}
