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

    /**
     * ADR 0058's operations alerts — order confirmations, payment failures,
     * fiscal blocks, 86'd items, dead letters — as distinct from the digests
     * above: a tenant that bound a chat did so to hear about its own orders,
     * so this one defaults {@code TRUE} like the catalogue's other features
     * (an unsubscribed tenant is an unfinished sale, never a tenant that
     * stops working), where digests are the opt-in half of the family.
     * Registered here at the wave-6 merge, replacing the provisional local
     * key {@code TelegramOperationsEntitlementGate} declared while the two
     * worktrees could not see each other.
     */
    public static final EntitlementKey<Boolean> TELEGRAM_OPERATIONS_ALERTS_ENABLED = EntitlementKey.feature(
                    "telegram.operations_alerts.enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("notifications")
            .describedAs("Whether operations Telegram alerts may fan out to this tenant's bound chats (ADR 0058).")
            .build();

    /**
     * ADR 0060: whether the Telegram staff bot answers a tap or a typed
     * command with anything beyond its stage-1, read-only reach — the
     * Approve/Reject buttons, the stop-list toggle, and the stats query.
     *
     * <p>{@code safeDefault(TRUE)} for the same reason every other feature key
     * here defaults open: the pilot runs meter-only, and a no-POS tenant that
     * onboarded before a plan exists must not lose the one floor ADR 0060
     * promises it. Linking an account and receiving notifications are
     * unaffected either way — this key gates interactivity specifically, not
     * ADR 0058's stage-1 plumbing underneath it.
     */
    public static final EntitlementKey<Boolean> TELEGRAM_BOT_INTERACTIVE_ENABLED = EntitlementKey.feature(
                    "integration.telegram_bot.interactive_enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("integration")
            .describedAs("Whether the Telegram staff bot accepts callback taps and typed commands for this tenant.")
            .build();

    /**
     * ADR 0058 stage 2's customer half: whether a customer's own linked 1:1
     * chat may receive transactional notifications on TELEGRAM in place of
     * SMS. {@code safeDefault(TRUE)}, the owner's own resolution of ADR
     * 0058's "entitlement-gated from day one" open input — matching {@link
     * #TELEGRAM_OPERATIONS_ALERTS_ENABLED}'s open default rather than {@link
     * #TELEGRAM_DIGESTS_ENABLED}'s: a customer who linked their chat did so
     * to hear about their own orders, the same "unsubscribed tenant is an
     * unfinished sale" reasoning that key's own doc comment gives. Gating
     * routing only, never linking itself: a customer may still link and
     * unlink their chat, and toggle their own preference, with this entitlement
     * off — what it withholds is the switch from SMS to TELEGRAM on send.
     */
    public static final EntitlementKey<Boolean> TELEGRAM_CUSTOMER_NOTIFICATIONS_ENABLED = EntitlementKey.feature(
                    "telegram.customer_notifications.enabled")
            .safeDefault(Boolean.TRUE)
            .ownedBy("notifications")
            .describedAs(
                    "Whether a customer's linked Telegram chat may receive transactional notifications (ADR 0058).")
            .build();

    /**
     * ADR 0059: whether the conversations engine may run flows for this
     * tenant's brand bots at all — the resellable SendPulse-replacement
     * product itself, not a channel add-on to it. {@code safeDefault(FALSE)},
     * matching {@link #TELEGRAM_DIGESTS_ENABLED}'s reasoning rather than
     * {@link #TELEGRAM_CUSTOMER_NOTIFICATIONS_ENABLED}'s: a tenant that has
     * never been sold this product must not have every brand bot answering
     * {@code /start} with a flow nobody at that tenant configured or agreed
     * to pay for. Gates {@link uz.horecaos.platform.iam.api.Capability
     * #CONVERSATION_FLOW_MANAGE}'s own authoring surface not at all —
     * authoring a flow is always allowed; this key gates the engine actually
     * running one against a live chat.
     */
    public static final EntitlementKey<Boolean> TELEGRAM_CONVERSATIONS_ENABLED = EntitlementKey.feature(
                    "telegram.conversations.enabled")
            .safeDefault(Boolean.FALSE)
            .ownedBy("conversations")
            .describedAs("Whether the conversations flow engine may run for this tenant's Telegram brand bots.")
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
            TELEGRAM_DIGESTS_ENABLED,
            TELEGRAM_OPERATIONS_ALERTS_ENABLED,
            TELEGRAM_BOT_INTERACTIVE_ENABLED,
            TELEGRAM_CUSTOMER_NOTIFICATIONS_ENABLED,
            TELEGRAM_CONVERSATIONS_ENABLED));

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
