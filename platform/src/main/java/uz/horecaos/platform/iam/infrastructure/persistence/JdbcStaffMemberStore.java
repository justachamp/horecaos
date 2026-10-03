package uz.horecaos.platform.iam.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code iam.staff_members}, its counter, and the two reads over {@code
 * iam.grants} that decide who may see whom (ADR 0139, V0453, V0454).
 *
 * <p>Every statement names the tenant. The member row is unique by {@code
 * (tenant_id, principal_subject)}, so a subject who works in two tenants has two
 * rows and a query that forgot the tenant would return the wrong one; there is
 * deliberately no method here that reads by subject alone.
 *
 * <p>Nothing here decrypts. The columns that start {@code protected_} come back
 * as the stored text, and the application services open them with the tenant
 * and the row identity the ciphertext is bound to.
 */
@Repository
public class JdbcStaffMemberStore {

    private static final String COLUMNS = """
            id, tenant_id, principal_subject, display_reference,
            protected_first_name, protected_last_name, protected_phone, phone_lookup_hash,
            protected_employee_number, employee_number_hash, photo_asset_id, ui_locale,
            spoken_languages, employment_status, employed_from, employed_until,
            version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public JdbcStaffMemberStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ writes

    /**
     * The next {@code display_reference} number for this tenant.
     *
     * <p>One statement, so the row lock it takes is held for the rest of the
     * allocating transaction: two invitations in one tenant queue on it and get
     * different numbers, and a rolled-back invitation gives its number back.
     */
    public int allocateReferenceNumber(UUID tenantId) {
        Integer next =
                jdbc.sql("""
                        INSERT INTO iam.staff_member_counters (tenant_id, last_reference)
                        VALUES (:tenantId, 1)
                        ON CONFLICT (tenant_id)
                        DO UPDATE SET last_reference = iam.staff_member_counters.last_reference + 1
                        RETURNING last_reference
                        """).param("tenantId", tenantId).query(Integer.class).single();
        return next;
    }

    public void insert(MemberRow row) {
        jdbc.sql("""
                INSERT INTO iam.staff_members (
                    id, tenant_id, principal_subject, display_reference,
                    protected_first_name, protected_last_name, protected_phone, phone_lookup_hash,
                    protected_employee_number, employee_number_hash, photo_asset_id, ui_locale,
                    spoken_languages, employment_status, employed_from, employed_until,
                    version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :subject, :reference,
                    :firstName, :lastName, :phone, :phoneHash,
                    :employeeNumber, :employeeNumberHash, :photo, :uiLocale,
                    CAST(:languages AS varchar(8)[]), :status, :employedFrom, :employedUntil,
                    :version, :createdAt, :updatedAt)
                """)
                .param("id", row.id())
                .param("tenantId", row.tenantId())
                .param("subject", row.principalSubject())
                .param("reference", row.displayReference())
                .param("firstName", row.protectedFirstName())
                .param("lastName", row.protectedLastName())
                .param("phone", row.protectedPhone())
                .param("phoneHash", row.phoneLookupHash())
                .param("employeeNumber", row.protectedEmployeeNumber())
                .param("employeeNumberHash", row.employeeNumberHash())
                .param("photo", row.photoAssetId())
                .param("uiLocale", row.uiLocale())
                .param("languages", row.spokenLanguages().toArray(String[]::new))
                .param("status", row.employmentStatus())
                .param("employedFrom", row.employedFrom())
                .param("employedUntil", row.employedUntil())
                .param("version", row.version())
                .param("createdAt", at(row.createdAt()))
                .param("updatedAt", at(row.updatedAt()))
                .update();
    }

    /**
     * Replaces every mutable column of the row, guarded on the version the
     * caller read. The version moves by one and the instant is the caller's.
     *
     * @return false when the row is not in this tenant or its version moved
     */
    public boolean update(MemberRow next, int expectedVersion) {
        int updated = jdbc.sql("""
                UPDATE iam.staff_members
                   SET protected_first_name = :firstName, protected_last_name = :lastName,
                       protected_phone = :phone, phone_lookup_hash = :phoneHash,
                       protected_employee_number = :employeeNumber, employee_number_hash = :employeeNumberHash,
                       photo_asset_id = :photo, ui_locale = :uiLocale,
                       spoken_languages = CAST(:languages AS varchar(8)[]),
                       employment_status = :status, employed_from = :employedFrom, employed_until = :employedUntil,
                       version = version + 1, updated_at = :updatedAt
                 WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """)
                .param("id", next.id())
                .param("tenantId", next.tenantId())
                .param("expectedVersion", expectedVersion)
                .param("firstName", next.protectedFirstName())
                .param("lastName", next.protectedLastName())
                .param("phone", next.protectedPhone())
                .param("phoneHash", next.phoneLookupHash())
                .param("employeeNumber", next.protectedEmployeeNumber())
                .param("employeeNumberHash", next.employeeNumberHash())
                .param("photo", next.photoAssetId())
                .param("uiLocale", next.uiLocale())
                .param("languages", next.spokenLanguages().toArray(String[]::new))
                .param("status", next.employmentStatus())
                .param("employedFrom", next.employedFrom())
                .param("employedUntil", next.employedUntil())
                .param("updatedAt", at(next.updatedAt()))
                .update();
        return updated == 1;
    }

    /** Bumps only the version, for a change to a part of the aggregate that lives in another table. */
    public boolean touch(UUID tenantId, UUID memberId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.staff_members
                           SET version = version + 1, updated_at = :now
                         WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                        """)
                        .param("id", memberId)
                        .param("tenantId", tenantId)
                        .param("expectedVersion", expectedVersion)
                        .param("now", at(now))
                        .update()
                == 1;
    }

    // ------------------------------------------------------------------- reads

    public Optional<MemberRow> find(UUID tenantId, UUID memberId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM iam.staff_members WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", memberId)
                .query(JdbcStaffMemberStore::mapRow)
                .optional();
    }

    /** As {@link #find}, taking the row lock for the rest of the transaction. */
    public Optional<MemberRow> findForUpdate(UUID tenantId, UUID memberId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM iam.staff_members WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", tenantId)
                .param("id", memberId)
                .query(JdbcStaffMemberStore::mapRow)
                .optional();
    }

    public Optional<MemberRow> findBySubject(UUID tenantId, String subject) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM iam.staff_members WHERE tenant_id = :tenantId AND principal_subject = :subject")
                .param("tenantId", tenantId)
                .param("subject", subject)
                .query(JdbcStaffMemberStore::mapRow)
                .optional();
    }

    /** As {@link #findBySubject}, taking the row lock for the rest of the transaction. */
    public Optional<MemberRow> findBySubjectForUpdate(UUID tenantId, String subject) {
        return jdbc.sql(
                        "SELECT " + COLUMNS
                                + " FROM iam.staff_members WHERE tenant_id = :tenantId AND principal_subject = :subject FOR UPDATE")
                .param("tenantId", tenantId)
                .param("subject", subject)
                .query(JdbcStaffMemberStore::mapRow)
                .optional();
    }

    public List<MemberRow> findBySubjects(UUID tenantId, Collection<String> subjects) {
        if (subjects.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM iam.staff_members WHERE tenant_id = :tenantId AND principal_subject = ANY(:subjects)")
                .param("tenantId", tenantId)
                .param("subjects", subjects.toArray(String[]::new))
                .query(JdbcStaffMemberStore::mapRow)
                .list();
    }

    public List<MemberRow> findByIds(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM iam.staff_members WHERE tenant_id = :tenantId AND id = ANY(:ids)")
                .param("tenantId", tenantId)
                .param("ids", ids.toArray(UUID[]::new))
                .query(JdbcStaffMemberStore::mapRow)
                .list();
    }

    /** Every member of the tenant, optionally narrowed to one status. */
    public List<MemberRow> listByTenant(UUID tenantId, @Nullable String status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM iam.staff_members WHERE tenant_id = :tenantId"
                        + (status == null ? "" : " AND employment_status = :status")
                        + " ORDER BY display_reference")
                .param("tenantId", tenantId)
                .params(status == null ? Map.of() : Map.of("status", status))
                .query(JdbcStaffMemberStore::mapRow)
                .list();
    }

    // ----------------------------------------------------------- who holds what

    /**
     * The places each of these subjects holds an active job in this tenant.
     *
     * <p>Active means what {@code JdbcAuthorizationService} means by it: the
     * grant and its role both {@code ACTIVE}, inside the validity window. The
     * machine roles are excluded -- a kitchen device and a support session are
     * grants without a colleague behind them, and counting them would make a
     * device a member of staff.
     */
    public Map<String, List<GrantPlace>> activeGrantPlaces(
            UUID tenantId, @Nullable Collection<String> subjects, Instant now, Set<String> machineRoleCodes) {
        if (subjects != null && subjects.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT g.principal_subject, g.scope_type, g.scope_id, l.brand_id AS location_brand_id
                  FROM iam.grants g
                  JOIN iam.roles r ON r.id = g.role_id
             LEFT JOIN tenant.locations l
                    ON g.scope_type = 'LOCATION' AND l.tenant_id = g.tenant_id AND l.id = g.scope_id
                 WHERE g.tenant_id = :tenantId
                   AND g.status = 'ACTIVE' AND r.status = 'ACTIVE'
                   AND g.valid_from <= :now AND (g.valid_until IS NULL OR g.valid_until > :now)
                   AND r.code <> ALL(:machineRoles)
                """ + (subjects == null ? "" : " AND g.principal_subject = ANY(:subjects)");
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("now", at(now))
                .param("machineRoles", machineRoleCodes.toArray(String[]::new));
        if (subjects != null) {
            statement = statement.param("subjects", subjects.toArray(String[]::new));
        }
        Map<String, List<GrantPlace>> places = new LinkedHashMap<>();
        statement
                .query((row, number) -> {
                    String scopeType = row.getString("scope_type");
                    UUID scopeId = row.getObject("scope_id", UUID.class);
                    UUID brandId = "LOCATION".equals(scopeType) ? row.getObject("location_brand_id", UUID.class) : null;
                    return Map.entry(row.getString("principal_subject"), new GrantPlace(scopeType, scopeId, brandId));
                })
                .list()
                .forEach(entry -> places.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue()));
        return places;
    }

    /** The grants {@code subject} holds in this tenant right now, with their ids, so each can be revoked on its own. */
    public List<ActiveGrant> activeGrantsOf(UUID tenantId, String subject, Instant now, Set<String> machineRoleCodes) {
        return jdbc.sql("""
                        SELECT g.id, g.scope_type, g.scope_id, l.brand_id AS location_brand_id
                          FROM iam.grants g
                          JOIN iam.roles r ON r.id = g.role_id
                     LEFT JOIN tenant.locations l
                            ON g.scope_type = 'LOCATION' AND l.tenant_id = g.tenant_id AND l.id = g.scope_id
                         WHERE g.tenant_id = :tenantId AND g.principal_subject = :subject
                           AND g.status = 'ACTIVE' AND r.status = 'ACTIVE'
                           AND g.valid_from <= :now AND (g.valid_until IS NULL OR g.valid_until > :now)
                           AND r.code <> ALL(:machineRoles)
                         ORDER BY g.created_at, g.id
                        """)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("now", at(now))
                .param("machineRoles", machineRoleCodes.toArray(String[]::new))
                .query((row, number) -> {
                    String scopeType = row.getString("scope_type");
                    UUID scopeId = row.getObject("scope_id", UUID.class);
                    UUID brandId = "LOCATION".equals(scopeType) ? row.getObject("location_brand_id", UUID.class) : null;
                    return new ActiveGrant(
                            row.getObject("id", UUID.class), new GrantPlace(scopeType, scopeId, brandId));
                })
                .list();
    }

    /**
     * Whether this subject has ever held a staff job in this tenant, active or
     * not. The identity-provider fallback for a name is allowed only for such a
     * subject: asking about someone who never worked here -- another tenant's
     * colleague, a HorecaOS support person -- must answer nothing, not leak the
     * name Keycloak happens to hold.
     */
    public boolean hasEverHeldStaffJob(UUID tenantId, String subject, Set<String> machineRoleCodes) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM iam.grants g
                              JOIN iam.roles r ON r.id = g.role_id
                             WHERE g.tenant_id = :tenantId AND g.principal_subject = :subject
                               AND r.code <> ALL(:machineRoles))
                        """)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("machineRoles", machineRoleCodes.toArray(String[]::new))
                .query(Boolean.class)
                .single();
    }

    // ------------------------------------------------------------ reconciliation

    /**
     * Subjects holding an active staff job in a tenant who have no member row:
     * the backfill's work list, in {@code (tenant, subject)} order.
     *
     * <p>Keyset-paged: {@code after} is the last subject of the previous page, and
     * only subjects sorting strictly after it are returned. The order alone is not
     * enough for a work list whose members can stay on it -- a subject the identity
     * provider has no account for, or cannot be asked about, is never given a row
     * and so sorts to the head of every {@code LIMIT} -- and a page that always
     * starts at the head would be filled by them and never reach the rest.
     *
     * @param after null for the first page
     */
    public List<SubjectRef> unbackedActiveSubjects(
            Instant now, Set<String> machineRoleCodes, int limit, @Nullable SubjectRef after) {
        String pastCursor =
                after == null ? "" : "AND (g.tenant_id, g.principal_subject) > (:afterTenant, :afterSubject)";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT DISTINCT g.tenant_id, g.principal_subject
                          FROM iam.grants g
                          JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.tenant_id IS NOT NULL
                           AND g.status = 'ACTIVE' AND r.status = 'ACTIVE'
                           AND g.valid_from <= :now AND (g.valid_until IS NULL OR g.valid_until > :now)
                           AND r.code <> ALL(:machineRoles)
                           AND NOT EXISTS (
                               SELECT 1 FROM iam.staff_members m
                                WHERE m.tenant_id = g.tenant_id AND m.principal_subject = g.principal_subject)
                           %s
                         ORDER BY g.tenant_id, g.principal_subject
                         LIMIT :limit
                        """.formatted(pastCursor))
                .param("now", at(now))
                .param("machineRoles", machineRoleCodes.toArray(String[]::new))
                .param("limit", limit);
        if (after != null) {
            statement =
                    statement.param("afterTenant", after.tenantId()).param("afterSubject", after.principalSubject());
        }
        return statement
                .query((row, number) ->
                        new SubjectRef(row.getObject("tenant_id", UUID.class), row.getString("principal_subject")))
                .list();
    }

    public long countUnbackedActiveSubjects(Instant now, Set<String> machineRoleCodes) {
        return jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT DISTINCT g.tenant_id, g.principal_subject
                              FROM iam.grants g
                              JOIN iam.roles r ON r.id = g.role_id
                             WHERE g.tenant_id IS NOT NULL
                               AND g.status = 'ACTIVE' AND r.status = 'ACTIVE'
                               AND g.valid_from <= :now AND (g.valid_until IS NULL OR g.valid_until > :now)
                               AND r.code <> ALL(:machineRoles)
                               AND NOT EXISTS (
                                   SELECT 1 FROM iam.staff_members m
                                    WHERE m.tenant_id = g.tenant_id AND m.principal_subject = g.principal_subject)
                        ) unbacked
                        """)
                .param("now", at(now))
                .param("machineRoles", machineRoleCodes.toArray(String[]::new))
                .query(Long.class)
                .single();
    }

    /** Ended members who still hold an active staff job: the drift the read-time flag shows. */
    public long countEndedWithAccess(Instant now, Set<String> machineRoleCodes) {
        return jdbc.sql("""
                        SELECT count(*) FROM iam.staff_members m
                         WHERE m.employment_status = 'ENDED'
                           AND EXISTS (
                               SELECT 1 FROM iam.grants g
                                 JOIN iam.roles r ON r.id = g.role_id
                                WHERE g.tenant_id = m.tenant_id AND g.principal_subject = m.principal_subject
                                  AND g.status = 'ACTIVE' AND r.status = 'ACTIVE'
                                  AND g.valid_from <= :now AND (g.valid_until IS NULL OR g.valid_until > :now)
                                  AND r.code <> ALL(:machineRoles))
                        """)
                .param("now", at(now))
                .param("machineRoles", machineRoleCodes.toArray(String[]::new))
                .query(Long.class)
                .single();
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT employment_status, count(*) AS total FROM iam.staff_members GROUP BY employment_status")
                .query((row, number) -> Map.entry(row.getString("employment_status"), row.getLong("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    /**
     * A {@code PENDING} member whose invitation was accepted: the acceptance
     * committed its invitation row and then lost the transaction that promotes
     * the member (the invitation's link is spent, so nobody can retry it).
     * Promoted from the evidence the invitation table already holds.
     *
     * @return how many members were promoted
     */
    public int promotePendingWithAcceptedInvitation(Instant now) {
        return jdbc.sql("""
                        UPDATE iam.staff_members m
                           SET employment_status = 'ACTIVE', version = m.version + 1, updated_at = :now
                         WHERE m.employment_status = 'PENDING'
                           AND EXISTS (
                               SELECT 1 FROM tenant.staff_invitations i
                                WHERE i.tenant_id = m.tenant_id AND i.subject_id = m.principal_subject
                                  AND i.status = 'ACCEPTED')
                        """).param("now", at(now)).update();
    }

    /** Ended members whose personal data is past retention and has not been anonymised yet. */
    public List<RetentionCandidate> endedBefore(LocalDate cutoff, int limit) {
        return jdbc.sql("""
                        SELECT m.id, m.tenant_id
                          FROM iam.staff_members m
                         WHERE m.employment_status = 'ENDED' AND m.employed_until < :cutoff
                           AND (m.protected_first_name IS NOT NULL OR m.protected_last_name IS NOT NULL
                                OR m.protected_phone IS NOT NULL OR m.protected_employee_number IS NOT NULL
                                OR m.photo_asset_id IS NOT NULL
                                OR EXISTS (
                                    SELECT 1 FROM iam.staff_emergency_contacts c
                                     WHERE c.tenant_id = m.tenant_id AND c.staff_member_id = m.id))
                         ORDER BY m.employed_until, m.id
                         LIMIT :limit
                        """)
                .param("cutoff", cutoff)
                .param("limit", limit)
                .query((row, number) ->
                        new RetentionCandidate(row.getObject("tenant_id", UUID.class), row.getObject("id", UUID.class)))
                .list();
    }

    public int deleteEmergencyContactsOf(UUID tenantId, UUID memberId) {
        return jdbc.sql(
                        "DELETE FROM iam.staff_emergency_contacts WHERE tenant_id = :tenantId AND staff_member_id = :id")
                .param("tenantId", tenantId)
                .param("id", memberId)
                .update();
    }

    /** The tenant's default timezone, so "today" means the tenant's today. */
    public Optional<String> tenantTimezone(UUID tenantId) {
        return jdbc.sql("SELECT default_timezone FROM tenant.tenants WHERE id = :id")
                .param("id", tenantId)
                .query(String.class)
                .optional();
    }

    // ----------------------------------------------------------------- mapping

    private static MemberRow mapRow(ResultSet row, int number) throws SQLException {
        String[] languages = new String[0];
        java.sql.Array array = row.getArray("spoken_languages");
        if (array != null) {
            languages = (String[]) array.getArray();
        }
        return new MemberRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("principal_subject"),
                row.getString("display_reference"),
                row.getString("protected_first_name"),
                row.getString("protected_last_name"),
                row.getString("protected_phone"),
                row.getString("phone_lookup_hash"),
                row.getString("protected_employee_number"),
                row.getString("employee_number_hash"),
                row.getObject("photo_asset_id", UUID.class),
                row.getString("ui_locale"),
                List.of(languages),
                row.getString("employment_status"),
                row.getObject("employed_from", LocalDate.class),
                row.getObject("employed_until", LocalDate.class),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * One {@code iam.staff_members} row, ciphertext and all. The generated
     * {@code toString} is overridden: a stored value is not plaintext, but a
     * log line that prints a row is a habit worth refusing (ADR 0029).
     */
    public record MemberRow(
            UUID id,
            UUID tenantId,
            String principalSubject,
            String displayReference,
            @Nullable String protectedFirstName,
            @Nullable String protectedLastName,
            @Nullable String protectedPhone,
            @Nullable String phoneLookupHash,
            @Nullable String protectedEmployeeNumber,
            @Nullable String employeeNumberHash,
            @Nullable UUID photoAssetId,
            @Nullable String uiLocale,
            List<String> spokenLanguages,
            String employmentStatus,
            @Nullable LocalDate employedFrom,
            @Nullable LocalDate employedUntil,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        @Override
        public String toString() {
            return "MemberRow[displayReference=" + displayReference + ", status=" + employmentStatus + ", version="
                    + version + "]";
        }
    }

    /** Where one active job sits: its scope level, that level's id, and for a location the brand above it. */
    public record GrantPlace(
            String scopeType,
            @Nullable UUID scopeId,
            @Nullable UUID brandId) {}

    public record ActiveGrant(UUID grantId, GrantPlace place) {}

    public record SubjectRef(UUID tenantId, String principalSubject) {}

    public record RetentionCandidate(UUID tenantId, UUID memberId) {}
}
