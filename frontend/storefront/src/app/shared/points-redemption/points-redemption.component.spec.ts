import { TestBed } from '@angular/core/testing';

import { type CartPointsOffer, PointsService } from '../../services/points.service';
import { TranslateService } from '../../services/translate.service';
import {
  NO_POINTS,
  PointsRedemptionComponent,
  type PointsSelection,
} from './points-redemption.component';

class FakeTranslateService {
  get(key: string): string {
    return key === 'common.currency' ? "so'm" : key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}|${Object.values(params).join('|')}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function offer(
  overrides: { maximum?: number; total?: number; refusal?: string | null; priced?: boolean } = {},
): CartPointsOffer {
  const maximum = overrides.maximum ?? 4_000;
  return {
    cartId: 'cart-1',
    priced: overrides.priced ?? true,
    orderTotal: { amountMinor: overrides.total ?? 15_000, currency: 'UZS' },
    offer: {
      accountId: 'pa-1',
      available: { amountMinor: 12_000, currency: 'UZS' },
      maximum: { amountMinor: maximum, currency: 'UZS' },
      redeemable: maximum > 0,
      refusal: maximum > 0 ? null : (overrides.refusal ?? 'INSUFFICIENT_BALANCE'),
    },
  };
}

/** Amounts are grouped with a no-break space; read every kind of space as a plain one. */
function textOf(element: HTMLElement | null): string {
  return (element?.textContent ?? '').replace(/\s+/g, ' ');
}

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

async function setUp(initial: CartPointsOffer | Error = offer()) {
  const points = {
    redemptionOffer: vi.fn(() =>
      initial instanceof Error ? Promise.reject(initial) : Promise.resolve(initial),
    ),
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: PointsService, useValue: points },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(PointsRedemptionComponent);
  const selections: PointsSelection[] = [];
  fixture.componentInstance.selectionChange.subscribe((s) => selections.push(s));
  fixture.componentRef.setInput('cartId', 'cart-1');
  fixture.componentRef.setInput('quoteId', 'quote-1');
  fixture.detectChanges();
  await settle();
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byId = (id: string) => host.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const type = async (value: string) => {
    const input = byId('points-amount') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
    fixture.detectChanges();
  };
  const toggle = async () => {
    byId('points-toggle')!.click();
    await settle();
    fixture.detectChanges();
  };
  return { fixture, points, selections, byId, type, toggle };
}

describe('PointsRedemptionComponent', () => {
  it('asks what may be spent for the cart, and says what is available and the most allowed', async () => {
    const { points, byId } = await setUp();

    expect(points.redemptionOffer).toHaveBeenCalledWith('cart-1');
    expect(textOf(byId('points-summary'))).toContain('12 000');
    expect(textOf(byId('points-summary'))).toContain('4 000');
  });

  it('spends nothing until switched on', async () => {
    const { fixture, selections } = await setUp();

    expect(fixture.componentInstance.selection()).toBe(NO_POINTS);
    expect(selections.at(-1)).toBe(NO_POINTS);
  });

  it('starts from the most allowed when switched on, and says what is left to pay', async () => {
    const { fixture, toggle, byId } = await setUp();

    await toggle();

    expect(fixture.componentInstance.selection()).toEqual({ redeemMinor: 4_000, blocked: false });
    expect(textOf(byId('points-cash'))).toContain('11 000');
  });

  it('tells the checkout the amount typed, and what is left to pay follows it', async () => {
    const { selections, toggle, type, byId } = await setUp();
    await toggle();

    await type('1 500');

    expect(selections.at(-1)).toEqual({ redeemMinor: 1_500, blocked: false });
    expect(textOf(byId('points-cash'))).toContain('13 500');
  });

  it.each(['9 000', '0', ''])(
    'blocks the checkout, and spends none, for %j: not an amount on offer',
    async (typed) => {
      const { selections, toggle, type, byId } = await setUp();
      await toggle();

      await type(typed);

      expect(selections.at(-1)).toEqual({ redeemMinor: 0, blocked: true });
      expect(byId('points-exceeds')).not.toBeNull();
      expect(byId('points-cash')).toBeNull();
    },
  );

  it('puts the largest amount back with one tap', async () => {
    const { selections, toggle, type, byId } = await setUp();
    await toggle();
    await type('100');

    byId('points-use-maximum')!.click();
    await settle();

    expect(selections.at(-1)).toEqual({ redeemMinor: 4_000, blocked: false });
  });

  it('is not there when there is nothing to offer', async () => {
    const { byId } = await setUp(offer({ maximum: 0, refusal: 'REDEMPTION_NOT_ENABLED' }));

    expect(byId('points-redemption')).toBeNull();
  });

  it('explains that points and the offer on this order do not combine, and offers no amount', async () => {
    const { byId } = await setUp(offer({ maximum: 0, refusal: 'REDEMPTION_BLOCKED_BY_PROMOTION' }));

    expect(byId('points-blocked')).not.toBeNull();
    expect(byId('points-toggle')).toBeNull();
  });

  it('offers nothing against a cart that has no quote to offer against', async () => {
    const { byId } = await setUp(offer({ priced: false }));

    expect(byId('points-redemption')).toBeNull();
  });

  it('is not there, and spends nothing, when the offer cannot be read', async () => {
    const { fixture, byId } = await setUp(new Error('offline'));

    expect(byId('points-redemption')).toBeNull();
    expect(fixture.componentInstance.selection()).toBe(NO_POINTS);
  });

  it('asks again when the cart is priced again, and lowers an amount the new quote no longer allows', async () => {
    const { fixture, points, selections, toggle, byId } = await setUp();
    await toggle();
    points.redemptionOffer.mockResolvedValue(offer({ maximum: 1_000 }));

    fixture.componentRef.setInput('quoteId', 'quote-2');
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    expect(points.redemptionOffer).toHaveBeenCalledTimes(2);
    expect(selections.at(-1)).toEqual({ redeemMinor: 1_000, blocked: false });
    expect(byId('points-adjusted')).not.toBeNull();
  });

  it('switches itself off when the new quote allows no points at all', async () => {
    const { fixture, points, selections, toggle, byId } = await setUp();
    await toggle();
    points.redemptionOffer.mockResolvedValue(offer({ maximum: 0 }));

    fixture.componentRef.setInput('quoteId', 'quote-2');
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    expect(selections.at(-1)).toBe(NO_POINTS);
    expect(byId('points-redemption')).toBeNull();
  });

  it('drops a slow answer for a quote that has since been replaced', async () => {
    const { fixture, points, selections, toggle } = await setUp();
    await toggle();
    let late!: (value: CartPointsOffer) => void;
    points.redemptionOffer.mockReturnValueOnce(
      new Promise<CartPointsOffer>((resolve) => (late = resolve)),
    );
    fixture.componentRef.setInput('quoteId', 'quote-2');
    fixture.detectChanges();
    points.redemptionOffer.mockResolvedValue(offer({ maximum: 2_500 }));
    fixture.componentRef.setInput('quoteId', 'quote-3');
    fixture.detectChanges();
    await settle();

    late(offer({ maximum: 9_000 }));
    await settle();
    fixture.detectChanges();

    expect(selections.at(-1)).toEqual({ redeemMinor: 2_500, blocked: false });
  });

  it('forgets everything once the basket has become an order', async () => {
    const { fixture, selections, toggle, byId } = await setUp();
    await toggle();

    fixture.componentRef.setInput('cartId', null);
    fixture.detectChanges();

    expect(selections.at(-1)).toBe(NO_POINTS);
    expect(byId('points-redemption')).toBeNull();
  });
});
