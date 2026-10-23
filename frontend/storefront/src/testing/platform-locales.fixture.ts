import type { PlatformLocaleEntry } from '../app/services/platform-locales.service';

/**
 * `GET /api/v1/storefront/locales` as the platform answers today (ADR 0149): `ru`, `uz-Latn` and `en`
 * live in every tier, `kk` and `ka` declared and live in none. A fixture, not a second source: the
 * activation rehearsal (`activation-rehearsal.spec.ts`) widens it to prove the storefront follows the
 * registry, and the backend's own `PlatformLocalesTests` is what keeps the real answer honest.
 */
export const REGISTRY_FIXTURE: readonly PlatformLocaleEntry[] = [
  {
    tag: 'ru',
    catalogCode: 'ru',
    inputAliases: [],
    script: 'CYRL',
    direction: 'LTR',
    fallbackRank: 0,
    tiers: ['CONTENT', 'MESSAGES', 'STAFF_UI'],
    names: { ru: 'Русский', 'uz-Latn': 'Rus tili', en: 'Russian' },
  },
  {
    tag: 'uz-Latn',
    catalogCode: 'uz',
    inputAliases: ['uz'],
    script: 'LATN',
    direction: 'LTR',
    fallbackRank: 1,
    tiers: ['CONTENT', 'MESSAGES', 'STAFF_UI'],
    names: { ru: 'Узбекский', 'uz-Latn': 'Oʻzbekcha', en: 'Uzbek' },
  },
  {
    tag: 'en',
    catalogCode: 'en',
    inputAliases: [],
    script: 'LATN',
    direction: 'LTR',
    fallbackRank: 2,
    tiers: ['CONTENT', 'MESSAGES', 'STAFF_UI'],
    names: { ru: 'Английский', 'uz-Latn': 'Inglizcha', en: 'English' },
  },
  {
    tag: 'kk',
    catalogCode: 'kk',
    inputAliases: [],
    script: 'CYRL',
    direction: 'LTR',
    fallbackRank: 3,
    tiers: [],
    names: { ru: 'Казахский', 'uz-Latn': 'Qozoqcha', en: 'Kazakh', kk: 'Қазақша' },
  },
  {
    tag: 'ka',
    catalogCode: 'ka',
    inputAliases: [],
    script: 'GEOR',
    direction: 'LTR',
    fallbackRank: 4,
    tiers: [],
    names: { ru: 'Грузинский', 'uz-Latn': 'Gruzincha', en: 'Georgian', ka: 'ქართული' },
  },
];
