package uz.horecaos.platform.customers.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code customer.legal_holds} (V0682): the statements, and nothing about who may run them. */
@Repository
public class JdbcLegalHoldStore {

    private static final String COLUMNS = """
            id, tenant_id, customer_account_id, status, reason_code,
            placed_by_actor_type, placed_by_actor_id, placed_at, place_approval_id,
            lifted_by_actor_type, lifted_by_actor_id, lifted_at, lift_approval_id, version
            """;

    private final JdbcClient jdbc;

    public JdbcLegalHoldStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an ACTIVE hold unless the account already has one.
     *
     * @return whether this call placed it: false is the unique index saying a hold already stands
     */
    public boolean insertIfNoneActive(
            UUID id,
            UUID tenantId,
            UUID accountId,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable UUID approvalId,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO customer.legal_holds (
                    id, tenant_id, customer_account_id, status, reason_code,
                    placed_by_actor_type, placed_by_actor_id, placed_at, place_approval_id)
                VALUES (:id, :tenantId, :accountId, 'ACTIVE', :reason, :actorType, :actorId, :now, :approvalId)
                ON CONFLICT (tenant_id, customer_account_id) WHERE status = 'ACTIVE' DO NOTHING
                """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("accountId", accountId)
                        .param("reason", reasonCode)
                        .param("actorType", actorType)
                        .param("actorId", actorId)
                        .param("approvalId", approvalId)
                        .param("now", at(now))
                        .update()
                == 1;
    }

    /**
     * Makes placing a hold on an account and erasing that account run one after the other, for the length of the
     * surrounding transaction ({@code pg_advisory_xact_lock}: released at commit or rollback).
     *
     * <p>An erasure looks for a hold with a plain read and then anonymises; a placement inserts a row. Neither blocks
     * the other: the hold's foreign key takes {@code FOR KEY SHARE} on the account, which the erasure's non-key
     * {@code UPDATE} does not conflict with, and under READ COMMITTED the erasure cannot see an insert that has not
     * committed. A hold signed while an erasure was already past its check therefore committed first and the account
     * was anonymised under it, which is the loss of evidence a hold exists to prevent. Under this lock the second to
     * arrive waits for the first to commit and reads what it left: an erasure finds the hold and refuses, a hold finds
     * an erased account.
     *
     * <p>Taken first by both callers, before any row of the account is read or written, so the order is the same for
     * everyone and the pair cannot wait on each other. Taken per account, so it serialises nothing else.
     */
    public void lockHoldDecision(UUID tenantId, UUID accountId) {
        String lockKey = "customer.hold-vs-erasure|%s|%s".formatted(tenantId, accountId);
        // The row mapper never reads the void column; consuming the one row is what waits for the lock.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", lockKey)
                .query((row, number) -> Boolean.TRUE)
                .list();
    }

    public Optional<HoldRow> active(UUID tenantId, UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM customer.legal_holds
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcLegalHoldStore::row)
                .optional();
    }

    public boolean isHeld(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM customer.legal_holds
                                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                                  AND status = 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(Boolean.class)
                .single();
    }

    public Optional<HoldRow> find(UUID tenantId, UUID holdId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM customer.legal_holds WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", holdId)
                .query(JdbcLegalHoldStore::row)
                .optional();
    }

    public List<HoldRow> history(UUID tenantId, UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM customer.legal_holds
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY placed_at DESC, id DESC
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcLegalHoldStore::row)
                .list();
    }

    /** Lifts an ACTIVE hold; zero rows means it was lifted already (or is not this tenant's). */
    public int lift(
            UUID tenantId, UUID holdId, String actorType, String actorId, @Nullable UUID approvalId, Instant now) {
        return jdbc.sql("""
                UPDATE customer.legal_holds
                   SET status = 'LIFTED', lifted_by_actor_type = :actorType, lifted_by_actor_id = :actorId,
                       lifted_at = :now, lift_approval_id = :approvalId, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                """)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("approvalId", approvalId)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("id", holdId)
                .update();
    }

    private static HoldRow row(ResultSet row, int number) throws SQLException {
        return new HoldRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("status"),
                row.getString("reason_code"),
                row.getString("placed_by_actor_type"),
                row.getString("placed_by_actor_id"),
                row.getObject("placed_at", OffsetDateTime.class).toInstant(),
                row.getObject("place_approval_id", UUID.class),
                row.getString("lifted_by_actor_type"),
                row.getString("lifted_by_actor_id"),
                instant(row, "lifted_at"),
                row.getObject("lift_approval_id", UUID.class),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One hold, ACTIVE or LIFTED. */
    public record HoldRow(
            UUID id,
            UUID tenantId,
            UUID customerAccountId,
            String status,
            String reasonCode,
            String placedByActorType,
            String placedByActorId,
            Instant placedAt,
            @Nullable UUID placeApprovalId,
            @Nullable String liftedByActorType,
            @Nullable String liftedByActorId,
            @Nullable Instant liftedAt,
            @Nullable UUID liftApprovalId,
            int version) {}
}
