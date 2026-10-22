package uz.horecaos.platform.courier.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.courier.domain.GateMode;
import uz.horecaos.platform.courier.domain.GateRefusal;
import uz.horecaos.platform.courier.domain.StepCheck;

/**
 * What each check of a pickup and a handover came to (ADR 0042, ADR 0178, V0656).
 *
 * <p>Written in the transaction that moves the shipment, so a step that happened cannot be missing
 * its record and a step that was refused cannot have one. A replay of the same step writes nothing
 * new: the first answer stands.
 */
@Repository
public class JdbcCourierStepCheckStore {

    /** The two steps whose checks are kept. */
    public enum Step {
        PICKED_UP,
        DELIVERED
    }

    private final JdbcClient jdbc;

    public JdbcCourierStepCheckStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(
            UUID tenantId,
            UUID shipmentId,
            UUID courierId,
            Step step,
            List<StepCheck> checks,
            GateMode mode,
            UUID policyId,
            int policyVersion,
            Instant at) {
        for (StepCheck check : checks) {
            jdbc.sql("""
                    INSERT INTO fulfillment.courier_step_checks (
                        id, tenant_id, shipment_id, courier_id, step, kind, outcome, reason_code,
                        gate_mode, policy_id, policy_version, recorded_at)
                    VALUES (:id, :tenantId, :shipmentId, :courierId, :step, :kind, :outcome, :reason,
                        :mode, :policyId, :policyVersion, :at)
                    ON CONFLICT (tenant_id, shipment_id, step, kind) DO NOTHING
                    """)
                    .param("id", Ids.newId())
                    .param("tenantId", tenantId)
                    .param("shipmentId", shipmentId)
                    .param("courierId", courierId)
                    .param("step", step.name())
                    .param("kind", check.kind().name())
                    .param("outcome", check.outcome().name())
                    .param(
                            "reason",
                            check.reason() == null ? null : check.reason().name())
                    .param("mode", mode.name())
                    .param("policyId", policyId)
                    .param("policyVersion", policyVersion)
                    .param("at", JdbcCourierStore.utc(at))
                    .update();
        }
    }

    /** The checks the step recorded, in a stable order; empty for a step that was not taken in the courier app. */
    public List<StepCheck> checksOf(UUID tenantId, UUID shipmentId, Step step) {
        return jdbc.sql("""
                        SELECT kind, outcome, reason_code
                          FROM fulfillment.courier_step_checks
                         WHERE tenant_id = :tenantId AND shipment_id = :shipmentId AND step = :step
                         ORDER BY kind
                        """)
                .param("tenantId", tenantId)
                .param("shipmentId", shipmentId)
                .param("step", step.name())
                .query((rs, row) -> {
                    String reason = rs.getString("reason_code");
                    return new StepCheck(
                            StepCheck.Kind.valueOf(rs.getString("kind")),
                            StepCheck.Outcome.valueOf(rs.getString("outcome")),
                            reason == null ? null : GateRefusal.valueOf(reason));
                })
                .list();
    }

    /**
     * Whether the courier app measured this delivery at both ends and the courier was where they
     * needed to be: the pickup and the handover each recorded a passed position check. Anything
     * less -- a step that was not measured, a check that failed, an order an operator closed with
     * no courier-app trail -- is unverified, which is what the accrual's {@code geo_unverified}
     * flag and the GEO_UNVERIFIED_RATE basis count.
     */
    public boolean geoVerified(UUID tenantId, UUID shipmentId) {
        Integer passed = jdbc.sql("""
                        SELECT count(*)
                          FROM fulfillment.courier_step_checks
                         WHERE tenant_id = :tenantId AND shipment_id = :shipmentId
                           AND kind = 'GEO' AND outcome = 'PASSED'
                           AND step IN ('PICKED_UP', 'DELIVERED')
                        """)
                .param("tenantId", tenantId)
                .param("shipmentId", shipmentId)
                .query(Integer.class)
                .single();
        return passed != null && passed == 2;
    }
}
