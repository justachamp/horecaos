import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import {
  BulkServiceStateResponse,
  LocationChannelsView,
  LocationLegalEntityView,
  LocationServiceStateView,
  LocationsApi,
  LocationView,
} from './locations-api';
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
  sortOrder: 0,
  seats: null,
  averageChequeAmount: null,
  averageChequeCurrency: null,
  hasParking: false,
  hasPlayground: false,
  virtualTourUrl: null,
  locales: [],
};

const CLOSED_LOCATION: LocationView = {
  ...OPEN_LOCATION,
  id: 'location-2',
  code: 'YUNUSABAD',
  slug: 'yunusabad',
  displayName: 'Yunusabad',
};

const THIRD_LOCATION: LocationView = {
  ...OPEN_LOCATION,
  id: 'location-3',
  code: 'SERGELI',
  slug: 'sergeli',
  displayName: 'Sergeli',
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
  {
    locationId: 'location-3',
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
  },
];

const LEGAL_ENTITIES: readonly LocationLegalEntityView[] = [
  { locationId: 'location-1', legalEntityCode: 'ACME', taxpayerNumber: '111111111' },
  { locationId: 'location-2', legalEntityCode: 'BETA', taxpayerNumber: '222222222' },
];

const CHANNELS: readonly LocationChannelsView[] = [
  { locationId: 'location-1', channelCodes: ['STOREFRONT'] },
  { locationId: 'location-2', channelCodes: ['STOREFRONT', 'YANDEX'] },
  { locationId: 'location-3', channelCodes: ['YANDEX'] },
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

function rows(fixture: ComponentFixture<LocationsPage>): readonly HTMLElement[] {
  return Array.from(fixture.nativeElement.querySelectorAll('tbody tr'));
}

/** The row whose text names this branch — robust to the severity sort reordering rows. */
function rowFor(fixture: ComponentFixture<LocationsPage>, displayName: string): HTMLElement {
  const row = rows(fixture).find((candidate) => candidate.textContent?.includes(displayName));
  if (!row) {
    throw new Error(`No row found for ${displayName}`);
  }
  return row;
}

describe('LocationsPage', () => {
  let fixture: ComponentFixture<LocationsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    serviceStates: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
    legalEntities: ReturnType<typeof vi.fn>;
    channels: ReturnType<typeof vi.fn>;
    bulkChangeServiceState: ReturnType<typeof vi.fn>;
  };

  async function render(overrides: Partial<typeof api> = {}) {
    api = {
      list: vi.fn().mockResolvedValue([OPEN_LOCATION, CLOSED_LOCATION, THIRD_LOCATION]),
      serviceStates: vi.fn().mockResolvedValue(STATES),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
      legalEntities: vi.fn().mockResolvedValue(LEGAL_ENTITIES),
      channels: vi.fn().mockResolvedValue(CHANNELS),
      bulkChangeServiceState: vi.fn().mockResolvedValue({
        requestedCount: 0,
        appliedCount: 0,
        failedCount: 0,
        items: [],
      } satisfies BulkServiceStateResponse),
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

    const openRow = rowFor(fixture, 'Chilanzar');
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

    const closedRow = rowFor(fixture, 'Yunusabad');
    (closedRow.querySelector('.actions-cell button') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(api.changeServiceState).toHaveBeenCalledWith(
      { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-2' },
      { mode: 'FOLLOW_SCHEDULE' },
    );
  });

  it('sorts forced-closed branches first, regardless of load order (severity sort)', async () => {
    await render();

    const names = rows(fixture).map((row) => row.textContent ?? '');
    // Yunusabad (FORCE_CLOSED) loaded second but must render first; the two
    // FOLLOW_SCHEDULE branches keep their original relative order after it.
    expect(names[0]).toContain('Yunusabad');
    expect(names.findIndex((text) => text.includes('Chilanzar'))).toBeLessThan(
      names.findIndex((text) => text.includes('Sergeli')),
    );
  });

  it('filters the list by channel over the batched channel read, without one call per row', async () => {
    await render();

    const select = fixture.nativeElement.querySelector('#channel-filter') as HTMLSelectElement;
    select.value = 'YANDEX';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Yunusabad');
    expect(text).toContain('Sergeli');
    expect(text).not.toContain('Chilanzar');
    expect(api.channels).toHaveBeenCalledWith(SCOPE);
    expect(api.channels).toHaveBeenCalledTimes(1);
  });

  it('filters the list by legal entity (INN) over the batched read, without one call per row', async () => {
    await render();

    const select = fixture.nativeElement.querySelector('#legal-entity-filter') as HTMLSelectElement;
    select.value = 'ACME';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilanzar');
    expect(text).not.toContain('Yunusabad');
    expect(text).not.toContain('Sergeli');
    expect(api.legalEntities).toHaveBeenCalledWith(SCOPE);
    expect(api.legalEntities).toHaveBeenCalledTimes(1);
  });

  it('bulk-reopens every selected branch in one capability-gated, idempotent call, reporting a per-branch outcome', async () => {
    await render({
      bulkChangeServiceState: vi.fn().mockResolvedValue({
        requestedCount: 2,
        appliedCount: 1,
        failedCount: 1,
        items: [
          { locationId: 'location-1', applied: true, problemCode: null },
          { locationId: 'location-3', applied: false, problemCode: 'VALIDATION_FAILED' },
        ],
      } satisfies BulkServiceStateResponse),
    });

    (
      rowFor(fixture, 'Chilanzar').querySelector('input[type=checkbox]') as HTMLInputElement
    ).click();
    (rowFor(fixture, 'Sergeli').querySelector('input[type=checkbox]') as HTMLInputElement).click();
    fixture.detectChanges();

    const reopenButton = Array.from(
      fixture.nativeElement.querySelectorAll('.bulk-bar button'),
    ).find((button) =>
      (button as HTMLButtonElement).textContent?.includes('Reopen selected'),
    ) as HTMLButtonElement;
    reopenButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.bulkChangeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FOLLOW_SCHEDULE',
      locationIds: ['location-1', 'location-3'],
    });
    // One request for the whole selection, never one per branch.
    expect(api.bulkChangeServiceState).toHaveBeenCalledTimes(1);

    const outcomesText =
      (fixture.nativeElement.querySelector('.bulk-outcomes') as HTMLElement | null)?.textContent ??
      '';
    expect(outcomesText).toContain('Chilanzar');
    expect(outcomesText).toContain('done');
    expect(outcomesText).toContain('Sergeli');
    // Translated through settings.locations.list.bulk.outcome.problem.* --
    // the raw backend code must never reach the screen untranslated (it
    // breaks the ru/uz-latn key-parity requirement for user-facing text).
    expect(outcomesText).toContain('Rejected as invalid');
    expect(outcomesText).not.toContain('VALIDATION_FAILED');
  });

  it('bulk-closes every selected branch with a required reason', async () => {
    await render();

    (
      rowFor(fixture, 'Chilanzar').querySelector('input[type=checkbox]') as HTMLInputElement
    ).click();
    fixture.detectChanges();

    const closeButton = Array.from(fixture.nativeElement.querySelectorAll('.bulk-bar button')).find(
      (button) => (button as HTMLButtonElement).textContent?.includes('Close selected'),
    ) as HTMLButtonElement;
    closeButton.click();
    fixture.detectChanges();

    const confirmButton = Array.from(
      fixture.nativeElement.querySelectorAll('.bulk-bar .close-form__actions button'),
    ).find((button) =>
      (button as HTMLButtonElement).textContent?.includes('Close'),
    ) as HTMLButtonElement;
    confirmButton.click();
    await flushMicrotasks();
    expect(api.bulkChangeServiceState).not.toHaveBeenCalled();

    const reasonInput = fixture.nativeElement.querySelector(
      '.bulk-bar .close-form input',
    ) as HTMLInputElement;
    reasonInput.value = 'regional holiday';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    confirmButton.click();
    await flushMicrotasks();

    expect(api.bulkChangeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FORCE_CLOSED',
      reasonCode: 'regional holiday',
      locationIds: ['location-1'],
    });
  });
});
