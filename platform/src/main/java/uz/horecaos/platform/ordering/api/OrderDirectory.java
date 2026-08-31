package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The small read another module needs about an order (ADR 0019, consumed by
 * ADR 0020).
 *
 * <p>ADR 0032 keeps order events down to identifiers, state, and totals, which is
 * right — the basket, the notes and eventually the address all sit one field away
 * on the order, and none of them belongs on a topic. The consequence is that a
 * consumer needing more than the event carries has to ask, and this is where it
 * asks. Notifications needs the customer whose order it is and the number to put
 * in the message, and neither is on {@link OrderConfirmed}.
 *
 * <p>Deliberately not a window onto the whole order. Lines, modifiers, notes and
 * the encrypted fields are reachable only through {@code OrderQueryService},
 * behind the capability and the recorded purpose that revealing them requires.
 */
public interface OrderDirectory {

    /**
     * @return empty when no order of that id belongs to this tenant, which is the
     *         same answer as "it does not exist" and deliberately so
     */
    Optional<OrderSummary> summary(UUID tenantId, UUID orderId);

    /**
     * Orders whose approval deadline falls inside {@code (now, now + within]} —
     * the board's own severity threshold for "about to breach"
     * (docs/operations-spec/orders.md §2.6: {@code AWAITING_APPROVAL with < 2 min
     * to deadline}), read here rather than reimplemented, because Внимание's
     * queue and ADR 0058's Telegram warning have to agree on when "about to"
     * starts.
     *
     * <p>Cross-tenant by design, the same way {@code OrderProcessWorker}'s own
     * timer sweep is: this is infrastructure scanning a partial index
     * ({@code ix_orders_awaiting_approval}), not a tenant-scoped business read,
     * and the caller (a scheduled sweeper) is expected to act tenant-row-by-row.
     * A pure read, unlike the timer sweep — nothing here claims or mutates the
     * order, so calling it twice before the same deadline is safe and expected:
     * the caller's own idempotency key on the resulting notification, not this
     * method, is what prevents a duplicate warning.
     *
     * <p>Defaulted to empty rather than made abstract, so the several hand-written
     * {@code OrderDirectory} test doubles that predate ADR 0058 do not all need a
     * mechanical implementation of a sweep they never exercise.
     */
    default List<ApprovalDeadlineWarning> ordersNearingApprovalDeadline(
            Instant now, java.time.Duration within, int limit) {
        return List.of();
    }

    record ApprovalDeadlineWarning(
            UUID tenantId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String publicOrderNumber,
            Instant approvalDeadlineAt) {}

    /**
     * What a consumer may hold about an order.
     *
     * @param customerAccountId null on a guest order, which is not an error: a
     *                          guest has no account to resolve a contact from, and
     *                          the caller has to handle that rather than assume
     * @param guestReferenceHash a keyed hash, never the device or session
     *                           reference itself
     */
    record OrderSummary(
            UUID orderId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String publicOrderNumber,
            UUID customerAccountId,
            String guestReferenceHash,
            String status,
            String currency,
            long totalMinor,
            int version) {

        public OrderSummary {
            Objects.requireNonNull(orderId, "An order id is required");
            Objects.requireNonNull(tenantId, "A tenant id is required");
        }

        public boolean hasAccount() {
            return customerAccountId != null;
        }
    }
}
