package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;

/**
 * Persistence for {@code catalog.variant_physical_attributes} (ADR 0137).
 *
 * <p>Its own class rather than more of {@link JdbcCatalogStore}, which is
 * already the largest file in the module: these are one table with one
 * lifecycle, and nothing else in the catalog shares a query with them.
 *
 * <p>Every statement carries the tenant and the brand, so a variant id from
 * another brand finds no row rather than somebody else's nutrition.
 */
@Repository
public class JdbcPhysicalAttributesStore {

    private static final String COLUMNS = """
            net_weight_grams, net_volume_millilitres, is_catchweight, catchweight_quantum_grams,
            catchweight_nominal_grams, is_splittable, portion_size, calories_kcal_per_100,
            protein_grams_per_100, fat_grams_per_100, carbohydrates_grams_per_100""";

    private final JdbcClient jdbc;

    public JdbcPhysicalAttributesStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored row with the version a caller must quote to change it. */
    public record Stored(PhysicalAttributes attributes, int version) {}

    public Optional<Stored> find(UUID tenantId, UUID brandId, UUID variantId) {
        return jdbc.sql("SELECT " + COLUMNS + ", version FROM catalog.variant_physical_attributes "
                        + "WHERE tenant_id = :tenantId AND brand_id = :brandId AND variant_id = :variantId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("variantId", variantId)
                .query((row, number) -> new Stored(map(row), row.getInt("version")))
                .optional();
    }

    /** Every variant of one brand that carries a row, for the publication snapshot and the validator. */
    public Map<UUID, PhysicalAttributes> forBrand(UUID tenantId, UUID brandId) {
        Map<UUID, PhysicalAttributes> byVariant = new LinkedHashMap<>();
        jdbc.sql("SELECT variant_id, " + COLUMNS + " FROM catalog.variant_physical_attributes "
                        + "WHERE tenant_id = :tenantId AND brand_id = :brandId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> Map.entry(row.getObject("variant_id", UUID.class), map(row)))
                .list()
                .forEach(entry -> byVariant.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(byVariant);
    }

    /**
     * Writes the first row for a variant.
     *
     * @return the new version, or empty when a row already exists (someone else
     *     wrote the first one between the caller's read and this write)
     */
    public Optional<Integer> insert(UUID tenantId, UUID brandId, UUID variantId, PhysicalAttributes attributes) {
        Map<String, Object> params = params(tenantId, brandId, variantId, attributes);
        return jdbc.sql("""
                INSERT INTO catalog.variant_physical_attributes (
                    variant_id, tenant_id, brand_id,
                    net_weight_grams, net_volume_millilitres, is_catchweight, catchweight_quantum_grams,
                    catchweight_nominal_grams, is_splittable, portion_size, calories_kcal_per_100,
                    protein_grams_per_100, fat_grams_per_100, carbohydrates_grams_per_100)
                VALUES (
                    :variantId, :tenantId, :brandId,
                    :netWeightGrams, :netVolumeMillilitres, :catchweight, :quantumGrams,
                    :nominalGrams, :splittable, :portionSize, :calories,
                    :protein, :fat, :carbohydrates)
                ON CONFLICT (variant_id) DO NOTHING
                RETURNING version
                """).params(params).query(Integer.class).optional();
    }

    /**
     * Replaces the row, only if it is still at {@code expectedVersion}.
     *
     * @return the new version, or empty when the version moved on
     */
    public Optional<Integer> update(
            UUID tenantId, UUID brandId, UUID variantId, PhysicalAttributes attributes, int expectedVersion) {
        Map<String, Object> params = params(tenantId, brandId, variantId, attributes);
        params.put("expectedVersion", expectedVersion);
        return jdbc.sql("""
                UPDATE catalog.variant_physical_attributes SET
                    net_weight_grams = :netWeightGrams,
                    net_volume_millilitres = :netVolumeMillilitres,
                    is_catchweight = :catchweight,
                    catchweight_quantum_grams = :quantumGrams,
                    catchweight_nominal_grams = :nominalGrams,
                    is_splittable = :splittable,
                    portion_size = :portionSize,
                    calories_kcal_per_100 = :calories,
                    protein_grams_per_100 = :protein,
                    fat_grams_per_100 = :fat,
                    carbohydrates_grams_per_100 = :carbohydrates,
                    version = version + 1,
                    updated_at = now()
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND variant_id = :variantId
                  AND version = :expectedVersion
                RETURNING version
                """).params(params).query(Integer.class).optional();
    }

    /** Removes the row, only if it is still at {@code expectedVersion}. */
    public boolean delete(UUID tenantId, UUID brandId, UUID variantId, int expectedVersion) {
        return jdbc.sql("""
                        DELETE FROM catalog.variant_physical_attributes
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND variant_id = :variantId
                          AND version = :expectedVersion
                        """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("variantId", variantId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    private static Map<String, Object> params(
            UUID tenantId, UUID brandId, UUID variantId, PhysicalAttributes attributes) {
        // A HashMap because nearly every value is legitimately null.
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("variantId", variantId);
        params.put("netWeightGrams", attributes.netWeightGrams());
        params.put("netVolumeMillilitres", attributes.netVolumeMillilitres());
        params.put("catchweight", attributes.catchweight());
        params.put("quantumGrams", attributes.catchweightQuantumGrams());
        params.put("nominalGrams", attributes.catchweightNominalGrams());
        params.put("splittable", attributes.splittable());
        params.put("portionSize", attributes.portionSize());
        params.put("calories", attributes.caloriesKcalPer100());
        params.put("protein", attributes.proteinGramsPer100());
        params.put("fat", attributes.fatGramsPer100());
        params.put("carbohydrates", attributes.carbohydratesGramsPer100());
        return params;
    }

    /**
     * Every nullable numeric column is read through {@code getObject}: the
     * primitive accessors answer 0 for a SQL null, and 0 grams of protein is a
     * figure a customer would read as a claim.
     */
    private static PhysicalAttributes map(ResultSet row) throws SQLException {
        return new PhysicalAttributes(
                row.getObject("net_weight_grams", Integer.class),
                row.getObject("net_volume_millilitres", Integer.class),
                row.getBoolean("is_catchweight"),
                row.getObject("catchweight_quantum_grams", Integer.class),
                row.getObject("catchweight_nominal_grams", Integer.class),
                row.getBoolean("is_splittable"),
                row.getObject("portion_size", BigDecimal.class),
                row.getObject("calories_kcal_per_100", BigDecimal.class),
                row.getObject("protein_grams_per_100", BigDecimal.class),
                row.getObject("fat_grams_per_100", BigDecimal.class),
                row.getObject("carbohydrates_grams_per_100", BigDecimal.class));
    }
}
