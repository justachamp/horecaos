package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.domain.PlanVersion;

/**
 * The plan catalogue's persistence (ADR 0021).
 *
 * <p>Activation is a conditional UPDATE naming the state it expects, like every
 * other state change in this codebase. Two operators activating the same draft
 * at the same moment settle at one activation and one conflict, rather than at
 * two audit records claiming to be the first.
 */
@Repository
public class JdbcPlanStore {

    private final JdbcClient jdbc;

    public JdbcPlanStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPlan(UUID id, String code, String name, Instant now) {
        jdbc.sql("""
                INSERT INTO commercial.plans (id, code, name, status, version, created_at, updated_at)
                VALUES (:id, :code, :name, 'DRAFT', 0, :now, :now)
                """)
                .param("id", id).param("code", code).param("name", name)
                .param("now", utc(now))
                .update();
    }

    public Optional<UUID> findPlanIdByCode(String code) {
        return jdbc.sql("SELECT id FROM commercial.plans WHERE code = :code")
                .param("code", code)
                .query(UUID.class)
                .optional();
    }

    public int nextVersionNumber(UUID planId) {
        return jdbc.sql("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                  FROM commercial.plan_versions WHERE plan_id = :planId
                """)
                .param("planId", planId)
                .query(Integer.class)
                .single();
    }

    public void insertPlanVersion(UUID id, UUID planId, int versionNumber, String currency,
            long priceMinor, String billingPeriod, String termsReference, String createdBy,
            Instant now) {

        jdbc.sql("""
                INSERT INTO commercial.plan_versions (
                    id, plan_id, version_number, currency, price_minor, billing_period,
                    status, terms_reference, created_by, created_at, updated_at)
                VALUES (
                    :id, :planId, :versionNumber, :currency, :priceMinor, :billingPeriod,
                    'DRAFT', :termsReference, :createdBy, :now, :now)
                """)
                .param("id", id).param("planId", planId).param("versionNumber", versionNumber)
                .param("currency", currency).param("priceMinor", priceMinor)
                .param("billingPeriod", billingPeriod).param("termsReference", termsReference)
                .param("createdBy", createdBy).param("now", utc(now))
                .update();
    }

    /**
     * Writes one entitlement onto a draft version.
     *
     * <p>An upsert, because authoring a plan is iterative until it is activated.
     * The trigger on the table refuses the same statement once the version has
     * been activated, so iteration cannot continue past the point where a tenant
     * could already be relying on the terms.
     */
    public void upsertPlanEntitlement(UUID planVersionId, String key, PlanEntitlement entitlement) {
        boolean counted = entitlement.integerValue() != null;
        jdbc.sql("""
                INSERT INTO commercial.plan_entitlements (
                    plan_version_id, entitlement_key, value_type, boolean_value, integer_value,
                    enforcement_mode, reset_period, warn_threshold_bps, overage_unit_price_minor)
                VALUES (
                    :planVersionId, :key, :valueType, :booleanValue, :integerValue,
                    :mode, :resetPeriod, :warnThreshold, :overagePrice)
                ON CONFLICT (plan_version_id, entitlement_key) DO UPDATE SET
                    value_type = EXCLUDED.value_type,
                    boolean_value = EXCLUDED.boolean_value,
                    integer_value = EXCLUDED.integer_value,
                    enforcement_mode = EXCLUDED.enforcement_mode,
                    reset_period = EXCLUDED.reset_period,
                    warn_threshold_bps = EXCLUDED.warn_threshold_bps,
                    overage_unit_price_minor = EXCLUDED.overage_unit_price_minor
                """)
                .param("planVersionId", planVersionId)
                .param("key", key)
                .param("valueType", counted ? "INTEGER" : "BOOLEAN")
                .param("booleanValue", entitlement.booleanValue())
                .param("integerValue", entitlement.integerValue())
                .param("mode", entitlement.enforcementMode().name())
                .param("resetPeriod", entitlement.resetPeriod().name())
                .param("warnThreshold", entitlement.warnThresholdBasisPoints())
                .param("overagePrice", entitlement.overageUnitPriceMinor())
                .update();
    }

    /**
     * Makes a draft version live.
     *
     * @return true when this call performed the activation
     */
    public boolean activate(UUID planVersionId, String approvedBy, Instant effectiveFrom, Instant now) {
        int updated = jdbc.sql("""
                UPDATE commercial.plan_versions
                   SET status = 'ACTIVE', approved_by = :approvedBy, activated_at = :now,
                       effective_from = COALESCE(effective_from, :effectiveFrom), updated_at = :now
                 WHERE id = :id AND activated_at IS NULL AND status = 'DRAFT'
                """)
                .param("id", planVersionId).param("approvedBy", approvedBy)
                .param("effectiveFrom", utc(effectiveFrom)).param("now", utc(now))
                .update();

        if (updated == 1) {
            jdbc.sql("""
                    UPDATE commercial.plans SET status = 'ACTIVE', version = version + 1,
                           updated_at = :now
                     WHERE id = (SELECT plan_id FROM commercial.plan_versions WHERE id = :id)
                    """)
                    .param("id", planVersionId).param("now", utc(now))
                    .update();
        }
        return updated == 1;
    }

    public Optional<PlanVersion> findVersion(UUID planVersionId) {
        return jdbc.sql(SELECT_VERSION + " WHERE v.id = :id")
                .param("id", planVersionId)
                .query(JdbcPlanStore::mapVersion)
                .optional();
    }

    /** Every activated version, newest per plan first. The control-plane price list. */
    public List<PlanVersion> listActiveVersions() {
        return jdbc.sql(SELECT_VERSION + """
                 WHERE v.status = 'ACTIVE' AND v.activated_at IS NOT NULL
                 ORDER BY p.code, v.version_number DESC
                """)
                .query(JdbcPlanStore::mapVersion)
                .list();
    }

    public Map<String, PlanEntitlement> entitlementsOf(UUID planVersionId) {
        List<PlanEntitlement> rows = jdbc.sql("""
                SELECT entitlement_key, value_type, boolean_value, integer_value,
                       enforcement_mode, reset_period, warn_threshold_bps, overage_unit_price_minor
                  FROM commercial.plan_entitlements
                 WHERE plan_version_id = :planVersionId
                 ORDER BY entitlement_key
                """)
                .param("planVersionId", planVersionId)
                .query((row, number) -> new PlanEntitlement(
                        row.getString("entitlement_key"),
                        // getLong answers 0 for SQL NULL, which would turn "no
                        // limit stated" into "a limit of zero" and refuse
                        // everything the moment enforcement was switched on.
                        row.getObject("integer_value", Long.class),
                        row.getObject("boolean_value", Boolean.class),
                        EnforcementMode.valueOf(row.getString("enforcement_mode")),
                        ResetPeriod.valueOf(row.getString("reset_period")),
                        row.getObject("warn_threshold_bps", Integer.class),
                        row.getObject("overage_unit_price_minor", Long.class)))
                .list();

        Map<String, PlanEntitlement> byKey = new LinkedHashMap<>();
        rows.forEach(row -> byKey.put(row.entitlementKey(), row));
        return Map.copyOf(byKey);
    }

    private static final String SELECT_VERSION = """
            SELECT v.id, v.plan_id, p.code AS plan_code, v.version_number, v.currency,
                   v.price_minor, v.billing_period, v.status, v.terms_reference,
                   v.created_by, v.approved_by, v.activated_at
              FROM commercial.plan_versions v
              JOIN commercial.plans p ON p.id = v.plan_id
            """;

    private static PlanVersion mapVersion(java.sql.ResultSet row, int number)
            throws java.sql.SQLException {
        OffsetDateTime activated = row.getObject("activated_at", OffsetDateTime.class);
        return new PlanVersion(
                row.getObject("id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getString("plan_code"),
                row.getInt("version_number"),
                row.getString("currency"),
                row.getLong("price_minor"),
                row.getString("billing_period"),
                row.getString("status"),
                row.getString("terms_reference"),
                row.getString("created_by"),
                row.getString("approved_by"),
                activated == null ? null : activated.toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
