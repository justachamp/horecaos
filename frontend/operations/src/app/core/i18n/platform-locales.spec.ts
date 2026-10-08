import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { REGISTRY_FIXTURE } from '../../../testing/platform-locales.fixture';
import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { DocumentDirection } from './document-direction';
import { I18n } from './i18n';
import {
  LOCALES_PATH,
  PlatformLocales,
  activeTags,
  entryOf,
  seedPlatformLocalesForTesting,
} from './platform-locales';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/** What `GET /api/v1/operations/locales` answers: the backend's registry. */
const ANSWER = REGISTRY_FIXTURE;

describe('PlatformLocales', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    // These specs prove the state before the registry has been read, so they start from nothing;
    // everything else in the suite starts from the seeded answer (src/testing/i18n-preload.setup.ts).
    seedPlatformLocalesForTesting(null);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    seedPlatformLocalesForTesting(ANSWER);
  });

  it('offers nothing and converts nothing before the registry has been read', () => {
    const registry = TestBed.inject(PlatformLocales);

    expect(registry.isLoaded()).toBe(false);
    expect(registry.active('CONTENT')).toEqual([]);
    expect(registry.catalogCode('uz-Latn')).toBe('uz-Latn');
    expect(registry.fallback()).toBe('ru');
    expect(registry.fallbackOrder()).toEqual(['ru']);
    expect(registry.directionOf('ru')).toBe('ltr');
  });

  it('reads the registry once, from the operations surface, and answers from what it read', async () => {
    const registry = TestBed.inject(PlatformLocales);

    const first = registry.ensureLoaded();
    const second = registry.ensureLoaded();
    http.expectOne(url(LOCALES_PATH)).flush(ANSWER);
    await Promise.all([first, second]);

    expect(registry.isLoaded()).toBe(true);
    expect(registry.active('CONTENT')).toEqual(['ru', 'uz-Latn', 'en']);
    expect(registry.active('STAFF_UI')).toEqual(['ru', 'uz-Latn', 'en']);
    expect(registry.fallbackOrder()).toEqual(['ru', 'uz-Latn', 'en', 'kk', 'ka']);
  });

  it('does not offer a language that is declared and not live, in any tier', async () => {
    const registry = TestBed.inject(PlatformLocales);
    const loaded = registry.ensureLoaded();
    http.expectOne(url(LOCALES_PATH)).flush(ANSWER);
    await loaded;

    for (const tier of ['CONTENT', 'MESSAGES', 'STAFF_UI'] as const) {
      expect(registry.active(tier)).not.toContain('kk');
      expect(registry.active(tier)).not.toContain('ka');
    }
    // Declared, so it can still be named and ordered where a stored row carries it.
    expect(registry.nameOf('kk', 'en')).toBe('Kazakh');
  });

  it('maps Uzbek to the catalog code and back, and reads the bare uz as the tag', async () => {
    const registry = TestBed.inject(PlatformLocales);
    const loaded = registry.ensureLoaded();
    http.expectOne(url(LOCALES_PATH)).flush(ANSWER);
    await loaded;

    expect(registry.catalogCode('uz-Latn')).toBe('uz');
    expect(registry.catalogCode('ru')).toBe('ru');
    expect(registry.tagOfCatalogCode('uz')).toBe('uz-Latn');
    expect(registry.tagOfCatalogCode('en')).toBe('en');
    expect(registry.canonical('uz')).toBe('uz-Latn');
    expect(registry.canonical('UZ-LATN')).toBe('uz-Latn');
    expect(registry.canonical('xx')).toBe('xx');
  });

  it('names a language as its reader sees it, falling back to its own name and then its tag', async () => {
    const registry = TestBed.inject(PlatformLocales);
    const loaded = registry.ensureLoaded();
    http.expectOne(url(LOCALES_PATH)).flush(ANSWER);
    await loaded;

    expect(registry.nameOf('uz-Latn', 'ru')).toBe('Узбекский');
    expect(registry.nameOf('uz-Latn', 'uz-Latn')).toBe('Oʻzbekcha');
    expect(registry.nameOf('kk', 'kk')).toBe('Қазақша');
    expect(registry.nameOf('ka', 'ru')).toBe('Грузинский');
    expect(registry.nameOf('zz', 'en')).toBe('zz');
  });

  it('leaves the console on its catalogues when the registry cannot be read, and never rejects', async () => {
    const registry = TestBed.inject(PlatformLocales);
    const consoleError = console.error;
    console.error = () => undefined;
    try {
      const loaded = registry.ensureLoaded();
      http.expectOne(url(LOCALES_PATH)).flush('nope', { status: 500, statusText: 'Server Error' });
      await expect(loaded).resolves.toBeUndefined();
    } finally {
      console.error = consoleError;
    }

    expect(registry.isLoaded()).toBe(false);
    expect(registry.active('CONTENT')).toEqual([]);
  });

  it('puts the tiers of an entry in fallback order, whatever order the answer came in', () => {
    const shuffled = [...ANSWER.locales].reverse();

    expect(activeTags(shuffled, 'CONTENT')).toEqual(['ru', 'uz-Latn', 'en']);
    expect(entryOf(shuffled, 'uz')?.tag).toBe('uz-Latn');
    expect(entryOf(shuffled, 'nothing')).toBeUndefined();
  });

  it('says what direction the active language is written in on <html dir>, and moves it with the registry', async () => {
    document.documentElement.dir = '';
    const i18n = TestBed.inject(I18n);
    TestBed.inject(DocumentDirection);
    TestBed.tick();
    expect(document.documentElement.dir).toBe('ltr');

    // A right-to-left language is not registered today and registering one needs its own record
    // (ADR 0149, Decision 6); this proves the attribute follows the registry's statement, not a constant.
    const rtlRegistry = {
      ...ANSWER,
      locales: ANSWER.locales.map((entry) =>
        entry.tag === 'en' ? { ...entry, direction: 'RTL' as unknown as 'LTR' } : entry,
      ),
    };
    const registry = TestBed.inject(PlatformLocales);
    const loaded = registry.ensureLoaded();
    http.expectOne(url(LOCALES_PATH)).flush(rtlRegistry);
    await loaded;

    i18n.setLocale('en');
    TestBed.tick();
    expect(registry.directionOf('en')).toBe('rtl');
    expect(document.documentElement.dir).toBe('rtl');

    i18n.setLocale('ru');
    TestBed.tick();
    expect(document.documentElement.dir).toBe('ltr');
  });
});
