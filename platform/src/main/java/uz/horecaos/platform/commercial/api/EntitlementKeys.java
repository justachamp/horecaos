package uz.horecaos.platform.commercial.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The code-owned entitlement catalogue (ADR 0021).
 *
 * <p>The keys are ADR 0021's initial candidates. The <em>values</em> below are
 * not the commercial limits: every {@code safeDefault} here is either null,
 * meaning unlimited, or true, meaning available, because product has not
 * approved a plan catalogue and a limit invented by an engineer is a limit that
 * will one day refuse a paying tenant. Real limits arrive as plan entitlements
 * on an activated plan version, which is a decision with an approver's name on
 * it.
 *
 * <p>Adding a key is a release. Renaming one is a migration, because a stored
 * plan entitlement, an override, and every ledger row reference it by string.
 */
public final class EntitlementKeys {

    // ------------------------------------------------------- standing limits

    public static final EntitlementKey<Long> BRANDS_MAX_COUNT = EntitlementKey.counted("brands.max_count", "brand")
            .ownedBy("tenancy")
            .describedAs("Brands the tenant may have. A standing limit: lowering it never removes a brand.")
            .build();

    public static final EntitlementKey<Long> LOCATIONS_MAX_COUNT = EntitlementKey.counted(
                    "locations.max_count", "location")
            .ownedBy("tenancy")
            .withDimensions("brand_id")
            .describedAs("Branches the tenant may operate. The line every plan is actually sold on.")
            .build();

    public static final EntitlementKey<Long> CONTROL_PLANE_USERS_MAX_COUNT = EntitlementKey.counted(
                    "control_plane.users.max_count", "user")
            .ownedBy("iam")
            .describedAs("Staff accounts with access to the tenant's own console.")
            .build();

    public static final EntitlementKey<Long> CATALOG_PRODUCTS_MAX_COUNT = EntitlementKey.counted(
                    "catalog.products.max_count", "product")
            .ownedBy("catalog")
            .withDimensions("brand_id")
            .describedAs("Products that may exist across the tenant's catalogues.")
            .build();

    public static final EntitlementKey<Long> POS_INSTALLATIONS_MAX_COUNT = EntitlementKey.counted(
                    "pos.installations.max_count", "installation")
            .ownedBy("integration")
            .describedAs("Provider installations of the POS category.")
            .build();

    // ----------------------------------------------------- period allowances

    public static final EntitlementKey<Long> ORDERS_MONTHLY_INCLUDED = EntitlementKey.counted(
                    "orders.monthly_included", "order")
            .resetting(ResetPeriod.BILLING_PERIOD)
            .ownedBy("ordering")
            .withDimensions("location_id", "channel", "fulfillment_mode")
            .describedAs("Orders included in the plan's billing period. The canonical overage line.")
            .build();

    public static final EntitlementKey<Long> NOTIFICATIONS_MONTHLY_INCLUDED = EntitlementKey.counted(
                    "notifications.monthly_included", "message")
            .resetting(ResetPeriod.BILLING_PERIOD)
            .ownedBy("notifications")
            .withDimensions("channel")
            .describedAs("Notification messages included per billing period. Each carries a real per-message cost.")
            .build();

    public static final EntitlementKey<Long> MEDIA_STORAGE_BYTES_INCLUDED = EntitlementKey.counted(
                    "media.storage_bytes_included", "byte")
            .ownedBy("media")
            .describedAs("Stored bytes included. A standing measure: deleting an asset returns the allowance.")
            .build();

    // ------------------------------------------------------------- features

    public static final EntitlementKey<Boolean> POS_INTEGRATIONS_ENABLED = EntitlementKey.feature(
                    "pos.integrations.enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("integration")
            .describedAs("Whether POS provider integrations may be installed and bound.")
            .build();

    public static final EntitlementKey<Boolean> DELIVERY_PARTNER_INTEGRATIONS_ENABLED = EntitlementKey.feature(
                    "delivery.partner_integrations.enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("fulfillment")
            .describedAs("Whether external delivery partners may be used for dispatch.")
            .build();

    public static final EntitlementKey<Boolean> PAYMENTS_PROVIDER_INTEGRATIONS_ENABLED = EntitlementKey.feature(
                    "payments.provider_integrations.enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("payments")
            .describedAs("Whether payment provider installations may be bound to a merchant account.")
            .build();

    public static final EntitlementKey<Boolean> ANALYTICS_ADVANCED_ENABLED = EntitlementKey.feature(
                    "analytics.advanced.enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("reporting")
            .describedAs("Whether the advanced reporting surfaces are available.")
            .build();

    /**
     * ADR 0058's Telegram feature family (digests, brand bot, inbox, staff
     * surfaces), starting with digests. Default {@code FALSE}, not {@code TRUE}
     * like this catalogue's other features: every other key here defaults open
     * because no plan catalogue exists yet to close it, but a boolean everyone
     * already holds true is not a gate ADR 0021's machinery can be proven
     * against. This one is opt-in from the day it ships, which is what "day one"
     * gating in ADR 0058's resolved open input means — a tenant is entitled
     * because a plan or override says so, not because nobody decided otherwise.
     */
    public static final EntitlementKey<Boolean> TELEGRAM_DIGESTS_ENABLED = EntitlementKey.feature(
                    "telegram.digests.enabled")
            .safeDefault(Boolean.FALSE)
            .ownedBy("notifications")
            .describedAs("Whether Telegram supervisor digests (15-minute, half-day, day-close) may be delivered.")
            .build();

    private static final Map<String, EntitlementKey<?>> BY_CODE = index(List.of(
            BRANDS_MAX_COUNT,
            LOCATIONS_MAX_COUNT,
            CONTROL_PLANE_USERS_MAX_COUNT,
            CATALOG_PRODUCTS_MAX_COUNT,
            POS_INSTALLATIONS_MAX_COUNT,
            ORDERS_MONTHLY_INCLUDED,
            NOTIFICATIONS_MONTHLY_INCLUDED,
            MEDIA_STORAGE_BYTES_INCLUDED,
            POS_INTEGRATIONS_ENABLED,
            DELIVERY_PARTNER_INTEGRATIONS_ENABLED,
            PAYMENTS_PROVIDER_INTEGRATIONS_ENABLED,
            ANALYTICS_ADVANCED_ENABLED,
            TELEGRAM_DIGESTS_ENABLED));

    private EntitlementKeys() {}

    public static Collection<EntitlementKey<?>> all() {
        return BY_CODE.values();
    }

    public static Optional<EntitlementKey<?>> find(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /**
     * The declaration for a stored code, or a failure naming the key.
     *
     * <p>Called when a plan version is activated and when a stored row is read,
     * so a key deleted from this file in a later release fails loudly at both
     * ends rather than resolving to nothing.
     */
    public static EntitlementKey<?> require(String code) {
        return find(code)
                .orElseThrow(() -> new UnknownEntitlementKeyException(
                        "Unknown entitlement key \"%s\". Declare it in EntitlementKeys (ADR 0021).".formatted(code)));
    }

    private static Map<String, EntitlementKey<?>> index(List<EntitlementKey<?>> keys) {
        Map<String, EntitlementKey<?>> byCode = new LinkedHashMap<>();
        for (EntitlementKey<?> key : keys) {
            if (byCode.put(key.code(), key) != null) {
                throw new IllegalStateException("Duplicate entitlement key: " + key.code());
            }
        }
        return Map.copyOf(byCode);
    }

    /** Thrown when a stored or requested key has no code-owned declaration. */
    public static final class UnknownEntitlementKeyException extends IllegalStateException {
        public UnknownEntitlementKeyException(String message) {
            super(message);
        }
    }
}
