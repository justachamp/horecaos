package uz.horecaos.platform.integration.failures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.validation.ValidationException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.AccessDeniedException;

/**
 * ADR 0006's classification gap: {@code last_error} was written by {@code
 * OutboxRelay} and {@code InboxExecutor} with nothing turning it into a
 * {@link FailureCategory}. Each pair of tests below asserts a rule and its
 * adjacent negative — the same discipline {@code CLAUDE.md} names directly:
 * "when you write an assertion, say out loud what would still be true if the
 * code were broken".
 */
class FailureClassifierTests {

    @Test
    void aTimeoutIsTransientInfrastructure() {
        assertThat(FailureClassifier.classify(new TimeoutException("no reply")))
                .isEqualTo(FailureCategory.TRANSIENT_INFRASTRUCTURE);
        assertThat(FailureClassifier.classify(new SocketTimeoutException("read timed out")))
                .isEqualTo(FailureCategory.TRANSIENT_INFRASTRUCTURE);
        assertThat(FailureClassifier.classify(new ConnectException("connection refused")))
                .isEqualTo(FailureCategory.TRANSIENT_INFRASTRUCTURE);
    }

    @Test
    void aCancellationIsNotTransientInfrastructure() {
        // Adjacent to TimeoutException by package and by "something did not
        // finish", but a deliberately cancelled operation was not stopped by
        // infrastructure, and retrying it on a timer would be wrong.
        assertThat(FailureClassifier.classify(new CancellationException("cancelled")))
                .as(
                        "a cancellation is not an infrastructure failure, and this classifier is not confident enough to guess")
                .isEqualTo(FailureCategory.UNKNOWN);
    }

    @Test
    void kafkasOwnRetriableMarkerIsTransientInfrastructure() {
        // org.apache.kafka.common.errors.TimeoutException implements
        // RetriableException — the client library's own documented signal
        // that a produce attempt may simply be repeated.
        assertThat(FailureClassifier.classify(new org.apache.kafka.common.errors.TimeoutException("broker busy")))
                .isEqualTo(FailureCategory.TRANSIENT_INFRASTRUCTURE);
    }

    @Test
    void aKafkaExceptionThatIsNotRetriableIsNotTransientInfrastructure() {
        // Adjacent: same org.apache.kafka.common.errors package, same
        // ApiException ancestor, but RecordTooLargeException does not
        // implement RetriableException — retrying a record too large for the
        // broker produces the same rejection forever.
        assertThat(FailureClassifier.classify(new RecordTooLargeException("record exceeds max size")))
                .isEqualTo(FailureCategory.UNKNOWN);
    }

    @Test
    void aTransientDataAccessFailureIsTransientInfrastructure() {
        assertThat(FailureClassifier.classify(new QueryTimeoutException("statement timeout")))
                .isEqualTo(FailureCategory.TRANSIENT_INFRASTRUCTURE);
    }

    @Test
    void anAccessDeniedExceptionIsAuthorizationRejected() {
        assertThat(FailureClassifier.classify(new AccessDeniedException("not entitled")))
                .isEqualTo(FailureCategory.AUTHORIZATION_REJECTED);
    }

    @Test
    void kafkasOwnAuthorizationExceptionIsAuthorizationRejected() {
        assertThat(FailureClassifier.classify(new AuthorizationException("not authorized to produce")))
                .isEqualTo(FailureCategory.AUTHORIZATION_REJECTED);
    }

    @Test
    void aGenericSecurityExceptionIsNotAuthorizationRejected() {
        // Adjacent: also security-flavored, but java.lang.SecurityException
        // carries no promise about tenant, scope, or service identity, which
        // is exactly what ADR 0006 means by this category.
        assertThat(FailureClassifier.classify(new SecurityException("generic security failure")))
                .isEqualTo(FailureCategory.UNKNOWN);
    }

    @Test
    void anIllegalArgumentIsPayloadInvalid() {
        assertThat(FailureClassifier.classify(new IllegalArgumentException("missing required field")))
                .isEqualTo(FailureCategory.PAYLOAD_INVALID);
    }

    @Test
    void aBeanValidationFailureIsPayloadInvalid() {
        assertThat(FailureClassifier.classify(new ValidationException("constraint violated")))
                .isEqualTo(FailureCategory.PAYLOAD_INVALID);
    }

    @Test
    void aNullPointerIsNotPayloadInvalid() {
        // Adjacent: just as common on a missing field as on an unrelated
        // programming defect. Guessing "payload invalid" about a null
        // pointer in application code is precisely the wrong-guess failure
        // mode this classifier exists to avoid.
        assertThat(FailureClassifier.classify(new NullPointerException("unexpected null")))
                .isEqualTo(FailureCategory.UNKNOWN);
    }

    @Test
    void anUnrecognisedExceptionIsHonestlyUnknownRatherThanForcedIntoACategory() {
        assertThat(FailureClassifier.classify(new ArithmeticException("divide by zero")))
                .isEqualTo(FailureCategory.UNKNOWN);
        assertThat(FailureClassifier.classify(new IOException("some other kind of I/O failure")))
                .as("a bare IOException is too broad a shape to trust")
                .isEqualTo(FailureCategory.UNKNOWN);
    }

    @Test
    void aFutureGetFailureIsClassifiedByItsRealCause() {
        // KafkaTemplate#send(...).get(timeout, unit) wraps the broker's own
        // failure in ExecutionException. Classifying the wrapper itself would
        // always answer UNKNOWN and defeat every rule above.
        assertThat(FailureClassifier.classify(new ExecutionException(new TimeoutException("broker busy"))))
                .isEqualTo(FailureCategory.TRANSIENT_INFRASTRUCTURE);
    }

    @Test
    void anExecutionExceptionWithNoCauseDoesNotThrow() {
        assertThat(FailureClassifier.classify(new ExecutionException(null))).isEqualTo(FailureCategory.UNKNOWN);
    }

    /**
     * ADR 0029: a provider or handler exception's message can quote the value
     * it rejected. This asserts the design guarantee directly rather than by
     * inference — the classifier is proven never to call {@link
     * Throwable#getMessage()} at all, on any of the shapes it recognizes, by
     * an exception whose {@code getMessage()} fails the test if invoked.
     */
    @Test
    void classificationNeverReadsTheExceptionMessage() {
        assertThatCode(() -> FailureClassifier.classify(new LoudMessageTimeoutException()))
                .doesNotThrowAnyException();
        assertThat(FailureClassifier.classify(new LoudMessageTimeoutException()))
                .isEqualTo(FailureCategory.TRANSIENT_INFRASTRUCTURE);
    }

    private static final class LoudMessageTimeoutException extends TimeoutException {

        @Override
        public String getMessage() {
            throw new AssertionError("FailureClassifier must never call getMessage()");
        }
    }
}
