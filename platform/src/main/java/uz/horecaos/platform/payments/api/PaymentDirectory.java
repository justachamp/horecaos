package uz.horecaos.platform.payments.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The small read another module needs about an order's payment (ADR 0013).
 *
 * <p>The same shape {@code OrderDirectory} takes, for the same reason: a consumer
 * needing more than an event carries has to ask somewhere, and this is where.
 * Deliberately not a window onto the attempts — a provider's own state, its
 * identifiers and the protected payload references are the payments module's, and
 * an operations console reads them through an authorized API with a recorded
 * purpose.
 */
public interface PaymentDirectory {

    Optional<PaymentSummary> summary(UUID tenantId, UUID orderId);

    /**
     * Orders carrying no provider fiscal receipt because cash cannot have one.
     *
     * <p>Exposed rather than kept internal because the whole point of recording
     * {@code NOT_APPLICABLE} as a value is that it is queryable. If the 2026-08-22
     * decision reverses, this is what finds the affected orders.
     */
    List<UnfiscalizedCashOrder> unfiscalizedCashOrders(UUID tenantId, Instant from, Instant to, int limit);

    /**
     * @param paid           whether the money has arrived, which is not the same as
     *                       whether an attempt succeeded: a reversed capture leaves
     *                       this true and a returned amount beside it
     * @param uncertain      whether an outcome is unknown and nothing further may be
     *                       attempted. A caller must handle this rather than treat
     *                       it as an error
     * @param capturedMinor  whole som, from the append-only transactions and never
     *                       from a running total
     */
    record PaymentSummary(
            UUID intentId,
            UUID orderId,
            String tender,
            String paymentMethodCode,
            String providerType,
            String status,
            boolean paid,
            boolean uncertain,
            boolean requiredBeforeConfirmation,
            long requestedAmountMinor,
            long capturedMinor,
            long returnedMinor,
            String currency,
            String fiscalStatus,
            String fiscalReasonCode) {}

    record UnfiscalizedCashOrder(
            UUID orderId, UUID fiscalDocumentId, String reasonCode, String reasonNote, Instant recordedAt) {}
}
