package uz.horecaos.platform.loyalty.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.loyalty.api.LoyaltyActivityDirectory;

/**
 * {@link LoyaltyActivityDirectory} over {@link JdbcLoyaltyStore} (gap-map row
 * {@code X.25}), the same adapter shape {@code JdbcAbandonedCartDirectory}
 * gives its own port.
 */
@Service
public class JdbcLoyaltyActivityDirectory implements LoyaltyActivityDirectory {

    private final JdbcLoyaltyStore store;

    public JdbcLoyaltyActivityDirectory(JdbcLoyaltyStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecentChange> recentChanges(
            UUID tenantId, UUID brandId, long minimumAbsMinor, Instant since, int limit) {
        return store.recentCustomerMovements(tenantId, brandId, minimumAbsMinor, since, limit).stream()
                .map(row -> new RecentChange(row.customerAccountId(), row.amountMinor(), row.occurredAt()))
                .toList();
    }
}
