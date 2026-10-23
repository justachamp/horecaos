import { describe, expect, it } from 'vitest';

import { LOCALES } from '../../core/i18n/i18n';
import { SETTINGS_NAVIGATION } from './settings-nav';
import {
  CONFIGURATION_KEY_ROUTES,
  NO_CONSOLE_SCREEN,
  NO_POLICY_SCREEN,
  POLICY_ROUTES,
  SETTING_ENTRIES,
} from './settings-search-index';
import { settingsSearchText } from './settings-search-text';

/**
 * The coverage guard of «Find a setting» (settings.md §10.0), console half. The platform half
 * (`ConfigurationKeyConsoleCoverageTests`) reads this file and holds it to `ConfigurationKeys.all()` and to
 * `TenantPolicyFamilies`; this half holds the file to itself and to the screens it names.
 */
describe('the settings search index', () => {
  const navPaths = new Set(SETTINGS_NAVIGATION.flatMap((group) => group.items.map((i) => i.path)));
  const routes = [
    ...Object.values(SETTING_ENTRIES).map((entry) => entry.route),
    ...Object.values(POLICY_ROUTES).map((entry) => entry.route),
  ];

  it('puts a key on a screen or on the list of keys no screen sets, never both and never neither', () => {
    const routed = Object.keys(SETTING_ENTRIES);
    const unrouted = Object.keys(NO_CONSOLE_SCREEN);

    expect(routed.filter((code) => unrouted.includes(code))).toEqual([]);
    expect(new Set([...routed, ...unrouted]).size).toBe(routed.length + unrouted.length);
  });

  it('names, for every key without a screen, the gap-map row that owes it one', () => {
    for (const [code, row] of Object.entries(NO_CONSOLE_SCREEN)) {
      expect(row, code).toMatch(/^(\d+|X)(\.\d+[a-z]?|\/X\.\d+)?$/);
    }
    for (const [code, row] of Object.entries(NO_POLICY_SCREEN)) {
      expect(row, code).toMatch(/^\d+\.\d+[a-z]?$/);
    }
  });

  it('puts a policy on a screen or on the list of policies no screen authors, never both', () => {
    const routed = Object.keys(POLICY_ROUTES);

    expect(routed.filter((code) => code in NO_POLICY_SCREEN)).toEqual([]);
  });

  it('sends every result to a screen the console has: a Settings tile, or a section it names', () => {
    for (const route of new Set(routes)) {
      if (navPaths.has(route)) {
        continue;
      }
      expect(
        route.startsWith('/'),
        `${route} is neither a Settings tile nor outside Settings`,
      ).toBe(true);
      for (const locale of LOCALES) {
        expect(
          settingsSearchText.text(locale, `dest.${route}` as never).length,
          `${locale} ${route}`,
        ).toBeGreaterThan(0);
      }
    }
  });

  it('names every setting in every language, and every policy', () => {
    for (const locale of LOCALES) {
      for (const code of Object.keys(SETTING_ENTRIES)) {
        const label = settingsSearchText.text(locale, `key.${code}` as never);
        expect(label, `${locale} ${code}`).toBeTruthy();
      }
      for (const code of Object.keys(POLICY_ROUTES)) {
        expect(
          settingsSearchText.text(locale, `policy.${code}` as never),
          `${locale} ${code}`,
        ).toBeTruthy();
      }
    }
  });

  it('gives no two settings the same name in one language, so a result is never ambiguous', () => {
    for (const locale of LOCALES) {
      const labels = Object.keys(SETTING_ENTRIES).map((code) =>
        settingsSearchText.text(locale, `key.${code}` as never),
      );
      expect(new Set(labels).size, locale).toBe(labels.length);
    }
  });

  it('keeps CONFIGURATION_KEY_ROUTES as the screen of each routed key', () => {
    expect(Object.keys(CONFIGURATION_KEY_ROUTES).sort()).toEqual(
      Object.keys(SETTING_ENTRIES).sort(),
    );
    expect(CONFIGURATION_KEY_ROUTES['ordering.minimum_order_amount_minor']).toBe('order-policy');
    expect(CONFIGURATION_KEY_ROUTES['delivery.out_of_zone_policy']).toBe(
      '/delivery/courier-policy',
    );
  });
});
