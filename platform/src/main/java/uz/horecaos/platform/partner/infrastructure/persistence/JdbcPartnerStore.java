package uz.horecaos.platform.partner.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.partner.domain.HandoverChallengeStatus;
import uz.horecaos.platform.partner.domain.HandoverChallengeType;
import uz.horecaos.platform.partner.domain.PartnerClientStatus;
import uz.horecaos.platform.partner.domain.RejectionCode;

/**
 * Every read and write this module owns (ADR 0040).
 *
 * <p>One store rather than five, following {@code JdbcKitchenStore}: the tables
 * here are one aggregate's worth of state — a partner relationship — and
 * splitting them by table would spread a single transaction across five classes
 * without making any of them independently useful.
 *
 * <p>Every query carries the tenant predicate, including the ones whose primary
 * key would already be unique. A partner token is a machine principal that a
 * tenant did not create and cannot see, so "this id belongs to somebody else" is
 * the single most likely thing a partner integration gets wrong, and the tenant
 * predicate is what makes the wrong answer "not found" rather than another
 * tenant's order.
 *
 * <p>Every nullable column is read with {@code getObject}. {@code getInt} and
 * {@code getDouble} answer 0 for SQL NULL, which here would turn "the partner
 * stated no tax" into "the partner stated tax of zero" and an unset staleness
 * threshold into an alert on every binding at once.
 */
@Component
public class JdbcPartnerStore {

    private final JdbcClient jdbc;

    public JdbcPartnerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ venues

    /**
     * Resolves the aggregator's own venue identifier to a HorecaOS branch.
     *
     * <p>The venue lives in ADR 0026's {@code configuration_override}, which is
     * where a binding's provider-specific, non-secret configuration belongs. It
     * is not a column of its own because "the aggregator's id for this branch" is
     * exactly the shape that jsonb exists for and every partner spells it
     * differently.
     *
     * <p>The channel comes from ADR 0036's registry, matched on the installation.
     * That link is the point of the registry: a marketplace order arrives on a
     * registered channel like every other order, so it is priced, filtered and
     * reported through the same machinery, and a tenant that has not registered
     * the channel has not finished configuring the integration.
     */
    public Optional<Venue> findVenue(UUID tenantId, String venueReference, Instant at) {
        return jdbc.sql("""
                SELECT b.id AS binding_id, b.installation_id, b.brand_id, b.location_id,
                       c.id AS channel_id, c.code AS channel_code, t.default_currency
                FROM integration.bindings b
                JOIN integration.installations i
                  ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                JOIN tenant.tenants t ON t.id = b.tenant_id
                LEFT JOIN tenant.sales_channels c
                  ON c.tenant_id = b.tenant_id
                 AND c.provider_installation_id = b.installation_id
                 AND c.status = 'ACTIVE'
                WHERE b.tenant_id = :tenantId
                  AND i.provider_category = 'MARKETPLACE'
                  AND i.status = 'ACTIVE'
                  AND b.status = 'ACTIVE'
                  AND b.effective_from <= :at
                  AND (b.effective_until IS NULL OR b.effective_until > :at)
                  AND b.configuration_override ->> 'venueReference' = :venue
                ORDER BY b.priority
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("venue", venueReference)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((row, number) -> new Venue(
                        row.getObject("binding_id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getObject("channel_id", UUID.class),
                        row.getString("channel_code"),
                        row.getString("default_currency")))
                .optional();
    }

    /**
     * Whether the branch is closed by an explicit override.
     *
     * <p>Only {@code FORCE_CLOSED} is consulted. The schedule half of ADR 0036's
     * serviceability answer — timetables, exceptions, and the grace window — is
     * that decision's resolver and lives in its module; reimplementing it here
     * would produce a second answer to "is this branch open", which is precisely
     * the failure ADR 0036 exists to prevent. Until that resolver is callable,
     * an aggregator order to a branch that is merely outside its opening hours is
     * accepted and lands on the pass, which is the safe direction: a person sees
     * it, and the alternative is a paid customer refused by a timetable HorecaOS
     * never checked against the aggregator's own.
     */
    public boolean isForceClosed(UUID tenantId, UUID locationId, Instant at) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT mode = 'FORCE_CLOSED'
                       AND (effective_until IS NULL OR effective_until > :at)
                FROM tenant.location_service_state
                WHERE tenant_id = :tenantId AND location_id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(Boolean.class)
                .optional()
                .orElse(Boolean.FALSE));
    }

    /**
     * Resolves the partner's own item identifiers to HorecaOS catalogue variants.
     *
     * <p>ADR 0026's {@code provider_entity_mappings} is the right table for this
     * and the wrong one for order identifiers: its unique keys make it a
     * one-to-one map per binding per entity type, which is exactly a menu item
     * and is not an order that carries four different partner references at once.
     *
     * <p>An identifier that is absent from the result is not an error here.
     * The caller stores that line {@code UNMAPPED} with the partner's own name
     * and amount, because refusing the order over a menu-sync lag on one item
     * means a customer who has already paid the aggregator gets nothing while the
     * branch never learns why.
     */
    public Map<String, UUID> resolveMenuItems(UUID tenantId, UUID bindingId, List<String> externalItemIds) {

        if (externalItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> resolved = new HashMap<>();
        jdbc.sql("""
                SELECT external_entity_id, horecaos_entity_id
                FROM integration.provider_entity_mappings
                WHERE tenant_id = :tenantId
                  AND binding_id = :bindingId
                  AND entity_type = 'MENU_ITEM'
                  AND status = 'ACTIVE'
                  AND external_entity_id IN (:ids)
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("ids", externalItemIds)
                .query((row, number) ->
                        Map.entry(row.getString("external_entity_id"), row.getObject("horecaos_entity_id", UUID.class)))
                .list()
                .forEach(entry -> resolved.put(entry.getKey(), entry.getValue()));
        return resolved;
    }

    // ---------------------------------------------------------- inbound staging

    /**
     * Records the push. The unique key on {@code (tenant, binding, external order
     * id)} is the whole duplicate defence: two concurrent pushes of one order
     * race here, the loser's transaction rolls back, and it reads back the
     * winner's order rather than creating a second one. An application-level
     * "does it exist yet" check would let both through under concurrency, and the
     * cost of that is a restaurant cooking the same order twice.
     */
    public void recordAccepted(InboundPush push, UUID orderId) {
        insertPush(push, "ACCEPTED", null, orderId);
    }

    public void recordRejected(InboundPush push, RejectionCode code) {
        insertPush(push, "REJECTED", code.name(), null);
    }

    private void insertPush(
            InboundPush push, String outcome, @Nullable String rejectionCode, @Nullable UUID orderId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("tenantId", push.tenantId());
        row.put("bindingId", push.bindingId());
        row.put("externalOrderId", push.externalOrderId());
        row.put("payload", push.encryptedPayload());
        row.put("hash", push.payloadSha256());
        row.put("outcome", outcome);
        row.put("rejectionCode", rejectionCode);
        row.put("orderId", orderId);
        row.put(
                "pickupAt",
                push.pickupExpectedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(push.pickupExpectedAt(), ZoneOffset.UTC));
        row.put("receivedAt", OffsetDateTime.ofInstant(push.receivedAt(), ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO partner.inbound_orders (
                    id, tenant_id, binding_id, external_order_id, received_at,
                    raw_payload_encrypted, payload_sha256, outcome, rejection_code,
                    order_id, partner_pickup_expected_at)
                VALUES (
                    :id, :tenantId, :bindingId, :externalOrderId, :receivedAt,
                    :payload, :hash, :outcome, :rejectionCode,
                    :orderId, :pickupAt)
                """).params(row).update();
    }

    /**
     * What HorecaOS already decided about this partner order id.
     *
     * <p>The outcome of a {@code (binding, external order id)} pair is decided
     * once and never revisited. A retry of an accepted order reads back its
     * order; a retry of a refused one reads back the same refusal, with the same
     * code, for ever. That is deliberate and it is the harder of the two
     * choices: letting a second push of one identifier be re-evaluated would let
     * a partner restate a total HorecaOS had already refused, and the second
     * statement would win silently. A partner that has genuinely corrected an
     * order issues a new identifier for it, which every aggregator protocol
     * permits and which leaves both versions visible.
     */
    public Optional<StagedOutcome> findStagedOutcome(UUID tenantId, UUID bindingId, String externalOrderId) {

        return jdbc.sql("""
                SELECT outcome, rejection_code, order_id
                FROM partner.inbound_orders
                WHERE tenant_id = :tenantId
                  AND binding_id = :bindingId
                  AND external_order_id = :externalOrderId
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("externalOrderId", externalOrderId)
                .query((row, number) -> new StagedOutcome(
                        row.getString("outcome"),
                        row.getString("rejection_code"),
                        // Null on a rejection, which is why this is getObject:
                        // a zero-value UUID would be an order that does not exist.
                        row.getObject("order_id", UUID.class)))
                .optional();
    }

    // ------------------------------------------------------- external references

    /**
     * Finds orders by whatever a customer or courier read off a screen.
     *
     * <p>Matches the normalised column across the tenant rather than within one
     * binding, and may legitimately return several rows: two aggregators issue
     * the same short numeric code on one day often enough that a per-binding
     * search would fail exactly when an operator needs it. The caller
     * disambiguates by provider and branch.
     */
    public List<ReferenceMatch> searchByReference(UUID tenantId, String normalisedValue, int limit) {
        return jdbc.sql("""
                SELECT r.order_id, r.reference_type, r.reference_value, r.binding_id,
                       o.public_order_number, o.location_id, o.status
                FROM ordering.order_external_references r
                JOIN ordering.orders o ON o.id = r.order_id AND o.tenant_id = r.tenant_id
                WHERE r.tenant_id = :tenantId
                  AND r.reference_value_normalised = :value
                ORDER BY o.created_at DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("value", normalisedValue)
                .param("limit", limit)
                .query((row, number) -> new ReferenceMatch(
                        row.getObject("order_id", UUID.class),
                        row.getString("reference_type"),
                        row.getString("reference_value"),
                        row.getObject("binding_id", UUID.class),
                        row.getString("public_order_number"),
                        row.getObject("location_id", UUID.class),
                        row.getString("status")))
                .list();
    }

    // ------------------------------------------------------- handover challenges

    public Optional<Challenge> findOpenChallenge(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, tenant_id, order_id, binding_id, challenge_type, issued_by,
                       expected_value_hash, attempts, max_attempts, status, version
                FROM ordering.order_handover_challenges
                WHERE tenant_id = :tenantId AND order_id = :orderId AND status = 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new Challenge(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("binding_id", UUID.class),
                        HandoverChallengeType.valueOf(row.getString("challenge_type")),
                        row.getString("issued_by"),
                        row.getString("expected_value_hash"),
                        row.getInt("attempts"),
                        row.getInt("max_attempts"),
                        HandoverChallengeStatus.valueOf(row.getString("status")),
                        row.getInt("version")))
                .optional();
    }

    /**
     * The order's challenge whatever state it reached. Used by the bypass path,
     * which must be able to override a challenge that has already exhausted its
     * attempts — a courier whose app will not show the code is a real situation,
     * and a branch with no way past it invents one.
     */
    public Optional<Challenge> findChallengeForOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, tenant_id, order_id, binding_id, challenge_type, issued_by,
                       expected_value_hash, attempts, max_attempts, status, version
                FROM ordering.order_handover_challenges
                WHERE tenant_id = :tenantId AND order_id = :orderId
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new Challenge(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("binding_id", UUID.class),
                        HandoverChallengeType.valueOf(row.getString("challenge_type")),
                        row.getString("issued_by"),
                        row.getString("expected_value_hash"),
                        row.getInt("attempts"),
                        row.getInt("max_attempts"),
                        HandoverChallengeStatus.valueOf(row.getString("status")),
                        row.getInt("version")))
                .optional();
    }

    /**
     * Burns one attempt.
     *
     * <p>The increment is a conditional UPDATE with the attempt count in its
     * predicate, so two devices trying codes against one challenge in the same
     * second consume two attempts and not one. Reading the row and writing back
     * {@code attempts + 1} would let a slow brute force get free tries by racing
     * itself, which is the only way five attempts is not five attempts.
     *
     * @return the attempts consumed after this one, or empty when the row moved
     *         underneath — settled, or already exhausted
     */
    public Optional<Integer> consumeAttempt(UUID tenantId, UUID challengeId, int expectedAttempts) {
        return jdbc.sql("""
                UPDATE ordering.order_handover_challenges
                SET attempts = attempts + 1,
                    status = CASE WHEN attempts + 1 >= max_attempts THEN 'FAILED' ELSE status END,
                    version = version + 1,
                    updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id
                  AND status = 'PENDING' AND attempts = :expected
                RETURNING attempts
                """)
                .param("tenantId", tenantId)
                .param("id", challengeId)
                .param("expected", expectedAttempts)
                .query(Integer.class)
                .optional();
    }

    /** Settles a challenge. Returns false when somebody else settled it first. */
    public boolean settleChallenge(
            UUID tenantId,
            UUID challengeId,
            HandoverChallengeStatus status,
            String verifiedBy,
            @Nullable String bypassReasonCode,
            Instant at) {

        Map<String, Object> row = new HashMap<>();
        row.put("tenantId", tenantId);
        row.put("id", challengeId);
        row.put("status", status.name());
        row.put("verifiedBy", verifiedBy);
        row.put("reason", bypassReasonCode);
        row.put("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));

        return jdbc.sql("""
                UPDATE ordering.order_handover_challenges
                SET status = :status,
                    verified_at = :at,
                    verified_by = :verifiedBy,
                    bypass_reason_code = :reason,
                    version = version + 1,
                    updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id
                  AND status IN ('PENDING', 'FAILED')
                """).params(row).update() == 1;
    }

    // ------------------------------------------------------------ partner clients

    public Optional<PartnerClient> findClientByClientId(String clientId) {
        return jdbc.sql("""
                SELECT c.id, c.tenant_id, c.installation_id, c.client_id, c.status,
                       c.secret_expires_at
                FROM partner.api_clients c
                JOIN integration.installations i
                  ON i.tenant_id = c.tenant_id AND i.id = c.installation_id
                WHERE c.client_id = :clientId
                  AND i.provider_category = 'MARKETPLACE'
                """)
                .param("clientId", clientId)
                .query((row, number) -> new PartnerClient(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        row.getString("client_id"),
                        PartnerClientStatus.valueOf(row.getString("status")),
                        instant(row.getObject("secret_expires_at", OffsetDateTime.class))))
                .optional();
    }

    /**
     * The bindings a partner credential may act for.
     *
     * <p>Derived from the installation rather than copied onto the credential.
     * A copy would be a second answer to "which branches may this partner see",
     * and the two would diverge the first time a branch is unbound — leaving a
     * revoked venue readable by a token that was scoped when it was issued.
     */
    public List<UUID> bindingsOf(UUID tenantId, UUID installationId, Instant at) {
        return jdbc.sql("""
                SELECT id
                FROM integration.bindings
                WHERE tenant_id = :tenantId
                  AND installation_id = :installationId
                  AND status = 'ACTIVE'
                  AND effective_from <= :at
                  AND (effective_until IS NULL OR effective_until > :at)
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(UUID.class)
                .list();
    }

    public void recordAuthentication(UUID clientId, Instant at) {
        jdbc.sql("""
                UPDATE partner.api_clients
                SET last_authenticated_at = :at, updated_at = now()
                WHERE id = :id
                """)
                .param("id", clientId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    // ---------------------------------------------------------------- watermarks

    /**
     * Records that something arrived or was pushed. Upserted rather than
     * inserted-then-updated because the first order on a new binding must not
     * fail on a row that nobody created at configuration time — a channel whose
     * liveness recording depends on a setup step somebody might skip is a channel
     * that goes stale invisibly, which is the exact failure this table exists for.
     */
    public void recordSuccess(
            UUID tenantId,
            UUID bindingId,
            UUID locationId,
            String direction,
            String reference,
            int staleAfterSeconds,
            Instant at) {

        Map<String, Object> row = new HashMap<>();
        row.put("tenantId", tenantId);
        row.put("bindingId", bindingId);
        row.put("locationId", locationId);
        row.put("direction", direction);
        row.put("reference", reference);
        row.put("staleAfter", staleAfterSeconds);
        row.put("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.provider_activity_watermarks (
                    tenant_id, binding_id, location_id, direction, last_success_at,
                    last_success_reference, stale_after_seconds, alert_state, updated_at)
                VALUES (
                    :tenantId, :bindingId, :locationId, :direction, :at,
                    :reference, :staleAfter, 'HEALTHY', now())
                ON CONFLICT (tenant_id, binding_id, direction) DO UPDATE
                SET last_success_at = EXCLUDED.last_success_at,
                    last_success_reference = EXCLUDED.last_success_reference,
                    location_id = EXCLUDED.location_id,
                    alert_state = 'HEALTHY',
                    alert_raised_at = NULL,
                    version = integration.provider_activity_watermarks.version + 1,
                    updated_at = now()
                """).params(row).update();
    }

    public void recordFailure(
            UUID tenantId,
            UUID bindingId,
            UUID locationId,
            String direction,
            String failureCode,
            int staleAfterSeconds,
            Instant at) {

        Map<String, Object> row = new HashMap<>();
        row.put("tenantId", tenantId);
        row.put("bindingId", bindingId);
        row.put("locationId", locationId);
        row.put("direction", direction);
        row.put("code", failureCode);
        row.put("staleAfter", staleAfterSeconds);
        row.put("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));

        jdbc.sql("""
                INSERT INTO integration.provider_activity_watermarks (
                    tenant_id, binding_id, location_id, direction, last_failure_at,
                    last_failure_code, stale_after_seconds, updated_at)
                VALUES (
                    :tenantId, :bindingId, :locationId, :direction, :at,
                    :code, :staleAfter, now())
                ON CONFLICT (tenant_id, binding_id, direction) DO UPDATE
                SET last_failure_at = EXCLUDED.last_failure_at,
                    last_failure_code = EXCLUDED.last_failure_code,
                    location_id = EXCLUDED.location_id,
                    version = integration.provider_activity_watermarks.version + 1,
                    updated_at = now()
                """).params(row).update();
    }

    /**
     * The liveness matrix — locations by bindings, with the silence measured
     * against each binding's own threshold.
     *
     * <p>A binding that has never received anything reports null seconds rather
     * than a large number. "Nothing has ever arrived here" and "nothing has
     * arrived for three hours" are different problems: the first is a
     * configuration that was never finished, the second is a working integration
     * that stopped, and an operator resolves them in different places.
     */
    public List<LivenessRow> liveness(UUID tenantId, Instant at) {
        return jdbc.sql("""
                SELECT w.binding_id, w.location_id, w.direction, w.last_success_at,
                       w.last_success_reference, w.last_failure_at, w.last_failure_code,
                       w.stale_after_seconds, w.observed_median_interval_seconds,
                       w.alert_state,
                       CASE WHEN w.last_success_at IS NULL THEN NULL
                            ELSE EXTRACT(EPOCH FROM (:at - w.last_success_at))::bigint
                       END AS silence_seconds,
                       i.display_name AS provider_name
                FROM integration.provider_activity_watermarks w
                JOIN integration.bindings b
                  ON b.tenant_id = w.tenant_id AND b.id = w.binding_id
                JOIN integration.installations i
                  ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                WHERE w.tenant_id = :tenantId
                ORDER BY i.display_name, w.location_id, w.direction
                """)
                .param("tenantId", tenantId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query((row, number) -> new LivenessRow(
                        row.getObject("binding_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("direction"),
                        row.getString("provider_name"),
                        instant(row.getObject("last_success_at", OffsetDateTime.class)),
                        row.getString("last_success_reference"),
                        instant(row.getObject("last_failure_at", OffsetDateTime.class)),
                        row.getString("last_failure_code"),
                        row.getInt("stale_after_seconds"),
                        // Nullable, and 0 would be a lie in both directions: an
                        // unobserved median read as "orders every zero seconds".
                        row.getObject("observed_median_interval_seconds", Integer.class),
                        row.getString("alert_state"),
                        row.getObject("silence_seconds", Long.class)))
                .list();
    }

    /** Moves every binding whose silence has crossed its own threshold. */
    public List<UUID> markStale(UUID tenantId, Instant at) {
        return jdbc.sql("""
                UPDATE integration.provider_activity_watermarks
                SET alert_state = 'STALE',
                    alert_raised_at = :at,
                    version = version + 1,
                    updated_at = now()
                WHERE tenant_id = :tenantId
                  AND alert_state = 'HEALTHY'
                  AND last_success_at IS NOT NULL
                  AND last_success_at < :at - make_interval(secs => stale_after_seconds)
                RETURNING binding_id
                """)
                .param("tenantId", tenantId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(UUID.class)
                .list();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    // -------------------------------------------------------------------- rows

    public record Venue(
            UUID bindingId,
            UUID installationId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            String channelCode,
            String tenantDefaultCurrency) {}

    public record InboundPush(
            UUID tenantId,
            UUID bindingId,
            String externalOrderId,
            String encryptedPayload,
            String payloadSha256,
            @Nullable Instant pickupExpectedAt,
            Instant receivedAt) {}

    public record ReferenceMatch(
            UUID orderId,
            String referenceType,
            String referenceValue,
            UUID bindingId,
            String publicOrderNumber,
            UUID locationId,
            String orderStatus) {}

    public record Challenge(
            UUID id,
            UUID tenantId,
            UUID orderId,
            UUID bindingId,
            HandoverChallengeType type,
            String issuedBy,
            String expectedValueHash,
            int attempts,
            int maxAttempts,
            HandoverChallengeStatus status,
            int version) {}

    public record PartnerClient(
            UUID id,
            UUID tenantId,
            UUID installationId,
            String clientId,
            PartnerClientStatus status,
            @Nullable Instant secretExpiresAt) {}

    public record StagedOutcome(String outcome, String rejectionCode, UUID orderId) {

        public boolean accepted() {
            return "ACCEPTED".equals(outcome);
        }
    }

    public record LivenessRow(
            UUID bindingId,
            UUID locationId,
            String direction,
            String providerName,
            @Nullable Instant lastSuccessAt,
            @Nullable String lastSuccessReference,
            @Nullable Instant lastFailureAt,
            @Nullable String lastFailureCode,
            int staleAfterSeconds,
            @Nullable Integer observedMedianIntervalSeconds,
            String alertState,
            @Nullable Long silenceSeconds) {}
}
