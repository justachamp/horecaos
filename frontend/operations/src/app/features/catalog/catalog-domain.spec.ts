import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { PlatformLocales } from '../../core/i18n/platform-locales';
import {
  SERVER_CATALOG_DEFAULT_LOCALE,
  fromCatalogLocale,
  listResolutionLocale,
  toCatalogLocale,
} from './catalog-domain';

/**
 * The console's one conversion between a platform tag and the catalog's own code (ADR 0149): it used
 * to be a ternary here and a twin in the platform, and it is now the registry's `catalogCode` read
 * through `PlatformLocales`. A change to what the catalog stores for a language is a change to the
 * registry, and these follow it.
 */
describe('catalog locale codes', () => {
  const registry = (): PlatformLocales => TestBed.inject(PlatformLocales);

  it('writes Uzbek under the catalog’s uz and every other language under its own tag', () => {
    expect(toCatalogLocale('uz-Latn', registry())).toBe('uz');
    expect(toCatalogLocale('ru', registry())).toBe('ru');
    expect(toCatalogLocale('en', registry())).toBe('en');
  });

  it('reads the catalog’s uz back as the platform’s uz-Latn, for naming it to an operator', () => {
    expect(fromCatalogLocale('uz', registry())).toBe('uz-Latn');
    expect(fromCatalogLocale('ru', registry())).toBe('ru');
    expect(fromCatalogLocale('xx', registry())).toBe('xx');
  });

  it('is the identity for a language the registry has never heard of, rather than inventing a code', () => {
    expect(toCatalogLocale('kaa', registry())).toBe('kaa');
  });

  it('resolves a list screen in the brand’s own default once it has chosen one, and in the server’s uz until then', () => {
    expect(listResolutionLocale(true, 'uz-Latn', registry())).toBe('uz');
    expect(listResolutionLocale(true, 'en', registry())).toBe('en');
    expect(listResolutionLocale(false, 'ru', registry())).toBe(SERVER_CATALOG_DEFAULT_LOCALE);
  });
});
