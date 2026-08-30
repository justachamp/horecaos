package uz.horecaos.platform.fulfillment.web;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.fulfillment.application.DeliveryTariffService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceAccrual;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.tariff.RoundingRule;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffBand;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffDiscount;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffTimeRule;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Rate tables (ADR 0037).
 *
 * <p>Authoring and activation are split for the same reason they are on zones, and
 * with a sharper edge: a tariff is money. The person who typed a per-kilometre
 * figure with a misplaced digit cannot be the only one who ever reads it.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/delivery-tariffs")
@Tag(name = "Delivery tariffs", description = "Versioned distance bands and peak-hour rules")
public class DeliveryTariffController {

    private final DeliveryTariffService tariffs;

    public DeliveryTariffController(DeliveryTariffService tariffs) {
        this.tariffs = tariffs;
    }

    @PostMapping
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_MANAGE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Register a rate table",
            description = "The lineage only; the numbers live on versions. At most one tariff per "
                    + "brand may be the default, which is the last rung of fee resolution — and "
                    + "there is no rung after it, because a missing rate table and free delivery "
                    + "must never look alike.")
    public ResponseEntity<TariffView> create(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @Valid @RequestBody CreateTariffRequest body) {

        UUID id = tariffs.createTariff(tenantId, brandId, body.code(), body.name(),
                body.brandDefault());
        return ResponseEntity.ok(new TariffView(id, body.code(), body.brandDefault()));
    }

    @PostMapping("/{tariffId}/versions")
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_MANAGE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Draft a new version of a rate table",
            description = "Bands must tile [0, maxDistanceMeters) with no gap and no overlap. "
                    + "Overlap is refused by the database on insert; the gap check runs at "
                    + "activation, because it is a property of the whole set. A gap makes 4,700 m "
                    + "unpriceable while 4,600 m and 4,800 m both price fine.")
    public ResponseEntity<VersionView> draftVersion(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID tariffId,
            @Valid @RequestBody DraftTariffVersionRequest body) {

        DeliveryTariff draft = new DeliveryTariff(tariffId, 0, VersionStatus.DRAFT,
                body.currency(), body.feeSource(), body.distanceMode(),
                body.roadFactorBasisPoints(), body.routingProviderInstallationId(),
                body.maxDistanceMeters(), body.minFeeMinor(), body.maxFeeMinor(),
                body.distanceAccrual() == null
                        ? DistanceAccrual.STARTED_KILOMETRE : body.distanceAccrual(),
                body.feeRoundingStepMinor(), body.feeRoundingRule(),
                bands(body.bands()), timeRules(body.timeRules()), discounts(body.discounts()));

        var drafted = tariffs.draftVersion(tenantId, brandId, draft, body.actorId());
        return ResponseEntity.ok(new VersionView(drafted.tariffId(), drafted.version(), "DRAFT"));
    }

    @PostMapping("/{tariffId}/versions/{version}/activate")
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_ACTIVATE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Make a rate table version live",
            description = "Refuses a band gap, an inverted fee bound, and ROAD distance with no "
                    + "routing binding installed. Every problem is returned at once.")
    public ResponseEntity<VersionView> activate(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID tariffId, @PathVariable int version,
            @Valid @RequestBody ActorRequest body) {

        try {
            tariffs.activate(tenantId, brandId, tariffId, version, body.actorId());
            return ResponseEntity.ok(new VersionView(tariffId, version, "ACTIVE"));
        } catch (DeliveryTariffService.TariffActivationRefusedException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage(),
                    Map.of("problems", refused.problems()));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/{tariffId}/locations")
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_MANAGE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Bind the rate table to a branch",
            description = "The middle rung of fee resolution: outranked by a zone's own tariff, "
                    + "and outranking the brand default.")
    public ResponseEntity<Void> bind(@PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID tariffId, @Valid @RequestBody BindLocationRequest body) {

        tariffs.bindLocation(tenantId, brandId, body.locationId(), tariffId);
        return ResponseEntity.noContent().build();
    }

    private static List<TariffBand> bands(List<BandRequest> requested) {
        List<TariffBand> bands = new java.util.ArrayList<>();
        int sequence = 0;
        for (BandRequest band : requested) {
            bands.add(new TariffBand(sequence++,
                    band.bandSet() == null ? TariffBand.BASE_SET : band.bandSet(),
                    band.fromMeters(), band.toMeters(), band.baseMinor(), band.perKmMinor()));
        }
        return bands;
    }

    private static List<TariffTimeRule> timeRules(List<TimeRuleRequest> requested) {
        if (requested == null) {
            return List.of();
        }
        List<TariffTimeRule> rules = new java.util.ArrayList<>();
        int sequence = 0;
        for (TimeRuleRequest rule : requested) {
            rules.add(new TariffTimeRule(sequence++, rule.priority(), rule.dayMask(),
                    rule.fromTime(), rule.toTime(), rule.bandSet(),
                    rule.multiplierBasisPoints(), rule.surchargeMinor()));
        }
        return rules;
    }

    private static List<TariffDiscount> discounts(List<DiscountRequest> requested) {
        if (requested == null) {
            return List.of();
        }
        List<TariffDiscount> discounts = new java.util.ArrayList<>();
        int sequence = 0;
        for (DiscountRequest discount : requested) {
            discounts.add(new TariffDiscount(sequence++, discount.priority(), discount.kind(),
                    discount.amountMinor(), discount.allowanceMeters(), discount.dayMask(),
                    discount.fromTime(), discount.toTime()));
        }
        return discounts;
    }

    public record CreateTariffRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 200) String name,
            boolean brandDefault) { }

    /**
     * @param roadFactorBasisPoints what a straight line is multiplied by when
     *                              routing does not answer. Never below 10000: a
     *                              factor under 1.0 claims the road is shorter than
     *                              the straight line
     */
    public record DraftTariffVersionRequest(
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull FeeSource feeSource,
            @NotNull DistanceMode distanceMode,
            @Min(10_000) int roadFactorBasisPoints,
            UUID routingProviderInstallationId,
            @Positive int maxDistanceMeters,
            @PositiveOrZero long minFeeMinor,
            @PositiveOrZero Long maxFeeMinor,
            DistanceAccrual distanceAccrual,
            @Positive Long feeRoundingStepMinor,
            RoundingRule feeRoundingRule,
            @NotEmpty List<BandRequest> bands,
            List<TimeRuleRequest> timeRules,
            List<DiscountRequest> discounts,
            @NotNull UUID actorId) { }

    /**
     * @param bandSet null for the base table. A named set is put in force by a time
     *                rule naming it, and is a complete rate table in its own right
     * @param baseMinor the flat charge for entering this band, not the cumulative
     *                  charge for reaching it. Bands accumulate
     */
    public record BandRequest(
            @Size(max = 32) String bandSet,
            @PositiveOrZero int fromMeters,
            @Positive int toMeters,
            @PositiveOrZero long baseMinor,
            @PositiveOrZero long perKmMinor) { }

    /**
     * @param dayMask bit 0 is Monday, so "weekdays" is 31 and "the whole week" is 127
     * @param bandSet the table this rule puts in force, replacing the base one
     *                outright, or null to leave the base standing and only surcharge
     */
    public record TimeRuleRequest(
            int priority,
            @Min(1) @Max(127) int dayMask,
            @NotNull LocalTime fromTime,
            @NotNull LocalTime toTime,
            @Size(max = 32) String bandSet,
            @Positive int multiplierBasisPoints,
            @PositiveOrZero long surchargeMinor) { }

    /**
     * The rate table's own standing discount, capped at the fee when it resolves.
     *
     * @param amountMinor     set exactly when the kind is AMOUNT
     * @param allowanceMeters set exactly when the kind is DISTANCE_ALLOWANCE — the
     *                        first this many metres are free, priced by whichever
     *                        band table is in force
     */
    public record DiscountRequest(
            int priority,
            @NotNull TariffDiscount.Kind kind,
            @PositiveOrZero Long amountMinor,
            @PositiveOrZero Integer allowanceMeters,
            @Min(1) @Max(127) int dayMask,
            @NotNull LocalTime fromTime,
            @NotNull LocalTime toTime) { }

    public record ActorRequest(@NotNull UUID actorId) { }

    public record BindLocationRequest(@NotNull UUID locationId) { }

    public record TariffView(UUID tariffId, String code, boolean brandDefault) { }

    public record VersionView(UUID tariffId, int version, String status) { }
}
