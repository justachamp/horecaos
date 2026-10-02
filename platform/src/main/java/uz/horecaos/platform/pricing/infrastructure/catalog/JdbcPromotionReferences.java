package uz.horecaos.platform.pricing.infrastructure.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.api.AudienceMembershipPort;
import uz.horecaos.platform.pricing.application.PromotionValidator;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;

/**
 * Answers the validator's "does this exist, and is it this brand's" questions
 * (ADR 0140, {@code UNKNOWN_REFERENCE}).
 *
 * <p>Every query carries the tenant and, where the thing is brand-owned, the
 * brand, so a product, category, variant, zone or location of another tenant or
 * brand is simply not found and the validator refuses it. Pricing reads catalog,
 * fulfillment and tenancy <em>tables</em> the way it already reads the catalog for
 * line membership; it imports none of their types.
 */
@Component
public class JdbcPromotionReferences {

    private final JdbcClient jdbc;
    private final JdbcPricingStore pricing;
    private final ObjectProvider<AudienceMembershipPort> audiences;

    public JdbcPromotionReferences(
            JdbcClient jdbc, JdbcPricingStore pricing, ObjectProvider<AudienceMembershipPort> audiences) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.audiences = audiences;
    }

    /** The reference checks for one brand of one tenant. */
    public PromotionValidator.References forBrand(UUID tenantId, UUID brandId, Instant now) {
        return new PromotionValidator.References() {
            @Override
            public Set<UUID> knownProducts(Collection<UUID> ids) {
                return brandOwned("catalog.products", tenantId, brandId, ids);
            }

            @Override
            public Set<UUID> knownCategories(Collection<UUID> ids) {
                return brandOwned("catalog.categories", tenantId, brandId, ids);
            }

            @Override
            public Set<UUID> knownVariants(Collection<UUID> ids) {
                return brandOwned("catalog.variants", tenantId, brandId, ids);
            }

            @Override
            public Set<UUID> knownZones(Collection<UUID> ids) {
                return brandOwned("fulfillment.service_zones", tenantId, brandId, ids);
            }

            @Override
            public Set<UUID> knownLocations(Collection<UUID> ids) {
                return brandOwned("tenant.locations", tenantId, brandId, ids);
            }

            @Override
            public Set<String> knownChannelCodes(Collection<String> codes) {
                return new HashSet<>(jdbc.sql("""
                        SELECT code FROM tenant.sales_channels
                        WHERE tenant_id = :tenantId AND code = ANY(:codes)
                        """)
                        .param("tenantId", tenantId)
                        .param("codes", codes.toArray(String[]::new))
                        .query(String.class)
                        .list());
            }

            @Override
            public Set<String> knownPaymentMethods(Collection<String> codes) {
                return new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT payment_method_code FROM tenant.channel_payment_methods
                        WHERE tenant_id = :tenantId AND payment_method_code = ANY(:codes)
                        """)
                        .param("tenantId", tenantId)
                        .param("codes", codes.toArray(String[]::new))
                        .query(String.class)
                        .list());
            }

            @Override
            public Set<String> knownAudiences(Collection<String> ids) {
                AudienceMembershipPort port = audiences.getIfAvailable();
                return port == null ? Set.of() : port.knownAudiences(tenantId, brandId, ids);
            }

            @Override
            public Optional<String> brandCurrency() {
                return jdbc.sql("""
                        SELECT currency FROM pricing.price_books
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND status <> 'ARCHIVED'
                        ORDER BY (status = 'ACTIVE') DESC, created_at
                        LIMIT 1
                        """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .query(String.class)
                        .optional();
            }

            @Override
            public Set<UUID> unpricedVariants(Collection<UUID> ids) {
                Set<UUID> requested = new HashSet<>(ids);
                Set<UUID> priced = pricing.pricedVariants(tenantId, brandId, requested, now);
                requested.removeAll(priced);
                return requested;
            }
        };
    }

    private Set<UUID> brandOwned(String table, UUID tenantId, UUID brandId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        // The table name is one of this class's own literals above, never caller input.
        return new HashSet<>(jdbc.sql("SELECT id FROM " + table
                        + " WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = ANY(:ids)")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", ids.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }
}
