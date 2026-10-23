package uz.horecaos.platform.customers.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The erasure statements for the customers-owned tables that {@code JdbcCustomerStore} does not already carry
 * (ADR 0029): a blacklist entry's reasons, a customer's favourites, and the proof an executed request keeps.
 *
 * <p>Separate from the 1,600-line account store because none of it is read on an ordinary request path: every
 * method here runs once, inside {@code CustomerErasureService.execute}'s transaction.
 */
@Repository
public class JdbcCustomerErasureStore {

    private final JdbcClient jdbc;

    public JdbcCustomerErasureStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A blacklist entry whose words still need overwriting, and whether it has a lift reason to overwrite too. */
    public record BlacklistReasonRef(UUID id, boolean hasLiftReason) {}

    /** Every blacklist entry of the account, lifted or not: the reasons are the free text a person typed. */
    public List<BlacklistReasonRef> blacklistEntriesOf(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, lift_reason_encrypted IS NOT NULL AS has_lift_reason
                  FROM customer.blacklist_entries
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query((row, number) ->
                        new BlacklistReasonRef(row.getObject("id", UUID.class), row.getBoolean("has_lift_reason")))
                .list();
    }

    /**
     * Overwrites an entry's reasons. The row, its status, its dates and who acted stay: that an account was barred,
     * when and by whom, is the tenant's record; what the operator wrote about the incident is the person's.
     */
    public int eraseBlacklistReasons(
            UUID tenantId, UUID entryId, String reasonTombstone, String liftReasonTombstoneOrNull) {
        return jdbc.sql("""
                UPDATE customer.blacklist_entries
                   SET reason_encrypted = :reason,
                       lift_reason_encrypted = CASE WHEN lift_reason_encrypted IS NULL THEN NULL ELSE :liftReason END
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("reason", reasonTombstone)
                .param("liftReason", liftReasonTombstoneOrNull)
                .param("tenantId", tenantId)
                .param("id", entryId)
                .update();
    }

    /** Tombstones every blacklist entry of the account, asking {@code tombstone} for each ciphertext. */
    public int eraseBlacklistReasonsOf(
            UUID tenantId,
            UUID accountId,
            Function<UUID, String> reasonTombstone,
            Function<UUID, String> liftTombstone) {
        int erased = 0;
        for (BlacklistReasonRef entry : blacklistEntriesOf(tenantId, accountId)) {
            erased += eraseBlacklistReasons(
                    tenantId, entry.id(), reasonTombstone.apply(entry.id()), liftTombstone.apply(entry.id()));
        }
        return erased;
    }

    /**
     * Deletes the corrections made to the account. Each holds the value that was replaced, which is the old personal
     * data, and the account is going: "preserving prior values under retention" (ADR 0029) ends with the person.
     */
    public int deleteCorrections(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                DELETE FROM customer.profile_corrections
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .update();
    }

    /** What a customer favourited says what they like; none of it is kept for a person who has gone. */
    public int deleteFavourites(UUID tenantId, UUID accountId) {
        return jdbc.sql("DELETE FROM customer.favourites WHERE tenant_id = :tenantId AND account_id = :accountId")
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .update();
    }

    /** Stores the proof document on the request that produced it. */
    public int recordProof(UUID tenantId, UUID requestId, String proofJson) {
        return jdbc.sql("""
                UPDATE customer.erasure_requests
                   SET proof = :proof::jsonb
                 WHERE tenant_id = :tenantId AND id = :requestId
                """)
                .param("proof", proofJson)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .update();
    }

    /** The stored proof, if the request completed after proofs were kept. */
    public java.util.Optional<String> proofOf(UUID tenantId, UUID requestId) {
        return jdbc.sql("""
                SELECT proof::text FROM customer.erasure_requests
                 WHERE tenant_id = :tenantId AND id = :requestId AND proof IS NOT NULL
                """)
                .param("tenantId", tenantId)
                .param("requestId", requestId)
                .query(String.class)
                .optional();
    }
}
