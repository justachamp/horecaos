import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { Toasts } from '../../../shared/ui/toast';
import { CustomersApi, RevealedCustomerAddress } from '../../customers/customers-api';
import { ChannelView, SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
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
          onSaleNow: true,
          amountMinor: 30_000,
        },
      ],
      modifierGroupIds: [],
      commentPresets: [],
    },
  ],
  modifierGroups: [],
};

/** Row 2.1b's fixture: two presets `v-1`'s product offers. */
const PRESETS: StorefrontMenu['products'][number]['commentPresets'] = [
  { code: 'NO_ONIONS', labelRu: 'Без лука', labelUz: 'Piyozsiz', labelEn: 'No onions' },
  { code: 'EXTRA_SPICY', labelRu: 'Поострее', labelUz: 'Achchiqroq', labelEn: 'Extra spicy' },
];

/** {@link MENU}, with `v-1`'s own variant/product fields overridden — rows 2.1b/4.2g's own fixtures. */
function menuWith(
  variantOverrides: Partial<StorefrontMenu['products'][number]['variants'][number]>,
  commentPresets: StorefrontMenu['products'][number]['commentPresets'] = [],
): StorefrontMenu {
  return {
    ...MENU,
    products: [
      {
        ...MENU.products[0],
        commentPresets,
        variants: [{ ...MENU.products[0].variants[0], ...variantOverrides }],
      },
    ],
  };
}

function candidate(overrides: Partial<CustomerLookupCandidate> = {}): CustomerLookupCandidate {
  return {
    accountId: 'acct-1',
    maskedDisplayName: 'A****** K******',
    lastOrderAt: '2026-09-01T12:00:00Z',
    recentOrderCount: 2,
    ...overrides,
  };
}

function address(overrides: Partial<RevealedCustomerAddress> = {}): RevealedCustomerAddress {
  return {
    id: 'addr-1',
    label: 'Home',
    fields: { line1: 'Amir Temur 1', city: 'Tashkent', district: 'Yunusabad' },
    deliveryInstructions: null,
    latitude: null,
    longitude: null,
    coordinateSource: 'NOT_GEOCODED',
    version: 1,
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
    createCustomer: ReturnType<typeof vi.fn>;
    searchItems: ReturnType<typeof vi.fn>;
    placeOrder: ReturnType<typeof vi.fn>;
    deliveryFeeQuote: ReturnType<typeof vi.fn>;
    aggregatorEntry: ReturnType<typeof vi.fn>;
    recordCallProvenance: ReturnType<typeof vi.fn>;
  };
  let customersApi: {
    create: ReturnType<typeof vi.fn>;
    ordersPageAtLocation: ReturnType<typeof vi.fn>;
    revealAddresses: ReturnType<typeof vi.fn>;
    addAddress: ReturnType<typeof vi.fn>;
    reorderPlan: ReturnType<typeof vi.fn>;
    /** Row 5.2d: the reorder deep link's own bootstrap read — no default resolution, only the tests that set query params call it. */
    profile: ReturnType<typeof vi.fn>;
  };
  let router: Router;

  afterEach(async () => {
    vi.useRealTimers();
    // submit() fires `void this.router.navigate(...)` without awaiting it, so
    // a mocked navigate's own promise can still be settling after `await
    // submit()` returns. Draining it here — after every test, not just the
    // ones that call submit() — keeps that stray resolution from firing
    // during whichever test happens to run next once this one's own router
    // (and its spy) have already been torn down.
    await flushMicrotasks();
  });

  async function render(
    overrides: Partial<typeof newOrderApi> = {},
    customersOverrides: Partial<typeof customersApi> = {},
    channels: readonly ChannelView[] = [],
    matrices: { paymentMethods: Record<string, boolean> } = { paymentMethods: {} },
    queryParams: Readonly<Record<string, string>> = {},
  ): Promise<void> {
    newOrderApi = {
      menu: vi.fn().mockResolvedValue(MENU),
      lookupCustomerByPhone: vi.fn().mockResolvedValue([]),
      createCustomer: vi.fn().mockResolvedValue('acct-new'),
      searchItems: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      placeOrder: vi.fn(),
      deliveryFeeQuote: vi
        .fn()
        .mockResolvedValue({ available: true, feeMinor: 15_000, reasonCode: null }),
      aggregatorEntry: vi.fn(),
      recordCallProvenance: vi.fn().mockResolvedValue(undefined),
      ...overrides,
    };
    customersApi = {
      create: vi.fn(),
      ordersPageAtLocation: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      revealAddresses: vi.fn().mockResolvedValue([]),
      addAddress: vi.fn().mockResolvedValue({ id: 'addr-new' }),
      reorderPlan: vi.fn().mockResolvedValue(null),
      profile: vi.fn().mockRejectedValue(new Error('no reorder deep link in this test')),
      ...customersOverrides,
    };
    await TestBed.configureTestingModule({
      imports: [NewOrderPage],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
            options: signal([{ id: 'l1', displayName: 'Chilanzar', status: 'ACTIVE' }]),
          },
        },
        { provide: NewOrderApi, useValue: newOrderApi },
        { provide: CustomersApi, useValue: customersApi },
        {
          provide: SalesChannelsApi,
          useValue: {
            list: vi.fn().mockResolvedValue(channels),
            matrices: vi
              .fn()
              .mockResolvedValue({ ...matrices, fulfillmentModes: {}, locationIds: [] }),
          },
        },
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
      {
        variantId: 'v-1',
        quantity: 1,
        modifierOptionIds: [],
        commentPresetCodes: [],
        customerNote: null,
      },
    ]);
    expect(navigateSpy).toHaveBeenCalledWith(['/orders', 'order-1']);
  });

  it('links the placed order to the claimed call it started from, when this screen was opened from one', async () => {
    const result: PlaceOrderResult = {
      orderId: 'order-2',
      publicOrderNumber: '#0002',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    };
    const placeOrder = vi.fn().mockResolvedValue(result);
    const recordCallProvenance = vi.fn().mockResolvedValue(undefined);
    await render(
      { placeOrder, recordCallProvenance },
      {},
      [],
      { paymentMethods: {} },
      {
        callEventId: 'call-9',
      },
    );
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(recordCallProvenance).toHaveBeenCalledWith(SCOPE, 'order-2', 'call-9');
  });

  it('never calls recordCallProvenance for an order not started from a claimed call', async () => {
    const result: PlaceOrderResult = {
      orderId: 'order-3',
      publicOrderNumber: '#0003',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    };
    const placeOrder = vi.fn().mockResolvedValue(result);
    const recordCallProvenance = vi.fn().mockResolvedValue(undefined);
    // No `callEventId` query param this time — the ordinary walk-in/typed order.
    await render({ placeOrder, recordCallProvenance });
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(recordCallProvenance).not.toHaveBeenCalled();
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

  // ------------------------------------------------------------- create-on-miss (1.3a)

  it('creates a customer through the location-scoped endpoint, not the tenant-scoped one', async () => {
    const create = vi
      .fn()
      .mockRejectedValue(new Error('customersApi.create must not be called from here'));
    await render({ createCustomer: vi.fn().mockResolvedValue('acct-new') }, { create });

    fixture.componentInstance['openCreateDialog']();
    await fixture.componentInstance['onCreateSubmit']({
      phone: '+998907654321',
      displayName: 'Nodira',
    });

    expect(newOrderApi.createCustomer).toHaveBeenCalledWith(SCOPE, {
      phone: '+998907654321',
      displayName: 'Nodira',
    });
    expect(create).not.toHaveBeenCalled();
    expect(fixture.componentInstance['selectedCustomer']()).toEqual({
      accountId: 'acct-new',
      label: 'Nodira',
    });
    expect(fixture.componentInstance['createDialogOpen']()).toBe(false);
  });

  // --------------------------------------------------------- §5.4 address pane (1.3b)

  it('choosing a saved address sends its id as the delivery destination', async () => {
    const placeOrder = vi.fn().mockResolvedValue({
      orderId: 'order-1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
    await render(
      { placeOrder },
      { revealAddresses: vi.fn().mockResolvedValue([address({ id: 'addr-9' })]) },
    );
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    await flushMicrotasks();
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    expect(fixture.componentInstance['addresses']()).toEqual([address({ id: 'addr-9' })]);
    expect(fixture.componentInstance['selectedAddressId']()).toBe('addr-9');

    await fixture.componentInstance['submit']();

    expect(placeOrder).toHaveBeenCalledTimes(1);
    const [, request] = placeOrder.mock.calls[0];
    expect(request.fulfillmentMode).toBe('DELIVERY');
    expect(request.destination.customerAddressId).toBe('addr-9');
  });

  it('a delivery order with no address selected cannot be submitted', async () => {
    const placeOrder = vi.fn();
    await render({ placeOrder }, { revealAddresses: vi.fn().mockResolvedValue([]) });

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    await flushMicrotasks();
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    expect(fixture.componentInstance['canSubmit']()).toBe(false);
    await fixture.componentInstance['submit']();
    expect(placeOrder).not.toHaveBeenCalled();
  });

  it('the inline add form saves LANDMARK_ONLY when a landmark is given, and NOT_GEOCODED otherwise', async () => {
    const addAddress = vi.fn().mockResolvedValue({ id: 'addr-new' });
    await render({}, { revealAddresses: vi.fn().mockResolvedValue([]), addAddress });

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    await flushMicrotasks();
    fixture.componentInstance['startAddingAddress']();
    fixture.componentInstance['setAddressField']('line1', 'Amir Temur 5');
    fixture.componentInstance['setAddressField']('city', 'Tashkent');
    fixture.componentInstance['setAddressField']('district', 'Mirzo Ulugbek');
    fixture.componentInstance['setAddressField']('landmark', 'Рядом с аптекой');

    await fixture.componentInstance['saveNewAddress']();

    expect(addAddress).toHaveBeenCalledWith(
      SCOPE,
      'acct-1',
      expect.objectContaining({ coordinateSource: 'LANDMARK_ONLY' }),
    );
    expect(fixture.componentInstance['selectedAddressId']()).toBe('addr-new');

    addAddress.mockClear();
    fixture.componentInstance['startAddingAddress']();
    fixture.componentInstance['setAddressField']('line1', 'Amir Temur 6');
    fixture.componentInstance['setAddressField']('city', 'Tashkent');
    fixture.componentInstance['setAddressField']('district', 'Mirzo Ulugbek');

    await fixture.componentInstance['saveNewAddress']();

    expect(addAddress).toHaveBeenCalledWith(
      SCOPE,
      'acct-1',
      expect.objectContaining({ coordinateSource: 'NOT_GEOCODED' }),
    );
  });

  it('renders the branch with a «по зоне» caption on a delivery order, and the plain name on a pickup order', async () => {
    await render({}, { revealAddresses: vi.fn().mockResolvedValue([address()]) });
    fixture.componentInstance['selectCandidate'](candidate());
    fixture.detectChanges();

    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('[data-testid="new-order-branch-row"]')?.textContent).toContain(
      'Chilanzar',
    );
    expect(host.querySelector('[data-testid="new-order-branch-row"]')?.textContent).not.toContain(
      'zone',
    );

    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="new-order-branch-row"]')?.textContent).toContain(
      'by zone',
    );
  });

  it('an out-of-zone refusal is shown in words, not a generic error', async () => {
    const refusal = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, reason: 'NOT_SERVICEABLE' },
      null,
    );
    const placeOrder = vi.fn().mockRejectedValue(refusal);
    await render(
      { placeOrder },
      { revealAddresses: vi.fn().mockResolvedValue([address({ id: 'addr-9' })]) },
    );

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    await flushMicrotasks();
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(fixture.componentInstance['submitError']()).toBe(
      TestBed.inject(I18n).t('orders.newOrder.address.notServiceable'),
    );
  });

  it('a DESTINATION_NOT_LOCATED refusal reads as an address-needs-a-pin message', async () => {
    const refusal = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, reason: 'DESTINATION_NOT_LOCATED' },
      null,
    );
    const placeOrder = vi.fn().mockRejectedValue(refusal);
    await render(
      { placeOrder },
      { revealAddresses: vi.fn().mockResolvedValue([address({ id: 'addr-9' })]) },
    );

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    await flushMicrotasks();
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(fixture.componentInstance['submitError']()).toBe(
      TestBed.inject(I18n).t('orders.newOrder.address.notLocated'),
    );
  });

  it('row 4.2g: an ITEM_OUT_OF_SALE_WINDOW refusal at submit is shown in words, and the line stays in the basket', async () => {
    const refusal = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, reason: 'ITEM_OUT_OF_SALE_WINDOW' },
      null,
    );
    const placeOrder = vi.fn().mockRejectedValue(refusal);
    await render({ placeOrder });

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(fixture.componentInstance['submitError']()).toBe(
      TestBed.inject(I18n).t('orders.newOrder.menu.itemOutOfSaleWindow'),
    );
    // Not silently dropped — the basket still holds the line the operator added.
    expect(fixture.componentInstance['basket']()).toHaveLength(1);
  });

  // ------------------------------------------------------- row 2.1b/4.2g: presets and sale window

  it("row 4.2g: refuses adding a variant outside its own sale window, the same way an 86'd one is refused", async () => {
    const showSpy = vi.fn();
    await render(
      { menu: vi.fn().mockResolvedValue(menuWith({ onSaleNow: false })) },
      {},
      [],
      { paymentMethods: {} },
      {},
    );
    // Re-provide Toasts with a spy — `render`'s own default discards messages.
    TestBed.inject(Toasts).show = showSpy;

    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    expect(fixture.componentInstance['basket']()).toHaveLength(0);
    expect(showSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        message: TestBed.inject(I18n).t('orders.newOrder.menu.itemOutOfSaleWindow'),
      }),
    );
  });

  it('row 2.1b: a product with comment presets but no modifier groups still opens the dialog, and the checked preset lands on the basket line', async () => {
    await render({ menu: vi.fn().mockResolvedValue(menuWith({}, PRESETS)) });

    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    expect(fixture.componentInstance['pendingModifiers']()).not.toBeNull();
    expect(fixture.componentInstance['basket']()).toHaveLength(0);

    fixture.componentInstance['onModifierConfirm']({
      selections: [],
      commentPresetCodes: ['NO_ONIONS'],
    });
    fixture.detectChanges();

    expect(fixture.componentInstance['basket']()).toHaveLength(1);
    expect(fixture.componentInstance['basket']()[0].commentPresetCodes).toEqual(['NO_ONIONS']);
    expect(fixture.componentInstance['pendingModifiers']()).toBeNull();
  });

  it('row 2.1b: a product with neither modifier groups nor presets skips the dialog and adds straight to the basket', async () => {
    await render();

    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();

    expect(fixture.componentInstance['pendingModifiers']()).toBeNull();
    expect(fixture.componentInstance['basket']()).toHaveLength(1);
    expect(fixture.componentInstance['basket']()[0].commentPresetCodes).toEqual([]);
  });

  it("row 2.1b: submit carries each line's checked preset codes", async () => {
    const result: PlaceOrderResult = {
      orderId: 'order-9',
      publicOrderNumber: '#0009',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    };
    const placeOrder = vi.fn().mockResolvedValue(result);
    await render(
      { placeOrder, menu: vi.fn().mockResolvedValue(menuWith({}, PRESETS)) },
      {},
      [],
      { paymentMethods: {} },
      {},
    );
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.detectChanges();
    fixture.componentInstance['onModifierConfirm']({
      selections: [],
      commentPresetCodes: ['NO_ONIONS', 'EXTRA_SPICY'],
    });
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    const [, request] = placeOrder.mock.calls[0];
    expect(request.lines[0].commentPresetCodes).toEqual(['NO_ONIONS', 'EXTRA_SPICY']);
  });

  // ---------------------------------------------------------- delivery fee preview (row 1.3)

  it('previews the delivery fee for a geocoded address, debounced', async () => {
    await render(
      {},
      {
        revealAddresses: vi
          .fn()
          .mockResolvedValue([address({ id: 'addr-geo', latitude: 41.31, longitude: 69.28 })]),
      },
    );
    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });

    // Fake timers from here on — the effect's own debounce runs on the
    // signal write below, before this test ever calls flushMicrotasks
    // (which needs real timers), so switching later would let the address
    // load's own settling race a real 400ms timer this test never controls.
    vi.useFakeTimers();
    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    // Settles loadAddresses()'s own promise chain — advanceTimersByTimeAsync
    // flushes microtasks as it goes, unlike a bare await.
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    expect(newOrderApi.deliveryFeeQuote).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(400);
    fixture.detectChanges();

    expect(newOrderApi.deliveryFeeQuote).toHaveBeenCalledWith(
      SCOPE,
      { lat: 41.31, lon: 69.28 },
      'UZS',
      30_000,
    );
    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('[data-testid="new-order-delivery-fee"]')?.textContent).toContain(
      '15',
    );
  });

  it('shows no delivery fee preview for an address with no coordinate', async () => {
    await render(
      {},
      { revealAddresses: vi.fn().mockResolvedValue([address({ id: 'addr-blind' })]) },
    );
    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['setFulfillmentMode']('DELIVERY');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(newOrderApi.deliveryFeeQuote).not.toHaveBeenCalled();
    expect(fixture.componentInstance['formattedDeliveryFee']()).toBe('—');
  });

  // ----------------------------------------------------------- §5.6 promo, payment (1.3e)

  it('threads a typed promo code into the place-order request', async () => {
    const placeOrder = vi.fn().mockResolvedValue({
      orderId: 'order-1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
    await render({ placeOrder });
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['promoCode'].set('welcome10');
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    const [, request] = placeOrder.mock.calls[0];
    expect(request.promoCode).toBe('welcome10');
  });

  it('reads the offered payment methods from the operator channel’s own matrix rather than hard-coding cash', async () => {
    await render(
      {},
      {},
      [
        {
          id: 'chan-1',
          code: 'call-centre',
          systemType: 'CALL_CENTRE',
          displayName: 'Call centre',
          status: 'ACTIVE',
          pricePlaneChannelId: null,
          externallyPriced: false,
          guestOrdersAllowed: false,
          providerInstallationId: null,
          version: 1,
          locationCount: 1,
          enabledPaymentMethodCount: 2,
          enabledFulfillmentModes: ['PICKUP'],
        },
      ],
      { paymentMethods: { CASH: true, CLICK: true, PAYME: false } },
    );

    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.componentInstance['paymentMethods']()).toEqual(['CASH', 'CLICK']);
  });

  // ------------------------------------------------------------- pre-order time (row 1.3d)

  it('submits requestedFor as an ISO instant only once «Позже» is enabled', async () => {
    const placeOrder = vi.fn().mockResolvedValue({
      orderId: 'order-1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
    await render({ placeOrder });
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['togglePreOrder']();
    fixture.componentInstance['setRequestedForLocal']('2099-06-01T15:00');
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    const [, request] = placeOrder.mock.calls[0];
    expect(request.requestedFor).toBe(new Date('2099-06-01T15:00').toISOString());
    expect(request.overrideOutOfHours).toBe(false);
  });

  it('cannot submit a pre-order with no time chosen', async () => {
    await render({});
    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['togglePreOrder']();
    fixture.detectChanges();

    expect(fixture.componentInstance['canSubmit']()).toBe(false);
  });

  it('a requested time already in the past is refused inline, before any request is sent', async () => {
    const placeOrder = vi.fn();
    await render({ placeOrder });
    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['togglePreOrder']();
    fixture.componentInstance['setRequestedForLocal']('2020-01-01T10:00');
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(placeOrder).not.toHaveBeenCalled();
    expect(fixture.componentInstance['requestedForError']()).toBe(
      TestBed.inject(I18n).t('orders.newOrder.order.preOrder.mustBeFuture'),
    );
  });

  it('a closed-branch refusal the server allows overriding becomes a confirmation, not a blocking error', async () => {
    const refusal = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, reason: 'BRANCH_CLOSED_AT_REQUESTED_TIME_CONFIRM' },
      null,
    );
    const placeOrder = vi.fn().mockRejectedValueOnce(refusal).mockResolvedValueOnce({
      orderId: 'order-1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
    await render({ placeOrder });
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['togglePreOrder']();
    fixture.componentInstance['setRequestedForLocal']('2099-06-01T03:00');
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(fixture.componentInstance['outOfHoursConfirmReason']()).not.toBeNull();
    expect(fixture.componentInstance['submitError']()).toBeNull();
    expect(placeOrder).toHaveBeenCalledTimes(1);
    expect(placeOrder.mock.calls[0][1].overrideOutOfHours).toBe(false);

    // The operator presses the same submit button again — now a confirmation.
    await fixture.componentInstance['submit']();

    expect(placeOrder).toHaveBeenCalledTimes(2);
    expect(placeOrder.mock.calls[1][1].overrideOutOfHours).toBe(true);
    expect(fixture.componentInstance['outOfHoursConfirmReason']()).toBeNull();
  });

  it('a branch whose own policy refuses pre-orders into a closed slot shows a blocking error, never a confirmation', async () => {
    const refusal = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, reason: 'BRANCH_CLOSED_AT_REQUESTED_TIME' },
      null,
    );
    const placeOrder = vi.fn().mockRejectedValue(refusal);
    await render({ placeOrder });

    fixture.componentInstance['selectCandidate'](candidate());
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['togglePreOrder']();
    fixture.componentInstance['setRequestedForLocal']('2099-06-01T03:00');
    fixture.detectChanges();

    await fixture.componentInstance['submit']();

    expect(fixture.componentInstance['outOfHoursConfirmReason']()).toBeNull();
    expect(fixture.componentInstance['submitError']()).toBe(
      TestBed.inject(I18n).t('orders.newOrder.order.preOrder.closedNoOverride'),
    );
  });

  // ------------------------------------------------------------------- «Повторить» (1.3f)

  /**
   * Major fix: before this, `toggleHistory` called `customersApi.ordersPage`
   * — the BRAND-scoped route — which always 403s for LOCATION_STAFF, this
   * screen's own primary persona (its `ORDER_READ` grant never reaches past
   * `LOCATION`), and the catch block silently rendered an empty popover. This
   * proves the screen calls the LOCATION-scoped route instead, and that the
   * popover actually fills in from it.
   */
  it('the history popover reads through the LOCATION-scoped route, not the BRAND-scoped one (1.3f/1.3a, major fix)', async () => {
    const ordersPageAtLocation = vi.fn().mockResolvedValue({
      items: [
        {
          orderId: 'order-old',
          publicOrderNumber: '#0900',
          locationId: 'l1',
          fulfillmentMode: 'PICKUP',
          status: 'COMPLETED',
          paymentStatus: 'CAPTURED',
          fulfillmentStatus: 'COLLECTED',
          currency: 'UZS',
          totalMinor: 60_000,
          promisedAt: null,
          version: 1,
          placedAt: '2026-09-01T12:00:00Z',
        },
      ],
      nextCursor: null,
    });
    await render({}, { ordersPageAtLocation });

    fixture.componentInstance['selectCandidate'](candidate());
    await fixture.componentInstance['toggleHistory']();

    expect(ordersPageAtLocation).toHaveBeenCalledWith(SCOPE, 'acct-1', expect.anything());
    expect(fixture.componentInstance['historyOrders']()).toHaveLength(1);
    expect(fixture.componentInstance['historyOrders']()[0].orderId).toBe('order-old');
  });

  it('«Повторить» adds every AVAILABLE reorder-plan line to the basket', async () => {
    const reorderPlan = vi.fn().mockResolvedValue({
      orderId: 'order-old',
      publicOrderNumber: '#0900',
      locationId: 'l1',
      channelCode: 'call-centre',
      verdict: 'READY',
      currency: 'UZS',
      lines: [
        {
          lineNumber: 1,
          productName: 'Cheeseburger',
          variantName: null,
          productId: 'p-1',
          variantId: 'v-1',
          quantity: 2,
          modifierOptionIds: [],
          status: 'AVAILABLE',
          unitAmountMinor: 30_000,
          originalUnitAmountMinor: 30_000,
        },
      ],
    });
    await render(
      {},
      {
        reorderPlan,
        ordersPageAtLocation: vi.fn().mockResolvedValue({
          items: [
            {
              orderId: 'order-old',
              publicOrderNumber: '#0900',
              locationId: 'l1',
              fulfillmentMode: 'PICKUP',
              status: 'COMPLETED',
              paymentStatus: 'CAPTURED',
              fulfillmentStatus: 'COLLECTED',
              currency: 'UZS',
              totalMinor: 60_000,
              promisedAt: null,
              version: 1,
              placedAt: '2026-09-01T12:00:00Z',
            },
          ],
          nextCursor: null,
        }),
      },
    );

    fixture.componentInstance['selectCandidate'](candidate());
    await fixture.componentInstance['toggleHistory']();
    const order = fixture.componentInstance['historyOrders']()[0];

    await fixture.componentInstance['reorder'](order);

    expect(reorderPlan).toHaveBeenCalledWith(SCOPE, 'acct-1', 'order-old');
    expect(fixture.componentInstance['basket']()).toHaveLength(1);
    expect(fixture.componentInstance['basket']()[0].quantity).toBe(2);
  });

  /**
   * Row 5.2d: the customer detail pane's own «Повторить» (`customer-detail-pane.ts`'s
   * `reorder`) navigates here with `?reorderAccountId=&reorderOrderId=`
   * instead of a not-built page. `ngOnInit`'s own `bootstrapReorder` must
   * pre-select the customer from the deep link and resolve the identical
   * reorder plan the history popover's own «Повторить» does — proving the
   * operator lands with the basket already filled, not an empty screen.
   */
  it('a reorder deep link pre-selects the customer and fills the basket from the plan', async () => {
    const reorderPlan = vi.fn().mockResolvedValue({
      orderId: 'order-old',
      publicOrderNumber: '#0900',
      locationId: 'l1',
      channelCode: 'call-centre',
      verdict: 'READY',
      currency: 'UZS',
      lines: [
        {
          lineNumber: 1,
          productName: 'Cheeseburger',
          variantName: null,
          productId: 'p-1',
          variantId: 'v-1',
          quantity: 3,
          modifierOptionIds: [],
          status: 'AVAILABLE',
          unitAmountMinor: 30_000,
          originalUnitAmountMinor: 30_000,
        },
      ],
    });
    const profile = vi.fn().mockResolvedValue({ value: { displayName: 'Aziza Karimova' } });

    await render(
      {},
      { reorderPlan, profile },
      [],
      { paymentMethods: {} },
      { reorderAccountId: 'acct-1', reorderOrderId: 'order-old' },
    );

    expect(profile).toHaveBeenCalledWith(SCOPE, 'acct-1');
    expect(fixture.componentInstance['selectedCustomer']()).toEqual({
      accountId: 'acct-1',
      label: 'Aziza Karimova',
    });
    expect(reorderPlan).toHaveBeenCalledWith(SCOPE, 'acct-1', 'order-old');
    expect(fixture.componentInstance['basket']()).toHaveLength(1);
    expect(fixture.componentInstance['basket']()[0].quantity).toBe(3);
  });

  it('a stale reorder deep link (account no longer resolvable) leaves the customer picker empty rather than blocking the screen', async () => {
    const profile = vi.fn().mockRejectedValue(new Error('not found'));

    await render(
      {},
      { profile },
      [],
      { paymentMethods: {} },
      {
        reorderAccountId: 'acct-gone',
        reorderOrderId: 'order-old',
      },
    );

    expect(fixture.componentInstance['selectedCustomer']()).toBeNull();
  });

  // --------------------------------------------------------- «Заказ агрегатора» (1.3g)

  it('an aggregator entry bypasses the customer pane and records the aggregator’s own total', async () => {
    const aggregatorEntry = vi.fn().mockResolvedValue({
      orderId: 'order-agg',
      publicOrderNumber: '#0777',
      status: 'RECEIVED',
      version: 1,
      outcome: 'CREATED',
      warnings: [],
    });
    await render({ aggregatorEntry }, {}, [
      {
        id: 'chan-agg',
        code: 'uzum-tezkor',
        systemType: 'AGGREGATOR',
        displayName: 'Uzum Tezkor',
        status: 'ACTIVE',
        pricePlaneChannelId: null,
        externallyPriced: true,
        guestOrdersAllowed: false,
        providerInstallationId: 'installation-1',
        version: 1,
        locationCount: 1,
        enabledPaymentMethodCount: 0,
        enabledFulfillmentModes: [],
      },
    ]);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance['toggleAggregatorMode']();
    fixture.componentInstance['onItemSelected']({ id: 'v-1', label: 'Cheeseburger' });
    fixture.componentInstance['aggregatorExternalOrderId'].set('YE-2291-04');
    fixture.componentInstance['aggregatorTotalMinor'].set(30_000);
    fixture.detectChanges();

    expect(fixture.componentInstance['aggregatorChannelCode']()).toBe('uzum-tezkor');
    expect(fixture.componentInstance['canSubmitAggregator']()).toBe(true);

    await fixture.componentInstance['submitAggregator']();

    expect(aggregatorEntry).toHaveBeenCalledTimes(1);
    const [scope, request] = aggregatorEntry.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(request.channelCode).toBe('uzum-tezkor');
    expect(request.externalOrderId).toBe('YE-2291-04');
    expect(request.totalMinor).toBe(30_000);
  });
});
