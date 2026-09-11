package uz.horecaos.platform.commercial.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Charging a tenant's stored card for a statement's remainder (ADR 0095
 * item 8). Declared here and implemented in {@code commercial.infrastructure}
 * — the arrangement {@link uz.horecaos.platform.payments.application.PaymentProviderPort}
 * sets for provider adapters generally: the module states what it needs, and
 * whoever can answer does.
 *
 * <p>HorecaOS has no Click or Payme merchant account of its own yet (ADR
 * 0095's own open input), so the only adapter wired today is
 * {@code NotConfiguredCardCharger}, which always answers
 * {@link Outcome.NotConfigured}. A {@code CARD} tenant is then collected
 * exactly like an {@code INVOICE} one: the remainder stays due until a real
 * adapter replaces it.
 *
 * <p>Never sees or stores a card number (ADR 0028): {@code cardTokenReference}
 * is a reference into a provider's own vault, exactly as
 * {@code commercial.tenant_billing.card_token_reference} stores it.
 */
public interface CardCharger {

    /**
     * Attempts to charge {@code amountMinor} to the tenant's stored card.
     *
     * @param cardTokenReference null when {@code CARD} is chosen but no token
     *                           is on file yet; an adapter answers {@code
     *                           Failed} or {@code NotConfigured} for that,
     *                           never a charge
     * @param idempotencyKey     stable per attempt (the statement id is enough,
     *                           since one statement is charged for its
     *                           remainder at most once per attempt), so a
     *                           retried call never risks a second charge once
     *                           a real adapter exists
     */
    Outcome charge(
            UUID tenantId,
            @Nullable String cardTokenReference,
            long amountMinor,
            String currency,
            String idempotencyKey);

    /** What an attempted card charge did. */
    sealed interface Outcome permits Outcome.Succeeded, Outcome.Failed, Outcome.NotConfigured {

        /** The charge succeeded; {@code providerReference} is the wallet entry's external reference. */
        record Succeeded(String providerReference) implements Outcome {}

        /** The provider declined or the attempt failed; the remainder stays due. */
        record Failed(String reason) implements Outcome {}

        /** No merchant account exists yet; the remainder stays due, exactly like INVOICE. */
        record NotConfigured() implements Outcome {}
    }
}
