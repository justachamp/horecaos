package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.courier.domain.EngagementStatus;
import uz.horecaos.platform.courier.domain.RegistrationWarningState;
import uz.horecaos.platform.courier.domain.VerificationMethod;

/**
 * Couriers, their types, and their engagements (ADR 0042).
 *
 * <p>Two rules run through every statement here.
 *
 * <p>The tenant predicate is inside the query, always. A courier id is a UUID
 * that arrives from a phone, and a lookup matching on it alone would put another
 * tenant's engagement — including the dates its compliance turns on — in front
 * of whoever asked.
 *
 * <p>Nothing selects {@code protected_registration_ref} except the one method
 * that exists to reveal it. ADR 0029 makes the identifier unqueryable by
 * construction; keeping it out of the ordinary projections is what stops it
 * being carried around by callers who never needed it.
 */
@Repository
public class JdbcCourierStore {

    private final JdbcClient jdbc;

    public JdbcCourierStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ courier types

    public void insertType(CourierTypeRow type) {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (
                    id, tenant_id, code, display_name, vehicle_class,
                    min_distance_meters, max_distance_meters,
                    max_concurrent_assignments, offer_ttl_seconds, status,
                    version, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :displayName, :vehicleClass,
                    :minDistance, :maxDistance, :maxConcurrent, :offerTtl, 'ACTIVE',
                    1, :now, :now)
                """)
                .params(typeParams(type))
                .update();
    }

    private static Map<String, Object> typeParams(CourierTypeRow type) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", type.id());
        params.put("tenantId", type.tenantId());
        params.put("code", type.code());
        params.put("displayName", type.displayName());
        params.put("vehicleClass", type.vehicleClass());
        params.put("minDistance", type.minDistanceMeters());
        params.put("maxDistance", type.maxDistanceMeters());
        params.put("maxConcurrent", type.maxConcurrentAssignments());
        params.put("offerTtl", type.offerTtlSeconds());
        params.put("now", utc(Instant.now()));
        return params;
    }

    public Optional<CourierTypeRow> findType(UUID tenantId, UUID typeId) {
        return jdbc.sql("""
                SELECT id, tenant_id, code, display_name, vehicle_class,
                       min_distance_meters, max_distance_meters,
                       max_concurrent_assignments, offer_ttl_seconds, status
                  FROM fulfillment.courier_types
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", typeId)
                .query(JdbcCourierStore::mapType)
                .optional();
    }

    // ----------------------------------------------------------------- couriers

    public void insertCourier(CourierRow courier) {
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (
                    id, tenant_id, courier_type_id, principal_subject, display_reference,
                    protected_full_name, status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :typeId, :subject, :reference,
                    :protectedName, 'ACTIVE', 1, :now, :now)
                """)
                .param("id", courier.id()).param("tenantId", courier.tenantId())
                .param("typeId", courier.courierTypeId())
                .param("subject", courier.principalSubject())
                .param("reference", courier.displayReference())
                .param("protectedName", courier.protectedFullName())
                .param("now", utc(Instant.now()))
                .update();
    }

    public Optional<CourierRow> findCourier(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_COURIER + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", courierId)
                .query(JdbcCourierStore::mapCourier)
                .optional();
    }

    /**
     * The lookup behind "a courier reads their own ledger and nobody else's".
     * The subject comes from the token rather than from the path, so a courier
     * cannot ask about a courier id that is not theirs.
     */
    public Optional<CourierRow> findCourierBySubject(UUID tenantId, String principalSubject) {
        return jdbc.sql(SELECT_COURIER
                + " WHERE tenant_id = :tenantId AND principal_subject = :subject")
                .param("tenantId", tenantId).param("subject", principalSubject)
                .query(JdbcCourierStore::mapCourier)
                .optional();
    }

    // -------------------------------------------------------------- engagements

    public void insertEngagement(EngagementRow engagement) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", engagement.id());
        params.put("tenantId", engagement.tenantId());
        params.put("courierId", engagement.courierId());
        params.put("status", engagement.status().name());
        params.put("engagedFrom", engagement.engagedFrom());
        params.put("engagedUntil", engagement.engagedUntil());
        params.put("registrationRef", engagement.protectedRegistrationRef());
        params.put("validUntil", engagement.registrationValidUntil());
        params.put("verifiedAt", utc(engagement.registrationVerifiedAt()));
        params.put("verifiedBy", engagement.registrationVerifiedBy());
        params.put("method", engagement.verificationMethod() == null
                ? null : engagement.verificationMethod().name());
        params.put("evidenceId", engagement.evidenceMediaId());
        params.put("dueOn", engagement.reverificationDueOn());
        params.put("warningState", engagement.warningState().name());
        params.put("now", utc(Instant.now()));

        jdbc.sql("""
                INSERT INTO fulfillment.courier_engagements (
                    id, tenant_id, courier_id, engagement_type, status,
                    engaged_from, engaged_until,
                    protected_registration_ref, registration_valid_until,
                    registration_verified_at, registration_verified_by,
                    verification_method, evidence_media_id, reverification_due_on,
                    warning_state, warning_state_changed_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :courierId, 'SELF_EMPLOYED', :status,
                    :engagedFrom, :engagedUntil,
                    :registrationRef, :validUntil,
                    :verifiedAt, :verifiedBy,
                    :method, :evidenceId, :dueOn,
                    :warningState, :now, 1, :now, :now)
                """)
                .params(params)
                .update();
    }

    public Optional<EngagementRow> findEngagement(UUID tenantId, UUID engagementId) {
        return jdbc.sql(SELECT_ENGAGEMENT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", engagementId)
                .query(JdbcCourierStore::mapEngagement)
                .optional();
    }

    /** The one live engagement, if there is one. A partial unique index guarantees at most one. */
    public Optional<EngagementRow> findLiveEngagement(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_ENGAGEMENT + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId AND status <> 'ENDED'
                """)
                .param("tenantId", tenantId).param("courierId", courierId)
                .query(JdbcCourierStore::mapEngagement)
                .optional();
    }

    /**
     * Records a verification and activates the engagement, conditional on the
     * version the caller read. Returns false when somebody else moved first.
     */
    public boolean verify(UUID tenantId, UUID engagementId, int expectedVersion,
            String protectedRegistrationRef, LocalDate validUntil, LocalDate reverificationDueOn,
            VerificationMethod method, String verifiedBy, UUID evidenceMediaId,
            RegistrationWarningState warningState, Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", engagementId);
        params.put("expectedVersion", expectedVersion);
        params.put("registrationRef", protectedRegistrationRef);
        params.put("validUntil", validUntil);
        params.put("dueOn", reverificationDueOn);
        params.put("method", method.name());
        params.put("verifiedBy", verifiedBy);
        params.put("evidenceId", evidenceMediaId);
        params.put("warningState", warningState.name());
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET protected_registration_ref = :registrationRef,
                       registration_valid_until = :validUntil,
                       reverification_due_on = :dueOn,
                       registration_verified_at = :now,
                       registration_verified_by = :verifiedBy,
                       verification_method = :method,
                       evidence_media_id = :evidenceId,
                       status = 'ACTIVE',
                       warning_state = :warningState,
                       warning_state_changed_at = :now,
                       suspension_reason_code = NULL,
                       suspended_at = NULL,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                   AND status <> 'ENDED'
                """)
                .params(params)
                .update() == 1;
    }

    /**
     * Suspends an engagement. Used by a manager and by the compliance sweeper,
     * which is why it takes the status rather than assuming one.
     */
    public boolean suspend(UUID tenantId, UUID engagementId, EngagementStatus status,
            String reasonCode, RegistrationWarningState warningState, Instant now) {

        return jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET status = :status,
                       suspension_reason_code = :reasonCode,
                       suspended_at = :now,
                       warning_state = :warningState,
                       warning_state_changed_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status IN ('ACTIVE', 'PENDING_VERIFICATION')
                """)
                .param("tenantId", tenantId).param("id", engagementId)
                .param("status", status.name()).param("reasonCode", reasonCode)
                .param("warningState", warningState.name()).param("now", utc(now))
                .update() == 1;
    }

    public void markWarningState(UUID tenantId, UUID engagementId,
            RegistrationWarningState warningState, Instant now) {

        jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET warning_state = :warningState,
                       warning_state_changed_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND warning_state <> :warningState
                """)
                .param("tenantId", tenantId).param("id", engagementId)
                .param("warningState", warningState.name()).param("now", utc(now))
                .update();
    }

    /**
     * Everything falling due on or before a date, across tenants.
     *
     * <p>The one query in this class without a tenant predicate, and it is a
     * scheduled sweeper rather than a request path: a job that had to be told
     * which tenants exist would silently stop noticing new ones. The tenant is
     * carried on every row it returns, and every write it makes is scoped by it.
     */
    public List<EngagementRow> dueBy(LocalDate date) {
        return jdbc.sql(SELECT_ENGAGEMENT + """
                 WHERE status IN ('ACTIVE', 'SUSPENDED_COMPLIANCE')
                   AND reverification_due_on IS NOT NULL
                   AND reverification_due_on <= :date
                 ORDER BY reverification_due_on
                """)
                .param("date", date)
                .query(JdbcCourierStore::mapEngagement)
                .list();
    }

    /** The Fleet screen's expiring count, and the notification ladder's candidates. */
    public List<EngagementRow> expiringBetween(LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT_ENGAGEMENT + """
                 WHERE status = 'ACTIVE'
                   AND reverification_due_on BETWEEN :from AND :to
                 ORDER BY reverification_due_on
                """)
                .param("from", from).param("to", to)
                .query(JdbcCourierStore::mapEngagement)
                .list();
    }

    /**
     * The ciphertext, read only where a reveal is about to happen. Separate from
     * every other projection so that "who can see a registration number" is a
     * question about call sites of one method.
     */
    public Optional<String> readProtectedRegistrationRef(UUID tenantId, UUID engagementId) {
        return jdbc.sql("""
                SELECT protected_registration_ref
                  FROM fulfillment.courier_engagements
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", engagementId)
                .query(String.class)
                .optional();
    }

    // --------------------------------------------------------- notification ladder

    /**
     * Records that a rung was rung, and answers whether this call was the one
     * that rang it. Conflict-free by the unique key, so two sweeper instances
     * racing produce one notification rather than two.
     */
    public boolean claimNotice(UUID tenantId, UUID engagementId, int rungDays, String audience,
            LocalDate validUntil, Instant now) {

        return jdbc.sql("""
                INSERT INTO fulfillment.courier_registration_notices (
                    id, tenant_id, engagement_id, rung_days, audience, valid_until, sent_at)
                VALUES (:id, :tenantId, :engagementId, :rungDays, :audience, :validUntil, :now)
                ON CONFLICT ON CONSTRAINT uq_notice_rung DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId)
                .param("engagementId", engagementId).param("rungDays", rungDays)
                .param("audience", audience).param("validUntil", validUntil)
                .param("now", utc(now))
                .update() == 1;
    }

    /**
     * When this engagement was first recorded as lapsed.
     *
     * <p>Read from the append-only notice rows rather than from the engagement,
     * because the engagement's dates move when somebody re-registers and the
     * question a statement asks — was this work done while the registration was
     * out — has to stay answerable afterwards.
     */
    public Optional<Instant> firstLapseNoticeAt(UUID tenantId, UUID engagementId) {
        return jdbc.sql("""
                SELECT MIN(sent_at)
                  FROM fulfillment.courier_registration_notices
                 WHERE tenant_id = :tenantId AND engagement_id = :engagementId AND rung_days = 0
                """)
                .param("tenantId", tenantId).param("engagementId", engagementId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    // ----------------------------------------------------- adjustment reasons

    public void insertAdjustmentReason(UUID id, UUID tenantId, String code, String kind,
            String outcomeBasis, String displayName) {

        jdbc.sql("""
                INSERT INTO fulfillment.courier_adjustment_reasons (
                    id, tenant_id, code, kind, outcome_basis, display_name, status, created_at)
                VALUES (:id, :tenantId, :code, :kind, :basis, :displayName, 'ACTIVE', now())
                """)
                .param("id", id).param("tenantId", tenantId).param("code", code)
                .param("kind", kind).param("basis", outcomeBasis).param("displayName", displayName)
                .update();
    }

    public Optional<AdjustmentReasonRow> findAdjustmentReason(UUID tenantId, String code) {
        return jdbc.sql("""
                SELECT id, tenant_id, code, kind, outcome_basis, display_name, status
                  FROM fulfillment.courier_adjustment_reasons
                 WHERE tenant_id = :tenantId AND code = :code
                """)
                .param("tenantId", tenantId).param("code", code)
                .query((ResultSet rs, int rowNumber) -> new AdjustmentReasonRow(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("code"), rs.getString("kind"), rs.getString("outcome_basis"),
                        rs.getString("display_name"), rs.getString("status")))
                .optional();
    }

    // ------------------------------------------------------------------- rows

    public record CourierTypeRow(UUID id, UUID tenantId, String code, String displayName,
            String vehicleClass, int minDistanceMeters, Integer maxDistanceMeters,
            int maxConcurrentAssignments, int offerTtlSeconds, String status) { }

    public record CourierRow(UUID id, UUID tenantId, UUID courierTypeId, String principalSubject,
            String displayReference, String protectedFullName, String status, int version) { }

    public record EngagementRow(UUID id, UUID tenantId, UUID courierId, EngagementStatus status,
            LocalDate engagedFrom, LocalDate engagedUntil, String protectedRegistrationRef,
            LocalDate registrationValidUntil, Instant registrationVerifiedAt,
            String registrationVerifiedBy, VerificationMethod verificationMethod,
            UUID evidenceMediaId, LocalDate reverificationDueOn,
            RegistrationWarningState warningState, String suspensionReasonCode, int version) { }

    public record AdjustmentReasonRow(UUID id, UUID tenantId, String code, String kind,
            String outcomeBasis, String displayName, String status) { }

    // ---------------------------------------------------------------- mapping

    private static final String SELECT_COURIER = """
            SELECT id, tenant_id, courier_type_id, principal_subject, display_reference,
                   protected_full_name, status, version
              FROM fulfillment.couriers
            """;

    /**
     * The engagement projection, deliberately without the ciphertext column.
     * Reading it here would put a registration number into every row every
     * screen holds, and the only defence would be remembering not to render it.
     */
    private static final String SELECT_ENGAGEMENT = """
            SELECT id, tenant_id, courier_id, status, engaged_from, engaged_until,
                   registration_valid_until, registration_verified_at, registration_verified_by,
                   verification_method, evidence_media_id, reverification_due_on,
                   warning_state, suspension_reason_code, version
              FROM fulfillment.courier_engagements
            """;

    private static CourierTypeRow mapType(ResultSet rs, int rowNumber) throws SQLException {
        return new CourierTypeRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getString("vehicle_class"),
                rs.getInt("min_distance_meters"),
                rs.getObject("max_distance_meters", Integer.class),
                rs.getInt("max_concurrent_assignments"),
                rs.getInt("offer_ttl_seconds"),
                rs.getString("status"));
    }

    private static CourierRow mapCourier(ResultSet rs, int rowNumber) throws SQLException {
        return new CourierRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("courier_type_id", UUID.class),
                rs.getString("principal_subject"),
                rs.getString("display_reference"),
                rs.getString("protected_full_name"),
                rs.getString("status"),
                rs.getInt("version"));
    }

    private static EngagementRow mapEngagement(ResultSet rs, int rowNumber) throws SQLException {
        String method = rs.getString("verification_method");
        return new EngagementRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("courier_id", UUID.class),
                EngagementStatus.valueOf(rs.getString("status")),
                rs.getObject("engaged_from", LocalDate.class),
                rs.getObject("engaged_until", LocalDate.class),
                null,
                rs.getObject("registration_valid_until", LocalDate.class),
                instant(rs.getObject("registration_verified_at", OffsetDateTime.class)),
                rs.getString("registration_verified_by"),
                method == null ? null : VerificationMethod.valueOf(method),
                rs.getObject("evidence_media_id", UUID.class),
                rs.getObject("reverification_due_on", LocalDate.class),
                RegistrationWarningState.valueOf(rs.getString("warning_state")),
                rs.getString("suspension_reason_code"),
                rs.getInt("version"));
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
