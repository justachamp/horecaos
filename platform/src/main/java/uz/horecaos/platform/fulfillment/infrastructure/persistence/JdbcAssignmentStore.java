package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.instant;
import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.utc;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.domain.sourcing.AttemptStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.ShipmentStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourceType;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;

/**
 * {@code fulfillment.assignment_attempts} and {@code fulfillment.shipments}
 * (ADR 0014, V0054).
 *
 * <p>One class for two tables because they are written together and nowhere else:
 * an attempt becomes ACCEPTED only by producing the shipment
 * {@code ck_attempt_accepted_has_shipment} requires, and a shipment exists only
 * because an attempt won. Splitting them would put the two halves of one
 * compare-and-set in two files.
 *
 * <p><b>Where the single-winner rule lives.</b> In V0054, and only there:
 * {@code ux_shipment_one_active_per_plan}, {@code ux_attempt_one_accepted} and
 * {@code ux_attempt_one_offered}. Every method below writes the statement it means
 * and reports whether the database let it through. None of them counts first —
 * ADR 0014 rejects that by name, because between the count and the insert is
 * exactly the window in which the second dispatcher books the second courier.
 */
@Repository
public class JdbcAssignmentStore {

    private final JdbcClient jdbc;

    public JdbcAssignmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ writes

    /**
     * The attempt row that must exist before anybody outside is asked.
     *
     * <p>Idempotent on {@code (tenant_id, idempotency_key)} — the key the partner
     * itself sees — so a tick replayed after a crash finds the row it wrote rather
     * than sending an equivalent command under a fresh id. That is the whole
     * mechanism by which at-least-once scheduling stops being at-least-once
     * booking.
     *
     * <p>{@code ux_attempt_one_offered} is a second index on this insert and is
     * not part of the conflict target, so a worker whose lease expired mid-tick
     * hits it rather than opening a second live attempt. Caught and turned into a
     * read of the attempt that won, because losing that race is an ordinary
     * outcome and not a fault.
     */
    public Opened open(NewAttempt attempt) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("tenantId", attempt.tenantId());
        params.put("planId", attempt.planId());
        params.put("sourceType", attempt.sourceType().name());
        params.put("status", attempt.status().name());
        params.put("courierId", attempt.courierId());
        params.put("bindingId", attempt.bindingId());
        params.put("quoteId", attempt.quoteId());
        params.put("idempotencyKey", attempt.idempotencyKey());
        params.put("reason", attempt.decisionReason());
        params.put("policyId", attempt.policyId());
        params.put("policyVersion", attempt.policyVersion());
        params.put("requestedAt", utc(attempt.now()));
        params.put("expiresAt", utc(attempt.expiresAt()));

        Optional<Opened> inserted;
        try {
            inserted = jdbc.sql("""
                    INSERT INTO fulfillment.assignment_attempts (
                        id, tenant_id, delivery_plan_id, sequence_number, source_type,
                        courier_id, provider_binding_id, quote_id, status, idempotency_key,
                        decision_reason, policy_id, policy_version, requested_at, expires_at)
                    SELECT :id, :tenantId, :planId,
                           coalesce(max(existing.sequence_number), 0) + 1,
                           :sourceType, :courierId, :bindingId, :quoteId, :status,
                           :idempotencyKey, :reason, :policyId, :policyVersion,
                           :requestedAt, :expiresAt
                    FROM fulfillment.assignment_attempts AS existing
                    WHERE existing.tenant_id = :tenantId AND existing.delivery_plan_id = :planId
                    ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                    RETURNING id, sequence_number, status
                    """)
                    .params(params)
                    .query((row, number) -> new Opened(
                            row.getObject("id", UUID.class),
                            row.getInt("sequence_number"),
                            AttemptStatus.valueOf(row.getString("status")),
                            true))
                    .optional();
        } catch (DuplicateKeyException conflict) {
            // ux_attempt_one_offered: somebody else already has a live attempt for
            // this plan. Their row is the one that matters, and asking anybody
            // else anything now is what puts two couriers at one door.
            inserted = Optional.empty();
        }

        return inserted.orElseGet(() -> replayed(attempt));
    }

    /**
     * The attempt a replay or a lost race should carry on with.
     *
     * <p>The idempotency key first, because that is the same attempt by
     * definition; the plan's live attempt only when no row bears the key, which is
     * the {@code ux_attempt_one_offered} case.
     */
    private Opened replayed(NewAttempt attempt) {
        return jdbc.sql("""
                SELECT id, sequence_number, status
                FROM fulfillment.assignment_attempts
                WHERE tenant_id = :tenantId
                  AND (idempotency_key = :idempotencyKey
                       OR (delivery_plan_id = :planId AND status IN ('REQUESTED', 'OFFERED')))
                ORDER BY (idempotency_key = :idempotencyKey) DESC, sequence_number DESC
                LIMIT 1
                """)
                .param("tenantId", attempt.tenantId())
                .param("idempotencyKey", attempt.idempotencyKey())
                .param("planId", attempt.planId())
                .query((row, number) -> new Opened(
                        row.getObject("id", UUID.class),
                        row.getInt("sequence_number"),
                        AttemptStatus.valueOf(row.getString("status")),
                        false))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Attempt " + attempt.idempotencyKey()
                        + " neither inserted nor found; the plan is unsafe to source"));
    }

    /**
     * The winning move: a shipment exists and this attempt is the reason.
     *
     * <p>Both statements in one transaction, and neither of them asks first. The
     * shipment insert loses to {@code ux_shipment_one_active_per_plan} if another
     * source already won; the attempt update loses to its own {@code WHERE} if the
     * attempt has moved on. A false answer from either means somebody else is
     * carrying this order, which the caller must render as "already assigned"
     * rather than as a failure.
     *
     * <p>The shipment's tenant, brand, location and order are read from the plan
     * row inside the statement rather than passed in. An entity id alone is never
     * proof of ownership, and a shipment stamped with a tenant the caller supplied
     * is a cross-tenant row waiting for a wrong argument.
     */
    @Transactional
    public Optional<UUID> win(WinningAttempt winner) {
        Map<String, Object> params = new HashMap<>();
        UUID shipmentId = UUID.randomUUID();
        params.put("shipmentId", shipmentId);
        params.put("tenantId", winner.tenantId());
        params.put("attemptId", winner.attemptId());
        params.put("sourceType", winner.sourceType().name());
        params.put("providerType", winner.providerType());
        params.put("externalReference", winner.externalReference());
        params.put("now", utc(winner.now()));
        params.put("fromStatus", winner.fromStatus().name());

        Optional<UUID> created = jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id,
                    status, source_type, courier_id, provider_binding_id, provider_type,
                    external_shipment_id, assigned_at)
                SELECT :shipmentId, plan.tenant_id, plan.brand_id, plan.location_id,
                       plan.order_id, plan.id, 'ASSIGNED', :sourceType,
                       attempt.courier_id, attempt.provider_binding_id, :providerType,
                       :externalReference, :now
                FROM fulfillment.assignment_attempts AS attempt
                JOIN fulfillment.delivery_plans AS plan
                  ON plan.id = attempt.delivery_plan_id AND plan.tenant_id = attempt.tenant_id
                WHERE attempt.tenant_id = :tenantId
                  AND attempt.id = :attemptId
                  AND attempt.status = :fromStatus
                ON CONFLICT (tenant_id, delivery_plan_id) WHERE status <> 'CANCELLED' DO NOTHING
                RETURNING id
                """)
                .params(params)
                .query((row, number) -> row.getObject("id", UUID.class))
                .optional();

        if (created.isEmpty()) {
            return Optional.empty();
        }

        int accepted = jdbc.sql("""
                UPDATE fulfillment.assignment_attempts
                SET status = 'ACCEPTED', accepted_at = :now, shipment_id = :shipmentId,
                    external_assignment_id = coalesce(:externalReference, external_assignment_id),
                    version = version + 1
                WHERE tenant_id = :tenantId AND id = :attemptId AND status = :fromStatus
                """).params(params).update();

        if (accepted != 1) {
            // The shipment was created from this attempt one statement ago, so the
            // attempt cannot have moved without the row being rewritten underneath
            // a held lease. Fail loudly rather than leave a shipment nothing
            // explains: the transaction rolls both back.
            throw new IllegalStateException("Attempt " + winner.attemptId() + " changed under its own winning update");
        }
        return created;
    }

    /** An offer taken. The whole compare-and-set is the statement in {@link #win}. */
    @Transactional
    public Optional<UUID> acceptOffer(UUID tenantId, UUID attemptId, UUID courierId, Instant now) {
        boolean holdsOffer = jdbc.sql("""
                SELECT 1 FROM fulfillment.assignment_attempts
                WHERE tenant_id = :tenantId AND id = :attemptId AND courier_id = :courierId
                  AND status = 'OFFERED' AND expires_at > :now
                """)
                .param("tenantId", tenantId)
                .param("attemptId", attemptId)
                .param("courierId", courierId)
                .param("now", utc(now))
                .query(Integer.class)
                .optional()
                .isPresent();

        // Not the invariant — the invariant is the unique index inside win(). This
        // is ownership and liveness: whose offer it is, and whether it has lapsed.
        // A courier who is not the one offered gets the same answer as one who was
        // a second too late, which is the answer the screen shows either way.
        if (!holdsOffer) {
            return Optional.empty();
        }
        Optional<UUID> shipment = win(
                new WinningAttempt(tenantId, attemptId, SourceType.INTERNAL, AttemptStatus.OFFERED, null, null, now));
        if (shipment.isEmpty()) {
            // Somebody else is already carrying this order. The offer is closed
            // rather than left OFFERED, because an offer that goes on holding
            // ux_attempt_one_offered against a plan that is already assigned is a
            // row nothing will ever clear.
            close(tenantId, attemptId, AttemptStatus.CANCELLED, null, null, false, now);
        }
        return shipment;
    }

    /** The attempt ended without a shipment. */
    public boolean close(
            UUID tenantId,
            UUID attemptId,
            AttemptStatus to,
            String failureCode,
            String externalReference,
            boolean uncertain,
            Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("attemptId", attemptId);
        params.put("to", to.name());
        params.put("failureCode", failureCode);
        params.put("externalReference", externalReference);
        params.put("uncertain", uncertain);
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE fulfillment.assignment_attempts
                SET status = :to,
                    failure_code = :failureCode,
                    failed_at = CASE WHEN CAST(:failureCode AS varchar) IS NULL
                                     THEN NULL ELSE :now END,
                    declined_at = CASE WHEN CAST(:to AS varchar) = 'DECLINED'
                                       THEN :now ELSE declined_at END,
                    cancelled_at = CASE WHEN CAST(:to AS varchar) = 'CANCELLED'
                                        THEN :now ELSE cancelled_at END,
                    uncertain_outcome = :uncertain,
                    external_assignment_id =
                        coalesce(:externalReference, external_assignment_id),
                    version = version + 1
                WHERE tenant_id = :tenantId AND id = :attemptId
                  AND status IN ('REQUESTED', 'OFFERED')
                """).params(params).update() == 1;
    }

    /**
     * Offers nobody answered.
     *
     * <p>Run before a plan is decided again, because an offer left OFFERED past
     * its expiry still holds {@code ux_attempt_one_offered} and the next courier
     * cannot be asked. A courier who is never told an offer lapsed holds an order
     * nobody else can be given.
     */
    public int expireLapsedOffers(UUID tenantId, UUID planId, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.assignment_attempts
                SET status = 'EXPIRED', version = version + 1
                WHERE tenant_id = :tenantId AND delivery_plan_id = :planId
                  AND status = 'OFFERED' AND expires_at <= :now
                """)
                .param("tenantId", tenantId)
                .param("planId", planId)
                .param("now", utc(now))
                .update();
    }

    // ------------------------------------------------------------------- reads

    /**
     * What this plan has already tried, from the attempts rather than a checkpoint.
     *
     * <p>A partner attempt still {@code REQUESTED} is deliberately <em>not</em>
     * counted as attempted. That is the transport-fault case, where the partner
     * refused nothing and the same command — under the same idempotency key — is
     * the right thing to send again; recording it as attempted would step past a
     * partner that never actually answered.
     */
    public SourcingProgress progress(UUID tenantId, UUID planId, Instant startedAt) {
        Set<UUID> offeredCouriers = new LinkedHashSet<>();
        Set<UUID> attemptedPartners = new LinkedHashSet<>();
        UUID outstandingOffer = null;
        Instant offerExpiresAt = null;
        boolean uncertain = false;

        var rows = jdbc.sql("""
                SELECT source_type, status, courier_id, provider_binding_id, expires_at,
                       uncertain_outcome
                FROM fulfillment.assignment_attempts
                WHERE tenant_id = :tenantId AND delivery_plan_id = :planId
                ORDER BY sequence_number
                """)
                .param("tenantId", tenantId)
                .param("planId", planId)
                .query((row, number) -> new AttemptRow(
                        SourceType.valueOf(row.getString("source_type")),
                        AttemptStatus.valueOf(row.getString("status")),
                        row.getObject("courier_id", UUID.class),
                        row.getObject("provider_binding_id", UUID.class),
                        instant(row, "expires_at"),
                        row.getBoolean("uncertain_outcome")))
                .list();

        for (AttemptRow row : rows) {
            uncertain |= row.uncertain();
            if (row.sourceType() == SourceType.INTERNAL) {
                offeredCouriers.add(row.courierId());
                if (row.status() == AttemptStatus.OFFERED) {
                    outstandingOffer = row.courierId();
                    offerExpiresAt = row.expiresAt();
                }
            } else if (row.status() != AttemptStatus.REQUESTED) {
                attemptedPartners.add(row.bindingId());
            }
        }

        return new SourcingProgress(
                startedAt, offeredCouriers, outstandingOffer, offerExpiresAt, attemptedPartners, uncertain);
    }

    public Optional<Shipment> findShipment(UUID tenantId, UUID planId) {
        return jdbc.sql("""
                SELECT id, order_id, status, source_type, courier_id, provider_binding_id,
                       provider_type, external_shipment_id
                FROM fulfillment.shipments
                WHERE tenant_id = :tenantId AND delivery_plan_id = :planId AND status <> 'CANCELLED'
                """)
                .param("tenantId", tenantId)
                .param("planId", planId)
                .query((row, number) -> new Shipment(
                        row.getObject("id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        ShipmentStatus.valueOf(row.getString("status")),
                        SourceType.valueOf(row.getString("source_type")),
                        row.getObject("courier_id", UUID.class),
                        row.getObject("provider_binding_id", UUID.class),
                        row.getString("provider_type"),
                        row.getString("external_shipment_id")))
                .optional();
    }

    // --------------------------------------------------------------- row types

    /**
     * @param expiresAt required for an internal offer and null for a partner
     *                  attempt, which is {@code ck_attempt_offer_expires} stated
     *                  in Java's type system as far as it goes
     */
    public record NewAttempt(
            UUID tenantId,
            UUID planId,
            SourceType sourceType,
            AttemptStatus status,
            UUID courierId,
            UUID bindingId,
            UUID quoteId,
            String idempotencyKey,
            String decisionReason,
            UUID policyId,
            Integer policyVersion,
            Instant expiresAt,
            Instant now) {}

    /**
     * @param fromStatus the status the attempt must still be in. REQUESTED for a
     *                   partner booking, OFFERED for a courier taking an offer
     */
    public record WinningAttempt(
            UUID tenantId,
            UUID attemptId,
            SourceType sourceType,
            AttemptStatus fromStatus,
            String providerType,
            String externalReference,
            Instant now) {}

    public record Opened(UUID attemptId, int sequenceNumber, AttemptStatus status, boolean fresh) {}

    public record Shipment(
            UUID id,
            UUID orderId,
            ShipmentStatus status,
            SourceType sourceType,
            UUID courierId,
            UUID providerBindingId,
            String providerType,
            String externalShipmentId) {}

    private record AttemptRow(
            SourceType sourceType,
            AttemptStatus status,
            UUID courierId,
            UUID bindingId,
            Instant expiresAt,
            boolean uncertain) {}
}
