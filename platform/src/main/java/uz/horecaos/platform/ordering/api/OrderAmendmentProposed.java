package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * An operator proposed a change to a live order (ADR 0039).
 *
 * <p>Fires when {@code OrderAmendmentService.propose} records the amendment and
 * its commands, before any consequence is applied — {@code amendmentStatus} is
 * {@code PRICED} at this point, never {@code APPLIED}, because propose only
 * prices and stages what {@link OrderAmendmentApplied} later commits.
 *
 * <p>{@code commandTypes} names the closed {@link
 * uz.horecaos.platform.ordering.domain.AmendmentCommandType} set the operator
 * issued — an enum name, never the payload a command carries. {@code
 * SET_KITCHEN_NOTE}'s free text and a phone number typed into {@code
 * CHANGE_CONTACT} are exactly the kind of value ADR 0029 keeps off every topic;
 * a consumer that needs the command's own content calls the amendment API with
 * the amendment id.
 */
public record OrderAmendmentProposed(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        UUID amendmentId,
        int baseRevision,
        List<String> commandTypes,
        String amendmentStatus,
        int orderVersion)
        implements OrderingEvent {

    public OrderAmendmentProposed {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(amendmentId, "Amendment ID is required");
        commandTypes = List.copyOf(commandTypes);
    }

    @Override
    public String eventType() {
        return "OrderAmendmentProposed";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                orderId, brandId, locationId, amendmentId, baseRevision, commandTypes, amendmentStatus, orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            UUID amendmentId,
            int baseRevision,
            List<String> commandTypes,
            String amendmentStatus,
            int orderVersion) {}
}
