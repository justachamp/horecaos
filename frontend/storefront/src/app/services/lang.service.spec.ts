import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { REGISTRY_FIXTURE } from '../../testing/platform-locales.fixture';
import { BUILD_CATALOGUES, LangService, langIdOf, platformTag } from './lang.service';
import {
  BrandLocales,
  PlatformLocaleEntry,
  adoptPlatformLocales,
} from './platform-locales.service';
import { StorageService } from './storage.service';

/**
 * The one mapping the storefront keeps between its catalogue ids and the platform's language tags
 * (ADR 0149): the registry's, read. Uzbek differs between the two spellings only because the
 * registry says so, and a registry that said otherwise would be followed.
 */
describe('platformTag', () => {
  afterEach(() => adoptPlatformLocales(REGISTRY_FIXTURE));

  it('says Uzbek the way the platform does, with its script', () => {
    expect(platformTag('uz')).toBe('uz-Latn');
  });

  it('leaves a language whose id is already its tag alone', () => {
    expect(platformTag('ru')).toBe('ru');
    expect(platformTag('en')).toBe('en');
  });

  it("reads a platform tag back as this app's id, and a tag it has no id for as it was", () => {
    expect(langIdOf('uz-Latn')).toBe('uz');
    expect(langIdOf('uz')).toBe('uz');
    expect(langIdOf('ru')).toBe('ru');
    expect(langIdOf('kk')).toBe('kk');
  });

  it('round-trips every id this app has a catalogue for', () => {
    for (const id of BUILD_CATALOGUES) {
      expect(langIdOf(platformTag(id))).toBe(id);
    }
  });

  it('follows the registry and not a table of its own: change the registry’s code and the mapping moves', () => {
    adoptPlatformLocales(
      REGISTRY_FIXTURE.map((entry) =>
        entry.tag === 'uz-Latn' ? { ...entry, catalogCode: 'uzb' } : entry,
      ),
    );

    expect(platformTag('uzb')).toBe('uz-Latn');
    expect(platformTag('uz')).toBe('uz');
    expect(langIdOf('uz-Latn')).toBe('uzb');
  });

  it('is the id itself while the registry has not been read', () => {
    adoptPlatformLocales([]);

    expect(platformTag('uz')).toBe('uz');
    expect(langIdOf('uz-Latn')).toBe('uz-Latn');
  });
});

describe('LangService (ADR 0149, Decision 5 and 6)', () => {
  let stored: string | null;
  let brand: BrandLocales | Error;
  let registry: readonly PlatformLocaleEntry[] | Error;
  const get = vi.fn();

  function configure(): LangService {
    get.mockImplementation((path: string) => {
      const answer = path === '/storefront/locales' ? registry : brand;
      return answer instanceof Error
        ? Promise.reject(answer)
        : Promise.resolve(
            path === '/storefront/locales' ? { locales: answer, fallback: 'ru' } : answer,
          );
    });
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: { get } },
        {
          provide: APP_CONFIG,
          useValue: { tenantId: 'tenant-1', brandId: 'brand-1' },
        },
        {
          provide: StorageService,
          useValue: {
            getItem: vi.fn().mockImplementation(() => Promise.resolve(stored)),
            setItem: vi.fn(),
          },
        },
      ],
    });
    return TestBed.inject(LangService);
  }

  beforeEach(() => {
    stored = null;
    registry = REGISTRY_FIXTURE;
    brand = { locales: ['uz-Latn', 'ru', 'en'], defaultLocale: 'uz-Latn', configured: true };
    get.mockReset();
    // The service starts from an unread registry, as the app does: the read is what is under test.
    adoptPlatformLocales([]);
  });

  afterEach(() => adoptPlatformLocales(REGISTRY_FIXTURE));

  it('opens in the language the brand defaults to, not a hard-coded uz', async () => {
    brand = { locales: ['ru', 'uz-Latn'], defaultLocale: 'ru', configured: true };
    const lang = configure();

    await lang.load();

    expect(lang.langId()).toBe('ru');
  });

  it('asks the platform for the registry and for this brand’s set, both without a session', async () => {
    const lang = configure();

    await lang.load();

    expect(get).toHaveBeenCalledWith('/storefront/locales', { anonymous: true, quiet: true });
    expect(get).toHaveBeenCalledWith('/storefront/tenants/tenant-1/brands/brand-1/locales', {
      anonymous: true,
      quiet: true,
    });
  });

  it('offers exactly the brand’s languages that are live and that this build can draw, its default first', async () => {
    // kk is in the brand's set but declared, not live: it must not be offered.
    brand = { locales: ['en', 'kk', 'uz-Latn'], defaultLocale: 'en', configured: true };
    const lang = configure();

    await lang.load();

    expect(lang.offered().map((option) => option.id)).toEqual(['en', 'uz']);
    expect(lang.offered().map((option) => option.label)).toEqual(['English', 'Oʻzbekcha']);
    expect(lang.labelOf('uz')).toBe('Oʻzbekcha');
  });

  it('offers every live language the build can draw to a brand that chose none, in the registry’s order', async () => {
    brand = { locales: ['ru', 'uz-Latn', 'en'], defaultLocale: 'ru', configured: false };
    const lang = configure();

    await lang.load();

    expect(lang.offered().map((option) => option.id)).toEqual(['ru', 'uz', 'en']);
  });

  it('keeps a customer’s stored language when the brand still serves it, and ignores one it no longer serves', async () => {
    brand = { locales: ['ru', 'uz-Latn'], defaultLocale: 'ru', configured: true };
    stored = 'uz';
    const kept = configure();
    await kept.load();
    expect(kept.langId()).toBe('uz');

    TestBed.resetTestingModule();
    adoptPlatformLocales([]);
    get.mockReset();
    stored = 'en';
    const dropped = configure();
    await dropped.load();
    expect(dropped.langId()).toBe('ru');
  });

  it('does not take a language a customer just chose back from them when the stored copy is unreadable', async () => {
    const lang = configure();
    await lang.load();

    lang.setLang('en');
    stored = null;
    await lang.load();

    expect(lang.langId()).toBe('en');
  });

  it('stays in the language it opened in when the brand’s set cannot be read, offering what the registry has live', async () => {
    brand = new Error('offline');
    const lang = configure();

    await lang.load();

    expect(lang.langId()).toBe('uz');
    expect(lang.offered().map((option) => option.id)).toEqual(['ru', 'uz', 'en']);
  });

  it('falls back to the build’s own catalogues, named by their ids, when the registry cannot be read', async () => {
    registry = new Error('offline');
    const lang = configure();

    await lang.load();

    expect(lang.offered().map((option) => option.id)).toEqual([...BUILD_CATALOGUES]);
    expect(lang.langId()).toBe('uz');
  });

  it('writes the active language and its direction onto <html>, and follows a change', async () => {
    brand = { locales: ['ru', 'uz-Latn'], defaultLocale: 'ru', configured: true };
    const lang = configure();
    await lang.load();
    TestBed.tick();
    const root = TestBed.inject(DOCUMENT).documentElement;

    expect(root.lang).toBe('ru');
    expect(root.dir).toBe('ltr');

    lang.setLang('uz');
    TestBed.tick();

    expect(root.lang).toBe('uz-Latn');
  });
});
