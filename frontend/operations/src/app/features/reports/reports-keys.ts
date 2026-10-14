import { inject } from '@angular/core';

import { localMessages } from '../../core/i18n/local-messages';
import {
  ScreenKeys,
  controlIn,
  focusFirst,
  press,
  registerScreenKeys,
} from '../../shared/keyboard/screen-keys';
import { Shortcut, ShortcutScope } from '../../shared/keyboard/shortcut-registry';
import { ReportsFilterState } from './reports-filter-state';

/**
 * The reports' keys (statistics.md §1.4, gap map row `X.1`), registered by the section's frame so they hold on
 * every tab: `d` `w` `m` set the period to today / seven days / this month; `[` and `]` shift it back and
 * forward by its own length; `g` then `o s b c p m` jumps to Overview / orderS / Branches / Couriers /
 * Products / Marketing; `/` goes to the view's search box where it has one; `e` goes to the export where the
 * view links one; `Esc` clears the slice (the secondary filters), which is what a second `Esc` does after the
 * first has closed a dialog (a dialog closes itself, and no key here runs while one is open).
 *
 * The keys press the pills, tabs and links the frame already draws, so one that is not drawn (the period pills
 * on the demand and geography tabs, which hide the bar) is not offered there. Two spec details differ from the
 * screen: the spec's `e` opens an export dialog on the view, and there is none -- an export is made on the
 * export centre, so `e` goes there from the view that links it; and `?` is the shell's sheet, not repeated.
 * `statistics.md` §1.4 records both.
 */
const messages = localMessages({
  ru: {
    title: 'Отчёты',
    today: 'Период: сегодня',
    week: 'Период: 7 дней',
    month: 'Период: месяц',
    back: 'Сдвинуть период назад',
    forward: 'Сдвинуть период вперёд',
    overview: 'Перейти к обзору',
    orders: 'Перейти к заказам',
    branches: 'Перейти к филиалам',
    couriers: 'Перейти к курьерам',
    products: 'Перейти к товарам',
    marketing: 'Перейти к маркетингу',
    search: 'Перейти в поиск отчёта',
    exportView: 'Перейти к выгрузке',
    clearSlice: 'Сбросить срез (вторичные фильтры)',
  },
  'uz-Latn': {
    title: 'Hisobotlar',
    today: 'Davr: bugun',
    week: 'Davr: 7 kun',
    month: 'Davr: oy',
    back: 'Davrni orqaga surish',
    forward: 'Davrni oldinga surish',
    overview: 'Sharhga oʻtish',
    orders: 'Buyurtmalarga oʻtish',
    branches: 'Filiallarga oʻtish',
    couriers: 'Kuryerlarga oʻtish',
    products: 'Mahsulotlarga oʻtish',
    marketing: 'Marketingga oʻtish',
    search: 'Hisobot qidiruviga oʻtish',
    exportView: 'Eksportga oʻtish',
    clearSlice: 'Kesimni tozalash (ikkilamchi filtrlar)',
  },
  en: {
    title: 'Reports',
    today: 'Period: today',
    week: 'Period: 7 days',
    month: 'Period: this month',
    back: 'Shift the period back',
    forward: 'Shift the period forward',
    overview: 'Go to Overview',
    orders: 'Go to Orders',
    branches: 'Go to Branches',
    couriers: 'Go to Couriers',
    products: 'Go to Products',
    marketing: 'Go to Marketing',
    search: "Go to the report's search box",
    exportView: 'Go to the export',
    clearSlice: 'Clear the slice (the secondary filters)',
  },
});

const FILTER_BAR = '[data-testid="reports-shell-filter-bar"]';
const SEARCH = 'input[type="search"]';
const EXPORT_LINK = '[data-testid="order-reports-export-link"]';

type Text = (key: Parameters<typeof messages.text>[1]) => string;

export function reportsScope(screen: ScreenKeys, state: ReportsFilterState): ShortcutScope {
  const text: Text = (key) => messages.text(screen.locale(), key);
  const { host } = screen;
  const pill = (period: string): string => `[data-testid="reports-filter-period-${period}"]`;
  const tab = (path: string): string => `a[href="/statistics/${path}"]`;
  const barShown = (): boolean => host.querySelector(FILTER_BAR) !== null;

  const period = (
    key: string,
    period: string,
    label: Parameters<typeof messages.text>[1],
  ): Shortcut => ({
    keys: [key],
    caps: [key],
    label: () => text(label),
    enabled: () => controlIn(host, pill(period)) !== null,
    run: () => void press(host, pill(period)),
  });
  const step = (
    key: string,
    direction: -1 | 1,
    label: Parameters<typeof messages.text>[1],
  ): Shortcut => ({
    keys: [key],
    caps: [key],
    label: () => text(label),
    enabled: barShown,
    run: () => state.stepPeriod(direction),
  });
  const jump = (
    second: string,
    path: string,
    label: Parameters<typeof messages.text>[1],
  ): Shortcut => ({
    keys: [],
    sequence: ['g', second],
    caps: ['g', second],
    label: () => text(label),
    enabled: () => controlIn(host, tab(path)) !== null,
    run: () => void press(host, tab(path)),
  });

  return {
    id: 'reports',
    title: () => text('title'),
    shortcuts: [
      period('d', 'today', 'today'),
      period('w', '7d', 'week'),
      period('m', 'month', 'month'),
      step('[', -1, 'back'),
      step(']', 1, 'forward'),
      jump('o', 'overview', 'overview'),
      jump('s', 'orders', 'orders'),
      jump('b', 'branches', 'branches'),
      jump('c', 'couriers', 'couriers'),
      jump('p', 'products', 'products'),
      jump('m', 'marketing', 'marketing'),
      {
        keys: ['/'],
        caps: ['/'],
        label: () => text('search'),
        enabled: () => host.querySelector(SEARCH) !== null,
        run: () => void focusFirst(host, SEARCH),
      },
      {
        keys: ['e'],
        caps: ['e'],
        label: () => text('exportView'),
        enabled: () => controlIn(host, EXPORT_LINK) !== null,
        run: () => void press(host, EXPORT_LINK),
      },
      {
        keys: ['Escape'],
        caps: ['Esc'],
        label: () => text('clearSlice'),
        enabled: (event) => !event.defaultPrevented && state.hasAnySecondaryFilter(),
        run: () => state.resetFilters(),
      },
    ],
  };
}

/** The frame's one line: `private readonly keys = registerReportsKeys();`. */
export function registerReportsKeys(): void {
  const state = inject(ReportsFilterState);
  registerScreenKeys((screen) => reportsScope(screen, state));
}
