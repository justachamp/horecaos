package uz.horecaos.platform.integration.camel.delivery;

import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.DeliveryRequest;
import uz.horecaos.platform.integration.api.provider.BindingRef;

/**
 * One provider-neutral delivery command carried through the route (ADR 0007).
 *
 * <p>Fulfilment builds this; nothing downstream of it names a partner. The
 * {@code commandId} is the idempotency key sent to the provider, which is why a
 * retry must reuse this exact command rather than build an equivalent one — a
 * fresh id would defeat the provider-side deduplication the retry depends on.
 *
 * @param externalReference the provider's own id, required by every operation
 *                          except quote and create
 */
public record DeliveryOperation(
        UUID commandId,
        UUID tenantId,
        BindingRef binding,
        DeliveryCapability capability,
        DeliveryRequest request,
        String externalReference,
        String reason,
        String correlationId) {

    public DeliveryOperation {
        Objects.requireNonNull(commandId, "A command id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(binding, "A provider binding is required");
        Objects.requireNonNull(capability, "A capability is required");
        if (!tenantId.equals(binding.tenantId())) {
            // A binding from another tenant would call that tenant's provider
            // account with this tenant's order. Checked here rather than trusted,
            // because the route has no other place to catch it.
            throw new IllegalArgumentException("The binding belongs to a different tenant");
        }
        if (requiresRequest(capability) && request == null) {
            throw new IllegalArgumentException(capability + " requires a delivery request");
        }
        if (requiresReference(capability) && (externalReference == null || externalReference.isBlank())) {
            throw new IllegalArgumentException(capability + " requires an external reference");
        }
    }

    /** The provider idempotency key. Stable for the life of this command. */
    public String idempotencyKey() {
        return commandId.toString();
    }

    private static boolean requiresRequest(DeliveryCapability capability) {
        return capability == DeliveryCapability.QUOTE_DELIVERY
                || capability == DeliveryCapability.RESERVE_SHIPMENT
                || capability == DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT
                || capability == DeliveryCapability.SCHEDULE_SHIPMENT;
    }

    private static boolean requiresReference(DeliveryCapability capability) {
        return capability == DeliveryCapability.CONFIRM_SHIPMENT
                || capability == DeliveryCapability.CANCEL_SHIPMENT
                || capability == DeliveryCapability.QUERY_CANCELLATION_COST
                || capability == DeliveryCapability.QUERY_SHIPMENT
                || capability == DeliveryCapability.TRACK_SHIPMENT;
    }
}
