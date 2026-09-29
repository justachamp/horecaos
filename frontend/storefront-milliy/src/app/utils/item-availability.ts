/**
 * Whether a dish, or one portion of it, can be bought right now -- and if not,
 * which of the two very different reasons it is.
 *
 * The published menu draws the line the customer has to see:
 *
 * - **sold out** (`active` false): 86'd, or inventory says none is left. Shown,
 *   not hidden (ADR 0033, rows 4.4c/4.4d).
 * - **out of window** (`onSaleNow` false): the item is fine and orderable, but
 *   its own sale schedule excludes this moment (row 4.2g) -- "try again during
 *   breakfast hours", not "gone".
 *
 * Sold out wins when both are true: a portion that is 86'd is not coming back
 * at its next window either, so saying "not on sale right now" would promise
 * something the kitchen cannot deliver.
 *
 * One definition, read by the menu grid, the product page, the cart and the
 * table screen, so the four can never disagree about the same dish.
 */
export type ItemAvailability = 'AVAILABLE' | 'SOLD_OUT' | 'OUT_OF_SALE_WINDOW';

/** The two facts every screen's variant shape carries under these names. */
export interface AvailabilityVariant {
  readonly active: boolean;
  readonly onSaleNow: boolean;
}

export function variantAvailability(variant: AvailabilityVariant): ItemAvailability {
  if (!variant.active) {
    return 'SOLD_OUT';
  }
  return variant.onSaleNow ? 'AVAILABLE' : 'OUT_OF_SALE_WINDOW';
}

/**
 * A product is available when any one portion is. Otherwise it is out of window
 * if some portion is merely waiting for its window, and sold out only when every
 * portion is gone. A product with no portions at all is sold out, never
 * available: there is nothing a customer could put in the basket.
 */
export function itemAvailability(item: {
  readonly variants: readonly AvailabilityVariant[];
}): ItemAvailability {
  let waiting = false;
  for (const variant of item.variants) {
    const availability = variantAvailability(variant);
    if (availability === 'AVAILABLE') {
      return 'AVAILABLE';
    }
    if (availability === 'OUT_OF_SALE_WINDOW') {
      waiting = true;
    }
  }
  return waiting ? 'OUT_OF_SALE_WINDOW' : 'SOLD_OUT';
}

/** The first portion that can be bought right now, or null when none can. */
export function firstSellableVariant<T extends AvailabilityVariant>(item: {
  readonly variants: readonly T[];
}): T | null {
  return item.variants.find((variant) => variantAvailability(variant) === 'AVAILABLE') ?? null;
}

/**
 * The portion a customer gets when they have not chosen one: the authored
 * default when it can be bought right now, otherwise the first portion that can.
 * Null when none can be bought right now.
 *
 * The menu grid prices its card from this and the product page preselects it,
 * so the price on the card is the price of the portion that lands in the
 * basket. An authored default that is off its sale window (a breakfast-only
 * portion at 15:00) is skipped, exactly as a sold-out one is.
 */
export function preferredSellableVariant<T extends AvailabilityVariant & { readonly isDefault?: boolean }>(item: {
  readonly variants: readonly T[];
}): T | null {
  return (
    item.variants.find(
      (variant) => variant.isDefault === true && variantAvailability(variant) === 'AVAILABLE',
    ) ?? firstSellableVariant(item)
  );
}
