package uz.horecaos.platform.payments.infrastructure.persistence;

import java.util.OptionalLong;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.payments.application.ReceiptPaymentFacts;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.TenderRow;
import uz.horecaos.platform.payments.settlement.TenderStatus;

/** {@link ReceiptPaymentFacts} over the payment intent and the order's settlement. */
@Component
public class JdbcReceiptPaymentFacts implements ReceiptPaymentFacts {

    private final JdbcPaymentIntentStore intents;
    private final JdbcSettlementStore settlements;

    public JdbcReceiptPaymentFacts(JdbcPaymentIntentStore intents, JdbcSettlementStore settlements) {
        this.intents = intents;
        this.settlements = settlements;
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong providerAmountMinor(UUID tenantId, @Nullable UUID paymentIntentId) {
        if (paymentIntentId == null) {
            return OptionalLong.empty();
        }
        return intents.find(tenantId, paymentIntentId)
                .map(intent -> OptionalLong.of(intent.amount().value()))
                .orElse(OptionalLong.empty());
    }

    /**
     * The points the order's balance tender holds or has spent: {@code RESERVED} and {@code SETTLED}.
     *
     * <p><strong>{@code RESERVED} counts, not only {@code SETTLED}.</strong> Payme's lines are built when the checkout is
     * first presented, which is before the money arrives, and at that moment the balance tender is {@code RESERVED}
     * ({@code OrderSettlementService.plan} takes the hold and reserves it; {@code settleWhenMoneyArrives} settles it on
     * capture). Counting settled tenders alone answered zero there, so the lines added up to the order total while the
     * intent was for the money leg, and a Payme checkout of any order that redeems points was refused as
     * {@code RECEIPT_NOT_BUILDABLE}. The same set as {@link JdbcSettlementStore#cashDueMinor}: points the customer has
     * given up are a discount whether or not the settlement has closed.
     *
     * <p>{@code RELEASED}, {@code FAILED} and {@code REVERSED} are excluded on purpose: those points went back to the
     * customer, so nothing was redeemed and no line carries a discount for it. {@code PLANNED} is excluded as well, for
     * a hold that was never taken is no redemption.
     */
    @Override
    @Transactional(readOnly = true)
    public long pointsRedeemedMinor(UUID tenantId, UUID orderId) {
        return settlements
                .findSettlement(tenantId, orderId)
                .map(settlement -> settlements.tendersOf(tenantId, settlement.id()).stream()
                        .filter(TenderRow::settlesFromBalance)
                        .filter(tender ->
                                tender.status() == TenderStatus.RESERVED || tender.status() == TenderStatus.SETTLED)
                        .mapToLong(TenderRow::amountMinor)
                        .sum())
                .orElse(0L);
    }
}
