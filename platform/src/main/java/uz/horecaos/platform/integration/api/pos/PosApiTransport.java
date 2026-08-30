package uz.horecaos.platform.integration.api.pos;

import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * Sends one {@link PosApiCall} through the ADR 0007 POS route.
 *
 * <p>Nothing is thrown. Every failure — connection refused, read timeout, a 401,
 * a 504, an unparseable body — comes back as one of the four canonical outcomes,
 * and the adapter's next move is decided from the classification rather than from
 * an exception type it would have to re-derive.
 *
 * <p>The outcome's {@code normalized()} map is the provider's parsed JSON body,
 * unchanged. That is deliberate. A provider-neutral transport cannot know that
 * one POS returns {@code 200 OK} with {@code success: false} for a
 * test-integrator-against-production-brand mismatch, so it does not try;
 * interpreting the body is the adapter's job and the adapter is where that
 * knowledge is written down.
 */
public interface PosApiTransport {

    ProviderOutcome exchange(PosApiCall call);
}
