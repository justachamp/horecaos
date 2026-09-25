import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { InventoryApi, StockPosition } from './inventory-api';
import { StockPage } from './stock-page';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function position(overrides: Partial<StockPosition> = {}): StockPosition {
  return {
    stockItemId: 'si1',
    variantId: 'v1',
    trackingMode: 'QUANTITY',
    binaryAvailable: null,
    onHandQuantity: 10,
    reservedQuantity: 2,
    remainingQuantity: 8,
    defaultQuantity: 20,
    lastResetBusinessDate: '2026-09-20',
    channelStopThresholds: [],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(inventoryApi: Partial<InventoryApi>): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'catalog/stock', component: StockPage }]),
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal(FAKE_SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: InventoryApi, useValue: inventoryApi },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

describe('StockPage', () => {
  it('renders one row per QUANTITY-tracked stock item, skipping BINARY/UNTRACKED ones', async () => {
    configure({
      listPositions: () =>
        of([
          position({ variantId: 'v1' }),
          position({
            variantId: 'v2',
            trackingMode: 'BINARY',
            onHandQuantity: 0,
            reservedQuantity: 0,
            remainingQuantity: null,
          }),
        ]),
    });

    const harness = await RouterTestingHarness.create('/catalog/stock');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('[data-testid="stock-row-v1"]')).toBeTruthy();
    expect(harness.routeNativeElement!.querySelector('[data-testid="stock-row-v2"]')).toBeFalsy();
  });

  it('renders the no-location state when the operator has no location grant', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'catalog/stock', component: StockPage }]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: InventoryApi, useValue: {} },
      ],
    });
    TestBed.inject(I18n).setLocale('en');

    const harness = await RouterTestingHarness.create('/catalog/stock');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="stock-no-location"]'),
    ).toBeTruthy();
  });

  it('renders the empty state when there are no QUANTITY items at this location', async () => {
    configure({ listPositions: () => of([]) });

    const harness = await RouterTestingHarness.create('/catalog/stock');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('[data-testid="stock-empty"]')).toBeTruthy();
  });

  it('sets on-hand with a required reason, then reloads', async () => {
    const setOnHand = vi.fn().mockReturnValue(of(undefined));
    const listPositions = vi
      .fn()
      .mockReturnValueOnce(of([position({ onHandQuantity: 10, remainingQuantity: 8 })]))
      .mockReturnValueOnce(of([position({ onHandQuantity: 25, remainingQuantity: 23 })]));
    configure({ listPositions, setOnHand });

    const harness = await RouterTestingHarness.create('/catalog/stock');
    await flushMicrotasks();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="stock-row-v1"] button',
      ) as HTMLButtonElement
    ).click();
    harness.detectChanges();

    const onHandInput = harness.routeNativeElement!.querySelector(
      '[data-testid="stock-on-hand-input"]',
    ) as HTMLInputElement;
    onHandInput.value = '25';
    onHandInput.dispatchEvent(new Event('input'));

    const reasonInput = harness.routeNativeElement!.querySelector(
      '[data-testid="stock-on-hand-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Physical recount';
    reasonInput.dispatchEvent(new Event('input'));
    harness.detectChanges();

    const saveButton = harness.routeNativeElement!.querySelector(
      '[data-testid="stock-on-hand-save"]',
    ) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);
    saveButton.click();
    await flushMicrotasks();

    expect(setOnHand).toHaveBeenCalledWith(FAKE_SCOPE, 'v1', 25, 'Physical recount');
    expect(listPositions).toHaveBeenCalledTimes(2);
  });

  it('sets the daily default, allowing a blank value to clear it', async () => {
    const setQuantityDefault = vi.fn().mockReturnValue(of(undefined));
    configure({
      listPositions: () => of([position({ defaultQuantity: null })]),
      setQuantityDefault,
    });

    const harness = await RouterTestingHarness.create('/catalog/stock');
    await flushMicrotasks();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="stock-row-v1"] button',
      ) as HTMLButtonElement
    ).click();
    harness.detectChanges();

    const reasonInput = harness.routeNativeElement!.querySelector(
      '[data-testid="stock-default-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Daily par level';
    reasonInput.dispatchEvent(new Event('input'));
    harness.detectChanges();

    const saveButton = harness.routeNativeElement!.querySelector(
      '[data-testid="stock-default-save"]',
    ) as HTMLButtonElement;
    // Blank default input is a legal save -- it clears the scheduled reset.
    expect(saveButton.disabled).toBe(false);
    saveButton.click();
    await flushMicrotasks();

    expect(setQuantityDefault).toHaveBeenCalledWith(FAKE_SCOPE, 'v1', null, 'Daily par level');
  });

  it('adds a channel stop threshold and can remove an existing one', async () => {
    const setChannelStopThreshold = vi.fn().mockReturnValue(of(undefined));
    const clearChannelStopThreshold = vi.fn().mockReturnValue(of(undefined));
    configure({
      listPositions: () =>
        of([
          position({
            channelStopThresholds: [{ channelSystemType: 'AGGREGATOR', stopAtOrBelow: 3 }],
          }),
        ]),
      setChannelStopThreshold,
      clearChannelStopThreshold,
    });

    const harness = await RouterTestingHarness.create('/catalog/stock');
    await flushMicrotasks();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="stock-row-v1"] button',
      ) as HTMLButtonElement
    ).click();
    harness.detectChanges();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="stock-threshold-remove-AGGREGATOR"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    expect(clearChannelStopThreshold).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'v1',
      'AGGREGATOR',
      'NO_LONGER_NEEDED',
    );

    const valueInput = harness.routeNativeElement!.querySelector(
      '[data-testid="stock-threshold-value"]',
    ) as HTMLInputElement;
    valueInput.value = '5';
    valueInput.dispatchEvent(new Event('input'));
    const reasonInput = harness.routeNativeElement!.querySelector(
      '[data-testid="stock-threshold-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Kitchen buffer';
    reasonInput.dispatchEvent(new Event('input'));
    harness.detectChanges();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="stock-threshold-save"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(setChannelStopThreshold).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'v1',
      'AGGREGATOR',
      5,
      'Kitchen buffer',
    );
  });
});
