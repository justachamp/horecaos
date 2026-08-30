package uz.horecaos.platform.pos.infrastructure.persistence;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.pos.domain.DifferenceEngine.TargetCatalog;
import uz.horecaos.platform.pos.domain.SyncDifference.EntityType;

/**
 * What HorecaOS currently holds for the entities a binding has mapped (ADR 0012).
 *
 * <p>Keyed on the external identifier throughout, because that is the only key
 * the two systems share. ADR 0012's rule against guessing a mapping from a
 * mutable product name is enforced by this shape rather than by discipline: an
 * entity that has no mapping row simply does not appear here, so the difference
 * engine sees it as an addition and never as a rename.
 *
 * <p>Only {@code ACTIVE} mappings are read. A {@code PROPOSED} mapping is a
 * suggestion nobody has accepted, and a {@code CONFLICTED} one is a question, so
 * treating either as identity would let a run apply a decision that has not been
 * taken.
 *
 * <p>The name is read at the brand's default locale from
 * {@code catalog.translations}. It is compared and never written: the field
 * authority policy makes every customer-facing name HorecaOS's, so the provider's
 * value reaches an operator's screen and stops there.
 */
@Component
public class JdbcPosTargetCatalog {

    private final JdbcClient jdbc;

    public JdbcPosTargetCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param defaultLocale the brand's own locale. A name compared at the wrong
     *                      locale would report every product as changed on the
     *                      first run and then never again, which is worse than
     *                      comparing nothing
     */
    public TargetCatalog read(UUID tenantId, UUID bindingId, UUID brandId, String defaultLocale) {
        Map<EntityType, Map<String, TargetCatalog.Entity>> byType = new HashMap<>();
        byType.put(EntityType.PRODUCT, products(tenantId, bindingId, brandId, defaultLocale));
        byType.put(EntityType.VARIANT, variants(tenantId, bindingId, brandId, defaultLocale));
        return new TargetCatalog(byType);
    }

    private Map<String, TargetCatalog.Entity> products(UUID tenantId, UUID bindingId, UUID brandId,
            String locale) {

        Map<String, TargetCatalog.Entity> entities = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.external_entity_id, p.id, p.version, p.status,
                       t.name AS translated_name, p.tax_category_code
                  FROM integration.provider_entity_mappings m
                  JOIN catalog.products p
                    ON p.id = m.horecaos_entity_id AND p.tenant_id = m.tenant_id
                  LEFT JOIN catalog.translations t
                         ON t.entity_type = 'PRODUCT' AND t.entity_id = p.id
                        AND t.locale = :locale AND t.tenant_id = p.tenant_id
                 WHERE m.tenant_id = :tenantId
                   AND m.binding_id = :bindingId
                   AND m.entity_type = 'VARIANT_PARENT'
                   AND m.status = 'ACTIVE'
                   AND p.brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("brandId", brandId)
                .param("locale", locale)
                .query((row, number) -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    putIfPresent(fields, "product.name", row.getString("translated_name"));
                    putIfPresent(fields, "product.status", row.getString("status"));
                    putIfPresent(fields, "product.governmentCode", row.getString("tax_category_code"));
                    return Map.entry(row.getString("external_entity_id"),
                            new TargetCatalog.Entity(
                                    row.getObject("id", UUID.class),
                                    row.getInt("version"),
                                    fields));
                })
                .list()
                .forEach(entry -> entities.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(entities);
    }

    private Map<String, TargetCatalog.Entity> variants(UUID tenantId, UUID bindingId, UUID brandId,
            String locale) {

        Map<String, TargetCatalog.Entity> entities = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT m.external_entity_id, v.id, v.version, v.status, v.unit_code,
                       t.name AS translated_name
                  FROM integration.provider_entity_mappings m
                  JOIN catalog.variants v
                    ON v.id = m.horecaos_entity_id AND v.tenant_id = m.tenant_id
                  LEFT JOIN catalog.translations t
                         ON t.entity_type = 'VARIANT' AND t.entity_id = v.id
                        AND t.locale = :locale AND t.tenant_id = v.tenant_id
                 WHERE m.tenant_id = :tenantId
                   AND m.binding_id = :bindingId
                   AND m.entity_type = 'VARIANT'
                   AND m.status = 'ACTIVE'
                   AND v.brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("brandId", brandId)
                .param("locale", locale)
                .query((row, number) -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    putIfPresent(fields, "variant.name", row.getString("translated_name"));
                    putIfPresent(fields, "variant.status", row.getString("status"));
                    putIfPresent(fields, "variant.unit", row.getString("unit_code"));
                    return Map.entry(row.getString("external_entity_id"),
                            new TargetCatalog.Entity(
                                    row.getObject("id", UUID.class),
                                    row.getInt("version"),
                                    fields));
                })
                .list()
                .forEach(entry -> entities.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(entities);
    }

    /**
     * Absent and blank are one thing in a comparison, so an absent value is left
     * out entirely rather than stored as an empty string a diff would report.
     */
    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }
}
