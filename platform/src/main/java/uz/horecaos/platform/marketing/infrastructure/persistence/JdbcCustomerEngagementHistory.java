package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry.Kind;
import uz.horecaos.platform.customers.api.CustomerHistorySource;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort.Redemption;

/**
 * How the marketing side has touched one guest, for the customer card (ADR 0111 §2): the campaigns
 * that reached her and the promotions she redeemed.
 *
 * <p>ADR 0111 names {@code marketing.api.CustomerEngagementHistoryPort} for exactly these two
 * shapes -- campaign receipts and promo redemptions -- and says ADR 0112 grows the same contract
 * with scenario step decisions and shown offers. This is that contract, declared as a {@link
 * CustomerHistorySource} in {@code customers.api} because {@code marketing} already imports it and
 * the reverse edge would close a cycle; ADR 0112's additions are further entries of the same source
 * rather than a second port, which is the decision's own intent.
 *
 * <p>The receipts are marketing's own rows ({@code marketing.campaign_recipients}); the redemptions
 * are pricing's, read through the port pricing already publishes for the customer pane's promos tab
 * -- marketing keeps no copy of either. A receipt shows the campaign's own name (tenant-authored,
 * never a guest's), its channel, and how the send ended or why this guest was refused.
 */
@Component
public class JdbcCustomerEngagementHistory implements CustomerHistorySource {

    private final JdbcClient jdbc;
    private final CustomerDiscountHistoryPort discounts;

    public JdbcCustomerEngagementHistory(JdbcClient jdbc, CustomerDiscountHistoryPort discounts) {
        this.jdbc = jdbc;
        this.discounts = discounts;
    }

    @Override
    public String name() {
        return "MARKETING";
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerHistoryEntry> history(
            UUID tenantId, UUID customerAccountId, @Nullable Instant before, int limit) {
        List<CustomerHistoryEntry> entries = new ArrayList<>(receipts(tenantId, customerAccountId, before, limit));
        for (Redemption redemption : discounts.history(tenantId, customerAccountId)) {
            Instant when = redemption.redeemedAt() != null ? redemption.redeemedAt() : redemption.reservedAt();
            if (before != null && !when.isBefore(before)) {
                continue;
            }
            entries.add(new CustomerHistoryEntry(
                    Kind.PROMO_REDEMPTION,
                    when,
                    null,
                    redemption.status().name(),
                    redemption.codeHint(),
                    redemption.redemptionId(),
                    redemption.orderId(),
                    null,
                    redemption.promotionName()));
        }
        return entries;
    }

    private List<CustomerHistoryEntry> receipts(UUID tenantId, UUID accountId, @Nullable Instant before, int limit) {
        return jdbc.sql("""
                SELECT r.campaign_id, c.name, c.channel, r.status, r.refusal_reason, r.terminal_status,
                       COALESCE(r.terminal_at, r.created_at) AS happened_at
                  FROM marketing.campaign_recipients r
                  JOIN marketing.campaigns c ON c.id = r.campaign_id AND c.tenant_id = r.tenant_id
                 WHERE r.tenant_id = :tenantId AND r.customer_account_id = :accountId
                   AND (CAST(:before AS timestamptz) IS NULL
                        OR COALESCE(r.terminal_at, r.created_at) < CAST(:before AS timestamptz))
                 ORDER BY happened_at DESC, r.campaign_id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("before", before == null ? null : before.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query((rs, n) -> new CustomerHistoryEntry(
                        Kind.CAMPAIGN_RECEIPT,
                        rs.getObject("happened_at", OffsetDateTime.class).toInstant(),
                        rs.getString("channel"),
                        rs.getString("terminal_status") != null
                                ? rs.getString("terminal_status")
                                : rs.getString("status"),
                        rs.getString("refusal_reason"),
                        rs.getObject("campaign_id", UUID.class),
                        null,
                        null,
                        rs.getString("name")))
                .list();
    }
}
