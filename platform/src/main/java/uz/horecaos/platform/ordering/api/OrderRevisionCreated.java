package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A new immutable order revision was appended (ADR 0039).
 *
 * <p>Published only for a revision an amendment produces ({@code source =
 * "AMENDMENT"}) — never for revision 1, the ADR 0019 checkout snapshot every
 * order already opens with. {@link OrderReceived} is that order's own opening
 * fact, and a second event announcing the same revision would carry no
 * information a consumer does not already have; the same restraint that keeps
 * {@code PREPARING}/{@code READY}/{@code FULFILLING} off this topic until
 * something reads them applies here.
 *
 * <p>{@code totalMinor} is revision N's own total, not a delta — a consumer
 * pinned to a revision can reconcile against it without replaying every prior
 * revision's change. It equals the previous revision's total for every command
 * this release can apply, because none of the three built commands changes
 * money.
 */
public record OrderRevisionCreated(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        int revision,
        @Nullable UUID amendmentId,
        String currency,
        long totalMinor,
        long deltaTotalMinor,
        int orderVersion)
        implements OrderingEvent {

    public OrderRevisionCreated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "OrderRevisionCreated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                orderId,
                brandId,
                locationId,
                revision,
                amendmentId,
                "AMENDMENT",
                currency,
                totalMinor,
                deltaTotalMinor,
                orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            int revision,
            @Nullable UUID amendmentId,
            String source,
            String currency,
            long totalMinor,
            long deltaTotalMinor,
            int orderVersion) {}
}
