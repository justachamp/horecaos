package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Every marked line of the order now holds all the codes it needs (ADR 0161, ADR 0032).
 *
 * <p>A nudge, not a ledger: the fiscal module re-reads {@code OrderMarksPort.markCounts} (counts only,
 * nothing decrypted) to reopen a document that was blocked {@code MARKS_INCOMPLETE}, and the board
 * refreshes through the realtime signal it already has. A removal after completion publishes nothing, and
 * consumers treat the current count as the truth.
 *
 * <p>Identifiers and counts only. A marking code is never in an event, and neither is the GTIN or the
 * customer; ADR 0029 keeps the code in the protected row.
 */
public record OrderMarksCompleted(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        int markedLineCount,
        int markedUnitCount,
        Instant completedAt)
        implements OrderingEvent {

    public OrderMarksCompleted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(completedAt, "A completion time is required");
    }

    @Override
    public String eventType() {
        return "OrderMarksCompleted";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, markedLineCount, markedUnitCount, completedAt.toString());
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            int markedLineCount,
            int markedUnitCount,
            // ISO-8601 text; see OrderAwaitingApproval for why the wire shape of a timestamp is pinned.
            String completedAt) {}
}
