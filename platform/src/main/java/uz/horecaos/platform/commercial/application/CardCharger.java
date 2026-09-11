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
     * @param idempotencyKey     identifies one charge attempt, and nothing
     *                           coarser: the same key is re-sent only when
     *                           retrying that same attempt for that same
     *                           amount, and a charge for a different amount
     *                           always carries a new one. It is the id of a
     *                           {@code commercial.card_charge_attempts} row
     *                           written before this call. A provider may treat
     *                           key reuse with changed parameters as an error,
     *                           or may replay the earlier result, and both are
     *                           fatal here — which is why this is no longer the
     *                           statement id. That was justified as "one
     *                           statement is charged for its remainder at most
     *                           once per attempt", true within one settlement
     *                           pass and false across them: settlement runs
     *                           again on every transfer, deposit, approved
     *                           grant and upward correction and charges
     *                           whatever is still owed, so one declined
     *                           statement, part-paid by a transfer, came back
     *                           under the same key for a smaller amount
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
