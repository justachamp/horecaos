import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LocationServiceStateView, LocationsApi, LocationView } from './locations-api';
import { LocationsPage } from './locations-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const OPEN_LOCATION: LocationView = {
  id: 'location-1',
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  code: 'CHILANZAR',
  slug: 'chilanzar',
  displayName: 'Chilanzar',
  timezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  addressLine: 'Bunyodkor 12',
  district: 'Chilanzar',
  city: 'Tashkent',
  landmark: null,
  contactPhone: '+998712000000',
  latitude: 41.3,
  longitude: 69.2,
  coordinateSource: 'MERCHANT_PIN',
};

const CLOSED_LOCATION: LocationView = {
  ...OPEN_LOCATION,
  id: 'location-2',
  code: 'YUNUSABAD',
  slug: 'yunusabad',
  displayName: 'Yunusabad',
};

const STATES: readonly LocationServiceStateView[] = [
  {
    locationId: 'location-1',
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
  },
  {
    locationId: 'location-2',
    mode: 'FORCE_CLOSED',
    effectiveMode: 'FORCE_CLOSED',
    reasonCode: 'fryer_broken',
    effectiveUntil: null,
  },
];

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('LocationsPage', () => {
  let fixture: ComponentFixture<LocationsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    serviceStates: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
  };

  async function render(overrides: Partial<typeof api> = {}) {
    api = {
      list: vi.fn().mockResolvedValue([OPEN_LOCATION, CLOSED_LOCATION]),
      serviceStates: vi.fn().mockResolvedValue(STATES),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
      ...overrides,
    };
    await TestBed.configureTestingModule({
      imports: [LocationsPage],
      providers: [
        provideRouter([]),
        { provide: LocationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LocationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture;
  }

  it('lists the brand’s locations and each row’s own state, from the batched read', async () => {
    await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilanzar');
    expect(text).toContain('Yunusabad');
    expect(text).toContain('fryer_broken');
    expect(api.serviceStates).toHaveBeenCalledWith(SCOPE);
  });

  it('filters the list to closed branches over the batched state read, without one call per row', async () => {
    await render();

    const select = fixture.nativeElement.querySelector('#state-filter') as HTMLSelectElement;
    select.value = 'FORCE_CLOSED';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Yunusabad');
    expect(text).not.toContain('Chilanzar');
    // One list read and one batched state read for the whole page — never one
    // request per row, which is the bug this wave's own comment named.
    expect(api.serviceStates).toHaveBeenCalledTimes(1);
  });

  it('closes an open branch from the row, with a required reason', async () => {
    await render();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    const openRow = rows[0] as HTMLElement;
    (openRow.querySelector('.actions-cell button') as HTMLButtonElement).click();
    fixture.detectChanges();

    // No reason yet: refused without reaching the API.
    const confirmButton = Array.from(openRow.querySelectorAll('.close-form__actions button')).find(
      (button) => button.textContent?.includes('Close'),
    ) as HTMLButtonElement;
    confirmButton.click();
    await flushMicrotasks();
    expect(api.changeServiceState).not.toHaveBeenCalled();

    const reasonInput = openRow.querySelector('.close-form input') as HTMLInputElement;
    reasonInput.value = 'fryer_broken';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    confirmButton.click();
    await flushMicrotasks();

    expect(api.changeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FORCE_CLOSED',
      reasonCode: 'fryer_broken',
    });
  });

  it('reopens a closed branch from the row with one click', async () => {
    await render();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    const closedRow = rows[1] as HTMLElement;
    (closedRow.querySelector('.actions-cell button') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(api.changeServiceState).toHaveBeenCalledWith(
      { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-2' },
      { mode: 'FOLLOW_SCHEDULE' },
    );
  });
});
