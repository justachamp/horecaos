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

    @Override
    @Transactional(readOnly = true)
    public long pointsRedeemedMinor(UUID tenantId, UUID orderId) {
        return settlements
                .findSettlement(tenantId, orderId)
                .map(settlement -> settlements.tendersOf(tenantId, settlement.id()).stream()
                        .filter(TenderRow::settlesFromBalance)
                        .filter(tender -> tender.status() == TenderStatus.SETTLED)
                        .mapToLong(TenderRow::amountMinor)
                        .sum())
                .orElse(0L);
    }
}
