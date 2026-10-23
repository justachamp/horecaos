import { localMessages } from '../../../core/i18n/local-messages';
import {
  ScreenKeys,
  controlIn,
  currentItem,
  itemsOf,
  moveFocus,
  press,
  registerScreenKeys,
} from '../../../shared/keyboard/screen-keys';
import { ShortcutScope } from '../../../shared/keyboard/shortcut-registry';

/**
 * The branch list's keys (brands-and-locations.md §1.8, gap map row `X.1`): `j`/`k` along the rows, `Enter` opens
 * the branch, `Space` or `x` selects it, `c` starts the quick close for the focused row, `o` reopens it, `Esc`
 * clears the selection. `g l` (go to locations) is the shell's, so it works from every screen.
 *
 * The rows are made focusable by the keys themselves (`tabindex="-1"`, set when the cursor lands), so the page
 * does not change for this. `c` and `o` press the buttons the row already draws, so a branch that is closed has
 * no «close» to press and one that is open has no «reopen»; `c` opens the row's own close form (the reason), it
 * does not close anything by itself. The spec's `/` and `1`..`6` name a search box and tabs the list does not have
 * (the seven tabs belong to the branch's detail, which has its own keys); `brands-and-locations.md` §1.8 says so.
 */
const messages = localMessages({
  ru: {
    title: 'Филиалы',
    move: 'Следующий / предыдущий филиал',
    open: 'Открыть филиал',
    select: 'Отметить или снять отметку с филиала',
    close: 'Закрыть филиал — откроется форма с причиной',
    reopen: 'Открыть закрытый филиал',
    clear: 'Снять отметки',
  },
  'uz-Latn': {
    title: 'Filiallar',
    move: 'Keyingi / oldingi filial',
    open: 'Filialni ochish',
    select: 'Filialni belgilash yoki belgisini olib tashlash',
    close: 'Filialni yopish — sabab shakli ochiladi',
    reopen: 'Yopilgan filialni qayta ochish',
    clear: 'Belgilarni olib tashlash',
  },
  en: {
    title: 'Branches',
    move: 'Next / previous branch',
    open: 'Open the branch',
    select: 'Select or deselect the branch',
    close: "Close the branch — opens the row's reason form",
    reopen: 'Reopen a closed branch',
    clear: 'Clear the selection',
  },
});

const ROW = 'tbody tr.row';
const SELECT = 'td:first-child input[type="checkbox"]';
const ACTION = 'td.actions-cell > button';
const BULK_CLEAR = '.bulk-bar > button:first-of-type';

function isClosed(row: HTMLElement): boolean {
  return row.querySelector('.state-badge--closed') !== null;
}

export function locationsListScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const focused = (): HTMLElement | null => currentItem(host, ROW);
  const toggle = (): void => {
    const row = focused();
    if (row !== null) {
      press(row, SELECT);
    }
  };

  return {
    id: 'locations-list',
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
        keys: ['Enter'],
        caps: ['Enter'],
        label: () => text('open'),
        enabled: () => focused() !== null,
        run: () => focused()?.click(),
      },
      {
        keys: [' ', 'x'],
        caps: ['Space', 'x'],
        label: () => text('select'),
        enabled: () => {
          const row = focused();
          return row !== null && controlIn(row, SELECT) !== null;
        },
        run: toggle,
      },
      {
        keys: ['c'],
        caps: ['c'],
        label: () => text('close'),
        enabled: () => {
          const row = focused();
          return row !== null && !isClosed(row) && controlIn(row, ACTION) !== null;
        },
        run: () => {
          const row = focused();
          if (row !== null) {
            press(row, ACTION);
          }
        },
      },
      {
        keys: ['o'],
        caps: ['o'],
        label: () => text('reopen'),
        enabled: () => {
          const row = focused();
          return row !== null && isClosed(row) && controlIn(row, ACTION) !== null;
        },
        run: () => {
          const row = focused();
          if (row !== null) {
            press(row, ACTION);
          }
        },
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('clear'),
        enabled: () => controlIn(host, BULK_CLEAR) !== null,
        run: () => void press(host, BULK_CLEAR),
      },
    ],
  };
}

/** The page's one line: `private readonly keys = registerLocationsListKeys();`. */
export function registerLocationsListKeys(): void {
  registerScreenKeys(locationsListScope);
}
