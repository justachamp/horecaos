import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CatalogApi } from './catalog-api';
import { InventoryApi } from './inventory-api';
import { MenusPage } from './menus-page';
import { PricingApi } from './pricing-api';
import { VariantAvailabilityRow } from './catalog-domain';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const EMPTY_PRICES = { priceBookId: null, currency: null, amountsMinor: {} };
const EMPTY_EXCLUSIONS = { excludedVariantIds: [] };

function row(overrides: Partial<VariantAvailabilityRow>): VariantAvailabilityRow {
  return {
    variantId: 'v1',
    productName: 'Плов',
    category: 'Основные блюда',
    available: true,
    ...overrides,
  };
}

function channel(overrides: Partial<ChannelView> = {}): ChannelView {
  return {
    id: 'ch1',
    code: 'UZUM_TEZKOR',
    systemType: 'AGGREGATOR',
    displayName: 'Uzum Tezkor',
    status: 'ACTIVE',
    pricePlaneChannelId: null,
    externallyPriced: false,
    guestOrdersAllowed: false,
    providerInstallationId: null,
    version: 1,
    locationCount: 1,
    enabledPaymentMethodCount: 0,
    enabledFulfillmentModes: [],
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
  channelsApi: Partial<SalesChannelsApi> = {},
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
      {
        provide: CatalogApi,
        useValue: { channelExclusions: () => of(EMPTY_EXCLUSIONS), ...catalogApi },
      },
      {
        provide: PricingApi,
        useValue: { resolvedVariantPrices: () => of(EMPTY_PRICES), ...pricingApi },
      },
      { provide: InventoryApi, useValue: inventoryApi },
      {
        provide: SalesChannelsApi,
        useValue: { list: () => Promise.resolve([]), ...channelsApi },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
}

describe('MenusPage', () => {
  it('renders one row per variant offered at the location', async () => {
    configure({
      variantsAtLocation: () =>
        of({
          items: [
            row({ variantId: 'v1', productName: 'Плов' }),
            row({ variantId: 'v2', productName: 'Лагман', available: false }),
          ],
          nextCursor: null,
        }),
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
        { provide: SalesChannelsApi, useValue: {} },
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
      {
        variantsAtLocation: () =>
          of({ items: [row({ variantId: 'v1', available: true })], nextCursor: null }),
      },
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

  it(
    'regression: reads variantsAtLocation as a Page<T> envelope and pages through ' +
      'every cursor rather than iterating the envelope object as if it were the array of rows',
    async () => {
      const variantsAtLocation = vi
        .fn()
        .mockReturnValueOnce(
          of({ items: [row({ variantId: 'v1', productName: 'Плов' })], nextCursor: 'cursor-1' }),
        )
        .mockReturnValueOnce(
          of({ items: [row({ variantId: 'v2', productName: 'Лагман' })], nextCursor: null }),
        );
      configure({ variantsAtLocation });

      const harness = await RouterTestingHarness.create('/catalog/menus');
      await flushMicrotasks();

      // Before the fix, `unwrap(api.get(...))` handed the page-shaped body
      // straight to `@for` as if it were `readonly VariantAvailabilityRow[]`
      // — an object has no iteration protocol, so this would have thrown
      // rather than rendering two rows across two fetched pages.
      expect(variantsAtLocation).toHaveBeenCalledTimes(2);
      const [, , firstPageArg] = variantsAtLocation.mock.calls[0];
      const [, , secondPageArg] = variantsAtLocation.mock.calls[1];
      expect(firstPageArg.cursor).toBeNull();
      expect(secondPageArg.cursor).toBe('cursor-1');

      const cells = [...harness.routeNativeElement!.querySelectorAll('[data-testid="menus-cell"]')];
      expect(cells.length).toBe(2);
      expect(harness.routeNativeElement!.textContent).toContain('Плов');
      expect(harness.routeNativeElement!.textContent).toContain('Лагман');
    },
  );

  it('renders the denied state on a 403 rather than an empty matrix', async () => {
    configure({
      variantsAtLocation: () =>
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('[data-testid="menus-denied"]')).toBeTruthy();
  });

  it(
    'a HIDDEN offering and a never-added variant render with different labels — ' +
      'row 4.4, the matrix distinction this wave adds',
    async () => {
      configure({
        variantsAtLocation: () =>
          of({
            items: [
              row({ variantId: 'v1', offeringStatus: 'HIDDEN' }),
              row({ variantId: 'v2', offeringStatus: undefined }),
            ],
            nextCursor: null,
          }),
      });

      const harness = await RouterTestingHarness.create('/catalog/menus');
      await flushMicrotasks();

      const badges = [
        ...harness.routeNativeElement!.querySelectorAll('[data-testid="menus-offering-status"]'),
      ];
      expect(badges[0].textContent?.trim()).toBe('Скрыт');
      expect(badges[1].textContent?.trim()).toBe('Не в меню');
    },
  );

  it('clicking a status filter tab refetches with that status, dropping the previous page', async () => {
    const variantsAtLocation = vi
      .fn()
      .mockReturnValue(of({ items: [row({ variantId: 'v1' })], nextCursor: null }));
    configure({ variantsAtLocation });

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="menus-filter-HIDDEN"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const lastCall = variantsAtLocation.mock.calls.at(-1)!;
    expect(lastCall[3]).toEqual({ search: undefined, status: 'HIDDEN' });
  });

  it('selecting rows and confirming a bulk action calls bulkSetOfferingStatus with the selection', async () => {
    const bulkSetOfferingStatus = vi.fn().mockReturnValue(of({ updatedCount: 2 }));
    configure({
      variantsAtLocation: () =>
        of({ items: [row({ variantId: 'v1' }), row({ variantId: 'v2' })], nextCursor: null }),
      bulkSetOfferingStatus,
    });

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const checkboxes = [
      ...host.querySelectorAll<HTMLInputElement>('[data-testid="menus-row-checkbox"]'),
    ];
    for (const checkbox of checkboxes) {
      checkbox.checked = true;
      checkbox.dispatchEvent(new Event('change'));
    }
    await flushMicrotasks();

    (host.querySelector('[data-testid="menus-bulk-unavailable"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(bulkSetOfferingStatus).toHaveBeenCalledWith(FAKE_SCOPE, 'l1', {
      variantIds: ['v1', 'v2'],
      status: 'UNAVAILABLE',
    });
  });

  /**
   * P23 second-pass adversarial review: `BulkOfferingStatusRequest` refuses
   * the whole request outright past 200 ids (`@Size(max = 200)`), and this
   * matrix can load up to 4000 sellable variants — so "select all" on a
   * large location used to build a selection the endpoint would always
   * reject whole, applying nothing.
   */
  it('caps "select all" at 200 rows, matching the endpoint\'s own limit, rather than selecting every row', async () => {
    const manyRows = Array.from({ length: 250 }, (_, i) => row({ variantId: `v${i}` }));
    const bulkSetOfferingStatus = vi.fn().mockReturnValue(of({ updatedCount: 200 }));
    configure({
      variantsAtLocation: () => of({ items: manyRows, nextCursor: null }),
      bulkSetOfferingStatus,
    });

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="menus-select-all"]') as HTMLInputElement).dispatchEvent(
      new Event('change'),
    );
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="menus-bulk-bar"]')?.textContent).toContain('200');
    expect(host.querySelector('[data-testid="menus-selection-capped"]')).not.toBeNull();

    (host.querySelector('[data-testid="menus-bulk-unavailable"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const call = bulkSetOfferingStatus.mock.calls.at(-1)!;
    expect((call[2] as { variantIds: readonly string[] }).variantIds).toHaveLength(200);
  });

  // ---------------------------------------------------------- P45: the channel plane

  it('renders "Зал" plus every configured channel in the channel picker', async () => {
    configure(
      { variantsAtLocation: () => of({ items: [row({ variantId: 'v1' })], nextCursor: null }) },
      {},
      {},
      { list: () => Promise.resolve([channel({ id: 'ch1', displayName: 'Uzum Tezkor' })]) },
    );

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();

    const options = [
      ...harness.routeNativeElement!.querySelectorAll<HTMLOptionElement>(
        '[data-testid="menus-channel-select"] option',
      ),
    ];
    expect(options.map((option) => option.textContent?.trim())).toEqual(['Зал', 'Uzum Tezkor']);
  });

  it(
    'selecting a channel that has no price book of its own shows a hint instead of an ' +
      'editable price, so a save can never silently rewrite the hall price',
    async () => {
      // Both calls resolve the same book — the channel is falling back to
      // the hall's own book, not carrying a channel-scoped one.
      const resolvedVariantPrices = vi
        .fn()
        .mockReturnValue(
          of({ priceBookId: 'hall-book', currency: 'UZS', amountsMinor: { v1: 50_000 } }),
        );
      configure(
        { variantsAtLocation: () => of({ items: [row({ variantId: 'v1' })], nextCursor: null }) },
        { resolvedVariantPrices },
        {},
        { list: () => Promise.resolve([channel({ id: 'ch1' })]) },
      );

      const harness = await RouterTestingHarness.create('/catalog/menus');
      await flushMicrotasks();
      const host = harness.routeNativeElement!;

      const select = host.querySelector<HTMLSelectElement>('[data-testid="menus-channel-select"]')!;
      select.value = 'ch1';
      select.dispatchEvent(new Event('change'));
      await flushMicrotasks();

      expect(host.querySelector('[data-testid="menus-channel-price-hint"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="menus-channel-price-input"]')).toBeNull();
    },
  );

  it(
    'selecting a channel with its own price book allows editing it, and saving writes ' +
      'through the channel book, never the hall one',
    async () => {
      const resolvedVariantPrices = vi.fn(
        (_scope: unknown, _locationId: unknown, _ids: unknown, channelId?: string) =>
          channelId === undefined
            ? of({ priceBookId: 'hall-book', currency: 'UZS', amountsMinor: { v1: 50_000 } })
            : of({ priceBookId: 'channel-book', currency: 'UZS', amountsMinor: { v1: 60_000 } }),
      );
      const setVariantPrice = vi.fn().mockReturnValue(of({}));
      configure(
        { variantsAtLocation: () => of({ items: [row({ variantId: 'v1' })], nextCursor: null }) },
        { resolvedVariantPrices, setVariantPrice },
        {},
        { list: () => Promise.resolve([channel({ id: 'ch1' })]) },
      );

      const harness = await RouterTestingHarness.create('/catalog/menus');
      await flushMicrotasks();
      const host = harness.routeNativeElement!;

      const select = host.querySelector<HTMLSelectElement>('[data-testid="menus-channel-select"]')!;
      select.value = 'ch1';
      select.dispatchEvent(new Event('change'));
      await flushMicrotasks();

      const input = host.querySelector<HTMLInputElement>(
        '[data-testid="menus-channel-price-input"]',
      )!;
      expect(input).not.toBeNull();
      input.value = '65000';
      (host.querySelector('[data-testid="menus-channel-price-save"]') as HTMLButtonElement).click();
      await flushMicrotasks();

      expect(setVariantPrice).toHaveBeenCalledWith(FAKE_SCOPE, 'channel-book', 'v1', 65_000);
    },
  );

  it('clicking the channel toggle calls setChannelOffering and flips the label', async () => {
    const setChannelOffering = vi.fn().mockReturnValue(of(undefined));
    configure(
      {
        variantsAtLocation: () => of({ items: [row({ variantId: 'v1' })], nextCursor: null }),
        channelExclusions: () => of({ excludedVariantIds: [] }),
        setChannelOffering,
      },
      {},
      {},
      { list: () => Promise.resolve([channel({ id: 'ch1' })]) },
    );

    const harness = await RouterTestingHarness.create('/catalog/menus');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const select = host.querySelector<HTMLSelectElement>('[data-testid="menus-channel-select"]')!;
    select.value = 'ch1';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="menus-channel-status"]')?.textContent?.trim()).toBe(
      'На канале',
    );

    (host.querySelector('[data-testid="menus-channel-status"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setChannelOffering).toHaveBeenCalledWith(FAKE_SCOPE, 'ch1', 'v1', {
      offered: false,
      locationId: 'l1',
    });
    expect(host.querySelector('[data-testid="menus-channel-status"]')?.textContent?.trim()).toBe(
      'Скрыто на канале',
    );
  });

  it(
    'the mass-enable gesture calls bulkSetChannelOffering with the selection, the ' +
      'channel and the location — the row this wave exists to fix',
    async () => {
      const bulkSetChannelOffering = vi.fn().mockReturnValue(of({ changedCount: 2 }));
      configure(
        {
          variantsAtLocation: () =>
            of({ items: [row({ variantId: 'v1' }), row({ variantId: 'v2' })], nextCursor: null }),
          bulkSetChannelOffering,
        },
        {},
        {},
        { list: () => Promise.resolve([channel({ id: 'ch1' })]) },
      );

      const harness = await RouterTestingHarness.create('/catalog/menus');
      await flushMicrotasks();
      const host = harness.routeNativeElement!;

      const select = host.querySelector<HTMLSelectElement>('[data-testid="menus-channel-select"]')!;
      select.value = 'ch1';
      select.dispatchEvent(new Event('change'));
      await flushMicrotasks();

      for (const checkbox of [
        ...host.querySelectorAll<HTMLInputElement>('[data-testid="menus-row-checkbox"]'),
      ]) {
        checkbox.checked = true;
        checkbox.dispatchEvent(new Event('change'));
      }
      await flushMicrotasks();

      (
        host.querySelector('[data-testid="menus-bulk-channel-enable"]') as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
      await flushMicrotasks();

      expect(bulkSetChannelOffering).toHaveBeenCalledWith(FAKE_SCOPE, 'ch1', {
        variantIds: ['v1', 'v2'],
        offered: true,
        locationId: 'l1',
      });
    },
  );
});
