import { describe, expect, it } from 'vitest';

import { LOCALES, areasLoadedForTesting } from './i18n';
import {
  CORE_AREA,
  MESSAGE_AREAS,
  areaOfKey,
  namespacesOfArea,
  prefixesOfArea,
} from './message-areas';
import { messagesEn } from './messages.en';
import { messagesRu } from './messages.ru';
import { messagesUzLatn } from './messages.uz-latn';

/**
 * The layout of the split catalogue: every key lives in the module of the area its prefix names, in
 * every locale, and every loader the runtime uses returns that module. `tools/i18n/split-catalogues.mjs`
 * writes the layout; these specs are what makes a hand edit that breaks it (a key added to the wrong
 * area's file, a loader pointing at the wrong module) fail the build.
 */
describe('message areas', () => {
  const whole = { en: messagesEn, ru: messagesRu, 'uz-Latn': messagesUzLatn } as const;

  it('places every key of the catalogue in an area', () => {
    const unplaced = Object.keys(messagesEn).filter((key) => areaOfKey(key) === undefined);
    expect(unplaced).toEqual([]);
  });

  it('puts the longest matching prefix first, and an unknown namespace nowhere', () => {
    expect(areaOfKey('orders.queue.title')).toBe('orders');
    expect(areaOfKey('orders.status.NEW')).toBe(CORE_AREA);
    expect(areaOfKey('orders.status')).toBe(CORE_AREA);
    expect(areaOfKey('reservations.title')).toBe('orders');
    expect(areaOfKey('wallboardKitchen.title')).toBe('wallboard');
    expect(areaOfKey('settings.scope.denied')).toBe(CORE_AREA);
    expect(areaOfKey('settings.scopeBar.x')).toBe('settings');
    expect(areaOfKey('nowhere.at.all')).toBeUndefined();
    expect(areaOfKey('')).toBeUndefined();
  });

  it('names every area in the tables, and every table entry names an area', () => {
    for (const area of MESSAGE_AREAS) {
      expect(namespacesOfArea(area).length + prefixesOfArea(area).length, area).toBeGreaterThan(0);
    }
  });

  for (const locale of LOCALES) {
    describe(locale, () => {
      const loaded = () => areasLoadedForTesting(locale);

      it('has a module for every area, and each one holds only keys of that area', () => {
        for (const area of MESSAGE_AREAS) {
          const messages = loaded().get(area);
          expect(messages, `${locale}/${area} was not loaded`).toBeDefined();
          const strays = Object.keys(messages ?? {}).filter((key) => areaOfKey(key) !== area);
          expect(strays, `${locale}/${area} holds keys that belong elsewhere`).toEqual([]);
        }
      });

      it('adds up to the whole catalogue: no key twice, none missing', () => {
        const keys = MESSAGE_AREAS.flatMap((area) => Object.keys(loaded().get(area) ?? {}));
        expect(new Set(keys).size, 'a key is defined in two areas').toBe(keys.length);
        expect([...keys].sort()).toEqual(Object.keys(whole[locale]).sort());
      });

      it('keeps `core` small enough to ship in the initial bundle', () => {
        const core = loaded().get(CORE_AREA) ?? {};
        // The number is a tripwire, not a budget: a key landing in `core` is a decision to put it on
        // every operator's first load. The build's initial-bundle budget is the real limit.
        expect(Object.keys(core).length).toBeLessThan(500);
      });
    });
  }
});
