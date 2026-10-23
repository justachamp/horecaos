import { localMessages } from '../../../core/i18n/local-messages';
import { ScreenKeys, press, registerScreenKeys } from '../../../shared/keyboard/screen-keys';
import { ShortcutScope } from '../../../shared/keyboard/shortcut-registry';

/**
 * The staff shifts view's keys (staff-and-access.md §9.2, «This screen is used standing»): `1`, `2`, `3` switch
 * between the week plan, today and the timesheet, and `t` jumps to today. The week grid keeps its own cell
 * cursor, with arrow keys, `Enter` to open the cell editor and `Esc` to close it (`roster-week-grid.ts`), so
 * none of those is registered twice here.
 *
 * The spec says `1`/`2` for two views; the screen has three tabs, so `3` is the third. Every key presses the
 * tab the screen already draws, so a tab the person's grant does not show is a tab no key reaches.
 */
const messages = localMessages({
  ru: {
    title: 'Смены сотрудников',
    week: 'План недели',
    today: 'Сегодня',
    timesheet: 'Табель',
    jumpToday: 'Перейти к сегодняшнему дню',
  },
  'uz-Latn': {
    title: 'Xodimlar smenalari',
    week: 'Hafta rejasi',
    today: 'Bugun',
    timesheet: 'Tabel',
    jumpToday: 'Bugungi kunga oʻtish',
  },
  en: {
    title: 'Staff shifts',
    week: 'Week plan',
    today: 'Today',
    timesheet: 'Timesheet',
    jumpToday: 'Jump to today',
  },
});

export function staffShiftsScope(screen: ScreenKeys): ShortcutScope {
  const text = (key: Parameters<typeof messages.text>[1]): string =>
    messages.text(screen.locale(), key);
  const { host } = screen;
  const tab = (id: 'week' | 'today' | 'timesheet') => `[data-testid="shifts-tab-${id}"]`;
  const hasTab = (id: 'week' | 'today' | 'timesheet'): boolean =>
    host.querySelector(tab(id)) !== null;

  return {
    id: 'staff-shifts',
    title: () => text('title'),
    shortcuts: [
      {
        keys: ['1'],
        caps: ['1'],
        label: () => text('week'),
        enabled: () => hasTab('week'),
        run: () => void press(host, tab('week')),
      },
      {
        keys: ['2'],
        caps: ['2'],
        label: () => text('today'),
        enabled: () => hasTab('today'),
        run: () => void press(host, tab('today')),
      },
      {
        keys: ['3'],
        caps: ['3'],
        label: () => text('timesheet'),
        enabled: () => hasTab('timesheet'),
        run: () => void press(host, tab('timesheet')),
      },
      {
        keys: ['t'],
        caps: ['t'],
        label: () => text('jumpToday'),
        enabled: () => hasTab('today'),
        run: () => void press(host, tab('today')),
      },
    ],
  };
}

/** The page's one line: `private readonly keys = registerStaffShiftsKeys();`. */
export function registerStaffShiftsKeys(): void {
  registerScreenKeys(staffShiftsScope);
}
