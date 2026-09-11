import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
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
  },
  {
    id: 'brand-2',
    tenantId: TENANT_ID,
    code: 'YUNUS',
    slug: 'yunusabad',
    displayName: 'Rayhon Yunusabad',
    status: 'ACTIVE',
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
});
