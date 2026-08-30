package uz.qoida.platform.integration.api.payment;

import uz.qoida.platform.integration.api.provider.ProviderOutcome;

/**
 * Sends one {@link MerchantApiCall} through the ADR 0007 payment route.
 *
 * <p>Nothing is thrown. Every failure — connection refused, read timeout, a 401, a
 * 502, an unparseable body — comes back as one of the four canonical outcomes, and
 * the caller's next move is decided from the classification rather than from an
 * exception type it would have to re-derive.
 *
 * <p>The outcome's {@code normalized()} map is the provider's parsed JSON response
 * body, unchanged. That is deliberate: a provider-neutral transport cannot know
 * that Click's {@code error_code: 0} beside {@code payment_status: 1} means the
 * call succeeded and the money has <em>not</em> moved, so it does not try.
 * Interpreting the body is the adapter's job.
 */
public interface MerchantApiTransport {

    ProviderOutcome exchange(MerchantApiCall call);
}
