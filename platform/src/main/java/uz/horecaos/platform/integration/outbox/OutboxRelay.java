package uz.horecaos.platform.integration.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.integration.retry.RetryBackoff;

@Component
@ConditionalOnProperty(
        name = "horecaos.messaging.outbox.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxRelay {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcOutboxStore outbox;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final RetryBackoff backoff;
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadLetterCounter;
    private final AtomicBoolean running = new AtomicBoolean();

    public OutboxRelay(
            JdbcOutboxStore outbox,
            OutboxPublisher publisher,
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${horecaos.messaging.outbox.batch-size:20}") int batchSize,
            @Value("${horecaos.messaging.outbox.lease-duration:5m}") Duration leaseDuration,
            @Value("${horecaos.messaging.outbox.publish-timeout:10s}") Duration publishTimeout,
            @Value("${horecaos.messaging.outbox.max-attempts:10}") int maxAttempts,
            @Value("${horecaos.messaging.outbox.initial-backoff:1s}") Duration initialBackoff,
            @Value("${horecaos.messaging.outbox.max-backoff:5m}") Duration maxBackoff) {
        if (batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("Outbox batch size and max attempts must be positive");
        }
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || publishTimeout.isNegative()
                || publishTimeout.isZero()
                || initialBackoff.isNegative()
                || initialBackoff.isZero()
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Outbox durations must be positive and consistently ordered");
        }
        Duration worstCaseBatch = publishTimeout.multipliedBy(batchSize).plusSeconds(5);
        if (leaseDuration.compareTo(worstCaseBatch) < 0) {
            throw new IllegalArgumentException(
                    "Outbox lease duration must exceed the batch's worst-case publish time");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
        // Jittered rather than pure exponential (ADR 0006). Every replica that
        // failed against the same broker outage holds the same attempt count,
        // so an undithered delay has them all wake together and re-form the
        // burst that caused the failure.
        this.backoff = RetryBackoff.of(initialBackoff, maxBackoff);
        this.publishedCounter = meterRegistry.counter("horecaos.outbox.publications", "outcome", "published");
        this.failedCounter = meterRegistry.counter("horecaos.outbox.publications", "outcome", "retry");
        this.deadLetterCounter = meterRegistry.counter("horecaos.outbox.publications", "outcome", "dead-letter");
    }

    @Scheduled(fixedDelayString = "${horecaos.messaging.outbox.poll-interval:1s}")
    public void relayScheduledBatch() {
        relayOnce();
    }

    public int relayOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }

        try {
            var claimed = outbox.claimBatch(clock.instant(), leaseDuration, batchSize);
            claimed.forEach(this::publish);
            return claimed.size();
        } finally {
            running.set(false);
        }
    }

    private void publish(ClaimedOutboxEvent event) {
        try {
            publisher.publish(event);
            if (outbox.markPublished(event.eventId(), event.claimToken(), clock.instant())) {
                publishedCounter.increment();
            } else {
                logger.warn("Outbox publication lease was lost before completion eventId={}", event.eventId());
            }
        } catch (Exception exception) {
            Instant failedAt = clock.instant();
            boolean deadLetter = event.attemptCount() >= maxAttempts;
            Instant nextAttempt = deadLetter ? failedAt : failedAt.plus(backoff.delayAfter(event.attemptCount()));
            boolean updated = outbox.markFailed(
                    event.eventId(),
                    event.claimToken(),
                    failedAt,
                    nextAttempt,
                    safeError(exception),
                    deadLetter);
            if (!updated) {
                logger.warn("Outbox failure lease was lost before completion eventId={}", event.eventId());
                return;
            }
            if (deadLetter) {
                deadLetterCounter.increment();
                logger.error(
                        "Outbox event exhausted its retry budget eventId={} eventType={} attempts={}",
                        event.eventId(), event.eventType(), event.attemptCount());
            } else {
                failedCounter.increment();
                logger.warn(
                        "Outbox event publication will retry eventId={} eventType={} attempt={}",
                        event.eventId(), event.eventType(), event.attemptCount());
            }
        }
    }

    private static String safeError(Exception exception) {
        String message = exception.getClass().getSimpleName();
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            message += ": " + exception.getMessage();
        }
        message = message.replaceAll("[\\r\\n\\t]+", " ");
        return message.substring(0, Math.min(message.length(), MAX_ERROR_LENGTH));
    }
}
