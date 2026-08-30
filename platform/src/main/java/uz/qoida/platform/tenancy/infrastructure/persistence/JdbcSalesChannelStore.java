package uz.qoida.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.tenancy.api.FulfillmentMode;
import uz.qoida.platform.tenancy.api.SalesChannel;
import uz.qoida.platform.tenancy.api.SalesChannelLookup;
import uz.qoida.platform.tenancy.api.SalesChannelSystemType;

/**
 * The sales channel registry and its three matrices (ADR 0036).
 *
 * <p>Every statement carries the tenant predicate in the query rather than
 * checking ownership after loading. A channel id arrives from a URL or a cart and
 * is not evidence of anything; filtering after the fact is how a cross-tenant
 * read becomes a cross-tenant write.
 */
@Repository
public class JdbcSalesChannelStore implements SalesChannelLookup {

    private static final String CHANNEL_COLUMNS = """
            id, tenant_id, code, system_type, display_name, status,
            price_plane_channel_id, externally_priced, guest_orders_allowed,
            provider_installation_id, version
            """;

    private final JdbcClient jdbc;

    public JdbcSalesChannelStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SalesChannel> byId(UUID tenantId, UUID channelId) {
        return jdbc.sql("SELECT " + CHANNEL_COLUMNS + """
                FROM tenant.sales_channels
                WHERE tenant_id = :tenantId AND id = :channelId
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .query(JdbcSalesChannelStore::toChannel)
                .optional();
    }

    @Override
    public Optional<SalesChannel> byCode(UUID tenantId, String code) {
        return jdbc.sql("SELECT " + CHANNEL_COLUMNS + """
                FROM tenant.sales_channels
                WHERE tenant_id = :tenantId AND code = :code
                """)
                .param("tenantId", tenantId).param("code", code)
                .query(JdbcSalesChannelStore::toChannel)
                .optional();
    }

    public List<SalesChannel> listForTenant(UUID tenantId) {
        return jdbc.sql("SELECT " + CHANNEL_COLUMNS + """
                FROM tenant.sales_channels
                WHERE tenant_id = :tenantId
                ORDER BY code
                """)
                .param("tenantId", tenantId)
                .query(JdbcSalesChannelStore::toChannel)
                .list();
    }

    public void insert(SalesChannel channel, Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (
                    id, tenant_id, code, system_type, display_name, status,
                    price_plane_channel_id, externally_priced, guest_orders_allowed,
                    provider_installation_id, version, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :systemType, :displayName, :status,
                    :pricePlane, :externallyPriced, :guestOrdersAllowed,
                    :installationId, :version, :now, :now)
                """)
                .param("id", channel.id()).param("tenantId", channel.tenantId())
                .param("code", channel.code())
                .param("systemType", channel.systemType().name())
                .param("displayName", channel.displayName())
                .param("status", channel.status().name())
                .param("pricePlane", channel.pricePlaneChannelId())
                .param("externallyPriced", channel.externallyPriced())
                .param("guestOrdersAllowed", channel.guestOrdersAllowed())
                .param("installationId", channel.providerInstallationId())
                .param("version", channel.version())
                .param("now", timestamp(now))
                .update();
    }

    /**
     * Moves a channel to a new status, bumping its version.
     *
     * <p>There is no delete. Every order carries its channel forever, and a
     * deleted row makes that order unattributable in every report — so
     * {@code ARCHIVED} is the end of the lifecycle and the row stays.
     */
    public boolean updateStatus(UUID tenantId, UUID channelId, SalesChannel.Status status,
            int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE tenant.sales_channels
                SET status = :status, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :channelId AND version = :expectedVersion
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .param("status", status.name()).param("expectedVersion", expectedVersion)
                .param("now", timestamp(now))
                .update() == 1;
    }

    // ------------------------------------------------------------------ matrices

    /**
     * Replaces a whole matrix under an expected version.
     *
     * <p>Whole-matrix writes, never per-cell patches: a payment matrix edited cell
     * by cell from two tabs produces a combination neither operator chose, and
     * neither would see anything wrong. The version check is what makes the second
     * tab lose loudly instead of silently overwriting the first.
     */
    public boolean replacePaymentMethods(UUID tenantId, UUID channelId,
            Map<String, Boolean> matrix, int expectedVersion, Instant now) {

        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("DELETE FROM tenant.channel_payment_methods "
                        + "WHERE tenant_id = :tenantId AND channel_id = :channelId")
                .param("tenantId", tenantId).param("channelId", channelId)
                .update();
        matrix.forEach((code, enabled) -> jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (
                    tenant_id, channel_id, payment_method_code, enabled, created_at, updated_at)
                VALUES (:tenantId, :channelId, :code, :enabled, :now, :now)
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .param("code", code).param("enabled", enabled)
                .param("now", timestamp(now))
                .update());
        return true;
    }

    public boolean replaceFulfillmentModes(UUID tenantId, UUID channelId,
            Map<FulfillmentMode, Boolean> matrix, int expectedVersion, Instant now) {

        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("DELETE FROM tenant.channel_fulfillment_modes "
                        + "WHERE tenant_id = :tenantId AND channel_id = :channelId")
                .param("tenantId", tenantId).param("channelId", channelId)
                .update();
        matrix.forEach((mode, enabled) -> jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (
                    tenant_id, channel_id, fulfillment_mode, enabled, created_at, updated_at)
                VALUES (:tenantId, :channelId, :mode, :enabled, :now, :now)
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .param("mode", mode.name()).param("enabled", enabled)
                .param("now", timestamp(now))
                .update());
        return true;
    }

    public boolean replaceLocations(UUID tenantId, UUID channelId, List<UUID> locationIds,
            int expectedVersion, Instant now) {

        if (!bumpVersion(tenantId, channelId, expectedVersion, now)) {
            return false;
        }
        jdbc.sql("DELETE FROM tenant.sales_channel_locations "
                        + "WHERE tenant_id = :tenantId AND channel_id = :channelId")
                .param("tenantId", tenantId).param("channelId", channelId)
                .update();
        for (UUID locationId : locationIds) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channel_locations (
                        tenant_id, channel_id, location_id, status, created_at, updated_at)
                    VALUES (:tenantId, :channelId, :locationId, 'ACTIVE', :now, :now)
                    """)
                    .param("tenantId", tenantId).param("channelId", channelId)
                    .param("locationId", locationId)
                    .param("now", timestamp(now))
                    .update();
        }
        return true;
    }

    public Map<String, Boolean> paymentMethods(UUID tenantId, UUID channelId) {
        Map<String, Boolean> matrix = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT payment_method_code, enabled FROM tenant.channel_payment_methods
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                ORDER BY payment_method_code
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .query((row, number) -> Map.entry(
                        row.getString("payment_method_code"), row.getBoolean("enabled")))
                .list()
                .forEach(entry -> matrix.put(entry.getKey(), entry.getValue()));
        return matrix;
    }

    /**
     * The enabled half of the payment matrix (ADR 0036).
     *
     * <p>Filtered in the statement rather than by the caller. The matrix carries
     * disabled rows deliberately — an operator's explicit no is not the same fact
     * as never having configured a method — and a caller that had to remember to
     * drop them is a caller that will one day offer a customer a method the tenant
     * switched off.
     */
    @Override
    public java.util.Set<String> enabledPaymentMethodCodes(UUID tenantId, UUID channelId) {
        return new java.util.LinkedHashSet<>(jdbc.sql("""
                SELECT payment_method_code FROM tenant.channel_payment_methods
                WHERE tenant_id = :tenantId AND channel_id = :channelId AND enabled
                ORDER BY payment_method_code
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .query(String.class)
                .list());
    }

    public Map<FulfillmentMode, Boolean> fulfillmentModes(UUID tenantId, UUID channelId) {
        Map<FulfillmentMode, Boolean> matrix = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT fulfillment_mode, enabled FROM tenant.channel_fulfillment_modes
                WHERE tenant_id = :tenantId AND channel_id = :channelId
                ORDER BY fulfillment_mode
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .query((row, number) -> Map.entry(
                        FulfillmentMode.valueOf(row.getString("fulfillment_mode")),
                        row.getBoolean("enabled")))
                .list()
                .forEach(entry -> matrix.put(entry.getKey(), entry.getValue()));
        return matrix;
    }

    public List<UUID> locations(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                SELECT location_id FROM tenant.sales_channel_locations
                WHERE tenant_id = :tenantId AND channel_id = :channelId AND status = 'ACTIVE'
                ORDER BY location_id
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .query(UUID.class)
                .list();
    }

    /**
     * Whether any other channel takes its prices from this one.
     *
     * <p>Asked before archiving, because archiving a channel that another channel
     * prices through would leave the second silently falling back to brand prices.
     */
    public boolean isPricePlaneForAnother(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                SELECT count(*) FROM tenant.sales_channels
                WHERE tenant_id = :tenantId AND price_plane_channel_id = :channelId
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .query(Long.class).single() > 0;
    }

    /** Translates the composite foreign keys into the sentence they are protecting. */
    public static RuntimeException explain(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("uq_sales_channel_code")) {
            return new IllegalStateException("A channel with this code already exists for the tenant");
        }
        if (message.contains("fk_channel_location_location")) {
            return new IllegalArgumentException("That location does not belong to this tenant");
        }
        if (message.contains("fk_sales_channel_price_plane")) {
            return new IllegalArgumentException("That price plane channel does not belong to this tenant");
        }
        return violation;
    }

    private boolean bumpVersion(UUID tenantId, UUID channelId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE tenant.sales_channels
                SET version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :channelId AND version = :expectedVersion
                """)
                .param("tenantId", tenantId).param("channelId", channelId)
                .param("expectedVersion", expectedVersion)
                .param("now", timestamp(now))
                .update() == 1;
    }

    private static SalesChannel toChannel(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new SalesChannel(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("code"),
                SalesChannelSystemType.valueOf(row.getString("system_type")),
                row.getString("display_name"),
                SalesChannel.Status.valueOf(row.getString("status")),
                row.getObject("price_plane_channel_id", UUID.class),
                row.getBoolean("externally_priced"),
                row.getBoolean("guest_orders_allowed"),
                row.getObject("provider_installation_id", UUID.class),
                row.getInt("version"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
