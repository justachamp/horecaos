import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { OrderActionsApi } from './order-actions-api';
import { OrderDetailPane } from './order-detail-pane';
import { OrderDetailResponse, OrderTimelineEntry } from './order-detail';
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

/** A path-aware `ApiClient.get` stub, since the pane fetches both the order and its timeline. */
function apiGet(
  orderResult: unknown,
  timelineResult: readonly OrderTimelineEntry[] = [],
): ReturnType<typeof vi.fn> {
  return vi.fn().mockImplementation((path: string) => {
    if (path === TIMELINE_PATH) {
      return of({ value: timelineResult, version: null });
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
  revealApi?: Partial<OrderRevealApi>;
  rejectReasonsApi?: Partial<RejectReasonsApi>;
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
      { provide: OrderRevealApi, useValue: options.revealApi ?? {} },
      {
        provide: RejectReasonsApi,
        useValue: options.rejectReasonsApi ?? { list: () => Promise.resolve(FAKE_REJECT_REASONS) },
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

    const gap = fixture.nativeElement.querySelector('[data-testid="order-detail-timeline-gap"]');
    expect(gap?.textContent).toContain('2');
  });

  it('renders the production and delivery lanes as not built, never silently dropped', async () => {
    configure({ get: apiGet({ value: detail(), version: 3 }) });
    const fixture = await render();
    expect(fixture.nativeElement.textContent).toContain('not built yet');
  });
});
