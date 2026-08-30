package uz.qoida.platform.ordering.infrastructure.catalog;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.qoida.platform.ordering.application.OrderCatalogSnapshot;

/**
 * Reads the catalog facts an order snapshot needs (ADR 0019).
 *
 * <p>Reads the publication for liveness and the translation rows for names. That
 * split is deliberate: the publication decides <em>whether</em> a menu is live,
 * while the translation rows carry the display names the customer has just been
 * shown. Copying those names onto the order is what makes it immune to the next
 * rename.
 *
 * <p>Every query carries both the tenant and the brand. A variant id is a UUID
 * that could have come from anywhere, and a lookup keyed on the id alone would
 * happily describe another brand's dish onto this order's receipt.
 */
@Component
public class JdbcOrderCatalogSnapshot implements OrderCatalogSnapshot {

    private final JdbcClient jdbc;
    private final String defaultLocale;

    public JdbcOrderCatalogSnapshot(JdbcClient jdbc,
            @Value("${qoida.catalog.default-locale:uz}") String defaultLocale) {
        this.jdbc = jdbc;
        this.defaultLocale = defaultLocale;
    }

    @Override
    public Optional<UUID> activePublicationId(UUID tenantId, UUID brandId, String channelCode) {
        return jdbc.sql("""
                SELECT id FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                .param("tenantId", tenantId).param("brandId", brandId).param("channel", channelCode)
                .query(UUID.class)
                .optional();
    }

    /**
     * The slowest override among the ordered variants at this branch.
     *
     * <p>Rows with no override are simply absent from the aggregate; {@code max}
     * over an empty set is SQL NULL, which becomes an empty optional and leaves
     * the band governing. A variant offered at this branch but with a null
     * override must not be read as "zero minutes" — that would be an override
     * claiming the dish is instant, which is the opposite of what a missing row
     * means.
     *
     * <p>Scoped by tenant, brand and location together. A location offering is
     * addressed by a client-supplied variant id, and matching on the id alone
     * would let another brand's slow dish stretch this order's promise.
     */
    @Override
    public Optional<Duration> longestPreparationOverride(UUID tenantId, UUID brandId,
            UUID locationId, Set<UUID> variantIds) {

        if (variantIds.isEmpty()) {
            return Optional.empty();
        }
        // Reduced to seconds in SQL rather than returned as an interval. The
        // driver's interval mapping is a moving target across pgjdbc versions,
        // and a promised time is not the place to discover that it changed.
        // max() ignores nulls and returns null over an empty set, which is the
        // "nothing here overrides the band" answer.
        return jdbc.sql("""
                SELECT extract(epoch FROM max(preparation_duration_override))::bigint
                FROM catalog.location_offerings
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND location_id = :locationId AND variant_id IN (:variantIds)
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
                .param("locationId", locationId).param("variantIds", variantIds)
                .query(Long.class)
                .optional()
                .map(Duration::ofSeconds);
    }

    @Override
    public Map<UUID, VariantDescriptor> variants(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VariantDescriptor> descriptors = new HashMap<>();
        jdbc.sql("""
                SELECT v.id AS variant_id,
                       v.product_id,
                       v.sku,
                       COALESCE(pt.name, p.code) AS product_name,
                       vt.name AS variant_name
                FROM catalog.variants v
                JOIN catalog.products p ON p.id = v.product_id
                LEFT JOIN catalog.translations pt
                       ON pt.entity_type = 'PRODUCT' AND pt.entity_id = p.id
                      AND pt.locale = :locale
                LEFT JOIN catalog.translations vt
                       ON vt.entity_type = 'VARIANT' AND vt.entity_id = v.id
                      AND vt.locale = :locale
                WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId
                  AND v.id = ANY(:ids)
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
                .param("locale", defaultLocale)
                .param("ids", variantIds.toArray(UUID[]::new))
                .query((row, number) -> new Described(
                        row.getObject("variant_id", UUID.class),
                        new VariantDescriptor(
                                row.getObject("product_id", UUID.class),
                                row.getString("product_name"),
                                // Null rather than the string "null": a variant
                                // genuinely may have no name of its own, and a
                                // receipt reading "Burger (null)" is worse than one
                                // reading "Burger".
                                row.getString("variant_name"),
                                row.getString("sku"))))
                .list()
                .forEach(described -> descriptors.put(described.id(), described.descriptor()));
        return descriptors;
    }

    @Override
    public Map<UUID, ModifierDescriptor> modifierOptions(UUID tenantId, UUID brandId,
            Set<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ModifierDescriptor> descriptors = new HashMap<>();
        jdbc.sql("""
                SELECT o.id AS option_id,
                       o.modifier_group_id,
                       COALESCE(gt.name, g.code) AS group_name,
                       COALESCE(ot.name, o.code) AS option_name
                FROM catalog.modifier_options o
                JOIN catalog.modifier_groups g ON g.id = o.modifier_group_id
                LEFT JOIN catalog.translations gt
                       ON gt.entity_type = 'MODIFIER_GROUP' AND gt.entity_id = g.id
                      AND gt.locale = :locale
                LEFT JOIN catalog.translations ot
                       ON ot.entity_type = 'MODIFIER_OPTION' AND ot.entity_id = o.id
                      AND ot.locale = :locale
                WHERE o.tenant_id = :tenantId AND o.brand_id = :brandId
                  AND o.id = ANY(:ids)
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
                .param("locale", defaultLocale)
                .param("ids", optionIds.toArray(UUID[]::new))
                .query((row, number) -> new DescribedOption(
                        row.getObject("option_id", UUID.class),
                        new ModifierDescriptor(
                                row.getObject("modifier_group_id", UUID.class),
                                row.getString("group_name"),
                                row.getString("option_name"))))
                .list()
                .forEach(described -> descriptors.put(described.id(), described.descriptor()));
        return descriptors;
    }

    /** Carries a nullable descriptor field, which {@code Map.entry} refuses to. */
    private record Described(UUID id, VariantDescriptor descriptor) { }

    private record DescribedOption(UUID id, ModifierDescriptor descriptor) { }
}
