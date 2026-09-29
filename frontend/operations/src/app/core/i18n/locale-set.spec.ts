import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { CurrentBrand } from '../auth/current-brand';
import { SessionContext } from '../auth/session-context';
import { LocaleSet, resolveLocaleSet } from './locale-set';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

function context(scopes: SessionContext['scopes']): SessionContext {
  return { subject: 'operator-1', activeTenantId: 't1', scopes };
}

/** See `current-brand.spec.ts`'s identical helper: waits past the promise hops between the two dependent HTTP calls. */
function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('LocaleSet', () => {
  let http: HttpTestingController;
  let localeSet: LocaleSet;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ApiClient,
        CurrentBrand,
        LocaleSet,
      ],
    });
    localeSet = TestBed.inject(LocaleSet);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('falls back to the platform triple, ru default, before load settles', () => {
    expect(localeSet.locales()).toEqual(['ru', 'uz-Latn', 'en']);
    expect(localeSet.defaultLocale()).toBe('ru');
    expect(localeSet.isConfigured()).toBe(false);
  });

  it('reads the brand-scoped grant, default first, once the brand profile answers', async () => {
    const promise = localeSet.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
          roleCode: 'MANAGER',
        },
      ]),
    );
    await tick();

    http.expectOne(url('/api/v1/operations/tenants/t1/brands/b1')).flush({
      id: 'b1',
      tenantId: 't1',
      code: 'MAIN',
      slug: 'main',
      displayName: 'Rayhon',
      status: 'ACTIVE',
      contactPhone: null,
      telegramHandle: null,
      logoAssetId: null,
      bannerAssetId: null,
      locales: [
        { locale: 'en', description: null, isDefault: false },
        { locale: 'uz-Latn', description: null, isDefault: true },
      ],
      version: 3,
    });
    await promise;

    expect(localeSet.locales()).toEqual(['uz-Latn', 'en']);
    expect(localeSet.defaultLocale()).toBe('uz-Latn');
    expect(localeSet.isConfigured()).toBe(true);
    expect(localeSet.supports('uz-Latn')).toBe(true);
    expect(localeSet.supports('ru')).toBe(false);
  });

  it('falls back to the platform triple when the brand has configured no locales yet', async () => {
    const promise = localeSet.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
          roleCode: 'MANAGER',
        },
      ]),
    );
    await tick();

    http.expectOne(url('/api/v1/operations/tenants/t1/brands/b1')).flush({
      id: 'b1',
      tenantId: 't1',
      code: 'MAIN',
      slug: 'main',
      displayName: 'Rayhon',
      status: 'ACTIVE',
      contactPhone: null,
      telegramHandle: null,
      logoAssetId: null,
      bannerAssetId: null,
      locales: [],
      version: 0,
    });
    await promise;

    expect(localeSet.locales()).toEqual(['ru', 'uz-Latn', 'en']);
    expect(localeSet.defaultLocale()).toBe('ru');
    expect(localeSet.isConfigured()).toBe(false);
  });

  it('falls back to the platform triple when no brand scope resolves at all', async () => {
    const promise = localeSet.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(context([]));
    await promise;

    expect(localeSet.locales()).toEqual(['ru', 'uz-Latn', 'en']);
    // The session-context call above is CurrentBrand's own; a denied brand
    // scope must never reach for a brand-profile call that has nowhere to
    // point.
  });

  it('falls back to the platform triple when the brand-profile call itself fails', async () => {
    const promise = localeSet.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
          roleCode: 'MANAGER',
        },
      ]),
    );
    await tick();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1'))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await promise;

    expect(localeSet.locales()).toEqual(['ru', 'uz-Latn', 'en']);
    expect(localeSet.isConfigured()).toBe(false);
  });

  it('fetches the brand profile exactly once no matter how many callers await it', async () => {
    const first = localeSet.ensureLoaded();
    const second = localeSet.ensureLoaded();

    http.expectOne(url('/api/v1/session/context')).flush(context([]));
    await Promise.all([first, second]);

    await localeSet.ensureLoaded();
  });
});

describe('resolveLocaleSet', () => {
  it('puts the default first and the rest in the platform order', () => {
    const resolved = resolveLocaleSet([
      { locale: 'en', isDefault: false },
      { locale: 'ru', isDefault: false },
      { locale: 'uz-Latn', isDefault: true },
    ]);

    expect(resolved.locales).toEqual(['uz-Latn', 'ru', 'en']);
    expect(resolved.defaultLocale).toBe('uz-Latn');
    expect(resolved.isConfigured).toBe(true);
  });

  it.each([[[]], [null], [undefined]])(
    'is the platform triple, ru default, for an unconfigured brand (%j)',
    (configured) => {
      const resolved = resolveLocaleSet(configured);

      expect(resolved.locales).toEqual(['ru', 'uz-Latn', 'en']);
      expect(resolved.defaultLocale).toBe('ru');
      expect(resolved.isConfigured).toBe(false);
    },
  );

  it('does not mutate the list it is given', () => {
    const configured = [
      { locale: 'en' as const, isDefault: false },
      { locale: 'ru' as const, isDefault: true },
    ];
    resolveLocaleSet(configured);

    expect(configured.map((option) => option.locale)).toEqual(['en', 'ru']);
  });
});
