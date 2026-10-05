package uz.horecaos.platform.payments.api;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * What a courier — or anyone else standing at the door — is actually owed in
 * cash for an order (ADR 0046).
 *
 * <p>Not the order total: a split-tender order settles part of itself from a
 * customer's loyalty balance or another non-cash tender, and only the money
 * leg is left to collect at handover. The figure this port answers is
 * computed from the settlement's own tenders — the same read {@code
 * OrderSettlementService.cashDueMinor} already gives the courier app — so a
 * caller outside the payments module never has to re-derive it from the
 * order total and risk collecting a customer's points twice.
 */
public interface CashDueLookupPort {

    /**
     * The order total less every tender that is not cash and has already
     * taken its share (reserved or settled).
     *
     * <p>Throws rather than answering zero for an order this port cannot
     * find a settlement for — a caller must not read "I don't know" as "the
     * customer owes nothing". <strong>Only a caller with no transaction of
     * its own to protect may catch that and use a fallback</strong> (the
     * after-commit {@code DeliveryAccrualOrderCompletionTrigger} does): the
     * implementation is a transactional bean method, so when it throws into a
     * caller's transaction it has already marked that transaction
     * rollback-only, and the caller's catch cannot undo it. A caller inside a
     * request transaction asks {@link #cashDueMinorIfSettled} instead.
     */
    long cashDueMinor(UUID tenantId, UUID orderId);

    /**
     * The same figure as {@link #cashDueMinor}, or empty for an order that
     * has no settlement — answered, never thrown, so a caller inside its own
     * transaction can fall back (to the order total, most likely) without the
     * lookup having poisoned that transaction.
     *
     * <p>Empty means "I do not know", not "nothing is due"; zero is a real
     * answer for an order whose tenders already hold all of it.
     */
    OptionalLong cashDueMinorIfSettled(UUID tenantId, UUID orderId);
}
