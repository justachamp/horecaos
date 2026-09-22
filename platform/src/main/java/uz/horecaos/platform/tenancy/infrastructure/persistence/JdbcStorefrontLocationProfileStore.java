package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The small, public projection of one branch a customer may be shown before or
 * after ordering from it — a name and an address, nothing a staff console needs
 * that a receipt does not already carry.
 *
 * <p>Every column read here is one {@code tenant.locations} already treats as
 * published: V0023's own doc comment on that table draws the line this store
 * repeats — a restaurant's address is printed on the receipt, shown in the
 * storefront and handed to every courier, unlike a customer's own address,
 * which stays encrypted. There is nothing here a merchant has not already
 * chosen to advertise.
 */
@Repository
public class JdbcStorefrontLocationProfileStore {

    private final JdbcClient jdbc;

    public JdbcStorefrontLocationProfileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The published profile of one active branch, tenant- and brand-scoped so
     * one tenant's location id can never read another tenant's branch.
     */
    public Optional<LocationProfileRow> find(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                SELECT l.display_name,
                       l.address_line,
                       l.district,
                       l.city,
                       l.landmark,
                       l.contact_phone,
                       l.latitude,
                       l.longitude
                FROM tenant.locations l
                JOIN tenant.brands b
                  ON b.tenant_id = l.tenant_id AND b.id = l.brand_id
                JOIN tenant.tenants t
                  ON t.id = l.tenant_id
                WHERE l.tenant_id = :tenantId
                  AND l.brand_id = :brandId
                  AND l.id = :locationId
                  AND t.status = 'ACTIVE'
                  AND b.status = 'ACTIVE'
                  AND l.status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query((row, number) -> new LocationProfileRow(
                        row.getString("display_name"),
                        row.getString("address_line"),
                        row.getString("district"),
                        row.getString("city"),
                        row.getString("landmark"),
                        row.getString("contact_phone"),
                        (Double) row.getObject("latitude"),
                        (Double) row.getObject("longitude")))
                .optional();
    }

    /** One branch's publishable identity. Every field but the name may be unset. */
    public record LocationProfileRow(
            String displayName,
            @Nullable String addressLine,
            @Nullable String district,
            @Nullable String city,
            @Nullable String landmark,
            @Nullable String contactPhone,
            @Nullable Double latitude,
            @Nullable Double longitude) {}
}
