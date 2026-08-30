package uz.qoida.platform.payments.click;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.payments.infrastructure.click.ClickErrorCodes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an absent {@code error_code} may and may not be taken to mean (ADR 0013).
 *
 * <p>The whole file is about one asymmetry. Click documents responses that carry
 * no {@code error_code} at all — the {@code ofd_data} GET among them — so a read
 * that insisted on one would report a successful fiscal read-back as a rejection.
 * A mutating call is the opposite: {@code payment/reversal} answering 2xx with a
 * body nobody could parse says nothing about whether money went back to a
 * cardholder, and treating that silence as success records a refund the customer
 * never received.
 */
class ClickErrorCodeTests {

    @Test
    @DisplayName("a read with no error_code succeeded; the ofd_data GET is documented that way")
    void anAbsentCodeIsSuccessOnARead() {
        assertThat(ClickErrorCodes.successfulRead(null)).isTrue();
        assertThat(ClickErrorCodes.successfulRead(0)).isTrue();
        assertThat(ClickErrorCodes.successfulRead(-5001)).isFalse();
    }

    @Test
    @DisplayName("a mutating call with no error_code has not succeeded")
    void anAbsentCodeIsNotSuccessOnAMutatingCall() {
        assertThat(ClickErrorCodes.successfulMutation(null))
                .as("a 2xx with an empty or unparsed body is not Click saying it reversed a "
                        + "payment, and recording a REVERSE for the full amount on the strength "
                        + "of it asserts money moved that may never have moved")
                .isFalse();
        assertThat(ClickErrorCodes.successfulMutation(0)).isTrue();
        assertThat(ClickErrorCodes.successfulMutation("0")).isTrue();
        assertThat(ClickErrorCodes.successfulMutation(-31300)).isFalse();
    }

    @Test
    @DisplayName("an absent code leaves a mutating call open; a non-zero one does not")
    void onlyAnAbsentCodeIsUncertain() {
        assertThat(ClickErrorCodes.uncertainMutation(null))
                .as("nobody can say whether Click acted, so resolve it by querying")
                .isTrue();
        assertThat(ClickErrorCodes.uncertainMutation(-31300))
                .as("unclassified, because the enumeration is not published — but Click did "
                        + "answer, and an answer is not an unknown")
                .isFalse();
        assertThat(ClickErrorCodes.uncertainMutation(0)).isFalse();
    }

    @Test
    @DisplayName("a non-numeric code is never read as success")
    void anUnparseableCodeIsNotSuccess() {
        assertThat(ClickErrorCodes.successfulRead("OK")).isFalse();
        assertThat(ClickErrorCodes.successfulMutation("OK")).isFalse();
        assertThat(ClickErrorCodes.uncertainMutation("OK"))
                .as("Click sent something; it is a failure to classify, not a missing answer")
                .isFalse();
    }
}
