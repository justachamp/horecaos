import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { BulkPriceChangePage } from './bulk-price-change-page';
import { CatalogApi } from './catalog-api';
import { PricingApi } from './pricing-api';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const LOCATION_SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const VARIANTS = [
  { variantId: 'v1', productName: 'Burger', category: 'Hot', available: true, trackingMode: null },
  { variantId: 'v2', productName: 'Pizza', category: 'Hot', available: true, trackingMode: null },
  { variantId: 'v3', productName: 'Cola', category: 'Drinks', available: true, trackingMode: null },
];

const RESOLVED = {
  priceBookId: 'book-1',
  currency: 'UZS',
  amountsMinor: { v1: 50_000, v2: 70_000, v3: 10_000 },
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('BulkPriceChangePage', () => {
  let fixture: ComponentFixture<BulkPriceChangePage>;

  async function render(
    catalogApi: Partial<CatalogApi>,
    pricingApi: Partial<PricingApi>,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [BulkPriceChangePage],
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
            scope: signal<LocationScope | null>(LOCATION_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CatalogApi, useValue: catalogApi },
        { provide: PricingApi, useValue: pricingApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BulkPriceChangePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('joins the filtered selection against current prices and computes a 10% increase', async () => {
    await render(
      { variantsAtLocation: () => of({ items: VARIANTS, nextCursor: null }) },
      { resolvedVariantPrices: () => of(RESOLVED) },
    );

    const host = fixture.nativeElement as HTMLElement;
    // Default calculator is PERCENT, 10%, INCREASE — 50,000 -> 55,000.
    expect(host.textContent).toContain('55');
    expect(host.querySelectorAll('[data-testid="dg-row"]')).toHaveLength(3);
  });

  it('narrows the selection to one category', async () => {
    await render(
      { variantsAtLocation: () => of({ items: VARIANTS, nextCursor: null }) },
      { resolvedVariantPrices: () => of(RESOLVED) },
    );

    const host = fixture.nativeElement as HTMLElement;
    const select = host.querySelector('[data-testid="price-bulk-category"]') as HTMLSelectElement;
    select.value = 'Drinks';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="dg-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('Cola');
    expect(host.textContent).not.toContain('Burger');
  });

  it('previews (dry run) and then applies, reloading prices afterward', async () => {
    const bulkApplyPrices = vi.fn().mockReturnValue(
      of({
        totalItems: 3,
        appliedCount: 3,
        failedCount: 0,
        dryRun: true,
        items: [
          {
            priceableType: 'VARIANT',
            priceableId: 'v1',
            applied: true,
            previousAmountMinor: 50_000,
            amountMinor: 55_000,
          },
          {
            priceableType: 'VARIANT',
            priceableId: 'v2',
            applied: true,
            previousAmountMinor: 70_000,
            amountMinor: 77_000,
          },
          {
            priceableType: 'VARIANT',
            priceableId: 'v3',
            applied: true,
            previousAmountMinor: 10_000,
            amountMinor: 11_000,
          },
        ],
      }),
    );
    const resolvedVariantPrices = vi.fn().mockReturnValue(of(RESOLVED));
    await render(
      { variantsAtLocation: () => of({ items: VARIANTS, nextCursor: null }) },
      { resolvedVariantPrices, bulkApplyPrices },
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="price-bulk-preview"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(bulkApplyPrices).toHaveBeenCalledWith(
      BRAND_SCOPE,
      'book-1',
      expect.arrayContaining([expect.objectContaining({ priceableId: 'v1', amountMinor: 55_000 })]),
      true,
    );
    expect(host.querySelector('[data-testid="price-bulk-report"]')?.textContent).toContain('3');
    expect(resolvedVariantPrices).toHaveBeenCalledTimes(1); // not reloaded after a dry run

    bulkApplyPrices.mockReturnValue(
      of({
        totalItems: 3,
        appliedCount: 3,
        failedCount: 0,
        dryRun: false,
        items: [
          {
            priceableType: 'VARIANT',
            priceableId: 'v1',
            applied: true,
            previousAmountMinor: 50_000,
            amountMinor: 55_000,
          },
          {
            priceableType: 'VARIANT',
            priceableId: 'v2',
            applied: true,
            previousAmountMinor: 70_000,
            amountMinor: 77_000,
          },
          {
            priceableType: 'VARIANT',
            priceableId: 'v3',
            applied: true,
            previousAmountMinor: 10_000,
            amountMinor: 11_000,
          },
        ],
      }),
    );
    (host.querySelector('[data-testid="price-bulk-apply"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(bulkApplyPrices).toHaveBeenLastCalledWith(
      BRAND_SCOPE,
      'book-1',
      expect.any(Array),
      false,
    );
    // Applying reloads current prices so a second pass computes off the new baseline.
    expect(resolvedVariantPrices).toHaveBeenCalledTimes(2);
  });

  it('shows the no-price-book state and disables preview/apply when nothing resolves', async () => {
    await render(
      { variantsAtLocation: () => of({ items: VARIANTS, nextCursor: null }) },
      { resolvedVariantPrices: () => of({ priceBookId: null, currency: null, amountsMinor: {} }) },
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="price-bulk-no-book"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="price-bulk-preview"]')).toBeNull();
  });

  it('shows the denied state when neither brand nor location resolves', async () => {
    await TestBed.configureTestingModule({
      imports: [BulkPriceChangePage],
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
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CatalogApi, useValue: { variantsAtLocation: vi.fn() } },
        { provide: PricingApi, useValue: { resolvedVariantPrices: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BulkPriceChangePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="price-bulk-denied"]'),
    ).not.toBeNull();
  });
});
