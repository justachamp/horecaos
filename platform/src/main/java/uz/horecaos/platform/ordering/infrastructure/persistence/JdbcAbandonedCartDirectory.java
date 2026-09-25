package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.AbandonedCartDirectory;

/**
 * {@link AbandonedCartDirectory} over {@link JdbcCartStore} (gap-map rows 1.4,
 * 6.5).
 */
@Service
public class JdbcAbandonedCartDirectory implements AbandonedCartDirectory {

    private final JdbcCartStore carts;

    public JdbcAbandonedCartDirectory(JdbcCartStore carts) {
        this.carts = carts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AbandonedCart> abandonedSince(Instant olderThan, int limit) {
        return carts.abandonedForAutomation(olderThan, limit).stream()
                .map(row -> new AbandonedCart(
                        row.tenantId(), row.brandId(), row.cartId(), row.customerAccountId(), row.abandonedAt()))
                .toList();
    }
}
