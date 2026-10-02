/**
 * The promotions behind a priced cart or a placed order, as the platform reports
 * them (ADR 0140).
 *
 * The platform names no promotion: an operator's name for one is internal, and a
 * customer-facing title is not a field of the promotion yet. What it says is whether
 * the customer asked for the benefit by typing a code, what the benefit did to the
 * total, and how much. These helpers turn that into lines a screen can print, and
 * compute nothing: every amount is the platform's, shown as it came.
 */

/** Whether the customer asked for it: a typed code, or an offer that applied by itself. */
export type PromotionSource = 'AUTOMATIC' | 'PROMO_CODE';

/**
 * What it did to the total. `DISCOUNT` is taken off the goods and is exactly the
 * `discountMinor` of the priced cart or order; the other two are already inside the
 * figures the platform returns, and are explained rather than added.
 */
export type PromotionEffect = 'DISCOUNT' | 'DELIVERY_DISCOUNT' | 'SURCHARGE';

export interface AppliedPromotion {
  readonly source: PromotionSource;
  readonly effect: PromotionEffect;
  /** Always positive; the direction is the effect's. */
  readonly amountMinor: number;
}

/** One printable line: the translation key of its label and the platform's amount. */
export interface PromotionLine {
  readonly labelKey: string;
  readonly amountMinor: number;
}

/**
 * The discounts, to be shown as lines that subtract. Their sum is `discountMinor`.
 * An offer nobody typed and a code are different lines, because the customer reads
 * them differently.
 */
export function discountLines(
  applied: readonly AppliedPromotion[] | null | undefined,
): readonly PromotionLine[] {
  return (applied ?? [])
    .filter((entry) => entry.effect === 'DISCOUNT' && entry.amountMinor > 0)
    .map((entry) => ({
      labelKey: entry.source === 'PROMO_CODE' ? 'cart.promoCode' : 'cart.offerDiscount',
      amountMinor: entry.amountMinor,
    }));
}

/**
 * The benefits already reflected in the delivery price or the goods, to be shown as a
 * caption rather than a line, because adding them to the sum would count them twice.
 */
export function noteLines(
  applied: readonly AppliedPromotion[] | null | undefined,
): readonly PromotionLine[] {
  return (applied ?? [])
    .filter((entry) => entry.effect !== 'DISCOUNT' && entry.amountMinor > 0)
    .map((entry) => ({
      labelKey: entry.effect === 'SURCHARGE' ? 'cart.surchargeNote' : 'cart.deliveryOfferNote',
      amountMinor: entry.amountMinor,
    }));
}

/** A line as a screen prints it: the translation key of its label and the amount, formatted. */
export interface PromotionRow {
  readonly labelKey: string;
  readonly amount: string;
}

/**
 * The discount rows for a priced cart or a placed order. Their sum is
 * `discountMinor`. A discount the platform reports without saying where it came from
 * (an answer that predates the breakdown) is one generic row, so a total is never
 * left with an unexplained gap.
 */
export function discountRowsFor(
  applied: readonly AppliedPromotion[] | null | undefined,
  discountMinor: number,
  format: (minor: number) => string,
): readonly PromotionRow[] {
  const lines = discountLines(applied);
  if (lines.length === 0) {
    return discountMinor > 0 ? [{ labelKey: 'cart.discount', amount: format(discountMinor) }] : [];
  }
  return lines.map((line) => ({ labelKey: line.labelKey, amount: format(line.amountMinor) }));
}

/** The captions for benefits already inside the delivery price or the goods. */
export function noteRowsFor(
  applied: readonly AppliedPromotion[] | null | undefined,
  format: (minor: number) => string,
): readonly PromotionRow[] {
  return noteLines(applied).map((line) => ({
    labelKey: line.labelKey,
    amount: format(line.amountMinor),
  }));
}
