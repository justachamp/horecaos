package uz.qoida.platform.ordering.api;

import java.time.Instant;
import java.util.UUID;

import uz.qoida.platform.tenancy.api.TenantId;

/**
 * A versioned business fact emitted by the ordering module (ADR 0019, ADR 0032).
 *
 * <p>Sealed, so the ADR 0032 completeness test can enumerate every event
 * ordering is capable of publishing and assert that each has a catalogue entry,
 * a JSON schema, and a documentation row. An event added without those three
 * fails the build rather than reaching a topic undocumented.
 *
 * <p>Payloads carry identifiers, state, versions, policy references, and the
 * minimum totals. Never an address, a phone number, a customer note, or a line
 * payload — a consumer needing those calls an authorized API with the order id.
 */
public sealed interface OrderingEvent
        permits OrderReceived, OrderAwaitingApproval, OrderConfirmed,
                OrderRejected, OrderExpired, OrderCancelled {

    UUID eventId();

    String eventType();

    int eventVersion();

    TenantId tenantId();

    UUID orderId();

    Instant occurredAt();

    Object payload();

    default String aggregateType() {
        return "Order";
    }

    default UUID aggregateId() {
        return orderId();
    }
}
