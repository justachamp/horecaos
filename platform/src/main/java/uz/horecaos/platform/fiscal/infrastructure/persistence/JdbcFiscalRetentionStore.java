package uz.horecaos.platform.fiscal.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The read side of the retention report (ADR 0166 Decision 10).
 *
 * <p>Reads, and only reads: the application role holds no {@code DELETE} on a fiscal table (V0039, V0551, V0643), so
 * the retention period holds by the absence of a way to erase anything. What is useful to say about it is how much
 * evidence a tenant holds and how much of it is older than the period, so that whoever one day builds an
 * erasure knows what it would touch before it exists.
 */
@Repository
public class JdbcFiscalRetentionStore {

    private final JdbcClient jdbc;

    public JdbcFiscalRetentionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What a tenant holds. */
    public record Holding(
            UUID tenantId, long documents, @Nullable Instant oldest) {}

    /**
     * Every tenant that holds at least one fiscal document, with how many and how old the oldest is. A document
     * is dated by its issue, else by when it was opened: a document that never issued is still evidence of an
     * obligation, and still held.
     */
    public List<Holding> holdings() {
        return jdbc.sql("""
                SELECT tenant_id, count(*) AS documents, min(COALESCE(issued_at, created_at)) AS oldest
                  FROM fiscal.fiscal_documents
                 GROUP BY tenant_id
                 ORDER BY tenant_id
                """)
                .query((row, number) -> new Holding(
                        row.getObject("tenant_id", UUID.class),
                        row.getLong("documents"),
                        row.getObject("oldest", OffsetDateTime.class) == null
                                ? null
                                : row.getObject("oldest", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** How many of a tenant's documents are dated before the cutoff. */
    public long datedBefore(UUID tenantId, Instant cutoff) {
        return jdbc.sql("""
                        SELECT count(*) FROM fiscal.fiscal_documents
                         WHERE tenant_id = :tenantId AND COALESCE(issued_at, created_at) < :cutoff
                        """)
                .param("tenantId", tenantId)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }
}
