package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.domain.DeliveryFeeResolution;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceSource;

/**
 * The evidence row (ADR 0037).
 *
 * <p>Write-once. Nothing here updates a resolution, because a resolution is a
 * statement about one moment and amending it would destroy the only record of
 * what was actually decided — which is the whole reason the table exists.
 */
@Repository
public class JdbcDeliveryFeeResolutionStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDeliveryFeeResolutionStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(DeliveryFeeResolution resolution) {
        // A HashMap because more than half of these are legitimately null on a
        // refusal, and Map.of rejects a null value outright.
        Map<String, Object> params = new HashMap<>();
        params.put("id", resolution.id());
        params.put("tenantId", resolution.tenantId());
        params.put("quoteId", resolution.quoteId());
        params.put("locationId", resolution.locationId());
        params.put("resolutionVersion", DeliveryFeeResolution.RESOLUTION_VERSION);
        params.put("outcome", resolution.outcome().name());
        params.put("reasonCode", resolution.reasonCode());
        params.put("currency", resolution.currency());
        params.put("zoneId", resolution.zoneId());
        params.put("zoneVersion", resolution.zoneVersion());
        params.put("tariffId", resolution.tariffId());
        params.put("tariffVersion", resolution.tariffVersion());
        params.put("bandSequence", resolution.bandSequence());
        params.put("timeRuleSequence", resolution.timeRuleSequence());
        params.put("distanceMeters", resolution.distanceMeters());
        params.put("distanceMode", name(resolution.distanceMode()));
        params.put("distanceSource", name(resolution.distanceSource()));
        params.put("routingProvider", resolution.routingProvider());
        params.put("providerQuote", resolution.providerQuoteMinor());
        params.put("computedFee", resolution.computedFeeMinor());
        params.put("finalFee", resolution.finalFeeMinor());
        params.put("tariffDiscount", resolution.tariffDiscountMinor());
        params.put("discountSequence", resolution.discountSequence());
        params.put("losingZoneIds", resolution.losingZoneIds().toArray(UUID[]::new));
        params.put("evidence", objectMapper.writeValueAsString(resolution.evidence()));

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_fee_resolutions (
                    id, tenant_id, quote_id, location_id, resolution_version, outcome, reason_code,
                    zone_id, zone_version, tariff_id, tariff_version,
                    band_sequence, time_rule_sequence,
                    distance_meters, distance_mode, distance_source, routing_provider,
                    provider_quote_minor, computed_fee_minor, final_fee_minor,
                    tariff_discount_minor, discount_sequence, currency,
                    losing_zone_ids, evidence)
                VALUES (
                    :id, :tenantId, :quoteId, :locationId, :resolutionVersion, :outcome, :reasonCode,
                    :zoneId, :zoneVersion, :tariffId, :tariffVersion,
                    :bandSequence, :timeRuleSequence,
                    :distanceMeters, :distanceMode, :distanceSource, :routingProvider,
                    :providerQuote, :computedFee, :finalFee,
                    :tariffDiscount, :discountSequence, :currency,
                    :losingZoneIds, CAST(:evidence AS jsonb))
                """).params(params).update();
    }

    /**
     * The operations console's "why was this fee this much".
     *
     * <p>Latest first, and more than one row is normal: a cart repriced three
     * times resolved three times, and the sequence of refusals before a success is
     * often the interesting part.
     */
    public List<ResolutionRow> forQuote(UUID tenantId, UUID quoteId) {
        return jdbc.sql("""
                SELECT id, quote_id, location_id, resolution_version, outcome, reason_code,
                       zone_id, zone_version, tariff_id, tariff_version,
                       band_sequence, time_rule_sequence,
                       distance_meters, distance_mode, distance_source, routing_provider,
                       provider_quote_minor, computed_fee_minor, final_fee_minor,
                       tariff_discount_minor, discount_sequence, currency,
                       losing_zone_ids, created_at
                FROM fulfillment.delivery_fee_resolutions
                WHERE tenant_id = :tenantId AND quote_id = :quoteId
                ORDER BY created_at DESC, id
                """)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query((row, number) -> new ResolutionRow(
                        row.getObject("id", UUID.class),
                        row.getObject("quote_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getInt("resolution_version"),
                        DeliveryFeeOutcome.valueOf(row.getString("outcome")),
                        row.getString("reason_code"),
                        row.getObject("zone_id", UUID.class),
                        // Every nullable number below goes through getObject.
                        // getInt answers 0 for SQL NULL, and 0 is a valid version,
                        // a valid band sequence and a valid distance — a silent
                        // zero here would claim a refused resolution had been
                        // priced by version 0 of a zone at the branch's own door.
                        row.getObject("zone_version", Integer.class),
                        row.getObject("tariff_id", UUID.class),
                        row.getObject("tariff_version", Integer.class),
                        row.getObject("band_sequence", Integer.class),
                        row.getObject("time_rule_sequence", Integer.class),
                        row.getObject("distance_meters", Integer.class),
                        row.getString("distance_mode"),
                        row.getString("distance_source"),
                        row.getString("routing_provider"),
                        row.getObject("provider_quote_minor", Long.class),
                        row.getObject("computed_fee_minor", Long.class),
                        row.getObject("final_fee_minor", Long.class),
                        row.getObject("tariff_discount_minor", Long.class),
                        row.getObject("discount_sequence", Integer.class),
                        row.getString("currency"),
                        uuidList(row.getArray("losing_zone_ids")),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public Optional<ResolutionRow> latestForQuote(UUID tenantId, UUID quoteId) {
        return forQuote(tenantId, quoteId).stream().findFirst();
    }

    public record ResolutionRow(
            UUID id,
            UUID quoteId,
            UUID locationId,
            int resolutionVersion,
            DeliveryFeeOutcome outcome,
            String reasonCode,
            UUID zoneId,
            Integer zoneVersion,
            UUID tariffId,
            Integer tariffVersion,
            Integer bandSequence,
            Integer timeRuleSequence,
            Integer distanceMeters,
            String distanceMode,
            String distanceSource,
            String routingProvider,
            Long providerQuoteMinor,
            Long computedFeeMinor,
            Long finalFeeMinor,
            Long tariffDiscountMinor,
            Integer discountSequence,
            String currency,
            List<UUID> losingZoneIds,
            java.time.Instant createdAt) {}

    /**
     * Reads a {@code uuid[]} without assuming the driver's element type.
     *
     * <p>The JDBC contract says {@link java.sql.Array#getArray()} returns
     * {@code Object}; pgjdbc happens to hand back {@code UUID[]} today. Casting to
     * that directly compiles fine and would fail at runtime on the day it does
     * not, which is exactly the kind of breakage that surfaces first in
     * production.
     */
    private static List<UUID> uuidList(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] elements = (Object[]) array.getArray();
        return java.util.Arrays.stream(elements).map(UUID.class::cast).toList();
    }

    private static @Nullable String name(@Nullable DistanceMode mode) {
        return mode == null ? null : mode.name();
    }

    private static @Nullable String name(@Nullable DistanceSource source) {
        return source == null ? null : source.name();
    }
}
