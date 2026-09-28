package uz.horecaos.platform.dinein.application.port;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The few order facts a session needs (ADR 0047).
 *
 * <p>Reads, and only reads. ADR 0047's whole benefit comes from dine-in reusing
 * the order aggregate untouched: pricing, inventory, fiscal treatment, audit, and
 * reporting work on day one because a DINE_IN order is an order. A session that
 * wrote one would be the parallel aggregate the ADR spent five alternatives
 * refusing.
 *
 * <p>The bill is deliberately a query rather than a column. ADR 0047 says the
 * session's total is the sum of its member orders and is never recomputed from
 * rules, and a summed column would be a second answer that drifts the first time
 * an order is amended.
 */
public interface SessionOrderSource {

    /**
     * The few facts a session needs about one of its member orders.
     *
     * @param status the order's own status, so a session can refuse to attach a
     *               round that was cancelled
     * @param customerAccountId the account that placed the order, when it was
     *                          placed by a signed-in customer rather than
     *                          through a guest-reference channel. A guest
     *                          endpoint attaching a round on the placing
     *                          customer's own say-so (rather than an operator's
     *                          capability) needs this to confirm the round is
     *                          actually theirs -- see {@code
     *                          TableSessionService#addRound}'s own doc
     */
    record OrderForSession(
            UUID orderId,
            UUID tenantId,
            UUID locationId,
            String fulfillmentMode,
            String status,
            String currency,
            long totalMinor,
            @Nullable UUID customerAccountId) {}

    /** The running bill: currency and the sum over the session's rounds. */
    record SessionBill(String currency, long totalMinor, int roundCount, int openRoundCount) {}

    Optional<OrderForSession> find(UUID tenantId, UUID orderId);

    SessionBill bill(UUID tenantId, UUID sessionId);
}
