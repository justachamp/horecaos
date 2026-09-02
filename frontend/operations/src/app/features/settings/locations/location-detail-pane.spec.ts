import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LocationDetailPane } from './location-detail-pane';
import { LocationsApi, LocationView, ServiceSummaryResponse } from './locations-api';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const LOCATION: LocationView = {
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

const SUMMARY: ServiceSummaryResponse = {
  mode: 'FOLLOW_SCHEDULE',
  effectiveMode: 'FOLLOW_SCHEDULE',
  reasonCode: null,
  effectiveUntil: null,
  maxConcurrentOrders: 20,
  openOrderCount: 7,
  bindings: [
    {
      fulfillmentMode: 'DELIVERY',
      scheduleId: 'schedule-1',
      scheduleName: 'Standard hours',
      acceptsScheduledOrders: true,
      sharedWithLocationCount: 3,
      rules: [{ dayOfWeek: 1, opensAt: '09:00', closesAt: '23:00' }],
      exceptions: [],
    },
  ],
  preparationBands: [
    {
      fulfillmentMode: 'DELIVERY',
      dayOfWeek: null,
      startsAt: '18:00',
      endsAt: '21:00',
      durationMinutes: 25,
      priority: 1,
    },
  ],
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('LocationDetailPane', () => {
  let fixture: ComponentFixture<LocationDetailPane>;
  let api: {
    profile: ReturnType<typeof vi.fn>;
    serviceSummary: ReturnType<typeof vi.fn>;
    describePlace: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
    setCapacity: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      profile: vi.fn().mockResolvedValue(LOCATION),
      serviceSummary: vi.fn().mockResolvedValue(SUMMARY),
      describePlace: vi.fn().mockResolvedValue({ ...LOCATION, addressLine: 'New address' }),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
      setCapacity: vi.fn().mockResolvedValue(undefined),
    };

    await TestBed.configureTestingModule({
      imports: [LocationDetailPane],
      providers: [
        { provide: LocationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LocationDetailPane);
    fixture.componentRef.setInput('locationId', 'location-1');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('merges the route’s locationId onto the operator’s own tenant/brand scope', () => {
    expect(api.profile).toHaveBeenCalledWith(SCOPE);
    expect(api.serviceSummary).toHaveBeenCalledWith(SCOPE);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Chilanzar');
  });

  it('shows the bound schedule’s grid and the shared-with-others warning on the Hours tab', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Standard hours');
    expect(text).toContain('3');
  });

  it('shows capacity and preparation bands on the Load tab', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[2] as HTMLButtonElement).click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('7');
    expect(text).toContain('25');
  });

  it('saves the address/phone through the cross-surface place endpoint', async () => {
    const editButton = fixture.nativeElement.querySelector('.primary') as HTMLButtonElement;
    editButton.click();
    fixture.detectChanges();

    const addressInput = fixture.nativeElement.querySelector('#place-address') as HTMLInputElement;
    addressInput.value = 'New address';
    addressInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const saveButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Save')) as HTMLButtonElement;
    saveButton.click();
    await flushMicrotasks();

    expect(api.describePlace).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ addressLine: 'New address' }),
    );
  });

  it('requires a reason code before forcing the location closed', async () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('#state-mode') as HTMLSelectElement;
    select.value = 'FORCE_CLOSED';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const applyButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Apply')) as HTMLButtonElement;
    applyButton.click();
    await flushMicrotasks();

    expect(api.changeServiceState).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });
});
