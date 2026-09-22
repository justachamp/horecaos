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
     * taken its share (reserved or settled) — zero on an order with no
     * settlement at all, rather than a fabricated figure.
     */
    long cashDueMinor(UUID tenantId, UUID orderId);
}
