package uz.horecaos.platform.pricing.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort.Redemption.Status;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.CustomerRedemptionRow;

/**
 * {@link CustomerDiscountHistoryPort}'s implementation (row 7.9a): a thin
 * read that maps {@link JdbcPromoCodeStore#redemptionsForCustomer} onto the
 * port's own {@code Status} enum rather than carrying the store's raw
 * string across the module boundary.
 */
@Service
public class CustomerDiscountHistoryService implements CustomerDiscountHistoryPort {

    private final JdbcPromoCodeStore store;

    public CustomerDiscountHistoryService(JdbcPromoCodeStore store) {
        this.store = store;
    }

    @Override
    public List<Redemption> history(UUID tenantId, UUID customerAccountId) {
        return store.redemptionsForCustomer(tenantId, customerAccountId).stream()
                .map(CustomerDiscountHistoryService::toRedemption)
                .toList();
    }

    private static Redemption toRedemption(CustomerRedemptionRow row) {
        return new Redemption(
                row.redemptionId(),
                row.brandId(),
                row.couponId(),
                row.codeHint(),
                row.promotionId(),
                row.promotionName(),
                row.orderId(),
                Status.valueOf(row.status()),
                row.amountMinor(),
                row.currency(),
                row.reservedAt(),
                row.redeemedAt(),
                row.releasedAt());
    }
}
