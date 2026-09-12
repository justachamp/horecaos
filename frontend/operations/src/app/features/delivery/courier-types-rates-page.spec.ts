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

  const SCOOTER = {
    courierTypeId: 'type-1',
    code: 'SCOOTER',
    displayName: 'Scooter',
    vehicleClass: 'SCOOTER',
    minDistanceMeters: 0,
    maxDistanceMeters: null,
    maxConcurrentAssignments: 2,
    offerTtlSeconds: 60,
    startingMinuteOffset: 0,
    workMode: 'SHIFT',
    status: 'ACTIVE' as const,
    version: 1,
  };

  const STANDARD_CARD = {
    cardId: 'card-1',
    brandId: 'b1',
    locationId: null,
    courierTypeId: null,
    code: 'STANDARD',
    cardVersion: 1,
    status: 'DRAFT' as const,
    currency: 'UZS',
    effectiveFrom: null,
    effectiveTo: null,
  };

  it('lists courier types and rate cards', async () => {
    await render({
      types: () => Promise.resolve([SCOOTER]),
      rateCards: () => Promise.resolve([STANDARD_CARD]),
      adjustmentReasons: () => Promise.resolve([]),
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
      adjustmentReasons: () => Promise.resolve([]),
      rateCards: () => {
        callCount += 1;
        return Promise.resolve([{ ...STANDARD_CARD, status: callCount > 1 ? 'ACTIVE' : 'DRAFT' }]);
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

  it('shows a rate card’s PER_KM_BAND ladder and effective dates on demand', async () => {
    const rateCard = vi.fn().mockResolvedValue({
      cardId: 'card-1',
      cardVersion: 1,
      currency: 'UZS',
      components: [
        {
          componentId: 'c1',
          componentType: 'PER_ORDER',
          priority: 0,
          amountMinor: 3000,
          bandFromMeters: null,
          bandToMeters: null,
          minimumPaidSeconds: null,
        },
        {
          componentId: 'c2',
          componentType: 'PER_KM_BAND',
          priority: 0,
          amountMinor: 2000,
          bandFromMeters: 0,
          bandToMeters: 3000,
          minimumPaidSeconds: null,
        },
        {
          componentId: 'c3',
          componentType: 'PER_KM_BAND',
          priority: 1,
          amountMinor: 1500,
          bandFromMeters: 3000,
          bandToMeters: null,
          minimumPaidSeconds: null,
        },
      ],
    });
    await render({
      types: () => Promise.resolve([]),
      adjustmentReasons: () => Promise.resolve([]),
      rateCards: () =>
        Promise.resolve([
          { ...STANDARD_CARD, status: 'ACTIVE', effectiveFrom: '2026-09-01T00:00:00Z' },
        ]),
      rateCard,
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('2026-09-01');
    (host.querySelector('[data-testid="rate-card-show-components"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(rateCard).toHaveBeenCalledWith('t1', 'card-1');
    expect(host.querySelectorAll('[data-testid="rate-card-component-row"]')).toHaveLength(3);
  });

  it('creates a courier type sending maxDistanceMeters, startingMinuteOffset and workMode', async () => {
    const createType = vi.fn().mockResolvedValue(SCOOTER);
    await render({
      types: () => Promise.resolve([]),
      rateCards: () => Promise.resolve([]),
      adjustmentReasons: () => Promise.resolve([]),
      createType,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="rates-add-type"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const modalInputs = host.querySelectorAll('.rates__modal input[type="text"]');
    (modalInputs[0] as HTMLInputElement).value = 'CAR';
    modalInputs[0].dispatchEvent(new Event('input'));
    (modalInputs[1] as HTMLInputElement).value = 'Car';
    modalInputs[1].dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="rates-submit-type"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(createType).toHaveBeenCalledWith(
      't1',
      expect.objectContaining({
        code: 'CAR',
        displayName: 'Car',
        maxDistanceMeters: null,
        startingMinuteOffset: 0,
        workMode: 'SHIFT',
      }),
    );
  });

  it('corrects a courier type under its expected version', async () => {
    const updateType = vi.fn().mockResolvedValue(SCOOTER);
    await render({
      types: () => Promise.resolve([SCOOTER]),
      rateCards: () => Promise.resolve([]),
      adjustmentReasons: () => Promise.resolve([]),
      updateType,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="type-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const reasonInputs = host.querySelectorAll('.rates__modal input[type="text"]');
    const reasonInput = reasonInputs[reasonInputs.length - 1] as HTMLInputElement;
    reasonInput.value = 'fixing a typo';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="rates-submit-type-edit"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(updateType).toHaveBeenCalledWith(
      't1',
      'type-1',
      expect.objectContaining({ expectedVersion: 1, reason: 'fixing a typo' }),
    );
  });

  it('archives a courier type with a reason', async () => {
    const archiveType = vi.fn().mockResolvedValue(undefined);
    await render({
      types: () => Promise.resolve([SCOOTER]),
      rateCards: () => Promise.resolve([]),
      adjustmentReasons: () => Promise.resolve([]),
      archiveType,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="type-archive"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const reasonInput = host.querySelector('.rates__inline-confirm input') as HTMLInputElement;
    reasonInput.value = 'retired vehicle class';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="type-archive-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(archiveType).toHaveBeenCalledWith('t1', 'type-1', 'retired vehicle class');
  });

  it('defines a rule-wired bonus/penalty reason', async () => {
    const createAdjustmentReason = vi.fn().mockResolvedValue({
      reasonId: 'r1',
      code: 'TWO_IN_A_SHIFT',
      kind: 'BONUS',
      outcomeBasis: 'DELIVERED_VOLUME',
      displayName: 'Two in a shift',
      status: 'ACTIVE',
      hasRule: true,
      ruleAmountMinor: 50000,
      ruleCurrency: 'UZS',
      ruleComparator: 'GTE',
      ruleThreshold: 2,
      ruleWindow: 'SHIFT',
      ruleTrigger: 'SHIFT_CLOSE',
      ruleVersion: 1,
    });
    await render({
      types: () => Promise.resolve([]),
      rateCards: () => Promise.resolve([]),
      adjustmentReasons: () => Promise.resolve([]),
      createAdjustmentReason,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="rates-add-reason"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const textInputs = host.querySelectorAll('.rates__modal input[type="text"]');
    (textInputs[0] as HTMLInputElement).value = 'two_in_a_shift';
    textInputs[0].dispatchEvent(new Event('input'));
    (textInputs[1] as HTMLInputElement).value = 'Two in a shift';
    textInputs[1].dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="new-reason-wire-toggle"]') as HTMLInputElement).click();
    fixture.detectChanges();

    (host.querySelector('[data-testid="new-reason-threshold"]') as HTMLInputElement).valueAsNumber =
      2;
    host.querySelector('[data-testid="new-reason-threshold"]')!.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="rates-submit-reason"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(createAdjustmentReason).toHaveBeenCalledWith(
      't1',
      expect.objectContaining({
        code: 'TWO_IN_A_SHIFT',
        outcomeBasis: 'DELIVERED_VOLUME',
        ruleWindow: 'SHIFT',
        ruleTrigger: 'SHIFT_CLOSE',
        ruleThreshold: 2,
      }),
    );
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
        {
          provide: CouriersApi,
          useValue: { types: vi.fn(), rateCards: vi.fn(), adjustmentReasons: vi.fn() },
        },
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
