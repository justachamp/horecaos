package uz.horecaos.platform.integration.inbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.ExternalWorkInboxHandler;
import uz.horecaos.platform.integration.api.InboxHandler;
import uz.horecaos.platform.integration.inbox.EnvelopeValidator.InvalidEnvelopeException;
import uz.horecaos.platform.integration.retry.RetryBackoff;

/**
 * Turns one Kafka record into at most one durable business effect (ADR 0005).
 *
 * <p>The ordering that matters: the handler's effect and the {@code PROCESSED}
 * transition commit in one transaction, and only then may the offset be
 * acknowledged. Acknowledging first would lose the event on a crash;
 * committing separately would allow the effect without the evidence.
 */
@Component
public class InboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(InboxExecutor.class);

    // Must stay in step with the @Value defaults on the primary constructor. Two
    // places, because a Duration cannot be a compile-time annotation constant and
    // the short constructor below has callers that predate these parameters.
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(2);
    private static final Duration DEFAULT_MAXIMUM_BACKOFF = Duration.ofMinutes(5);
    private static final Duration DEFAULT_BLOCKED_RECHECK = Duration.ofSeconds(30);

    private final JdbcInboxStore store;
    private final InboxHandlerRegistry registry;
    private final EnvelopeValidator validator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final int maximumAttempts;
    private final RetryBackoff backoff;
    private final Duration blockedRecheckDelay;

    /** The retry policy defaults, for a caller with no reason to override them. */
    public InboxExecutor(
            JdbcInboxStore store,
            InboxHandlerRegistry registry,
            EnvelopeValidator validator,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            MeterRegistry meters,
            int maximumAttempts) {
        this(
                store,
                registry,
                validator,
                objectMapper,
                transactions,
                meters,
                maximumAttempts,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAXIMUM_BACKOFF,
                DEFAULT_BLOCKED_RECHECK);
    }

    @Autowired
    public InboxExecutor(
            JdbcInboxStore store,
            InboxHandlerRegistry registry,
            EnvelopeValidator validator,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            MeterRegistry meters,
            @Value("${horecaos.messaging.inbox.max-attempts:10}") int maximumAttempts,
            @Value("${horecaos.messaging.inbox.initial-backoff:2s}") Duration initialBackoff,
            @Value("${horecaos.messaging.inbox.max-backoff:5m}") Duration maximumBackoff,
            @Value("${horecaos.messaging.inbox.blocked-recheck:30s}") Duration blockedRecheckDelay) {
        this.store = store;
        this.registry = registry;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.meters = meters;
        this.maximumAttempts = maximumAttempts;
        // Jittered (ADR 0006): the delay is otherwise a function of the attempt
        // count alone, so every consumer that failed against the same outage
        // wakes at the same instant and re-forms the burst.
        this.backoff = RetryBackoff.of(initialBackoff, maximumBackoff);
        this.blockedRecheckDelay = blockedRecheckDelay;
    }

    /**
     * Offers one record to one named consumer.
     *
     * @return whether the Kafka offset may be acknowledged
     */
    public InboxResult execute(
            String consumerName,
            String recordKey,
            String body,
            Map<String, String> headers,
            String topic,
            int partition,
            long offset) {

        ExternalEventEnvelope<JsonNode> envelope;
        try {
            envelope = validator.validate(recordKey, body, headers, topic, partition, offset);
        } catch (InvalidEnvelopeException invalid) {
            // Nothing about retrying an unparseable record improves it, and the
            // record cannot even be identified well enough to store as an inbox
            // row, so it is reported for operations and acknowledged.
            log.error(
                    "Invalid envelope on {}-{}@{} for {}: {}",
                    topic,
                    partition,
                    offset,
                    consumerName,
                    invalid.getMessage());
            count(consumerName, "unknown", "invalid_envelope");
            return InboxResult.INVALID_ENVELOPE;
        }

        String payloadJson = objectMapper.writeValueAsString(envelope.payload());
        Optional<JdbcInboxStore.InboxRow> existing = store.claim(consumerName, envelope, payloadJson);

        if (existing.isPresent()) {
            JdbcInboxStore.InboxRow row = existing.get();

            if (!row.payloadSha256().equals(envelope.payloadSha256())) {
                // A producer has reused an event id for different content. This
                // is a contract violation, not a redelivery, and treating it as
                // a duplicate would silently discard the differing data.
                //
                // The stored row is only quarantined when it has not already
                // been processed. A processed row is true evidence that the
                // effect happened, and overwriting it as DEAD_LETTER would
                // destroy that to describe a different record's problem. The
                // database lifecycle constraint enforces this, and it is right
                // to: the collision belongs to the arriving record.
                if (!row.isProcessed()) {
                    store.deadLetter(
                            row.id(), "PAYLOAD_INVALID", "The same event id arrived with a different payload hash");
                }
                log.error(
                        "Contract collision on {}: event {} arrived with a different payload than the stored one "
                                + "(stored status {}). A producer has violated event immutability.",
                        consumerName,
                        envelope.eventId(),
                        row.status());
                count(consumerName, envelope.eventType(), "contract_collision");
                return InboxResult.CONTRACT_COLLISION;
            }
            if (row.isProcessed()) {
                count(consumerName, envelope.eventType(), "duplicate");
                return InboxResult.DUPLICATE_IGNORED;
            }
            if (row.isDeadLettered()) {
                count(consumerName, envelope.eventType(), "dead_letter");
                return InboxResult.DEAD_LETTERED;
            }
        }

        JdbcInboxStore.InboxRow row =
                store.find(consumerName, envelope.eventId()).orElseThrow();

        return drive(consumerName, envelope, row);
    }

    /**
     * Re-runs a stored item that is due again, rebuilding its envelope from the
     * inbox row (ADR 0006).
     *
     * <p>Rebuilt from PostgreSQL rather than replayed from Kafka because the
     * inbox row has been the authoritative copy of the work since ADR 0005, and
     * by the time a retry is due the broker record may be past retention.
     */
    InboxResult redrive(JdbcInboxStore.StoredInboxItem item) {
        ExternalEventEnvelope<JsonNode> envelope = new ExternalEventEnvelope<>(
                item.eventId(),
                item.eventType(),
                item.eventVersion(),
                item.tenantId(),
                item.aggregateType(),
                item.aggregateId(),
                item.correlationId(),
                item.causationId(),
                item.occurredAt(),
                objectMapper.readTree(item.payloadJson()),
                item.payloadSha256(),
                new ExternalEventEnvelope.TransportContext(
                        item.topic(),
                        item.partition(),
                        item.recordOffset(),
                        item.aggregateId().toString()));

        JdbcInboxStore.InboxRow row =
                new JdbcInboxStore.InboxRow(item.id(), item.status(), item.payloadSha256(), item.attemptCount(), null);

        return drive(item.consumerName(), envelope, row);
    }

    /**
     * The part that is identical whether the work arrived on a Kafka record or
     * came back round on the retry worker: resolve a handler, refuse to overtake
     * an earlier sibling, take the lease, run.
     */
    private InboxResult drive(
            String consumerName, ExternalEventEnvelope<JsonNode> envelope, JdbcInboxStore.InboxRow row) {

        Optional<InboxHandler<?>> handler = registry.find(consumerName, envelope.eventType(), envelope.eventVersion());

        if (handler.isEmpty()) {
            // An unsupported type or version is a permanent contract failure.
            // Retrying it forever would hide a deployment mismatch behind a
            // growing backlog.
            store.deadLetter(
                    row.id(),
                    "CONTRACT_UNSUPPORTED",
                    "No handler for %s v%d".formatted(envelope.eventType(), envelope.eventVersion()));
            count(consumerName, envelope.eventType(), "unsupported");
            return InboxResult.UNSUPPORTED;
        }

        if (store.hasEarlierUnresolvedForAggregate(
                consumerName,
                envelope.transport().topic(),
                envelope.aggregateId(),
                envelope.occurredAt(),
                envelope.eventId())) {
            // Kafka orders a partition, not an aggregate's history across
            // failures: once an earlier event dead-letters, its offset is
            // acknowledged and the next event for the same aggregate would
            // otherwise apply on top of a state transition that never happened.
            store.blockOnEarlier(
                    row.id(),
                    blockedRecheckDelay,
                    "An earlier unresolved event for aggregate %s must be settled first"
                            .formatted(envelope.aggregateId()));
            count(consumerName, envelope.eventType(), "blocked");
            return InboxResult.BLOCKED_BY_EARLIER;
        }

        UUID processingToken = UUID.randomUUID();
        if (!store.beginProcessing(row.id(), processingToken)) {
            // Another worker holds a live lease; do not run a second handler.
            count(consumerName, envelope.eventType(), "already_processing");
            return InboxResult.RETRY_SCHEDULED;
        }

        return invoke(consumerName, envelope, handler.get(), row, processingToken);
    }

    private InboxResult invoke(
            String consumerName,
            ExternalEventEnvelope<JsonNode> envelope,
            InboxHandler<?> handler,
            JdbcInboxStore.InboxRow row,
            UUID processingToken) {

        try {
            run(handler, envelope, row, processingToken);
            count(consumerName, envelope.eventType(), "processed");
            return InboxResult.PROCESSED;
        } catch (RuntimeException failure) {
            int attempts = row.attemptCount() + 1;
            String safeMessage = failure.getClass().getSimpleName() + ": " + failure.getMessage();

            if (attempts >= maximumAttempts) {
                store.deadLetter(row.id(), "TRANSIENT_INFRASTRUCTURE", safeMessage);
                log.error("Inbox item {} for {} exhausted {} attempts", row.id(), consumerName, attempts, failure);
                count(consumerName, envelope.eventType(), "dead_letter");
                return InboxResult.DEAD_LETTERED;
            }

            store.scheduleRetry(row.id(), backoff.delayAfter(attempts), "TRANSIENT_INFRASTRUCTURE", safeMessage);
            log.warn("Inbox item {} for {} failed on attempt {}", row.id(), consumerName, attempts, failure);
            count(consumerName, envelope.eventType(), "retry");
            return InboxResult.RETRY_SCHEDULED;
        }
    }

    /**
     * Runs one handler and commits the {@code PROCESSED} transition with it.
     *
     * <p>Where the handler declares external work (ADR 0007), the call to the
     * outside world happens <em>before</em> the transaction opens and only the
     * recording of its result is inside. That is the difference between holding
     * one of ten pooled connections for the length of a courier's timeout and
     * holding it for an insert. The duplicate-effect risk is unchanged by the
     * split — a commit can fail after the call whichever way it is written — and
     * is answered where ADR 0007 says it must be, by a provider idempotency key
     * derived from the command id.
     */
    @SuppressWarnings("unchecked")
    private void run(
            InboxHandler<?> handler,
            ExternalEventEnvelope<JsonNode> envelope,
            JdbcInboxStore.InboxRow row,
            UUID processingToken) {

        ExternalEventEnvelope<Object> typed = typed(handler, envelope);

        if (handler instanceof ExternalWorkInboxHandler<?, ?>) {
            ExternalWorkInboxHandler<Object, Object> external = (ExternalWorkInboxHandler<Object, Object>) handler;
            Object work = external.perform(
                    typed, new ExternalWorkInboxHandler.Attempt(row.attemptCount() + 1, maximumAttempts));

            transactions.executeWithoutResult(status -> {
                external.record(typed, work);
                store.markProcessed(row.id(), processingToken);
            });
            return;
        }

        transactions.executeWithoutResult(status -> {
            ((InboxHandler<Object>) handler).handle(typed);
            store.markProcessed(row.id(), processingToken);
        });
    }

    private ExternalEventEnvelope<Object> typed(InboxHandler<?> handler, ExternalEventEnvelope<JsonNode> envelope) {

        Object payload = objectMapper.treeToValue(envelope.payload(), handler.payloadType());
        return new ExternalEventEnvelope<>(
                envelope.eventId(),
                envelope.eventType(),
                envelope.eventVersion(),
                envelope.tenantId(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.correlationId(),
                envelope.causationId(),
                envelope.occurredAt(),
                payload,
                envelope.payloadSha256(),
                envelope.transport());
    }

    private void count(String consumerName, String eventType, String outcome) {
        Counter.builder("horecaos.inbox.records")
                .description("ADR 0005 inbox outcomes")
                .tag("consumer", consumerName)
                .tag("event_type", eventType)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
