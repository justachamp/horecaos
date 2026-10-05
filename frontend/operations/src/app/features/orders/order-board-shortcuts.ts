import { Locale } from '../../core/i18n/i18n';
import { localMessages } from '../../core/i18n/local-messages';
import { ShortcutScope } from '../../shared/keyboard/shortcut-registry';

/**
 * The board's keys (orders.md §2.12: «the board is keyboard-first»), in the three languages. Local to
 * the board's lazy chunk -- see `LocalMessages` for why the initial bundle cannot take more Russian
 * keys.
 */
const boardMessages = localMessages({
  ru: {
    title: 'Доска заказов',
    search: 'Перейти в поиск',
    leaveSearch: 'Очистить поиск и выйти из него',
    next: 'Следующий заказ',
    previous: 'Предыдущий заказ',
    open: 'Открыть выбранный заказ',
    select: 'Отметить или снять отметку с заказа',
    tab: 'Переключить вкладку: от «Внимание» до «Все»',
    approve: 'Подтвердить заказ (только если он ждёт подтверждения)',
    cancel: 'Отменить заказ — сначала откроется окно подтверждения',
    courier: 'Назначить курьера на заказ',
    newOrder: 'Новый заказ',
    refresh: 'Обновить доску',
    leaveSelection: 'Выйти из режима выбора',
  },
  'uz-Latn': {
    title: 'Buyurtmalar taxtasi',
    search: 'Qidiruvga oʻtish',
    leaveSearch: 'Qidiruvni tozalab, undan chiqish',
    next: 'Keyingi buyurtma',
    previous: 'Oldingi buyurtma',
    open: 'Tanlangan buyurtmani ochish',
    select: 'Buyurtmani belgilash yoki belgisini olib tashlash',
    tab: 'Yorliqni almashtirish: «Diqqat»dan «Hammasi»gacha',
    approve: 'Buyurtmani tasdiqlash (faqat tasdiq kutayotgan boʻlsa)',
    cancel: 'Buyurtmani bekor qilish — avval tasdiqlash oynasi ochiladi',
    courier: 'Buyurtmaga kuryer tayinlash',
    newOrder: 'Yangi buyurtma',
    refresh: 'Taxtani yangilash',
    leaveSelection: 'Tanlash rejimidan chiqish',
  },
  en: {
    title: 'Order board',
    search: 'Go to the search box',
    leaveSearch: 'Clear the search and leave it',
    next: 'Next order',
    previous: 'Previous order',
    open: 'Open the focused order',
    select: 'Select or deselect the focused order',
    tab: 'Switch tab: Attention through All',
    approve: 'Approve the focused order (only when it awaits approval)',
    cancel: 'Cancel the focused order — a dialog asks first',
    courier: 'Assign a courier to the focused order',
    newOrder: 'New order',
    refresh: 'Refresh the board',
    leaveSelection: 'Leave selection mode',
  },
});

/** The order actions a key can start. They are the server's own `actions[]` codes. */
export type BoardKeyAction = 'APPROVE' | 'CANCEL' | 'ASSIGN_COURIER';

/**
 * What the board offers its keys. The keys decide nothing about orders: whether an action is
 * available is the server's `actions[]` for that order (`canAct`), and doing it is the same
 * handler the row's own button calls (`act`), so a key can never reach an action the button would
 * not have offered.
 */
export interface OrderBoardKeys {
  locale(): Locale;
  /** Whether there is a row to move onto. */
  hasRows(): boolean;
  /** A row's overflow menu is open: its own keys are in use. */
  menuOpen(): boolean;
  /** The order that has focus offers this action and is not already busy with another. */
  canAct(action: BoardKeyAction): boolean;
  canToggleSelection(): boolean;
  hasSelection(): boolean;
  /** The search box, or null while the selection bar has replaced the filter row (§2.10). */
  searchField(): HTMLInputElement | null;
  move(delta: 1 | -1): void;
  act(action: BoardKeyAction, event: KeyboardEvent): void;
  toggleSelection(event: KeyboardEvent): void;
  /** 1 to 7. */
  selectTab(position: number): void;
  clearSelection(): void;
  clearSearch(field: HTMLInputElement): void;
  newOrder(): void;
  refresh(): void;
}

/**
 * The documented keys of the order board, as a registrable scope. `p` («print to POS») is not here:
 * the board's rows carry no print action (`OrderActionCode` has none; the POS push lives in the
 * order detail's panel), and a sheet that lists a key nothing handles is worse than no key.
 */
export function orderBoardScope(board: OrderBoardKeys): ShortcutScope {
  const text = (key: Parameters<typeof boardMessages.text>[1]): string =>
    boardMessages.text(board.locale(), key);
  const idle = (): boolean => board.hasRows() && !board.menuOpen();
  const onSearch = (event: KeyboardEvent): boolean => {
    const field = board.searchField();
    return field !== null && event.target === field;
  };

  return {
    id: 'orders-board',
    title: () => text('title'),
    shortcuts: [
      {
        keys: ['/'],
        caps: ['/'],
        label: () => text('search'),
        enabled: () => board.searchField() !== null,
        run: () => board.searchField()?.focus(),
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('leaveSearch'),
        inField: true,
        enabled: onSearch,
        run: (event) => {
          const field = board.searchField();
          if (field !== null && event.target === field) {
            board.clearSearch(field);
          }
        },
      },
      {
        keys: ['j', 'ArrowDown'],
        caps: ['j', '↓'],
        label: () => text('next'),
        enabled: idle,
        run: () => board.move(1),
      },
      {
        keys: ['k', 'ArrowUp'],
        caps: ['k', '↑'],
        label: () => text('previous'),
        enabled: idle,
        run: () => board.move(-1),
      },
      // The row opens itself on Enter (it is a focusable row with its own handler); listed so the
      // sheet is the whole scheme.
      { keys: ['Enter'], caps: ['Enter'], label: () => text('open') },
      {
        keys: [' '],
        caps: ['Space'],
        label: () => text('select'),
        enabled: () => idle() && board.canToggleSelection(),
        run: (event) => board.toggleSelection(event),
      },
      {
        keys: ['1', '2', '3', '4', '5', '6', '7'],
        caps: ['1–7'],
        label: () => text('tab'),
        run: (event) => board.selectTab(Number(event.key)),
      },
      {
        keys: ['a'],
        caps: ['a'],
        label: () => text('approve'),
        enabled: () => !board.menuOpen() && board.canAct('APPROVE'),
        run: (event) => board.act('APPROVE', event),
      },
      {
        keys: ['x'],
        caps: ['x'],
        label: () => text('cancel'),
        enabled: () => !board.menuOpen() && board.canAct('CANCEL'),
        run: (event) => board.act('CANCEL', event),
      },
      {
        keys: ['c'],
        caps: ['c'],
        label: () => text('courier'),
        enabled: () => !board.menuOpen() && board.canAct('ASSIGN_COURIER'),
        run: (event) => board.act('ASSIGN_COURIER', event),
      },
      {
        keys: ['n'],
        caps: ['n'],
        label: () => text('newOrder'),
        run: () => board.newOrder(),
      },
      {
        keys: ['r'],
        caps: ['r'],
        label: () => text('refresh'),
        run: () => board.refresh(),
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('leaveSelection'),
        enabled: () => board.hasSelection(),
        run: () => board.clearSelection(),
      },
    ],
  };
}
