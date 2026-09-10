package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Which plan every tenant is on (ADR 0090): the directory's plan column, read
 * in one query rather than a subscription call per tenant.
 */
@RestController
@Tag(name = "SaaS control plane", description = "Tenant, brand, and single-brand location onboarding")
public class TenantPlansController {

    private final JdbcClient jdbc;

    public TenantPlansController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/control-plane/tenant-plans")
    @RequiresCapability(value = Capability.COMMERCIAL_PLAN_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every tenant's live subscription, by plan",
            description = "A tenant with no live subscription is not listed.")
    List<TenantPlan> plans() {
        return jdbc.sql("""
                        SELECT s.tenant_id, p.code AS plan_code, v.version_number, s.status
                          FROM commercial.subscriptions s
                          JOIN commercial.plan_versions v ON v.id = s.plan_version_id
                          JOIN commercial.plans p ON p.id = v.plan_id
                         WHERE s.status NOT IN ('TERMINATED', 'EXPIRED')
                        """)
                .query((row, number) -> new TenantPlan(
                        row.getObject("tenant_id", UUID.class),
                        row.getString("plan_code"),
                        row.getInt("version_number"),
                        row.getString("status")))
                .list();
    }

    /** One tenant's plan and where its subscription is in the lifecycle. */
    public record TenantPlan(UUID tenantId, String planCode, int planVersionNumber, String status) {}
}
