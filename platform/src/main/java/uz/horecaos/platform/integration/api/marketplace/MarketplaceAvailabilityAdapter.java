package uz.horecaos.platform.integration.api.marketplace;

import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * An aggregator adapter that can be told which of its mapped items are available (ADR 0040
 * {@code marketplace.availability.push}, ADR 0141).
 *
 * <p>Declaring it is the capability. A provider type with no adapter of this kind is not an
 * error — it is a provider that has no stop API a third party can call, and the platform says
 * so (ADR 0011: an unsupported capability may never be the sole business path): every binding
 * of it shows {@code MANUAL} on the propagation read, "not propagated automatically", and the
 * operator updates the partner portal by hand. Whether any of Uzum Tezkor, Yandex Eda, Wolt or
 * Express24 exposes such an API is a commercial question outside a design decision, so this
 * build ships the mechanism and no adapter.
 *
 * <p>An adapter names an endpoint and a body and reads the partner's answer; it never decides
 * retry, circuit policy or what to believe afterwards. Provider DTOs stay inside it.
 */
public interface MarketplaceAvailabilityAdapter {

    /** The {@code integration.installations.provider_type} this adapter serves. */
    String providerType();

    /** A stable version string for the capability declaration, e.g. {@code marketplace/yandex-eda/v1}. */
    String adapterVersion();

    /** The one call that makes {@code push.externalItemId} available or unavailable. */
    MarketplaceApiCall availabilityCall(AvailabilityPush push);

    /**
     * Reads the transport's classified outcome in the provider's own terms. An adapter
     * that can tell "this partner has no such item" returns {@code ProviderOutcome.rejected(
     * PushConclusion.UNKNOWN_ITEM, ...)}; everything else passes through. The default does no
     * interpretation.
     */
    default ProviderOutcome interpret(ProviderOutcome transportOutcome) {
        return transportOutcome;
    }
}
