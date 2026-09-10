package uz.horecaos.platform.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Each tenant's open problems, counted (ADR 0090): the directory's health
 * column.
 *
 * <p>Health here is a count, not a score. The three things that need a person
 * in a tenant's issue queue are counted the same way that queue lists them:
 * events the platform could not publish, fiscal receipts it could not issue,
 * and orders sent to a POS whose outcome nobody could establish. Zero means
 * nothing is waiting on anyone. A weighted score would need someone to decide
 * what a blocked receipt is worth against a dead letter, and nobody has.
 */
@RestController
@Tag(name = "Platform health", description = "ADR 0084: platform-wide figures across every tenant")
public class TenantHealthController {

    private final JdbcClient jdbc;

    public TenantHealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/control-plane/tenant-health")
    @RequiresCapability(value = Capability.TENANT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Open problems per tenant",
            description = "Events that could not be published, fiscal receipts that could not be "
                    + "issued, and POS orders awaiting a decision, per tenant. Only tenants with at "
                    + "least one are listed.")
    List<TenantHealth> health() {
        Map<UUID, long[]> counts = new HashMap<>();
        tally(counts, 0, """
                SELECT tenant_id, count(*) AS total FROM integration.outbox_events
                 WHERE status = 'DEAD_LETTER' AND tenant_id IS NOT NULL GROUP BY tenant_id
                """);
        tally(counts, 1, """
                SELECT tenant_id, count(*) AS total FROM fiscal.fiscal_documents
                 WHERE status = 'BLOCKED' GROUP BY tenant_id
                """);
        tally(counts, 2, """
                SELECT tenant_id, count(*) AS total FROM integration.pos_order_exports
                 WHERE state IN ('UNCERTAIN', 'AWAITING_OPERATOR') GROUP BY tenant_id
                """);
        return counts.entrySet().stream()
                .map(entry ->
                        new TenantHealth(entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                .sorted((left, right) -> Long.compare(right.openProblems(), left.openProblems()))
                .toList();
    }

    private void tally(Map<UUID, long[]> counts, int column, String sql) {
        jdbc.sql(sql)
                .query((row, number) -> {
                    counts.computeIfAbsent(row.getObject("tenant_id", UUID.class), ignored -> new long[3])[column] =
                            row.getLong("total");
                    return 1;
                })
                .list();
    }

    /** One tenant's open problems, by kind. */
    public record TenantHealth(UUID tenantId, long deadLetters, long blockedReceipts, long posOrdersAwaiting) {

        public long openProblems() {
            return deadLetters + blockedReceipts + posOrdersAwaiting;
        }
    }
}
