package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceAccrual;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.tariff.RoundingRule;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffBand;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffDiscount;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffTimeRule;

/** Rate tables, their versions, and the bands and time rules that hang off a version (ADR 0037). */
@Repository
public class JdbcDeliveryTariffStore {

    private final JdbcClient jdbc;

    public JdbcDeliveryTariffStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------- reads

    /**
     * Step 4's second rung: the branch's own rate table.
     *
     * <p>Windowed, so "we changed our delivery prices in March" is answerable from
     * this table rather than from a second one holding its history.
     */
    public Optional<UUID> locationTariffId(UUID tenantId, UUID locationId, Instant at) {
        return jdbc.sql("""
                SELECT tariff_id FROM fulfillment.location_tariff_bindings
                WHERE tenant_id = :tenantId AND location_id = :locationId
                  AND valid_from <= :at AND (valid_until IS NULL OR valid_until > :at)
                ORDER BY valid_from DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("at", timestamp(at))
                .query(UUID.class)
                .optional();
    }

    /**
     * Step 4's last rung.
     *
     * <p>There is no rung after this one. A brand with no default gets
     * {@code NO_TARIFF} and a refused quote, never a fee of zero: a missing rate
     * table and free delivery must not look alike, because the first is a
     * configuration fault somebody has to fix and the second is a deliberate
     * commercial choice.
     */
    public Optional<UUID> brandDefaultTariffId(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id FROM fulfillment.delivery_tariffs
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND is_brand_default AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Whether this tariff belongs to this brand.
     *
     * <p>The tariff endpoints declare a BRAND-scoped capability, so the brand in
     * the URL is what the caller was authorised for -- but the tariff is reached
     * by id, and the id alone says nothing about which brand owns it. Without
     * this, holding DELIVERY_TARIFF_MANAGE for one brand is enough to redraft or
     * activate another brand's delivery rates, which is a direct route to
     * charging that brand's customers whatever you like.
     */
    public boolean tariffBelongsToBrand(UUID tenantId, UUID brandId, UUID tariffId) {
        return jdbc.sql("""
                SELECT 1 FROM fulfillment.delivery_tariffs
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :tariffId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("tariffId", tariffId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Every rate table this brand has registered, with its live version's
     * headline numbers when it has one (operations §3.7 Delivery tariffs).
     * {@code LEFT JOIN}, the same reason {@code JdbcServiceZoneStore#listZones}
     * gives: a tariff drafted but never activated is a real, visible state.
     */
    public List<TariffSummaryRow> listTariffs(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT t.id, t.code, t.name, t.status, t.is_brand_default,
                       v.version, v.currency, v.fee_source, v.distance_mode, v.max_distance_meters
                  FROM fulfillment.delivery_tariffs t
             LEFT JOIN fulfillment.delivery_tariff_versions v
                    ON v.tenant_id = t.tenant_id AND v.tariff_id = t.id AND v.status = 'ACTIVE'
                 WHERE t.tenant_id = :tenantId AND t.brand_id = :brandId
                 ORDER BY t.code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new TariffSummaryRow(
                        row.getObject("id", UUID.class),
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("status"),
                        row.getBoolean("is_brand_default"),
                        row.getObject("version", Integer.class),
                        row.getString("currency"),
                        row.getString("fee_source"),
                        row.getString("distance_mode"),
                        row.getObject("max_distance_meters", Integer.class)))
                .list();
    }

    /** The lineage row alone — code, name, and the brand-default flag the version rows do not carry. */
    public Optional<TariffSummaryRow> findTariffSummary(UUID tenantId, UUID brandId, UUID tariffId) {
        return jdbc.sql("""
                SELECT t.id, t.code, t.name, t.status, t.is_brand_default,
                       v.version, v.currency, v.fee_source, v.distance_mode, v.max_distance_meters
                  FROM fulfillment.delivery_tariffs t
             LEFT JOIN fulfillment.delivery_tariff_versions v
                    ON v.tenant_id = t.tenant_id AND v.tariff_id = t.id AND v.status = 'ACTIVE'
                 WHERE t.tenant_id = :tenantId AND t.brand_id = :brandId AND t.id = :tariffId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("tariffId", tariffId)
                .query((row, number) -> new TariffSummaryRow(
                        row.getObject("id", UUID.class),
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("status"),
                        row.getBoolean("is_brand_default"),
                        row.getObject("version", Integer.class),
                        row.getString("currency"),
                        row.getString("fee_source"),
                        row.getString("distance_mode"),
                        row.getObject("max_distance_meters", Integer.class)))
                .optional();
    }

    /** The live version of a tariff, with its bands and time rules. */
    public Optional<DeliveryTariff> loadActive(UUID tenantId, UUID tariffId) {
        return load(tenantId, tariffId, null);
    }

    /**
     * One specific version, live or long retired.
     *
     * <p>This is what makes ADR 0037's exit criterion true: a months-old fee is
     * re-derived from the version the resolution row pinned, without executing
     * today's rates.
     */
    public Optional<DeliveryTariff> loadVersion(UUID tenantId, UUID tariffId, int version) {
        return load(tenantId, tariffId, version);
    }

    private Optional<DeliveryTariff> load(UUID tenantId, UUID tariffId, @Nullable Integer version) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("tariffId", tariffId);
        params.put("version", version);

        Optional<Header> header = jdbc.sql("""
                SELECT tariff_id, version, status, currency, fee_source, distance_mode,
                       road_factor_basis_points, routing_provider_installation_id,
                       max_distance_meters, min_fee_minor, max_fee_minor,
                       distance_accrual, fee_rounding_step_minor, fee_rounding_mode
                FROM fulfillment.delivery_tariff_versions
                WHERE tenant_id = :tenantId AND tariff_id = :tariffId
                  AND ((:version::integer IS NULL AND status = 'ACTIVE')
                       OR version = :version::integer)
                """)
                .params(params)
                .query((row, number) -> new Header(
                        row.getObject("tariff_id", UUID.class),
                        row.getInt("version"),
                        VersionStatus.valueOf(row.getString("status")),
                        row.getString("currency"),
                        FeeSource.valueOf(row.getString("fee_source")),
                        DistanceMode.valueOf(row.getString("distance_mode")),
                        row.getInt("road_factor_basis_points"),
                        row.getObject("routing_provider_installation_id", UUID.class),
                        row.getInt("max_distance_meters"),
                        row.getLong("min_fee_minor"),
                        // Nullable, and getLong would answer 0 — which is a real
                        // cap, and the tightest one possible. Every fee under this
                        // tariff would clamp to zero and nothing would fail.
                        row.getObject("max_fee_minor", Long.class),
                        DistanceAccrual.valueOf(row.getString("distance_accrual")),
                        // Null means no rounding step. getLong would answer 0,
                        // which the domain refuses outright — a step of zero is
                        // not a coarser rounding, it is a division by nothing.
                        row.getObject("fee_rounding_step_minor", Long.class),
                        row.getString("fee_rounding_mode") == null
                                ? null
                                : RoundingRule.valueOf(row.getString("fee_rounding_mode"))))
                .optional();

        return header.map(found -> new DeliveryTariff(
                found.tariffId(),
                found.version(),
                found.status(),
                found.currency(),
                found.feeSource(),
                found.distanceMode(),
                found.roadFactorBasisPoints(),
                found.routingProviderInstallationId(),
                found.maxDistanceMeters(),
                found.minFeeMinor(),
                found.maxFeeMinor(),
                found.distanceAccrual(),
                found.feeRoundingStepMinor(),
                found.feeRoundingRule(),
                bands(tenantId, tariffId, found.version()),
                timeRules(tenantId, tariffId, found.version()),
                discounts(tenantId, tariffId, found.version())));
    }

    public List<TariffBand> bands(UUID tenantId, UUID tariffId, int version) {
        return jdbc.sql("""
                SELECT sequence, band_set, from_meters, to_meters, base_minor, per_km_minor
                FROM fulfillment.delivery_tariff_bands
                WHERE tenant_id = :tenantId AND tariff_id = :tariffId AND tariff_version = :version
                ORDER BY band_set, from_meters, sequence
                """)
                .param("tenantId", tenantId)
                .param("tariffId", tariffId)
                .param("version", version)
                .query((row, number) -> new TariffBand(
                        row.getInt("sequence"),
                        row.getString("band_set"),
                        row.getInt("from_meters"),
                        row.getInt("to_meters"),
                        row.getLong("base_minor"),
                        row.getLong("per_km_minor")))
                .list();
    }

    public List<TariffTimeRule> timeRules(UUID tenantId, UUID tariffId, int version) {
        return jdbc.sql("""
                SELECT sequence, priority, day_of_week_mask, local_from_time, local_to_time,
                       band_set, multiplier_basis_points, surcharge_minor
                FROM fulfillment.delivery_tariff_time_rules
                WHERE tenant_id = :tenantId AND tariff_id = :tariffId AND tariff_version = :version
                ORDER BY priority DESC, sequence
                """)
                .param("tenantId", tenantId)
                .param("tariffId", tariffId)
                .param("version", version)
                .query((row, number) -> new TariffTimeRule(
                        row.getInt("sequence"),
                        row.getInt("priority"),
                        row.getShort("day_of_week_mask"),
                        row.getObject("local_from_time", LocalTime.class),
                        row.getObject("local_to_time", LocalTime.class),
                        row.getString("band_set"),
                        row.getInt("multiplier_basis_points"),
                        row.getLong("surcharge_minor")))
                .list();
    }

    /**
     * The rate table's own standing discounts (ADR 0037, V0032).
     *
     * <p>Ordered the way the calculator ranks them, so a reader of either can check
     * the other without holding two orderings in mind.
     */
    public List<TariffDiscount> discounts(UUID tenantId, UUID tariffId, int version) {
        return jdbc.sql("""
                SELECT sequence, priority, discount_kind, amount_minor, allowance_meters,
                       day_of_week_mask, local_from_time, local_to_time
                FROM fulfillment.delivery_tariff_discounts
                WHERE tenant_id = :tenantId AND tariff_id = :tariffId AND tariff_version = :version
                ORDER BY priority DESC, sequence
                """)
                .param("tenantId", tenantId)
                .param("tariffId", tariffId)
                .param("version", version)
                .query((row, number) -> new TariffDiscount(
                        row.getInt("sequence"),
                        row.getInt("priority"),
                        TariffDiscount.Kind.valueOf(row.getString("discount_kind")),
                        // Both nullable and both meaningful at zero: a zero amount
                        // is a discount somebody disabled by hand, and getLong
                        // cannot tell that from an absent column.
                        row.getObject("amount_minor", Long.class),
                        row.getObject("allowance_meters", Integer.class),
                        row.getShort("day_of_week_mask"),
                        row.getObject("local_from_time", LocalTime.class),
                        row.getObject("local_to_time", LocalTime.class)))
                .list();
    }

    public int nextVersion(UUID tenantId, UUID tariffId) {
        return jdbc.sql("""
                SELECT coalesce(max(version), 0) + 1 FROM fulfillment.delivery_tariff_versions
                WHERE tenant_id = :tenantId AND tariff_id = :tariffId
                """)
                .param("tenantId", tenantId)
                .param("tariffId", tariffId)
                .query(Integer.class)
                .single();
    }

    // ------------------------------------------------------------------ writes

    public void insertTariff(
            UUID id, UUID tenantId, UUID brandId, String code, String name, boolean brandDefault, Instant now) {
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_tariffs (
                    id, tenant_id, brand_id, code, name, is_brand_default, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :code, :name, :brandDefault, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("name", name)
                .param("brandDefault", brandDefault)
                .param("now", timestamp(now))
                .update();
    }

    public void insertVersion(UUID id, UUID tenantId, DeliveryTariff draft, UUID createdBy, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("tariffId", draft.tariffId());
        params.put("version", draft.version());
        params.put("currency", draft.currency());
        params.put("feeSource", draft.feeSource().name());
        params.put("distanceMode", draft.distanceMode().name());
        params.put("roadFactor", draft.roadFactorBasisPoints());
        params.put("routingInstallationId", draft.routingProviderInstallationId());
        params.put("maxDistance", draft.maxDistanceMeters());
        params.put("minFee", draft.minFeeMinor());
        params.put("maxFee", draft.maxFeeMinor());
        params.put("accrual", draft.distanceAccrual().name());
        params.put("roundingStep", draft.feeRoundingStepMinor());
        params.put(
                "roundingMode",
                draft.feeRoundingRule() == null ? null : draft.feeRoundingRule().name());
        params.put("createdBy", createdBy);
        params.put("now", timestamp(now));

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_tariff_versions (
                    id, tenant_id, tariff_id, version, status, currency, fee_source,
                    distance_mode, road_factor_basis_points, routing_provider_installation_id,
                    max_distance_meters, min_fee_minor, max_fee_minor,
                    distance_accrual, fee_rounding_step_minor, fee_rounding_mode,
                    created_by, created_at)
                VALUES (:id, :tenantId, :tariffId, :version, 'DRAFT', :currency, :feeSource,
                    :distanceMode, :roadFactor, :routingInstallationId,
                    :maxDistance, :minFee, :maxFee,
                    :accrual, :roundingStep, :roundingMode, :createdBy, :now)
                """).params(params).update();

        for (TariffBand band : draft.bands()) {
            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_tariff_bands (
                        tenant_id, tariff_id, tariff_version, sequence, band_set,
                        from_meters, to_meters, base_minor, per_km_minor)
                    VALUES (:tenantId, :tariffId, :version, :sequence, :bandSet,
                        :from, :to, :base, :perKm)
                    """)
                    .param("tenantId", tenantId)
                    .param("tariffId", draft.tariffId())
                    .param("version", draft.version())
                    .param("sequence", band.sequence())
                    .param("bandSet", band.bandSet())
                    .param("from", band.fromMeters())
                    .param("to", band.toMeters())
                    .param("base", band.baseMinor())
                    .param("perKm", band.perKmMinor())
                    .update();
        }

        for (TariffTimeRule rule : draft.timeRules()) {
            // params(Map) rather than a chain of param(...) calls: band_set is
            // nullable, and JdbcClient throws outright if param and paramSource are
            // mixed, so a HashMap is the only shape that carries a null cleanly.
            Map<String, Object> ruleParams = new HashMap<>();
            ruleParams.put("tenantId", tenantId);
            ruleParams.put("tariffId", draft.tariffId());
            ruleParams.put("version", draft.version());
            ruleParams.put("sequence", rule.sequence());
            ruleParams.put("priority", rule.priority());
            ruleParams.put("dayMask", (short) rule.dayMask());
            ruleParams.put("fromTime", rule.fromTime());
            ruleParams.put("toTime", rule.toTime());
            ruleParams.put("bandSet", rule.bandSet());
            ruleParams.put("multiplier", rule.multiplierBasisPoints());
            ruleParams.put("surcharge", rule.surchargeMinor());

            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_tariff_time_rules (
                        tenant_id, tariff_id, tariff_version, sequence, priority,
                        day_of_week_mask, local_from_time, local_to_time, band_set,
                        multiplier_basis_points, surcharge_minor)
                    VALUES (:tenantId, :tariffId, :version, :sequence, :priority,
                        :dayMask, :fromTime, :toTime, :bandSet, :multiplier, :surcharge)
                    """).params(ruleParams).update();
        }

        for (TariffDiscount discount : draft.discounts()) {
            Map<String, Object> discountParams = new HashMap<>();
            discountParams.put("tenantId", tenantId);
            discountParams.put("tariffId", draft.tariffId());
            discountParams.put("version", draft.version());
            discountParams.put("sequence", discount.sequence());
            discountParams.put("priority", discount.priority());
            discountParams.put("kind", discount.kind().name());
            discountParams.put("amount", discount.amountMinor());
            discountParams.put("allowance", discount.allowanceMeters());
            discountParams.put("dayMask", (short) discount.dayMask());
            discountParams.put("fromTime", discount.fromTime());
            discountParams.put("toTime", discount.toTime());

            jdbc.sql("""
                    INSERT INTO fulfillment.delivery_tariff_discounts (
                        tenant_id, tariff_id, tariff_version, sequence, priority,
                        discount_kind, amount_minor, allowance_meters,
                        day_of_week_mask, local_from_time, local_to_time)
                    VALUES (:tenantId, :tariffId, :version, :sequence, :priority,
                        :kind, :amount, :allowance, :dayMask, :fromTime, :toTime)
                    """).params(discountParams).update();
        }
    }

    /** Retires the live version and promotes this one, in that order and one transaction. */
    public int activateVersion(UUID tenantId, UUID tariffId, int version, UUID actorId, Instant now) {
        jdbc.sql("""
                UPDATE fulfillment.delivery_tariff_versions
                SET status = 'RETIRED', retired_at = :now
                WHERE tenant_id = :tenantId AND tariff_id = :tariffId AND status = 'ACTIVE'
                  AND version <> :version
                """)
                .param("tenantId", tenantId)
                .param("tariffId", tariffId)
                .param("version", version)
                .param("now", timestamp(now))
                .update();

        return jdbc.sql("""
                UPDATE fulfillment.delivery_tariff_versions
                SET status = 'ACTIVE', activated_by = :actorId, activated_at = :now
                WHERE tenant_id = :tenantId AND tariff_id = :tariffId AND version = :version
                  AND status = 'DRAFT'
                """)
                .param("tenantId", tenantId)
                .param("tariffId", tariffId)
                .param("version", version)
                .param("actorId", actorId)
                .param("now", timestamp(now))
                .update();
    }

    public void bindLocation(UUID tenantId, UUID brandId, UUID locationId, UUID tariffId, Instant from) {
        jdbc.sql("""
                INSERT INTO fulfillment.location_tariff_bindings (
                    tenant_id, brand_id, location_id, tariff_id, valid_from)
                VALUES (:tenantId, :brandId, :locationId, :tariffId, :from)
                ON CONFLICT (location_id, valid_from) DO UPDATE SET tariff_id = EXCLUDED.tariff_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("tariffId", tariffId)
                .param("from", timestamp(from))
                .update();
    }

    /**
     * One tariff, lineage plus its live version's headline numbers — {@link
     * #listTariffs} and {@link #findTariffSummary}'s shared projection. A
     * tariff with no {@code ACTIVE} version carries nulls from {@code version}
     * on, the same "drafted, never activated" state a zone can be in.
     */
    public record TariffSummaryRow(
            UUID id,
            String code,
            String name,
            String status,
            boolean brandDefault,
            @Nullable Integer activeVersion,
            @Nullable String currency,
            @Nullable String feeSource,
            @Nullable String distanceMode,
            @Nullable Integer maxDistanceMeters) {}

    private record Header(
            UUID tariffId,
            int version,
            VersionStatus status,
            String currency,
            FeeSource feeSource,
            DistanceMode distanceMode,
            int roadFactorBasisPoints,
            UUID routingProviderInstallationId,
            int maxDistanceMeters,
            long minFeeMinor,
            Long maxFeeMinor,
            DistanceAccrual distanceAccrual,
            Long feeRoundingStepMinor,
            @Nullable RoundingRule feeRoundingRule) {}

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
