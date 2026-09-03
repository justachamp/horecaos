import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { CouriersApi } from '../couriers/couriers-api';
import { I18n } from '../../core/i18n/i18n';
import { CourierTypesRatesPage } from './courier-types-rates-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CourierTypesRatesPage', () => {
  let fixture: ComponentFixture<CourierTypesRatesPage>;

  async function render(api: Partial<CouriersApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [CourierTypesRatesPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierTypesRatesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists courier types and rate cards', async () => {
    await render({
      types: () =>
        Promise.resolve([
          {
            courierTypeId: 'type-1',
            code: 'SCOOTER',
            displayName: 'Scooter',
            vehicleClass: 'SCOOTER',
            minDistanceMeters: 0,
            maxDistanceMeters: null,
            maxConcurrentAssignments: 2,
            offerTtlSeconds: 60,
          },
        ]),
      rateCards: () =>
        Promise.resolve([
          {
            cardId: 'card-1',
            brandId: 'b1',
            locationId: null,
            courierTypeId: null,
            code: 'STANDARD',
            cardVersion: 1,
            status: 'DRAFT',
            currency: 'UZS',
            effectiveFrom: null,
            effectiveTo: null,
          },
        ]),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="courier-type-row"]')).toHaveLength(1);
    expect(host.querySelectorAll('[data-testid="rate-card-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('SCOOTER');
    expect(host.textContent).toContain('STANDARD');
  });

  it('activates a draft rate card and reloads', async () => {
    const activateRateCard = vi.fn().mockResolvedValue(undefined);
    let callCount = 0;
    await render({
      types: () => Promise.resolve([]),
      rateCards: () => {
        callCount += 1;
        return Promise.resolve([
          {
            cardId: 'card-1',
            brandId: 'b1',
            locationId: null,
            courierTypeId: null,
            code: 'STANDARD',
            cardVersion: 1,
            status: callCount > 1 ? 'ACTIVE' : 'DRAFT',
            currency: 'UZS',
            effectiveFrom: null,
            effectiveTo: null,
          },
        ]);
      },
      activateRateCard,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="rate-card-activate"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(activateRateCard).toHaveBeenCalledWith('t1', 'card-1', expect.any(String));
    expect(host.querySelector('[data-testid="rate-card-activate"]')).toBeNull();
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [CourierTypesRatesPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: { types: vi.fn(), rateCards: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierTypesRatesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="rates-denied"]'),
    ).not.toBeNull();
  });
});
