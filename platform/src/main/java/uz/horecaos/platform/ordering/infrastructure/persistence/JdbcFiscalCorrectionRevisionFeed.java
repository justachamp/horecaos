package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
    public List<FlaggedRevision> flaggedSince(Instant since, int limit) {
        return jdbc.sql("""
                        SELECT tenant_id, order_id, revision, created_at, delta_total_minor
                          FROM ordering.order_revisions
                         WHERE fiscal_correction_required
                           AND created_at >= :since
                           AND revision >= 2
                         ORDER BY created_at, order_id, revision
                         LIMIT :limit
                        """)
                .param("since", since.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query((row, number) -> new FlaggedRevision(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getInt("revision"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getLong("delta_total_minor")))
                .list();
    }
}
