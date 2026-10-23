import { localMessages } from '../../core/i18n/local-messages';
import {
  ScreenKeys,
  currentItem,
  itemsOf,
  moveFocus,
  registerScreenKeys,
} from '../../shared/keyboard/screen-keys';
import { ShortcutScope } from '../../shared/keyboard/shortcut-registry';

/**
 * The people list's keys (staff-and-access.md §2, gap map row `X.1`): `/` to the search box, `j`/`k` along the
 * rows, `Enter` opens the person, `Esc` in the search box empties it and then lets go of it.
 *
 * **No key suspends, restores or removes anyone**, by the spec's own rule: «a muscle-memory keystroke that
 * removes someone's access is a hazard with no compensating speed». The spec's `x` (select a row) names a
 * selection this list does not have, and its «`Esc` ... then back» is left to the browser's own Back, so
 * neither is bound; `staff-and-access.md` §2 records that.
 */
const messages = localMessages({
  ru: {
    title: 'Люди',
    search: 'Перейти в поиск',
    move: 'Следующая / предыдущая строка',
    open: 'Открыть сотрудника',
    leaveSearch: 'Очистить поиск, затем выйти из него',
  },
  'uz-Latn': {
    title: 'Odamlar',
    search: 'Qidiruvga oʻtish',
    move: 'Keyingi / oldingi qator',
    open: 'Xodimni ochish',
    leaveSearch: 'Qidiruvni tozalash, soʻng undan chiqish',
  },
  en: {
    title: 'People',
    search: 'Go to the search box',
    move: 'Next / previous row',
    open: 'Open the person',
    leaveSearch: 'Clear the search, then leave it',
  },
});

const SEARCH = '[data-testid="staff-search"]';
const ROW = 'tbody tr.row';

export function staffListScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const search = (): HTMLInputElement | null => host.querySelector<HTMLInputElement>(SEARCH);

  return {
    id: 'staff-list',
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
        keys: ['Enter'],
        caps: ['Enter'],
        label: () => text('open'),
        enabled: () => currentItem(host, ROW) !== null,
        run: () => currentItem(host, ROW)?.click(),
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

/** The page's one line: `private readonly keys = registerStaffListKeys();`. */
export function registerStaffListKeys(): void {
  registerScreenKeys(staffListScope);
}
