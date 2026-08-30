package uz.horecaos.platform.customers.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.customers.application.CustomerSessionStore;

/**
 * {@link CustomerSessionStore} over {@code customer.customer_sessions} (ADR 0051).
 */
@Component
public class JdbcCustomerSessionStore implements CustomerSessionStore {

    private final JdbcClient jdbc;

    public JdbcCustomerSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NewSession session) {
        jdbc.sql("""
                INSERT INTO customer.customer_sessions (
                    id, tenant_id, brand_id, customer_account_id,
                    identity_partition_brand_id, token_hash, issued_at, expires_at)
                VALUES (:id, :tenantId, :brandId, :accountId,
                    :partition, :tokenHash, :issuedAt, :expiresAt)
                """)
                .param("id", session.sessionId())
                .param("tenantId", session.tenantId())
                .param("brandId", session.brandId())
                .param("accountId", session.accountId())
                .param("partition", session.identityPartitionBrandId())
                .param("tokenHash", session.tokenHash())
                .param("issuedAt", utc(session.issuedAt()))
                .param("expiresAt", utc(session.expiresAt()))
                .update();
    }

    /**
     * One probe on the unique digest index, and no clock in the predicate.
     *
     * <p>Liveness is decided by the caller from the two timestamps that come back
     * rather than filtered out here, so that "expired" and "never existed" can be
     * told apart — see the port for why that distinction is the point rather than
     * a leak.
     */
    @Override
    public Optional<StoredSession> find(String tokenHash) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, customer_account_id,
                       identity_partition_brand_id, issued_at, expires_at, revoked_at
                FROM customer.customer_sessions
                WHERE token_hash = :tokenHash
                """)
                .param("tokenHash", tokenHash)
                .query(JdbcCustomerSessionStore::readSession)
                .optional();
    }

    /**
     * Ends a session, once.
     *
     * <p>Guarded on {@code revoked_at IS NULL} so that two taps of sign-out do not
     * rewrite the instant a session ended — the first end is the true one, and an
     * incident timeline that moves is worse than none.
     */
    @Override
    public boolean revoke(String tokenHash, Instant now) {
        return jdbc.sql("""
                UPDATE customer.customer_sessions
                SET revoked_at = :now
                WHERE token_hash = :tokenHash AND revoked_at IS NULL
                """)
                .param("tokenHash", tokenHash)
                .param("now", utc(now))
                .update() == 1;
    }

    @Override
    public int revokeForAccount(UUID tenantId, UUID accountId, Instant now) {
        return jdbc.sql("""
                UPDATE customer.customer_sessions
                SET revoked_at = :now
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND revoked_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("now", utc(now))
                .update();
    }

    /**
     * Removes sessions that are over.
     *
     * <p>A revoked session is deleted on the same cutoff as an expired one rather
     * than immediately: a sign-out that is followed by a support call an hour
     * later should still have a row to look at.
     */
    @Override
    public int purgeEndedBefore(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM customer.customer_sessions
                WHERE id IN (
                    SELECT id FROM customer.customer_sessions
                    WHERE expires_at < :cutoff
                       OR (revoked_at IS NOT NULL AND revoked_at < :cutoff)
                    ORDER BY expires_at
                    LIMIT :limit
                )
                """)
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .update();
    }

    private static StoredSession readSession(ResultSet rs, int rowNum) throws SQLException {
        return new StoredSession(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("customer_account_id", UUID.class),
                rs.getObject("identity_partition_brand_id", UUID.class),
                instant(rs, "issued_at"),
                instant(rs, "expires_at"),
                instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
