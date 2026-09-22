package uz.horecaos.platform.payments.api;

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
     * customer owes nothing"; a caller that has a safe fallback for that
     * case (an order total, most likely) is expected to catch and use it
     * itself, the way {@code DeliveryAccrualOrderCompletionTrigger} does.
     */
    long cashDueMinor(UUID tenantId, UUID orderId);
}
