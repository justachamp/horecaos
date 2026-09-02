import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from './catalog-api';
import { InventoryApi } from './inventory-api';
import { MenusPage } from './menus-page';
import { PricingApi } from './pricing-api';
import { VariantAvailabilityRow } from './catalog-domain';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const EMPTY_PRICES = { priceBookId: null, currency: null, amountsMinor: {} };

function row(overrides: Partial<VariantAvailabilityRow>): VariantAvailabilityRow {
  return {
    variantId: 'v1',
    productName: 'Плов',
    category: 'Основные блюда',
    available: true,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(
  catalogApi: Partial<CatalogApi>,
  pricingApi: Partial<PricingApi> = {},
  inventoryApi: Partial<InventoryApi> = {},
): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'catalog/menus', component: MenusPage }]),
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal(FAKE_SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: CatalogApi, useValue: catalogApi },
      {
        provide: PricingApi,
        useValue: { resolvedVariantPrices: () => of(EMPTY_PRICES), ...pricingApi },
      },
      { provide: InventoryApi, useValue: inventoryApi },
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
}

describe('MenusPage', () => {
  it('renders one row per variant offered at the location', async () => {
    configure({
      variantsAtLocation: () =>
        of([
          row({ variantId: 'v1', productName: 'Плов' }),
          row({ variantId: 'v2', productName: 'Лагман', available: false }),
        ]),
    });

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();

    const cells = [...harness.routeNativeElement!.querySelectorAll('[data-testid="menus-cell"]')];
    expect(cells.length).toBe(2);
    expect(cells[0].textContent?.trim()).toBe('В меню');
    expect(cells[1].textContent?.trim()).toBe('Стоп');
  });

  it('renders the no-location state when the operator has no location grant', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'catalog/menus', component: MenusPage }]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CatalogApi, useValue: {} },
        { provide: PricingApi, useValue: {} },
        { provide: InventoryApi, useValue: {} },
      ],
    });
    TestBed.inject(I18n).setLocale('ru');

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="menus-no-location"]'),
    ).toBeTruthy();
  });

  it('toggles a cell from В меню to Стоп through the audited inventory endpoint, not the offering one', async () => {
    const setAvailability = vi.fn().mockReturnValue(of(undefined));
    configure(
      { variantsAtLocation: () => of([row({ variantId: 'v1', available: true })]) },
      {},
      { setAvailability },
    );

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="menus-cell"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    // `variantsAtLocation` reports `inventory.positions.binary_available`
    // (see `menus-page.ts`'s own doc) — writing through
    // `CatalogApi.setOffering` instead would desync the toggle from what a
    // real re-fetch shows, which is exactly the bug this assertion guards.
    expect(setAvailability).toHaveBeenCalledWith(FAKE_SCOPE, 'v1', false);
    expect(host.querySelector('[data-testid="menus-cell"]')?.textContent?.trim()).toBe('Стоп');
  });

  it('renders the denied state on a 403 rather than an empty matrix', async () => {
    configure({
      variantsAtLocation: () =>
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('[data-testid="menus-denied"]')).toBeTruthy();
  });
});
