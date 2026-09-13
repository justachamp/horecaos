package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Whether a manual assignment may name this courier (ADR 0042), read straight
 * off {@code fulfillment.courier_engagements} rather than through the courier
 * module: the table lives in this module's own schema (the same reasoning
 * {@code InternalFleetPort.ActiveAssignments}'s own doc gives for why counting
 * a courier's carried orders belongs to fulfillment and not to courier).
 *
 * <p>Deliberately narrower than {@code CourierDispatchGate}, which also
 * enforces a live shift and a distance band — questions a manual, dispatcher-
 * initiated assignment does not ask. This answers only the one question
 * {@link uz.horecaos.platform.fulfillment.application.ManualDispatchService}
 * needs before it opens an attempt: is this courier's engagement active, and
 * has their compliance document lapsed.
 */
@Component
public class JdbcCourierEligibilityStore {

    private final JdbcClient jdbc;

    public JdbcCourierEligibilityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The courier's one live engagement (status not {@code ENDED}), if any —
     * {@code ux_engagement_one_live} guarantees at most one. Empty means no
     * engagement was ever opened for this courier at all, which is refused the
     * same as an inactive one: a manual assignment names an in-house courier,
     * and one with no engagement record is not that.
     */
    public Optional<Eligibility> findLiveEngagement(UUID tenantId, UUID courierId) {
        return jdbc.sql("""
                SELECT status, warning_state
                FROM fulfillment.courier_engagements
                WHERE tenant_id = :tenantId AND courier_id = :courierId AND status <> 'ENDED'
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query((rs, n) -> new Eligibility(rs.getString("status"), rs.getString("warning_state")))
                .optional();
    }

    /** @param status {@code PENDING_VERIFICATION} | {@code ACTIVE} | {@code SUSPENDED_COMPLIANCE} | {@code SUSPENDED_OPERATIONAL} */
    public record Eligibility(String status, String warningState) {

        /** ADR 0042: an active engagement whose compliance document has not lapsed. */
        public boolean dispatchable() {
            return "ACTIVE".equals(status) && !"LAPSED".equals(warningState);
        }
    }
}
