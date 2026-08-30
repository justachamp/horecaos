package uz.horecaos.platform.dinein.application.port;

import java.util.Optional;
import java.util.UUID;

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
     * @param status the order's own status, so a session can refuse to attach a
     *               round that was cancelled
     */
    record OrderForSession(UUID orderId, UUID tenantId, UUID locationId, String fulfillmentMode,
            String status, String currency, long totalMinor) {
    }

    /** The running bill: currency and the sum over the session's rounds. */
    record SessionBill(String currency, long totalMinor, int roundCount, int openRoundCount) {
    }

    Optional<OrderForSession> find(UUID tenantId, UUID orderId);

    SessionBill bill(UUID tenantId, UUID sessionId);
}
