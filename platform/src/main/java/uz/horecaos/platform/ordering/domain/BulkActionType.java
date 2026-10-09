package uz.horecaos.platform.ordering.domain;

/**
 * The closed set of actions a bulk request may apply (ADR 0039).
 *
 * <p>Advancing state is not the same act as cancelling, and the two are not
 * treated alike here. {@link #ADVANCE} moves a live order between the
 * non-terminal kitchen-path states {@code OrderStateService.advance} already
 * governs — {@code CONFIRMED -> PREPARING -> READY -> FULFILLING} — the same
 * routine, reversible-in-effect move a line cook makes on one order all shift,
 * now applied to many at once. {@link #CANCEL} ends an order, through the same
 * tenant reason registry and stock/liability consequence a single cancellation
 * goes through ({@code OrderOutcomeService.cancel}); it is the dangerous half,
 * because a hundred cancellations in one click is how one operator's mistake
 * becomes a hundred refunds.
 *
 * <p>{@link #ASSIGN_COURIER} was ADR 0039's first supported action and ADR 0172 adds it
 * as the third member: it reuses this class's one-transaction-per-item rule, its
 * 200-order cap and its idempotency, and changes none of them.
 *
 * <p>Deliberately absent, and why:
 *
 * <ul>
 *   <li>{@code COMPLETE} — completion needs a reason validated against the
 *       order's fulfilment mode ({@code OrderOutcomeService.complete}), the
 *       same shape of decision as cancellation, and adding a second terminal
 *       bulk action before the first has real operational usage is exactly
 *       the surface-area growth ADR 0039's own "closed set" argument warns
 *       against.
 *   <li>{@code REJECT} — economically identical to cancelling before
 *       confirmation; it shares CANCEL's danger without sharing its
 *       reason-registry machinery, and folding it in would mean inventing a
 *       second bulk-cancel path rather than reusing one.
 *   <li>{@code AMEND} — every one of the ten commands either changes money
 *       (refused outright, three of the ten are even built) or, for the
 *       three that are, holds the one-open-amendment-per-order lock that
 *       bulk contends with by design. An amendment also needs the operator's
 *       attestation that the customer agreed to what changed; there is no
 *       version of "the customer agreed" that a hundred orders can share.
 * </ul>
 */
public enum BulkActionType {
    ADVANCE,
    CANCEL,

    /**
     * N independent assignments of selected delivery orders to one named courier (ADR
     * 0172), the first supported action ADR 0039 named and could not build until ADR
     * 0014's single assignment existed. Each item is decided and applied in its own
     * transaction through {@code fulfillment.api.CourierAssignmentPort}; the request
     * additionally needs {@code delivery.manual_assign}, and a person's assignment
     * overrides a dispatch rule, visibly, and never creates a run.
     */
    ASSIGN_COURIER
}
