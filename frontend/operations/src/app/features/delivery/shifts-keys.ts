import { localMessages } from '../../core/i18n/local-messages';
import {
  ScreenKeys,
  controlIn,
  currentItem,
  itemsOf,
  moveFocus,
  press,
  registerScreenKeys,
} from '../../shared/keyboard/screen-keys';
import { ShortcutScope } from '../../shared/keyboard/shortcut-registry';

/**
 * The courier shift board's keys (couriers.md §7, `j`/`k` rows, `p` approve, `c` close). The spec's `Enter`
 * (open the courier) and `1`..`5` (tabs) name a courier page and tabs this screen does not have, so they are
 * not bound; `couriers.md` §7 says so.
 *
 * `p` presses the row's «Подтвердить» and `c` its «Закрыть» -- the buttons the row already draws, so a shift
 * that cannot be approved (the person lacks the right, the shift is not awaiting approval) has nothing to
 * press. Closing a shift ends a courier's paid time, so `c` asks first; approving is the button's own click.
 */
const messages = localMessages({
  ru: {
    title: 'Смены курьеров',
    move: 'Следующая / предыдущая смена',
    approve: 'Подтвердить смену, ожидающую подтверждения',
    close: 'Закрыть открытую смену (сначала спросит)',
    closeTitle: 'Закрыть смену курьера?',
    closeBody: 'Оплачиваемое время смены перестанет идти.',
    closeConfirm: 'Закрыть смену',
    keep: 'Оставить открытой',
  },
  'uz-Latn': {
    title: 'Kuryer smenalari',
    move: 'Keyingi / oldingi smena',
    approve: 'Tasdiq kutayotgan smenani tasdiqlash',
    close: 'Ochiq smenani yopish (avval soʻraydi)',
    closeTitle: 'Kuryer smenasi yopilsinmi?',
    closeBody: 'Smenaning toʻlanadigan vaqti toʻxtaydi.',
    closeConfirm: 'Smenani yopish',
    keep: 'Ochiq qoldirish',
  },
  en: {
    title: 'Courier shifts',
    move: 'Next / previous shift',
    approve: 'Approve a shift that is awaiting approval',
    close: 'Close an open shift (asks first)',
    closeTitle: "Close this courier's shift?",
    closeBody: 'The shift stops counting paid time.',
    closeConfirm: 'Close the shift',
    keep: 'Keep it open',
  },
});

const ROW = '[data-testid="shift-row"]';
const APPROVE = '[data-testid="shift-approve"]';
const CLOSE = '[data-testid="shift-close"]';

export function shiftsScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const focused = (): HTMLElement | null => currentItem(host, ROW);

  return {
    id: 'courier-shifts',
    title: () => text('title'),
    shortcuts: [
      {
        keys: ['j', 'ArrowDown'],
        caps: ['j', '↓'],
        label: () => text('move'),
        enabled: () => itemsOf(host, ROW).length > 0,
        run: () => void moveFocus(host, ROW, 1),
      },
      {
        keys: ['k', 'ArrowUp'],
        caps: ['k', '↑'],
        label: () => text('move'),
        enabled: () => itemsOf(host, ROW).length > 0,
        run: () => void moveFocus(host, ROW, -1),
      },
      {
        keys: ['p'],
        caps: ['p'],
        label: () => text('approve'),
        enabled: () => {
          const row = focused();
          return row !== null && controlIn(row, APPROVE) !== null;
        },
        run: () => {
          const row = focused();
          if (row !== null) {
            press(row, APPROVE);
          }
        },
      },
      {
        keys: ['c'],
        caps: ['c'],
        label: () => text('close'),
        enabled: () => {
          const row = focused();
          return row !== null && controlIn(row, CLOSE) !== null;
        },
        run: () => {
          const row = focused();
          if (row === null) {
            return;
          }
          void screen
            .confirm({
              title: text('closeTitle'),
              body: text('closeBody'),
              confirmLabel: text('closeConfirm'),
              cancelLabel: text('keep'),
              tone: 'destructive',
            })
            .then((yes) => {
              if (yes) {
                press(row, CLOSE);
              }
            });
        },
      },
    ],
  };
}

/** The page's one line: `private readonly keys = registerShiftsKeys();`. */
export function registerShiftsKeys(): void {
  registerScreenKeys(shiftsScope);
}
