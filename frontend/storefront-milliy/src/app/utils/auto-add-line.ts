import type { CartResponseItem } from '../types/cart.types';

/** Thousandths, the scale a quantity is stored at, so `0.2 + 0.1` is `0.3` and not `0.30000000000000004`. */
function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}

/**
 * Whether the stepper may take one step down (ADR 0158).
 *
 * A line the customer chose goes down to nothing and is then removed. A line a rule added stops at its own
 * floor, `minimumQuantity`, and reaches zero only when the rule says it is `removable`: the screen draws the
 * control from what the cart says about the line and never from knowledge of the rules.
 */
export function canStepDown(item: CartResponseItem, step: number): boolean {
  const auto = item.autoAdd;
  if (!auto) {
    return item.quantity > 0;
  }
  const next = tidy(item.quantity - step);
  return next >= auto.minimumQuantity && (next > 0 || auto.removable);
}

/** Whether the stepper may take one step up: a line a rule added stops at its own ceiling. */
export function canStepUp(item: CartResponseItem, step: number): boolean {
  const auto = item.autoAdd;
  return !auto || tidy(item.quantity + step) <= auto.maximumQuantity;
}
