package uz.horecaos.platform.integration.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An outbox or inbox record ran out of retry budget and became a dead
 * letter (ADR 0006): the operator decision path {@code
 * docs/runbooks/dead-letter-decision.md} exists for.
 *
 * <p>Fired from all four dead-lettering call sites — {@code
 * OutboxRelay.publish}'s retry-exhaustion branch, and {@code
 * InboxExecutor}'s payload-collision, unsupported-contract and
 * retry-exhaustion branches — with the same shape either direction, because
 * an operator reading the ADR 0058 alert does not need to know which side
 * of the platform boundary failed to decide whether to look.
 *
 * <p>{@code aggregateType}/{@code aggregateId} name what the dead letter was
 * about, never what it carried: this record does not resolve a
 * brand/location — a dead letter can be about any aggregate type on any
 * topic, so there is no uniform lookup for one. {@code
 * uz.horecaos.platform.notifications.application.IntegrationOperationsAlertTrigger}
 * resolves brand/location only when {@code aggregateType} is one it can look
 * up (today: {@code "Order"}) and otherwise logs without fanning out — a
 * named, honest gap rather than a silent one.
 *
 * <p>An in-process signal only. Not itself an ADR 0032 event and not
 * appended to the outbox — a dead letter about a publish failure republishing
 * itself through the same failing mechanism would be exactly the wrong fix.
 */
public record DeadLetterRecorded(
        UUID eventId,
        UUID tenantId,
        String source,
        String aggregateType,
        UUID aggregateId,
        String reasonCode,
        Instant occurredAt) {

    /** {@link #source}: this dead letter came from the outbox relay's own publish path. */
    public static final String SOURCE_OUTBOX = "OUTBOX";

    /** {@link #source}: this dead letter came from an inbound consumer's inbox. */
    public static final String SOURCE_INBOX = "INBOX";

    public DeadLetterRecorded {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(source, "A source is required");
        Objects.requireNonNull(aggregateType, "An aggregate type is required");
        Objects.requireNonNull(aggregateId, "An aggregate ID is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
