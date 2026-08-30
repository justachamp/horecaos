package uz.qoida.platform.payments.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the caller asked to be shown, and to whom (ADR 0013).
 *
 * <p>A request and not an instruction. The adapter decides what it can actually
 * produce — Payme has no push at all, and a Click binding with no
 * {@code merchant_id} cannot build a link — so this names a preference and the
 * {@link ProviderInvoice} that comes back names what happened.
 *
 * <p>Nothing here is persisted. {@link #pushRecipient()} is a phone number, which
 * is personal data under ADR 0029: it is carried for the length of one provider
 * call, never written to an attempt row, never put in an event, and never logged.
 * The platform holds the customer's own number under envelope encryption, and
 * revealing it needs a recorded purpose — so the number used here is the one the
 * customer typed for this payment, which is a disclosure they made rather than
 * one the platform performed.
 *
 * @param language {@code ru}, {@code uz} or {@code en}. Payme accepts it on the
 *                 checkout page and ignores anything else; Click's link has no
 *                 language parameter at all
 */
public record PresentationRequest(
        PresentationKind preferredKind,
        String returnUrl,
        String language,
        String pushRecipient) {

    /** The three Payme documents; anything else is silently ignored by Payme. */
    private static final Set<String> LANGUAGES = Set.of("ru", "uz", "en");

    /**
     * Click's {@code phone_number}: twelve digits, country code included, no
     * {@code +}. Documented by example rather than by rule, and enforced here so
     * that a number in some other shape fails before it reaches a mutating call
     * that has no idempotency key to recover with.
     */
    private static final String UZ_MSISDN = "^998\\d{9}$";

    public PresentationRequest {
        Objects.requireNonNull(preferredKind, "A presentation kind is required");

        language = language == null || language.isBlank()
                ? null : language.strip().toLowerCase(Locale.ROOT);
        if (language != null && !LANGUAGES.contains(language)) {
            throw new IllegalArgumentException(
                    "A checkout language must be one of ru, uz or en");
        }

        pushRecipient = pushRecipient == null || pushRecipient.isBlank()
                ? null : pushRecipient.strip();
        if (pushRecipient != null && !pushRecipient.matches(UZ_MSISDN)) {
            throw new IllegalArgumentException(
                    "A push recipient must be twelve digits beginning 998");
        }

        if (preferredKind == PresentationKind.INVOICE_PUSH && pushRecipient == null) {
            throw new IllegalArgumentException(
                    "An invoice push needs the number to push it to");
        }
    }

    /** The ordinary case: a link the browser follows, with nothing personal in it. */
    public static PresentationRequest link() {
        return new PresentationRequest(PresentationKind.PAYMENT_LINK, null, null, null);
    }

    public Optional<String> returnTo() {
        return Optional.ofNullable(returnUrl).filter(url -> !url.isBlank());
    }

    public Optional<String> languageTag() {
        return Optional.ofNullable(language);
    }

    public Optional<String> recipient() {
        return Optional.ofNullable(pushRecipient);
    }

    /**
     * Never includes the recipient.
     *
     * <p>A record's generated {@code toString} is how a phone number reaches a log
     * line without anybody deciding that it should. ADR 0029 puts no personal data
     * in logs, traces, metrics or events, and this is the only field here that is
     * any.
     */
    @Override
    public String toString() {
        return "PresentationRequest[" + preferredKind
                + (pushRecipient == null ? "" : " push=yes") + "]";
    }
}
