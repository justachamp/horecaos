package uz.horecaos.platform.customers.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The customers module's ADR 0030 configuration keys.
 *
 * <p>The first is the phone-shape gate ADR 0063's share-contact sign-in
 * checks a Telegram-attested contact against before resolving or creating a
 * customer account. The owner's 2026-09-08 answer to that ADR's open input
 * ("the allowed-phone pattern's final value") is "configurable" — this key,
 * consumed by {@code integration.provider.telegram.TelegramUpdateHandler},
 * replaces what was a deployment-time {@code @Value} property with a
 * platform-default, tenant/brand-overridable ADR 0030 value: an operator can
 * now widen or narrow which markets may sign in through a specific brand's bot
 * without a deploy.
 *
 * <p>The second is the dual-channel OTP delivery order the owner's
 * 2026-09-08 direction asks for: "keep both SMS and Telegram Gateway" for
 * ADR 0015 verification codes, and let the control plane decide which one is
 * tried first rather than burying the order in Java. See {@link
 * #OTP_DELIVERY_CHANNEL_ORDER} for the wire shape and the fallback rule it
 * governs.
 *
 * <p><strong>This is not {@code customers.domain.PhoneNumber}.</strong> That
 * class hard-codes {@code \+998\d{9}} for {@code requireDeliverableMobile}, the
 * SMS verification-challenge path, deliberately — its own Javadoc calls
 * widening it "an anti-fraud control" against SMS-pumping fraud, "a deliberate
 * change with a conversation about destination pricing attached". This key
 * governs a different, narrower gate: which Telegram-attested phone the
 * share-contact bot flow accepts, where the fraud shape is not the same (the
 * number arrives already verified by Telegram, not dialled at the platform's
 * expense) and per-brand configurability is exactly what ADR 0063 asked for.
 * Nothing here changes what {@code PhoneNumber} accepts.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator
 * consults lives in {@code tenancy.domain.configuration}, which is internal to
 * the tenancy module; importing it here is not possible. The registry
 * therefore carries an identical declaration, and {@code
 * CustomerConfigurationKeysTests} fails the build if the two ever drift apart
 * — the same arrangement {@code CommercialConfigurationKeys} and {@code
 * TelemetryConfigurationKeys} use.
 */
public final class CustomerConfigurationKeys {

    /** The code both declarations share. */
    public static final String TELEGRAM_AUTH_PHONE_PATTERN_CODE = "customers.telegram_auth_phone_pattern";

    /**
     * The regular expression a Telegram-shared contact's phone number must
     * match for ADR 0063 share-contact sign-in to proceed.
     *
     * <p>Settable down to a brand: one tenant's brands can serve different
     * markets behind different bots, and ADR 0030 scoping exists for exactly
     * that. Not settable per location — a Telegram bot binds at brand level,
     * never narrower (see {@code TelegramInstallationBrandLookup}).
     *
     * <p><strong>This key narrows; it cannot widen.</strong> Whatever it
     * admits, {@code customers.domain.PhoneNumber} still has to accept
     * afterwards, and that constant is deliberately Uzbek-only — read its own
     * comment, which says widening is "a deliberate change with a conversation
     * about destination pricing attached". Every OTP to a destination the
     * platform has not decided to pay for is money spent reaching nobody it
     * serves, so a per-brand regular expression must not be the way around that
     * conversation. Setting this to something broader than {@code PhoneNumber}
     * is therefore not an error and not a widening: the number still gets as
     * far as {@code CustomerVerificationService} and is still refused there.
     * Use it to restrict a brand's bot further than the platform default —
     * a single operator prefix, say — and change {@code PhoneNumber} itself if
     * a market genuinely opens.
     */
    public static final ConfigurationKey<String> TELEGRAM_AUTH_PHONE_PATTERN = ConfigurationKey.of(
                    TELEGRAM_AUTH_PHONE_PATTERN_CODE, String.class)
            .defaultValue("^\\+?998\\d{9}$")
            .ownedBy("customers")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Regular expression a Telegram-shared contact's phone number must match "
                    + "for ADR 0063 share-contact sign-in. Defaults to an Uzbek mobile in E.164.")
            .build();

    /** The code both declarations share. */
    public static final String OTP_DELIVERY_CHANNEL_ORDER_CODE = "customers.otp_delivery_channel_order";

    /** One of the two tokens {@link #OTP_DELIVERY_CHANNEL_ORDER} accepts. */
    public static final String CHANNEL_TELEGRAM_GATEWAY = "TELEGRAM_GATEWAY";

    /** The other. */
    public static final String CHANNEL_SMS = "SMS";

    /** The platform default: cheaper-first, matching ADR 0063's own Decision text. */
    public static final String DEFAULT_OTP_DELIVERY_CHANNEL_ORDER = CHANNEL_TELEGRAM_GATEWAY + "," + CHANNEL_SMS;

    /**
     * The order {@code integration.camel.sms.CamelVerificationCodeTransport}
     * tries ADR 0015 verification-code delivery channels in.
     *
     * <p>A comma-separated permutation of exactly {@code TELEGRAM_GATEWAY} and
     * {@code SMS}, both present — see {@link #CHANNEL_TELEGRAM_GATEWAY} and
     * {@link #CHANNEL_SMS}. The default, {@link #DEFAULT_OTP_DELIVERY_CHANNEL_ORDER},
     * is ADR 0063's own decision restated as data: Telegram Gateway costs a
     * fraction of an SMS but only reaches a number with a Telegram account, so
     * it goes first and SMS is what catches everyone else.
     *
     * <p><strong>Order, not membership.</strong> ADR 0063's own Alternatives
     * table rules out ever dropping SMS entirely ("the Gateway cannot reach a
     * number with no Telegram account; SMS stays the fallback... Never
     * fully") — frozen Decision-adjacent text this key does not reopen. What
     * this key controls is purely which channel {@code
     * CamelVerificationCodeTransport} asks first; whichever one goes second
     * is still asked whenever the first one is skipped (Gateway unconfigured)
     * or does not accept the message. A value naming only one channel, naming
     * a channel twice, or naming anything else is rejected at resolution time
     * — logged and treated as the platform default — for the same reason a
     * malformed {@link #TELEGRAM_AUTH_PHONE_PATTERN} refuses every sign-in
     * rather than crashing a webhook: an operator's typo in the control plane
     * must not silently stop every OTP in the tenant.
     *
     * <p>Settable down to a brand, the same reach as the phone pattern above:
     * one tenant's brands can serve markets with different Telegram
     * penetration. Telegram Gateway itself stays platform-wide (one token,
     * not one binding per brand — see {@code TelegramGatewayClient}'s own
     * Javadoc) but which channel a given brand tries <em>first</em> is a
     * business call this key exists to let an operator make without a
     * deploy, per the owner's standing direction that platform configuration
     * belongs at the control plane.
     */
    public static final ConfigurationKey<String> OTP_DELIVERY_CHANNEL_ORDER = ConfigurationKey.of(
                    OTP_DELIVERY_CHANNEL_ORDER_CODE, String.class)
            .defaultValue(DEFAULT_OTP_DELIVERY_CHANNEL_ORDER)
            .ownedBy("customers")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Comma-separated order (a permutation of TELEGRAM_GATEWAY,SMS, both required) "
                    + "in which ADR 0015 verification-code delivery tries its two channels. "
                    + "Defaults to Telegram Gateway first (cheaper) with SMS as the reachability "
                    + "fallback; whichever channel is not tried first is still tried when the "
                    + "first is unconfigured or does not accept the message.")
            .build();

    private CustomerConfigurationKeys() {}
}
