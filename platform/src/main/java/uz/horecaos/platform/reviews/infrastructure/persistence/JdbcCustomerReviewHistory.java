package uz.horecaos.platform.reviews.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry.Kind;
import uz.horecaos.platform.customers.api.CustomerHistorySource;
import uz.horecaos.platform.customers.api.HistoryCursor;

/**
 * The reviews one guest has left, for the customer card (ADR 0111 §2): the rating and the order it
 * was for, never the comment.
 *
 * <p>The comment is a customer's own words about a real visit (ADR 0071) and is envelope-encrypted
 * for exactly that reason; a card that listed it would decrypt on every open. A staff member who
 * wants to read it opens the review, where the read is authorised and audited as it always was. ADR
 * 0111 asks for {@code CustomerReviewPort.history}; this is that history, declared as a {@link
 * CustomerHistorySource} for the reason that interface gives -- {@code reviews} already imports
 * {@code customers.api}, and the reverse edge would close a cycle.
 */
@Component
public class JdbcCustomerReviewHistory implements CustomerHistorySource {

    private final JdbcClient jdbc;

    public JdbcCustomerReviewHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "REVIEWS";
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerHistoryEntry> history(
            UUID tenantId, UUID customerAccountId, @Nullable HistoryCursor before, int limit) {
        return jdbc.sql("""
                SELECT id, order_id, rating, submitted_at
                  FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                   AND (CAST(:before AS timestamptz) IS NULL
                        OR (submitted_at, id) < (CAST(:before AS timestamptz), CAST(:beforeId AS uuid)))
                 ORDER BY submitted_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .param("before", before == null ? null : before.occurredAt().atOffset(ZoneOffset.UTC))
                .param("beforeId", before == null ? null : before.referenceId().toString())
                .param("limit", limit)
                .query((rs, n) -> new CustomerHistoryEntry(
                        Kind.REVIEW,
                        rs.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                        null,
                        "SUBMITTED",
                        null,
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getInt("rating"),
                        null))
                .list();
    }
}
