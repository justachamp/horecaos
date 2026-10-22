import type { CatalogueLoaders } from './i18n';

/**
 * Dynamic-import loaders: one chunk per area and locale (`messages/<area>.<locale>.ts`).
 *
 * **This module is not imported by `i18n.ts`, on purpose.** It is handed to `I18n` through
 * `registerCatalogueLoaders`, by `main.ts` in the app and by `src/testing/i18n.setup.ts` in the
 * unit tests. Were `i18n.ts` to import it, every module that reaches `I18n` would reach every
 * catalogue of every locale through these `import()`s, and the unit-test builder -- which bundles
 * each spec as its own entry with code splitting off -- would inline all of them into every spec.
 * In the production bundle nothing changes: each `import()` below is its own lazy chunk.
 */
export const CATALOGUE_LOADERS: CatalogueLoaders = {
  ru: {
    core: () => import('./messages/core.ru').then((m) => m.coreRu),
    home: () => import('./messages/home.ru').then((m) => m.homeRu),
    profile: () => import('./messages/profile.ru').then((m) => m.profileRu),
    shift: () => import('./messages/shift.ru').then((m) => m.shiftRu),
    notice: () => import('./messages/notice.ru').then((m) => m.noticeRu),
  },
  'uz-Latn': {
    core: () => import('./messages/core.uz-latn').then((m) => m.coreUzLatn),
    home: () => import('./messages/home.uz-latn').then((m) => m.homeUzLatn),
    profile: () => import('./messages/profile.uz-latn').then((m) => m.profileUzLatn),
    shift: () => import('./messages/shift.uz-latn').then((m) => m.shiftUzLatn),
    notice: () => import('./messages/notice.uz-latn').then((m) => m.noticeUzLatn),
  },
  en: {
    core: () => import('./messages/core.en').then((m) => m.coreEn),
    home: () => import('./messages/home.en').then((m) => m.homeEn),
    profile: () => import('./messages/profile.en').then((m) => m.profileEn),
    shift: () => import('./messages/shift.en').then((m) => m.shiftEn),
    notice: () => import('./messages/notice.en').then((m) => m.noticeEn),
  },
};
