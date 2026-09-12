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

    /**
     * Asks what the provider currently believes happened to a previously
     * asked attempt, without charging anything.
     *
     * <p>The only caller is {@code WalletService.settleOneCardRemainder},
     * and only for an attempt {@code beginCardAttempt} is about to retry
     * under its own id rather than mint a fresh one — one left {@code
     * PENDING} by an earlier, unanswered {@link #charge} call, or one a
     * racing settlement pass for the same tenant already committed. Asking
     * first is what makes the retry a reconciliation rather than a second
     * charge: a provider that is sure the attempt already succeeded is
     * recorded without calling {@link #charge} again, and anything else —
     * declined, or simply unknown — is answered by {@link #charge} itself,
     * replayed under the same {@code idempotencyKey}, which a provider that
     * honours keys must treat as the same attempt rather than a new one.
     *
     * @param idempotencyKey the id of the {@code commercial.card_charge_attempts}
     *                       row this attempt was first asked under
     */
    StatusOutcome status(String idempotencyKey);

    /** What an attempted card charge did. */
    sealed interface Outcome permits Outcome.Succeeded, Outcome.Failed, Outcome.NotConfigured {

        /** The charge succeeded; {@code providerReference} is the wallet entry's external reference. */
        record Succeeded(String providerReference) implements Outcome {}

        /** The provider declined or the attempt failed; the remainder stays due. */
        record Failed(String reason) implements Outcome {}

        /** No merchant account exists yet; the remainder stays due, exactly like INVOICE. */
        record NotConfigured() implements Outcome {}
    }

    /**
     * What the provider currently believes about a previously asked attempt,
     * with only the two arms {@link #status} exists to distinguish: sure it
     * already succeeded, or anything else. A decline and a genuinely unknown
     * answer are the same arm here because they lead to the same action —
     * replay {@link #charge} under the same key — and a provider is free to
     * tell them apart on that call the way {@link Outcome} already does.
     */
    sealed interface StatusOutcome permits StatusOutcome.Succeeded, StatusOutcome.NotSucceeded {

        /** The provider is sure this attempt already succeeded; {@code providerReference} is its own reference. */
        record Succeeded(String providerReference) implements StatusOutcome {}

        /** Declined, unknown, or no merchant account: safe to replay {@link #charge} under the same key. */
        record NotSucceeded() implements StatusOutcome {}
    }
}
