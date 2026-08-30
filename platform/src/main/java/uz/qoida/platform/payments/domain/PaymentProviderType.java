package uz.qoida.platform.payments.domain;

/**
 * The payment providers the platform integrates (ADR 0013).
 *
 * <p>Two of them ship together, deliberately. Click is outbound-dominant with an
 * inbound SHOP API callback that is the only surface which credits an order;
 * Payme is an inbound JSON-RPC server whose single endpoint is the whole
 * integration. Building one first and adding the other later gets that provider's
 * transaction identity, reversal direction, and reconciliation direction
 * backwards, so two providers is the constraint that stops the port being shaped
 * like one provider.
 */
public enum PaymentProviderType {

    CLICK,

    PAYME,

    /**
     * Designed for, not built.
     *
     * <p>Telegram is a third shape wearing the same two-phase silhouette: money
     * moves through Telegram's Bot Payments API with a BotFather-issued provider
     * token and no provider callback, and Payme additionally requires a separate
     * bot cashbox. The value here costs a column and an opaque invoice payload;
     * what is missing is a reconciliation path, because Payme does not document
     * whether a bot-cashbox payment reaches the Merchant API endpoint at all.
     * Present so that adding the channel is configuration rather than a redesign.
     */
    TELEGRAM;

    /**
     * Whether Qoida may initiate a reversal against this provider.
     *
     * <p>A declared fact rather than a call that fails. On Payme there is no
     * outbound refund at all — the cabinet's refund button calls Qoida's
     * {@code CancelTransaction}, and Qoida's only lever is to veto it — so a
     * uniform outbound refund capability would be a lie on half the providers.
     * The binding carries the authoritative answer; this is the default it is
     * configured from.
     */
    public boolean reversalIsOutbound() {
        return this == CLICK;
    }

    /** Whether this provider can produce a fiscal receipt for a payment it settled. */
    public boolean canFiscalize() {
        return this == CLICK || this == PAYME;
    }
}
