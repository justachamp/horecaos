package uz.horecaos.platform.integration.camel.cardbilling;

import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * Sends one {@link CardBillingApiCall} through the ADR 0007 card-billing route.
 *
 * <p>Nothing is thrown. Every failure -- connection refused, read timeout, a 401, a 502, an
 * unparseable body -- comes back as one of the four canonical outcomes, and the adapter decides
 * its next move from the classification. The outcome's {@code normalized()} map is the provider's
 * parsed JSON answer, unchanged: interpreting it is the adapter's job.
 *
 * <p>An interface so the adapter is exercised against a recording fake with no route, no HTTP and
 * no secrets manager as well as against a real socket.
 */
public interface CardBillingApiTransport {

    ProviderOutcome exchange(CardBillingApiCall call);
}
