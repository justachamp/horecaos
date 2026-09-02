import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LocationsApi, LocationView } from './locations-api';
import { LocationsPage } from './locations-page';

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

  beforeEach(async () => {
    const api = { list: vi.fn().mockResolvedValue([LOCATION]) };

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
  });

  it('lists the brand’s locations', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilanzar');
    expect(text).toContain('CHILANZAR');
    expect(text).toContain('Tashkent');
  });
});
