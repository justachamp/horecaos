import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { Toasts } from '../../../shared/ui/toast';
import { CustomersApi } from '../../customers/customers-api';
import { SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import {
  CustomerLookupCandidate,
  NewOrderApi,
  PlaceOrderResult,
  StorefrontMenu,
} from './new-order-api';
import { NewOrderPage } from './new-order-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const MENU: StorefrontMenu = {
  publicationId: 'pub-1',
  locale: 'ru',
  currency: 'UZS',
  categories: [
    {
      categoryId: 'cat-1',
      code: 'MAIN',
      name: 'Main',
      parentCategoryId: null,
      sortOrder: 1,
      productIds: ['p-1'],
    },
  ],
  products: [
    {
      productId: 'p-1',
      code: 'BURGER',
      name: 'Cheeseburger',
      description: null,
      imageUrls: [],
      variants: [
        {
          variantId: 'v-1',
          sku: null,
          unitCode: null,
          isDefault: true,
          orderable: true,
          amountMinor: 30_000,
        },
      ],
      modifierGroupIds: [],
    },
  ],
  modifierGroups: [],
};

function candidate(overrides: Partial<CustomerLookupCandidate> = {}): CustomerLookupCandidate {
  return {
    accountId: 'acct-1',
    maskedDisplayName: 'A****** K******',
    lastOrderAt: '2026-09-01T12:00:00Z',
    recentOrderCount: 2,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('NewOrderPage', () => {
  let fixture: ComponentFixture<NewOrderPage>;
  let newOrderApi: {
    menu: ReturnType<typeof vi.fn>;
    lookupCustomerByPhone: ReturnType<typeof vi.fn>;
    searchItems: ReturnType<typeof vi.fn>;
    placeOrder: ReturnType<typeof vi.fn>;
  };
  let router: Router;

  afterEach(() => {
    vi.useRealTimers();
  });

  async function render(overrides: Partial<typeof newOrderApi> = {}): Promise<void> {
    newOrderApi = {
      menu: vi.fn().mockResolvedValue(MENU),
      lookupCustomerByPhone: vi.fn().mockResolvedValue([]),
      searchItems: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      placeOrder: vi.fn(),
      ...overrides,
    };
    await TestBed.configureTestingModule({
      imports: [NewOrderPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: NewOrderApi, useValue: newOrderApi },
        {
          provide: CustomersApi,
          useValue: {
            create: vi.fn(),
            ordersPage: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
          },
        },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: Toasts, useValue: { show: () => 0 } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(NewOrderPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  // ------------------------------------------------------------------ search

  it('searches the location-scoped item read and renders combobox options from the result', async () => {
    await render({
      searchItems: vi.fn().mockResolvedValue({
        items: [
          {
            variantId: 'v-1',
            productName: 'Cheeseburger',
            category: 'Main',
            available: true,
            trackingMode: 'BINARY',
          },
        ],
        nextCursor: null,
      }),
    });

    await fixture.componentInstance['onItemSearch']('burger');
    fixture.detectChanges();

    expect(newOrderApi.searchItems).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ cursor: null }),
      'burger',
      'en',
    );
    expect(fixture.componentInstance['itemOptions']()).toEqual([
      { id: 'v-1', label: 'Cheeseburger', sublabel: 'Main' },
    ]);
  });

  it('marks a stopped item in its sublabel rather than hiding it from the search results', async () => {
    await render({
      searchItems: vi.fn().mockResolvedValue({
        items: [
          {
            variantId: 'v-2',
            productName: 'Sold out dish',
            category: null,
            available: false,
            trackingMode: 'BINARY',
          },
        ],
        nextCursor: null,
      }),
    });

    await fixture.componentInstance['onItemSearch']('sold');
    fixture.detectChanges();

    expect(fixture.componentInstance['itemOptions']()[0].sublabel).toContain('stop');
  });

  // ------------------------------------------------------- total reconciliation

  it('adding an item from the loaded menu reconciles the displayed total against its own price', async () => {
    await render();

    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    expect(fixture.componentInstance['basket']()).toHaveLength(1);
    expect(fixture.componentInstance['total']().subtotalMinor).toBe(30_000);
    expect(fixture.componentInstance['total']().fullyPriced).toBe(true);
    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('[data-testid="new-order-total"]')?.textContent).toContain('30');
  });

  it('two of the same item reconciles the total to twice the unit price', async () => {
    await render();
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();
    const line = fixture.componentInstance['basket']()[0];

    fixture.componentInstance['setLineQuantity'](line.lineKey, 2);
    fixture.detectChanges();

    expect(fixture.componentInstance['total']().subtotalMinor).toBe(60_000);
  });

  // -------------------------------------------------------------------- submit

  it('submits with an Idempotency-Key and navigates to the created order', async () => {
    const result: PlaceOrderResult = {
      orderId: 'order-1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    };
    const placeOrder = vi.fn().mockResolvedValue(result);
    await render({ placeOrder });
    const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(placeOrder).toHaveBeenCalledTimes(1);
    const [scope, request] = placeOrder.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(request.customerAccountId).toBe('acct-1');
    expect(request.paymentMethodCode).toBe('CASH');
    expect(request.fulfillmentMode).toBe('PICKUP');
    expect(request.lines).toEqual([
      { variantId: 'v-1', quantity: 1, modifierOptionIds: [], customerNote: null },
    ]);
    expect(navigateSpy).toHaveBeenCalledWith(['/orders', 'order-1']);
  });

  it('refuses to submit with an empty basket even when a customer is selected', async () => {
    const placeOrder = vi.fn();
    await render({ placeOrder });

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.detectChanges();

    expect(fixture.componentInstance['canSubmit']()).toBe(false);
    await fixture.componentInstance['submit']();
    expect(placeOrder).not.toHaveBeenCalled();
  });

  it('refuses to submit with no customer selected even when the basket has items', async () => {
    const placeOrder = vi.fn();
    await render({ placeOrder });

    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    expect(fixture.componentInstance['canSubmit']()).toBe(false);
    await fixture.componentInstance['submit']();
    expect(placeOrder).not.toHaveBeenCalled();
  });

  // ------------------------------------------------------------------ phone lookup

  it('looks up a phone number once nine digits have been typed, after the debounce settles', async () => {
    await render({ lookupCustomerByPhone: vi.fn().mockResolvedValue([candidate()]) });

    // Fake timers only from here — `render()` above needs the real ones to
    // settle its own initial load.
    vi.useFakeTimers();
    fixture.componentInstance['onPhoneInput']('+998901234567');
    expect(newOrderApi.lookupCustomerByPhone).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(400);
    fixture.detectChanges();

    expect(newOrderApi.lookupCustomerByPhone).toHaveBeenCalledWith(SCOPE, '+998901234567');
    expect(fixture.componentInstance['customerCandidates']()).toEqual([candidate()]);
  });
});
