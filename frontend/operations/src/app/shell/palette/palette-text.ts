import { localMessages } from '../../core/i18n/local-messages';

/** The command palette's own sentences (settings.md §1.6, `⌘K`); they load with the palette, after first paint. */
export const paletteText = localMessages({
  ru: {
    title: 'Быстрый переход',
    placeholder: 'Куда перейти, какую настройку найти, какой заказ открыть…',
    label: 'Поиск по консоли',
    empty: 'Ничего не найдено.',
    'group.go': 'Перейти',
    'group.setting': 'Настройка',
    'group.order': 'Заказ',
    'go.hint': 'Раздел',
    'order.label': 'Найти заказ «{query}»',
    'order.hint': 'Откроет доски заказов с этим номером',
    'keys.title': 'Быстрый переход',
    'keys.open': 'Открыть быстрый переход',
    results: 'Результатов: {count}',
  },
  'uz-Latn': {
    title: 'Tezkor oʻtish',
    placeholder: 'Qayerga oʻtish, qaysi sozlamani topish, qaysi buyurtmani ochish…',
    label: 'Konsol boʻyicha qidiruv',
    empty: 'Hech narsa topilmadi.',
    'group.go': 'Oʻtish',
    'group.setting': 'Sozlama',
    'group.order': 'Buyurtma',
    'go.hint': 'Boʻlim',
    'order.label': '«{query}» buyurtmasini topish',
    'order.hint': 'Buyurtmalar doskasini shu raqam bilan ochadi',
    'keys.title': 'Tezkor oʻtish',
    'keys.open': 'Tezkor oʻtishni ochish',
    results: 'Natijalar: {count}',
  },
  en: {
    title: 'Quick jump',
    placeholder: 'Where to go, which setting to find, which order to open…',
    label: 'Search the console',
    empty: 'Nothing found.',
    'group.go': 'Go to',
    'group.setting': 'Setting',
    'group.order': 'Order',
    'go.hint': 'Section',
    'order.label': 'Find order “{query}”',
    'order.hint': 'Opens the order board filtered by this number',
    'keys.title': 'Quick jump',
    'keys.open': 'Open the quick jump',
    results: '{count} results',
  },
});

export type PaletteTextKey = Parameters<typeof paletteText.text>[1];
