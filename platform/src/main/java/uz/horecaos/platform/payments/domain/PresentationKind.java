package uz.horecaos.platform.payments.domain;

/**
 * How the customer was shown the payment (ADR 0013).
 *
 * <p>Presentation is the one outbound step common to every provider, and on every
 * provider it proves nothing: Click's payment link takes an arbitrary amount from
 * anyone, Payme's base64 checkout link is equally unauthenticated, and Payme's
 * {@code :transaction} placeholder in a return URL can be the literal string
 * {@code "null"} on a perfectly good payment. So this is recorded for support and
 * for analytics, and no state ever moves because of it.
 */
public enum PresentationKind {
    PAYMENT_LINK,

    CARD_FORM,

    QR,

    INVOICE_PUSH,

    TELEGRAM_INVOICE;

    /**
     * Whether producing this surface is a mutating call to the provider.
     *
     * <p>The distinction decides what a re-presentation may do. A link and a QR
     * are strings built in this process from an amount and an identifier, so
     * handing one to a customer a second time costs nothing and changes nothing.
     * Click's {@code invoice/create} is an HTTP call that pushes a payment request
     * to somebody's phone, it carries no idempotency key — Click's MERCHANT API
     * has none anywhere — and a lost response to it is an uncertainty rather than
     * something to try again. So an abandoned push is never repeated on the
     * customer's own request; a link is.
     */
    public boolean mutatesTheProvider() {
        return this == INVOICE_PUSH || this == TELEGRAM_INVOICE;
    }
}
