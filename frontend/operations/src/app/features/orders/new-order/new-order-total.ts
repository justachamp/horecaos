import {
  catchweightPriceOf,
  initialQuantity,
  lineQuantityStep,
  timesQuantity,
} from '../../../core/format/quantity';
import type { MenuPhysicalFacts } from './new-order-api';

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
  /** What the customer reads on the option, when the menu names it; the code is shown otherwise. */
  readonly name?: string | null;
  readonly quantity: number;
  /** Null means unpriced — see this module's own doc for why that is never treated as zero. */
  readonly amountMinor: number | null;
}

/** ADR 0136: one component picked inside a combo, with what one unit of it costs in this combo. */
export interface BasketComboPick {
  readonly componentId: string;
  readonly name: string;
  /** How many times the component was picked within its group. */
  readonly pickQuantity: number;
  /** Units one pick puts on the order; the price below is per unit. */
  readonly unitQuantity: number;
  /** Null means unpriced — see this module's own doc for why that is never treated as zero. */
  readonly amountMinor: number | null;
}

/** ADR 0136: what a combo line was built from. */
export interface BasketCombo {
  readonly picks: readonly BasketComboPick[];
}

/**
 * One combo's price: every pick's component price per unit, times units per pick, times how many
 * times it was picked. Null when any component is unpriced — the combo has no price of its own to
 * fall back on, so a missing component price is a missing price, never a smaller total.
 */
export function comboAmountMinor(combo: BasketCombo): number | null {
  let total = 0;
  for (const pick of combo.picks) {
    if (pick.amountMinor === null) {
      return null;
    }
    total += pick.amountMinor * pick.unitQuantity * pick.pickQuantity;
  }
  return total;
}

/**
 * ADR 0137: what makes a line's amount an estimate. The variant's price is per `quantumGrams`,
 * and until the kitchen weighs it at handover the line is priced at the nominal weight of
 * every unit.
 */
export interface BasketCatchweight {
  readonly quantumGrams: number;
  readonly nominalGramsPerUnit: number;
}

export interface BasketLine {
  /** Client-local identity for the line (not the variant id — two lines can share a variant with different modifiers). */
  readonly lineKey: string;
  readonly variantId: string;
  readonly productName: string;
  /** A decimal for a splittable variant (ADR 0137): `0.5` is half a portion. */
  readonly quantity: number;
  /**
   * The price row: per unit, or per `catchweight.quantumGrams` when the line is sold by weight.
   * Null means unpriced. For a combo line it is {@link comboAmountMinor} of {@link combo}: the
   * container itself is never priced.
   */
  readonly unitAmountMinor: number | null;
  /** ADR 0136: set exactly when this line is a combo; `variantId` is then its container and `quantity` counts combos. */
  readonly combo?: BasketCombo;
  /** The step the quantity moves in — the variant's portion size; absent means whole units. */
  readonly portionStep?: number;
  /** Present when the line is sold by weight: its amount is provisional until it is weighed. */
  readonly catchweight?: BasketCatchweight | null;
  readonly modifiers: readonly BasketModifierSelection[];
  /** Row 2.1b: the coded presets the operator checked when this line was added. */
  readonly commentPresetCodes: readonly string[];
  readonly customerNote: string | null;
  /** Whether the menu still lists this variant as sellable — orders.md §5.7's "item became unavailable" state. */
  readonly orderable: boolean;
  /**
   * Row 4.2g: the variant's own sale-window state as of the last menu read.
   * Snapshotted at add time exactly like {@link orderable} — this screen has
   * no live menu poll, so a window that closes after the line was added is
   * only caught at submit, where the server's own `ITEM_OUT_OF_SALE_WINDOW`
   * refusal is what actually flags it (see `submit`'s own doc).
   */
  readonly onSaleNow: boolean;
}

export interface BasketTotal {
  readonly currency: string | null;
  readonly subtotalMinor: number;
  /** False when any line or selected modifier has no price on file. */
  readonly fullyPriced: boolean;
  /** False when any line's variant is no longer orderable, or is outside its own sale window (row 4.2g). */
  readonly allAvailable: boolean;
  /** True while any line is sold by weight: the total is an estimate the weighing at handover replaces. */
  readonly provisional: boolean;
}

/**
 * What one unit costs before modifiers: the price row, or for a line sold by weight the price
 * of one nominal-weight unit. Null when unpriced.
 */
export function lineUnitAmountMinor(line: BasketLine): number | null {
  if (line.unitAmountMinor === null) {
    return null;
  }
  return line.catchweight
    ? catchweightPriceOf(
        line.unitAmountMinor,
        line.catchweight.quantumGrams,
        line.catchweight.nominalGramsPerUnit,
      )
    : line.unitAmountMinor;
}

/**
 * One line's contribution to the subtotal, or `null` when any of its components is unpriced.
 *
 * Priced the way `PricingEngine` prices it (ADR 0137): the base rounded once on the line — a
 * portion is `unit × quantity`, a weighed line `price × grams ÷ quantum` — and the modifiers
 * rounded once on their own sum, so the running total cannot disagree with the quote the
 * platform makes when the order is placed.
 */
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
  const base = line.catchweight
    ? catchweightPriceOf(
        line.unitAmountMinor,
        line.catchweight.quantumGrams,
        line.quantity * line.catchweight.nominalGramsPerUnit,
      )
    : timesQuantity(line.unitAmountMinor, line.quantity);
  return base + timesQuantity(modifiersMinor, line.quantity);
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
  let provisional = false;
  for (const line of lines) {
    if (line.catchweight) {
      provisional = true;
    }
    if (!line.orderable || !line.onSaleNow) {
      allAvailable = false;
    }
    const amount = lineAmountMinor(line);
    if (amount === null) {
      fullyPriced = false;
      continue;
    }
    subtotalMinor += amount;
  }
  return { currency, subtotalMinor, fullyPriced, allAvailable, provisional };
}

/**
 * What a menu variant makes of a basket line (ADR 0137): the step its quantity moves in, the
 * quantity a first tap starts at, and — for a variant sold by weight — what its amount is
 * estimated from. A catchweight variant that has no authored estimate is estimated from its net
 * weight, as the platform does; one with neither cannot be estimated and is treated as plain
 * (the platform refuses to publish it, so this is a defence, not a path).
 */
export function basketFactsFor(physical: MenuPhysicalFacts | null | undefined): {
  readonly portionStep: number;
  readonly initialQuantity: number;
  readonly catchweight: BasketCatchweight | null;
} {
  const portionStep = lineQuantityStep(physical);
  const nominal = physical?.catchweightNominalGrams ?? physical?.netWeightGrams ?? null;
  const quantum = physical?.catchweightQuantumGrams ?? null;
  return {
    portionStep,
    initialQuantity: initialQuantity(portionStep),
    catchweight:
      physical?.catchweight && quantum !== null && nominal !== null
        ? { quantumGrams: quantum, nominalGramsPerUnit: nominal }
        : null,
  };
}
