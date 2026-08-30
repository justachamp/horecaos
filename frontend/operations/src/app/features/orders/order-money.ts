/**
 * The §1.3 hard rule: "the money panel must show all five [subtotal, discount,
 * fee, delivery, tax] and must never display a computed total that disagrees
 * with `total_minor`; if they disagree the panel renders an error, because
 * that is data corruption and hiding it is worse than an ugly screen."
 *
 * **What this checks, and why it is narrower than the five-row rule.**
 * `OrderDetailResponse` (`OperationsOrderController.java`) does not carry
 * `discount_minor` or `fee_minor` yet — only `subtotalMinor` and `taxMinor`
 * beside the summary's `totalMinor`. Comparing `subtotal + tax` against
 * `total` would produce a false "corrupted" error on any order that legitimately
 * carries a fee or a discount, which the current wire shape cannot rule out.
 * That is a worse failure than the one this module exists to catch: a false
 * alarm on an ordinary order teaches an operator to ignore the alarm.
 *
 * What the wire *does* give two independently-sourced numbers for is the
 * subtotal: `subtotalMinor` is a field the server computed once, and
 * `lines[].finalAmountMinor` is the per-line data it was computed from. Those
 * two must always agree — `subtotal_minor` is defined as their sum — with no
 * missing field standing between them, so disagreement here is unambiguously
 * the data corruption §1.3 is guarding against, not an unmodelled fee. This is
 * the same reduction pattern as `order-severity.ts`: implement exactly the
 * invariant the current wire shape can support without fabricating the rest,
 * and say so.
 *
 * `totalMinor` itself is rendered as the server sends it; extending this check
 * to it is exactly the work of adding `discountMinor`/`feeMinor` to
 * `OrderDetailResponse`, not something this client can do without them.
 */

export interface MoneyReconciliation {
  readonly lineSumMinor: number;
  readonly subtotalMinor: number;
  readonly totalMinor: number;
  /** False means data corruption — render the error, never a total anyway. */
  readonly reconciles: boolean;
}

export function reconcileMoney(
  lines: readonly { readonly finalAmountMinor: number }[],
  subtotalMinor: number,
  totalMinor: number,
): MoneyReconciliation {
  const lineSumMinor = lines.reduce((sum, line) => sum + line.finalAmountMinor, 0);
  return {
    lineSumMinor,
    subtotalMinor,
    totalMinor,
    reconciles: lineSumMinor === subtotalMinor,
  };
}
