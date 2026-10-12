import type { CatalogueLoaders } from './i18n';

/**
 * Dynamic-import loaders: one chunk per area and locale (`messages/<area>.<locale>.ts`).
 *
 * The one entry missing is the one that is not lazy: `ru`'s `core` is imported statically by `i18n.ts`,
 * so the default locale's first paint needs no network round trip and no await. Every other chunk is
 * fetched the first time something asks for it -- `main.ts` for the persisted locale's `core`, a route's
 * `messagesGuard` for the area it is about to show, a locale switch for the areas already in use.
 *
 * **This module is not imported by `i18n.ts`, on purpose.** It is handed to `I18n` through
 * {@link registerCatalogueLoaders}, by `main.ts` in the app and by `src/testing/i18n-preload.setup.ts` in
 * the unit tests. Were `i18n.ts` to import it, every module that reaches `I18n` -- nearly all of them --
 * would reach every catalogue module of every locale through these `import()`s. A bundler that cannot
 * split inlines every dynamic import into the entry that reaches it, and the unit-test builder is such a
 * bundler (it bundles each spec file as its own entry point with code splitting disabled), so each of the
 * five hundred spec bundles carried its own three-megabyte copy of the catalogues: 91% of the 1.2 GB the
 * suite bundled, and the esbuild peak of more than ten gigabytes that got the CI runner killed. In the
 * production bundle nothing changes: each `import()` below is still its own lazy chunk.
 */
export const CATALOGUE_LOADERS: CatalogueLoaders = {
  ru: {
    auth: () => import('./messages/auth.ru').then((m) => m.authRu),
    today: () => import('./messages/today.ru').then((m) => m.todayRu),
    orders: () => import('./messages/orders.ru').then((m) => m.ordersRu),
    customers: () => import('./messages/customers.ru').then((m) => m.customersRu),
    inbox: () => import('./messages/inbox.ru').then((m) => m.inboxRu),
    settings: () => import('./messages/settings.ru').then((m) => m.settingsRu),
    staff: () => import('./messages/staff.ru').then((m) => m.staffRu),
    finance: () => import('./messages/finance.ru').then((m) => m.financeRu),
    marketing: () => import('./messages/marketing.ru').then((m) => m.marketingRu),
    catalog: () => import('./messages/catalog.ru').then((m) => m.catalogRu),
    reports: () => import('./messages/reports.ru').then((m) => m.reportsRu),
    kitchen: () => import('./messages/kitchen.ru').then((m) => m.kitchenRu),
    delivery: () => import('./messages/delivery.ru').then((m) => m.deliveryRu),
    couriers: () => import('./messages/couriers.ru').then((m) => m.couriersRu),
    device: () => import('./messages/device.ru').then((m) => m.deviceRu),
    wallboard: () => import('./messages/wallboard.ru').then((m) => m.wallboardRu),
    map: () => import('./messages/map.ru').then((m) => m.mapRu),
  },
  'uz-Latn': {
    core: () => import('./messages/core.uz-latn').then((m) => m.coreUzLatn),
    auth: () => import('./messages/auth.uz-latn').then((m) => m.authUzLatn),
    today: () => import('./messages/today.uz-latn').then((m) => m.todayUzLatn),
    orders: () => import('./messages/orders.uz-latn').then((m) => m.ordersUzLatn),
    customers: () => import('./messages/customers.uz-latn').then((m) => m.customersUzLatn),
    inbox: () => import('./messages/inbox.uz-latn').then((m) => m.inboxUzLatn),
    settings: () => import('./messages/settings.uz-latn').then((m) => m.settingsUzLatn),
    staff: () => import('./messages/staff.uz-latn').then((m) => m.staffUzLatn),
    finance: () => import('./messages/finance.uz-latn').then((m) => m.financeUzLatn),
    marketing: () => import('./messages/marketing.uz-latn').then((m) => m.marketingUzLatn),
    catalog: () => import('./messages/catalog.uz-latn').then((m) => m.catalogUzLatn),
    reports: () => import('./messages/reports.uz-latn').then((m) => m.reportsUzLatn),
    kitchen: () => import('./messages/kitchen.uz-latn').then((m) => m.kitchenUzLatn),
    delivery: () => import('./messages/delivery.uz-latn').then((m) => m.deliveryUzLatn),
    couriers: () => import('./messages/couriers.uz-latn').then((m) => m.couriersUzLatn),
    device: () => import('./messages/device.uz-latn').then((m) => m.deviceUzLatn),
    wallboard: () => import('./messages/wallboard.uz-latn').then((m) => m.wallboardUzLatn),
    map: () => import('./messages/map.uz-latn').then((m) => m.mapUzLatn),
  },
  en: {
    core: () => import('./messages/core.en').then((m) => m.coreEn),
    auth: () => import('./messages/auth.en').then((m) => m.authEn),
    today: () => import('./messages/today.en').then((m) => m.todayEn),
    orders: () => import('./messages/orders.en').then((m) => m.ordersEn),
    customers: () => import('./messages/customers.en').then((m) => m.customersEn),
    inbox: () => import('./messages/inbox.en').then((m) => m.inboxEn),
    settings: () => import('./messages/settings.en').then((m) => m.settingsEn),
    staff: () => import('./messages/staff.en').then((m) => m.staffEn),
    finance: () => import('./messages/finance.en').then((m) => m.financeEn),
    marketing: () => import('./messages/marketing.en').then((m) => m.marketingEn),
    catalog: () => import('./messages/catalog.en').then((m) => m.catalogEn),
    reports: () => import('./messages/reports.en').then((m) => m.reportsEn),
    kitchen: () => import('./messages/kitchen.en').then((m) => m.kitchenEn),
    delivery: () => import('./messages/delivery.en').then((m) => m.deliveryEn),
    couriers: () => import('./messages/couriers.en').then((m) => m.couriersEn),
    device: () => import('./messages/device.en').then((m) => m.deviceEn),
    wallboard: () => import('./messages/wallboard.en').then((m) => m.wallboardEn),
    map: () => import('./messages/map.en').then((m) => m.mapEn),
  },
};
