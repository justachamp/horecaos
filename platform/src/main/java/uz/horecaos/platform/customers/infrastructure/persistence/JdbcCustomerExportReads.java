package uz.horecaos.platform.customers.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.customers.spi.ExportTable;

/**
 * The reads a data-subject export makes of the customers module's own tables that no other store already serves (ADR 0029).
 * Every statement is bounded by {@code ExportTable.MAX_ROWS + 1}, newest first, so a table can say it was cut off.
 */
@Repository
public class JdbcCustomerExportReads {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;

    public JdbcCustomerExportReads(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> brandProfiles(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT brand_id, status, loyalty_reference, first_order_at, last_order_at, created_at
                  FROM customer.brand_profiles
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC LIMIT :fetch
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("fetch", FETCH)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> favourites(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT brand_id, product_id, created_at
                  FROM customer.favourites
                 WHERE tenant_id = :tenantId AND account_id = :accountId
                 ORDER BY created_at DESC LIMIT :fetch
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("fetch", FETCH)
                .query()
                .listOfRows();
    }

    /**
     * The leads that are hers: linked to her account, or not linked to anybody and holding one of her numbers (the rule
     * the erasure uses, so what she is given is what would be erased). Encrypted columns come back as they are stored.
     */
    public List<Map<String, Object>> leads(UUID tenantId, UUID accountId, Collection<String> phoneLookupHashes) {
        String byNumber = phoneLookupHashes.isEmpty()
                ? ""
                : " OR (customer_account_id IS NULL AND phone_lookup_hash IN (:hashes))";
        var statement = jdbc.sql("""
                SELECT id, status, source, phone_encrypted, display_name_encrypted, notes_encrypted,
                       closed_reason, created_at
                  FROM customer.leads
                 WHERE tenant_id = :tenantId AND (customer_account_id = :accountId""" + byNumber + """
                ) ORDER BY created_at DESC LIMIT :fetch
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("fetch", FETCH);
        if (!phoneLookupHashes.isEmpty()) {
            statement = statement.param("hashes", phoneLookupHashes);
        }
        return statement.query().listOfRows();
    }

    /** The blacklist's facts: that she was listed, since when, until when, and when it was lifted. Never the reasons. */
    public List<Map<String, Object>> blacklistFacts(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, status, created_at, expires_at, lifted_at
                  FROM customer.blacklist_entries
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC LIMIT :fetch
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("fetch", FETCH)
                .query()
                .listOfRows();
    }

    /** Her corrections with the prior value still protected; the participant reveals it under the export's purpose. */
    public List<Map<String, Object>> corrections(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, subject, target_id, evidence_reference, prior_value_protected, corrected_at
                  FROM customer.profile_corrections
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY corrected_at DESC, id DESC LIMIT :fetch
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("fetch", FETCH)
                .query()
                .listOfRows();
    }

    /** Her earlier requests for her data: when, how and with what outcome; no values. */
    public List<Map<String, Object>> exportRequests(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, status, requested_via, requested_at, response_due_at, produced_at, bundle_expires_at,
                       bundle_removed_at, cancelled_at
                  FROM customer.export_requests
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY requested_at DESC, id DESC LIMIT :fetch
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("fetch", FETCH)
                .query()
                .listOfRows();
    }

    /** Her requests to be forgotten: when, how and where they stand; no reason, because none is taken. */
    public List<Map<String, Object>> erasureRequests(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, status, requested_via, requested_at, completed_at, cancelled_at
                  FROM customer.erasure_requests
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY requested_at DESC, id DESC LIMIT :fetch
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("fetch", FETCH)
                .query()
                .listOfRows();
    }
}
