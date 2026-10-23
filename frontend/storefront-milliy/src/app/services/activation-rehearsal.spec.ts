import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { REGISTRY_FIXTURE } from '../../testing/platform-locales.fixture';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { BUILD_CATALOGUES, LangService, langIdOf, platformTag } from './lang.service';
import { PlatformLocaleEntry, adoptPlatformLocales } from './platform-locales.service';
import { StorageService } from './storage.service';

/**
 * ADR 0149's exit criterion on the storefront side, rehearsed: "declaring `kk` active in the registry,
 * adding a Kazakh catalogue and a brand's supported set makes that brand's storefront ... speak Kazakh,
 * with no migration and no change to any module other than its catalogue".
 *
 * No language is activated, so the claim has never been seen to hold here. This declares a synthetic
 * fifth language live in the content tier, gives the build a catalogue for it (the one thing a client
 * must add) and lets the brand serve it, then checks that the picker, the labels, the platform tags
 * and `<html lang dir>` follow without a line of the app being touched. The backend's
 * `ActivationRehearsalTests` rehearses the other half, against the real schema.
 */
describe('activating a fifth language (ADR 0149 exit criteria)', () => {
  const FIFTH: PlatformLocaleEntry = {
    tag: 'zz',
    catalogCode: 'zz',
    inputAliases: [],
    script: 'LATN',
    direction: 'LTR',
    fallbackRank: 5,
    tiers: ['CONTENT', 'MESSAGES'],
    names: { ru: 'Пятый', en: 'Fifth', zz: 'Zzzz' },
  };

  let catalogues: readonly string[];
  const get = vi.fn();

  function configure(brandServes: readonly string[], brandDefault: string): LangService {
    get.mockImplementation((path: string) =>
      Promise.resolve(
        path === '/storefront/locales'
          ? { locales: [...REGISTRY_FIXTURE, FIFTH], fallback: 'ru' }
          : { locales: brandServes, defaultLocale: brandDefault, configured: true },
      ),
    );
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: { get } },
        { provide: APP_CONFIG, useValue: { tenantId: 'tenant-1', brandId: 'brand-1' } },
        {
          provide: StorageService,
          useValue: { getItem: () => Promise.resolve(null), setItem: vi.fn() },
        },
        { provide: BUILD_CATALOGUES, useValue: catalogues },
      ],
    });
    return TestBed.inject(LangService);
  }

  beforeEach(() => {
    catalogues = ['uz', 'ru', 'en', 'zz'];
    get.mockReset();
    adoptPlatformLocales([]);
  });

  afterEach(() => adoptPlatformLocales(REGISTRY_FIXTURE));

  it('is offered, named and tagged once the registry has it live, the build has its catalogue and the brand serves it', async () => {
    const lang = configure(['zz', 'ru'], 'zz');

    await lang.load();

    expect(lang.offered().map((option) => option.id)).toEqual(['zz', 'ru']);
    expect(lang.labelOf('zz')).toBe('Zzzz');
    expect(lang.langId()).toBe('zz');
    expect(platformTag('zz')).toBe('zz');
    expect(langIdOf('zz')).toBe('zz');
  });

  it('writes its tag and direction onto <html> with nothing else changed', async () => {
    const lang = configure(['zz', 'ru'], 'zz');

    await lang.load();
    TestBed.tick();

    const root = TestBed.inject(DOCUMENT).documentElement;
    expect(root.lang).toBe('zz');
    expect(root.dir).toBe('ltr');
  });

  it('is not offered by a build that has no catalogue for it, however live the registry says it is', async () => {
    catalogues = ['uz', 'ru', 'en'];
    const lang = configure(['zz', 'ru'], 'zz');

    await lang.load();

    expect(lang.offered().map((option) => option.id)).toEqual(['ru']);
    expect(lang.langId()).toBe('ru');
  });

  it('is not offered by a brand that does not serve it, though the registry and the build both have it', async () => {
    const lang = configure(['uz-Latn', 'ru'], 'uz-Latn');

    await lang.load();

    expect(lang.offered().map((option) => option.id)).toEqual(['uz', 'ru']);
  });
});
