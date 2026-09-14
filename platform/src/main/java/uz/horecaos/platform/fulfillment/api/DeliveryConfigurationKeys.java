package uz.horecaos.platform.fulfillment.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The fulfillment module's ADR 0030 configuration keys (gap map row {@code
 * 10.13}, wave P38).
 *
 * <p>{@link #OUT_OF_ZONE_POLICY} is the first key this module owns. It names
 * what a tenant wants done with an address {@code DeliveryFeeResolver}
 * already refuses — {@code OUT_OF_ZONE} (no zone covers the address) or
 * {@code OUTSIDE_CATCHMENT} (the branch's own catchment guard) — without
 * changing that refusal, which stays unconditional. See the key's own
 * {@code describedAs} for exactly what is, and is not, built yet.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
 * validator consults lives in {@code tenancy.domain.configuration}, which is
 * internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry
 * carries an identical declaration, and {@code DeliveryConfigurationKeyTests}
 * fails the build if the two ever drift apart — the same arrangement {@code
 * TelemetryConfigurationKeys} already documents for its own pair.
 */
public final class DeliveryConfigurationKeys {

    public static final String OUT_OF_ZONE_POLICY_CODE = "delivery.out_of_zone_policy";

    public static final ConfigurationKey<String> OUT_OF_ZONE_POLICY = ConfigurationKey.of(
                    OUT_OF_ZONE_POLICY_CODE, String.class)
            .defaultValue("REJECT")
            .ownedBy("fulfillment")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION)
            .describedAs("What happens to an address DeliveryFeeResolver refuses as out of every "
                    + "zone or outside a branch's catchment: REJECT (default — matches today's "
                    + "unconditional refusal), OFFER_PICKUP, or MANUAL_REVIEW. Not yet enforced: "
                    + "the resolver's own refusal does not read this key yet.")
            .build();

    private DeliveryConfigurationKeys() {}
}
