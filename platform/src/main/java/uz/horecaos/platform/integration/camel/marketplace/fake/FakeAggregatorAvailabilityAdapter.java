package uz.horecaos.platform.integration.camel.marketplace.fake;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import uz.horecaos.platform.integration.api.marketplace.AvailabilityPush;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityAdapter;
import uz.horecaos.platform.integration.api.marketplace.PushConclusion;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The adapter for {@link FakeAggregatorHttpProvider} (ADR 0007, ADR 0141): a {@code
 * marketplace.availability.push} adapter registered under the {@code local} profile only, so a
 * {@code FAKE_AGGREGATOR} installation reads {@code AUTOMATIC} on the propagation read and the
 * reconciler pushes to the loopback fake. Every other provider type still reads {@code MANUAL}, because
 * no real aggregator adapter exists in this build.
 *
 * <p>Names an endpoint and a body and reads the partner's answer; it never decides retry, circuit policy
 * or what to believe afterwards (that is the reconciler's), and no provider DTO leaves it.
 */
public final class FakeAggregatorAvailabilityAdapter implements MarketplaceAvailabilityAdapter {

    /** The {@code integration.installations.provider_type} this adapter serves. */
    public static final String PROVIDER_TYPE = "FAKE_AGGREGATOR";

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String adapterVersion() {
        return "marketplace/fake-aggregator/v1";
    }

    @Override
    public MarketplaceApiCall availabilityCall(AvailabilityPush push) {
        return new MarketplaceApiCall(
                push.tenantId(),
                push.bindingId(),
                push.installationId(),
                push.providerType(),
                "availability.set",
                "PUT",
                FakeAggregatorHttpProvider.ITEMS_PATH
                        + URLEncoder.encode(push.externalItemId(), StandardCharsets.UTF_8)
                                .replace("+", "%20")
                        + "/availability",
                MarketplaceApiCall.fixedBody(Map.of("available", push.available(), "sequence", push.sequence())),
                credential -> Map.of("Authorization", "Bearer " + credential),
                push.correlationId(),
                null);
    }

    @Override
    public ProviderOutcome interpret(ProviderOutcome transportOutcome) {
        String detail = transportOutcome.detail();
        if (transportOutcome.status() == ProviderOutcome.Status.REJECTED
                && detail != null
                && detail.contains("item_not_found")) {
            return ProviderOutcome.rejected(PushConclusion.UNKNOWN_ITEM, "The aggregator does not know this item");
        }
        return transportOutcome;
    }
}
