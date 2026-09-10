package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.commercial.domain.SellableModule;
import uz.horecaos.platform.commercial.domain.TenantModule;

/**
 * Modules and the tenants that have them (ADR 0087).
 *
 * <p>Every state change is a conditional UPDATE naming the state it expects,
 * so two people acting at once settle at one outcome and the loser is told.
 */
@Repository
public class JdbcModuleStore {

    private final JdbcClient jdbc;

    public JdbcModuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SellableModule module, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", module.id());
        params.put("code", module.code());
        params.put("name", module.name());
        params.put("description", module.description());
        params.put("billingUnit", module.billingUnit().name());
        params.put("currency", module.currency());
        params.put("unitPriceMinor", module.unitPriceMinor());
        params.put("featureKeys", module.featureKeys().toArray(new String[0]));
        params.put("createdBy", module.createdBy());
        params.put("now", utc(now));
        jdbc.sql("""
                INSERT INTO commercial.modules (
                    id, code, name, description, billing_unit, currency, unit_price_minor,
                    feature_keys, status, created_by, created_at, updated_at)
                VALUES (:id, :code, :name, :description, :billingUnit, :currency, :unitPriceMinor,
                    :featureKeys, 'DRAFT', :createdBy, :now, :now)
                """).params(params).update();
    }

    public Optional<SellableModule> find(UUID id) {
        return jdbc.sql(SELECT_MODULE + " WHERE id = :id")
                .param("id", id)
                .query(JdbcModuleStore::module)
                .optional();
    }

    public Optional<SellableModule> findByCode(String code) {
        return jdbc.sql(SELECT_MODULE + " WHERE code = :code")
                .param("code", code)
                .query(JdbcModuleStore::module)
                .optional();
    }

    /** Every module, drafts and retired ones included, by code. */
    public List<SellableModule> list() {
        return jdbc.sql(SELECT_MODULE + " ORDER BY code")
                .query(JdbcModuleStore::module)
                .list();
    }

    /** DRAFT to ACTIVE; false when it was not a draft any more. */
    public boolean activate(UUID id, String approvedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.modules
                           SET status = 'ACTIVE', approved_by = :approvedBy, activated_at = :now,
                               updated_at = :now
                         WHERE id = :id AND status = 'DRAFT'
                        """)
                        .param("id", id)
                        .param("approvedBy", approvedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** ACTIVE to RETIRED; false when it was not on sale. */
    public boolean retire(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.modules
                           SET status = 'RETIRED', retired_at = :now, updated_at = :now
                         WHERE id = :id AND status = 'ACTIVE'
                        """).param("id", id).param("now", utc(now)).update() == 1;
    }

    // -------------------------------------------------------- tenant modules

    public void insertTenantModule(TenantModule module) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", module.id());
        params.put("tenantId", module.tenantId());
        params.put("moduleId", module.moduleId());
        params.put("quantity", module.quantity());
        params.put("startedAt", utc(module.startedAt()));
        params.put("startedBy", module.startedBy());
        params.put("startReason", module.startReason());
        jdbc.sql("""
                INSERT INTO commercial.tenant_modules (
                    id, tenant_id, module_id, quantity, started_at, started_by, start_reason)
                VALUES (:id, :tenantId, :moduleId, :quantity, :startedAt, :startedBy, :startReason)
                """).params(params).update();
    }

    /** Ends a live module; false when it had already ended. */
    public boolean endTenantModule(UUID tenantId, UUID id, String endedBy, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.tenant_modules
                           SET ended_at = :now, ended_by = :endedBy, end_reason = :reason
                         WHERE tenant_id = :tenantId AND id = :id AND ended_at IS NULL
                        """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("endedBy", endedBy)
                        .param("reason", reason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public Optional<TenantModule> findTenantModule(UUID tenantId, UUID id) {
        return jdbc.sql(SELECT_TENANT_MODULE + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(JdbcModuleStore::tenantModule)
                .optional();
    }

    /** Every module the tenant has had, live ones first, newest first within each. */
    public List<TenantModule> tenantModules(UUID tenantId) {
        return jdbc.sql(SELECT_TENANT_MODULE + """
                         WHERE tenant_id = :tenantId
                         ORDER BY (ended_at IS NULL) DESC, started_at DESC
                        """)
                .param("tenantId", tenantId)
                .query(JdbcModuleStore::tenantModule)
                .list();
    }

    /** The modules live at any moment of {@code [start, end)}, for a statement. */
    public List<TenantModule> overlapping(UUID tenantId, Instant start, Instant end) {
        return jdbc.sql(SELECT_TENANT_MODULE + """
                         WHERE tenant_id = :tenantId AND started_at < :end
                           AND (ended_at IS NULL OR ended_at > :start)
                         ORDER BY started_at
                        """)
                .param("tenantId", tenantId)
                .param("start", utc(start))
                .param("end", utc(end))
                .query(JdbcModuleStore::tenantModule)
                .list();
    }

    /**
     * The feature entitlements the tenant's live modules switch on.
     *
     * <p>A retired module still counts for a tenant that has it: retiring stops
     * a sale, it does not take back one already made.
     */
    public Set<String> liveFeatureKeys(UUID tenantId) {
        return new LinkedHashSet<>(
                jdbc.sql("""
                        SELECT DISTINCT unnest(m.feature_keys) AS feature_key
                          FROM commercial.tenant_modules tm
                          JOIN commercial.modules m ON m.id = tm.module_id
                         WHERE tm.tenant_id = :tenantId AND tm.ended_at IS NULL
                           AND m.activated_at IS NOT NULL
                        """).param("tenantId", tenantId).query(String.class).list());
    }

    // ------------------------------------------------------------- mapping

    private static final String SELECT_MODULE = """
            SELECT id, code, name, description, billing_unit, currency, unit_price_minor,
                   feature_keys, status, created_by, approved_by, activated_at, retired_at
              FROM commercial.modules
            """;

    private static final String SELECT_TENANT_MODULE = """
            SELECT id, tenant_id, module_id, quantity, started_at, started_by, start_reason,
                   ended_at, ended_by, end_reason
              FROM commercial.tenant_modules
            """;

    private static SellableModule module(ResultSet row, int number) throws SQLException {
        return new SellableModule(
                row.getObject("id", UUID.class),
                row.getString("code"),
                row.getString("name"),
                row.getString("description"),
                BillingUnit.valueOf(row.getString("billing_unit")),
                row.getString("currency"),
                row.getLong("unit_price_minor"),
                strings(row.getArray("feature_keys")),
                row.getString("status"),
                row.getString("created_by"),
                row.getString("approved_by"),
                instant(row, "activated_at"),
                instant(row, "retired_at"));
    }

    private static TenantModule tenantModule(ResultSet row, int number) throws SQLException {
        return new TenantModule(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("module_id", UUID.class),
                row.getObject("quantity", Integer.class),
                Objects.requireNonNull(instant(row, "started_at"), "started_at is NOT NULL"),
                row.getString("started_by"),
                row.getString("start_reason"),
                instant(row, "ended_at"),
                row.getString("ended_by"),
                row.getString("end_reason"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] elements = (Object[]) array.getArray();
        return java.util.Arrays.stream(elements).map(String::valueOf).toList();
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
