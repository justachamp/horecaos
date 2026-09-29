import {
  firstSellableVariant,
  itemAvailability,
  preferredSellableVariant,
  variantAvailability,
} from './item-availability';

const v = (active: boolean, onSaleNow: boolean, id = 'v') => ({
  id,
  active,
  onSaleNow,
  remainingQuantity: null,
});

describe('variantAvailability', () => {
  it('is available only when the variant is orderable AND inside its sale window', () => {
    expect(variantAvailability(v(true, true))).toBe('AVAILABLE');
  });

  it('is sold out when the variant is 86\'d, whatever its schedule says', () => {
    expect(variantAvailability(v(false, true))).toBe('SOLD_OUT');
    expect(variantAvailability(v(false, false))).toBe('SOLD_OUT');
  });

  it('is out of window when orderable but outside every sale window -- distinct from sold out', () => {
    expect(variantAvailability(v(true, false))).toBe('OUT_OF_SALE_WINDOW');
  });
});

describe('itemAvailability', () => {
  it('is available as soon as one variant is sellable', () => {
    expect(itemAvailability({ variants: [v(false, true, 'a'), v(true, true, 'b')] })).toBe('AVAILABLE');
    expect(itemAvailability({ variants: [v(true, false, 'a'), v(true, true, 'b')] })).toBe('AVAILABLE');
  });

  it('is out of window when nothing is sellable but something is only waiting for its window', () => {
    expect(itemAvailability({ variants: [v(false, true, 'a'), v(true, false, 'b')] })).toBe(
      'OUT_OF_SALE_WINDOW',
    );
  });

  it('is sold out when every variant is 86\'d', () => {
    expect(itemAvailability({ variants: [v(false, true, 'a'), v(false, true, 'b')] })).toBe('SOLD_OUT');
  });

  it('is sold out for a product with no variants at all, never available', () => {
    expect(itemAvailability({ variants: [] })).toBe('SOLD_OUT');
  });
});

describe('firstSellableVariant', () => {
  it('picks the first variant that is both orderable and on sale', () => {
    const variants = [v(false, true, 'a'), v(true, false, 'b'), v(true, true, 'c'), v(true, true, 'd')];

    expect(firstSellableVariant({ variants })?.id).toBe('c');
  });

  it('is null when nothing can be bought right now', () => {
    expect(firstSellableVariant({ variants: [v(false, true), v(true, false)] })).toBeNull();
  });
});

describe('preferredSellableVariant', () => {
  const d = (active: boolean, onSaleNow: boolean, id: string, isDefault = false) => ({
    ...v(active, onSaleNow, id),
    isDefault,
  });

  it('is the authored default when it can be bought, wherever it sits in the list', () => {
    const variants = [d(true, true, 'a'), d(true, true, 'b', true)];

    expect(preferredSellableVariant({ variants })?.id).toBe('b');
  });

  it('skips a default that is sold out or off its sale window for the first portion that can be bought', () => {
    expect(
      preferredSellableVariant({ variants: [d(false, true, 'a', true), d(true, true, 'b')] })?.id,
    ).toBe('b');
    expect(
      preferredSellableVariant({ variants: [d(true, false, 'a', true), d(true, true, 'b')] })?.id,
    ).toBe('b');
  });

  it('is the first sellable portion when nothing is marked default', () => {
    expect(
      preferredSellableVariant({ variants: [v(true, false, 'a'), v(true, true, 'b'), v(true, true, 'c')] })
        ?.id,
    ).toBe('b');
  });

  it('is null when nothing can be bought right now', () => {
    expect(preferredSellableVariant({ variants: [d(false, true, 'a', true), d(true, false, 'b')] })).toBeNull();
  });
});
