package uz.qoida.platform.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of presenting a payment to the customer (ADR 0013).
 *
 * <p>It is a presentation and never a payment. Click's payment link, Click's
 * in-page card form, Payme's base64 checkout link and Payme's QR are all
 * unauthenticated surfaces that anyone may construct with an arbitrary amount, so
 * nothing about this credits an order. The authoritative signal always arrives
 * inbound, on both providers, which is the one thing the port truly abstracts.
 *
 * @param checkoutUrl        where to send the browser, or null for a push
 * @param qrPayload          the string to render as a QR, or null
 * @param externalInvoiceId  Click's {@code invoice_id} or Payme's receipt id when
 *                           the provider minted one at presentation. Often absent:
 *                           on Click the join key is Qoida's own id and the
 *                           provider's arrives later, so the port may assume no
 *                           direction of identity
 * @param providerToken      the BotFather-issued token a Telegram invoice needs.
 *                           Present so that adding the Telegram channel costs a
 *                           field rather than a redesign; unused today
 * @param invoicePayload     Telegram's opaque payload, for the same reason
 */
public record ProviderInvoice(
        PresentationKind presentationKind,
        String checkoutUrl,
        String qrPayload,
        String externalInvoiceId,
        String providerToken,
        String invoicePayload,
        Instant expiresAt) {

    public ProviderInvoice {
        Objects.requireNonNull(presentationKind, "A presentation kind is required");
    }

    public static ProviderInvoice link(String checkoutUrl, Instant expiresAt) {
        return new ProviderInvoice(PresentationKind.PAYMENT_LINK, checkoutUrl, null, null,
                null, null, expiresAt);
    }

    public static ProviderInvoice qr(String qrPayload, Instant expiresAt) {
        return new ProviderInvoice(PresentationKind.QR, null, qrPayload, null, null, null, expiresAt);
    }

    public Optional<String> url() {
        return Optional.ofNullable(checkoutUrl);
    }

    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }

    /**
     * Never includes the provider token.
     *
     * <p>A Telegram provider token authorises charges, so it is a credential in
     * everything but name and must not reach a log line by way of a record's
     * generated {@code toString}.
     */
    @Override
    public String toString() {
        return "ProviderInvoice[" + presentationKind + " invoice=" + externalInvoiceId + "]";
    }
}
