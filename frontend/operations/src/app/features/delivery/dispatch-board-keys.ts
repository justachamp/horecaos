import { localMessages } from '../../core/i18n/local-messages';
import {
  ScreenKeys,
  controlIn,
  currentItem,
  itemsOf,
  moveFocus,
  press,
  registerScreenKeys,
  whenPresent,
} from '../../shared/keyboard/screen-keys';
import { ShortcutScope } from '../../shared/keyboard/shortcut-registry';

/**
 * The dispatch board's keys (couriers.md §3, «Speed matters here more than anywhere else in the console»).
 *
 * What the board draws is a card per delivery plan, in a column per courier plus «Unassigned». So the keys
 * are the ones that fit cards and columns: `j`/`k` move between cards, `x` and `Shift+X` select, `a` and `r`
 * take the card to the courier picker, `e` calls an external courier, `u` unassigns (after asking), `m` shows
 * or hides the map, `Esc` clears the selection. The spec's `/` (search), `1`..`6` (status tabs) and `g f`
 * (fleet rail) belong to a board with a search box, status tabs and a rail; this one has none of the three,
 * and a key that names a control nobody drew would only promise something. `couriers.md` §3 says so.
 *
 * Every key presses the control the card already shows, so the board's own rules apply unchanged: a plan that
 * cannot be selected has no checkbox to press, an assigned one has no «call an external courier».
 */
const messages = localMessages({
  ru: {
    title: 'Диспетчерская доска',
    move: 'Следующая / предыдущая карточка',
    select: 'Отметить или снять отметку с карточки',
    extend: 'Отметить все карточки от последней отмеченной до этой',
    assign: 'Назначить или переназначить: отметить карточку и перейти к выбору курьера',
    external: 'Вызвать внешнего курьера для карточки',
    unassign: 'Снять курьера с карточки (сначала спросит)',
    map: 'Показать или скрыть карту',
    clear: 'Снять отметки',
    unassignTitle: 'Снять курьера с заказа?',
    unassignBody: 'Заказ вернётся в «Не назначены».',
    unassignConfirm: 'Снять курьера',
    keep: 'Оставить',
  },
  'uz-Latn': {
    title: 'Dispetcher taxtasi',
    move: 'Keyingi / oldingi kartochka',
    select: 'Kartochkani belgilash yoki belgisini olib tashlash',
    extend: 'Oxirgi belgilangandan shu kartochkagacha hammasini belgilash',
    assign: 'Tayinlash yoki qayta tayinlash: kartochkani belgilab, kuryer tanlashga oʻtish',
    external: 'Kartochka uchun tashqi kuryer chaqirish',
    unassign: 'Kartochkadan kuryerni olib tashlash (avval soʻraydi)',
    map: 'Xaritani koʻrsatish yoki yashirish',
    clear: 'Belgilarni olib tashlash',
    unassignTitle: 'Buyurtmadan kuryer olib tashlansinmi?',
    unassignBody: 'Buyurtma «Tayinlanmagan» ustuniga qaytadi.',
    unassignConfirm: 'Kuryerni olib tashlash',
    keep: 'Qoldirish',
  },
  en: {
    title: 'Dispatch board',
    move: 'Next / previous card',
    select: 'Select or deselect the card',
    extend: 'Select every card from the last one selected to this one',
    assign: 'Assign or reassign: select the card and go to the courier picker',
    external: 'Call an external courier for the card',
    unassign: 'Take the courier off the card (asks first)',
    map: 'Show or hide the map',
    clear: 'Clear the selection',
    unassignTitle: 'Take the courier off this order?',
    unassignBody: 'The order goes back to Unassigned.',
    unassignConfirm: 'Unassign',
    keep: 'Keep',
  },
});

const CARD = '[data-testid="dispatch-card"]';
const SELECT = '[data-testid="dispatch-card-select"]';
const EXTERNAL = '[data-testid="dispatch-card-external-courier"]';
const UNASSIGN = '.dispatch-card__unassign';
const MAP = '[data-testid="dispatch-map-toggle"]';
const BULK_SELECT = '[data-testid="dispatch-bulk-courier-select"]';
const BULK_CLEAR = '[data-testid="dispatch-bulk-clear"]';

export function dispatchBoardScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  /** The card most recently toggled with `x`: where `Shift+X` extends from. */
  let anchor: HTMLElement | null = null;
  const focused = (): HTMLElement | null => currentItem(host, CARD);
  const isSelected = (card: HTMLElement): boolean =>
    (card.querySelector<HTMLInputElement>(SELECT)?.checked ?? false) === true;
  const selectable = (card: HTMLElement | null): boolean =>
    card !== null && controlIn(card, SELECT) !== null;

  return {
    id: 'dispatch-board',
    title: () => text('title'),
    shortcuts: [
      {
        keys: ['j', 'ArrowDown'],
        caps: ['j', '↓'],
        label: () => text('move'),
        enabled: () => itemsOf(host, CARD).length > 0,
        run: () => void moveFocus(host, CARD, 1),
      },
      {
        keys: ['k', 'ArrowUp'],
        caps: ['k', '↑'],
        label: () => text('move'),
        enabled: () => itemsOf(host, CARD).length > 0,
        run: () => void moveFocus(host, CARD, -1),
      },
      {
        keys: ['x'],
        caps: ['x'],
        label: () => text('select'),
        enabled: () => selectable(focused()),
        run: () => {
          const card = focused();
          if (card !== null && press(card, SELECT)) {
            anchor = card;
          }
        },
      },
      {
        keys: ['x'],
        shift: true,
        caps: ['Shift+X'],
        label: () => text('extend'),
        enabled: () => selectable(focused()),
        run: () => {
          const card = focused();
          if (card === null) {
            return;
          }
          const cards = itemsOf(host, CARD);
          const from =
            anchor !== null && cards.includes(anchor) ? cards.indexOf(anchor) : cards.indexOf(card);
          const to = cards.indexOf(card);
          for (const between of cards.slice(Math.min(from, to), Math.max(from, to) + 1)) {
            if (selectable(between) && !isSelected(between)) {
              press(between, SELECT);
            }
          }
          anchor = card;
        },
      },
      {
        keys: ['a', 'r'],
        caps: ['a', 'r'],
        label: () => text('assign'),
        enabled: () => selectable(focused()),
        run: () => {
          const card = focused();
          if (card !== null && !isSelected(card)) {
            press(card, SELECT);
            anchor = card;
          }
          whenPresent(host, BULK_SELECT, (picker) => picker.focus());
        },
      },
      {
        keys: ['e'],
        caps: ['e'],
        label: () => text('external'),
        enabled: () => {
          const card = focused();
          return card !== null && controlIn(card, EXTERNAL) !== null;
        },
        run: () => {
          const card = focused();
          if (card !== null) {
            press(card, EXTERNAL);
          }
        },
      },
      {
        keys: ['u'],
        caps: ['u'],
        label: () => text('unassign'),
        enabled: () => {
          const card = focused();
          return card !== null && controlIn(card, UNASSIGN) !== null;
        },
        run: () => {
          const card = focused();
          if (card === null) {
            return;
          }
          void screen
            .confirm({
              title: text('unassignTitle'),
              body: text('unassignBody'),
              confirmLabel: text('unassignConfirm'),
              cancelLabel: text('keep'),
              tone: 'destructive',
            })
            .then((yes) => {
              if (yes) {
                press(card, UNASSIGN);
              }
            });
        },
      },
      {
        keys: ['m'],
        caps: ['m'],
        label: () => text('map'),
        enabled: () => controlIn(host, MAP) !== null,
        run: () => void press(host, MAP),
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('clear'),
        enabled: () => controlIn(host, BULK_CLEAR) !== null,
        run: () => {
          anchor = null;
          press(host, BULK_CLEAR);
        },
      },
    ],
  };
}

/** The page's one line: `private readonly keys = registerDispatchBoardKeys();`. */
export function registerDispatchBoardKeys(): void {
  registerScreenKeys(dispatchBoardScope);
}
