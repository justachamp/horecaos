package uz.horecaos.platform.pos.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A POS export reached {@code AWAITING_OPERATOR} (ADR 0011): the recovery
 * read finished and could not decide on its own, so a person must.
 *
 * <p>Published from the {@code OPERATOR} branch of {@code
 * PosOrderExportService.discoverOutcome} alone — the branch where {@code
 * UncertainExportResolver} has already run and named the reason. The
 * sibling transition into the same state, reached from that method's
 * {@code ExportNotPossible} catch block when a binding was retired
 * mid-flight, is not wired: the order cannot be resolved at that point
 * (that is exactly why the export could not be prepared), and there is no
 * brand/location to route an alert on without a lookup that could itself
 * fail the same way. A named gap, not a silent one.
 *
 * <p>An in-process signal only, in the {@code payments.api.PaymentAttemptFailed}
 * genre: no ADR 0032 catalogue entry, never appended to the outbox.
 */
public record PosExportAwaitingOperator(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID exportId,
        UUID orderId,
        String reasonCode,
        Instant occurredAt) {

    public PosExportAwaitingOperator {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(exportId, "Export ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
