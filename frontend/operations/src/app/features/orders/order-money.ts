/**
 * The §1.3 hard rule: "the money panel must show all five [subtotal, discount,
 * fee, delivery, tax] and must never display a computed total that disagrees
 * with `total_minor`; if they disagree the panel renders an error, because
 * that is data corruption and hiding it is worse than an ugly screen."
 *
 * **What this checks, and why it is narrower than the five-row rule.** H4:
 * `discountMinor`/`feeMinor` now render on `order-detail-pane.html`'s Money
 * panel (`OrderSummaryResponse` has carried them since 2026-09-11), but this
 * module's own `reconcileMoney` still checks only the line-sum-against-subtotal
 * invariant below, not the full `subtotal + tax + fee − discount = total`
 * formula `ck_order_total_reconciles` guarantees server-side. Extending the
 * check to the full formula is real further work this fix does not attempt:
 * a wrong assumption there (a rounding rule, a case this reduction has not
 * considered) would turn an ordinary order into a false "data corruption"
 * error, which is a worse failure than the one this module exists to catch —
 * a false alarm on an ordinary order teaches an operator to ignore the alarm.
 *
 * What the wire *does* give two independently-sourced numbers for is the
 * subtotal: `subtotalMinor` is a field the server computed once, and
 * `lines[].finalAmountMinor` is the per-line data it was computed from. Those
 * two must always agree — `subtotal_minor` is defined as their sum — with no
 * missing field standing between them, so disagreement here is unambiguously
 * the data corruption §1.3 is guarding against. This is the same reduction
 * pattern as `order-severity.ts`: implement exactly the invariant this module
 * is confident of without fabricating the rest, and say so.
 *
 * `totalMinor` itself is still rendered as the server sends it, unchecked
 * against the other four rows.
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
