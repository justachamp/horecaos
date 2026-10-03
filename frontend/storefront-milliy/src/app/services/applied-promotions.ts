/**
 * The promotions behind a priced cart, as the platform reports them (ADR 0140).
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
 * priced cart's `discountMinor`; the other two are already inside the figures the
 * platform returns, and are explained rather than added.
 */
export type PromotionEffect = 'DISCOUNT' | 'DELIVERY_DISCOUNT' | 'SURCHARGE';

export interface AppliedPromotion {
  readonly source: PromotionSource;
  readonly effect: PromotionEffect;
  /** Always positive; the direction is the effect's. */
  readonly amountMinor: number;
}

/**
 * What became of the code on the cart. The code stays on the cart whatever the
 * outcome, so a customer who typed one and sees no discount needs to be told why.
 */
export type PromoCodeOutcome = 'APPLIED' | 'OFFERS_ARE_BETTER' | 'NOT_APPLICABLE' | 'NOT_VALID';

/** One printable line: the translation key of its label and the platform's amount. */
export interface PromotionLine {
  readonly labelKey: string;
  readonly source: PromotionSource;
  readonly amountMinor: number;
}

/**
 * The discounts, to be shown as lines that subtract. Their sum is `discountMinor`.
 * An offer nobody typed and a code are different lines, because only the customer's
 * own code is theirs to remove.
 */
export function discountLines(
  applied: readonly AppliedPromotion[] | null | undefined,
): readonly PromotionLine[] {
  return (applied ?? [])
    .filter((entry) => entry.effect === 'DISCOUNT' && entry.amountMinor > 0)
    .map((entry) => ({
      labelKey: entry.source === 'PROMO_CODE' ? 'cart.promoCode' : 'cart.offerDiscount',
      source: entry.source,
      amountMinor: entry.amountMinor,
    }));
}

/**
 * The benefits already reflected in the delivery price or the goods, to be shown as
 * a caption rather than a line, because adding them to the sum would count them
 * twice.
 */
export function noteLines(
  applied: readonly AppliedPromotion[] | null | undefined,
): readonly PromotionLine[] {
  return (applied ?? [])
    .filter((entry) => entry.effect !== 'DISCOUNT' && entry.amountMinor > 0)
    .map((entry) => ({
      labelKey: entry.effect === 'SURCHARGE' ? 'cart.surchargeNote' : 'cart.deliveryOfferNote',
      source: entry.source,
      amountMinor: entry.amountMinor,
    }));
}

/**
 * The sentence for a code that did not move the price, or null when there is nothing
 * to say: no code, or a code that applied.
 */
export function promoOutcomeKey(outcome: PromoCodeOutcome | null | undefined): string | null {
  switch (outcome) {
    case 'OFFERS_ARE_BETTER':
      return 'checkout.promoOffersBetter';
    case 'NOT_APPLICABLE':
      return 'checkout.promoNotApplicable';
    case 'NOT_VALID':
      return 'checkout.promoNoLongerValid';
    default:
      return null;
  }
}
