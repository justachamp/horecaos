import { coreEn } from './messages/core.en';
import { coreRu } from './messages/core.ru';
import { coreUzLatn } from './messages/core.uz-latn';
import { homeEn } from './messages/home.en';
import { homeRu } from './messages/home.ru';
import { homeUzLatn } from './messages/home.uz-latn';
import { profileEn } from './messages/profile.en';
import { profileRu } from './messages/profile.ru';
import { profileUzLatn } from './messages/profile.uz-latn';
import { shiftEn } from './messages/shift.en';
import { shiftRu } from './messages/shift.ru';
import { shiftUzLatn } from './messages/shift.uz-latn';
import { noticeEn } from './messages/notice.en';
import { noticeRu } from './messages/notice.ru';
import { noticeUzLatn } from './messages/notice.uz-latn';
import { workEn } from './messages/work.en';
import { workRu } from './messages/work.ru';
import { workUzLatn } from './messages/work.uz-latn';
import { offersEn } from './messages/offers.en';
import { offersRu } from './messages/offers.ru';
import { offersUzLatn } from './messages/offers.uz-latn';
import { deliveriesEn } from './messages/deliveries.en';
import { deliveriesRu } from './messages/deliveries.ru';
import { deliveriesUzLatn } from './messages/deliveries.uz-latn';
import { CATALOGUE_LOADERS } from './catalogue-loaders';
import { MESSAGE_AREAS } from './message-areas';

const AREAS: Record<string, Record<'en' | 'ru' | 'uz-Latn', Record<string, string>>> = {
  core: { en: coreEn, ru: coreRu, 'uz-Latn': coreUzLatn },
  home: { en: homeEn, ru: homeRu, 'uz-Latn': homeUzLatn },
  profile: { en: profileEn, ru: profileRu, 'uz-Latn': profileUzLatn },
  shift: { en: shiftEn, ru: shiftRu, 'uz-Latn': shiftUzLatn },
  notice: { en: noticeEn, ru: noticeRu, 'uz-Latn': noticeUzLatn },
  work: { en: workEn, ru: workRu, 'uz-Latn': workUzLatn },
  offers: { en: offersEn, ru: offersRu, 'uz-Latn': offersUzLatn },
  deliveries: { en: deliveriesEn, ru: deliveriesRu, 'uz-Latn': deliveriesUzLatn },
};

const placeholders = (text: string) =>
  [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();

describe('the message catalogues', () => {
  for (const [area, locales] of Object.entries(AREAS)) {
    describe(`${area}`, () => {
      for (const locale of ['ru', 'uz-Latn'] as const) {
        it(`${locale} has exactly the keys English has -- none missing, none left over`, () => {
          expect(Object.keys(locales[locale]).sort()).toEqual(Object.keys(locales.en).sort());
        });

        it(`${locale} keeps every {placeholder} of the English message`, () => {
          for (const [key, english] of Object.entries(locales.en)) {
            expect(placeholders(locales[locale][key]), key).toEqual(placeholders(english));
          }
        });

        it(`${locale} has no empty message`, () => {
          for (const [key, text] of Object.entries(locales[locale])) {
            expect(text.trim(), key).not.toBe('');
          }
        });
      }
    });
  }

  it("gives every key to exactly one area, so a key cannot be shadowed by another area's", () => {
    const seen = new Map<string, string>();
    for (const [area, locales] of Object.entries(AREAS)) {
      for (const key of Object.keys(locales.en)) {
        expect(seen.get(key), `${key} is in ${area} and ${seen.get(key)}`).toBeUndefined();
        seen.set(key, area);
      }
    }
  });

  it('registers a loader for every area and locale, and no area the catalogue lacks', () => {
    for (const locale of ['ru', 'uz-Latn', 'en'] as const) {
      expect(Object.keys(CATALOGUE_LOADERS[locale]).sort()).toEqual([...MESSAGE_AREAS].sort());
    }
    expect(Object.keys(AREAS).sort()).toEqual([...MESSAGE_AREAS].sort());
  });

  it('loads each area from the module that defines it', async () => {
    expect(await CATALOGUE_LOADERS['uz-Latn']['home']!()).toBe(homeUzLatn);
    expect(await CATALOGUE_LOADERS['en']['core']!()).toBe(coreEn);
  });
});
