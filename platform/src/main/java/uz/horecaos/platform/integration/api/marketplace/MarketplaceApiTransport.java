package uz.horecaos.platform.integration.api.marketplace;

import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * Sends one {@link MarketplaceApiCall} through the ADR 0007 marketplace route.
 *
 * <p>Nothing is thrown. Every failure — connection refused, a timeout after the request was
 * written, a 429, a 502, an unparseable body — comes back as one of the four canonical
 * outcomes, and what the platform may then believe the partner holds is drawn from the
 * classification ({@link PushConclusion#of}) rather than from an exception type it would have
 * to re-derive. A timeout and a refused connection are both "failed", and only one leaves the
 * partner as it was.
 */
public interface MarketplaceApiTransport {

    ProviderOutcome exchange(MarketplaceApiCall call);
}
