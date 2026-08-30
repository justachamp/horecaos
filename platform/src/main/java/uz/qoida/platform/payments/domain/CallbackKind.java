package uz.qoida.platform.payments.domain;

/**
 * The inbound surfaces a provider may call (ADR 0013, ADR 0005).
 *
 * <p>On both providers the authoritative "money moved" signal arrives inbound,
 * which is the one thing the port genuinely abstracts. Everything else about
 * these differs down to the content type.
 */
public enum CallbackKind {

    /** Form-encoded, MD5 {@code sign_string}, no auth header, always answered HTTP 200. */
    CLICK_PREPARE,

    /** The same, and the only Click surface that credits an order. */
    CLICK_COMPLETE,

    /** Any of Payme's mandatory JSON-RPC methods, Basic-authenticated, always HTTP 200. */
    PAYME_RPC,

    /** Payme reporting a fiscal outcome back, asynchronously and possibly never. */
    PAYME_SET_FISCAL_DATA,

    TELEGRAM_PRE_CHECKOUT,

    TELEGRAM_SUCCESSFUL_PAYMENT
}
