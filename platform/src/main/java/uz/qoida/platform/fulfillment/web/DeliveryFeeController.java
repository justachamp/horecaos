package uz.qoida.platform.fulfillment.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.fulfillment.api.DeliveryFeeQuery;
import uz.qoida.platform.fulfillment.api.PricingAuthority;
import uz.qoida.platform.fulfillment.application.DeliveryFeeResolver;
import uz.qoida.platform.fulfillment.domain.DeliveryFeeResolution;
import uz.qoida.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.tenancy.api.GeoPoint;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * Asking what delivery costs, and asking why it cost that (ADR 0037).
 *
 * <p>Three surfaces, one resolver. The storefront asks before there is a cart, the
 * control plane asks before there is a customer, and operations asks weeks after
 * there was both. All three run the identical code path: a simulator that agrees
 * with the resolver only most of the time is worse than no simulator, because it
 * is believed.
 *
 * <p>ADR 0037 spells the simulator as {@code POST .../simulate}. It is a
 * {@code GET} here because it writes nothing and decides nothing, and because the
 * ADR's own serviceability endpoint already carries a point in the query string.
 * A {@code POST} would additionally have to declare itself mutating to satisfy the
 * ADR 0031 build gate and would then demand an idempotency key to protect a write
 * that does not happen.
 */
@RestController
@Tag(name = "Delivery fee", description = "What delivery costs here, and the evidence for it")
public class DeliveryFeeController {

    private final DeliveryFeeResolver resolver;
    private final JdbcDeliveryFeeResolutionStore resolutions;
    private final Clock clock;

    public DeliveryFeeController(DeliveryFeeResolver resolver,
            JdbcDeliveryFeeResolutionStore resolutions, Clock clock) {
        this.resolver = resolver;
        this.resolutions = resolutions;
        this.clock = clock;
    }

    /**
     * "Can this branch deliver here, and for how much."
     *
     * <p>Unauthenticated, like the menu it accompanies: this is what a customer
     * sees before they have an account. It writes nothing, so a customer dragging a
     * pin around a map does not leave a resolution row per pixel.
     *
     * <p>Browse may cache for 30 seconds under ADR 0033. Checkout does not read
     * this endpoint and never a cached answer: it re-resolves inside its own
     * transaction, because a zone retired five minutes ago must not still be
     * selling.
     */
    @GetMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}"
            + "/locations/{locationId}/delivery-fee")
    @Operation(summary = "Resolve the delivery fee for one point",
            description = "Returns one fee, or one stable refusal code and no fee. An address "
                    + "outside every zone of this branch is refused here and never re-homed to a "
                    + "branch that does cover it: substituting one changes the menu, the prices, "
                    + "the preparation time and eventually the legal entity on the receipt.")
    public ResponseEntity<DeliveryFeeView> quote(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId,
            @RequestParam double lat, @RequestParam double lon,
            @RequestParam String currency,
            @RequestParam(defaultValue = "0") long subtotalMinor) {

        DeliveryFeeResolution resolution = resolver.simulate(new DeliveryFeeQuery(
                tenantId, brandId, locationId, null, new GeoPoint(lat, lon), currency,
                subtotalMinor, PricingAuthority.QOIDA, clock.instant()));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(DeliveryFeeView.of(resolution));
    }

    /**
     * "What would this cost from Chilonzor at 19:00."
     *
     * <p>The control plane has to be able to answer that before activating a zone,
     * not after a customer finds out. The instant is supplied rather than taken
     * from the clock, which is the whole point: a peak-hour rule is invisible at
     * eleven in the morning.
     */
    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/delivery/simulations")
    @RequiresCapability(value = Capability.DELIVERY_ZONE_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Simulate a fee at a point, a basket, and an instant",
            description = "Runs the full resolver with a fixed clock and writes nothing. The "
                    + "returned evidence is the same shape the stored evidence takes, so what is "
                    + "seen before activation is what will be recorded after it.")
    public SimulationView simulate(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @RequestParam UUID locationId,
            @RequestParam double lat, @RequestParam double lon,
            @RequestParam String currency,
            @RequestParam(defaultValue = "0") long subtotalMinor,
            @RequestParam(required = false) Instant at) {

        DeliveryFeeResolution resolution = resolver.simulate(new DeliveryFeeQuery(
                tenantId, brandId, locationId, null, new GeoPoint(lat, lon), currency,
                subtotalMinor, PricingAuthority.QOIDA, at == null ? clock.instant() : at));

        return new SimulationView(DeliveryFeeView.of(resolution), resolution.evidence());
    }

    /**
     * "Why was this delivery 18,000 so'm."
     *
     * <p>More than one row is normal and the sequence is often the interesting
     * part: a cart repriced three times resolved three times, and the refusals
     * before the success are what explain a customer's complaint.
     */
    @GetMapping("/api/v1/operations/tenants/{tenantId}/quotes/{quoteId}/delivery-fee-evidence")
    @RequiresCapability(Capability.DELIVERY_FEE_EVIDENCE_READ)
    @Operation(summary = "Every fee resolution recorded against one quote",
            description = "Zone version, tariff version, band, time rule, distance, distance "
                    + "source, and the zones that contained the address and lost the ranking. "
                    + "Carries no address and no coordinates (ADR 0029).")
    public List<EvidenceView> evidence(@PathVariable UUID tenantId, @PathVariable UUID quoteId) {
        return resolutions.forQuote(tenantId, quoteId).stream().map(EvidenceView::of).toList();
    }

    /**
     * The wire shape the storefront branches on.
     *
     * <p>{@code outcome} is a stable code and never rendered. The storefront maps
     * it to wording, which is what lets the wording change without a release here.
     */
    public record DeliveryFeeView(
            String outcome, String reasonCode, boolean available,
            Long feeMinor, String currency,
            Long minBasketMinor, Long freeDeliveryFromMinor,
            Integer distanceMeters, String distanceSource) {

        static DeliveryFeeView of(DeliveryFeeResolution resolution) {
            return new DeliveryFeeView(
                    resolution.outcome().name(), resolution.reasonCode(),
                    !resolution.outcome().isRefusal(),
                    resolution.finalFeeMinor(), resolution.currency(),
                    resolution.minBasketMinor(), resolution.freeDeliveryFromMinor(),
                    resolution.distanceMeters(),
                    resolution.distanceSource() == null ? null : resolution.distanceSource().name());
        }
    }

    public record SimulationView(DeliveryFeeView fee, java.util.Map<String, Object> evidence) { }

    public record EvidenceView(
            UUID resolutionId, int resolutionVersion, String outcome, String reasonCode,
            UUID zoneId, Integer zoneVersion, UUID tariffId, Integer tariffVersion,
            Integer bandSequence, Integer timeRuleSequence,
            Integer distanceMeters, String distanceMode, String distanceSource,
            String routingProvider, Long computedFeeMinor, Long finalFeeMinor, String currency,
            List<UUID> losingZoneIds, Instant createdAt) {

        static EvidenceView of(JdbcDeliveryFeeResolutionStore.ResolutionRow row) {
            return new EvidenceView(row.id(), row.resolutionVersion(), row.outcome().name(),
                    row.reasonCode(), row.zoneId(), row.zoneVersion(), row.tariffId(),
                    row.tariffVersion(), row.bandSequence(), row.timeRuleSequence(),
                    row.distanceMeters(), row.distanceMode(), row.distanceSource(),
                    row.routingProvider(), row.computedFeeMinor(), row.finalFeeMinor(),
                    row.currency(), row.losingZoneIds(), row.createdAt());
        }
    }
}
