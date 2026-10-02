import {
  discountLines,
  discountRowsFor,
  noteLines,
  noteRowsFor,
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
      { labelKey: 'cart.offerDiscount', amountMinor: 4_000 },
      { labelKey: 'cart.promoCode', amountMinor: 2_000 },
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
  it('describes a delivery offer and a surcharge, in the order the platform listed them', () => {
    expect(noteLines([SURCHARGE, DELIVERY])).toEqual([
      { labelKey: 'cart.surchargeNote', amountMinor: 1_500 },
      { labelKey: 'cart.deliveryOfferNote', amountMinor: 5_000 },
    ]);
  });

  it('never repeats a discount, which is already a line of its own', () => {
    expect(noteLines([AUTO_DISCOUNT, CODE_DISCOUNT])).toEqual([]);
  });
});

const FORMAT = (minor: number) => `${minor} som`;

describe('discountRowsFor', () => {
  it("formats the platform's own amounts and adds nothing of its own", () => {
    expect(discountRowsFor([AUTO_DISCOUNT, CODE_DISCOUNT], 6_000, FORMAT)).toEqual([
      { labelKey: 'cart.offerDiscount', amount: '4000 som' },
      { labelKey: 'cart.promoCode', amount: '2000 som' },
    ]);
  });

  it('keeps a discount with no breakdown as one generic row, so the total has no unexplained gap', () => {
    expect(discountRowsFor(undefined, 5_000, FORMAT)).toEqual([
      { labelKey: 'cart.discount', amount: '5000 som' },
    ]);
  });

  it('has no row when nothing was discounted', () => {
    expect(discountRowsFor([], 0, FORMAT)).toEqual([]);
    expect(discountRowsFor(undefined, 0, FORMAT)).toEqual([]);
  });

  it('does not invent a generic row beside a delivery offer: that is a caption, not a discount of the goods', () => {
    expect(discountRowsFor([DELIVERY], 0, FORMAT)).toEqual([]);
  });
});

describe('noteRowsFor', () => {
  it('formats the captions', () => {
    expect(noteRowsFor([DELIVERY, SURCHARGE], FORMAT)).toEqual([
      { labelKey: 'cart.deliveryOfferNote', amount: '5000 som' },
      { labelKey: 'cart.surchargeNote', amount: '1500 som' },
    ]);
  });
});
