package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.FiscalCorrectionRevisionFeed;

/** {@link FiscalCorrectionRevisionFeed} over {@code ordering.order_revisions}, by its partial index. */
@Repository
class JdbcFiscalCorrectionRevisionFeed implements FiscalCorrectionRevisionFeed {

    private final JdbcClient jdbc;

    JdbcFiscalCorrectionRevisionFeed(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlaggedRevision> flaggedSince(Instant since, @Nullable FlaggedRevision after, int limit) {
        // A row comparison on the whole sort key, so the page starts strictly after the last row of the one before
        // it and two revisions created in the same instant are neither skipped nor read twice.
        String afterClause = after == null
                ? ""
                : " AND (created_at, tenant_id, order_id, revision) > (:afterAt, :afterTenant, :afterOrder, :afterRevision)";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT tenant_id, order_id, revision, created_at, delta_total_minor
                          FROM ordering.order_revisions
                         WHERE fiscal_correction_required
                           AND created_at >= :since
                           AND revision >= 2
                        """ + afterClause + """
                         ORDER BY created_at, tenant_id, order_id, revision
                         LIMIT :limit
                        """)
                .param("since", since.atOffset(ZoneOffset.UTC))
                .param("limit", limit);
        if (after != null) {
            statement = statement
                    .param("afterAt", after.createdAt().atOffset(ZoneOffset.UTC))
                    .param("afterTenant", after.tenantId())
                    .param("afterOrder", after.orderId())
                    .param("afterRevision", after.revision());
        }
        return statement
                .query((row, number) -> new FlaggedRevision(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getInt("revision"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getLong("delta_total_minor")))
                .list();
    }
}
