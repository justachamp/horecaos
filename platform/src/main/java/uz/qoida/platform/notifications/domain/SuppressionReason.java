package uz.qoida.platform.notifications.domain;

/**
 * Why a message that was intended will never be sent (ADR 0020).
 *
 * <p>A stable code and never free text, for the reason {@code OrderRejected} gives
 * for its own reason code: a sentence somebody typed is untranslatable and
 * eventually contains personal data. This is what a tenant reads when they ask why
 * a customer did not get their confirmation, so every value here has to be an
 * answer to that question rather than a log line.
 */
public enum SuppressionReason {

    /** The customer withdrew consent, or was never asked, for the purpose. */
    CONSENT_WITHHELD,

    /** The customer switched this class off on this channel. */
    PREFERENCE_DISABLED,

    /** No account, so no ADR 0015 contact to resolve. Guest orders land here. */
    NO_RECIPIENT_ACCOUNT,

    /** The account has no contact point of the kind this channel addresses. */
    NO_RECIPIENT_ENDPOINT,

    /** No active template version for this tenant, brand, key, and channel. */
    NO_ACTIVE_TEMPLATE,

    /** An active template exists but not in the locale this customer reads. */
    NO_TEMPLATE_FOR_LOCALE,

    /** The channel has no adapter in this release. */
    CHANNEL_NOT_AVAILABLE
}
