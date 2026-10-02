import { describe, expect, it } from 'vitest';

import {
  BasketComboPick,
  BasketLine,
  comboAmountMinor,
  computeBasketTotal,
  lineAmountMinor,
  lineUnitAmountMinor,
} from './new-order-total';

function line(overrides: Partial<BasketLine> = {}): BasketLine {
  return {
    lineKey: 'l1',
    variantId: 'v1',
    productName: 'Osh',
    quantity: 1,
    unitAmountMinor: 30_000,
    modifiers: [],
    commentPresetCodes: [],
    customerNote: null,
    orderable: true,
    onSaleNow: true,
    ...overrides,
  };
}

describe('lineAmountMinor', () => {
  it('multiplies unit price by quantity with no modifiers', () => {
    expect(lineAmountMinor(line({ unitAmountMinor: 30_000, quantity: 2 }))).toBe(60_000);
  });

  it('adds every modifier price before multiplying by quantity', () => {
    const withModifiers = line({
      unitAmountMinor: 30_000,
      quantity: 2,
      modifiers: [
        { optionId: 'm1', code: 'EXTRA_CHEESE', quantity: 1, amountMinor: 5_000 },
        { optionId: 'm2', code: 'NO_ONION', quantity: 1, amountMinor: 0 },
      ],
    });
    // (30_000 + 5_000 + 0) * 2
    expect(lineAmountMinor(withModifiers)).toBe(70_000);
  });

  it('multiplies a modifier by its own selected quantity, not just the line quantity', () => {
    const withRepeatedModifier = line({
      unitAmountMinor: 20_000,
      quantity: 1,
      modifiers: [{ optionId: 'm1', code: 'EXTRA_SHOT', quantity: 3, amountMinor: 2_000 }],
    });
    // 20_000 + (2_000 * 3)
    expect(lineAmountMinor(withRepeatedModifier)).toBe(26_000);
  });

  it('is null when the variant itself has no price on file — never treated as zero', () => {
    expect(lineAmountMinor(line({ unitAmountMinor: null }))).toBeNull();
  });

  it('is null when any selected modifier has no price on file, even if the variant is priced', () => {
    const unpricedModifier = line({
      unitAmountMinor: 30_000,
      modifiers: [{ optionId: 'm1', code: 'MYSTERY', quantity: 1, amountMinor: null }],
    });
    expect(lineAmountMinor(unpricedModifier)).toBeNull();
  });
});

describe('computeBasketTotal', () => {
  it('reconciles the total against the sum of every priced line', () => {
    const lines = [
      line({ lineKey: 'a', unitAmountMinor: 30_000, quantity: 2 }),
      line({ lineKey: 'b', unitAmountMinor: 18_000, quantity: 1 }),
    ];
    const total = computeBasketTotal(lines, 'UZS');
    expect(total.subtotalMinor).toBe(78_000);
    expect(total.currency).toBe('UZS');
    expect(total.fullyPriced).toBe(true);
    expect(total.allAvailable).toBe(true);
  });

  it('an empty basket totals to zero and is trivially fully priced', () => {
    const total = computeBasketTotal([], 'UZS');
    expect(total.subtotalMinor).toBe(0);
    expect(total.fullyPriced).toBe(true);
  });

  it('flags fullyPriced false and excludes the unpriced line from the sum, rather than counting it as zero', () => {
    const lines = [
      line({ lineKey: 'priced', unitAmountMinor: 30_000, quantity: 1 }),
      line({ lineKey: 'unpriced', unitAmountMinor: null, quantity: 5 }),
    ];
    const total = computeBasketTotal(lines, 'UZS');
    // If the unpriced line were silently treated as zero, this would still be
    // 30_000 — the same wrong number a data-corrupted `total_minor` produces
    // (`order-money.ts`'s own failure mode). The guard is `fullyPriced`, not
    // the sum: an operator reading only the number would not see the gap.
    expect(total.subtotalMinor).toBe(30_000);
    expect(total.fullyPriced).toBe(false);
  });

  it('flags allAvailable false when a line is no longer orderable, independent of pricing', () => {
    const lines = [line({ orderable: false })];
    const total = computeBasketTotal(lines, 'UZS');
    expect(total.allAvailable).toBe(false);
    // Still priced — availability and pricing are independent facts, and the
    // screen must be able to tell "stopped" from "not priced" apart.
    expect(total.fullyPriced).toBe(true);
  });

  it('row 4.2g: flags allAvailable false when a line left its sale window, independent of pricing', () => {
    const lines = [line({ onSaleNow: false })];
    const total = computeBasketTotal(lines, 'UZS');
    expect(total.allAvailable).toBe(false);
    expect(total.fullyPriced).toBe(true);
  });

  it('breaking the guard — an unpriced line contributing zero instead of being excluded — must fail this test', () => {
    // Regression harness for the reduction itself: if `computeBasketTotal`
    // is ever "simplified" to `amount ?? 0`, this line still sums to
    // 30_000 but `fullyPriced` silently stays true. Asserting both keeps
    // that mutation caught.
    const lines = [
      line({ lineKey: 'priced', unitAmountMinor: 30_000, quantity: 1 }),
      line({ lineKey: 'unpriced', unitAmountMinor: null, quantity: 1 }),
    ];
    const total = computeBasketTotal(lines, 'UZS');
    expect(total.fullyPriced).toBe(false);
  });
});

describe('comboAmountMinor (ADR 0136)', () => {
  const pick = (overrides: Partial<BasketComboPick> = {}): BasketComboPick => ({
    componentId: 'c1',
    name: 'Burger',
    pickQuantity: 1,
    unitQuantity: 1,
    amountMinor: 25_000,
    ...overrides,
  });

  it('is the sum of each pick’s price per unit, times the units one pick puts on the order, times how often it was picked', () => {
    expect(
      comboAmountMinor({
        picks: [
          pick(),
          pick({
            componentId: 'c2',
            name: 'Cola',
            pickQuantity: 2,
            unitQuantity: 2,
            amountMinor: 3_000,
          }),
        ],
      }),
    ).toBe(25_000 + 3_000 * 2 * 2);
  });

  it('is unknown while any picked component has no price, never a smaller sum', () => {
    expect(
      comboAmountMinor({ picks: [pick(), pick({ componentId: 'c2', amountMinor: null })] }),
    ).toBeNull();
  });

  it('is multiplied by the combo count when the line is totalled', () => {
    const combo = { picks: [pick({ unitQuantity: 2, amountMinor: 1_000 })] };

    expect(
      lineAmountMinor(line({ quantity: 3, unitAmountMinor: comboAmountMinor(combo), combo })),
    ).toBe(6_000);
  });
});

describe('a portion and a weighed line (ADR 0137)', () => {
  it('prices half a portion at half the unit price', () => {
    expect(lineAmountMinor(line({ unitAmountMinor: 38_000, quantity: 0.5 }))).toBe(19_000);
  });

  it('rounds a portion once, on the line, half up — as the server does', () => {
    expect(lineAmountMinor(line({ unitAmountMinor: 18_001, quantity: 0.5 }))).toBe(9_001);
  });

  it('prices modifiers by the portion too, rounded once on their own sum', () => {
    const withModifier = line({
      unitAmountMinor: 38_000,
      quantity: 0.5,
      modifiers: [{ optionId: 'm1', code: 'EXTRA', quantity: 1, amountMinor: 5_001 }],
    });
    // 19 000 + round(2 500.5)
    expect(lineAmountMinor(withModifier)).toBe(19_000 + 2_501);
  });

  it('prices a catchweight line from its price per quantum and the nominal weight of every unit', () => {
    const cake = line({
      unitAmountMinor: 15_000, // per 100 g
      quantity: 1,
      catchweight: { quantumGrams: 100, nominalGramsPerUnit: 1_200 },
    });

    expect(lineAmountMinor(cake)).toBe(180_000);
    expect(lineAmountMinor({ ...cake, quantity: 2 })).toBe(360_000);
  });

  it('a catchweight line’s modifiers are not weighed', () => {
    const cake = line({
      unitAmountMinor: 15_000,
      quantity: 1,
      catchweight: { quantumGrams: 100, nominalGramsPerUnit: 1_200 },
      modifiers: [{ optionId: 'm1', code: 'BOX', quantity: 1, amountMinor: 3_000 }],
    });

    expect(lineAmountMinor(cake)).toBe(183_000);
  });

  it('knows what one unit costs, for an aggregator line that states a unit price', () => {
    expect(lineUnitAmountMinor(line({ unitAmountMinor: 30_000 }))).toBe(30_000);
    expect(
      lineUnitAmountMinor(
        line({
          unitAmountMinor: 15_000,
          catchweight: { quantumGrams: 100, nominalGramsPerUnit: 1_200 },
        }),
      ),
    ).toBe(180_000);
    expect(lineUnitAmountMinor(line({ unitAmountMinor: null }))).toBeNull();
  });

  it('says the total is provisional while any line is sold by weight', () => {
    const plain = computeBasketTotal([line()], 'UZS');
    const weighed = computeBasketTotal(
      [
        line(),
        line({
          lineKey: 'cake',
          unitAmountMinor: 15_000,
          catchweight: { quantumGrams: 100, nominalGramsPerUnit: 1_200 },
        }),
      ],
      'UZS',
    );

    expect(plain.provisional).toBe(false);
    expect(weighed.provisional).toBe(true);
    expect(weighed.subtotalMinor).toBe(30_000 + 180_000);
  });
});
