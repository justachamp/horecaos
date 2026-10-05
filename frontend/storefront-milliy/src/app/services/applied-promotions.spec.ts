import {
  discountLines,
  giftOfferGroups,
  noteLines,
  promoOutcomeKey,
  type AppliedPromotion,
  type GiftOffer,
} from './applied-promotions';

const AUTO_DISCOUNT: AppliedPromotion = {
  source: 'AUTOMATIC',
  effect: 'DISCOUNT',
  amountMinor: 4_000,
};
const CODE_DISCOUNT: AppliedPromotion = {
  source: 'PROMO_CODE',
  effect: 'DISCOUNT',
  amountMinor: 2_000,
};
const DELIVERY: AppliedPromotion = {
  source: 'AUTOMATIC',
  effect: 'DELIVERY_DISCOUNT',
  amountMinor: 5_000,
};
const SURCHARGE: AppliedPromotion = {
  source: 'AUTOMATIC',
  effect: 'SURCHARGE',
  amountMinor: 1_500,
};

describe('discountLines', () => {
  it("turns discounts into lines, an offer and a typed code apart, with the platform's amounts untouched", () => {
    expect(discountLines([AUTO_DISCOUNT, CODE_DISCOUNT])).toEqual([
      { labelKey: 'cart.offerDiscount', source: 'AUTOMATIC', amountMinor: 4_000 },
      { labelKey: 'cart.promoCode', source: 'PROMO_CODE', amountMinor: 2_000 },
    ]);
  });

  it('leaves out what is not taken off the goods: those are explained as captions instead', () => {
    expect(discountLines([DELIVERY, SURCHARGE])).toEqual([]);
  });

  it('reads an answer without the breakdown as having none', () => {
    expect(discountLines(undefined)).toEqual([]);
    expect(discountLines(null)).toEqual([]);
  });

  it('drops an entry that took nothing off', () => {
    expect(discountLines([{ ...AUTO_DISCOUNT, amountMinor: 0 }])).toEqual([]);
  });
});

describe('noteLines', () => {
  it('describes a delivery offer and a surcharge, in that order of appearance', () => {
    expect(noteLines([SURCHARGE, DELIVERY])).toEqual([
      { labelKey: 'cart.surchargeNote', source: 'AUTOMATIC', amountMinor: 1_500 },
      { labelKey: 'cart.deliveryOfferNote', source: 'AUTOMATIC', amountMinor: 5_000 },
    ]);
  });

  it('never repeats a discount, which is already a line of its own', () => {
    expect(noteLines([AUTO_DISCOUNT, CODE_DISCOUNT])).toEqual([]);
  });
});

describe('promoOutcomeKey', () => {
  it('has a sentence for each way a code can fail to move the price', () => {
    expect(promoOutcomeKey('OFFERS_ARE_BETTER')).toBe('checkout.promoOffersBetter');
    expect(promoOutcomeKey('NOT_APPLICABLE')).toBe('checkout.promoNotApplicable');
    expect(promoOutcomeKey('NOT_VALID')).toBe('checkout.promoNoLongerValid');
  });

  it('has nothing to say about a code that applied, or no code', () => {
    expect(promoOutcomeKey('APPLIED')).toBeNull();
    expect(promoOutcomeKey(null)).toBeNull();
    expect(promoOutcomeKey(undefined)).toBeNull();
  });
});

describe('giftOfferGroups (ADR 0140: an offer, never a line)', () => {
  function offer(overrides: Partial<GiftOffer> = {}): GiftOffer {
    return {
      ruleId: 'rule-1',
      variantId: 'v-cola',
      quantity: 1,
      inCart: false,
      toAdd: 1,
      ...overrides,
    };
  }
  const MENU = new Map([
    ['v-cola', { name: 'Cola', image: '/cola.png' }],
    ['v-fanta', { name: 'Fanta', image: null }],
  ]);

  it('names the gift from the menu, which the offer itself does not carry', () => {
    expect(giftOfferGroups([offer()], MENU)).toEqual([
      {
        ruleId: 'rule-1',
        toAdd: 1,
        choices: [{ variantId: 'v-cola', name: 'Cola', image: '/cola.png', inCart: false }],
      },
    ]);
  });

  it('keeps the variants of one rule together as a choice, any of which fills the same allowance', () => {
    const groups = giftOfferGroups(
      [offer(), offer({ variantId: 'v-fanta' }), offer({ ruleId: 'rule-2', variantId: 'v-cola' })],
      MENU,
    );

    expect(groups.map((group) => [group.ruleId, group.choices.map((c) => c.name)])).toEqual([
      ['rule-1', ['Cola', 'Fanta']],
      ['rule-2', ['Cola']],
    ]);
  });

  it('keeps apart two gifts of one rule that have different allowances', () => {
    const groups = giftOfferGroups(
      [offer({ quantity: 1, toAdd: 1 }), offer({ variantId: 'v-fanta', quantity: 2, toAdd: 2 })],
      MENU,
    );

    expect(groups.map((group) => [group.ruleId, group.toAdd, group.choices[0].name])).toEqual([
      ['rule-1', 1, 'Cola'],
      ['rule-1', 2, 'Fanta'],
    ]);
  });

  it('offers nothing once the allowance is already in the cart: that is the discount line, not an offer', () => {
    expect(giftOfferGroups([offer({ inCart: true, toAdd: 0 })], MENU)).toEqual([]);
  });

  it('offers the remainder when the cart holds only part of the allowance', () => {
    const groups = giftOfferGroups([offer({ quantity: 3, inCart: true, toAdd: 2 })], MENU);

    expect(groups[0].toAdd).toBe(2);
    expect(groups[0].choices[0].inCart).toBe(true);
  });

  it('leaves out a gift the menu does not carry, and a rule left with no choice at all', () => {
    expect(giftOfferGroups([offer({ variantId: 'v-gone' })], MENU)).toEqual([]);
    expect(
      giftOfferGroups([offer({ variantId: 'v-gone' }), offer({ variantId: 'v-fanta' })], MENU)[0]
        .choices,
    ).toEqual([{ variantId: 'v-fanta', name: 'Fanta', image: null, inCart: false }]);
  });

  it('reads an answer without offers as having none', () => {
    expect(giftOfferGroups(undefined, MENU)).toEqual([]);
    expect(giftOfferGroups([], MENU)).toEqual([]);
  });
});
