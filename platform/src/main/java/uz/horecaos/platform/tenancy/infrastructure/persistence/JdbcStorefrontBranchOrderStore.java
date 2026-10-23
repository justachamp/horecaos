package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code tenant.locations.storefront_sort_order} (V0674, ADR 0157 Release 4, ADR 0183 D17): the order a brand's storefront lists
 * its branches in when it knows no customer point, and its tie-break after distance when it does.
 *
 * <p>Distinct from {@code tenant.locations.sort_order} (V0401), which orders the console's own branch list; a branch can be
 * first in the manager's list and last in the customer's, and conflating the two would make one screen's tidy-up a
 * customer-facing change.
 */
@Repository
public class JdbcStorefrontBranchOrderStore {

    private final JdbcClient jdbc;

    public JdbcStorefrontBranchOrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One of a brand's branches and where the storefront puts it; unset sorts last. */
    public record Branch(
            UUID locationId,
            String displayName,
            String status,
            @Nullable Integer storefrontSortOrder) {}

    /**
     * Holds the brand's row until the transaction ends, so two replacements of one brand's order run one after the other:
     * the second then reads the order the first left and is compared against the version it presented. {@code FOR NO KEY
     * UPDATE} does not block the foreign-key checks of a branch being created or edited under the brand.
     */
    public void lockBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                SELECT id FROM tenant.brands
                 WHERE tenant_id = :tenantId AND id = :brandId
                   FOR NO KEY UPDATE
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(UUID.class)
                .optional();
    }

    /** The brand's branches in the order the storefront would list them with no point. */
    public List<Branch> list(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id, display_name, status, storefront_sort_order
                  FROM tenant.locations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                 ORDER BY storefront_sort_order NULLS LAST, display_name, id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new Branch(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getString("status"),
                        row.getObject("storefront_sort_order", Integer.class)))
                .list();
    }

    /**
     * Sets the order: the named branches take 10, 20, 30... in the order given and every other branch of the brand goes back
     * to unset, in one statement, so a half-applied order cannot exist.
     */
    public void replace(UUID tenantId, UUID brandId, List<UUID> orderedIds, Instant now) {
        Integer[] positions = new Integer[orderedIds.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = (i + 1) * 10;
        }
        jdbc.sql("""
                UPDATE tenant.locations l
                   SET storefront_sort_order = o.position,
                       updated_at = :now
                  FROM (SELECT l2.id, o2.position
                          FROM tenant.locations l2
                          LEFT JOIN unnest(CAST(:ids AS uuid[]), CAST(:positions AS integer[])) AS o2(id, position)
                            ON o2.id = l2.id
                         WHERE l2.tenant_id = :tenantId AND l2.brand_id = :brandId) o
                 WHERE l.tenant_id = :tenantId AND l.brand_id = :brandId AND l.id = o.id
                   AND l.storefront_sort_order IS DISTINCT FROM o.position
                """)
                .param("ids", orderedIds.toArray(UUID[]::new))
                .param("positions", positions)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
