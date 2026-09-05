package uz.horecaos.platform.integration.events;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.integration.events.EventContract.Classification;
import uz.horecaos.platform.integration.events.EventContract.Retention;

/**
 * The code-owned registry of external event contracts required by ADR 0032.
 *
 * <p>This registry is the enforcement point for the rule that no event may be
 * published without a documented contract. It is deliberately a code constant
 * rather than a database table: an unknown event type must fail at build or
 * startup, not at read time.
 *
 * <p>Adding an event means adding an entry here, a JSON Schema file at the
 * declared {@code schemaPath}, and a row in {@code docs/domains/events.md}.
 * Tests assert all three exist together.
 */
public final class EventCatalog {

    public static final String TENANCY_EVENTS_TOPIC = KafkaTopicCatalog.TENANCY_EVENTS;

    /**
     * ADR 0019 order facts. A topic of its own rather than a share of
     * {@code tenancy.events}, because ordering volume is three orders of
     * magnitude higher and its retention and partitioning have nothing in common
     * with a handful of control-plane creations.
     */
    public static final String ORDERING_EVENTS_TOPIC = KafkaTopicCatalog.ORDERING_EVENTS;

    /**
     * ADR 0045's ephemeral fan-out topic, and the only member of ADR 0032's
     * fourth topic class.
     *
     * <p>Named {@code realtime.signals} rather than {@code realtime.events}
     * because nothing on it is an event in this catalogue's sense: there is no
     * fact, no aggregate, and nothing anybody reconciles against. A record says
     * that something in a scope changed and every replica holding a connection
     * for that scope tells its clients to re-read through the ordinary
     * authorized API.
     *
     * <p>It is registered here anyway, because ADR 0032's rule is that nothing is
     * published to a topic without a contract — and a topic that carried a
     * coordinate or an order's contents would be exactly the kind of drift the
     * registry exists to catch. The schema below is what makes "signals carry
     * identifiers only" a checked property rather than an intention.
     */
    public static final String REALTIME_SIGNALS_TOPIC = KafkaTopicCatalog.REALTIME_SIGNALS;

    /**
     * ADR 0010 media facts.
     *
     * <p>Its own topic rather than a share of {@code tenancy.events}, for the
     * reason ordering has one: a menu import writes thousands of assets in
     * minutes and a handful of control-plane creations should not sit behind
     * them. Nothing on it carries an object key or a presigned URL — a topic is
     * read by consumers with no authorization to the bytes, and a key on it
     * would be a read capability handed to all of them.
     */
    public static final String MEDIA_EVENTS_TOPIC = KafkaTopicCatalog.MEDIA_EVENTS;

    /**
     * ADR 0007's asynchronous command entry point.
     *
     * <p>A separate topic from {@code fulfillment.events} because the two have
     * opposite retention: a command is work the platform still owes itself and
     * PostgreSQL is its durable store (ADR 0004), so days of Kafka retention
     * would only make a replay re-issue work that has already been done. A fact
     * on {@code fulfillment.events} is retained for reconciliation.
     */
    public static final String FULFILLMENT_COMMANDS_TOPIC = KafkaTopicCatalog.FULFILLMENT_COMMANDS;

    /** ADR 0014 fulfilment facts, including the settled result of a route command. */
    public static final String FULFILLMENT_EVENTS_TOPIC = KafkaTopicCatalog.FULFILLMENT_EVENTS;

    /** ADR 0064 call-event facts, from either the hosted-PBX or the Asterisk-class adapter. */
    public static final String VOICE_EVENTS_TOPIC = KafkaTopicCatalog.VOICE_EVENTS;

    private static final Map<String, EventContract> CONTRACTS = index(List.of(
            new EventContract(
                    "TenantCreated",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "tenantId",
                    "events/tenancy.events/TenantCreated.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "A tenant was created in the control plane."),
            new EventContract(
                    "BrandCreated",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "brandId",
                    "events/tenancy.events/BrandCreated.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "A brand was created under a tenant."),
            new EventContract(
                    "LocationCreated",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "locationId",
                    "events/tenancy.events/LocationCreated.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "A location was created under a brand."),
            // ADR 0008's onboarding facts. All five key on the tenant rather than
            // the run, because onboarding order is tenant-scoped: a consumer must
            // not see an activation before the start that produced it.
            new EventContract(
                    "TenantOnboardingStarted",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "tenantId",
                    "events/tenancy.events/TenantOnboardingStarted.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "A resumable tenant onboarding run was created."),
            new EventContract(
                    "TenantOnboardingStepCompleted",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "tenantId",
                    "events/tenancy.events/TenantOnboardingStepCompleted.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "One onboarding step completed. Names the step, never what it produced."),
            new EventContract(
                    "TenantOnboardingFailed",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "tenantId",
                    "events/tenancy.events/TenantOnboardingFailed.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "A run stopped on a required step. Error code only, never the detail."),
            new EventContract(
                    "TenantReady",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "tenantId",
                    "events/tenancy.events/TenantReady.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "Every required readiness step passed. Ready is not live."),
            new EventContract(
                    "TenantActivated",
                    1,
                    "tenancy",
                    TENANCY_EVENTS_TOPIC,
                    "tenantId",
                    "events/tenancy.events/TenantActivated.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "The tenant is live after a platform-approved activation."),
            new EventContract(
                    "OrderReceived",
                    1,
                    "ordering",
                    ORDERING_EVENTS_TOPIC,
                    "orderId",
                    "events/ordering.events/OrderReceived.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "An order was created and is durable. Nothing external has happened yet."),
            new EventContract(
                    "OrderAwaitingApproval",
                    1,
                    "ordering",
                    ORDERING_EVENTS_TOPIC,
                    "orderId",
                    "events/ordering.events/OrderAwaitingApproval.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "An order is waiting for a restaurant decision, with its deadline."),
            new EventContract(
                    "OrderConfirmed",
                    1,
                    "ordering",
                    ORDERING_EVENTS_TOPIC,
                    "orderId",
                    "events/ordering.events/OrderConfirmed.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "The commercial commitment. POS export never gates this."),
            new EventContract(
                    "OrderRejected",
                    1,
                    "ordering",
                    ORDERING_EVENTS_TOPIC,
                    "orderId",
                    "events/ordering.events/OrderRejected.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "The restaurant declined the order."),
            new EventContract(
                    "OrderExpired",
                    1,
                    "ordering",
                    ORDERING_EVENTS_TOPIC,
                    "orderId",
                    "events/ordering.events/OrderExpired.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "Nobody decided before the approval deadline."),
            new EventContract(
                    "OrderCancelled",
                    1,
                    "ordering",
                    ORDERING_EVENTS_TOPIC,
                    "orderId",
                    "events/ordering.events/OrderCancelled.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "The order was cancelled before confirmation."),
            // ADR 0007's reconciliation command and its settled answer. Both key
            // on the delivery operation whose outcome is unknown, not on the
            // reconciliation: two reconciliations for one operation must not
            // overtake each other, and the inbox parks the second behind the
            // first for exactly that reason.
            new EventContract(
                    "ShipmentReconciliationRequested",
                    1,
                    "integration",
                    FULFILLMENT_COMMANDS_TOPIC,
                    "operationCommandId",
                    "events/fulfillment.commands/ShipmentReconciliationRequested.v1.schema.json",
                    Retention.COMMAND,
                    Classification.INTERNAL,
                    "A courier call's outcome is unknown and could not be settled in the route. "
                            + "Identifiers only; never an address, a name, or a phone number."),
            new EventContract(
                    "ShipmentOutcomeReconciled",
                    1,
                    "integration",
                    FULFILLMENT_EVENTS_TOPIC,
                    "operationCommandId",
                    "events/fulfillment.events/ShipmentOutcomeReconciled.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "What the partner says actually happened to a shipment whose outcome was "
                            + "uncertain, or that nobody could establish it."),
            new EventContract(
                    "MediaAssetAvailable",
                    1,
                    "media",
                    MEDIA_EVENTS_TOPIC,
                    "assetId",
                    "events/media.events/MediaAssetAvailable.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "An upload was verified against the object store and may be shown. "
                            + "Identifiers and verified technical facts only — never the "
                            + "object key, a signed URL, or the uploaded filename."),
            new EventContract(
                    "RealtimeSignal",
                    1,
                    "telemetry",
                    REALTIME_SIGNALS_TOPIC,
                    "scope",
                    "events/realtime.signals/RealtimeSignal.v1.schema.json",
                    Retention.SIGNAL,
                    Classification.INTERNAL,
                    "Something in a scope changed; the reader re-reads it through the authorized "
                            + "API. Identifiers only, never state."),
            new EventContract(
                    "VoiceCallEventRecorded",
                    1,
                    "voice",
                    VOICE_EVENTS_TOPIC,
                    "callCorrelationId",
                    "events/voice.events/VoiceCallEventRecorded.v1.schema.json",
                    Retention.BUSINESS_FACT,
                    Classification.INTERNAL,
                    "One normalized call event (offered/answered/ended/missed/transferred). "
                            + "Never a caller number, encrypted or not — only a resolved customer "
                            + "account id, when resolution succeeded.")));

    private EventCatalog() {}

    private static Map<String, EventContract> index(List<EventContract> contracts) {
        Map<String, EventContract> byKey = new LinkedHashMap<>();
        for (EventContract contract : contracts) {
            EventContract previous = byKey.put(contract.key(), contract);
            if (previous != null) {
                throw new IllegalStateException("Duplicate event contract: " + contract.key());
            }
        }
        return Map.copyOf(byKey);
    }

    public static Collection<EventContract> all() {
        return CONTRACTS.values();
    }

    public static Optional<EventContract> find(String eventType, int eventVersion) {
        return Optional.ofNullable(CONTRACTS.get(eventType + ".v" + eventVersion));
    }

    /**
     * Resolves the registered contract or fails.
     *
     * <p>ADR 0032: an event without a catalogue entry must not reach a topic.
     * Failing here converts a documentation omission into an immediate,
     * attributable error rather than an undocumented contract in production.
     */
    public static EventContract require(String eventType, int eventVersion) {
        return find(eventType, eventVersion)
                .orElseThrow(() -> new UnregisteredEventException(
                        "No ADR 0032 catalogue entry for %s v%d. Add the contract to EventCatalog, "
                                        .formatted(eventType, eventVersion)
                                + "add its JSON Schema, and document it in docs/domains/events.md."));
    }

    /** Thrown when a producer attempts to emit an event that has no registered contract. */
    public static final class UnregisteredEventException extends IllegalStateException {
        UnregisteredEventException(String message) {
            super(message);
        }
    }
}
