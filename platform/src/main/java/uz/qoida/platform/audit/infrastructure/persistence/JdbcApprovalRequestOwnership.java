package uz.qoida.platform.audit.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ApprovalRequestOwnership;

/**
 * The ownership lookup, answered by the one predicate that matters.
 *
 * <p>The query constrains on the tenant the caller was authorised against rather
 * than on the id alone, which is the rule this whole reference exists to restore:
 * {@code tenant_id IS NULL} is the platform's request and {@code tenant_id =
 * :tenantId} is the caller's own, and there is no third branch to fall through.
 *
 * <p>V0088 makes the same rule a foreign key, so this is the error message and
 * not the guarantee. A caller that skipped it — a repair script, a second write
 * path — is refused by {@code fk_cutover_approval_request} instead, as a
 * constraint violation rather than as a sentence anybody can act on.
 */
@Repository
public class JdbcApprovalRequestOwnership implements ApprovalRequestOwnership {

    private final JdbcClient jdbc;

    public JdbcApprovalRequestOwnership(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Owner> resolve(UUID approvalRequestId, UUID tenantId) {
        if (approvalRequestId == null || tenantId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT tenant_id IS NULL AS platform_owned
                  FROM audit.approval_requests
                 WHERE id = :id
                   AND (tenant_id IS NULL OR tenant_id = :tenantId)
                """)
                .param("id", approvalRequestId)
                .param("tenantId", tenantId)
                .query((resultSet, rowNumber) ->
                        resultSet.getBoolean("platform_owned") ? Owner.PLATFORM : Owner.CALLER)
                .optional();
    }
}
