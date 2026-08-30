package uz.qoida.platform.integration.camel.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.integration.api.pos.PosApiCall;
import uz.qoida.platform.integration.api.pos.PosApiCall.Effect;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;

/**
 * The one rule the POS gateway adds to the shared ADR 0007 classification.
 *
 * <p>The shared classifier calls a 5xx retryable because every courier partner
 * documents a 5xx as a failed request. No point of sale in this build documents
 * that, and the one implemented documents the opposite: its own retry guidance
 * tells integrators to check the server state before re-sending a non-idempotent
 * request. Handing that back as retryable is a licence to print a second kitchen
 * ticket.
 */
class PosGatewayClassificationTests {

    @Test
    @DisplayName("a retryable failure on an unkeyed create becomes uncertain")
    void aLostCreateIsNeverRetryable() {
        ProviderOutcome corrected = PosGateway.classifyForPos(
                call(Effect.UNKEYED_CREATE),
                ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "504", Duration.ofSeconds(10)));

        assertThat(corrected.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(corrected.mayRetryDirectly()).isFalse();
        assertThat(corrected.requiresReconciliation()).isTrue();
    }

    @Test
    @DisplayName("a read keeps the shared answer, because repeating it cooks nothing")
    void readsStayRetryable() {
        ProviderOutcome corrected = PosGateway.classifyForPos(
                call(Effect.READ),
                ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "500", null));

        assertThat(corrected.mayRetryDirectly()).isTrue();
    }

    @Test
    @DisplayName("a value-setting write keeps the shared answer too")
    void idempotentWritesStayRetryable() {
        ProviderOutcome corrected = PosGateway.classifyForPos(
                call(Effect.IDEMPOTENT_WRITE),
                ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "500", null));

        assertThat(corrected.status())
                .as("writing a fiscal identifier sets one field to one value, so a lost "
                        + "response really is safe to send again")
                .isEqualTo(ProviderOutcome.Status.RETRYABLE);
    }

    @Test
    @DisplayName("an open circuit stays retryable even on a create")
    void nothingWasSentSoThereIsNothingToDiscover() {
        ProviderOutcome corrected = PosGateway.classifyForPos(
                call(Effect.UNKEYED_CREATE),
                ProviderOutcome.retryable("CIRCUIT_OPEN", "open", Duration.ofSeconds(30)));

        assertThat(corrected.status())
                .as("the breaker refused before anything left this process, so the till "
                        + "provably did not act")
                .isEqualTo(ProviderOutcome.Status.RETRYABLE);
    }

    @Test
    @DisplayName("a business rejection is not turned into an uncertainty")
    void aRefusalStaysARefusal() {
        ProviderOutcome corrected = PosGateway.classifyForPos(
                call(Effect.UNKEYED_CREATE),
                ProviderOutcome.rejected("PROVIDER_REJECTED", "validation_failed"));

        assertThat(corrected.status())
                .as("sending a malformed export to a human queue would hide our own bug")
                .isEqualTo(ProviderOutcome.Status.REJECTED);
    }

    @Test
    @DisplayName("the call record does not render its body or its credential")
    void nothingPersonalReachesALogLine() {
        String rendered = call(Effect.UNKEYED_CREATE).toString();

        assertThat(rendered)
                .as("an export body carries the customer's name, telephone number, and address")
                .doesNotContain("secret")
                .doesNotContain("998");
        assertThat(rendered).contains("order.create");
    }

    private static PosApiCall call(Effect effect) {
        return new PosApiCall(UUID.randomUUID(), UUID.randomUUID(), "clopos",
                "order.create", "POST", "/orders",
                PosApiCall.fixedBody(Map.of("customer", Map.of("phone", "+998901234567"))),
                effect,
                PosApiCall.fixedHeaders(Map.of("x-token", "secret-token")),
                "correlation-1", null);
    }
}
