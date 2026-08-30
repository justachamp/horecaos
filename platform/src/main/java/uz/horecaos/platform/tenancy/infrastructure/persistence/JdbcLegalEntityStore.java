package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.tenancy.api.LegalEntityId;
import uz.horecaos.platform.tenancy.api.LegalEntitySummary;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.LegalEntity;
import uz.horecaos.platform.tenancy.domain.LocationFiscalAssignment;
import uz.horecaos.platform.tenancy.domain.OperatingUnitStatus;
import uz.horecaos.platform.tenancy.domain.TaxpayerNumber;

/**
 * Legal entities and the effective-dated assignment of a branch to one, in SQL
 * (ADR 0038).
 *
 * <p>Every statement carries the tenant predicate in the query rather than
 * checking ownership after loading, and the resolution below carries the location
 * predicate beside it. A legal entity id arriving from a request body or from
 * another module's row is not evidence of anything: filtering afterwards is how a
 * cross-tenant read becomes another restaurant's INN on a customer's receipt.
 *
 * <p><strong>The two tables this reads do not exist yet.</strong> ADR 0038's
 * rollout stage 1 is unbuilt and schema numbering is central, so
 * {@link #isWired()} probes for them and every read answers empty until they
 * land. That is deliberately not an exception: the fiscal module's obligation
 * opener asks this on every completed order, and a deployment without the
 * migration must produce visible blocked work rather than a stack trace per
 * order. When the migration lands the probe flips without a restart and nothing
 * else here changes.
 */
@Repository
public class JdbcLegalEntityStore implements LegalEntityDirectory {

    private static final Logger log = LoggerFactory.getLogger(JdbcLegalEntityStore.class);

    private static final String ENTITY_COLUMNS = """
            id, tenant_id, code, legal_name, short_name, tin, vat_registered,
            vat_certificate_reference, tax_profile_id, registered_address, contact_phone,
            status, version
            """;

    private static final String ASSIGNMENT_COLUMNS = """
            id, tenant_id, brand_id, location_id, legal_entity_id, effective_from,
            effective_until, approved_by, approval_reference, version
            """;

    private final JdbcClient jdbc;

    /**
     * Cached only once it is true. Re-probed while false so the migration landing
     * is picked up without a redeployment; a probe is one
     * {@code SELECT to_regclass(...)} and this is not a hot path.
     */
    private volatile boolean schemaPresent;

    public JdbcLegalEntityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isWired() {
        if (schemaPresent) {
            return true;
        }
        boolean present = Boolean.TRUE.equals(jdbc.sql("""
                SELECT to_regclass('tenant.legal_entities') IS NOT NULL
                   AND to_regclass('tenant.location_fiscal_assignments') IS NOT NULL
                """).query(Boolean.class).single());
        if (present) {
            schemaPresent = true;
        } else {
            log.debug("tenant.legal_entities is not present; no location resolves a seller "
                    + "(ADR 0038 rollout stage 1).");
        }
        return present;
    }

    // ------------------------------------------------------------- resolution

    /**
     * The seller in force at a location on one business date.
     *
     * <p>An inner join on {@code (tenant_id, id)} rather than on the entity id
     * alone: the composite is what makes it impossible for an assignment carrying
     * a foreign tenant's entity — however it got written — to resolve to that
     * entity's INN.
     *
     * <p>{@code LIMIT 1} without an {@code ORDER BY} tiebreak is deliberate. The
     * exclusion constraint means at most one row can cover a date, so ordering
     * would only be there to make an impossible second row deterministic — and a
     * deterministic wrong taxpayer is worse than a loud one, because nobody looks
     * at it.
     */
    @Override
    public Optional<FiscalSeller> sellerFor(UUID tenantId, UUID locationId, LocalDate businessDate) {
        if (!isWired()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT e.id, e.tenant_id, e.code, e.legal_name, e.tin, e.vat_registered,
                       e.tax_profile_id, e.status,
                       a.id AS assignment_id, a.version AS assignment_version,
                       a.effective_from, a.effective_until
                FROM tenant.location_fiscal_assignments a
                JOIN tenant.legal_entities e
                  ON e.tenant_id = a.tenant_id AND e.id = a.legal_entity_id
                WHERE a.tenant_id = :tenantId
                  AND a.location_id = :locationId
                  AND a.effective_from <= :businessDate
                  AND (a.effective_until IS NULL OR a.effective_until > :businessDate)
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("businessDate", businessDate)
                .query(JdbcLegalEntityStore::toSeller)
                .optional();
    }

    /**
     * The narrow, id-keyed answer {@link LegalEntityDirectory#summary} promises.
     *
     * <p>A separate query rather than a call to {@link #find}, deliberately:
     * {@link #find} returns the full mutable {@link LegalEntity} aggregate that
     * only {@code tenancy.application} may hold, and reusing it here would put a
     * domain type on the interface another module implements against. This reads
     * only the two columns {@link LegalEntitySummary} carries.
     */
    @Override
    public Optional<LegalEntitySummary> summary(UUID tenantId, UUID legalEntityId) {
        if (!isWired()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT id, tenant_id, code, status
                  FROM tenant.legal_entities
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", legalEntityId)
                .query((row, number) -> new LegalEntitySummary(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("code"),
                        OperatingUnitStatus.valueOf(row.getString("status")) == OperatingUnitStatus.ACTIVE))
                .optional();
    }

    // ----------------------------------------------------------- legal entities

    public void insert(LegalEntity entity, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (
                    id, tenant_id, code, legal_name, short_name, tin, vat_registered,
                    vat_certificate_reference, tax_profile_id, registered_address, contact_phone,
                    status, version, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :legalName, :shortName, :tin, :vatRegistered,
                    :certificate, :taxProfileId, :address, :phone, :status, :version, :now, :now)
                """)
                .param("id", entity.id().value())
                .param("tenantId", entity.tenantId().value())
                .param("code", entity.code())
                .param("legalName", entity.legalName())
                .param("shortName", entity.shortName())
                .param("tin", entity.tin().value())
                .param("vatRegistered", entity.vatRegistered())
                .param("certificate", entity.vatCertificateReference())
                .param("taxProfileId", entity.taxProfileId())
                .param("address", entity.registeredAddress())
                .param("phone", entity.contactPhone())
                .param("status", entity.status().name())
                .param("version", entity.version())
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Writes an entity back under its expected version.
     *
     * <p>The TIN is not in the SET list, and its absence is the point. A
     * re-registration is a different taxpayer, so it is a new entity and a new
     * assignment from the date it took effect — rewriting the number here would
     * silently restate the seller on every receipt this entity has ever issued.
     *
     * @return false when somebody else moved the row first
     */
    public boolean update(LegalEntity entity, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE tenant.legal_entities
                SET legal_name = :legalName,
                    short_name = :shortName,
                    vat_registered = :vatRegistered,
                    vat_certificate_reference = :certificate,
                    tax_profile_id = :taxProfileId,
                    registered_address = :address,
                    contact_phone = :phone,
                    status = :status,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("id", entity.id().value())
                        .param("tenantId", entity.tenantId().value())
                        .param("legalName", entity.legalName())
                        .param("shortName", entity.shortName())
                        .param("vatRegistered", entity.vatRegistered())
                        .param("certificate", entity.vatCertificateReference())
                        .param("taxProfileId", entity.taxProfileId())
                        .param("address", entity.registeredAddress())
                        .param("phone", entity.contactPhone())
                        .param("status", entity.status().name())
                        .param("expectedVersion", expectedVersion)
                        .param("now", timestamp(now))
                        .update()
                == 1;
    }

    public Optional<LegalEntity> find(UUID tenantId, UUID entityId) {
        if (!isWired()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + ENTITY_COLUMNS + """
                 FROM tenant.legal_entities
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", entityId)
                .query(JdbcLegalEntityStore::toEntity)
                .optional();
    }

    public List<LegalEntity> listForTenant(UUID tenantId) {
        if (!isWired()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + ENTITY_COLUMNS + """
                 FROM tenant.legal_entities
                 WHERE tenant_id = :tenantId
                 ORDER BY code
                """)
                .param("tenantId", tenantId)
                .query(JdbcLegalEntityStore::toEntity)
                .list();
    }

    // -------------------------------------------------------------- assignments

    public void insertAssignment(LocationFiscalAssignment assignment, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (
                    id, tenant_id, brand_id, location_id, legal_entity_id, effective_from,
                    effective_until, approved_by, approval_reference, version, created_at,
                    updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :entityId, :from, :until,
                    :approvedBy, :approvalReference, :version, :now, :now)
                """)
                .param("id", assignment.id())
                .param("tenantId", assignment.tenantId())
                .param("brandId", assignment.brandId())
                .param("locationId", assignment.locationId())
                .param("entityId", assignment.legalEntityId())
                .param("from", assignment.effectiveFrom())
                .param("until", assignment.effectiveUntil())
                .param("approvedBy", assignment.approvedBy())
                .param("approvalReference", assignment.approvalReference())
                .param("version", assignment.version())
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Ends the open assignment for a location on a given date.
     *
     * <p>Half-open, so the end date is the first day the successor applies and no
     * day belongs to two taxpayers. Conditional on the row still being open: a
     * second operator closing the same assignment finds nothing to close rather
     * than moving a date somebody else already set.
     *
     * @return the number of assignments closed — zero when the location had none
     *         open, which is the first assignment and not an error
     */
    public int closeOpenAssignment(UUID tenantId, UUID locationId, LocalDate endingOn, Instant now) {
        return jdbc.sql("""
                UPDATE tenant.location_fiscal_assignments
                SET effective_until = :endingOn,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND location_id = :locationId
                  AND effective_until IS NULL
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("endingOn", endingOn)
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Every assignment a location has ever had, newest first.
     *
     * <p>History, not a current-state read. "Which company were we when this
     * receipt was issued" is answered from this list, and a projection that keeps
     * only the live row cannot answer it at all.
     */
    public List<LocationFiscalAssignment> assignmentHistory(UUID tenantId, UUID locationId) {
        if (!isWired()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + ASSIGNMENT_COLUMNS + """
                 FROM tenant.location_fiscal_assignments
                 WHERE tenant_id = :tenantId AND location_id = :locationId
                 ORDER BY effective_from DESC
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcLegalEntityStore::toAssignment)
                .list();
    }

    /** Translates the constraints into the sentence each one is protecting. */
    public static RuntimeException explain(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("ex_location_fiscal_assignment_no_overlap")) {
            return new IllegalStateException("That location already has a legal entity assigned "
                    + "over part of this period; two would make two INNs correct at once");
        }
        if (message.contains("uq_legal_entity_tin")) {
            return new IllegalStateException(
                    "A legal entity with this taxpayer number already " + "exists for the tenant");
        }
        if (message.contains("uq_legal_entity_code")) {
            return new IllegalStateException("A legal entity with this code already exists for " + "the tenant");
        }
        if (message.contains("fk_location_fiscal_assignment_entity")) {
            return new IllegalArgumentException("That legal entity does not belong to this tenant");
        }
        if (message.contains("fk_location_fiscal_assignment_location")) {
            return new IllegalArgumentException("That location does not belong to this tenant");
        }
        return violation;
    }

    private static FiscalSeller toSeller(ResultSet row, int number) throws SQLException {
        return new FiscalSeller(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("code"),
                row.getString("legal_name"),
                row.getString("tin"),
                row.getBoolean("vat_registered"),
                row.getObject("tax_profile_id", UUID.class),
                OperatingUnitStatus.valueOf(row.getString("status")) == OperatingUnitStatus.ACTIVE,
                row.getObject("assignment_id", UUID.class),
                row.getInt("assignment_version"),
                row.getObject("effective_from", LocalDate.class),
                row.getObject("effective_until", LocalDate.class));
    }

    private static LegalEntity toEntity(ResultSet row, int number) throws SQLException {
        return LegalEntity.reconstitute(
                new LegalEntityId(row.getObject("id", UUID.class)),
                new TenantId(row.getObject("tenant_id", UUID.class)),
                row.getString("code"),
                row.getString("legal_name"),
                row.getString("short_name"),
                new TaxpayerNumber(row.getString("tin")),
                row.getBoolean("vat_registered"),
                row.getString("vat_certificate_reference"),
                row.getObject("tax_profile_id", UUID.class),
                row.getString("registered_address"),
                row.getString("contact_phone"),
                OperatingUnitStatus.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static LocationFiscalAssignment toAssignment(ResultSet row, int number) throws SQLException {
        return new LocationFiscalAssignment(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getObject("effective_from", LocalDate.class),
                row.getObject("effective_until", LocalDate.class),
                row.getString("approved_by"),
                row.getString("approval_reference"),
                row.getInt("version"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
