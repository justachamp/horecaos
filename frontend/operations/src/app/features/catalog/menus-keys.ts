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
 * The menu matrix's keys (catalog.md §4.5, gap map row `X.1`). The matrix here is a list of dishes with a status
 * cell for the branch and, with a sales channel picked, one for the channel; the keys move a row cursor and
 * press those cells, so they offer only what a click would.
 *
 *  - `j`/`k` (and the arrows) move the row cursor; `/` goes to the search box;
 *  - `Space` flips the branch cell of the row (on the menu / on stop);
 *  - `1` puts the dish on the menu and `2` on stop at the branch, `3` hides it from the picked channel, each
 *    pressing the cell only if it is not already so;
 *  - `Shift+↓` / `Shift+↑` tick the row and the one beyond it, extending the selection along the column;
 *  - `f` fills: it applies the row's branch status to every ticked row through the bulk bar's own button, which
 *    still asks before it writes.
 */
const messages = localMessages({
  ru: {
    title: 'Меню',
    search: 'Перейти в поиск',
    move: 'Следующая / предыдущая строка',
    cycle: 'Переключить статус блюда в филиале',
    onMenu: 'Вернуть в меню',
    onStop: 'Поставить на стоп',
    hide: 'Скрыть из выбранного канала',
    extendDown: 'Отметить строку и следующую',
    extendUp: 'Отметить строку и предыдущую',
    fill: 'Заполнить: статус этой строки всем отмеченным',
    leaveSearch: 'Очистить поиск, затем выйти из него',
  },
  'uz-Latn': {
    title: 'Menyu',
    search: 'Qidiruvga oʻtish',
    move: 'Keyingi / oldingi qator',
    cycle: 'Taom holatini filialda almashtirish',
    onMenu: 'Menyuga qaytarish',
    onStop: 'Stopga qoʻyish',
    hide: 'Tanlangan kanaldan yashirish',
    extendDown: 'Qatorni va keyingisini belgilash',
    extendUp: 'Qatorni va oldingisini belgilash',
    fill: 'Toʻldirish: shu qator holatini belgilanganlarga qoʻllash',
    leaveSearch: 'Qidiruvni tozalash, soʻng undan chiqish',
  },
  en: {
    title: 'Menu',
    search: 'Go to the search box',
    move: 'Next / previous row',
    cycle: "Flip the dish's status at the branch",
    onMenu: 'Put on the menu',
    onStop: 'Put on stop',
    hide: 'Hide from the picked channel',
    extendDown: 'Tick this row and the next',
    extendUp: 'Tick this row and the previous',
    fill: "Fill: this row's status to every ticked row",
    leaveSearch: 'Clear the search, then leave it',
  },
});

const SEARCH = '[data-testid="menus-search"]';
const ROW = '.menus__table tbody tr';
const BRANCH_CELL = '[data-testid="menus-cell"]';
const CHANNEL_CELL = '[data-testid="menus-channel-status"]';
const ROW_CHECKBOX = '[data-testid="menus-row-checkbox"]';
const BULK_AVAILABLE = '[data-testid="menus-bulk-available"]';
const BULK_UNAVAILABLE = '[data-testid="menus-bulk-unavailable"]';
const AVAILABLE = 'menus__cell--available';

export function menusScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const search = (): HTMLInputElement | null => host.querySelector<HTMLInputElement>(SEARCH);
  const row = (): HTMLElement | null => currentItem(host, ROW);
  const hasRows = (): boolean => itemsOf(host, ROW).length > 0;

  /** Whether the row's cell says «available»: the cell's own class is the screen's truth. */
  const isOn = (cell: HTMLElement): boolean => cell.classList.contains(AVAILABLE);
  const setBranch = (wantOn: boolean): void => {
    const current = row();
    const cell = current === null ? null : controlIn(current, BRANCH_CELL);
    if (cell !== null && isOn(cell) !== wantOn) {
      cell.click();
    }
  };
  const canSet = (wantOn: boolean): boolean => {
    const current = row();
    const cell = current === null ? null : controlIn(current, BRANCH_CELL);
    return cell !== null && isOn(cell) !== wantOn;
  };
  const tick = (target: HTMLElement | null): void => {
    const box = target === null ? null : controlIn(target, ROW_CHECKBOX);
    if (box instanceof HTMLInputElement && !box.checked) {
      box.click();
    }
  };
  const extend = (delta: 1 | -1): void => {
    const current = row();
    if (current === null) {
      return;
    }
    tick(current);
    tick(moveFocus(host, ROW, delta));
  };

  return {
    id: 'catalog-menus',
    title: () => text('title'),
    shortcuts: [
      {
        keys: ['/'],
        caps: ['/'],
        label: () => text('search'),
        enabled: () => search() !== null,
        run: () => search()?.focus(),
      },
      {
        keys: ['j', 'ArrowDown'],
        caps: ['j', '↓'],
        label: () => text('move'),
        enabled: hasRows,
        run: () => void moveFocus(host, ROW, 1),
      },
      {
        keys: ['k', 'ArrowUp'],
        caps: ['k', '↑'],
        label: () => text('move'),
        enabled: hasRows,
        run: () => void moveFocus(host, ROW, -1),
      },
      {
        keys: [' '],
        caps: ['Space'],
        label: () => text('cycle'),
        enabled: () => {
          const current = row();
          return current !== null && controlIn(current, BRANCH_CELL) !== null;
        },
        run: () => {
          const current = row();
          if (current !== null) {
            press(current, BRANCH_CELL);
          }
        },
      },
      {
        keys: ['1'],
        caps: ['1'],
        label: () => text('onMenu'),
        enabled: () => canSet(true),
        run: () => setBranch(true),
      },
      {
        keys: ['2'],
        caps: ['2'],
        label: () => text('onStop'),
        enabled: () => canSet(false),
        run: () => setBranch(false),
      },
      {
        keys: ['3'],
        caps: ['3'],
        label: () => text('hide'),
        enabled: () => {
          const current = row();
          const cell = current === null ? null : controlIn(current, CHANNEL_CELL);
          return cell !== null && isOn(cell);
        },
        run: () => {
          const current = row();
          const cell = current === null ? null : controlIn(current, CHANNEL_CELL);
          if (cell !== null && isOn(cell)) {
            cell.click();
          }
        },
      },
      {
        keys: ['ArrowDown'],
        shift: true,
        caps: ['Shift+↓'],
        label: () => text('extendDown'),
        enabled: () => row() !== null,
        run: () => extend(1),
      },
      {
        keys: ['ArrowUp'],
        shift: true,
        caps: ['Shift+↑'],
        label: () => text('extendUp'),
        enabled: () => row() !== null,
        run: () => extend(-1),
      },
      {
        keys: ['f'],
        caps: ['f'],
        label: () => text('fill'),
        enabled: () => {
          const current = row();
          const cell = current === null ? null : controlIn(current, BRANCH_CELL);
          return (
            cell !== null &&
            controlIn(host, isOn(cell) ? BULK_AVAILABLE : BULK_UNAVAILABLE) !== null
          );
        },
        run: () => {
          const current = row();
          const cell = current === null ? null : controlIn(current, BRANCH_CELL);
          if (cell !== null) {
            press(host, isOn(cell) ? BULK_AVAILABLE : BULK_UNAVAILABLE);
          }
        },
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('leaveSearch'),
        inField: true,
        enabled: (event) => search() !== null && event.target === search(),
        run: () => {
          const field = search();
          if (field === null) {
            return;
          }
          if (field.value !== '') {
            field.value = '';
            field.dispatchEvent(new Event('input', { bubbles: true }));
            return;
          }
          field.blur();
        },
      },
    ],
  };
}

/** The page's one line: `private readonly keys = registerMenusKeys();`. */
export function registerMenusKeys(): void {
  registerScreenKeys(menusScope);
}
