package uz.qoida.platform.integration.outbox;

import java.time.Instant;
import java.util.UUID;

import uz.qoida.platform.integration.api.provider.ProviderOutcome;

/**
 * Writes the controlled route's canonical result to the ADR 0004 outbox.
 *
 * <p>In this package because {@code NewOutboxEvent} is package-private: appending
 * to the outbox is the integration module's own job, and every production caller
 * reaches it through a listener that lives here too. A test that reached around
 * that with raw SQL would prove the SQL works and nothing about the path.
 *
 * <p>The result is appended inside the handler's transaction, so it commits with
 * the inbox {@code PROCESSED} transition or not at all. That is ADR 0004's rule
 * that nothing publishes to Kafka inside a business transaction: the row is
 * written here and the relay publishes it afterwards.
 */
public final class ControlledResultOutbox {

    /** Test-only, and deliberately not in {@code EventCatalog} for that reason. */
    public static final String EVENT_TYPE = "ControlledCommandCompleted";
    public static final String TOPIC = "integration.controlled.events";

    private final JdbcOutboxStore outbox;

    public ControlledResultOutbox(JdbcOutboxStore outbox) {
        this.outbox = outbox;
    }

    public void appendResult(UUID commandId, UUID tenantId, ProviderOutcome outcome, Instant occurredAt) {
        outbox.append(new NewOutboxEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                1,
                tenantId,
                "ControlledCommand",
                commandId,
                TOPIC,
                commandId.toString(),
                commandId.toString(),
                null,
                occurredAt,
                // No provider body and no external reference detail beyond the
                // opaque reference: ADR 0029 keeps provider payloads out of
                // events, and a canonical result is exactly where that leaks.
                """
                {"commandId":"%s","status":"%s","externalReference":%s}"""
                        .formatted(commandId, outcome.status().name(),
                                outcome.externalReference() == null
                                        ? "null"
                                        : "\"" + outcome.externalReference() + "\""),
                "{}"));
    }
}
