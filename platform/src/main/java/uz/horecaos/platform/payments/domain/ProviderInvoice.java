package uz.horecaos.platform.payments.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
 *                           on Click the join key is HorecaOS's own id and the
 *                           provider's arrives later, so the port may assume no
 *                           direction of identity
 * @param providerToken      the BotFather-issued token a Telegram invoice needs.
 *                           Present so that adding the Telegram channel costs a
 *                           field rather than a redesign; unused today
 * @param invoicePayload     Telegram's opaque payload, for the same reason
 * @param form               a checkout the browser must POST rather than follow (Payme's checkout form, the one
 *                           surface documented to carry the fiscal {@code detail}); null when the surface is the
 *                           link or the QR. When present, {@code checkoutUrl} is still the GET link, for the
 *                           surfaces that cannot post
 */
public record ProviderInvoice(
        PresentationKind presentationKind,
        @Nullable String checkoutUrl,
        @Nullable String qrPayload,
        @Nullable String externalInvoiceId,
        @Nullable String providerToken,
        @Nullable String invoicePayload,
        @Nullable Instant expiresAt,
        @Nullable PaymentForm form) {

    /**
     * A form the browser submits to the provider: where, and the hidden fields.
     *
     * <p>The fields are the provider's own checkout fields, in order. One of them is the fiscal receipt detail
     * (base64 JSON of the lines), which is financial data about what a named customer bought; this record is
     * handed to the customer's own browser and nowhere else, never stored on the attempt and never logged.
     */
    public record PaymentForm(String action, Map<String, String> fields) {
        public PaymentForm {
            Objects.requireNonNull(action, "A form action is required");
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /** Never includes a field value: one of them is the receipt. */
        @Override
        public String toString() {
            return "PaymentForm[" + action + " fields=" + fields.keySet() + "]";
        }
    }

    public ProviderInvoice {
        Objects.requireNonNull(presentationKind, "A presentation kind is required");
    }

    /** A surface that is not a form: every presentation there was before Payme's checkout form. */
    public ProviderInvoice(
            PresentationKind presentationKind,
            @Nullable String checkoutUrl,
            @Nullable String qrPayload,
            @Nullable String externalInvoiceId,
            @Nullable String providerToken,
            @Nullable String invoicePayload,
            @Nullable Instant expiresAt) {
        this(
                presentationKind,
                checkoutUrl,
                qrPayload,
                externalInvoiceId,
                providerToken,
                invoicePayload,
                expiresAt,
                null);
    }

    /** The same checkout, to be submitted as a form. */
    public ProviderInvoice withForm(PaymentForm paymentForm) {
        return new ProviderInvoice(
                presentationKind,
                checkoutUrl,
                qrPayload,
                externalInvoiceId,
                providerToken,
                invoicePayload,
                expiresAt,
                paymentForm);
    }

    public static ProviderInvoice link(String checkoutUrl, Instant expiresAt) {
        return new ProviderInvoice(PresentationKind.PAYMENT_LINK, checkoutUrl, null, null, null, null, expiresAt);
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
