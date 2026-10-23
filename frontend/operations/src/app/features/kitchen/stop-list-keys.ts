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
 * The stop list's keys (catalog.md §4.6, gap map row `X.1`): `/` to the search box, `j`/`k` along the rows,
 * `Enter` or `s` stops the dish under the cursor (or, if it is stopped, puts it back), `u` undoes the last one.
 *
 * The stop is the screen's own one-tap button, so it is exactly as audited as a tap: a key presses
 * `stop-list-toggle` and nothing else, and a row whose button is busy or not drawn takes no key. Undo is
 * the same button on the same row, found again by the row's id after the table redraws, so «undo» can never
 * land on a different dish. It undoes one step, not a history: a second `u` does nothing until another
 * stop is made. The spec's `Tab` between the two tabs is the browser's own Tab, left alone.
 */
const messages = localMessages({
  ru: {
    title: 'Стоп-лист',
    search: 'Перейти в поиск',
    move: 'Следующая / предыдущая строка',
    toggle: 'Поставить блюдо на стоп или вернуть',
    undo: 'Отменить последнюю остановку',
    leaveSearch: 'Очистить поиск, затем выйти из него',
  },
  'uz-Latn': {
    title: 'Stop-list',
    search: 'Qidiruvga oʻtish',
    move: 'Keyingi / oldingi qator',
    toggle: 'Taomni stopga qoʻyish yoki qaytarish',
    undo: 'Oxirgi toʻxtatishni bekor qilish',
    leaveSearch: 'Qidiruvni tozalash, soʻng undan chiqish',
  },
  en: {
    title: 'Stop list',
    search: 'Go to the search box',
    move: 'Next / previous row',
    toggle: 'Stop the dish, or put it back',
    undo: 'Undo the last stop',
    leaveSearch: 'Clear the search, then leave it',
  },
});

const SEARCH = '[data-testid="stop-list-search"]';
const ROW = 'tbody tr[data-testid="dt-row"]';
const TOGGLE = '[data-testid="stop-list-toggle"]';

export function stopListScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const search = (): HTMLInputElement | null => host.querySelector<HTMLInputElement>(SEARCH);
  /** The row the last stop key was pressed on; undo goes back to it by id. */
  let lastToggledRowId: string | null = null;

  const rowById = (id: string): HTMLElement | null =>
    itemsOf(host, ROW).find((row) => row.dataset['rowId'] === id) ?? null;
  const canToggle = (): boolean => {
    const row = currentItem(host, ROW);
    return row !== null && controlIn(row, TOGGLE) !== null;
  };

  return {
    id: 'stop-list',
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
        keys: ['Enter', 's'],
        caps: ['Enter', 's'],
        label: () => text('toggle'),
        enabled: canToggle,
        run: () => {
          const row = currentItem(host, ROW);
          if (row !== null && press(row, TOGGLE)) {
            lastToggledRowId = row.dataset['rowId'] ?? null;
          }
        },
      },
      {
        keys: ['u'],
        caps: ['u'],
        label: () => text('undo'),
        enabled: () => {
          const row = lastToggledRowId === null ? null : rowById(lastToggledRowId);
          return row !== null && controlIn(row, TOGGLE) !== null;
        },
        run: () => {
          const row = lastToggledRowId === null ? null : rowById(lastToggledRowId);
          if (row !== null && press(row, TOGGLE)) {
            lastToggledRowId = null;
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

/** The page's one line: `private readonly keys = registerStopListKeys();`. */
export function registerStopListKeys(): void {
  registerScreenKeys(stopListScope);
}
