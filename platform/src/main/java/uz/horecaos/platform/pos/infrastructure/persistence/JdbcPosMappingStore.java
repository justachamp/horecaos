package uz.horecaos.platform.pos.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The tenant-facing mapping pane over {@code integration.provider_entity_mappings}
 * (ADR 0012/0026, gap-map row 10.8b).
 *
 * <p>Nothing in the codebase inserts a row into this table today — {@link
 * uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosTargetCatalog} and
 * {@link uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore}
 * only ever read it, and {@code JdbcPosApplyStore} only ever repoints or
 * retires a row a sync run's own apply step already found. This class is the
 * first writer, and it writes with {@code mapping_source = 'OPERATOR'} —
 * allowed by V0013's {@code CHECK} from the day that table was created, never
 * exercised until now.
 *
 * <p>{@link MappingEntityType#PRODUCT} stores {@code entity_type =
 * 'VARIANT_PARENT'}, the same string the catalog sync engine already reads —
 * see that enum's own doc for why. The other five values are new vocabulary
 * this wave introduces; nothing else in the codebase writes or reads them, so
 * there is no collision to avoid.
 */
@Component
public class JdbcPosMappingStore {

    private static final String CURSOR_SEPARATOR = "|";

    private final JdbcClient jdbc;

    public JdbcPosMappingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A binding's mappings for one entity type, optionally filtered by status, newest-updated first. */
    public List<MappingRow> list(
            UUID tenantId,
            UUID bindingId,
            MappingEntityType type,
            @Nullable String status,
            int limit,
            @Nullable String cursor) {

        CursorPosition position = cursor == null ? null : decodeCursor(cursor);

        StringBuilder sql = new StringBuilder("""
                SELECT id, installation_id, binding_id, entity_type, horecaos_entity_id,
                       external_entity_id, external_parent_id, status, mapping_source,
                       last_seen_at, version, created_at, updated_at
                  FROM integration.provider_entity_mappings
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId AND entity_type = :entityType
                """);
        if (status != null) {
            sql.append(" AND status = :status\n");
        }
        if (position != null) {
            sql.append(" AND (updated_at, id) < (:cursorUpdatedAt, :cursorId)\n");
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT :limit");

        var statement = jdbc.sql(sql.toString())
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("entityType", type.storedAs())
                .param("limit", limit);
        if (status != null) {
            statement = statement.param("status", status);
        }
        if (position != null) {
            statement = statement
                    .param("cursorUpdatedAt", OffsetDateTime.ofInstant(position.updatedAt(), ZoneOffset.UTC))
                    .param("cursorId", position.id());
        }
        return statement.query(JdbcPosMappingStore::toRow).list();
    }

    public Optional<MappingRow> find(UUID tenantId, UUID mappingId) {
        return jdbc.sql("""
                SELECT id, installation_id, binding_id, entity_type, horecaos_entity_id,
                       external_entity_id, external_parent_id, status, mapping_source,
                       last_seen_at, version, created_at, updated_at
                  FROM integration.provider_entity_mappings
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", mappingId)
                .query(JdbcPosMappingStore::toRow)
                .optional();
    }

    /** Whether an ACTIVE mapping already claims this external id or this HorecaOS id, for this binding and type. */
    public Optional<MappingRow> findActiveConflict(
            UUID tenantId, UUID bindingId, MappingEntityType type, UUID horecaosEntityId, String externalEntityId) {
        return jdbc.sql("""
                SELECT id, installation_id, binding_id, entity_type, horecaos_entity_id,
                       external_entity_id, external_parent_id, status, mapping_source,
                       last_seen_at, version, created_at, updated_at
                  FROM integration.provider_entity_mappings
                 WHERE tenant_id = :tenantId AND binding_id = :bindingId AND entity_type = :entityType
                   AND status = 'ACTIVE'
                   AND (external_entity_id = :externalId OR horecaos_entity_id = :horecaosId)
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("entityType", type.storedAs())
                .param("externalId", externalEntityId)
                .param("horecaosId", horecaosEntityId)
                .query(JdbcPosMappingStore::toRow)
                .optional();
    }

    /**
     * Creates an {@code OPERATOR}-sourced mapping, {@code ACTIVE} immediately: an
     * operator who paired these two by hand has already made the decision a
     * {@code DISCOVERED} mapping still waits on review for.
     *
     * @return the new mapping's id
     */
    public UUID create(
            UUID tenantId,
            UUID installationId,
            UUID bindingId,
            MappingEntityType type,
            UUID horecaosEntityId,
            String externalEntityId,
            @Nullable String externalParentId,
            Instant now) {

        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                     external_entity_id, external_parent_id, status, mapping_source,
                     version, created_at, updated_at)
                VALUES (:id, :tenantId, :installationId, :bindingId, :entityType, :horecaosId,
                        :externalId, :externalParentId, 'ACTIVE', 'OPERATOR',
                        0, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("bindingId", bindingId)
                .param("entityType", type.storedAs())
                .param("horecaosId", horecaosEntityId)
                .param("externalId", externalEntityId)
                .param("externalParentId", externalParentId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return id;
    }

    /** @return false when the mapping does not exist for this tenant, is already RETIRED, or {@code expectedVersion} is stale */
    public boolean retire(UUID tenantId, UUID mappingId, long expectedVersion, Instant now) {
        int updated = jdbc.sql("""
                UPDATE integration.provider_entity_mappings
                   SET status = 'RETIRED', version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion AND status <> 'RETIRED'
                """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("id", mappingId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated > 0;
    }

    // ------------------------------------------------------------------
    // Candidate sourcing for "list unmapped" and bulk auto-match. Each
    // entity type's HorecaOS-side aggregate lives in a different module's
    // schema; this class reads across them directly the same way
    // JdbcPosTargetCatalog already does for catalog.products, rather than
    // inventing a port per module for a read this narrow.
    // ------------------------------------------------------------------

    /** Latest run's staged, comparable, not-yet-mapped products for this binding — {@link MappingEntityType#PRODUCT}'s external side. */
    public List<ExternalCandidate> unmappedStagedProducts(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                WITH latest_run AS (
                    SELECT id FROM integration.pos_sync_runs
                     WHERE tenant_id = :tenantId AND binding_id = :bindingId
                     ORDER BY started_at DESC LIMIT 1
                )
                SELECT sp.external_entity_id, sp.name, sp.external_category_id
                  FROM integration.pos_staged_products sp
                  JOIN latest_run lr ON lr.id = sp.run_id
                 WHERE sp.tenant_id = :tenantId AND sp.comparable
                   AND NOT EXISTS (
                       SELECT 1 FROM integration.provider_entity_mappings m
                        WHERE m.tenant_id = :tenantId AND m.binding_id = :bindingId
                          AND m.entity_type = 'VARIANT_PARENT' AND m.status = 'ACTIVE'
                          AND m.external_entity_id = sp.external_entity_id)
                 ORDER BY sp.name
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> new ExternalCandidate(
                        row.getString("external_entity_id"),
                        row.getString("name"),
                        row.getString("external_category_id")))
                .list();
    }

    /** Not-yet-mapped brand products, for {@link MappingEntityType#PRODUCT}'s HorecaOS side (bulk auto-match). */
    public List<NamedCandidate> unmappedProducts(UUID tenantId, UUID bindingId, UUID brandId, String locale) {
        return jdbc.sql("""
                SELECT p.id, coalesce(t.name, p.code) AS name
                  FROM catalog.products p
             LEFT JOIN catalog.translations t
                     ON t.entity_type = 'PRODUCT' AND t.entity_id = p.id
                    AND t.locale = :locale AND t.tenant_id = p.tenant_id
                 WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM integration.provider_entity_mappings m
                        WHERE m.tenant_id = :tenantId AND m.binding_id = :bindingId
                          AND m.entity_type = 'VARIANT_PARENT' AND m.status = 'ACTIVE'
                          AND m.horecaos_entity_id = p.id)
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("brandId", brandId)
                .param("locale", locale)
                .query((row, number) -> new NamedCandidate(row.getObject("id", UUID.class), row.getString("name")))
                .list();
    }

    /**
     * Not-yet-mapped, active variants, for {@link MappingEntityType#VARIANT}'s
     * HorecaOS side (gap-map row 1.2i's fix path) — the exact granularity
     * {@code PosOrderExportService} exports an order line against, one level
     * finer than {@link MappingEntityType#PRODUCT}'s own {@code VARIANT_PARENT}.
     */
    public List<NamedCandidate> unmappedVariants(UUID tenantId, UUID bindingId, UUID brandId, String locale) {
        return jdbc.sql("""
                SELECT v.id, coalesce(t.name, v.sku, v.id::text) AS name
                  FROM catalog.variants v
             LEFT JOIN catalog.translations t
                     ON t.entity_type = 'VARIANT' AND t.entity_id = v.id
                    AND t.locale = :locale AND t.tenant_id = v.tenant_id
                 WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId AND v.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM integration.provider_entity_mappings m
                        WHERE m.tenant_id = :tenantId AND m.binding_id = :bindingId
                          AND m.entity_type = 'VARIANT' AND m.status = 'ACTIVE'
                          AND m.horecaos_entity_id = v.id)
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("brandId", brandId)
                .param("locale", locale)
                .query((row, number) -> new NamedCandidate(row.getObject("id", UUID.class), row.getString("name")))
                .list();
    }

    /**
     * Not-yet-mapped, active modifier options, for {@link
     * MappingEntityType#MODIFIER}'s HorecaOS side (gap-map row 1.2i's fix
     * path) — {@code catalog.modifier_options}, the same table {@code
     * PosOrderExportService} resolves a line's {@code modifierOptionIds}
     * against. Brand-scoped, like {@link #unmappedVariants}, rather than
     * folded into {@link #unmappedByTenant}: that helper has no parameter slot
     * for a second scoping id, only a literal filter clause, and a literal
     * cannot carry a caller-supplied brand id without building SQL by hand.
     */
    public List<NamedCandidate> unmappedModifierOptions(UUID tenantId, UUID bindingId, UUID brandId) {
        return jdbc.sql("""
                SELECT mo.id, mo.code AS name
                  FROM catalog.modifier_options mo
                 WHERE mo.tenant_id = :tenantId AND mo.brand_id = :brandId AND mo.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM integration.provider_entity_mappings m
                        WHERE m.tenant_id = :tenantId AND m.binding_id = :bindingId
                          AND m.entity_type = 'MODIFIER' AND m.status = 'ACTIVE'
                          AND m.horecaos_entity_id = mo.id)
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("brandId", brandId)
                .query((row, number) -> new NamedCandidate(row.getObject("id", UUID.class), row.getString("name")))
                .list();
    }

    /** Not-yet-mapped, active payment methods, for {@link MappingEntityType#PAYMENT_TYPE}'s HorecaOS side. */
    public List<NamedCandidate> unmappedPaymentMethods(UUID tenantId, UUID bindingId) {
        return unmappedByTenant(
                tenantId,
                bindingId,
                MappingEntityType.PAYMENT_TYPE,
                "payments.payment_methods",
                "display_name",
                "status = 'ACTIVE'");
    }

    /** Not-yet-mapped, active promotions, for {@link MappingEntityType#DISCOUNT}'s HorecaOS side. */
    public List<NamedCandidate> unmappedDiscounts(UUID tenantId, UUID bindingId, UUID brandId) {
        return jdbc.sql("""
                SELECT pr.id, pr.name
                  FROM pricing.promotions pr
                 WHERE pr.tenant_id = :tenantId AND pr.brand_id = :brandId AND pr.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM integration.provider_entity_mappings m
                        WHERE m.tenant_id = :tenantId AND m.binding_id = :bindingId
                          AND m.entity_type = 'DISCOUNT' AND m.status = 'ACTIVE'
                          AND m.horecaos_entity_id = pr.id)
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("brandId", brandId)
                .query((row, number) -> new NamedCandidate(row.getObject("id", UUID.class), row.getString("name")))
                .list();
    }

    /** Not-yet-mapped, active couriers, for {@link MappingEntityType#COURIER}'s HorecaOS side. Never the courier's protected name (ADR 0029) — {@code display_reference} only. */
    public List<NamedCandidate> unmappedCouriers(UUID tenantId, UUID bindingId) {
        return unmappedByTenant(
                tenantId,
                bindingId,
                MappingEntityType.COURIER,
                "fulfillment.couriers",
                "display_reference",
                "status = 'ACTIVE'");
    }

    /** Not-yet-mapped, active cancellation reasons, for {@link MappingEntityType#CANCELLATION_REASON}'s HorecaOS side. */
    public List<NamedCandidate> unmappedCancellationReasons(UUID tenantId, UUID bindingId) {
        return unmappedByTenant(
                tenantId,
                bindingId,
                MappingEntityType.CANCELLATION_REASON,
                "ordering.order_outcome_reasons",
                "internal_name",
                "status = 'ACTIVE' AND kind = 'CANCELLATION'");
    }

    /** Not-yet-mapped, active sales channels, for {@link MappingEntityType#CHANNEL_POS_CODE}'s HorecaOS side. */
    public List<NamedCandidate> unmappedSalesChannels(UUID tenantId, UUID bindingId) {
        return unmappedByTenant(
                tenantId,
                bindingId,
                MappingEntityType.CHANNEL_POS_CODE,
                "tenant.sales_channels",
                "display_name",
                "status = 'ACTIVE'");
    }

    /**
     * The shared shape of five of the six candidate reads: one tenant-scoped
     * table, one name column, an optional extra filter, an anti-join against
     * this binding's own {@code ACTIVE} mappings.
     *
     * @param table  a literal from this file's own call sites, never a caller's
     *               string — a table name cannot be a bound parameter
     * @param filter an extra {@code AND} clause, or null for none
     */
    private List<NamedCandidate> unmappedByTenant(
            UUID tenantId,
            UUID bindingId,
            MappingEntityType type,
            String table,
            String nameColumn,
            @Nullable String filter) {
        String sql = """
                SELECT src.id, src.%s AS name
                  FROM %s src
                 WHERE src.tenant_id = :tenantId
                   %s
                   AND NOT EXISTS (
                       SELECT 1 FROM integration.provider_entity_mappings m
                        WHERE m.tenant_id = :tenantId AND m.binding_id = :bindingId
                          AND m.entity_type = :entityType AND m.status = 'ACTIVE'
                          AND m.horecaos_entity_id = src.id)
                """.formatted(nameColumn, table, filter == null ? "" : "AND src." + filter);
        return jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("entityType", type.storedAs())
                .query((row, number) -> new NamedCandidate(row.getObject("id", UUID.class), row.getString("name")))
                .list();
    }

    /**
     * The HorecaOS-side display name for a batch of already-mapped ids, for the
     * pane's linked-pairs table — {@link #list}'s own rows carry only ids, and
     * a mapping an operator cannot read the name of is not reviewable.
     *
     * <p>The external side has no equivalent: the provider's own code is what
     * a mapping stores, and resolving a display name for it would mean calling
     * the adapter (or trusting a staged snapshot that may have rolled past
     * this id) on every page render. The table shows the provider's code
     * as-is instead, which is at minimum what an operator needs to cross-check
     * against the till's own admin screen.
     *
     * @param brandId required for {@link MappingEntityType#PRODUCT}, {@link
     *                MappingEntityType#VARIANT} and {@link MappingEntityType#MODIFIER}
     *                (each brand-owned data); null is fine for the rest, and
     *                for those three a null brandId simply resolves nothing
     *                rather than reaching across the whole tenant
     */
    public Map<UUID, String> resolveHorecaosNames(
            UUID tenantId, @Nullable UUID brandId, MappingEntityType type, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return switch (type) {
            case PRODUCT -> resolveProductNames(tenantId, brandId, ids);
            case PAYMENT_TYPE -> resolveById(tenantId, "payments.payment_methods", "display_name", ids);
            case DISCOUNT -> resolveById(tenantId, "pricing.promotions", "name", ids);
            case COURIER -> resolveById(tenantId, "fulfillment.couriers", "display_reference", ids);
            case CANCELLATION_REASON -> resolveById(tenantId, "ordering.order_outcome_reasons", "internal_name", ids);
            case CHANNEL_POS_CODE -> resolveById(tenantId, "tenant.sales_channels", "display_name", ids);
            case VARIANT -> resolveVariantNames(tenantId, brandId, ids);
            case MODIFIER -> resolveModifierNames(tenantId, brandId, ids);
        };
    }

    /**
     * Whether {@code horecaosEntityId} names a real row of this {@code type}
     * in this tenant (and, for {@link MappingEntityType#PRODUCT}, {@link
     * MappingEntityType#VARIANT} and {@link MappingEntityType#MODIFIER}, this
     * brand) — the same per-type resolution {@link #resolveHorecaosNames}
     * already knows, run for one id rather than a batch so {@code create} can
     * refuse a nonexistent, cross-tenant, or (for those three brand-owned
     * types) cross-brand id before it ever reaches the INSERT.
     */
    public boolean horecaosEntityExists(
            UUID tenantId, @Nullable UUID brandId, MappingEntityType type, UUID horecaosEntityId) {
        return !resolveHorecaosNames(tenantId, brandId, type, Set.of(horecaosEntityId))
                .isEmpty();
    }

    private Map<UUID, String> resolveProductNames(UUID tenantId, @Nullable UUID brandId, Set<UUID> ids) {
        if (brandId == null) {
            return Map.of();
        }
        return jdbc
                .sql("""
                SELECT p.id, coalesce(t.name, p.code) AS name
                  FROM catalog.products p
             LEFT JOIN catalog.translations t
                     ON t.entity_type = 'PRODUCT' AND t.entity_id = p.id
                    AND t.locale = 'uz-UZ' AND t.tenant_id = p.tenant_id
                 WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(row.getObject("id", UUID.class), row.getString("name")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<UUID, String> resolveVariantNames(UUID tenantId, @Nullable UUID brandId, Set<UUID> ids) {
        if (brandId == null) {
            return Map.of();
        }
        return jdbc
                .sql("""
                SELECT v.id, coalesce(t.name, v.sku, v.id::text) AS name
                  FROM catalog.variants v
             LEFT JOIN catalog.translations t
                     ON t.entity_type = 'VARIANT' AND t.entity_id = v.id
                    AND t.locale = 'uz-UZ' AND t.tenant_id = v.tenant_id
                 WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId AND v.id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(row.getObject("id", UUID.class), row.getString("name")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Brand-scoped, exactly like {@link #resolveVariantNames} and for the same
     * reason: {@code catalog.modifier_options} is brand-owned data (V0016,
     * {@code brand_id uuid NOT NULL}), and a binding always belongs to one
     * specific brand, so a plain tenant-wide {@link #resolveById} would let a
     * {@code MODIFIER} mapping resolve -- and {@link #horecaosEntityExists}
     * accept -- a modifier option from a brand other than the binding's own.
     */
    private Map<UUID, String> resolveModifierNames(UUID tenantId, @Nullable UUID brandId, Set<UUID> ids) {
        if (brandId == null) {
            return Map.of();
        }
        return jdbc
                .sql("""
                SELECT id, code AS name FROM catalog.modifier_options
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(row.getObject("id", UUID.class), row.getString("name")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * @param table      a literal from this file's own call sites, never a caller's string
     * @param nameColumn likewise
     */
    private Map<UUID, String> resolveById(UUID tenantId, String table, String nameColumn, Set<UUID> ids) {
        String sql = "SELECT id, %s AS name FROM %s WHERE tenant_id = :tenantId AND id = ANY(:ids)"
                .formatted(nameColumn, table);
        return jdbc
                .sql(sql)
                .param("tenantId", tenantId)
                .param("ids", ids.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(row.getObject("id", UUID.class), row.getString("name")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** {@code cursor} for the mapping right after {@code row} in {@link #list}'s own order. */
    public static String cursorFor(MappingRow row) {
        return row.updatedAt() + CURSOR_SEPARATOR + row.id();
    }

    private static CursorPosition decodeCursor(String raw) {
        int separator = raw.lastIndexOf(CURSOR_SEPARATOR);
        if (separator <= 0 || separator == raw.length() - 1) {
            throw malformedCursor();
        }
        try {
            Instant updatedAt = Instant.parse(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new CursorPosition(updatedAt, id);
        } catch (DateTimeParseException | IllegalArgumentException malformed) {
            throw malformedCursor();
        }
    }

    private static ApiException malformedCursor() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Malformed cursor");
    }

    private static MappingRow toRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        var lastSeenAt = row.getObject("last_seen_at", OffsetDateTime.class);
        return new MappingRow(
                row.getObject("id", UUID.class),
                row.getObject("installation_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getString("entity_type"),
                row.getObject("horecaos_entity_id", UUID.class),
                row.getString("external_entity_id"),
                row.getString("external_parent_id"),
                row.getString("status"),
                row.getString("mapping_source"),
                lastSeenAt == null ? null : lastSeenAt.toInstant(),
                row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private record CursorPosition(Instant updatedAt, UUID id) {}

    public record MappingRow(
            UUID id,
            UUID installationId,
            UUID bindingId,
            String entityType,
            UUID horecaosEntityId,
            String externalEntityId,
            @Nullable String externalParentId,
            String status,
            String mappingSource,
            @Nullable Instant lastSeenAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    /** One provider-side candidate, not yet mapped. */
    public record ExternalCandidate(
            String externalId,
            @Nullable String name,
            @Nullable String externalParentId) {}

    /** One HorecaOS-side candidate, not yet mapped. */
    public record NamedCandidate(UUID id, @Nullable String name) {}
}
