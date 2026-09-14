import { OrderSummaryResponse } from './order-summary';

/**
 * Selection and bulk-action eligibility (orders.md §2.10, wave P07) — pure
 * functions over the rows a caller already has, so the queue component only
 * has to hold the {@code Set<orderId>} and call these, and so the "is this
 * action offered" logic is unit-testable without a component harness.
 *
 * §2.10: "An action is offered only when it is valid for every selected
 * row. Not disabled — absent, with one line saying why." Every eligibility
 * function here reads `OrderSummaryResponse.actions` — the server-supplied
 * `actions[]` array (§4.2) — never invents its own rule for what a status
 * permits; the same discipline `order-actions.ts`'s own doc insists on for a
 * single row.
 */

/** Selects (or, if every id in {@link pageIds} is already selected, deselects) one page's worth of rows — never a silent select-all across a filtered set of unknown size. */
export function toggleSelectPage(
  selected: ReadonlySet<string>,
  pageIds: readonly string[],
): ReadonlySet<string> {
  if (pageIds.length === 0) {
    return selected;
  }
  const allSelected = pageIds.every((id) => selected.has(id));
  const next = new Set(selected);
  for (const id of pageIds) {
    if (allSelected) {
      next.delete(id);
    } else {
      next.add(id);
    }
  }
  return next;
}

export function toggleOne(selected: ReadonlySet<string>, orderId: string): ReadonlySet<string> {
  const next = new Set(selected);
  if (next.has(orderId)) {
    next.delete(orderId);
  } else {
    next.add(orderId);
  }
  return next;
}

/**
 * Drops any selected id that no longer names a loaded row — the queue's own
 * "selection across a page boundary" rule: a refetch that no longer includes
 * an order (it left the fetched window, or the operator's filters narrowed
 * past it) silently drops it from the selection rather than carrying a
 * pointer at nothing forward into the next bulk submission.
 *
 * Returns the same {@link selected} instance when nothing needed pruning, so
 * a caller holding this in a signal does not re-render on every refresh.
 */
export function pruneSelection(
  selected: ReadonlySet<string>,
  presentIds: ReadonlySet<string>,
): ReadonlySet<string> {
  let changed = false;
  const next = new Set<string>();
  for (const id of selected) {
    if (presentIds.has(id)) {
      next.add(id);
    } else {
      changed = true;
    }
  }
  return changed ? next : selected;
}

function hasAction(order: OrderSummaryResponse, action: string): boolean {
  return (order.actions ?? []).some((entry) => entry.action === action);
}

/** How many of the selected orders do **not** support `CANCEL` right now — 0 means the bulk action is offered. */
export function bulkCancelIneligibleCount(selected: readonly OrderSummaryResponse[]): number {
  return selected.filter((order) => !hasAction(order, 'CANCEL')).length;
}

export function bulkCancelEligible(selected: readonly OrderSummaryResponse[]): boolean {
  return selected.length > 0 && bulkCancelIneligibleCount(selected) === 0;
}

/**
 * The bulk targets `OrderBulkActionService.ADVANCE_TARGETS` accepts — routine,
 * reversible-in-effect kitchen-path moves only (ADR 0039). A per-row single
 * advance may legitimately target other statuses too (`CONFIRMED`, say, off
 * `RECEIVED`), but the *bulk* endpoint refuses every one of those with a
 * batch-wide 400 before touching a single order, so a target outside this set
 * must never be offered as a bulk action.
 */
const BULK_ADVANCE_TARGETS: ReadonlySet<string> = new Set(['PREPARING', 'READY', 'FULFILLING']);

/**
 * The one `targetStatus` a bulk `ADVANCE` may use, or `null` when the
 * selection cannot share one — some row lacks an `ADVANCE` action at all, the
 * rows are not all at the same stage (a `CONFIRMED` row's next status differs
 * from a `PREPARING` row's), or the shared target is not one
 * {@link BULK_ADVANCE_TARGETS} allows (e.g. every selected row is `RECEIVED`,
 * whose only `ADVANCE` target is `CONFIRMED`). A single bulk request carries
 * exactly one `targetStatus` (`OrderBulkActionService.BulkActionCommand`), so
 * a mixed selection — or one whose shared target the bulk endpoint refuses
 * outright — has no single call that advances all of it correctly.
 */
export function bulkAdvanceTarget(selected: readonly OrderSummaryResponse[]): string | null {
  if (selected.length === 0) {
    return null;
  }
  let target: string | null = null;
  for (const order of selected) {
    const advance = (order.actions ?? []).find((entry) => entry.action === 'ADVANCE');
    if (!advance?.targetStatus) {
      return null;
    }
    if (target === null) {
      target = advance.targetStatus;
    } else if (target !== advance.targetStatus) {
      return null;
    }
  }
  return target !== null && BULK_ADVANCE_TARGETS.has(target) ? target : null;
}
