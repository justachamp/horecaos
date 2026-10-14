import { localMessages } from '../../core/i18n/local-messages';
import {
  ScreenKeys,
  controlIn,
  currentItem,
  itemsOf,
  moveFocus,
  press,
  pressRowMenuItem,
  registerScreenKeys,
} from '../../shared/keyboard/screen-keys';
import { ShortcutScope } from '../../shared/keyboard/shortcut-registry';

/**
 * The product list's keys (catalog.md §4.1, gap map row `X.1`): `/` to the search box, `j`/`k` along the rows,
 * `Enter` or `e` opens the product's editor, `Space` ticks the row for a bulk action, `c` copies the product's
 * id, `Esc` empties the search and then lets go of it, or else clears the selection.
 *
 * The spec's `Enter` opens a drawer and `e` the full editor; this list has no drawer (the row opens the editor
 * itself), so both open the editor. Its `s` («stop for the current location») is not here: stopping a dish
 * belongs to the stop list, where the reason is asked for, and the list's own «stop in all branches» is a menu
 * item that asks first. `catalog.md` §4.1 records both.
 */
const messages = localMessages({
  ru: {
    title: 'Товары',
    search: 'Перейти в поиск',
    move: 'Следующая / предыдущая строка',
    open: 'Открыть редактор товара',
    select: 'Отметить строку для массового действия',
    copyId: 'Скопировать идентификатор товара',
    clear: 'Очистить поиск, затем выйти из него; иначе снять отметки',
  },
  'uz-Latn': {
    title: 'Mahsulotlar',
    search: 'Qidiruvga oʻtish',
    move: 'Keyingi / oldingi qator',
    open: 'Mahsulot tahririni ochish',
    select: 'Qatorni ommaviy amal uchun belgilash',
    copyId: 'Mahsulot identifikatorini nusxalash',
    clear: 'Qidiruvni tozalash, soʻng undan chiqish; boshqacha belgilarni olib tashlash',
  },
  en: {
    title: 'Products',
    search: 'Go to the search box',
    move: 'Next / previous row',
    open: 'Open the product editor',
    select: 'Tick the row for a bulk action',
    copyId: "Copy the product's id",
    clear: 'Clear the search, then leave it; otherwise clear the selection',
  },
});

const SEARCH = '[data-testid="products-search"]';
const ROW = 'tbody tr[data-testid="dt-row"]';
const ROW_SELECT = '[data-testid="dt-row-select"]';
const CLEAR_SELECTION = '[data-testid="dt-bulk-clear"]';

export function productsScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const search = (): HTMLInputElement | null => host.querySelector<HTMLInputElement>(SEARCH);
  const hasRow = (): boolean => currentItem(host, ROW) !== null;

  return {
    id: 'catalog-products',
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
        keys: ['Enter', 'e'],
        caps: ['Enter', 'e'],
        label: () => text('open'),
        enabled: hasRow,
        run: () => currentItem(host, ROW)?.click(),
      },
      {
        keys: [' '],
        caps: ['Space'],
        label: () => text('select'),
        enabled: () => {
          const row = currentItem(host, ROW);
          return row !== null && controlIn(row, ROW_SELECT) !== null;
        },
        run: () => {
          const row = currentItem(host, ROW);
          if (row !== null) {
            press(row, ROW_SELECT);
          }
        },
      },
      {
        keys: ['c'],
        caps: ['c'],
        label: () => text('copyId'),
        enabled: () => {
          const row = currentItem(host, ROW);
          return row !== null && controlIn(row, '[data-testid="q-action-menu-trigger"]') !== null;
        },
        run: () => {
          const row = currentItem(host, ROW);
          if (row !== null) {
            pressRowMenuItem(row, 'copyId');
          }
        },
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('clear'),
        inField: true,
        enabled: (event) => {
          const field = search();
          if (field !== null && event.target === field) {
            return true;
          }
          return controlIn(host, CLEAR_SELECTION) !== null;
        },
        run: (event) => {
          const field = search();
          if (field !== null && event.target === field) {
            if (field.value !== '') {
              field.value = '';
              field.dispatchEvent(new Event('input', { bubbles: true }));
              return;
            }
            field.blur();
            return;
          }
          press(host, CLEAR_SELECTION);
        },
      },
    ],
  };
}

/** The page's one line: `private readonly keys = registerProductsKeys();`. */
export function registerProductsKeys(): void {
  registerScreenKeys(productsScope);
}
