import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { ReasonResponse, ReferenceDataApi } from '../settings/reference-data/reference-data-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { DispatchApi, DispatchResponse } from '../delivery/dispatch-api';
import { KitchenApi, KitchenEventsResponse } from '../kitchen/kitchen-api';
import { OrderActionsApi } from './order-actions-api';
import { AmendmentResponse } from './order-amendments';
import { OrderAmendmentsApi } from './order-amendments-api';
import { OrderDeliveryApi } from './order-delivery-api';
import { OrderDetailPane } from './order-detail-pane';
import {
  OrderApprovalDecision,
  OrderDeliveryResponse,
  OrderDetailResponse,
  OrderTimelineEntry,
  RevisionResponse,
} from './order-detail';
import { OrderHandoverApi } from './order-handover-api';
import { NewOrderApi } from './new-order/new-order-api';
import { OrderPosExportApi, OrderPosExportView, PosExportView } from './order-pos-export-api';
import { RejectReasonOption } from './order-reject-reason-dialog';
import { RejectReasonsApi } from './order-reject-reasons-api';
import { OrderRevealApi } from './order-reveal-api';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** See `order-queue.spec.ts`'s identical fixture for why this is a small, made-up list rather than the platform's real eight. */
const FAKE_REJECT_REASONS: readonly RejectReasonOption[] = [
  {
    code: 'ITEM_UNAVAILABLE',
    displayOrder: 1,
    requiresNote: false,
    labels: { ru: 'Нет в наличии', 'uz-Latn': 'Mavjud emas', en: 'Item unavailable' },
  },
  {
    code: 'OTHER',
    displayOrder: 8,
    requiresNote: true,
    labels: { ru: 'Другое', 'uz-Latn': 'Boshqa', en: 'Other' },
  },
];
const ORDER_PATH = '/api/v1/tenants/t1/brands/b1/locations/l1/orders/order-1';
const TIMELINE_PATH = `${ORDER_PATH}/timeline`;
const DECISIONS_PATH = `${ORDER_PATH}/decisions`;

/** Settles the constructor `effect()` → `load()` → `firstValueFrom` chain, matching `order-queue.spec.ts`'s helper. */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function detail(overrides: Partial<OrderDetailResponse> = {}): OrderDetailResponse {
  return {
    summary: {
      orderId: 'order-1',
      publicOrderNumber: '0142',
      status: 'AWAITING_APPROVAL',
      createdAt: '2026-08-30T09:00:00Z',
      totalMinor: 146_000,
      currency: 'UZS',
      feeMinor: 0,
      discountMinor: 0,
      version: 3,
      fulfillmentMode: 'DELIVERY',
      actions: [{ action: 'APPROVE' }, { action: 'REJECT' }],
    },
    subtotalMinor: 146_000,
    taxMinor: 14_600,
    lines: [
      {
        lineNumber: 1,
        productName: 'Лагман',
        quantity: 2,
        finalAmountMinor: 146_000,
        modifiers: [],
        commentPresets: [],
        lineId: 'line-1',
        hasNote: false,
      },
    ],
    warnings: [],
    currentRevision: 1,
    callbackRequested: false,
    customer: {
      displayName: 'Азиз Каримов',
      phoneMasked: '+998 90 ••• •• 42',
      customerType: 'ACCOUNT',
      hasAddress: true,
      hasDeliveryInstructions: false,
      transactionalContactAllowed: true,
      anonymized: false,
    },
    ...overrides,
  };
}

/** `AmendmentResponse`, minimally filled — the shape every built-command test below settles as. */
function amendmentResult(overrides: Partial<AmendmentResponse> = {}): AmendmentResponse {
  return {
    amendmentId: 'amendment-1',
    orderId: 'order-1',
    status: 'APPLIED',
    baseRevision: 1,
    appliedRevision: 2,
    deltaTotalMinor: 0,
    requiresApproval: false,
    expiresAt: '2026-08-30T09:15:00Z',
    amendmentVersion: 1,
    orderVersion: 4,
    commands: [],
    warnings: [],
    replayed: false,
    commandDetails: [],
    createdAt: '2026-08-30T09:00:00Z',
    createdByActorType: 'USER',
    ...overrides,
  };
}

/**
 * A path-aware `ApiClient.get` stub, since the pane fetches the order, its
 * timeline, and (wave P11, row 1.2b) the losing side of its decisions
 * straight through `ApiClient` — everything else the pane loads (delivery,
 * kitchen events, courier roster) goes through its own dedicated API class
 * and is mocked separately below.
 */
function apiGet(
  orderResult: unknown,
  timelineResult: readonly OrderTimelineEntry[] = [],
  decisionsResult: readonly OrderApprovalDecision[] = [],
): ReturnType<typeof vi.fn> {
  return vi.fn().mockImplementation((path: string) => {
    if (path === TIMELINE_PATH) {
      return of({ value: timelineResult, version: null });
    }
    if (path === DECISIONS_PATH) {
      return of({ value: decisionsResult, version: null });
    }
    if (path === ORDER_PATH) {
      return orderResult instanceof Error ? throwError(() => orderResult) : of(orderResult);
    }
    return throwError(() => new Error(`unexpected path ${path}`));
  });
}

function configure(options: {
  get?: ReturnType<typeof vi.fn>;
  actionsApi?: Partial<OrderActionsApi>;
  amendmentsApi?: Partial<OrderAmendmentsApi>;
  revealApi?: Partial<OrderRevealApi>;
  rejectReasonsApi?: Partial<RejectReasonsApi>;
  referenceDataApi?: Partial<ReferenceDataApi>;
  handoverApi?: Partial<OrderHandoverApi>;
  deliveryApi?: Partial<OrderDeliveryApi>;
  dispatchApi?: Partial<DispatchApi>;
  couriersApi?: Partial<CouriersApi>;
  kitchenApi?: Partial<KitchenApi>;
  posExportApi?: Partial<OrderPosExportApi>;
  newOrderApi?: Partial<NewOrderApi>;
  channelsApi?: Partial<SalesChannelsApi>;
  scope?: typeof FAKE_SCOPE | null;
}): void {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: CurrentLocation,
        useValue: {
          scope: () => (options.scope === undefined ? FAKE_SCOPE : options.scope),
          denied: () => options.scope === null,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: ApiClient,
        useValue: { get: options.get ?? apiGet({ value: detail(), version: 3 }) },
      },
      { provide: OrderActionsApi, useValue: options.actionsApi ?? {} },
      { provide: OrderAmendmentsApi, useValue: options.amendmentsApi ?? {} },
      { provide: OrderRevealApi, useValue: options.revealApi ?? {} },
      {
        provide: RejectReasonsApi,
        useValue: options.rejectReasonsApi ?? { list: () => Promise.resolve(FAKE_REJECT_REASONS) },
      },
      {
        provide: ReferenceDataApi,
        useValue: options.referenceDataApi ?? { list: () => Promise.resolve([]) },
      },
      // `q-order-handover-panel` (row 1.2m) is always rendered once the order
      // loads; every test in this file gets a harmless "no challenge" answer
      // unless it says otherwise, so a test unrelated to handover never has
      // to know the panel exists.
      {
        provide: OrderHandoverApi,
        useValue: options.handoverApi ?? { challenge: () => of(null) },
      },
      // The order-to-fulfilment seam (wave P11, rows 1.2e/1.2n/2.1a/1.2b):
      // every test not focused on it gets a harmless "nothing here" answer,
      // the same rule OrderHandoverApi's own comment states above.
      {
        provide: OrderDeliveryApi,
        useValue: options.deliveryApi ?? { delivery: () => Promise.resolve(null) },
      },
      { provide: DispatchApi, useValue: options.dispatchApi ?? {} },
      {
        provide: CouriersApi,
        useValue: options.couriersApi ?? { roster: () => Promise.resolve([]) },
      },
      {
        provide: KitchenApi,
        useValue:
          options.kitchenApi ??
          ({
            eventsForOrder: () =>
              Promise.resolve({ ticketId: null, ticketStatus: null, events: [] }),
          } as Partial<KitchenApi>),
      },
      // §3.11's POS row (wave P42, row 1.2i): every test not focused on it
      // gets a harmless "no POS binding here" answer, the same rule
      // OrderHandoverApi's own comment above states -- posCapable false is
      // exactly what suppresses the whole section and the amend interlock.
      {
        provide: OrderPosExportApi,
        useValue: options.posExportApi ?? {
          forOrder: () => Promise.resolve({ posCapable: false, export: null }),
        },
      },
      // Wave 10 (rows 1.2c/2.1d): ADD_LINES's own search reuses NewOrderApi,
      // CHANGE_PAYMENT_METHOD's own picker reuses SalesChannelsApi's matrix —
      // every test not focused on either gets a harmless empty answer, the
      // same rule OrderHandoverApi's own comment above states.
      {
        provide: NewOrderApi,
        useValue: options.newOrderApi ?? {
          searchItems: () => Promise.resolve({ items: [], nextCursor: null }),
        },
      },
      {
        provide: SalesChannelsApi,
        useValue: options.channelsApi ?? { list: () => Promise.resolve([]) },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render(orderId = 'order-1') {
  const fixture = TestBed.createComponent(OrderDetailPane);
  fixture.componentRef.setInput('orderId', orderId);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('OrderDetailPane: rendering the loaded order', () => {
  it('shows the order number, status and version', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.textContent).toContain('0142');
    expect(host.textContent).toContain('Awaiting approval');
    expect(host.textContent).toContain('Version 3');
  });

  it('row 2.1b: renders a line\'s comment presets as chips ahead of the note', async () => {
    configure({
      get: apiGet({
        value: detail({
          lines: [
            {
              lineNumber: 1,
              productName: 'Лагман',
              quantity: 2,
              finalAmountMinor: 146_000,
              modifiers: [],
              commentPresets: [
                { code: 'NO_ONIONS', labelRu: 'Без лука', labelUz: 'Piyozsiz', labelEn: 'No onions' },
                { code: 'EXTRA_SPICY', labelRu: 'Поострее', labelUz: 'Achchiqroq', labelEn: 'Extra spicy' },
              ],
              lineId: 'line-1',
              hasNote: false,
            },
          ],
        }),
        version: 3,
      }),
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const presets = host.querySelector('[data-testid="order-detail-line-presets"]');
    expect(presets).not.toBeNull();
    expect(presets?.textContent).toContain('No onions');
    expect(presets?.textContent).toContain('Extra spicy');
  });

  it('always shows the raw route order id, even before the fetch settles', () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = TestBed.createComponent(OrderDetailPane);
    fixture.componentRef.setInput('orderId', 'order-1');
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-id"]')?.textContent,
    ).toBe('order-1');
  });

  it('shows the denied state when the operator holds no location scope', async () => {
    configure({ scope: null });
    const fixture = await render();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-denied"]'),
    ).not.toBeNull();
  });

  it('shows the not-found state on a 404', async () => {
    configure({ get: apiGet(new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null)) });
    const fixture = await render();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-not-found"]'),
    ).not.toBeNull();
  });
});

describe('OrderDetailPane: actions render exactly from actions[] (§4.2)', () => {
  it('renders the first action as primary and the rest in overflow', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(
      host.querySelector('[data-testid="order-detail-primary-action"]')?.textContent?.trim(),
    ).toBe('Accept');
    expect(host.querySelector('[data-testid="order-detail-overflow-trigger"]')).not.toBeNull();
  });

  it('renders no primary action button — never a disabled one — for a status with an empty actions[]', async () => {
    configure({
      get: apiGet({ value: detail({ summary: { ...detail().summary, actions: [] } }), version: 3 }),
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-primary-action"]'),
    ).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-overflow-trigger"]'),
    ).toBeNull();
  });

  it('never computes availability itself: a single-ADVANCE actions[] renders that one button, labelled by target status', async () => {
    const withAdvance = detail({
      summary: {
        ...detail().summary,
        status: 'CONFIRMED',
        actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
      },
    });
    configure({ get: apiGet({ value: withAdvance, version: 3 }) });
    const fixture = await render();

    expect(
      fixture.nativeElement
        .querySelector('[data-testid="order-detail-primary-action"]')
        ?.textContent?.trim(),
    ).toBe('Send to kitchen');
  });
});

function clickPrimaryAction(fixture: { nativeElement: HTMLElement }): void {
  (
    fixture.nativeElement.querySelector(
      '[data-testid="order-detail-primary-action"]',
    ) as HTMLButtonElement
  ).click();
}

describe('OrderDetailPane: approve/reject idempotency and the lost-race render', () => {
  it('reuses the decisionId across an immediate retry after a retryable failure, and settles it on success', async () => {
    const seenIds: string[] = [];
    const approve = vi.fn().mockImplementation((..._args: unknown[]) => {
      const decisionId = _args[2] as string;
      seenIds.push(decisionId);
      return seenIds.length === 1
        ? throwError(() => new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, null))
        : of({
            orderId: 'order-1',
            status: 'CONFIRMED',
            version: 4,
            applied: true,
            effectiveDecisionId: null,
            effectiveAction: null,
          });
    });
    configure({ get: apiGet({ value: detail(), version: 3 }), actionsApi: { approve } });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(seenIds).toHaveLength(2);
    expect(seenIds[0]).toBe(seenIds[1]);
  });

  it('renders the settling decision, not a generic error, when this operator lost the race', async () => {
    const approve = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'REJECTED',
        version: 4,
        applied: false,
        effectiveDecisionId: 'someone-elses-decision',
        effectiveAction: 'REJECT',
      }),
    );
    configure({ get: apiGet({ value: detail(), version: 3 }), actionsApi: { approve } });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();

    const notice = fixture.nativeElement.querySelector('[data-testid="order-detail-notice"]');
    expect(notice?.textContent).toContain('Already rejected');
    expect(notice?.textContent).toContain('another operator');
  });

  it('re-reads the order after a settled decision, rather than trusting the response alone', async () => {
    const approve = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'CONFIRMED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    const get = apiGet({ value: detail(), version: 3 });
    configure({ get, actionsApi: { approve } });
    const fixture = await render();

    const callsBefore = get.mock.calls.filter((c: unknown[]) => c[0] === ORDER_PATH).length;
    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();

    const callsAfter = get.mock.calls.filter((c: unknown[]) => c[0] === ORDER_PATH).length;
    expect(callsAfter).toBeGreaterThan(callsBefore);
  });
});

describe('OrderDetailPane: reject dialog is the curated picker (wave 24)', () => {
  it('fetches GET .../orders/reject-reasons, opens the picker (not the free-text dialog), and submits the chosen code', async () => {
    const reject = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'REJECTED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    const rejectReasonsList = vi.fn().mockResolvedValue(FAKE_REJECT_REASONS);
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      actionsApi: { reject },
      rejectReasonsApi: { list: rejectReasonsList },
    });
    const fixture = await render();
    const host = fixture.nativeElement as HTMLElement;

    // REJECT is actions[1] in the fixture — the overflow menu, not primary.
    (
      host.querySelector('[data-testid="order-detail-overflow-trigger"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-detail-action-REJECT"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(rejectReasonsList).toHaveBeenCalledWith(FAKE_SCOPE);
    expect(host.querySelector('[data-testid="order-reason-dialog"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-reject-reason-dialog"]')).not.toBeNull();

    (
      host.querySelector(
        '[data-testid="order-reject-reason-option-ITEM_UNAVAILABLE"]',
      ) as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-reject-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(reject).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'order-1',
      expect.any(String),
      'ITEM_UNAVAILABLE',
      undefined,
    );
  });

  it('OTHER refuses to submit with no note, then sends code and note once one is entered', async () => {
    const reject = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'REJECTED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      actionsApi: { reject },
      rejectReasonsApi: { list: () => Promise.resolve(FAKE_REJECT_REASONS) },
    });
    const fixture = await render();
    const host = fixture.nativeElement as HTMLElement;

    (
      host.querySelector('[data-testid="order-detail-overflow-trigger"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-detail-action-REJECT"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    (
      host.querySelector('[data-testid="order-reject-reason-option-OTHER"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-reject-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(reject).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="order-reject-reason-note-required"]')).not.toBeNull();

    const noteField = host.querySelector(
      '[data-testid="order-reject-reason-note"]',
    ) as HTMLTextAreaElement;
    noteField.value = 'подозрительный заказ';
    noteField.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-reject-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(reject).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'order-1',
      expect.any(String),
      'OTHER',
      'подозрительный заказ',
    );
  });
});

describe('OrderDetailPane: STALE_VERSION never auto-retries (§4.1)', () => {
  it('re-reads the order and says it changed, without resubmitting the mutation', async () => {
    const advance = vi
      .fn()
      .mockReturnValue(
        throwError(
          () =>
            new ApiError(
              ApiErrorCode.STALE_VERSION,
              409,
              { status: 409, expected: 3, actual: 5 },
              null,
            ),
        ),
      );
    const confirmed = detail({
      summary: {
        ...detail().summary,
        status: 'CONFIRMED',
        actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
      },
    });
    const get = apiGet({ value: confirmed, version: 3 });
    configure({ get, actionsApi: { advance } });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(advance).toHaveBeenCalledTimes(1);
    const notice = fixture.nativeElement.querySelector('[data-testid="order-detail-notice"]');
    expect(notice?.textContent).toContain('changed');

    // Re-read, not a retry: the order path was fetched again, `advance` was not called twice.
    const orderCalls = get.mock.calls.filter((c: unknown[]) => c[0] === ORDER_PATH).length;
    expect(orderCalls).toBeGreaterThan(1);
    expect(advance).toHaveBeenCalledTimes(1);
  });
});

describe('OrderDetailPane: money reconciliation (§1.3)', () => {
  it('renders the total normally when the line sum matches the subtotal', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-money-error"]'),
    ).toBeNull();
    // Money grouping uses U+00A0 (non-breaking space), never a comma — money.ts's own rule.
    expect(fixture.nativeElement.textContent).toContain('146 000');
  });

  it('renders an explicit error instead of a wrong total when the line sum disagrees with the subtotal', async () => {
    const corrupted = detail({
      lines: [
        {
          lineNumber: 1,
          productName: 'Лагман',
          quantity: 1,
          finalAmountMinor: 100_000,
          modifiers: [],
          commentPresets: [],
          lineId: 'line-1',
          hasNote: false,
        },
      ],
      subtotalMinor: 146_000,
    });
    configure({ get: apiGet({ value: corrupted, version: 3 }) });
    const fixture = await render();

    const error = fixture.nativeElement.querySelector('[data-testid="order-detail-money-error"]');
    expect(error).not.toBeNull();
    expect(error?.textContent).toContain('100 000');
    expect(error?.textContent).toContain('146 000');
  });
});

/**
 * H4: `OperationsOrderController.OrderSummaryResponse` has carried
 * `feeMinor`/`discountMinor` since 2026-09-11 (checkout's fee, applied
 * promotions), but the Money panel only ever rendered subtotal, tax and
 * total -- silently dropping the two figures the five-row rule requires,
 * even once the backend started sending them on every read.
 */
describe('OrderDetailPane: fee and discount render in the money panel (H4)', () => {
  it('renders the fee and discount rows the backend sends, not just subtotal/tax/total', async () => {
    const withFeeAndDiscount = detail({
      summary: { ...detail().summary, feeMinor: 5_000, discountMinor: 3_000 },
    });
    configure({ get: apiGet({ value: withFeeAndDiscount, version: 3 }) });
    const fixture = await render();

    const fee = fixture.nativeElement.querySelector('[data-testid="order-detail-money-fee"]');
    const discount = fixture.nativeElement.querySelector(
      '[data-testid="order-detail-money-discount"]',
    );
    expect(fee?.textContent).toContain('5 000');
    expect(discount?.textContent).toContain('3 000');
    // A discount reduces the total -- rendered with a minus, not a bare positive figure.
    expect(discount?.textContent).toContain('−');
  });

  it('still renders the fee/discount rows at zero -- the five-row rule always shows all five', async () => {
    // The base `detail()` fixture carries feeMinor: 0, discountMinor: 0.
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-money-fee"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-money-discount"]'),
    ).not.toBeNull();
  });
});

describe('OrderDetailPane: PII reveal is a separate audited call (§1.5)', () => {
  it('calls the reveal endpoint on click and shows the returned number', async () => {
    const revealPhone = vi.fn().mockReturnValue(of({ phone: '+998901234567' }));
    configure({ get: apiGet({ value: detail(), version: 3 }), revealApi: { revealPhone } });
    const fixture = await render();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="order-detail-phone-reveal"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(revealPhone).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.textContent).toContain('+998901234567');
  });

  it('copy makes its own independent reveal call rather than reusing an already-revealed value', async () => {
    const revealPhone = vi.fn().mockReturnValue(of({ phone: '+998901234567' }));
    configure({ get: apiGet({ value: detail(), version: 3 }), revealApi: { revealPhone } });
    const fixture = await render();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="order-detail-phone-reveal"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="order-detail-phone-copy"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(revealPhone).toHaveBeenCalledTimes(2);
    const purposes = revealPhone.mock.calls.map((c: unknown[]) => c[2]);
    expect(new Set(purposes).size).toBe(2);
  });

  it('does not reveal the address until the reveal control is used', async () => {
    const revealAddress = vi.fn().mockReturnValue(of({ latitude: 41.3, longitude: 69.2 }));
    configure({ get: apiGet({ value: detail(), version: 3 }), revealApi: { revealAddress } });
    const fixture = await render();

    expect(revealAddress).not.toHaveBeenCalled();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-address-reveal"]'),
    ).not.toBeNull();
  });
});

describe('OrderDetailPane: timeline (§3.10)', () => {
  it('flags a gap in the sequence rather than hiding it', async () => {
    const entries: OrderTimelineEntry[] = [
      {
        sequence: 1,
        fromStatus: 'RECEIVED',
        toStatus: 'AWAITING_APPROVAL',
        trigger: 'CHECKOUT',
        actorType: 'SYSTEM',
        occurredAt: '2026-08-30T09:00:00Z',
      },
      {
        sequence: 3,
        fromStatus: 'AWAITING_APPROVAL',
        toStatus: 'CONFIRMED',
        trigger: 'APPROVAL_DECISION',
        actorType: 'USER',
        occurredAt: '2026-08-30T09:05:00Z',
      },
    ];
    configure({ get: apiGet({ value: detail(), version: 3 }, entries) });
    const fixture = await render();

    // `q-timeline` (row X.26) renders the gap row now — the order lane's
    // hand-rolled `<ol>` is gone, but the gap notice's own text is unchanged.
    const gap = fixture.nativeElement.querySelector('[data-testid="q-timeline-gap"]');
    expect(gap?.textContent).toContain('2');
  });

  it('renders a transition through q-timeline — row X.26, the same component the audit list uses', async () => {
    const entries: OrderTimelineEntry[] = [
      {
        sequence: 1,
        fromStatus: 'RECEIVED',
        toStatus: 'CONFIRMED',
        trigger: 'APPROVAL_DECISION',
        actorType: 'USER',
        occurredAt: '2026-08-30T09:00:00Z',
      },
    ];
    configure({ get: apiGet({ value: detail(), version: 3 }, entries) });
    const fixture = await render();

    const row = fixture.nativeElement.querySelector(
      '[data-testid="order-detail-timeline"] [data-testid="q-timeline-entry"]',
    );
    expect(row?.textContent).toContain('Received');
    expect(row?.textContent).toContain('Confirmed');
    expect(row?.textContent).toContain('Approval decision');
  });

  it('renders the production and delivery lanes as built (wave P11, row 1.2b) — see that wave’s own describe block for the elapsed-duration detail', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-detail-timeline-production"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="order-detail-timeline-delivery"]')).not.toBeNull();
    expect(host.textContent).not.toContain('not built yet');
  });
});

describe('OrderDetailPane: lifecycle rail (row X.33)', () => {
  function lifecycleSteps(fixture: Awaited<ReturnType<typeof render>>): NodeListOf<HTMLElement> {
    return (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="order-detail-lifecycle"] [data-testid="q-steps-item"]',
    );
  }

  it('marks the order’s current happy-path status current on the rail', async () => {
    configure({
      get: apiGet({
        value: detail({ summary: { ...detail().summary, status: 'PREPARING' } }),
        version: 3,
      }),
    });
    const fixture = await render();

    const steps = lifecycleSteps(fixture);
    expect(steps).toHaveLength(6);
    expect(steps[2].className).toContain('q-steps__item--current');
    expect(steps[2].textContent).toContain('Preparing');
  });

  it('appends a cancellation to the happy-path steps the timeline shows the order having reached', async () => {
    const entries: OrderTimelineEntry[] = [
      {
        sequence: 1,
        fromStatus: 'RECEIVED',
        toStatus: 'CONFIRMED',
        trigger: 'APPROVAL_DECISION',
        actorType: 'USER',
        occurredAt: '2026-08-30T09:00:00Z',
      },
    ];
    configure({
      get: apiGet(
        { value: detail({ summary: { ...detail().summary, status: 'CANCELLED' } }), version: 3 },
        entries,
      ),
    });
    const fixture = await render();

    const steps = lifecycleSteps(fixture);
    expect(steps).toHaveLength(3);
    expect(steps[2].className).toContain('q-steps__item--danger');
  });

  it('does not render the rail before the order has loaded', async () => {
    configure({ get: apiGet(new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, null)) });
    const fixture = await render();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="order-detail-lifecycle"]',
      ),
    ).toBeNull();
  });
});

function reason(overrides: Partial<ReasonResponse> = {}): ReasonResponse {
  return {
    id: 'reason-1',
    kind: 'CANCELLATION',
    systemCategory: 'ITEM_UNAVAILABLE',
    internalName: 'Нет товара',
    stockDisposition: 'WRITE_OFF',
    liabilityParty: 'TENANT',
    customerRefund: 'FULL',
    allowedFulfillmentModes: null,
    customerTexts: { ru: 'Извините, блюдо закончилось' },
    status: 'ACTIVE',
    version: 1,
    updatedAt: '2026-08-30T09:00:00Z',
    ...overrides,
  };
}

const REVISIONS_PATH = `${ORDER_PATH}/revisions`;

describe('OrderDetailPane: the whole OutcomeResponse, not the 13-of-19 slice (row 1.2)', () => {
  it('renders the outcome band — kind, category, disposition, liable party, refund and when', async () => {
    const cancelled = detail({
      summary: { ...detail().summary, status: 'CANCELLED', actions: [] },
      outcome: {
        kind: 'CANCELLED',
        systemCategory: 'ITEM_UNAVAILABLE',
        reasonId: 'reason-1',
        reasonVersion: 1,
        stockDisposition: 'WRITE_OFF',
        liabilityParty: 'TENANT',
        customerRefund: 'FULL',
        reservationCommitted: true,
        occurredAt: '2026-08-30T10:00:00Z',
      },
    });
    configure({ get: apiGet({ value: cancelled, version: 4 }) });
    const fixture = await render();

    const band = fixture.nativeElement.querySelector('[data-testid="order-detail-outcome"]');
    expect(band?.textContent).toContain('Cancelled');
    expect(band?.textContent).toContain('Item unavailable');
    expect(band?.textContent).toContain('Written off');
    expect(band?.textContent).toContain('At the branch’s cost');
    expect(band?.textContent).toContain('Full refund');
  });

  it('renders no outcome band for an order that has not ended', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();

    expect(fixture.nativeElement.querySelector('[data-testid="order-detail-outcome"]')).toBeNull();
  });

  it('renders kitchenNote, callback state and the cash/change-due pair', async () => {
    const withDetails = detail({
      kitchenNote: 'Без лука',
      callbackRequested: true,
      callbackResolvedAt: null,
      cashTenderedExpectedMinor: 200_000,
      changeDueMinor: 54_000,
    });
    configure({ get: apiGet({ value: withDetails, version: 3 }) });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-detail-kitchen-note"]')?.textContent).toContain(
      'Без лука',
    );
    expect(host.querySelector('[data-testid="order-detail-callback"]')?.textContent).toContain(
      'not yet resolved',
    );
    // Money grouping uses U+00A0 (non-breaking space), never a comma — money.ts's own rule.
    expect(host.querySelector('[data-testid="order-detail-cash-tendered"]')?.textContent).toContain(
      '200 000',
    );
    expect(host.querySelector('[data-testid="order-detail-change-due"]')?.textContent).toContain(
      '54 000',
    );
  });

  it('renders every warning rather than dropping them', async () => {
    const withWarnings = detail({ warnings: ['PRICE_CHANGED_SINCE_CHECKOUT', 'ITEM_SUBSTITUTED'] });
    configure({ get: apiGet({ value: withWarnings, version: 3 }) });
    const fixture = await render();

    const warnings = fixture.nativeElement.querySelectorAll('[data-testid="order-detail-warning"]');
    expect(warnings).toHaveLength(2);
    expect(warnings[0].textContent).toContain('PRICE_CHANGED_SINCE_CHECKOUT');
  });

  it('renders createdBy and acceptedBy with when the order has been accepted', async () => {
    const attributed = detail({
      createdByActorType: 'CUSTOMER',
      createdByActorId: null,
      acceptedByActorType: 'USER',
      acceptedByActorId: 'operator-7',
      acceptedAt: '2026-08-30T09:02:00Z',
    });
    configure({ get: apiGet({ value: attributed, version: 3 }) });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-detail-created-by"]')?.textContent).toContain(
      'CUSTOMER',
    );
    expect(host.querySelector('[data-testid="order-detail-accepted-by"]')?.textContent).toContain(
      'operator-7',
    );
  });

  it('gap map 9.2d: prefers the resolved display name over the raw actor id', async () => {
    const attributed = detail({
      createdByActorType: 'USER',
      createdByActorId: 'operator-7',
      createdByDisplayName: 'Шахзод Каримов',
      acceptedByActorType: 'USER',
      acceptedByActorId: 'operator-9',
      acceptedByDisplayName: 'Дилноза Юсупова',
      acceptedAt: '2026-08-30T09:02:00Z',
    });
    configure({ get: apiGet({ value: attributed, version: 3 }) });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const createdBy = host.querySelector('[data-testid="order-detail-created-by"]')?.textContent;
    expect(createdBy).toContain('Шахзод Каримов');
    expect(createdBy).not.toContain('operator-7');
    const acceptedBy = host.querySelector('[data-testid="order-detail-accepted-by"]')?.textContent;
    expect(acceptedBy).toContain('Дилноза Юсупова');
    expect(acceptedBy).not.toContain('operator-9');
  });

  it('9.2d: falls back to the raw type/id pair when no display name was resolved', async () => {
    const attributed = detail({
      createdByActorType: 'USER',
      createdByActorId: 'operator-7',
      createdByDisplayName: null,
    });
    configure({ get: apiGet({ value: attributed, version: 3 }) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-created-by"]')?.textContent,
    ).toContain('operator-7');
  });

  it('renders an honest "not yet accepted" rather than a blank cell', async () => {
    const unaccepted = detail({
      acceptedByActorType: null,
      acceptedByActorId: null,
      acceptedAt: null,
    });
    configure({ get: apiGet({ value: unaccepted, version: 3 }) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-accepted-by"]')?.textContent,
    ).toContain('Not yet accepted');
  });
});

describe('OrderDetailPane: revisions, fetched on demand (§3.9, row 1.2p)', () => {
  it('does not fetch GET .../revisions until the operator asks', async () => {
    const get = apiGet({ value: detail(), version: 3 });
    configure({ get });
    await render();

    expect(get.mock.calls.some((c: unknown[]) => c[0] === REVISIONS_PATH)).toBe(false);
  });

  it('fetches and renders the revision chain once toggled open', async () => {
    const revisions: readonly RevisionResponse[] = [
      {
        revision: 1,
        source: 'CHECKOUT',
        currency: 'UZS',
        subtotalMinor: 146_000,
        taxMinor: 14_600,
        discountMinor: 0,
        feeMinor: 0,
        totalMinor: 146_000,
        deltaTotalMinor: 0,
        createdByActorType: 'CUSTOMER',
        createdAt: '2026-08-30T09:00:00Z',
      },
      {
        revision: 2,
        source: 'AMENDMENT',
        amendmentId: 'amend-1',
        currency: 'UZS',
        subtotalMinor: 166_000,
        taxMinor: 16_600,
        discountMinor: 0,
        feeMinor: 0,
        totalMinor: 166_000,
        deltaTotalMinor: 20_000,
        createdByActorType: 'USER',
        createdByActorId: 'operator-7',
        createdAt: '2026-08-30T09:10:00Z',
      },
    ];
    const get = vi.fn().mockImplementation((path: string) => {
      if (path === REVISIONS_PATH) {
        return of({ value: revisions, version: null });
      }
      if (path === TIMELINE_PATH) {
        return of({ value: [], version: null });
      }
      if (path === ORDER_PATH) {
        return of({ value: detail({ currentRevision: 2 }), version: 3 });
      }
      return throwError(() => new Error(`unexpected path ${path}`));
    });
    configure({ get });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-revisions-toggle"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const rows = host.querySelectorAll('[data-testid="order-detail-revision-row"]');
    expect(rows).toHaveLength(2);
    expect(rows[1].textContent).toContain('166 000');
    expect(rows[1].textContent).toContain('20 000');
  });

  it('shows an error rather than a stale or empty table when the revisions call fails', async () => {
    const get = apiGet({ value: detail(), version: 3 });
    get.mockImplementation((path: string) => {
      if (path === REVISIONS_PATH) {
        return throwError(() => new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null));
      }
      if (path === TIMELINE_PATH) {
        return of({ value: [], version: null });
      }
      if (path === ORDER_PATH) {
        return of({ value: detail(), version: 3 });
      }
      return throwError(() => new Error(`unexpected path ${path}`));
    });
    configure({ get });
    const fixture = await render();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="order-detail-revisions-toggle"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-revisions-error"]'),
    ).not.toBeNull();
  });
});

describe('OrderDetailPane: the handover panel is on the page (row 1.2m)', () => {
  it('mounts q-order-handover-panel once the order has loaded', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-handover-panel"]'),
    ).not.toBeNull();
  });
});

describe('OrderDetailPane: cancel past CONFIRMED uses a registry reason, never free text (§4.5, row 1.2k)', () => {
  function confirmedWithCancel(): OrderDetailResponse {
    return detail({
      summary: { ...detail().summary, status: 'CONFIRMED', actions: [{ action: 'CANCEL' }] },
    });
  }

  it('fetches active CANCELLATION reasons before opening the picker, never the free-text dialog', async () => {
    const list = vi.fn().mockResolvedValue([reason()]);
    configure({
      get: apiGet({ value: confirmedWithCancel(), version: 3 }),
      referenceDataApi: { list },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(list).toHaveBeenCalledWith(FAKE_SCOPE, 'CANCELLATION');
    expect(fixture.nativeElement.querySelector('[data-testid="order-reason-dialog"]')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-outcome-reason-dialog"]'),
    ).not.toBeNull();
  });

  it('shows the reason’s consequences and submits reasonId/reasonCode, not free text', async () => {
    const cancel = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'CANCELLED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configure({
      get: apiGet({ value: confirmedWithCancel(), version: 3 }),
      actionsApi: { cancelWithReason: cancel },
      referenceDataApi: { list: () => Promise.resolve([reason()]) },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="order-outcome-reason-consequences"]')).toBeNull();
    (
      host.querySelector('[data-testid="order-outcome-reason-option-reason-1"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(
      host.querySelector('[data-testid="order-outcome-reason-consequences"]')?.textContent,
    ).toContain('Written off');

    (
      host.querySelector('[data-testid="order-outcome-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(cancel).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'order-1',
      3,
      'reason-1',
      'ITEM_UNAVAILABLE',
      undefined,
    );
  });

  it('the cancel dialog renders the provider-cancellation outcome once the cascade settles (gap map row 1.2g)', async () => {
    const cancel = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'CANCELLED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
        deliveryCancellation: { outcome: 'PROVIDER_UNCERTAIN', providerType: 'noor-delivery' },
      }),
    );
    configure({
      get: apiGet({ value: confirmedWithCancel(), version: 3 }),
      actionsApi: { cancelWithReason: cancel },
      referenceDataApi: { list: () => Promise.resolve([reason()]) },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    (
      host.querySelector('[data-testid="order-outcome-reason-option-reason-1"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-outcome-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const notice = host.querySelector('[data-testid="order-detail-notice"]');
    expect(notice?.textContent).toContain(
      'The courier provider’s cancellation could not be confirmed — check manually.',
    );
  });

  it('a cancellation with nothing to tell a courier about renders no delivery notice at all', async () => {
    const cancel = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'CANCELLED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
        deliveryCancellation: { outcome: 'NOTHING_TO_CANCEL', providerType: null },
      }),
    );
    configure({
      get: apiGet({ value: confirmedWithCancel(), version: 3 }),
      actionsApi: { cancelWithReason: cancel },
      referenceDataApi: { list: () => Promise.resolve([reason()]) },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    (
      host.querySelector('[data-testid="order-outcome-reason-option-reason-1"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-outcome-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-detail-notice"]')).toBeNull();
  });
});

describe('OrderDetailPane: the override dialog restores an earlier status (ADR 0019 amendment, ADR 0110, wave 9 row 1.1h)', () => {
  function readyWithOverride(): OrderDetailResponse {
    return detail({
      summary: {
        ...detail().summary,
        status: 'READY',
        actions: [{ action: 'OVERRIDE', targetStatus: 'PREPARING' }],
      },
    });
  }

  it('fetches active CANCELLATION reasons and names the target status in the title, never a free-text field', async () => {
    const list = vi.fn().mockResolvedValue([reason()]);
    configure({
      get: apiGet({ value: readyWithOverride(), version: 3 }),
      referenceDataApi: { list },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(list).toHaveBeenCalledWith(FAKE_SCOPE, 'CANCELLATION');
    expect(host.querySelector('[data-testid="order-outcome-reason-dialog"]')).not.toBeNull();
    expect(host.querySelector('.outcome-dialog__title')?.textContent).toContain('Preparing');
    expect(host.querySelector('[data-testid="order-reason-dialog"]')).toBeNull();
  });

  it('submits the mandatory registry reason with the target status the clicked action named and the expected version', async () => {
    const override = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'PREPARING',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configure({
      get: apiGet({ value: readyWithOverride(), version: 3 }),
      actionsApi: { override },
      referenceDataApi: { list: () => Promise.resolve([reason()]) },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    (
      host.querySelector('[data-testid="order-outcome-reason-option-reason-1"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-outcome-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(override).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 'PREPARING', 3, 'reason-1');
  });
});

describe('OrderDetailPane: completion names the fulfilment mode’s own reason (§4.6, row 1.2j)', () => {
  it('completes without a dialog when exactly one reason is valid for the mode', async () => {
    const complete = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'COMPLETED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    const pickup = detail({
      summary: {
        ...detail().summary,
        status: 'READY',
        fulfillmentMode: 'PICKUP',
        actions: [{ action: 'COMPLETE' }],
      },
    });
    configure({
      get: apiGet({ value: pickup, version: 3 }),
      actionsApi: { complete },
      referenceDataApi: {
        list: (scope, kind) =>
          Promise.resolve(
            kind === 'COMPLETION'
              ? [
                  reason({
                    id: 'pickup-reason',
                    systemCategory: 'COLLECTED_BY_CUSTOMER',
                    allowedFulfillmentModes: ['PICKUP'],
                  }),
                ]
              : [],
          ),
      },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();

    expect(complete).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, 'pickup-reason');
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-outcome-reason-dialog"]'),
    ).toBeNull();
  });

  it('opens a picker naming DELIVERED_OWN_COURIER and DELIVERED_PARTNER_COURIER separately when both are valid', async () => {
    const complete = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'COMPLETED',
        version: 4,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    const delivery = detail({
      summary: {
        ...detail().summary,
        status: 'FULFILLING',
        fulfillmentMode: 'DELIVERY',
        actions: [{ action: 'COMPLETE' }],
      },
    });
    configure({
      get: apiGet({ value: delivery, version: 3 }),
      actionsApi: { complete },
      referenceDataApi: {
        list: () =>
          Promise.resolve([
            reason({
              id: 'own-courier',
              systemCategory: 'DELIVERED_OWN_COURIER',
              internalName: 'Доставлен своим курьером',
              allowedFulfillmentModes: ['DELIVERY'],
            }),
            reason({
              id: 'partner-courier',
              systemCategory: 'DELIVERED_PARTNER_COURIER',
              internalName: 'Доставлен сторонней службой',
              allowedFulfillmentModes: ['DELIVERY'],
            }),
          ]),
      },
    });
    const fixture = await render();

    clickPrimaryAction(fixture);
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="order-outcome-reason-dialog"]')).not.toBeNull();
    expect(complete).not.toHaveBeenCalled();

    (
      host.querySelector(
        '[data-testid="order-outcome-reason-option-partner-courier"]',
      ) as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-outcome-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(complete).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, 'partner-courier');
  });

  it('prefers COMPLETE over the redundant ADVANCE(COMPLETED) entry and hides the duplicate button', async () => {
    const pickupWithBoth = detail({
      summary: {
        ...detail().summary,
        status: 'READY',
        fulfillmentMode: 'PICKUP',
        actions: [{ action: 'ADVANCE', targetStatus: 'COMPLETED' }, { action: 'COMPLETE' }],
      },
    });
    configure({
      get: apiGet({ value: pickupWithBoth, version: 3 }),
      referenceDataApi: { list: () => Promise.resolve([]) },
    });
    const fixture = await render();

    // Exactly one primary action button, and no overflow — the duplicate ADVANCE entry is hidden.
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-overflow-trigger"]'),
    ).toBeNull();
    expect(
      fixture.nativeElement
        .querySelector('[data-testid="order-detail-primary-action"]')
        ?.textContent?.trim(),
    ).toBe('Handed over');
  });
});

describe('OrderDetailPane: §3.6 Комментарии — the amendment client (ADR 0039, wave P10)', () => {
  it('wires SET_KITCHEN_NOTE from the row’s own edit affordance', async () => {
    const setKitchenNote = vi.fn().mockReturnValue(of(amendmentResult()));
    configure({
      get: apiGet({
        value: detail({
          kitchenNote: 'Без лука',
          summary: { ...detail().summary, status: 'CONFIRMED' },
        }),
        version: 3,
      }),
      amendmentsApi: { setKitchenNote },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-kitchen-note-edit"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const textarea = host.querySelector(
      '[data-testid="order-note-dialog-textarea"]',
    ) as HTMLTextAreaElement;
    expect(textarea.value).toBe('Без лука');
    textarea.value = 'Острее, без лука';
    textarea.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="order-note-dialog-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setKitchenNote).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, 'Острее, без лука');
  });

  it('wires SET_CALLBACK_REQUESTED as an instant toggle, with no dialog', async () => {
    const setCallbackRequested = vi.fn().mockReturnValue(of(amendmentResult()));
    configure({
      get: apiGet({ value: detail({ callbackRequested: false }), version: 3 }),
      amendmentsApi: { setCallbackRequested },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-callback-toggle"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(setCallbackRequested).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, true);
    expect(host.querySelector('[data-testid="order-note-dialog"]')).toBeNull();
  });

  it('wires SET_CASH_TENDERED from the row’s own edit affordance', async () => {
    const setCashTendered = vi.fn().mockReturnValue(of(amendmentResult()));
    configure({
      get: apiGet({ value: detail({ cashTenderedExpectedMinor: 200_000 }), version: 3 }),
      amendmentsApi: { setCashTendered },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-cash-tendered-edit"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const input = host.querySelector(
      '[data-testid="order-cash-tendered-dialog-amount"] input',
    ) as HTMLInputElement;
    input.value = '250 000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-cash-tendered-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(setCashTendered).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, 250_000);
  });

  it('wires SET_COURIER_NOTE from the row’s own add affordance (ADR 0113)', async () => {
    const setCourierNote = vi.fn().mockReturnValue(of(amendmentResult()));
    configure({ get: apiGet({ value: detail(), version: 3 }), amendmentsApi: { setCourierNote } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-courier-note-add"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const textarea = host.querySelector(
      '[data-testid="order-note-dialog-textarea"]',
    ) as HTMLTextAreaElement;
    textarea.value = 'Позвонить у ворот';
    textarea.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="order-note-dialog-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setCourierNote).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, 'Позвонить у ворот');
  });

  it('wires SET_INTERNAL_NOTE from the row’s own add affordance (ADR 0113)', async () => {
    const setInternalNote = vi.fn().mockReturnValue(of(amendmentResult()));
    configure({ get: apiGet({ value: detail(), version: 3 }), amendmentsApi: { setInternalNote } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-internal-note-add"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const textarea = host.querySelector(
      '[data-testid="order-note-dialog-textarea"]',
    ) as HTMLTextAreaElement;
    textarea.value = 'VIP клиент';
    textarea.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="order-note-dialog-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setInternalNote).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, 'VIP клиент');
  });

  it('shows the acknowledgeable CASH_TENDERED_INSUFFICIENT notice when a later amendment raises the total', async () => {
    const setCashTendered = vi
      .fn()
      .mockReturnValue(of(amendmentResult({ warnings: ['CASH_TENDERED_INSUFFICIENT'] })));
    configure({
      get: apiGet({ value: detail({ cashTenderedExpectedMinor: 100_000 }), version: 3 }),
      amendmentsApi: { setCashTendered },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-detail-cash-tendered-warning"]')).toBeNull();

    (
      host.querySelector('[data-testid="order-detail-cash-tendered-edit"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-cash-tendered-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    // Acknowledgeable, never a refusal (ADR 0039): the customer can hand over more.
    const notice = host.querySelector('[data-testid="order-detail-cash-tendered-warning"]');
    expect(notice?.textContent).toContain('short of the total');

    (
      host.querySelector(
        '[data-testid="order-detail-cash-tendered-warning-ack"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-detail-cash-tendered-warning"]')).toBeNull();
  });

  it('AMEND opens the eleven-command menu the row’s own edit affordances use', async () => {
    configure({
      get: apiGet({
        value: detail({
          summary: { ...detail().summary, status: 'CONFIRMED', actions: [{ action: 'AMEND' }] },
        }),
        version: 3,
      }),
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(
      host.querySelector('[data-testid="order-detail-primary-action"]')?.textContent?.trim(),
    ).toBe('Amend');
    (
      host.querySelector('[data-testid="order-detail-primary-action"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-amend-menu"]')).not.toBeNull();
    // Wave 10 (rows 1.2c/2.1d): six of the seven financial commands are built
    // and do reach this menu now — only REMOVE_LINES, still refused by name,
    // never does. See `order-amend-menu.spec.ts` for the exhaustive check.
    expect(host.querySelector('[data-testid="order-amend-menu-ADD_LINES"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="order-amend-menu-REMOVE_LINES"]')).toBeNull();

    (
      host.querySelector('[data-testid="order-amend-menu-SET_KITCHEN_NOTE"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-note-dialog"]')).not.toBeNull();
  });

  it('wires ADD_LINES: search via NewOrderApi, propose applyImmediately:false, then confirm-and-apply', async () => {
    const addLines = vi.fn().mockReturnValue(
      of(
        amendmentResult({
          status: 'PRICED',
          deltaTotalMinor: 18_000,
          requiresApproval: false,
          amendmentVersion: 1,
        }),
      ),
    );
    const confirm = vi.fn().mockReturnValue(of(amendmentResult({ status: 'APPLIED' })));
    const searchItems = vi.fn().mockResolvedValue({
      items: [
        {
          variantId: 'variant-1',
          productName: 'Лагман',
          category: 'Горячие блюда',
          available: true,
          trackingMode: null,
        },
      ],
      nextCursor: null,
    });
    configure({
      get: apiGet({
        value: detail({
          summary: { ...detail().summary, status: 'CONFIRMED', actions: [{ action: 'AMEND' }] },
        }),
        version: 3,
      }),
      amendmentsApi: { addLines, confirm },
      newOrderApi: { searchItems },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-primary-action"]') as HTMLButtonElement
    )?.click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-amend-menu-ADD_LINES"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="order-add-lines-dialog"]')).not.toBeNull();

    // `q-combobox` debounces `search` by 250ms real time (its own doc) — fake
    // timers only around the debounce itself, real ones again for the
    // resulting promise chain, matching `combobox.spec.ts`'s own pattern.
    const searchField = host.querySelector('[data-testid="q-combobox-input"]') as HTMLInputElement;
    vi.useFakeTimers();
    searchField.value = 'Лагман';
    searchField.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(250);
    vi.useRealTimers();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(searchItems).toHaveBeenCalled();

    searchField.dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="q-combobox-option"]') as HTMLLIElement).click();
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-add-lines-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(addLines).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 3, [
      { variantId: 'variant-1', quantity: 1, modifierOptionIds: [] },
    ]);
    // The propose call never applied it (applyImmediately: false) — the
    // priced-delta confirmation dialog is what the pane opens next.
    expect(host.querySelector('[data-testid="order-amendment-confirm-dialog"]')).not.toBeNull();
    expect(
      host.querySelector('[data-testid="order-amendment-confirm-delta"]')?.textContent,
    ).toContain('18');

    (
      host.querySelector('[data-testid="order-amendment-confirm-submit"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(confirm).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 'amendment-1', 1, 'PHONE');
  });

  it('wires CHANGE_FULFILLMENT_TIME as a direct apply, no confirmation step', async () => {
    const changeFulfillmentTime = vi.fn().mockReturnValue(of(amendmentResult()));
    configure({
      get: apiGet({
        value: detail({
          summary: { ...detail().summary, status: 'CONFIRMED', actions: [{ action: 'AMEND' }] },
        }),
        version: 3,
      }),
      amendmentsApi: { changeFulfillmentTime },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-primary-action"]') as HTMLButtonElement
    )?.click();
    fixture.detectChanges();
    (
      host.querySelector(
        '[data-testid="order-amend-menu-CHANGE_FULFILLMENT_TIME"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const input = host.querySelector(
      '[data-testid="order-change-time-dialog-input"]',
    ) as HTMLInputElement;
    input.value = '2026-10-01T18:00';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-change-time-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(changeFulfillmentTime).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'order-1',
      3,
      new Date('2026-10-01T18:00').toISOString(),
    );
    expect(host.querySelector('[data-testid="order-amendment-confirm-dialog"]')).toBeNull();
  });

  it('wires the RESOLVE row action to reopen the confirmation dialog for a still-pending amendment', async () => {
    const confirm = vi.fn().mockReturnValue(of(amendmentResult({ status: 'APPLIED' })));
    const history = vi.fn().mockResolvedValue([
      amendmentResult({
        amendmentId: 'amendment-9',
        status: 'PRICED',
        deltaTotalMinor: 25_000,
        requiresApproval: false,
        amendmentVersion: 2,
        commandDetails: [{ type: 'ADD_LINES', text: null }],
        actions: ['RESOLVE'],
      }),
    ]);
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      amendmentsApi: { confirm, history },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector(
        '[data-testid="order-detail-amendment-history-toggle"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const resolveButton = host.querySelector(
      '[data-testid="order-detail-amendment-history-resolve"]',
    ) as HTMLButtonElement;
    expect(resolveButton).not.toBeNull();
    resolveButton.click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-amendment-confirm-dialog"]')).not.toBeNull();

    (
      host.querySelector('[data-testid="order-amendment-confirm-submit"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(confirm).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 'amendment-9', 2, 'PHONE');
  });
});

// ================================================================ wave P11: the order-to-fulfilment seam

/** `OrderDeliveryResponse`, minimally filled — every P11 test below narrows this as it needs. */
function deliveryResponse(overrides: Partial<OrderDeliveryResponse> = {}): OrderDeliveryResponse {
  return {
    planId: 'plan-1',
    planVersion: 2,
    planStatus: 'ASSIGNED',
    estimatedReadyAt: '2026-08-30T09:20:00Z',
    customerDeliveryFeeMinor: 12_000,
    currency: 'UZS',
    exceptions: [],
    ...overrides,
  };
}

function roster(overrides: Partial<RosterEntryResponse> = {}): RosterEntryResponse {
  return {
    courierId: 'courier-1',
    displayReference: 'K-014',
    status: 'ACTIVE',
    courierTypeId: 'type-1',
    courierTypeName: 'Scooter',
    vehicleClass: 'SCOOTER',
    activeAssignments: 0,
    concurrencyCeiling: 2,
    ...overrides,
  };
}

/** Opens the picker, types into it, and clicks the first rendered option — `installation-detail-panel.spec.ts`'s own `q-combobox` idiom. */
async function pickCourier(fixture: { nativeElement: HTMLElement }, query: string): Promise<void> {
  const host = fixture.nativeElement;
  (
    host.querySelector('[data-testid="order-detail-courier-assign-toggle"]') as HTMLButtonElement
  ).click();
  await flushMicrotasks();
  const input = host.querySelector<HTMLInputElement>(
    '[data-testid="order-detail-courier-picker"] [data-testid="q-combobox-input"]',
  )!;
  input.value = query;
  input.dispatchEvent(new Event('input'));
  await flushMicrotasks();
  host
    .querySelector<HTMLElement>(
      '[data-testid="order-detail-courier-picker"] [data-testid="q-combobox-option"]',
    )!
    .click();
  await flushMicrotasks();
}

describe('OrderDetailPane: assign/unassign courier (wave P11, row 1.2e)', () => {
  it('is not shown for a pickup order, or before the delivery plan has loaded', async () => {
    configure({
      get: apiGet({
        value: detail({ summary: { ...detail().summary, fulfillmentMode: 'PICKUP' } }),
        version: 3,
      }),
      deliveryApi: { delivery: () => Promise.resolve(deliveryResponse()) },
    });
    const fixture = await render();

    expect(fixture.nativeElement.querySelector('[data-testid="order-detail-courier"]')).toBeNull();
  });

  it('renders the courier ETA the delivery read carries (wave P11, row 2.1a) -- fetched since P11, never shown before this fix', async () => {
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: {
        delivery: () => Promise.resolve(deliveryResponse({ courierEtaAt: '2026-08-30T09:20:00Z' })),
      },
    });
    const fixture = await render();

    const eta = fixture.nativeElement.querySelector('[data-testid="order-detail-courier-eta"]');
    // Asia/Tashkent is UTC+5, so 09:20 UTC renders as 14:20 local.
    expect(eta?.textContent?.trim()).toBe('Courier ETA 14:20:00');
  });

  it('renders no courier ETA line when the delivery read carries none', async () => {
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: { delivery: () => Promise.resolve(deliveryResponse()) },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-courier-eta"]'),
    ).toBeNull();
  });

  it('assigns a courier through DispatchApi against the planId and version the delivery read returns', async () => {
    const assign = vi.fn().mockResolvedValue({
      applied: true,
      planStatus: 'ASSIGNED',
      planVersion: 3,
    });
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: { delivery: () => Promise.resolve(deliveryResponse()) },
      couriersApi: { roster: () => Promise.resolve([roster()]) },
      dispatchApi: { assign },
    });
    const fixture = await render();

    await pickCourier(fixture, 'K-014');
    fixture.detectChanges();

    expect(assign).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'plan-1',
      'courier-1',
      2,
      'OPERATIONS_ORDER_DETAIL_ASSIGN',
    );
    // The picker closes on a successful assign, exactly like the kitchen pass's own.
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-courier-picker"]'),
    ).toBeNull();
  });

  it('surfaces a refused assign (ALREADY_ASSIGNED, a lost race) as a notice, never a thrown error', async () => {
    const assign = vi.fn().mockResolvedValue({
      applied: false,
      planStatus: 'ASSIGNED',
      planVersion: 3,
      reason: 'ALREADY_ASSIGNED',
    });
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: { delivery: () => Promise.resolve(deliveryResponse()) },
      couriersApi: { roster: () => Promise.resolve([roster()]) },
      dispatchApi: { assign },
    });
    const fixture = await render();

    await pickCourier(fixture, 'K-014');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('ALREADY_ASSIGNED');
  });

  it('unassigns the current courier through DispatchApi against the shipment version', async () => {
    const unassign = vi.fn().mockResolvedValue({
      applied: true,
      planStatus: 'WAITING_TO_SOURCE',
      planVersion: 3,
    });
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: {
        delivery: () =>
          Promise.resolve(
            deliveryResponse({
              shipment: {
                shipmentId: 'shipment-1',
                status: 'ASSIGNED',
                sourceType: 'INTERNAL',
                courierId: 'courier-1',
                version: 4,
              },
            }),
          ),
      },
      dispatchApi: { unassign },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-courier-unassign"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(unassign).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'plan-1',
      4,
      'OPERATIONS_ORDER_DETAIL_UNASSIGN',
    );
  });

  it('offers no unassign button once no courier is carried', async () => {
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: { delivery: () => Promise.resolve(deliveryResponse()) },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-courier-unassign"]'),
    ).toBeNull();
  });
});

describe('OrderDetailPane: call an external courier (gap map rows 1.2e/2.1c)', () => {
  it('requests a quote and accepts it through the order-keyed path OrderDeliveryController.externalCourier exposes, not the plan-keyed DispatchController route', async () => {
    const externalPartners = vi
      .fn()
      .mockResolvedValue([{ bindingId: 'binding-1', providerType: 'YANDEX', supportsHold: false }]);
    const requestExternalCourierQuote = vi.fn().mockResolvedValue({
      priced: true,
      quoteId: 'quote-1',
      bindingId: 'binding-1',
      providerType: 'YANDEX',
      priceMinor: 12_000,
      currency: 'UZS',
      customerDeliveryFeeMinor: 12_000,
      deltaMinor: 0,
    });
    const decideExternalCourier = vi.fn().mockResolvedValue({
      applied: true,
      abandoned: false,
      planVersion: 3,
      shipmentId: 'shipment-1',
    });
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: {
        delivery: () => Promise.resolve(deliveryResponse()),
        requestExternalCourierQuote,
        decideExternalCourier,
      },
      dispatchApi: { externalPartners },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-detail-external-courier"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    // The partner picker is still the plan-keyed read (row 1.2f) -- only
    // quote/accept moved to the order-keyed path this wave built.
    expect(externalPartners).toHaveBeenCalledWith(FAKE_SCOPE, 'plan-1');

    (host.querySelector('[data-testid="external-courier-quote"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(requestExternalCourierQuote).toHaveBeenCalledWith(FAKE_SCOPE, 'order-1', 'binding-1');

    (host.querySelector('[data-testid="external-courier-accept"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(decideExternalCourier).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'order-1',
      'binding-1',
      'quote-1',
      'ACCEPT',
      expect.any(String),
    );
    expect(host.querySelector('[data-testid="external-courier-dialog"]')).toBeNull();
  });

  it("renders the provider's booking state instead of claiming no courier is assigned once an external partner carries the plan", async () => {
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: {
        delivery: () =>
          Promise.resolve(
            deliveryResponse({
              shipment: {
                shipmentId: 'shipment-1',
                status: 'PICKED_UP',
                sourceType: 'PARTNER',
                providerBindingId: 'binding-1',
                version: 2,
              },
            }),
          ),
      },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const state = host.querySelector('[data-testid="order-detail-courier-external"]');
    expect(state).not.toBeNull();
    expect(state?.textContent).toContain('External partner');
    expect(state?.textContent).toContain('Picked up');
    // Never the misleading "no courier" line a `courierId`-only check would show.
    expect(host.textContent).not.toContain('No courier assigned');
    // «Вызвать курьера» is only offered while nobody carries the plan yet (row 1.2f).
    expect(host.querySelector('[data-testid="order-detail-external-courier"]')).toBeNull();
  });
});

describe('OrderDetailPane: three real timeline lanes with elapsed durations (wave P11, row 1.2b)', () => {
  function stageLabels(
    fixture: { nativeElement: HTMLElement },
    testId: string,
  ): (string | undefined)[] {
    const lane = fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
    return Array.from(lane?.querySelectorAll('.q-steps__label') ?? []).map((el) =>
      el.textContent?.trim(),
    );
  }

  it('renders the production lane from kitchen.ticket_events, with elapsed durations between stages', async () => {
    const events: KitchenEventsResponse['events'] = [
      {
        id: 'e1',
        toStatus: 'FIRED',
        trigger: 'SYSTEM',
        actorType: 'SYSTEM',
        actorId: 'system',
        occurredAt: '2026-08-30T09:00:00Z',
      },
      {
        id: 'e2',
        fromStatus: 'FIRED',
        toStatus: 'IN_PRODUCTION',
        trigger: 'OPERATIONS_ACTION',
        actorType: 'USER',
        actorId: 'cook-1',
        occurredAt: '2026-08-30T09:05:00Z',
      },
      {
        id: 'e3',
        fromStatus: 'IN_PRODUCTION',
        toStatus: 'READY',
        trigger: 'OPERATIONS_ACTION',
        actorType: 'USER',
        actorId: 'cook-1',
        occurredAt: '2026-08-30T09:15:00Z',
      },
      {
        id: 'e4',
        fromStatus: 'READY',
        toStatus: 'HANDED_OVER',
        trigger: 'OPERATIONS_ACTION',
        actorType: 'USER',
        actorId: 'expo-1',
        occurredAt: '2026-08-30T09:20:00Z',
      },
    ];
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      kitchenApi: {
        eventsForOrder: () =>
          Promise.resolve({ ticketId: 'ticket-1', ticketStatus: 'HANDED_OVER', events }),
      },
    });
    const fixture = await render();

    expect(stageLabels(fixture, 'order-detail-timeline-production')).toEqual([
      'Fired',
      'In production · 5 min',
      'Ready · 10 min',
      'Handed over · 5 min',
    ]);
  });

  it('renders nothing on the production lane for an order that never opened a ticket', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-timeline-production"]'),
    ).not.toBeNull();
    expect(stageLabels(fixture, 'order-detail-timeline-production')).toEqual([]);
  });

  it("renders the delivery lane from the shipment's own custody timestamps, with elapsed durations", async () => {
    configure({
      get: apiGet({ value: detail(), version: 3 }),
      deliveryApi: {
        delivery: () =>
          Promise.resolve(
            deliveryResponse({
              shipment: {
                shipmentId: 'shipment-1',
                status: 'DELIVERED',
                sourceType: 'INTERNAL',
                courierId: 'courier-1',
                assignedAt: '2026-08-30T09:02:00Z',
                pickedUpAt: '2026-08-30T09:10:00Z',
                deliveredAt: '2026-08-30T09:22:00Z',
                version: 5,
              },
            }),
          ),
      },
    });
    const fixture = await render();

    expect(stageLabels(fixture, 'order-detail-timeline-delivery')).toEqual([
      'Assigned',
      'Picked up · 8 min',
      'Delivered · 12 min',
    ]);
  });

  it('still renders the commercial lane unchanged alongside the two new ones', async () => {
    configure({
      get: apiGet({ value: detail(), version: 3 }, [
        {
          sequence: 1,
          fromStatus: 'RECEIVED',
          toStatus: 'CONFIRMED',
          trigger: 'CHECKOUT',
          reasonCode: '',
          actorType: 'SYSTEM',
          occurredAt: '2026-08-30T09:00:00Z',
        },
      ]),
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-timeline"]'),
    ).not.toBeNull();
  });
});

describe('OrderDetailPane: the losing side of a decision is shown (wave P11, row 1.2b)', () => {
  it('shows a REJECT that lost the compare-and-set, and never the one that won', async () => {
    const decisions: OrderApprovalDecision[] = [
      {
        decisionId: 'click-a',
        action: 'REJECT',
        decisionChannel: 'HORECAOS_OPERATIONS',
        actorType: 'USER',
        actorId: 'operator-a',
        reasonCode: 'OUT_OF_STOCK',
        effective: false,
        issuedAt: '2026-08-30T09:00:00Z',
      },
      {
        decisionId: 'click-b',
        action: 'APPROVE',
        decisionChannel: 'HORECAOS_OPERATIONS',
        actorType: 'USER',
        actorId: 'operator-b',
        effective: true,
        issuedAt: '2026-08-30T09:00:01Z',
      },
    ];
    configure({ get: apiGet({ value: detail(), version: 3 }, [], decisions) });
    const fixture = await render();

    const section = fixture.nativeElement.querySelector(
      '[data-testid="order-detail-timeline-losing-decisions"]',
    );
    expect(section).not.toBeNull();
    expect(section!.textContent).toContain('rejected');
    expect(section!.textContent).toContain('OUT_OF_STOCK');
    expect(section!.textContent).not.toContain('operator-b');
  });

  it('renders no losing-decisions lane when every decision on record was effective', async () => {
    const decisions: OrderApprovalDecision[] = [
      {
        decisionId: 'click-a',
        action: 'APPROVE',
        decisionChannel: 'HORECAOS_OPERATIONS',
        actorType: 'USER',
        actorId: 'operator-a',
        effective: true,
        issuedAt: '2026-08-30T09:00:00Z',
      },
    ];
    configure({ get: apiGet({ value: detail(), version: 3 }, [], decisions) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-timeline-losing-decisions"]'),
    ).toBeNull();
  });
});

// ================================================================ wave P42: §3.11 POS export and its interlock

/** A minimal `PosExportView`, overridable per test — mirrors `OrderPosExportController.ExportView`. */
function posExportView(overrides: Partial<PosExportView> = {}): PosExportView {
  return {
    exportId: 'export-1',
    state: 'ACCEPTED',
    permitsAmendment: false,
    attemptCount: 1,
    externalOrderId: null,
    requestedAt: '2026-09-15T09:00:00Z',
    firstSentAt: '2026-09-15T09:00:01Z',
    settledAt: '2026-09-15T09:00:02Z',
    lastErrorCode: null,
    lastError: null,
    resolutionKind: null,
    resolutionReason: null,
    resolvedAt: null,
    ...overrides,
  };
}

/** The order this describe block amends throughout -- CONFIRMED with only AMEND offered, matching the P10 AMEND test's own fixture shape. */
function amendableDetail() {
  return detail({
    summary: { ...detail().summary, status: 'CONFIRMED', actions: [{ action: 'AMEND' }] },
  });
}

describe('OrderDetailPane: POS export and its §3.11 amendment interlock (wave P42, row 1.2i)', () => {
  it('the section and the interlock are both absent when the tenant has no POS binding', async () => {
    configure({
      get: apiGet({ value: amendableDetail(), version: 3 }),
      posExportApi: { forOrder: () => Promise.resolve({ posCapable: false, export: null }) },
    });
    const fixture = await render();
    await flushMicrotasks();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-detail-pos-export"]')).toBeNull();
    // No POS binding means nothing to wait on either: AMEND stays enabled.
    const primary = host.querySelector(
      '[data-testid="order-detail-primary-action"]',
    ) as HTMLButtonElement;
    expect(primary.disabled).toBe(false);
    expect(host.querySelector('[data-testid="order-detail-amend-blocked"]')).toBeNull();
  });

  it('renders the POS export section once the tenant is posCapable', async () => {
    configure({
      get: apiGet({ value: amendableDetail(), version: 3 }),
      posExportApi: {
        forOrder: () =>
          Promise.resolve({
            posCapable: true,
            export: posExportView({
              state: 'REJECTED',
              permitsAmendment: true,
              lastErrorCode: 'LINE_UNMAPPED',
              lastError: 'no provider mapping',
            }),
          }),
      },
    });
    const fixture = await render();
    await flushMicrotasks();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    const section = host.querySelector('[data-testid="order-detail-pos-export"]');
    expect(section).not.toBeNull();
    expect(section!.textContent).toContain('till refused it');
    // The Traps note this row exists for: never render a failed export as an
    // order failure -- the reassurance sentence is what says so.
    expect(
      host.querySelector('[data-testid="order-detail-pos-export-reassurance"]')?.textContent,
    ).toContain('order is fine');
    expect(
      host.querySelector('[data-testid="order-detail-pos-export-last-error"]')?.textContent,
    ).toContain('no provider mapping');
  });

  it('disables AMEND and shows the reason while an export is unacknowledged', async () => {
    configure({
      get: apiGet({ value: amendableDetail(), version: 3 }),
      posExportApi: {
        forOrder: () =>
          Promise.resolve({
            posCapable: true,
            export: posExportView({ state: 'ACCEPTED', permitsAmendment: false }),
          }),
      },
    });
    const fixture = await render();
    await flushMicrotasks();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    const primary = host.querySelector(
      '[data-testid="order-detail-primary-action"]',
    ) as HTMLButtonElement;
    expect(primary.disabled).toBe(true);
    expect(host.querySelector('[data-testid="order-detail-amend-blocked"]')?.textContent).toContain(
      'waiting on the POS',
    );

    // Defense in depth: even a click that reaches the handler must not open the menu.
    primary.click();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="order-amend-menu"]')).toBeNull();
  });

  it('leaves AMEND enabled once the export has settled (PENDING permits an amendment)', async () => {
    configure({
      get: apiGet({ value: amendableDetail(), version: 3 }),
      posExportApi: {
        forOrder: () =>
          Promise.resolve({
            posCapable: true,
            export: posExportView({ state: 'PENDING', permitsAmendment: true }),
          }),
      },
    });
    const fixture = await render();
    await flushMicrotasks();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    const primary = host.querySelector(
      '[data-testid="order-detail-primary-action"]',
    ) as HTMLButtonElement;
    expect(primary.disabled).toBe(false);
    expect(host.querySelector('[data-testid="order-detail-amend-blocked"]')).toBeNull();

    primary.click();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="order-amend-menu"]')).not.toBeNull();
  });
});
