import {
  discountLines,
  noteLines,
  promoOutcomeKey,
  type AppliedPromotion,
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
