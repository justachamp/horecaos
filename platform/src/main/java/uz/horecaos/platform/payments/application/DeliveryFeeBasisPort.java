package uz.horecaos.platform.payments.application;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * What delivery fee an order was actually charged (ADR 0013 as amended
 * 2026-08-25, ADR 0037).
 *
 * <p>A delivery-fee reimbursement has two ceilings and they are not the same. The
 * first is what the tenders settled, which {@code OrderSettlementService.refund}
 * already enforces and which this port has nothing to do with. The second is the
 * fee itself: reimbursing 30 000 som of a 12 000 som delivery fee is a refund of
 * the food wearing a delivery label, and it lands in the report the tenant reads
 * to decide whether their courier network is losing money.
 *
 * <p>Payments cannot answer the second question on its own.
 * {@code OrderDirectory.OrderSummary} carries the order total and not its fee
 * component, and {@code ordering.orders.fee_minor} belongs to another module's
 * schema. So the fee arrives through a port, and — following
 * {@code PaymentLegalEntityConfiguration} and {@code OrderProgressPort} — an
 * unwired stand-in answers "not known" rather than guessing.
 *
 * <p>The consequence is recorded rather than hidden. A reimbursement written while
 * the port is unwired stores a null {@code delivery_fee_basis_minor}, which says
 * on the row itself that no fee ceiling was checked. That is a smaller lie than a
 * zero, and it is the column finance filters on when the port is wired and someone
 * asks which historical reimbursements were never bounded.
 */
public interface DeliveryFeeBasisPort {

    /**
     * Returns the delivery fee charged on this order in whole som, or empty when
     * the platform cannot establish one. Empty is never zero: zero means
     * free delivery and would refuse every reimbursement.
     */
    OptionalLong deliveryFeeMinor(UUID tenantId, UUID orderId);

    /** False for the stand-in, so a caller can record that no ceiling existed. */
    default boolean isWired() {
        return true;
    }
}
