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

/**
 * A free gift a firing rule would price free if the cart held it (ADR 0140): an offer, never a
 * line. Pricing adds nothing to the cart; the gift is free only once the customer puts it in and
 * the cart is priced again. A rule that names several gift variants yields one offer per variant,
 * any of which fills the same allowance.
 */
export interface GiftOffer {
  /** Opaque key of the rule behind the offer, stable across quotes; never shown. */
  readonly ruleId: string;
  readonly variantId: string;
  /** The units the rule gives free once taken up, across its gift variants. */
  readonly quantity: number;
  /** Whether the cart already holds this variant. */
  readonly inCart: boolean;
  /** The units still missing for the allowance to be complete; zero when there is nothing to add. */
  readonly toAdd: number;
}

/** One gift a customer may take, named in their language by the menu. */
export interface GiftChoice {
  readonly variantId: string;
  readonly name: string;
  readonly image: string | null;
  readonly inCart: boolean;
}

/** The gifts of one rule: the units still to add, and the variants any of which will do. */
export interface GiftOfferGroup {
  readonly ruleId: string;
  readonly toAdd: number;
  readonly choices: readonly GiftChoice[];
}

/**
 * The offers a screen may print: one group per rule, in the order the platform listed them,
 * each variant named from the menu (`known`, by variant id).
 *
 * An offer with nothing left to add is not an offer (the gift is already in the basket and the
 * discount line says so), a gift the menu does not carry cannot be added and is not offered, and
 * a rule left with no choice is dropped rather than shown empty.
 */
export function giftOfferGroups(
  offers: readonly GiftOffer[] | null | undefined,
  known: ReadonlyMap<string, { readonly name: string; readonly image: string | null }>,
): readonly GiftOfferGroup[] {
  const groups = new Map<string, { ruleId: string; toAdd: number; choices: GiftChoice[] }>();
  for (const offer of offers ?? []) {
    const gift = known.get(offer.variantId);
    if (offer.toAdd <= 0 || !gift) {
      continue;
    }
    // One rule can hold more than one gift action, each with its own allowance: those are
    // separate offers even though they share the rule's key.
    const key = `${offer.ruleId}|${offer.quantity}|${offer.toAdd}`;
    const group = groups.get(key) ?? { ruleId: offer.ruleId, toAdd: offer.toAdd, choices: [] };
    group.choices.push({
      variantId: offer.variantId,
      name: gift.name,
      image: gift.image,
      inCart: offer.inCart,
    });
    groups.set(key, group);
  }
  return [...groups.values()];
}
