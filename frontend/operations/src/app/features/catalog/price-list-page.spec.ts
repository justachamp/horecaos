import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { PriceListPage } from './price-list-page';
import { PricingApi } from './pricing-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const ACTIVE_BOOK = {
  priceBookId: 'book-1',
  name: 'Base',
  currency: 'UZS',
  status: 'ACTIVE' as const,
  priority: 0,
  validFrom: new Date().toISOString(),
  validUntil: null,
  version: 3,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PriceListPage', () => {
  let fixture: ComponentFixture<PriceListPage>;

  async function render(
    api: Partial<PricingApi>,
    locations: Partial<LocationsApi> = { list: () => Promise.resolve([]) },
    channels: Partial<SalesChannelsApi> = { list: () => Promise.resolve([]) },
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [PriceListPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PricingApi, useValue: api },
        { provide: LocationsApi, useValue: locations },
        { provide: SalesChannelsApi, useValue: channels },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PriceListPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists price books and offers assign/activate on a draft', async () => {
    await render({
      listPriceBooks: () =>
        of([
          {
            priceBookId: 'book-1',
            name: 'Base',
            currency: 'UZS',
            status: 'DRAFT',
            priority: 0,
            validFrom: new Date().toISOString(),
            validUntil: null,
            version: 1,
          },
        ]),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="price-book-row"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="price-book-assign"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="price-book-activate"]')).not.toBeNull();
  });

  it('offers assign, but not activate, on an ACTIVE book — assign is no longer gated on DRAFT', async () => {
    await render({ listPriceBooks: () => of([ACTIVE_BOOK]) });

    const host = fixture.nativeElement as HTMLElement;
    const assign = host.querySelector('[data-testid="price-book-assign"]') as HTMLButtonElement;
    const activate = host.querySelector('[data-testid="price-book-activate"]') as HTMLButtonElement;
    expect(assign.disabled).toBe(false);
    expect(activate.disabled).toBe(true);
  });

  it('assigns an ACTIVE book to one location, with priority and a validity window', async () => {
    const assignToLocation = vi.fn().mockReturnValue(of(ACTIVE_BOOK));
    await render(
      { listPriceBooks: () => of([ACTIVE_BOOK]), assignToLocation },
      { list: () => Promise.resolve([{ id: 'loc-1', displayName: 'Chorsu branch' } as never]) },
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="price-book-assign"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="price-book-assign-scope-location"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const target = host.querySelector(
      '[data-testid="price-book-assign-target"]',
    ) as HTMLSelectElement;
    target.value = 'loc-1';
    target.dispatchEvent(new Event('change'));
    const priority = host.querySelector(
      '[data-testid="price-book-assign-priority"]',
    ) as HTMLInputElement;
    priority.value = '5';
    priority.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="price-book-assign-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(assignToLocation).toHaveBeenCalledWith(
      SCOPE,
      'book-1',
      'loc-1',
      expect.objectContaining({ priority: 5 }),
    );
  });

  it('assigns a book to one channel', async () => {
    const assignToChannel = vi.fn().mockReturnValue(of(ACTIVE_BOOK));
    await render({ listPriceBooks: () => of([ACTIVE_BOOK]), assignToChannel }, undefined, {
      list: () => Promise.resolve([{ id: 'chan-1', displayName: 'Yandex Eats' } as never]),
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="price-book-assign"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="price-book-assign-scope-channel"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const target = host.querySelector(
      '[data-testid="price-book-assign-target"]',
    ) as HTMLSelectElement;
    target.value = 'chan-1';
    target.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="price-book-assign-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(assignToChannel).toHaveBeenCalledWith(SCOPE, 'book-1', 'chan-1', expect.anything());
  });

  it('activates a draft book with its version as If-Match', async () => {
    const activate = vi.fn().mockReturnValue(
      of({
        priceBookId: 'book-1',
        name: 'Base',
        currency: 'UZS',
        status: 'ACTIVE',
        priority: 0,
        validFrom: '',
        validUntil: null,
        version: 2,
      }),
    );
    await render({
      listPriceBooks: () =>
        of([
          {
            priceBookId: 'book-1',
            name: 'Base',
            currency: 'UZS',
            status: 'DRAFT',
            priority: 0,
            validFrom: new Date().toISOString(),
            validUntil: null,
            version: 1,
          },
        ]),
      activate,
    });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="price-book-activate"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(activate).toHaveBeenCalledWith(SCOPE, 'book-1', 1);
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [PriceListPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PricingApi, useValue: { listPriceBooks: vi.fn() } },
        { provide: LocationsApi, useValue: { list: vi.fn() } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PriceListPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="price-list-denied"]'),
    ).not.toBeNull();
  });
});
