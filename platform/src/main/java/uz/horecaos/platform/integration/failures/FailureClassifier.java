package uz.horecaos.platform.integration.failures;

import jakarta.validation.ValidationException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Turns a caught {@link Throwable} into a {@link FailureCategory} at the point
 * a retry attempt gives up (ADR 0006).
 *
 * <p>{@code OutboxRelay} and {@code InboxExecutor} are the two callers, and
 * both call this <em>before</em> the exception is flattened to the {@code
 * last_error} text that a dead-letter list returns. That ordering is the whole
 * design: this class classifies on the exception's <strong>type</strong> only
 * and never calls {@link Throwable#getMessage()}. A provider or handler
 * exception's message can quote the value it rejected — a phone number, an
 * address — and {@code last_error} already carries that text into the row as a
 * known, bounded gap (see {@code FailureOperationsService.OutboxFailureDetail}'s
 * javadoc). Matching against the message would launder that same personal data
 * through this classifier's own output, into a column ADR 0029 treats as safe
 * to hand to any holder of {@code integration.failure.read} — cross-tenant,
 * and without {@code customer.pii.reveal}.
 *
 * <p>Every rule below is a type this class is genuinely confident about, not a
 * best guess. A category assigned in error is worse than none: an operator who
 * trusts {@link FailureCategory#TRANSIENT_INFRASTRUCTURE} may leave an item for
 * the retry timer forever while the real fault is permanent, or trust a
 * rejection category and never retry a blip that would have cleared itself.
 * When a cause is not confidently one of the shapes named here, the honest
 * answer is {@link FailureCategory#UNKNOWN}, not a guess — the same discipline
 * {@code MessagingBacklogMetrics} already applies by coalescing an absent
 * inbox category to that literal.
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    /**
     * Classifies one failed attempt.
     *
     * <p>Unwraps exactly one level of {@link ExecutionException}, because
     * {@code KafkaOutboxPublisher} learns of a broker-side failure through
     * {@code Future.get()}, which always wraps the real cause once. Neither
     * caller nests a second future inside a future, so a single unwrap is
     * enough; unwrapping indefinitely would risk walking into a cause chain an
     * unrelated library built for its own reasons.
     */
    public static FailureCategory classify(Throwable failure) {
        Throwable cause = unwrap(failure);

        if (isTransientInfrastructure(cause)) {
            return FailureCategory.TRANSIENT_INFRASTRUCTURE;
        }
        if (isAuthorizationRejected(cause)) {
            return FailureCategory.AUTHORIZATION_REJECTED;
        }
        if (isPayloadInvalid(cause)) {
            return FailureCategory.PAYLOAD_INVALID;
        }
        return FailureCategory.UNKNOWN;
    }

    /**
     * A failure whose own type promises it is safe to retry: a timeout, an
     * unreachable host, or Kafka's own {@link RetriableException} marker —
     * the client library's documented signal that a produce attempt may
     * simply be repeated. Spring's {@link TransientDataAccessException} is the
     * same signal for the database side: a deadlock, a lock-wait timeout, a
     * connection the pool could not obtain right now.
     *
     * <p>Deliberately excludes a bare {@link java.io.IOException} and a bare
     * {@link IllegalStateException}. Both are used in this codebase's own test
     * fixtures to simulate an arbitrary failure with no particular meaning —
     * see {@code InboxExecutorTests}' {@code RecordingHandler}, which throws
     * {@code IllegalStateException} to mean "simulated transient handler
     * failure" while {@code JdbcOutboxStoreTests} throws the same type to mean
     * "broker unavailable". A type this codebase already uses for unrelated
     * failures is not a type this classifier can trust.
     */
    private static boolean isTransientInfrastructure(Throwable cause) {
        return cause instanceof TimeoutException
                || cause instanceof SocketTimeoutException
                || cause instanceof ConnectException
                || cause instanceof RetriableException
                || cause instanceof TransientDataAccessException;
    }

    /**
     * Spring Security's own "the principal may not do this" exception, and
     * Kafka's {@code AuthorizationException} — the client library's answer
     * when the configured principal is not entitled to produce to the topic.
     * Both name authorization specifically, unlike the generic {@link
     * SecurityException}, which this classifier does not map: a JVM security
     * manager can raise it for reasons that have nothing to do with a tenant,
     * scope, or service identity, which is what this category promises ADR
     * 0006's alerting means by it.
     */
    private static boolean isAuthorizationRejected(Throwable cause) {
        return cause instanceof AccessDeniedException
                || cause instanceof org.apache.kafka.common.errors.AuthorizationException;
    }

    /**
     * Bean Validation's own failure and {@link IllegalArgumentException} — the
     * two ways an inbox handler's own code says "this event's data did not
     * have what I needed" rather than "something external went wrong".
     * Deliberately excludes {@link NullPointerException}: it is exactly as
     * common on a missing field as it is on an unrelated programming defect,
     * and this classifier would rather say {@code UNKNOWN} than tell an
     * operator "payload invalid" about a null pointer in application code.
     */
    private static boolean isPayloadInvalid(Throwable cause) {
        return cause instanceof ValidationException || cause instanceof IllegalArgumentException;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof ExecutionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}
