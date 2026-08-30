package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;

/**
 * Rate cards and their components (ADR 0042).
 *
 * <p>Resolution is most-specific-first across brand, location, and courier type,
 * the same shape ADR 0030 uses for policy. The alternative — one card per branch
 * per type, authored in full — is how a tenant with twelve branches ends up with
 * eleven correct cards and one that pays nothing for the second kilometre.
 */
@Repository
public class JdbcCourierRateCardStore {

    private final JdbcClient jdbc;

    public JdbcCourierRateCardStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCard(UUID id, UUID tenantId, UUID brandId, UUID locationId,
            UUID courierTypeId, String code, int cardVersion, String currency) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("locationId", locationId);
        params.put("courierTypeId", courierTypeId);
        params.put("code", code);
        params.put("cardVersion", cardVersion);
        params.put("currency", currency);
        params.put("now", JdbcCourierStore.utc(Instant.now()));

        jdbc.sql("""
                INSERT INTO fulfillment.courier_rate_cards (
                    id, tenant_id, brand_id, location_id, courier_type_id, code, card_version,
                    status, currency, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :courierTypeId, :code, :cardVersion,
                    'DRAFT', :currency, :now, :now)
                """)
                .params(params)
                .update();
    }

    public void insertComponent(UUID id, UUID tenantId, UUID cardId, RateComponent component) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("cardId", cardId);
        params.put("type", component.type().name());
        params.put("priority", component.priority());
        params.put("amount", component.amountMinor());
        params.put("bandFrom", component.bandFromMeters());
        params.put("bandTo", component.bandToMeters());
        params.put("minimumSeconds", component.minimumPaidSeconds());

        jdbc.sql("""
                INSERT INTO fulfillment.courier_rate_components (
                    id, tenant_id, rate_card_id, component_type, priority, amount_minor,
                    band_from_meters, band_to_meters, minimum_paid_seconds, created_at)
                VALUES (:id, :tenantId, :cardId, :type, :priority, :amount,
                    :bandFrom, :bandTo, :minimumSeconds, now())
                """)
                .params(params)
                .update();
    }

    /**
     * Activates a card, superseding any earlier active card with the same code.
     * Two active versions of one card would make an accrual depend on which row
     * a query read first, and the courier would see the difference.
     */
    public boolean activate(UUID tenantId, UUID cardId, String activatedBy, Instant effectiveFrom) {
        jdbc.sql("""
                UPDATE fulfillment.courier_rate_cards
                   SET status = 'SUPERSEDED', effective_to = :effectiveFrom, updated_at = :now
                 WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                   AND code = (SELECT code FROM fulfillment.courier_rate_cards
                                WHERE tenant_id = :tenantId AND id = :id)
                """)
                .param("tenantId", tenantId).param("id", cardId)
                .param("effectiveFrom", JdbcCourierStore.utc(effectiveFrom))
                .param("now", JdbcCourierStore.utc(Instant.now()))
                .update();

        return jdbc.sql("""
                UPDATE fulfillment.courier_rate_cards
                   SET status = 'ACTIVE', activated_by = :activatedBy, activated_at = :now,
                       effective_from = :effectiveFrom, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'
                """)
                .param("tenantId", tenantId).param("id", cardId)
                .param("activatedBy", activatedBy)
                .param("effectiveFrom", JdbcCourierStore.utc(effectiveFrom))
                .param("now", JdbcCourierStore.utc(Instant.now()))
                .update() == 1;
    }

    public Optional<RateCard> findCard(UUID tenantId, UUID cardId) {
        Optional<CardHeader> header = jdbc.sql("""
                SELECT id, card_version, currency, status
                  FROM fulfillment.courier_rate_cards
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", cardId)
                .query((ResultSet rs, int rowNumber) -> new CardHeader(
                        rs.getObject("id", UUID.class), rs.getInt("card_version"),
                        rs.getString("currency"), rs.getString("status")))
                .optional();

        return header.map(found -> new RateCard(
                found.id(), found.version(), found.currency(), componentsOf(tenantId, found.id())));
    }

    /**
     * The card that applies to a branch and a courier type, most specific first.
     * Ordering is by how many of the three scopes the card names, so a card
     * naming the branch and the type beats one naming only the brand.
     */
    public Optional<RateCard> resolve(UUID tenantId, UUID brandId, UUID locationId,
            UUID courierTypeId, Instant at) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("locationId", locationId);
        params.put("courierTypeId", courierTypeId);
        params.put("at", JdbcCourierStore.utc(at));

        Optional<CardHeader> header = jdbc.sql("""
                SELECT id, card_version, currency, status
                  FROM fulfillment.courier_rate_cards
                 WHERE tenant_id = :tenantId
                   AND status = 'ACTIVE'
                   AND effective_from <= :at
                   AND (effective_to IS NULL OR effective_to > :at)
                   AND (brand_id IS NULL OR brand_id = :brandId)
                   AND (location_id IS NULL OR location_id = :locationId)
                   AND (courier_type_id IS NULL OR courier_type_id = :courierTypeId)
                 ORDER BY (location_id IS NOT NULL)::int DESC,
                          (courier_type_id IS NOT NULL)::int DESC,
                          (brand_id IS NOT NULL)::int DESC,
                          card_version DESC
                 LIMIT 1
                """)
                .params(params)
                .query((ResultSet rs, int rowNumber) -> new CardHeader(
                        rs.getObject("id", UUID.class), rs.getInt("card_version"),
                        rs.getString("currency"), rs.getString("status")))
                .optional();

        return header.map(found -> new RateCard(
                found.id(), found.version(), found.currency(), componentsOf(tenantId, found.id())));
    }

    private List<RateComponent> componentsOf(UUID tenantId, UUID cardId) {
        List<RateComponent> components = new ArrayList<>(jdbc.sql("""
                SELECT id, component_type, priority, amount_minor, band_from_meters,
                       band_to_meters, minimum_paid_seconds
                  FROM fulfillment.courier_rate_components
                 WHERE tenant_id = :tenantId AND rate_card_id = :cardId
                 ORDER BY priority, band_from_meters NULLS FIRST
                """)
                .param("tenantId", tenantId).param("cardId", cardId)
                .query(JdbcCourierRateCardStore::mapComponent)
                .list());
        return List.copyOf(components);
    }

    private static RateComponent mapComponent(ResultSet rs, int rowNumber) throws SQLException {
        return new RateComponent(
                rs.getObject("id", UUID.class),
                RateComponentType.valueOf(rs.getString("component_type")),
                rs.getInt("priority"),
                rs.getLong("amount_minor"),
                // Nullable on every component that is not a distance band, and
                // getInt would answer zero — a band starting at the branch door.
                rs.getObject("band_from_meters", Integer.class),
                rs.getObject("band_to_meters", Integer.class),
                rs.getObject("minimum_paid_seconds", Integer.class));
    }

    private record CardHeader(UUID id, int version, String currency, String status) { }
}
