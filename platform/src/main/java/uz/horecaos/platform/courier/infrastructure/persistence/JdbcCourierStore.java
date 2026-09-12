package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.courier.domain.ComplianceField;
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
                """).params(typeParams(type)).update();
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
                .param("tenantId", tenantId)
                .param("id", typeId)
                .query(JdbcCourierStore::mapType)
                .optional();
    }

    /** Every active vehicle class, for the registration form's picker (§9). */
    public List<CourierTypeRow> listTypes(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, tenant_id, code, display_name, vehicle_class,
                       min_distance_meters, max_distance_meters,
                       max_concurrent_assignments, offer_ttl_seconds, status
                  FROM fulfillment.courier_types
                 WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                 ORDER BY display_name
                """)
                .param("tenantId", tenantId)
                .query(JdbcCourierStore::mapType)
                .list();
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
                .param("id", courier.id())
                .param("tenantId", courier.tenantId())
                .param("typeId", courier.courierTypeId())
                .param("subject", courier.principalSubject())
                .param("reference", courier.displayReference())
                .param("protectedName", courier.protectedFullName())
                .param("now", utc(Instant.now()))
                .update();
    }

    public Optional<CourierRow> findCourier(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_COURIER + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", courierId)
                .query(JdbcCourierStore::mapCourier)
                .optional();
    }

    /**
     * {@code display_reference} for a batch of couriers, keyed by id. The
     * non-personal handle (ADR 0029) a shift or roster list names a courier by
     * when it must show more than a bare id, and never the decrypted name.
     */
    public Map<UUID, String> displayReferencesOf(UUID tenantId, Collection<UUID> courierIds) {
        if (courierIds.isEmpty()) {
            // NamedParameterJdbcTemplate renders an empty collection as `IN ()`,
            // which PostgreSQL rejects. An empty question also has an answer.
            return Map.of();
        }
        return jdbc
                .sql("""
                SELECT id, display_reference
                  FROM fulfillment.couriers
                 WHERE tenant_id = :tenantId AND id IN (:courierIds)
                """)
                .param("tenantId", tenantId)
                .param("courierIds", courierIds)
                .query((ResultSet rs, int rowNumber) ->
                        Map.entry(rs.getObject("id", UUID.class), rs.getString("display_reference")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The roster (§3.3 Couriers, §5 list). No decrypted name: {@code
     * display_reference} is what this projection carries, and every screen that
     * reads it is the routine one {@code courier.read}'s own doc describes
     * (ADR 0029). The engagement is a {@code LEFT JOIN} rather than an inner one
     * so a courier row with no live engagement — a state {@link #suspend} and
     * {@link #verify} never produce, but a future write path might — still
     * renders instead of vanishing from a manager's count.
     */
    public List<CourierRosterRow> listCouriers(UUID tenantId) {
        return jdbc.sql(SELECT_ROSTER + """
                 WHERE c.tenant_id = :tenantId
                 ORDER BY c.created_at DESC
                """)
                .param("tenantId", tenantId)
                .query(JdbcCourierStore::mapRoster)
                .list();
    }

    /**
     * One roster row, for the detail pane behind the list (IA 3.3).
     *
     * <p>The same projection as {@link #listCouriers} rather than a wider one:
     * opening a courier must not by itself show more about them than the list
     * does. What the detail pane adds it adds through {@link
     * #findComplianceSummary} — presence, not content — and through an explicit
     * reveal.
     */
    public Optional<CourierRosterRow> findRosterEntry(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_ROSTER + " WHERE c.tenant_id = :tenantId AND c.id = :courierId")
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcCourierStore::mapRoster)
                .optional();
    }

    /**
     * The lookup behind "a courier reads their own ledger and nobody else's".
     * The subject comes from the token rather than from the path, so a courier
     * cannot ask about a courier id that is not theirs.
     */
    public Optional<CourierRow> findCourierBySubject(UUID tenantId, String principalSubject) {
        return jdbc.sql(SELECT_COURIER + " WHERE tenant_id = :tenantId AND principal_subject = :subject")
                .param("tenantId", tenantId)
                .param("subject", principalSubject)
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
        params.put(
                "method",
                engagement.verificationMethod() == null
                        ? null
                        : engagement.verificationMethod().name());
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
                """).params(params).update();
    }

    public Optional<EngagementRow> findEngagement(UUID tenantId, UUID engagementId) {
        return jdbc.sql(SELECT_ENGAGEMENT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", engagementId)
                .query(JdbcCourierStore::mapEngagement)
                .optional();
    }

    /** The one live engagement, if there is one. A partial unique index guarantees at most one. */
    public Optional<EngagementRow> findLiveEngagement(UUID tenantId, UUID courierId) {
        return jdbc.sql(SELECT_ENGAGEMENT + """
                 WHERE tenant_id = :tenantId AND courier_id = :courierId AND status <> 'ENDED'
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcCourierStore::mapEngagement)
                .optional();
    }

    /**
     * Records a verification and activates the engagement, conditional on the
     * version the caller read. Returns false when somebody else moved first.
     */
    public boolean verify(
            UUID tenantId,
            UUID engagementId,
            int expectedVersion,
            String protectedRegistrationRef,
            LocalDate validUntil,
            LocalDate reverificationDueOn,
            VerificationMethod method,
            String verifiedBy,
            @Nullable UUID evidenceMediaId,
            RegistrationWarningState warningState,
            Instant now) {

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
                """).params(params).update() == 1;
    }

    /**
     * Suspends an engagement. Used by a manager and by the compliance sweeper,
     * which is why it takes the status rather than assuming one.
     */
    public boolean suspend(
            UUID tenantId,
            UUID engagementId,
            EngagementStatus status,
            String reasonCode,
            RegistrationWarningState warningState,
            Instant now) {

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
                        .param("tenantId", tenantId)
                        .param("id", engagementId)
                        .param("status", status.name())
                        .param("reasonCode", reasonCode)
                        .param("warningState", warningState.name())
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public void markWarningState(UUID tenantId, UUID engagementId, RegistrationWarningState warningState, Instant now) {

        jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET warning_state = :warningState,
                       warning_state_changed_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND warning_state <> :warningState
                """)
                .param("tenantId", tenantId)
                .param("id", engagementId)
                .param("warningState", warningState.name())
                .param("now", utc(now))
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
                .param("from", from)
                .param("to", to)
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
                .param("tenantId", tenantId)
                .param("id", engagementId)
                .query(String.class)
                .optional();
    }

    // ------------------------------------------------------- the compliance file

    /**
     * Writes the fields this call carries and clears the ones it names, leaving
     * every other field alone (IA 3.3).
     *
     * <p>Not a whole-row replace, and the reason is the screen rather than a
     * preference. Nothing outside a reveal ever holds the plaintext of these
     * columns, so a detail pane cannot re-send the eight fields it did not
     * change; a replace would therefore erase a passport every time somebody
     * corrected a plate. Clearing is consequently an explicit act with its own
     * list, which is also the honest shape: "I no longer hold this document" is
     * a different statement from "I am not sending it right now".
     *
     * <p>The column names are interpolated, and are safe to interpolate because
     * the only source of one is {@link ComplianceField#column()} — a caller
     * cannot reach this statement with a string it invented.
     */
    public void recordComplianceFile(
            UUID tenantId,
            UUID courierId,
            Map<ComplianceField, String> protectedValues,
            Set<ComplianceField> cleared,
            @Nullable String vehicleFuelType,
            @Nullable UUID photoMediaId,
            String updatedBy,
            Instant now) {

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        int index = 0;
        for (Map.Entry<ComplianceField, String> entry : protectedValues.entrySet()) {
            String parameter = "field" + index++;
            assignments.add(entry.getKey().column() + " = :" + parameter);
            params.put(parameter, entry.getValue());
        }
        for (ComplianceField field : cleared) {
            assignments.add(field.column() + " = NULL");
        }
        if (vehicleFuelType != null) {
            assignments.add("vehicle_fuel_type = :fuelType");
            params.put("fuelType", vehicleFuelType);
        }
        if (photoMediaId != null) {
            assignments.add("photo_media_id = :photoMediaId");
            params.put("photoMediaId", photoMediaId);
        }
        if (assignments.isEmpty()) {
            // A call that changes nothing still must not stamp a provenance: it
            // would read afterwards as though somebody had reviewed the file.
            return;
        }

        assignments.add("compliance_updated_at = :now");
        assignments.add("compliance_updated_by = :updatedBy");
        assignments.add("version = version + 1");
        assignments.add("updated_at = :now");
        params.put("now", utc(now));
        params.put("updatedBy", updatedBy);
        params.put("tenantId", tenantId);
        params.put("courierId", courierId);

        jdbc.sql("UPDATE fulfillment.couriers SET " + String.join(", ", assignments)
                        + " WHERE tenant_id = :tenantId AND id = :courierId")
                .params(params)
                .update();
    }

    /**
     * Every ciphertext on file, read only where a reveal is about to happen.
     *
     * <p>The counterpart of {@link #readProtectedRegistrationRef} and separate
     * from {@link #findComplianceSummary} for the same reason: "who may see a
     * courier's passport" stays a question about the call sites of one method.
     */
    public Map<ComplianceField, String> readComplianceFile(UUID tenantId, UUID courierId) {
        String columns = Arrays.stream(ComplianceField.values())
                .map(ComplianceField::column)
                .collect(Collectors.joining(", "));

        return jdbc.sql("SELECT " + columns + " FROM fulfillment.couriers"
                        + " WHERE tenant_id = :tenantId AND id = :courierId")
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query((ResultSet rs, int rowNumber) -> {
                    Map<ComplianceField, String> held = new EnumMap<>(ComplianceField.class);
                    for (ComplianceField field : ComplianceField.values()) {
                        String ciphertext = rs.getString(field.column());
                        if (ciphertext != null) {
                            held.put(field, ciphertext);
                        }
                    }
                    return held;
                })
                .optional()
                .orElseGet(() -> new EnumMap<>(ComplianceField.class));
    }

    /**
     * What the detail pane reads: which fields are on file, never their
     * contents.
     *
     * <p>Presence is not content, and the distinction is what lets a manager
     * see an incomplete file — the whole point of IA 3.3's compliance worklist —
     * without anybody exercising {@code courier.pii.reveal} to find out.
     */
    public Optional<ComplianceSummaryRow> findComplianceSummary(UUID tenantId, UUID courierId) {
        String presence = Arrays.stream(ComplianceField.values())
                .map(field -> "(" + field.column() + " IS NOT NULL) AS has_"
                        + field.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));

        return jdbc.sql("SELECT " + presence + ", vehicle_fuel_type, photo_media_id,"
                        + " compliance_updated_at, compliance_updated_by"
                        + " FROM fulfillment.couriers WHERE tenant_id = :tenantId AND id = :courierId")
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query((ResultSet rs, int rowNumber) -> {
                    Set<ComplianceField> onFile = EnumSet.noneOf(ComplianceField.class);
                    for (ComplianceField field : ComplianceField.values()) {
                        if (rs.getBoolean("has_" + field.name().toLowerCase(Locale.ROOT))) {
                            onFile.add(field);
                        }
                    }
                    OffsetDateTime updatedAt = rs.getObject("compliance_updated_at", OffsetDateTime.class);
                    return new ComplianceSummaryRow(
                            onFile,
                            rs.getString("vehicle_fuel_type"),
                            rs.getObject("photo_media_id", UUID.class),
                            updatedAt == null ? null : updatedAt.toInstant(),
                            rs.getString("compliance_updated_by"));
                })
                .optional();
    }

    // ------------------------------------------------------------------ groups

    public void insertGroup(UUID id, UUID tenantId, String code, String displayName) {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_groups (id, tenant_id, code, display_name, status)
                VALUES (:id, :tenantId, :code, :displayName, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("displayName", displayName)
                .update();
    }

    public List<CourierGroupRow> listGroups(UUID tenantId) {
        return jdbc.sql("""
                SELECT g.id, g.code, g.display_name, g.status,
                       (SELECT count(*) FROM fulfillment.courier_group_members m
                         WHERE m.tenant_id = g.tenant_id AND m.group_id = g.id) AS member_count
                  FROM fulfillment.courier_groups g
                 WHERE g.tenant_id = :tenantId
                 ORDER BY g.display_name
                """)
                .param("tenantId", tenantId)
                .query((ResultSet rs, int rowNumber) -> new CourierGroupRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getInt("member_count")))
                .list();
    }

    /** Archives a group. Its members stay listed so a past shift plan still reads. */
    public boolean archiveGroup(UUID tenantId, UUID groupId, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.courier_groups
                   SET status = 'ARCHIVED', version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :groupId AND status = 'ACTIVE'
                """)
                        .param("tenantId", tenantId)
                        .param("groupId", groupId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Idempotent: joining a group somebody is already in is not an error. */
    public boolean addToGroup(UUID tenantId, UUID groupId, UUID courierId, String addedBy, Instant now) {
        return jdbc.sql("""
                INSERT INTO fulfillment.courier_group_members (tenant_id, group_id, courier_id, added_at, added_by)
                VALUES (:tenantId, :groupId, :courierId, :now, :addedBy)
                ON CONFLICT ON CONSTRAINT pk_courier_group_member DO NOTHING
                """)
                        .param("tenantId", tenantId)
                        .param("groupId", groupId)
                        .param("courierId", courierId)
                        .param("addedBy", addedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean removeFromGroup(UUID tenantId, UUID groupId, UUID courierId) {
        return jdbc.sql("""
                DELETE FROM fulfillment.courier_group_members
                 WHERE tenant_id = :tenantId AND group_id = :groupId AND courier_id = :courierId
                """)
                        .param("tenantId", tenantId)
                        .param("groupId", groupId)
                        .param("courierId", courierId)
                        .update()
                == 1;
    }

    public List<CourierGroupRow> groupsOf(UUID tenantId, UUID courierId) {
        return jdbc.sql("""
                SELECT g.id, g.code, g.display_name, g.status, 0 AS member_count
                  FROM fulfillment.courier_group_members m
                  JOIN fulfillment.courier_groups g
                    ON g.tenant_id = m.tenant_id AND g.id = m.group_id
                 WHERE m.tenant_id = :tenantId AND m.courier_id = :courierId
                 ORDER BY g.display_name
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query((ResultSet rs, int rowNumber) -> new CourierGroupRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getInt("member_count")))
                .list();
    }

    // -------------------------------------------------------- branch bindings

    /**
     * Binds a courier to a branch. Re-binding an existing pair moves the primary
     * flag rather than failing, because "make Chilonzor his main branch" is the
     * same operator gesture as "bind him to Chilonzor" a second time.
     */
    public void bindToBranch(
            UUID tenantId,
            UUID courierId,
            UUID brandId,
            UUID locationId,
            boolean primary,
            String boundBy,
            Instant now) {

        if (primary) {
            // One primary per courier is a partial unique index, so the old one
            // is stood down first rather than colliding with the new one.
            jdbc.sql("""
                    UPDATE fulfillment.courier_branch_bindings
                       SET is_primary = false
                     WHERE tenant_id = :tenantId AND courier_id = :courierId AND is_primary
                    """)
                    .param("tenantId", tenantId)
                    .param("courierId", courierId)
                    .update();
        }

        jdbc.sql("""
                INSERT INTO fulfillment.courier_branch_bindings (
                    id, tenant_id, courier_id, brand_id, location_id, is_primary, bound_at, bound_by)
                VALUES (:id, :tenantId, :courierId, :brandId, :locationId, :primary, :now, :boundBy)
                ON CONFLICT ON CONSTRAINT uq_courier_binding
                DO UPDATE SET is_primary = EXCLUDED.is_primary, bound_at = EXCLUDED.bound_at,
                              bound_by = EXCLUDED.bound_by
                """)
                .param("id", uz.horecaos.platform.configuration.Ids.newId())
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("primary", primary)
                .param("boundBy", boundBy)
                .param("now", utc(now))
                .update();
    }

    public boolean unbindFromBranch(UUID tenantId, UUID courierId, UUID locationId) {
        return jdbc.sql("""
                DELETE FROM fulfillment.courier_branch_bindings
                 WHERE tenant_id = :tenantId AND courier_id = :courierId AND location_id = :locationId
                """)
                        .param("tenantId", tenantId)
                        .param("courierId", courierId)
                        .param("locationId", locationId)
                        .update()
                == 1;
    }

    public List<BranchBindingRow> bindingsOf(UUID tenantId, UUID courierId) {
        return jdbc.sql("""
                SELECT b.location_id, b.brand_id, b.is_primary, l.display_name
                  FROM fulfillment.courier_branch_bindings b
                  JOIN tenant.locations l
                    ON l.tenant_id = b.tenant_id AND l.brand_id = b.brand_id AND l.id = b.location_id
                 WHERE b.tenant_id = :tenantId AND b.courier_id = :courierId
                 ORDER BY b.is_primary DESC, l.display_name
                """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query((ResultSet rs, int rowNumber) -> new BranchBindingRow(
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("brand_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getBoolean("is_primary")))
                .list();
    }

    // --------------------------------------------------------- notification ladder

    /**
     * Records that a rung was rung, and answers whether this call was the one
     * that rang it. Conflict-free by the unique key, so two sweeper instances
     * racing produce one notification rather than two.
     */
    public boolean claimNotice(
            UUID tenantId, UUID engagementId, int rungDays, String audience, LocalDate validUntil, Instant now) {

        return jdbc.sql("""
                INSERT INTO fulfillment.courier_registration_notices (
                    id, tenant_id, engagement_id, rung_days, audience, valid_until, sent_at)
                VALUES (:id, :tenantId, :engagementId, :rungDays, :audience, :validUntil, :now)
                ON CONFLICT ON CONSTRAINT uq_notice_rung DO NOTHING
                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", tenantId)
                        .param("engagementId", engagementId)
                        .param("rungDays", rungDays)
                        .param("audience", audience)
                        .param("validUntil", validUntil)
                        .param("now", utc(now))
                        .update()
                == 1;
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
                .param("tenantId", tenantId)
                .param("engagementId", engagementId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    // ----------------------------------------------------- adjustment reasons

    public void insertAdjustmentReason(
            UUID id, UUID tenantId, String code, String kind, String outcomeBasis, String displayName) {

        jdbc.sql("""
                INSERT INTO fulfillment.courier_adjustment_reasons (
                    id, tenant_id, code, kind, outcome_basis, display_name, status, created_at)
                VALUES (:id, :tenantId, :code, :kind, :basis, :displayName, 'ACTIVE', now())
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("kind", kind)
                .param("basis", outcomeBasis)
                .param("displayName", displayName)
                .update();
    }

    public Optional<AdjustmentReasonRow> findAdjustmentReason(UUID tenantId, String code) {
        return jdbc.sql("""
                SELECT id, tenant_id, code, kind, outcome_basis, display_name, status
                  FROM fulfillment.courier_adjustment_reasons
                 WHERE tenant_id = :tenantId AND code = :code
                """)
                .param("tenantId", tenantId)
                .param("code", code)
                .query((ResultSet rs, int rowNumber) -> new AdjustmentReasonRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("kind"),
                        rs.getString("outcome_basis"),
                        rs.getString("display_name"),
                        rs.getString("status")))
                .optional();
    }

    // ------------------------------------------------------------------- rows

    /**
     * A vehicle class and its dispatch numbers.
     *
     * @param maxDistanceMeters null when the class has no upper distance bound
     */
    public record CourierTypeRow(
            UUID id,
            UUID tenantId,
            String code,
            String displayName,
            String vehicleClass,
            int minDistanceMeters,
            @Nullable Integer maxDistanceMeters,
            int maxConcurrentAssignments,
            int offerTtlSeconds,
            String status) {}

    public record CourierRow(
            UUID id,
            UUID tenantId,
            UUID courierTypeId,
            String principalSubject,
            String displayReference,
            String protectedFullName,
            String status,
            int version) {}

    /**
     * One roster row — {@link #listCouriers}'s projection, joined with the type
     * and the live engagement (absent for the engagement fields when there is
     * none). This is deliberately a different shape from {@link CourierRow}:
     * that one is the write-path aggregate, the internals a command loads and
     * conditions on; this one is what a list screen renders, and the two must
     * be free to diverge without either constraining the other's columns.
     */
    public record CourierRosterRow(
            UUID id,
            String displayReference,
            String status,
            UUID courierTypeId,
            String courierTypeName,
            String vehicleClass,
            int maxConcurrentAssignments,
            @Nullable UUID engagementId,
            @Nullable String engagementStatus,
            @Nullable String warningState,
            @Nullable LocalDate reverificationDueOn) {}

    /**
     * An engagement as the ordinary projection reads it.
     *
     * <p>Everything after {@code engagedFrom} is absent until the act that
     * writes it happens: the registration fields until {@link #verify}, the
     * suspension reason until a suspension. {@code protectedRegistrationRef} is
     * always null here — the projection deliberately never selects the
     * ciphertext column.
     */
    public record EngagementRow(
            UUID id,
            UUID tenantId,
            UUID courierId,
            EngagementStatus status,
            LocalDate engagedFrom,
            @Nullable LocalDate engagedUntil,
            @Nullable String protectedRegistrationRef,
            @Nullable LocalDate registrationValidUntil,
            @Nullable Instant registrationVerifiedAt,
            @Nullable String registrationVerifiedBy,
            @Nullable VerificationMethod verificationMethod,
            @Nullable UUID evidenceMediaId,
            @Nullable LocalDate reverificationDueOn,
            RegistrationWarningState warningState,
            @Nullable String suspensionReasonCode,
            int version) {}

    public record AdjustmentReasonRow(
            UUID id, UUID tenantId, String code, String kind, String outcomeBasis, String displayName, String status) {}

    /**
     * The compliance file as a screen may read it: which fields exist, and the
     * two that are held in clear because they are not facts about a person.
     *
     * @param onFile which protected fields have a value — presence, never content
     * @param updatedBy the IAM subject that last recorded the file, never a name
     */
    public record ComplianceSummaryRow(
            Set<ComplianceField> onFile,
            @Nullable String vehicleFuelType,
            @Nullable UUID photoMediaId,
            @Nullable Instant complianceUpdatedAt,
            @Nullable String updatedBy) {}

    /** @param memberCount zero when the row came from {@code groupsOf}, which does not count */
    public record CourierGroupRow(UUID id, String code, String displayName, String status, int memberCount) {}

    public record BranchBindingRow(UUID locationId, UUID brandId, String locationName, boolean primary) {}

    // ---------------------------------------------------------------- mapping

    private static final String SELECT_COURIER = """
            SELECT id, tenant_id, courier_type_id, principal_subject, display_reference,
                   protected_full_name, status, version
              FROM fulfillment.couriers
            """;

    /**
     * The list projection, shared by the roster and by the detail pane.
     *
     * <p>Not one ciphertext column among them. Nine protected columns now sit on
     * {@code fulfillment.couriers}, and {@code SELECT *} on this table would put
     * a passport in every row of a screen a dispatcher keeps open all day; naming
     * the columns is what makes that a deliberate act rather than an omission.
     */
    private static final String SELECT_ROSTER = """
            SELECT c.id, c.display_reference, c.status,
                   c.courier_type_id, t.display_name AS type_name, t.vehicle_class,
                   t.max_concurrent_assignments,
                   e.id AS engagement_id, e.status AS engagement_status,
                   e.warning_state, e.reverification_due_on
              FROM fulfillment.couriers c
              JOIN fulfillment.courier_types t
                ON t.tenant_id = c.tenant_id AND t.id = c.courier_type_id
         LEFT JOIN fulfillment.courier_engagements e
                ON e.tenant_id = c.tenant_id AND e.courier_id = c.id AND e.status <> 'ENDED'
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

    private static CourierRosterRow mapRoster(ResultSet rs, int rowNumber) throws SQLException {
        return new CourierRosterRow(
                rs.getObject("id", UUID.class),
                rs.getString("display_reference"),
                rs.getString("status"),
                rs.getObject("courier_type_id", UUID.class),
                rs.getString("type_name"),
                rs.getString("vehicle_class"),
                rs.getInt("max_concurrent_assignments"),
                rs.getObject("engagement_id", UUID.class),
                rs.getString("engagement_status"),
                rs.getString("warning_state"),
                rs.getObject("reverification_due_on", LocalDate.class));
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

    static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    static @Nullable OffsetDateTime utc(@Nullable Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    // ------------------------------------------------------ applicant retention

    /**
     * Couriers who applied and were never verified, untouched since {@code
     * cutoff} (ADR 0092, decided 2026-09-11), claimed for erasure.
     *
     * <p>Never verified means no engagement of theirs carries a verification,
     * and no shift or ledger entry names them -- a person who was paid is a
     * courier with a settlement history, not an applicant, whatever their
     * engagement says now. Locks the courier row only; the application role
     * holds UPDATE on it.
     */
    public List<ApplicantRef> claimUnverifiedApplicants(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                SELECT c.tenant_id, c.id
                  FROM fulfillment.couriers c
                 WHERE c.status = 'ACTIVE'
                   AND c.updated_at < :cutoff
                   AND NOT EXISTS (
                       SELECT 1 FROM fulfillment.courier_engagements e
                        WHERE e.tenant_id = c.tenant_id AND e.courier_id = c.id
                          AND (e.registration_verified_at IS NOT NULL OR e.updated_at >= :cutoff))
                   AND NOT EXISTS (
                       SELECT 1 FROM fulfillment.courier_shifts s
                        WHERE s.tenant_id = c.tenant_id AND s.courier_id = c.id)
                   AND NOT EXISTS (
                       SELECT 1 FROM fulfillment.courier_ledger_entries l
                        WHERE l.tenant_id = c.tenant_id AND l.courier_id = c.id)
                 ORDER BY c.updated_at
                 LIMIT :batchSize
                 FOR UPDATE OF c SKIP LOCKED
                """)
                .param("cutoff", utc(cutoff))
                .param("batchSize", batchSize)
                .query((row, number) ->
                        new ApplicantRef(row.getObject("tenant_id", UUID.class), row.getObject("id", UUID.class)))
                .list();
    }

    /**
     * Overwrites an applicant's name with a tombstone, archives them, and
     * ends every engagement they opened. The tombstone is protected like any
     * name, so a later reveal reads as exactly what happened.
     */
    public void eraseApplicant(UUID tenantId, UUID courierId, String protectedTombstone, LocalDate today, Instant now) {
        jdbc.sql("""
                UPDATE fulfillment.couriers
                   SET protected_full_name = :tombstone, status = 'ARCHIVED',
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :courierId
                """)
                .param("tombstone", protectedTombstone)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .update();
        jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET status = 'ENDED', engaged_until = GREATEST(engaged_from, CAST(:today AS date)),
                       protected_registration_ref = NULL, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND courier_id = :courierId AND status <> 'ENDED'
                """)
                .param("today", today)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .update();
    }

    /** A courier identity without its row -- what the cross-tenant applicant sweep claims. */
    public record ApplicantRef(UUID tenantId, UUID courierId) {}
}
