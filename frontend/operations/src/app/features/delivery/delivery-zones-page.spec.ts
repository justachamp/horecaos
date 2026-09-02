import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { DeliveryZonesApi, ZoneSummaryResponse } from './delivery-zones-api';
import { DeliveryZonesPage } from './delivery-zones-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const ZONE: ZoneSummaryResponse = {
  zoneId: 'zone-1',
  role: 'DELIVERY',
  code: 'CITY',
  displayNameRu: 'Город',
  displayNameUz: 'Shahar',
  displayNameEn: 'City',
  status: 'ACTIVE',
  activeVersion: 1,
  priority: 3,
  currency: 'UZS',
  deliveryTariffId: null,
  freeDeliveryFromMinor: 50_000,
  minBasketMinor: 20_000,
  areaSquareMeters: 12_000_000,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeliveryZonesPage', () => {
  let fixture: ComponentFixture<DeliveryZonesPage>;

  async function render(api: Partial<DeliveryZonesApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DeliveryZonesPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal('actor-1') } },
        { provide: DeliveryZonesApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryZonesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists a zone with its live version’s priority and status', async () => {
    await render({ list: vi.fn().mockResolvedValue([ZONE]) });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="zone-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('CITY');
    expect(host.textContent).toContain('v1');
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [DeliveryZonesPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal(null) } },
        { provide: DeliveryZonesApi, useValue: { list: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryZonesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="zones-denied"]'),
    ).not.toBeNull();
  });
});
