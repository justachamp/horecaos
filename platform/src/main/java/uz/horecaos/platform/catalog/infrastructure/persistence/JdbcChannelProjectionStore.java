package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;

/**
 * What a marketplace projection preview reads beside the catalog itself
 * (ADR 0138).
 *
 * <p>Three small groups, all of them questions about <em>one channel</em>:
 * which branches sell on it, which marketplace binding sits at a branch, and the
 * channel-scoped media overrides.
 *
 * <p>The first two read {@code tenant.*} and {@code integration.*} directly in
 * SQL, the same cross-schema-by-raw-SQL shape {@code JdbcCatalogTenantContext}
 * already uses for {@code tenant.locations.timezone}: a plain-SQL read across
 * schemas is not a Java import across module boundaries, and the alternative —
 * a port in each of two modules for two columns — is a seam with no second
 * implementation behind it. Every query carries the tenant predicate: a channel,
 * location or binding id is a UUID a client supplies, and a read keyed on the id
 * alone would hand back another tenant's row.
 */
@Repository
public class JdbcChannelProjectionStore {

    private final JdbcClient jdbc;

    public JdbcChannelProjectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------ branches on a channel

    /**
     * The brand's branches that sell on this channel right now
     * ({@code tenant.sales_channel_locations}, ADR 0036) — the locations the
     * channel would actually be served from.
     */
    public List<UUID> activeLocationsOfChannel(UUID tenantId, UUID brandId, UUID channelId) {
        return jdbc.sql("""
                SELECT scl.location_id
                FROM tenant.sales_channel_locations scl
                JOIN tenant.locations l ON l.tenant_id = scl.tenant_id AND l.id = scl.location_id
                WHERE scl.tenant_id = :tenantId AND scl.channel_id = :channelId
                  AND scl.status = 'ACTIVE' AND l.brand_id = :brandId
                ORDER BY scl.location_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channelId", channelId)
                .query(UUID.class)
                .list();
    }

    /**
     * The same branches as {@link #activeLocationsOfChannel}, each with the name the tenant gave it --
     * what a console labels a branch picker with. A binding's name cannot: a brand-wide binding gives
     * every branch the same one.
     */
    public List<BranchRow> activeBranchesOfChannel(UUID tenantId, UUID brandId, UUID channelId) {
        return jdbc.sql("""
                SELECT scl.location_id, l.display_name
                FROM tenant.sales_channel_locations scl
                JOIN tenant.locations l ON l.tenant_id = scl.tenant_id AND l.id = scl.location_id
                WHERE scl.tenant_id = :tenantId AND scl.channel_id = :channelId
                  AND scl.status = 'ACTIVE' AND l.brand_id = :brandId
                ORDER BY scl.location_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channelId", channelId)
                .query((row, number) ->
                        new BranchRow(row.getObject("location_id", UUID.class), row.getString("display_name")))
                .list();
    }

    /** A branch a channel sells at and its name. */
    public record BranchRow(UUID locationId, String displayName) {}

    public boolean locationBelongsToBrand(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM tenant.locations
                    WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :locationId)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query(Boolean.class)
                .single();
    }

    // -------------------------------------------------- marketplace bindings

    /**
     * A binding by its own id, when it is a binding of a {@code MARKETPLACE}
     * installation of this tenant. Any other category is "no such binding" here:
     * a ruleset is a marketplace concept and a payment or POS binding has none.
     */
    public Optional<MarketplaceBindingRow> marketplaceBinding(UUID tenantId, UUID bindingId) {
        return jdbc.sql(BINDING_SELECT + """
                WHERE b.tenant_id = :tenantId AND b.id = :bindingId
                  AND i.provider_category = 'MARKETPLACE'
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query(JdbcChannelProjectionStore::mapBinding)
                .optional();
    }

    /**
     * The binding of this channel's installation that covers a branch: the
     * branch's own binding if it has one, else the brand-wide one, preferring an
     * {@code ACTIVE} binding over a draft or suspended one — the same
     * specificity order {@code ProviderInstallationLookup#bindingForInstallation}
     * resolves an aggregator order by.
     */
    public Optional<MarketplaceBindingRow> bindingCovering(
            UUID tenantId, UUID installationId, UUID brandId, UUID locationId) {
        return jdbc.sql(BINDING_SELECT + """
                WHERE b.tenant_id = :tenantId AND b.installation_id = :installationId
                  AND i.provider_category = 'MARKETPLACE'
                  AND (b.location_id = :locationId OR (b.location_id IS NULL AND b.brand_id = :brandId))
                ORDER BY (b.status = 'ACTIVE') DESC, (b.location_id IS NOT NULL) DESC, b.priority ASC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query(JdbcChannelProjectionStore::mapBinding)
                .optional();
    }

    /** Every marketplace binding of this installation that belongs to the brand. */
    public List<MarketplaceBindingRow> bindingsOfInstallation(UUID tenantId, UUID installationId, UUID brandId) {
        return jdbc.sql(BINDING_SELECT + """
                WHERE b.tenant_id = :tenantId AND b.installation_id = :installationId
                  AND i.provider_category = 'MARKETPLACE' AND b.brand_id = :brandId
                ORDER BY b.location_id NULLS FIRST, b.priority, b.id
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .query(JdbcChannelProjectionStore::mapBinding)
                .list();
    }

    private static final String BINDING_SELECT = """
            SELECT b.id, b.installation_id, b.brand_id, b.location_id, b.status,
                   b.marketplace_ruleset_code, i.provider_type, i.display_name
            FROM integration.bindings b
            JOIN integration.installations i
              ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
            """;

    private static MarketplaceBindingRow mapBinding(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new MarketplaceBindingRow(
                row.getObject("id", UUID.class),
                row.getObject("installation_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("status"),
                row.getString("marketplace_ruleset_code"),
                row.getString("provider_type"),
                row.getString("display_name"));
    }

    /**
     * One marketplace binding, as far as a preview needs to know it.
     *
     * @param locationId null for a brand-wide binding
     * @param rulesetCode null when the binding names no ruleset: the universal
     *     rules alone apply
     * @param providerType a catalogue value, not a credential
     * @param displayName what the tenant typed when it registered the installation
     */
    public record MarketplaceBindingRow(
            UUID bindingId,
            UUID installationId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String status,
            @Nullable String rulesetCode,
            String providerType,
            String displayName) {}

    // ------------------------------------------------------ media overrides

    /** Every override this brand has written for this channel, in display order within each entity. */
    public List<MediaOverrideRow> mediaOverrides(UUID tenantId, UUID brandId, UUID channelId) {
        return jdbc.sql("""
                SELECT entity_type, entity_id, media_asset_id, role, sort_order, version
                FROM catalog.channel_media_overrides
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel_id = :channelId
                ORDER BY entity_type, entity_id, (role = 'PRIMARY') DESC, sort_order, media_asset_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channelId", channelId)
                .query(JdbcChannelProjectionStore::mapOverride)
                .list();
    }

    public List<MediaOverrideRow> mediaOverridesFor(
            UUID tenantId, UUID brandId, UUID channelId, EntityType entityType, UUID entityId) {
        return jdbc.sql("""
                SELECT entity_type, entity_id, media_asset_id, role, sort_order, version
                FROM catalog.channel_media_overrides
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel_id = :channelId
                  AND entity_type = :entityType AND entity_id = :entityId
                ORDER BY (role = 'PRIMARY') DESC, sort_order, media_asset_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channelId", channelId)
                .param("entityType", entityType.name())
                .param("entityId", entityId)
                .query(JdbcChannelProjectionStore::mapOverride)
                .list();
    }

    /**
     * Makes two writers of one entity's override set on one channel run one after the other, for
     * the length of the surrounding transaction ({@code pg_advisory_xact_lock}: released at commit
     * or rollback).
     *
     * <p>A replace is a delete and some inserts. Two writers of a set that does not exist yet both
     * delete nothing and both insert, and the loser is refused by {@code
     * ux_channel_media_override_primary} only after the winner commits -- as a constraint violation
     * the caller cannot tell from a fault. Under this lock the second writer reads the set the first
     * left, so its expected version is wrong and it is told so.
     */
    public void lockMediaOverrideSet(UUID tenantId, UUID channelId, EntityType entityType, UUID entityId) {
        String lockKey =
                "channel_media_override|%s|%s|%s|%s".formatted(tenantId, channelId, entityType.name(), entityId);
        // The row mapper never reads the void column; consuming the one row is what waits for the lock.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", lockKey)
                .query((row, number) -> Boolean.TRUE)
                .list();
    }

    /**
     * Replaces one entity's whole override set on one channel — the set a
     * channel shows instead of the entity's own images. An empty list removes
     * every override and the entity goes back to its defaults.
     *
     * <p>The whole set every time, never a delta, matching how the console's
     * photo editor saves: what is on the screen is what is stored. Every row of
     * the new set carries {@code previous highest version + 1}, so the set's
     * version moves on each replace.
     *
     * @return the rows that were there before, for the audit diff
     */
    public List<MediaOverrideRow> replaceMediaOverrides(
            UUID tenantId,
            UUID brandId,
            UUID channelId,
            EntityType entityType,
            UUID entityId,
            List<MediaOverrideRow> replacement) {
        List<MediaOverrideRow> previous = mediaOverridesFor(tenantId, brandId, channelId, entityType, entityId);
        int nextVersion =
                previous.stream().mapToInt(MediaOverrideRow::version).max().orElse(0) + 1;

        jdbc.sql("""
                DELETE FROM catalog.channel_media_overrides
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel_id = :channelId
                  AND entity_type = :entityType AND entity_id = :entityId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channelId", channelId)
                .param("entityType", entityType.name())
                .param("entityId", entityId)
                .update();

        for (MediaOverrideRow row : replacement) {
            jdbc.sql("""
                    INSERT INTO catalog.channel_media_overrides (
                        tenant_id, brand_id, channel_id, entity_type, entity_id, role,
                        media_asset_id, sort_order, version)
                    VALUES (:tenantId, :brandId, :channelId, :entityType, :entityId, :role,
                            :assetId, :sortOrder, :version)
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("channelId", channelId)
                    .param("entityType", entityType.name())
                    .param("entityId", entityId)
                    .param("role", row.role())
                    .param("assetId", row.mediaAssetId())
                    .param("sortOrder", row.sortOrder())
                    .param("version", nextVersion)
                    .update();
        }
        return previous;
    }

    private static MediaOverrideRow mapOverride(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new MediaOverrideRow(
                EntityType.valueOf(row.getString("entity_type")),
                row.getObject("entity_id", UUID.class),
                row.getObject("media_asset_id", UUID.class),
                row.getString("role"),
                row.getInt("sort_order"),
                row.getInt("version"));
    }

    /**
     * One image a channel shows instead of an entity's own.
     *
     * @param version on a row being written this is ignored — {@link
     *     #replaceMediaOverrides} assigns the next set version
     */
    public record MediaOverrideRow(
            EntityType entityType, UUID entityId, UUID mediaAssetId, String role, int sortOrder, int version) {}
}
