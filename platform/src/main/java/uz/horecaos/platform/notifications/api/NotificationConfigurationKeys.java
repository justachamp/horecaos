package uz.horecaos.platform.notifications.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The notifications module's ADR 0030 configuration keys (gap map row {@code
 * 10.9d}, wave P36).
 *
 * <p>Neither switch had a configuration key before this wave — an operator
 * could not turn either behaviour on at all, from any surface. Both are
 * declared and settable now, matching {@code
 * ConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE}'s own precedent for a key
 * that is real and self-service before the behaviour reading it exists:
 * **not yet enforced**.
 * {@link #PAYMENT_LINK_AUTO_SEND} has no trigger that reads it yet — sending a
 * payment link automatically needs a template key and a trigger listener the
 * same shape {@code OrderNotificationTrigger} already gives order confirmation,
 * which is its own build. {@link #AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED} has
 * no aggregator-shift domain concept anywhere in the codebase yet for it to
 * gate — there is no {@code ShiftOpened}/{@code ShiftClosed} fact to listen
 * for. Both are named here as open build items rather than left as one more
 * silent gap on the settings screen.
 *
 * <p><strong>Declared twice</strong>, the same discipline {@code
 * CourierConfigurationKeys} documents: this declaration is what a caller in
 * this module reads, and {@code
 * uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys}
 * carries an identical one for the startup validator, since importing across
 * that boundary would make the two modules cyclic. {@code
 * NotificationConfigurationKeyTests} fails the build if they drift.
 */
public final class NotificationConfigurationKeys {

    public static final String PAYMENT_LINK_AUTO_SEND_CODE = "notifications.payment_link_auto_send";

    /**
     * Whether a payment link is sent to the customer automatically rather
     * than only on request. A Delever-parity behaviour (gap map row {@code
     * 10.9d}) with no trigger yet — see the class doc.
     */
    public static final ConfigurationKey<Boolean> PAYMENT_LINK_AUTO_SEND = ConfigurationKey.of(
                    PAYMENT_LINK_AUTO_SEND_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("notifications")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Send a payment link to the customer automatically, rather than only "
                    + "on request. Not yet enforced: no trigger reads this key yet.")
            .build();

    public static final String AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED_CODE =
            "notifications.aggregator_shift_notifications_enabled";

    /**
     * Whether an aggregator shift's own open/close events are delivered to
     * the tenant, in the tenant's own business timezone (gap map row {@code
     * 10.9d}). No aggregator-shift domain fact exists yet for this to gate —
     * see the class doc.
     */
    public static final ConfigurationKey<Boolean> AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED = ConfigurationKey.of(
                    AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("notifications")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Deliver an aggregator shift's own open/close notifications, in the "
                    + "tenant's own business timezone. Not yet enforced: no aggregator-shift "
                    + "domain fact exists yet to trigger it.")
            .build();

    private NotificationConfigurationKeys() {}
}
