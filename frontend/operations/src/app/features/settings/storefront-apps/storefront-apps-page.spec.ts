import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { BrandProfileApi, BrandView } from '../brand-profile/brand-profile-api';
import { CatalogueEntry, StorefrontAppsApi } from './storefront-apps-api';
import { StorefrontAppsPage } from './storefront-apps-page';

const BRAND: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'RAYHON',
  slug: 'rayhon',
  displayName: 'Rayhon',
  status: 'ACTIVE',
  contactPhone: null,
  telegramHandle: null,
  logoAssetId: null,
  bannerAssetId: null,
  locales: [],
  version: 0,
};

const BRAND_2: BrandView = { ...BRAND, id: 'brand-2', code: 'OSHXONA', displayName: 'Oshxona' };

function entry(overrides: Partial<CatalogueEntry> = {}): CatalogueEntry {
  return {
    appId: 'app-1',
    name: 'Tandir Shop',
    vendor: 'Acme Web',
    clientType: 'PUBLIC',
    firstParty: false,
    appStatus: 'ACTIVE',
    conformance: { status: 'NOT_RUN', contractVersion: null, recordedAt: null, note: null },
    standing: 'NOT_AUTHORISED',
    grantedAt: null,
    grantedBy: null,
    revokedAt: null,
    authorisationVersion: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('tenant-1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function render(
  brandsApi: Partial<BrandProfileApi>,
  appsApi: Partial<StorefrontAppsApi>,
  tenant: FakeCurrentTenant = new FakeCurrentTenant(),
): Promise<ComponentFixture<StorefrontAppsPage>> {
  await TestBed.configureTestingModule({
    imports: [StorefrontAppsPage],
    providers: [
      provideRouter([]),
      { provide: BrandProfileApi, useValue: brandsApi },
      { provide: StorefrontAppsApi, useValue: appsApi },
      { provide: CurrentTenant, useValue: tenant },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(StorefrontAppsPage);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

function el<T extends HTMLElement>(fixture: ComponentFixture<unknown>, selector: string): T {
  return (fixture.nativeElement as HTMLElement).querySelector(selector) as T;
}

async function type(
  fixture: ComponentFixture<unknown>,
  selector: string,
  value: string,
): Promise<void> {
  const input = el<HTMLInputElement>(fixture, selector);
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
}

async function click(fixture: ComponentFixture<unknown>, selector: string): Promise<void> {
  el<HTMLButtonElement>(fixture, selector).click();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
}

describe('StorefrontAppsPage', () => {
  it('lists every app with what this brand has decided, and says what a browser-only app can and cannot be', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        catalogue: vi.fn().mockResolvedValue([
          entry(),
          entry({
            appId: 'app-2',
            name: 'Kitchen Cloud',
            clientType: 'CONFIDENTIAL',
            standing: 'AUTHORISED',
            authorisationVersion: 4,
          }),
        ]),
      },
    );

    const first = el(fixture, '[data-app="app-1"]');
    expect(first.getAttribute('data-standing')).toBe('NOT_AUTHORISED');
    expect(first.textContent).toContain('Not authorised');
    expect(first.textContent).toContain('cannot prove who is behind it');
    expect(first.textContent).not.toContain('secure');

    const second = el(fixture, '[data-app="app-2"]');
    expect(second.getAttribute('data-standing')).toBe('AUTHORISED');
    expect(second.textContent).toContain('proves its identity with a secret');
  });

  it('authorises an app only with a reason', async () => {
    const authorise = vi.fn().mockResolvedValue({});
    const catalogue = vi
      .fn()
      .mockResolvedValueOnce([entry()])
      .mockResolvedValue([entry({ standing: 'AUTHORISED', authorisationVersion: 0 })]);
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { catalogue, authorise },
    );

    await click(fixture, '[data-app="app-1"] .authorise');
    expect(el<HTMLButtonElement>(fixture, '.confirm-action').disabled).toBe(true);

    await type(fixture, '[data-app="app-1"] input[name="reason"]', 'we chose Tandir Shop');
    expect(el<HTMLButtonElement>(fixture, '.confirm-action').disabled).toBe(false);
    await click(fixture, '.confirm-action');

    expect(authorise).toHaveBeenCalledWith('tenant-1', 'brand-1', 'app-1', 'we chose Tandir Shop');
    expect(el(fixture, '[data-app="app-1"]').getAttribute('data-standing')).toBe('AUTHORISED');
    expect(el(fixture, '[data-testid="storefront-apps-notice"]').textContent).toContain(
      'Tandir Shop is authorised',
    );
  });

  it('revokes against the version it read, and shows the app as revoked afterwards', async () => {
    const revoke = vi.fn().mockResolvedValue({});
    const catalogue = vi
      .fn()
      .mockResolvedValueOnce([entry({ standing: 'AUTHORISED', authorisationVersion: 7 })])
      .mockResolvedValue([entry({ standing: 'REVOKED', authorisationVersion: 8 })]);
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { catalogue, revoke },
    );

    await click(fixture, '[data-app="app-1"] .revoke');
    await type(fixture, '[data-app="app-1"] input[name="reason"]', 'moving to another storefront');
    await click(fixture, '.confirm-action');

    expect(revoke).toHaveBeenCalledWith(
      'tenant-1',
      'brand-1',
      'app-1',
      7,
      'moving to another storefront',
    );
    expect(el(fixture, '[data-app="app-1"]').getAttribute('data-standing')).toBe('REVOKED');
    expect(el(fixture, '[data-app="app-1"] .authorise')).toBeTruthy();
  });

  it('offers no way to authorise an app HorecaOS has paused', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { catalogue: vi.fn().mockResolvedValue([entry({ appStatus: 'SUSPENDED' })]) },
    );

    expect(el(fixture, '[data-app="app-1"] .authorise')).toBeNull();
    expect(el(fixture, '[data-testid="app-not-active"]')).toBeTruthy();
  });

  it('says an expired conformance pass is expired', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        catalogue: vi.fn().mockResolvedValue([
          entry({
            conformance: {
              status: 'EXPIRED',
              contractVersion: 'v0',
              recordedAt: '2026-09-01T00:00:00Z',
              note: null,
            },
          }),
        ]),
      },
    );

    expect(el(fixture, '[data-app="app-1"]').textContent).toContain('expired');
  });

  it('shows what is true now when the authorisation changed under the operator', async () => {
    const conflict = new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'c-1');
    const revoke = vi.fn().mockRejectedValue(conflict);
    const catalogue = vi
      .fn()
      .mockResolvedValueOnce([entry({ standing: 'AUTHORISED', authorisationVersion: 1 })])
      .mockResolvedValue([entry({ standing: 'REVOKED', authorisationVersion: 2 })]);
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { catalogue, revoke },
    );

    await click(fixture, '[data-app="app-1"] .revoke');
    await type(fixture, '[data-app="app-1"] input[name="reason"]', 'a reason');
    await click(fixture, '.confirm-action');

    expect(catalogue).toHaveBeenCalledTimes(2);
    expect(el(fixture, '[data-app="app-1"]').getAttribute('data-standing')).toBe('REVOKED');
  });

  it('has a brand picker only with more than one brand, and clears the list while the next brand loads', async () => {
    const catalogue = vi
      .fn()
      .mockResolvedValueOnce([entry()])
      .mockResolvedValue([entry({ standing: 'AUTHORISED', authorisationVersion: 3 })]);
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND, BRAND_2]) },
      { catalogue },
    );

    const picker = el<HTMLSelectElement>(fixture, '#storefront-apps-brand');
    expect(picker.querySelectorAll('option').length).toBe(2);

    picker.value = 'brand-2';
    picker.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(catalogue).toHaveBeenLastCalledWith('tenant-1', 'brand-2');
    expect(el(fixture, '[data-app="app-1"]').getAttribute('data-standing')).toBe('AUTHORISED');
  });

  it('does not render a brand picker with only one brand', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { catalogue: vi.fn().mockResolvedValue([entry()]) },
    );

    expect(el(fixture, '#storefront-apps-brand')).toBeNull();
  });

  it('shows the denied state to somebody who may not choose a storefront', async () => {
    const forbidden = new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, 'c-2');
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { catalogue: vi.fn().mockRejectedValue(forbidden) },
    );

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      "Only the account's owner and administrators can choose storefront apps.",
    );
  });
});
