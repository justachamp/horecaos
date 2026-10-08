import type { PlatformLocalesResponse } from '../app/core/i18n/platform-locales';

/**
 * The registry's answer as the backend gives it today (`PlatformLocales` in `tenancy.api`, ADR 0149):
 * `ru`, `uz-Latn` and `en` live in every tier, `kk` and `ka` declared and not live. A fixture may
 * list them; a console may not, which is why this is under `src/testing` and nothing in `src/app`
 * imports it.
 */
export const REGISTRY_FIXTURE: PlatformLocalesResponse = {
  fallback: 'ru',
  locales: [
    {
      tag: 'ru',
      catalogCode: 'ru',
      inputAliases: [],
      script: 'CYRL',
      direction: 'LTR',
      face: 'ibm-plex-sans',
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
      face: 'ibm-plex-sans',
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
      face: 'ibm-plex-sans',
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
      face: 'ibm-plex-sans',
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
      face: 'georgian-lazy',
      fallbackRank: 4,
      tiers: [],
      names: { ru: 'Грузинский', 'uz-Latn': 'Gruzincha', en: 'Georgian', ka: 'ქართული' },
    },
  ],
};
