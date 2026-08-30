package uz.qoida.platform.integration.camel.sms;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * smsgw.vas.uz's status codes, and what each one means for a verification code
 * (ADR 0007), from {@code docs/providers/sms-gateway-vas.md}.
 *
 * <p>The whole table is here rather than only the codes we expect, because a code
 * this enum does not know arrives as {@link #UNDOCUMENTED} and is treated as
 * uncertain — and "uncertain" on this provider means a {@code /search}, not a
 * second SMS. Leaving out the codes that belong to the distribution API would
 * turn an ordinary misconfiguration into that reconciliation path.
 *
 * <p>Four of these are worth reading before changing anything.
 *
 * <p><strong>{@code 20 receiver in blacklist} is a product fact.</strong> A
 * customer the operator has blacklisted can never receive a code and therefore
 * can never sign in by phone. It gets its own reason code so a storefront can say
 * so, rather than being folded into a generic refusal that reads as "try again".
 *
 * <p><strong>{@code 13 wrong key} must be loud.</strong> The key is rotated in the
 * provider's console and then in OpenBao, with no API between the two, so this
 * code means the second half did not happen. It is mapped onto the platform's
 * {@code PROVIDER_AUTHENTICATION} so the gateway spends its one ADR 0028 read
 * past the secret cache — which is what separates "our cache is stale" from
 * "OpenBao is stale" — and {@link SmsProcessor} alarms when it survives that.
 *
 * <p><strong>{@code 1 spam} is an alarm, not a backoff.</strong> The provider
 * allows fifty an hour per number per partner and our own OTP budget is five, so
 * this code cannot be reached by a working limiter. Seeing it means either the
 * limiter is broken or something else is sending on this partner account. It
 * carries no retry delay for exactly that reason: waiting would hide it.
 *
 * <p><strong>{@code 27 server side error} is the only retryable one</strong>, and
 * even that is not retried automatically here — see {@code SmsRouteBuilder}.
 */
enum SmsGateCode {

    SUCCESS(0, Effect.ACCEPTED, "SMS_ACCEPTED"),

    /**
     * Fifty per hour per number per partner. See the class note: an alarm.
     */
    SPAM(1, Effect.REFUSED, "SMS_SPAM_LIMIT"),

    LOGIN_REQUIRED(10, Effect.REFUSED, "SMS_ACCOUNT_MISCONFIGURED"),
    KEY_REQUIRED(11, Effect.REFUSED, "SMS_ACCOUNT_MISCONFIGURED"),

    /** The login is not an account this gateway knows. Configuration, not credential. */
    PARTNER_NOT_FOUND(12, Effect.REFUSED, "SMS_ACCOUNT_MISCONFIGURED"),

    /**
     * Mapped onto the platform-wide authentication code deliberately, so the one
     * bounded refresh past the ADR 0028 secret cache happens before anybody is
     * paged.
     */
    WRONG_KEY(13, Effect.REFUSED, "PROVIDER_AUTHENTICATION"),

    PHONE_REQUIRED(14, Effect.REFUSED, "SMS_REQUEST_INVALID"),
    SENDER_REQUIRED(15, Effect.REFUSED, "SMS_ACCOUNT_MISCONFIGURED"),

    /** The sender string is not registered on this account. There is no registration API. */
    WRONG_SENDER(16, Effect.REFUSED, "SMS_SENDER_NOT_REGISTERED"),

    /** The destination is not a shape this gateway will route to. */
    PHONE_PATTERN(17, Effect.REFUSED, "SMS_DESTINATION_UNROUTABLE"),

    TEXT_REQUIRED(18, Effect.REFUSED, "SMS_REQUEST_INVALID"),

    /** Our template grew past a segment budget. A code fault, not a customer's. */
    TEXT_TOO_LONG(19, Effect.REFUSED, "SMS_TEXT_TOO_LONG"),

    /** Its own outcome. See the class note. */
    RECEIVER_IN_BLACKLIST(20, Effect.REFUSED, "SMS_RECEIVER_BLACKLISTED"),

    /** No operator claims this number range. As final as a blacklist, for us. */
    UNKNOWN_OPERATOR(21, Effect.REFUSED, "SMS_DESTINATION_UNROUTABLE"),

    // 22-26 belong to the distribution API, which this platform does not call.
    // They are listed so that receiving one is a named refusal rather than an
    // undocumented code, which would otherwise trigger a reconciliation search
    // for a message that was never accepted.
    DISTRIBUTION_NAME_REQUIRED(22, Effect.REFUSED, "SMS_UNSUPPORTED_OPERATION"),
    DISTRIBUTION_NOT_CREATED(23, Effect.REFUSED, "SMS_UNSUPPORTED_OPERATION"),
    DISTRIBUTION_ID_REQUIRED(24, Effect.REFUSED, "SMS_UNSUPPORTED_OPERATION"),
    DISTRIBUTION_NOT_FOUND(25, Effect.REFUSED, "SMS_UNSUPPORTED_OPERATION"),
    DISTRIBUTION_EXPIRED(26, Effect.REFUSED, "SMS_UNSUPPORTED_OPERATION"),

    SERVER_ERROR(27, Effect.RETRYABLE, "SMS_PROVIDER_ERROR"),

    /**
     * Anything the document does not list, and the absence of a code at all.
     *
     * <p>Uncertain rather than refused. The provider answered something we cannot
     * read, and on an endpoint with no idempotency key the honest answer is that
     * we do not know whether an SMS left — which is resolved by asking
     * {@code /search}, never by sending again.
     */
    UNDOCUMENTED(Integer.MIN_VALUE, Effect.UNCERTAIN, "SMS_RESPONSE_UNREADABLE");

    /** What the platform does about a code, in ADR 0007's vocabulary. */
    enum Effect { ACCEPTED, REFUSED, RETRYABLE, UNCERTAIN }

    private static final Map<Integer, SmsGateCode> BY_WIRE_VALUE = Stream.of(values())
            .filter(code -> code != UNDOCUMENTED)
            .collect(Collectors.toUnmodifiableMap(SmsGateCode::wireValue, code -> code));

    private final int wireValue;
    private final Effect effect;
    private final String reasonCode;

    SmsGateCode(int wireValue, Effect effect, String reasonCode) {
        this.wireValue = wireValue;
        this.effect = effect;
        this.reasonCode = reasonCode;
    }

    static SmsGateCode of(Integer wireValue) {
        return wireValue == null ? UNDOCUMENTED : BY_WIRE_VALUE.getOrDefault(wireValue, UNDOCUMENTED);
    }

    int wireValue() {
        return wireValue;
    }

    Effect effect() {
        return effect;
    }

    /**
     * The stable code this platform uses for the outcome.
     *
     * <p>Ours rather than the provider's text, and safe to log, to count as a
     * metric tag, and to return as an ADR 0031 problem property. The provider's
     * {@code description} never travels: gateways echo the request back inside an
     * error, and this request holds a phone number and a live one-time code.
     */
    String reasonCode() {
        return reasonCode;
    }
}
