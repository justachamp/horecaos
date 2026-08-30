package uz.horecaos.platform.pos.infrastructure.clopos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.integration.api.pos.PosApiCall.Effect;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * Reading a Clopos response, including the ones whose status code disagrees with
 * their body.
 */
class CloposEnvelopeTests {

    @Test
    @DisplayName("HTTP 200 with success false is a failure")
    void theBodyIsReadBeforeTheStatusCode() {
        ProviderOutcome transport = ProviderOutcome.success(Map.of(
                "success", false,
                "error", "Integrator is in test mode. But brand is not in test mode"), null);

        ProviderOutcome outcome = CloposEnvelope.read(transport, Effect.READ);

        assertThat(outcome.status())
                .as("Clopos returns this authentication failure as 200 OK, and an adapter that "
                        + "branches on status alone treats it as a successful login")
                .isEqualTo(ProviderOutcome.Status.REJECTED);
    }

    @Test
    @DisplayName("a broken integrator registration is distinguished from a bad tenant credential")
    void aPlatformWideFailureIsNamedAsOne() {
        ProviderOutcome transport = ProviderOutcome.success(Map.of(
                "success", false, "error", "Invalid integrator_id"), null);

        ProviderOutcome outcome = CloposEnvelope.read(transport, Effect.READ);

        assertThat(outcome.errorCode())
                .as("treating this as a tenant credential failure would suspend every binding "
                        + "in the estate the day Clopos deactivates our integrator")
                .isEqualTo("CLOPOS_INTEGRATOR_INVALID");
    }

    @Test
    @DisplayName("a disabled Open API module is the restaurant's own switch")
    void aDisabledClientIsItsOwnAnswer() {
        ProviderOutcome transport = ProviderOutcome.rejected(
                "PROVIDER_AUTHENTICATION", "{\"error\":\"Client is disabled\"}");

        ProviderOutcome outcome = CloposEnvelope.read(transport, Effect.READ);

        assertThat(outcome.errorCode()).isEqualTo("CLOPOS_CLIENT_DISABLED");
    }

    @Test
    @DisplayName("an expired token is the one retryable authentication failure")
    void anExpiredTokenIsRetryable() {
        ProviderOutcome transport = ProviderOutcome.rejected(
                "PROVIDER_AUTHENTICATION", "{\"error\":\"Token expired\"}");

        ProviderOutcome outcome = CloposEnvelope.read(transport, Effect.READ);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(outcome.errorCode()).isEqualTo("CLOPOS_TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("an uncertain transport outcome is never second-guessed into something safer")
    void uncertaintyIsNotReclassified() {
        ProviderOutcome transport = ProviderOutcome.uncertain("READ_TIMEOUT", "no response");

        ProviderOutcome outcome = CloposEnvelope.read(transport, Effect.UNKEYED_CREATE);

        assertThat(outcome.status())
                .as("only the transport knows whether the request left the process")
                .isEqualTo(ProviderOutcome.Status.UNCERTAIN);
    }

    @Test
    @DisplayName("money is read from the literal rather than through a double")
    void decimalsAreExact() {
        assertThat(CloposEnvelope.decimal(Map.of("price", 8.5), "price"))
                .isEqualByComparingTo("8.5");
        assertThat(CloposEnvelope.decimal(Map.of("total", 30000), "total"))
                .isEqualByComparingTo("30000");
        assertThat(CloposEnvelope.decimal(Map.of("price", "12.25"), "price"))
                .isEqualByComparingTo("12.25");
    }

    @Test
    @DisplayName("a provider error body is truncated before it reaches a detail field")
    void anEchoedRequestBodyCannotGrowUnbounded() {
        String long_ = "x".repeat(2_000);
        ProviderOutcome transport = ProviderOutcome.success(
                Map.of("success", false, "error", "validation_failed", "message", long_), null);

        ProviderOutcome outcome = CloposEnvelope.read(transport, Effect.READ);

        assertThat(outcome.detail().length())
                .as("a Clopos error has been observed to echo request content, and a request "
                        + "body here carries a customer's address")
                .isLessThan(400);
    }
}
