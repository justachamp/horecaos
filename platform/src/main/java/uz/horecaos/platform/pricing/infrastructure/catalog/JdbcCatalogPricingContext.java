package uz.horecaos.platform.pricing.infrastructure.catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.CatalogNameLocales;
import uz.horecaos.platform.pricing.application.CatalogPricingContext;
import uz.horecaos.platform.pricing.application.PriceableType;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;

/**
 * Reads the catalog facts pricing needs (ADR 0018).
 *
 * <p>Everything a <em>quote</em> reads comes from the published tables. Pricing a
 * draft would let an unpublished price or an unreleased dish reach a customer's
 * cart, which is exactly what the publication boundary exists to prevent.
 * Authoring is the one exception and reads the draft tables on purpose: an
 * operator prices a dish before publishing the menu it belongs to.
 */
@Component
public class JdbcCatalogPricingContext implements CatalogPricingContext {

    private final JdbcClient jdbc;
    private final BrandLocaleLookup brandLocales;
    private final String defaultLocale;

    /**
     * @param defaultLocale {@code horecaos.catalog.default-locale} -- where a name is
     *                      looked for when the brand's own default language has none
     */
    @Autowired
    public JdbcCatalogPricingContext(
            JdbcClient jdbc,
            BrandLocaleLookup brandLocales,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.jdbc = jdbc;
        this.brandLocales = brandLocales;
        this.defaultLocale = defaultLocale;
    }

    /** Resolves every brand in the configured locale, for callers that build pricing without a tenancy. */
    public JdbcCatalogPricingContext(JdbcClient jdbc, String defaultLocale) {
        this(jdbc, BrandLocaleLookup.platformFallback(), defaultLocale);
    }

    @Override
    public Optional<UUID> activePublicationId(UUID tenantId, UUID brandId, String channelCode) {
        // ADR 0036 correction: the channel is bound rather than hardcoded to
        // 'STOREFRONT'. The literal made a kiosk cart price against the storefront
        // menu, silently, with the kiosk publication live and never read.
        return jdbc.sql("""
                SELECT id FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channelCode)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Map<UUID, String> descriptions(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        CatalogNameLocales locales = CatalogNameLocales.of(brandLocales, tenantId, brandId, defaultLocale);
        // A variant's name falls back to its product's, because most variants are
        // "regular" or "large" and the customer-facing name is the dish. The brand's
        // own default language is tried for both before the server's locale is, so a
        // line never mixes languages when the brand has named the whole dish.
        jdbc.sql("""
                SELECT v.id AS variant_id,
                       COALESCE(vt.name, pt.name, vtf.name, ptf.name, p.code) AS display_name
                FROM catalog.variants v
                JOIN catalog.products p ON p.id = v.product_id
                LEFT JOIN catalog.translations vt
                       ON vt.entity_type = 'VARIANT' AND vt.entity_id = v.id
                      AND vt.locale = :preferredLocale
                LEFT JOIN catalog.translations pt
                       ON pt.entity_type = 'PRODUCT' AND pt.entity_id = p.id
                      AND pt.locale = :preferredLocale
                LEFT JOIN catalog.translations vtf
                       ON vtf.entity_type = 'VARIANT' AND vtf.entity_id = v.id
                      AND vtf.locale = :fallbackLocale
                LEFT JOIN catalog.translations ptf
                       ON ptf.entity_type = 'PRODUCT' AND ptf.entity_id = p.id
                      AND ptf.locale = :fallbackLocale
                WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId
                  AND v.id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("preferredLocale", locales.preferred())
                .param("fallbackLocale", locales.fallback())
                .param("ids", variantIds.toArray(UUID[]::new))
                .query((row, number) ->
                        Map.entry(row.getObject("variant_id", UUID.class), row.getString("display_name")))
                .list()
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    @Override
    public boolean priceableExists(UUID tenantId, UUID brandId, PriceableType type, UUID priceableId) {
        // The authoring tables, not the published snapshot: a price is set on a
        // draft dish long before the menu carrying it goes live, and reading the
        // publication here would refuse every price an operator writes first.
        String sql =
                switch (type) {
                    case VARIANT -> """
                    SELECT count(*) FROM catalog.variants
                    WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                      AND status <> 'ARCHIVED'
                    """;
                    case MODIFIER_OPTION -> """
                    SELECT count(*) FROM catalog.modifier_options
                    WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                      AND status <> 'ARCHIVED'
                    """;
                };
        return jdbc.sql(sql)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", priceableId)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
