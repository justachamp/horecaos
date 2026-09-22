package uz.horecaos.platform.notifications.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The notifications module's ADR 0030 configuration keys (gap map row {@code
 * 10.9d}, wave P36).
 *
 * <p>Neither switch had a configuration key before wave P36 — an operator
 * could not turn either behaviour on at all, from any surface, and both were
 * declared and settable before either had a reader: **not enforced**, the
 * same {@code ConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE} precedent for a
 * key that is real and self-service before the behaviour reading it exists.
 * Gap map row {@code 10.9d} (wave w8) closed both gaps. {@link
 * #PAYMENT_LINK_AUTO_SEND} is read by {@code payments.notifications.PaymentLinkAutoSendTrigger},
 * on {@code PaymentIntentCreated}, at {@code BRAND} scope, after commit.
 * {@link #AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED} is read by {@code
 * partner.application.MarketplaceShiftNotificationService}, the trigger for
 * an aggregator's own shift-open/close ping over the partner API (ADR 0040),
 * at {@code BRAND} scope.
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
     * 10.9d}) — see the class doc for the trigger that reads it.
     */
    public static final ConfigurationKey<Boolean> PAYMENT_LINK_AUTO_SEND = ConfigurationKey.of(
                    PAYMENT_LINK_AUTO_SEND_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("notifications")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Send a payment link to the customer automatically, rather than only "
                    + "on request, when an online-payment intent is opened.")
            .build();

    public static final String AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED_CODE =
            "notifications.aggregator_shift_notifications_enabled";

    /**
     * Whether an aggregator shift's own open/close events are delivered to
     * the tenant, in the tenant's own business timezone (gap map row {@code
     * 10.9d}) — see the class doc for the trigger that reads it.
     */
    public static final ConfigurationKey<Boolean> AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED = ConfigurationKey.of(
                    AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("notifications")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Deliver an aggregator shift's own open/close notifications, in the "
                    + "tenant's own business timezone.")
            .build();

    private NotificationConfigurationKeys() {}
}
