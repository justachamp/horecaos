import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { PromotionsApi } from '../marketing/promotions/promotions-api';
import { OrderRequoteApi, RequoteFinding } from './order-requote-api';
import { OrderRequotePanel } from './order-requote-panel';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const PROMOTION = '3fa85f64-5717-4562-b3fc-2c963f66afa6';

function finding(overrides: Partial<RequoteFinding> = {}): RequoteFinding {
  return {
    id: 'f1',
    trigger: 'CHECKPOINT',
    checkpointAt: '2026-10-14T09:00:00Z',
    outcome: 'CHANGED',
    refusalCode: null,
    currency: 'UZS',
    heldTotalMinor: 81_000,
    heldDiscountMinor: 9_000,
    requoteTotalMinor: 90_000,
    requoteDiscountMinor: 0,
    deltaTotalMinor: 9_000,
    promotionChanges: [
      { promotionId: PROMOTION, change: 'DROPPED', heldMinor: -9_000, requoteMinor: 0 },
    ],
    createdAt: '2026-10-14T09:00:01Z',
    ...overrides,
  };
}

function configure(options: {
  findings?: () => Promise<readonly RequoteFinding[]>;
  recheck?: () => Promise<RequoteFinding>;
  names?: readonly { promotionId: string; name: string }[] | 'denied';
}) {
  const api = {
    findings: vi.fn(options.findings ?? (() => Promise.resolve([] as readonly RequoteFinding[]))),
    recheck: vi.fn(options.recheck ?? (() => Promise.resolve(finding()))),
  };
  const promotions = {
    list: vi.fn(() =>
      options.names === 'denied'
        ? Promise.reject(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null))
        : Promise.resolve(options.names ?? []),
    ),
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: OrderRequoteApi, useValue: api },
      { provide: PromotionsApi, useValue: promotions },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return { api, promotions };
}

async function render() {
  const fixture = TestBed.createComponent(OrderRequotePanel);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('orderId', 'order-1');
  fixture.detectChanges();
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-detail-requote-intro"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Loading');
  });
  return fixture;
}

function byId(fixture: { nativeElement: HTMLElement }, id: string): HTMLElement | null {
  return fixture.nativeElement.querySelector(`[data-testid="${id}"]`);
}

describe("OrderRequotePanel: a scheduled order's promotions at the checkpoint (row 6.1)", () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('reads the findings of this order at this branch', async () => {
    const { api } = configure({ findings: () => Promise.resolve([finding()]) });
    await render();

    expect(api.findings).toHaveBeenCalledWith(SCOPE, 'order-1');
  });

  it('says in words that nothing here changes the order, and that a person phones or cancels', async () => {
    configure({});
    const fixture = await render();

    const intro = byId(fixture, 'order-detail-requote-intro')?.textContent ?? '';
    expect(intro).toContain('Nothing here changes the order');
    expect(intro).toContain('phone the guest or cancel');
  });

  it('shows an order that has not been checked yet as such, with the re-check on offer', async () => {
    configure({});
    const fixture = await render();

    expect(byId(fixture, 'order-detail-requote-empty')).not.toBeNull();
    expect(byId(fixture, 'order-detail-requote-recheck')).not.toBeNull();
  });

  it('shows a changed finding with both prices, the difference and the promotion that dropped, by name', async () => {
    configure({
      findings: () => Promise.resolve([finding()]),
      names: [{ promotionId: PROMOTION, name: 'Lunch 10' }],
    });
    const fixture = await render();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId(fixture, 'order-detail-requote-change')?.textContent).toContain('Lunch 10');
    });

    const row = byId(fixture, 'order-detail-requote-finding');
    expect(row?.getAttribute('data-outcome')).toBe('CHANGED');
    expect(row?.textContent).toContain('The price would change');
    expect(row?.textContent).toContain('Automatic check');
    const totals = byId(fixture, 'order-detail-requote-totals')?.textContent ?? '';
    expect(totals).toMatch(/Held price .*81.*priced again .*90/s);
    expect(byId(fixture, 'order-detail-requote-delta')?.textContent).toContain('more');
    expect(byId(fixture, 'order-detail-requote-change')?.textContent).toContain(
      'no longer applies',
    );
  });

  it("falls back to a short reference when the promotion's name cannot be read", async () => {
    configure({ findings: () => Promise.resolve([finding()]), names: 'denied' });
    const fixture = await render();

    expect(byId(fixture, 'order-detail-requote-change')?.textContent).toContain('3fa85f64');
  });

  it('shows why an order could not be priced again, with no totals', async () => {
    configure({
      findings: () =>
        Promise.resolve([
          finding({
            outcome: 'NOT_PRICEABLE',
            refusalCode: 'ITEM_NOT_PRICED',
            requoteTotalMinor: null,
            requoteDiscountMinor: null,
            deltaTotalMinor: null,
            promotionChanges: [],
          }),
        ]),
    });
    const fixture = await render();

    expect(byId(fixture, 'order-detail-requote-refusal')?.textContent).toContain('ITEM_NOT_PRICED');
    expect(byId(fixture, 'order-detail-requote-totals')).toBeNull();
  });

  it('puts the re-check at the top of the list, newest first, and offers it again afterwards', async () => {
    const fresh = finding({
      id: 'f2',
      trigger: 'OPERATOR',
      outcome: 'UNCHANGED',
      promotionChanges: [],
      deltaTotalMinor: 0,
    });
    const { api } = configure({
      findings: () => Promise.resolve([finding()]),
      recheck: () => Promise.resolve(fresh),
    });
    const fixture = await render();

    byId(fixture, 'order-detail-requote-recheck')?.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelectorAll('[data-testid="order-detail-requote-finding"]')
          .length,
      ).toBe(2);
    });

    expect(api.recheck).toHaveBeenCalledWith(SCOPE, 'order-1');
    const first = fixture.nativeElement.querySelector(
      '[data-testid="order-detail-requote-finding"]',
    );
    expect(first?.getAttribute('data-outcome')).toBe('UNCHANGED');
    expect(first?.textContent).toContain('Checked by hand');
    expect((byId(fixture, 'order-detail-requote-recheck') as HTMLButtonElement).disabled).toBe(
      false,
    );
  });

  it('says so when the order is no longer one that can be checked', async () => {
    configure({
      recheck: () =>
        Promise.reject(
          new ApiError(
            ApiErrorCode.RESOURCE_CONFLICT,
            409,
            { status: 409, reason: 'NOT_AWAITING_CHECKPOINT' },
            null,
          ),
        ),
    });
    const fixture = await render();

    byId(fixture, 'order-detail-requote-recheck')?.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId(fixture, 'order-detail-requote-check-error')?.textContent).toContain(
        'kitchen has not started',
      );
    });
  });

  it('shows an error state instead of crashing when the findings cannot be read', async () => {
    configure({
      findings: () => Promise.reject(new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null)),
    });
    const fixture = TestBed.createComponent(OrderRequotePanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('orderId', 'order-1');
    fixture.detectChanges();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(byId(fixture, 'order-detail-requote-error')).not.toBeNull();
    });
  });
});
