package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's own authoring of its delivery rate tables (ADR 0037).
 *
 * <p>The mirror of {@link OperationsServiceZoneController} for tariffs: drawing
 * bands, peak-hour rules and standing discounts is ordinary tenant-owned
 * pricing configuration, and every capability here — {@code
 * DELIVERY_TARIFF_READ}, {@code DELIVERY_TARIFF_MANAGE}, {@code
 * DELIVERY_TARIFF_ACTIVATE} — is already held by {@code TENANT_OWNER}, {@code
 * TENANT_ADMIN} (read and manage) and {@code TENANT_FINANCE} (read and
 * activate, deliberately without manage — "activating a rate table is money;
 * drawing one is not", per {@code Capability.DELIVERY_TARIFF_ACTIVATE}'s own
 * doc) under {@link uz.horecaos.platform.iam.api.PlatformRole}. This
 * controller is the surface those bundles were missing, not a new grant.
 *
 * <p>Delegates to {@link DeliveryTariffService}, so the tiling validation and
 * the ADR 0027 audit trail it now writes are identical to the control-plane
 * surface. As with zones, the actor is the caller's own authenticated
 * identity rather than a request field.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/delivery-tariffs")
@Tag(name = "Delivery tariffs", description = "A tenant's own versioned distance bands and peak-hour rules (ADR 0037)")
public class OperationsDeliveryTariffController {

    private final DeliveryTariffService tariffs;
    private final CurrentActor currentActor;

    public OperationsDeliveryTariffController(DeliveryTariffService tariffs, CurrentActor currentActor) {
        this.tariffs = tariffs;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every rate table this brand has registered")
    public ResponseEntity<List<DeliveryTariffController.TariffSummaryResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(tariffs.listTariffs(tenantId, brandId).stream()
                .map(DeliveryTariffController.TariffSummaryResponse::of)
                .toList());
    }

    @GetMapping("/{tariffId}")
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One tariff's live bands, time rules and discounts",
            description = "activeVersion is absent for a tariff drafted but never activated.")
    public ResponseEntity<DeliveryTariffController.TariffDetailResponse> detail(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID tariffId) {
        try {
            return ResponseEntity.ok(DeliveryTariffController.TariffDetailResponse.of(
                    tariffs.tariffDetail(tenantId, brandId, tariffId)));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Register a rate table",
            description = "The lineage only; the numbers live on versions. At most one tariff per "
                    + "brand may be the default.")
    public ResponseEntity<DeliveryTariffController.TariffView> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody DeliveryTariffController.CreateTariffRequest body) {

        UUID id = tariffs.createTariff(tenantId, brandId, body.code(), body.name(), body.brandDefault());
        return ResponseEntity.ok(new DeliveryTariffController.TariffView(id, body.code(), body.brandDefault()));
    }

    @PostMapping("/{tariffId}/versions")
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a new version of a rate table",
            description = "Bands must tile [0, maxDistanceMeters) with no gap and no overlap. "
                    + "Overlap is refused by the database on insert; the gap check runs at "
                    + "activation.")
    public ResponseEntity<DeliveryTariffController.VersionView> draftVersion(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID tariffId,
            @Valid @RequestBody DraftTariffVersionRequest body) {

        DeliveryTariff draft = new DeliveryTariff(
                tariffId,
                0,
                VersionStatus.DRAFT,
                body.currency(),
                body.feeSource(),
                body.distanceMode(),
                body.roadFactorBasisPoints(),
                body.routingProviderInstallationId(),
                body.maxDistanceMeters(),
                body.minFeeMinor(),
                body.maxFeeMinor(),
                body.distanceAccrual() == null ? DistanceAccrual.STARTED_KILOMETRE : body.distanceAccrual(),
                body.feeRoundingStepMinor(),
                body.feeRoundingRule(),
                bands(body.bands()),
                timeRules(body.timeRules()),
                discounts(body.discounts()));

        var drafted = tariffs.draftVersion(tenantId, brandId, draft, actorId());
        return ResponseEntity.ok(
                new DeliveryTariffController.VersionView(drafted.tariffId(), drafted.version(), "DRAFT"));
    }

    @PostMapping("/{tariffId}/versions/{version}/activate")
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_ACTIVATE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Make a rate table version live",
            description = "Refuses a band gap, an inverted fee bound, and ROAD distance with no "
                    + "routing binding installed. Every problem is returned at once.")
    public ResponseEntity<DeliveryTariffController.VersionView> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID tariffId,
            @PathVariable int version) {

        try {
            tariffs.activate(tenantId, brandId, tariffId, version, actorId());
            return ResponseEntity.ok(new DeliveryTariffController.VersionView(tariffId, version, "ACTIVE"));
        } catch (DeliveryTariffService.TariffActivationRefusedException refused) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, refused.getMessage(), Map.of("problems", refused.problems()));
        } catch (ServiceZoneService.DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/{tariffId}/locations")
    @RequiresCapability(value = Capability.DELIVERY_TARIFF_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Bind the rate table to a branch",
            description = "The middle rung of fee resolution: outranked by a zone's own tariff, "
                    + "and outranking the brand default.")
    public ResponseEntity<Void> bind(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID tariffId,
            @Valid @RequestBody DeliveryTariffController.BindLocationRequest body) {

        tariffs.bindLocation(tenantId, brandId, body.locationId(), tariffId);
        return ResponseEntity.noContent().build();
    }

    /** The caller's own authenticated identity, never a request field. Production Keycloak subjects are UUIDs. */
    private UUID actorId() {
        return UUID.fromString(currentActor.get().subject());
    }

    private static List<TariffBand> bands(List<DeliveryTariffController.BandRequest> requested) {
        List<TariffBand> bands = new ArrayList<>();
        int sequence = 0;
        for (DeliveryTariffController.BandRequest band : requested) {
            bands.add(new TariffBand(
                    sequence++,
                    band.bandSet() == null ? TariffBand.BASE_SET : band.bandSet(),
                    band.fromMeters(),
                    band.toMeters(),
                    band.baseMinor(),
                    band.perKmMinor()));
        }
        return bands;
    }

    private static List<TariffTimeRule> timeRules(List<DeliveryTariffController.TimeRuleRequest> requested) {
        if (requested == null) {
            return List.of();
        }
        List<TariffTimeRule> rules = new ArrayList<>();
        int sequence = 0;
        for (DeliveryTariffController.TimeRuleRequest rule : requested) {
            rules.add(new TariffTimeRule(
                    sequence++,
                    rule.priority(),
                    rule.dayMask(),
                    rule.fromTime(),
                    rule.toTime(),
                    rule.bandSet(),
                    rule.multiplierBasisPoints(),
                    rule.surchargeMinor()));
        }
        return rules;
    }

    private static List<TariffDiscount> discounts(List<DeliveryTariffController.DiscountRequest> requested) {
        if (requested == null) {
            return List.of();
        }
        List<TariffDiscount> discounts = new ArrayList<>();
        int sequence = 0;
        for (DeliveryTariffController.DiscountRequest discount : requested) {
            discounts.add(new TariffDiscount(
                    sequence++,
                    discount.priority(),
                    discount.kind(),
                    discount.amountMinor(),
                    discount.allowanceMeters(),
                    discount.dayMask(),
                    discount.fromTime(),
                    discount.toTime()));
        }
        return discounts;
    }

    /**
     * A new draft version of a tariff, with its bands, time rules and discounts.
     * The control-plane equivalent's {@code actorId} field is absent here: the
     * caller's authenticated identity is used instead, never a request field.
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
            @NotEmpty List<DeliveryTariffController.BandRequest> bands,
            List<DeliveryTariffController.TimeRuleRequest> timeRules,
            List<DeliveryTariffController.DiscountRequest> discounts) {}
}
