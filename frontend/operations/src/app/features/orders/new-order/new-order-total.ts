/**
 * The running total (orders.md §5.6's Итого), and the one guarantee it makes:
 * **never silently show a total that treats an unpriced component as free.**
 *
 * §1.3's money rule for the order detail screen is "never display a computed
 * total that disagrees with `total_minor`; if they disagree the panel renders
 * an error, because that is data corruption and hiding it is worse than an
 * ugly screen" (`order-money.ts`). This screen has no `total_minor` to check
 * against before submit — `OperatorOrderingService.place` prices and checks
 * out in one atomic call (create the cart, add every line, price it, check
 * out), and there is no separate preview endpoint this screen's principal can
 * reach: `QuoteController`'s live quote needs `PRICING_READ` at `BRAND` scope,
 * which neither `LOCATION_STAFF` nor `LOCATION_MANAGER` holds at that scope
 * (`PlatformRole.java`) — a `LOCATION_MANAGER` grant covers only its own
 * location, `JdbcAuthorizationService#hasGrant`'s `scope().covers(scope)`
 * never lets a narrower grant answer a broader one. So the running total here
 * is computed from the published menu's own prices (`StorefrontMenu`,
 * server data, just not a quote), reconciled against nothing until the
 * operator actually presses Создать and the server prices the real cart.
 *
 * The reduction this module applies, in `order-money.ts`'s own terms: a line
 * or a selected modifier with `amountMinor: null` never contributes zero to
 * the sum. It flips {@link BasketTotal.fullyPriced} to `false` instead, and
 * the screen renders that as "not yet priced" rather than a total that is
 * quietly wrong by the price of one item.
 */

export interface BasketModifierSelection {
  readonly optionId: string;
  readonly code: string;
  readonly quantity: number;
  /** Null means unpriced — see this module's own doc for why that is never treated as zero. */
  readonly amountMinor: number | null;
}

export interface BasketLine {
  /** Client-local identity for the line (not the variant id — two lines can share a variant with different modifiers). */
  readonly lineKey: string;
  readonly variantId: string;
  readonly productName: string;
  readonly quantity: number;
  readonly unitAmountMinor: number | null;
  readonly modifiers: readonly BasketModifierSelection[];
  readonly customerNote: string | null;
  /** Whether the menu still lists this variant as sellable — orders.md §5.7's "item became unavailable" state. */
  readonly orderable: boolean;
}

export interface BasketTotal {
  readonly currency: string | null;
  readonly subtotalMinor: number;
  /** False when any line or selected modifier has no price on file. */
  readonly fullyPriced: boolean;
  /** False when any line's variant is no longer orderable. */
  readonly allAvailable: boolean;
}

/** One line's contribution to the subtotal, or `null` when any of its components is unpriced. */
export function lineAmountMinor(line: BasketLine): number | null {
  if (line.unitAmountMinor === null) {
    return null;
  }
  let modifiersMinor = 0;
  for (const modifier of line.modifiers) {
    if (modifier.amountMinor === null) {
      return null;
    }
    modifiersMinor += modifier.amountMinor * modifier.quantity;
  }
  return (line.unitAmountMinor + modifiersMinor) * line.quantity;
}

/**
 * Sums every line's {@link lineAmountMinor}. An unpriced line is excluded from
 * the sum (never counted as zero) and reported through {@link BasketTotal.fullyPriced}
 * instead, so the caller can refuse to render a total that silently omitted
 * something rather than showing a wrong one.
 */
export function computeBasketTotal(
  lines: readonly BasketLine[],
  currency: string | null,
): BasketTotal {
  let subtotalMinor = 0;
  let fullyPriced = true;
  let allAvailable = true;
  for (const line of lines) {
    if (!line.orderable) {
      allAvailable = false;
    }
    const amount = lineAmountMinor(line);
    if (amount === null) {
      fullyPriced = false;
      continue;
    }
    subtotalMinor += amount;
  }
  return { currency, subtotalMinor, fullyPriced, allAvailable };
}
