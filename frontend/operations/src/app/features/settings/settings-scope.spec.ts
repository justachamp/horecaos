import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { BrandChoice } from '../../core/auth/brand-choice';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { BrandView } from './brand-profile/brand-profile-api';
import { LocationView } from './locations/locations-api';
import { SettingsScope } from './settings-scope';

const TENANT_ID = 'tenant-1';

const BRANDS: readonly BrandView[] = [
  {
    id: 'brand-1',
    tenantId: TENANT_ID,
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
  },
  {
    id: 'brand-2',
    tenantId: TENANT_ID,
    code: 'YUNUS',
    slug: 'yunusabad',
    displayName: 'Rayhon Yunusabad',
    status: 'ACTIVE',
    contactPhone: null,
    telegramHandle: null,
    logoAssetId: null,
    bannerAssetId: null,
    locales: [],
    version: 0,
  },
];

function location(id: string, brandId: string, displayName: string): LocationView {
  return {
    id,
    tenantId: TENANT_ID,
    brandId,
    code: id,
    slug: id,
    displayName,
    timezone: 'Asia/Tashkent',
    status: 'ACTIVE',
    addressLine: null,
    district: null,
    city: null,
    landmark: null,
    contactPhone: null,
    latitude: null,
    longitude: null,
    coordinateSource: 'NOT_GEOCODED',
    sortOrder: 0,
    seats: null,
    averageChequeAmount: null,
    averageChequeCurrency: null,
    hasParking: false,
    hasPlayground: false,
    virtualTourUrl: null,
    locales: [],
  };
}

const LOCATIONS_FOR_BRAND_1: readonly LocationView[] = [location('loc-1', 'brand-1', 'Chilonzor')];
const LOCATIONS_FOR_BRAND_2: readonly LocationView[] = [location('loc-2', 'brand-2', 'Yunusabad')];

@Component({ selector: 'q-test-host', template: '' })
class TestHostComponent {}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(get: ReturnType<typeof vi.fn>): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'settings', component: TestHostComponent }]),
      {
        provide: CurrentTenant,
        useValue: {
          tenantId: () => TENANT_ID,
          denied: () => false,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: ApiClient, useValue: { get } },
    ],
  });
}

describe('SettingsScope', () => {
  let get: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    // The header's pick is remembered in real storage, which jsdom keeps across tests in a file.
    localStorage.removeItem('horecaos.operations.brandId');
    get = vi.fn((path: string) => {
      if (path.includes('/brands/brand-1/locations')) {
        return of({ value: LOCATIONS_FOR_BRAND_1 });
      }
      if (path.includes('/brands/brand-2/locations')) {
        return of({ value: LOCATIONS_FOR_BRAND_2 });
      }
      return of({ value: BRANDS });
    });
  });

  it('resolves the brand named in ?brand= when this tenant has it', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-2');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.brandId()).toBe('brand-2');
  });

  it('falls back to the first brand and writes it back into the URL when none is named', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.brandId()).toBe('brand-1');
    const router = TestBed.inject(Router);
    expect(router.url).toContain('brand=brand-1');
  });

  it('opens on the brand picked in the shell’s header when the URL names none, not on the first brand (row X.1)', async () => {
    configure(get);
    const shell = TestBed.inject(BrandChoice);
    shell.offer(BRANDS.map((brand) => ({ id: brand.id, displayName: brand.displayName })));
    shell.select('brand-2');
    await RouterTestingHarness.create('/settings');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.brandId()).toBe('brand-2');
    expect(TestBed.inject(Router).url).toContain('brand=brand-2');
  });

  it('lets ?brand= beat the shell’s pick: a pasted link opens what it names', async () => {
    configure(get);
    const shell = TestBed.inject(BrandChoice);
    shell.offer(BRANDS.map((brand) => ({ id: brand.id, displayName: brand.displayName })));
    shell.select('brand-2');
    await RouterTestingHarness.create('/settings?brand=brand-1');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.brandId()).toBe('brand-1');
  });

  describe('a brand picked in the shell’s header while Settings is open', () => {
    /** Settings open on the first brand, as the scope bar left it, with the header offering both. */
    async function openOnBrand1(query: string): Promise<SettingsScope> {
      configure(get);
      TestBed.inject(BrandChoice).offer(
        BRANDS.map((brand) => ({ id: brand.id, displayName: brand.displayName })),
      );
      await RouterTestingHarness.create(`/settings?${query}`);
      const scope = TestBed.inject(SettingsScope);
      await flushMicrotasks();
      return scope;
    }

    it('is the brand the screen reads at once, with the branch of the brand just left dropped', async () => {
      const scope = await openOnBrand1('brand=brand-1&location=loc-1');
      expect(scope.brandId()).toBe('brand-1');
      expect(scope.locationId()).toBe('loc-1');

      // What the shell does on a pick, and then rebuilds the screen, which reads the scope straight away.
      TestBed.inject(BrandChoice).select('brand-2');

      expect(scope.brandId()).toBe('brand-2');
      expect(scope.locationId()).toBeNull();
      expect(scope.level()).toBe('BRAND');
    });

    it('re-points the URL at the picked brand, clearing the branch, and reads that brand’s branches', async () => {
      const scope = await openOnBrand1('brand=brand-1&location=loc-1');

      TestBed.inject(BrandChoice).select('brand-2');
      TestBed.tick();
      await flushMicrotasks();

      const url = TestBed.inject(Router).url;
      expect(url).toContain('brand=brand-2');
      expect(url).not.toContain('location=');
      expect(scope.brandId()).toBe('brand-2');
      expect(scope.locations()).toEqual(LOCATIONS_FOR_BRAND_2);
    });

    it('leaves the company-wide level, as picking a brand in the scope bar does', async () => {
      const scope = await openOnBrand1('brand=brand-1&level=tenant');
      expect(scope.level()).toBe('TENANT');

      TestBed.inject(BrandChoice).select('brand-2');
      TestBed.tick();
      await flushMicrotasks();

      expect(scope.level()).toBe('BRAND');
      expect(TestBed.inject(Router).url).not.toContain('level=');
    });

    it('does not outrank a brand chosen in the scope bar afterwards', async () => {
      const scope = await openOnBrand1('brand=brand-1');
      TestBed.inject(BrandChoice).select('brand-2');
      TestBed.tick();
      await flushMicrotasks();

      scope.setBrand('brand-1');
      await flushMicrotasks();

      expect(scope.brandId()).toBe('brand-1');
      expect(TestBed.inject(Router).url).toContain('brand=brand-1');
    });
  });

  it('ignores a shell pick this tenant’s brand list does not contain', async () => {
    configure(get);
    const shell = TestBed.inject(BrandChoice);
    shell.offer([
      { id: 'gone', displayName: 'Gone' },
      { id: 'brand-1', displayName: 'Rayhon' },
    ]);
    shell.select('gone');
    await RouterTestingHarness.create('/settings');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.brandId()).toBe('brand-1');
  });

  it('reads no ?location= as "Все филиалы" — editing at BRAND level', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.locationId()).toBeNull();
    expect(scope.level()).toBe('BRAND');
  });

  it('resolves a ?location= that belongs to the current brand, editing at LOCATION level', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&location=loc-1');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.locationId()).toBe('loc-1');
    expect(scope.level()).toBe('LOCATION');
  });

  it('drops a ?location= that does not belong to the resolved brand', async () => {
    configure(get);
    // loc-2 belongs to brand-2, not the brand named in the query string.
    await RouterTestingHarness.create('/settings?brand=brand-1&location=loc-2');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.locationId()).toBeNull();
  });

  it('setBrand clears the location and writes both into the query string', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&location=loc-1');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    scope.setBrand('brand-2');
    await flushMicrotasks();

    const router = TestBed.inject(Router);
    expect(router.url).toContain('brand=brand-2');
    expect(router.url).not.toContain('location=loc-1');
    expect(scope.locationId()).toBeNull();
  });

  it('setLocation(null) returns to "Все филиалы"', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&location=loc-1');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    scope.setLocation(null);
    await flushMicrotasks();

    expect(scope.locationId()).toBeNull();
    const router = TestBed.inject(Router);
    expect(router.url).not.toContain('location=loc-1');
  });

  it('hides the brand picker signal when the tenant has exactly one brand', async () => {
    get = vi.fn(() => of({ value: [BRANDS[0]] }));
    configure(get);
    await RouterTestingHarness.create('/settings');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.showBrandPicker()).toBe(false);
  });

  // Row 10.3b: TENANT is a third level, orthogonal to brand/location.

  it('reads ?level=tenant as TENANT level, regardless of the brand/location also named', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&location=loc-1&level=tenant');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    expect(scope.level()).toBe('TENANT');
    expect(scope.tenantWide()).toBe(true);
    // The brand/location pickers keep their own resolved values as display
    // context even while TENANT wins the level.
    expect(scope.brandId()).toBe('brand-1');
    expect(scope.locationId()).toBe('loc-1');
  });

  it('setTenantLevel switches to TENANT without disturbing the brand/location query params', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&location=loc-1');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    scope.setTenantLevel();
    await flushMicrotasks();

    expect(scope.level()).toBe('TENANT');
    const router = TestBed.inject(Router);
    expect(router.url).toContain('level=tenant');
    expect(router.url).toContain('brand=brand-1');
    expect(router.url).toContain('location=loc-1');
  });

  it('leaveTenantLevel returns to whatever the brand/location pickers already show', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&location=loc-1&level=tenant');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    scope.leaveTenantLevel();
    await flushMicrotasks();

    expect(scope.level()).toBe('LOCATION');
    const router = TestBed.inject(Router);
    expect(router.url).not.toContain('level=');
  });

  it('setBrand leaves TENANT level — picking a brand is picking a narrower scope', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&level=tenant');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    scope.setBrand('brand-2');
    await flushMicrotasks();

    expect(scope.level()).toBe('BRAND');
  });

  it('setLocation leaves TENANT level the same way', async () => {
    configure(get);
    await RouterTestingHarness.create('/settings?brand=brand-1&level=tenant');
    const scope = TestBed.inject(SettingsScope);
    await flushMicrotasks();

    scope.setLocation('loc-1');
    await flushMicrotasks();

    expect(scope.level()).toBe('LOCATION');
  });
});
