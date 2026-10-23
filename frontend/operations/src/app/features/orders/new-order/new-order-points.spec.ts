import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import {
  NewOrderApi,
  OperatorRedemptionOffer,
  PlaceOrderRequest,
  RedemptionOffer,
} from './new-order-api';
import { NO_REDEMPTION, NewOrderPoints, PointsAsk } from './new-order-points';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function requestOf(customerAccountId = 'acct-1'): PlaceOrderRequest {
  return {
    customerAccountId,
    channelCode: 'OPERATOR',
    fulfillmentMode: 'PICKUP',
    lines: [],
    paymentMethodCode: 'CASH',
  };
}

function askOf(customerAccountId = 'acct-1'): PointsAsk {
  return { scope: SCOPE, request: requestOf(customerAccountId) };
}

/** What the server answers for a 25 000 order, 40 000 points and a 20 000 cap; the figures follow the amount asked for. */
function answerFor(
  requested: number | null,
  offer: Partial<RedemptionOffer> = {},
): OperatorRedemptionOffer {
  const maximum = offer.maximum?.amountMinor ?? 20_000;
  const exceeds = requested !== null && requested > maximum;
  return {
    customerAccountId: 'acct-1',
    orderTotal: { amountMinor: 25_000, currency: 'UZS' },
    deliveryFee: { amountMinor: 0, currency: 'UZS' },
    offer: {
      accountId: 'pa-1',
      available: { amountMinor: 40_000, currency: 'UZS' },
      maximum: { amountMinor: maximum, currency: 'UZS' },
      redeemable: maximum > 0,
      refusal: null,
      ...offer,
    },
    requested: requested === null ? null : { amountMinor: requested, currency: 'UZS' },
    requestedRefusal: exceeds ? 'EXCEEDS_MAXIMUM' : null,
    cashToCollect:
      requested === null
        ? { amountMinor: 25_000, currency: 'UZS' }
        : exceeds
          ? null
          : { amountMinor: 25_000 - requested, currency: 'UZS' },
  };
}

describe('NewOrderPoints', () => {
  let fixture: ComponentFixture<NewOrderPoints>;
  let redemptionOffer: ReturnType<typeof vi.fn>;

  function render(
    api: ReturnType<typeof vi.fn> = vi
      .fn()
      .mockImplementation(async (_scope: LocationScope, request: PlaceOrderRequest) =>
        answerFor(request.redeemFromBalanceMinor ?? null),
      ),
    ask: PointsAsk | null = askOf(),
  ): void {
    TestBed.resetTestingModule();
    redemptionOffer = api;
    TestBed.configureTestingModule({
      providers: [{ provide: NewOrderApi, useValue: { redemptionOffer } }],
    });
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(NewOrderPoints);
    fixture.componentRef.setInput('customerAccountId', 'acct-1');
    fixture.componentRef.setInput('ask', ask);
    fixture.detectChanges();
  }

  const host = () => fixture.nativeElement as HTMLElement;
  const el = (testId: string) => host().querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const text = (testId: string) => el(testId)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  /** The `calls`th request has been made and its answer has landed and been drawn. */
  async function answered(calls: number): Promise<void> {
    await vi.waitFor(() => expect(redemptionOffer).toHaveBeenCalledTimes(calls), { timeout: 3000 });
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function type(amount: string): void {
    const field = el('new-order-points-amount')!.querySelector('input')!;
    field.value = amount;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function agree(): void {
    const box = el('new-order-points-agreed') as HTMLInputElement;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('waits for a server price: with none on screen it asks nothing and shows nothing', async () => {
    render(undefined, null);
    await new Promise<void>((resolve) => setTimeout(resolve, 400));

    expect(redemptionOffer).not.toHaveBeenCalled();
    expect(el('new-order-points')).toBeNull();
    expect(fixture.componentInstance.selection()).toBe(NO_REDEMPTION);
  });

  it('shows what may be spent, asking with the request the price was asked with and no amount', async () => {
    render();
    await answered(1);

    expect(redemptionOffer.mock.calls[0][0]).toEqual(SCOPE);
    expect(redemptionOffer.mock.calls[0][1]).toMatchObject({
      customerAccountId: 'acct-1',
      redeemFromBalanceMinor: null,
      operatorAttestsCustomerAgreement: null,
    });
    expect(text('new-order-points-available')).toMatch(/40[\s,.  ]?000/);
    expect(text('new-order-points-maximum')).toMatch(/20[\s,.  ]?000/);
    expect(
      fixture.componentInstance.selection(),
      'nothing typed: nothing to wait for and nothing to send',
    ).toBe(NO_REDEMPTION);
  });

  it.each(['NO_ACCOUNT', 'REDEMPTION_NOT_ENABLED'])(
    'says nothing to an operator when the answer is %s: there are no points to talk about',
    async (refusal) => {
      render(
        vi.fn().mockResolvedValue(
          answerFor(null, {
            maximum: { amountMinor: 0, currency: 'UZS' },
            redeemable: false,
            refusal,
          }),
        ),
      );
      await answered(1);

      expect(el('new-order-points')).toBeNull();
    },
  );

  it('says why a customer with points cannot spend them here, and offers no amount to type', async () => {
    render(
      vi.fn().mockResolvedValue(
        answerFor(null, {
          maximum: { amountMinor: 0, currency: 'UZS' },
          redeemable: false,
          refusal: 'REDEMPTION_BLOCKED_BY_PROMOTION',
        }),
      ),
    );
    await answered(1);

    expect(text('new-order-points-refusal')).toMatch(/cannot be combined with points/);
    expect(el('new-order-points-amount')).toBeNull();
  });

  it('is ready with an amount only when the server accepted it and the operator said the customer agreed', async () => {
    render();
    await answered(1);

    type('10 000');
    expect(fixture.componentInstance.selection().ready, 'not asked about this amount yet').toBe(
      false,
    );
    await answered(2);
    expect(redemptionOffer.mock.calls[1][1].redeemFromBalanceMinor).toBe(10_000);
    expect(text('new-order-points-cash')).toMatch(/15[\s,.  ]?000/);
    expect(
      fixture.componentInstance.selection().ready,
      'accepted by the server, but nobody has said the customer agreed',
    ).toBe(false);

    agree();

    expect(fixture.componentInstance.selection()).toEqual({
      requestedMinor: 10_000,
      ready: true,
      cashToCollectMinor: 15_000,
    });
  });

  it('refuses an amount above the maximum in words and never releases it', async () => {
    render();
    await answered(1);

    type('30 000');
    await answered(2);
    agree();

    expect(text('new-order-points-exceeds')).toMatch(/More than this order allows/);
    expect(el('new-order-points-cash')).toBeNull();
    expect(fixture.componentInstance.selection().ready).toBe(false);
    expect(fixture.componentInstance.selection().cashToCollectMinor).toBeNull();
  });

  it('asks for the agreement again when the amount changes: the customer agreed to a figure, not to any figure', async () => {
    render();
    await answered(1);
    type('10 000');
    await answered(2);
    agree();
    expect(fixture.componentInstance.selection().ready).toBe(true);

    type('12 000');

    expect(fixture.componentInstance.selection().ready).toBe(false);
    expect((el('new-order-points-agreed') as HTMLInputElement).checked).toBe(false);
  });

  it('does not let an answer for an earlier price release the order against a new one', async () => {
    render();
    await answered(1);
    type('10 000');
    await answered(2);
    agree();
    expect(fixture.componentInstance.selection().ready).toBe(true);

    // The price moves: the screen retires the ask, then supplies a new one for the new price.
    fixture.componentRef.setInput('ask', null);
    fixture.detectChanges();
    expect(fixture.componentInstance.selection().ready, 'no price on screen').toBe(false);
    fixture.componentRef.setInput('ask', askOf());
    fixture.detectChanges();
    expect(
      fixture.componentInstance.selection().ready,
      'the new price has not been asked about yet',
    ).toBe(false);

    await answered(3);

    expect(fixture.componentInstance.selection().ready).toBe(true);
  });

  it('drops a slow answer for an amount that has since changed', async () => {
    const slow: { resolve: (offer: OperatorRedemptionOffer) => void }[] = [];
    render(
      vi.fn().mockImplementation(
        (_scope: LocationScope, request: PlaceOrderRequest) =>
          new Promise<OperatorRedemptionOffer>((resolve) => {
            if (request.redeemFromBalanceMinor === 10_000) {
              slow.push({ resolve });
            } else {
              resolve(answerFor(request.redeemFromBalanceMinor ?? null));
            }
          }),
      ),
    );
    await answered(1);
    type('10 000');
    await vi.waitFor(() => expect(slow).toHaveLength(1), { timeout: 3000 });
    type('5 000');
    await answered(3);
    agree();
    expect(fixture.componentInstance.selection().cashToCollectMinor).toBe(20_000);

    slow[0].resolve(answerFor(10_000));
    await new Promise<void>((resolve) => setTimeout(resolve, 20));
    fixture.detectChanges();

    expect(
      fixture.componentInstance.selection(),
      'the late answer for 10 000 must not replace the answer for 5 000',
    ).toEqual({ requestedMinor: 5_000, ready: true, cashToCollectMinor: 20_000 });
  });

  it('starts again for a different customer: an amount agreed for one is no one else’s', async () => {
    render();
    await answered(1);
    type('10 000');
    await answered(2);
    agree();
    expect(fixture.componentInstance.selection().ready).toBe(true);

    fixture.componentRef.setInput('customerAccountId', 'acct-2');
    fixture.componentRef.setInput('ask', null);
    fixture.detectChanges();

    expect(fixture.componentInstance.selection()).toBe(NO_REDEMPTION);
    expect(el('new-order-points')).toBeNull();
  });

  it('puts the largest allowed amount in with one click, and takes it out again', async () => {
    render();
    await answered(1);

    (el('new-order-points-use-maximum') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance['requestedMinor']()).toBe(20_000);

    (el('new-order-points-clear') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance['requestedMinor']()).toBe(0);
    expect(fixture.componentInstance.selection()).toBe(NO_REDEMPTION);
  });

  it('holds the order back, and says so, when an amount is typed and the points cannot be checked', async () => {
    render(vi.fn().mockResolvedValueOnce(answerFor(null)).mockRejectedValue(new Error('offline')));
    await answered(1);

    type('10 000');
    await answered(2);
    agree();

    expect(text('new-order-points-unavailable')).toMatch(/can still be placed without them/);
    expect(fixture.componentInstance.selection().ready).toBe(false);
  });
});
