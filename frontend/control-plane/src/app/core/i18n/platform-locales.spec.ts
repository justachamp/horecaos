import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { REGISTRY_FIXTURE } from '../../../testing/platform-locales.fixture';
import { APP_CONFIG } from '../config/app-config';
import { DocumentDirection } from './document-direction';
import { I18nService } from './i18n.service';
import {
  LOCALES_PATH,
  PlatformLocales,
  activeTags,
  entryOf,
  seedPlatformLocalesForTesting,
} from './platform-locales';

const BASE = 'https://api.test.horecaos.uz';

describe('PlatformLocales (control plane)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    // These specs prove the state before the registry has been read, so they start from nothing;
    // everything else in the suite starts from the seeded answer (src/testing/i18n-preload.setup.ts).
    seedPlatformLocalesForTesting(null);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: { apiBaseUrl: BASE, displayTimeZone: 'Asia/Tashkent' } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    seedPlatformLocalesForTesting(REGISTRY_FIXTURE);
  });

  it('offers nothing before the registry has been read', () => {
    const registry = TestBed.inject(PlatformLocales);

    expect(registry.isLoaded()).toBe(false);
    expect(registry.active('MESSAGES')).toEqual([]);
    expect(registry.fallback()).toBe('ru');
    expect(registry.canonical('uz')).toBe('uz');
  });

  it('reads the registry once, from the control-plane surface, and answers from what it read', async () => {
    const registry = TestBed.inject(PlatformLocales);

    const first = registry.ensureLoaded();
    const second = registry.ensureLoaded();
    http.expectOne(`${BASE}${LOCALES_PATH}`).flush(REGISTRY_FIXTURE);
    await Promise.all([first, second]);

    expect(registry.active('MESSAGES')).toEqual(['ru', 'uz-Latn', 'en']);
    expect(registry.active('STAFF_UI')).toEqual(['ru', 'uz-Latn', 'en']);
  });

  it('does not offer a language that is declared and not live, and reads the bare uz as the tag', async () => {
    const registry = TestBed.inject(PlatformLocales);
    const loaded = registry.ensureLoaded();
    http.expectOne(`${BASE}${LOCALES_PATH}`).flush(REGISTRY_FIXTURE);
    await loaded;

    expect(registry.active('MESSAGES')).not.toContain('kk');
    expect(registry.canonical('uz')).toBe('uz-Latn');
    expect(registry.nameOf('uz-Latn', 'ru')).toBe('Узбекский');
    expect(registry.nameOf('kk', 'en')).toBe('Kazakh');
  });

  it('leaves the console on its catalogues when the registry cannot be read, and never rejects', async () => {
    const registry = TestBed.inject(PlatformLocales);
    const consoleError = console.error;
    console.error = () => undefined;
    try {
      const loaded = registry.ensureLoaded();
      http
        .expectOne(`${BASE}${LOCALES_PATH}`)
        .flush('nope', { status: 500, statusText: 'Server Error' });
      await expect(loaded).resolves.toBeUndefined();
    } finally {
      console.error = consoleError;
    }

    expect(registry.isLoaded()).toBe(false);
  });

  it('orders tiers by the registry rank whatever order the answer came in', () => {
    const shuffled = [...REGISTRY_FIXTURE.locales].reverse();

    expect(activeTags(shuffled, 'CONTENT')).toEqual(['ru', 'uz-Latn', 'en']);
    expect(entryOf(shuffled, 'UZ')?.tag).toBe('uz-Latn');
  });

  it('says what direction the active language is written in on <html dir>, and follows the registry', async () => {
    document.documentElement.dir = '';
    TestBed.inject(DocumentDirection);
    TestBed.tick();
    expect(document.documentElement.dir).toBe('ltr');

    const registry = TestBed.inject(PlatformLocales);
    const loaded = registry.ensureLoaded();
    http.expectOne(`${BASE}${LOCALES_PATH}`).flush({
      ...REGISTRY_FIXTURE,
      locales: REGISTRY_FIXTURE.locales.map((entry) =>
        entry.tag === 'en' ? { ...entry, direction: 'RTL' as unknown as 'LTR' } : entry,
      ),
    });
    await loaded;

    const i18n = TestBed.inject(I18nService);
    i18n.use('en');
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    TestBed.tick();
    expect(document.documentElement.dir).toBe('rtl');

    i18n.use('ru');
    TestBed.tick();
    expect(document.documentElement.dir).toBe('ltr');
  });
});
