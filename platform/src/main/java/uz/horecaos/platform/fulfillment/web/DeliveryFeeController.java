package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.fulfillment.domain.DeliveryFeeResolution;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Asking what delivery costs, and asking why it cost that (ADR 0037).
 *
 * <p>Three surfaces, one resolver. The storefront asks before there is a cart, the
 * control plane asks before there is a customer, and operations asks weeks after
 * there was both. All three run the identical code path: a simulator that agrees
 * with the resolver only most of the time is worse than no simulator, because it
 * is believed.
 *
 * <p>ADR 0037 spells the simulator as {@code POST .../simulate}, and {@link
 * #quote} matches it since 2026-09-21: both write nothing and decide nothing,
 * and both are {@code POST} anyway, because that is the only way to keep the
 * point they take out of a URL (ADR 0029). {@link #quote}'s own javadoc has
 * the rest of that story -- it used to be the one holdout {@code GET}, kept
 * that way for exactly the reason this paragraph used to give.
 */
@RestController
@Tag(name = "Delivery fee", description = "What delivery costs here, and the evidence for it")
public class DeliveryFeeController {

    private final DeliveryFeeResolver resolver;
    private final JdbcDeliveryFeeResolutionStore resolutions;
    private final Clock clock;

    public DeliveryFeeController(
            DeliveryFeeResolver resolver, JdbcDeliveryFeeResolutionStore resolutions, Clock clock) {
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
     * <p>{@code POST} rather than {@code GET} (2026-09-21 audit follow-up (b)):
     * the point was a query-string parameter until this change, which put a
     * customer's coordinate in the URL -- logged by every proxy on the way in,
     * kept in browser history, and the exact ADR 0029 shape this platform
     * refuses everywhere else. The 30-second public cache a {@code GET} would
     * have allowed goes with it; a {@code POST} response is not cached by an
     * intermediary regardless of the header, so none is set here, and the
     * simulator this shares its resolver with was already unaffected -- it was
     * always ADR 0037's own {@code POST .../simulate} that gave the whole
     * endpoint its "why not a POST" javadoc in the first place, answered here:
     * a write-shaped verb on a handler that writes nothing needs neither a
     * capability declaration nor an idempotency key, and {@code
     * EndpointCapabilityDeclarationTests.isDeliveryFeePreviewEndpoint} records
     * why rather than leaving the exemption to be found later as a violation.
     */
    @PostMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}" + "/locations/{locationId}/delivery-fee")
    @Operation(
            summary = "Resolve the delivery fee for one point",
            description = "Returns one fee, or one stable refusal code and no fee. An address "
                    + "outside every zone of this branch is refused here and never re-homed to a "
                    + "branch that does cover it: substituting one changes the menu, the prices, "
                    + "the preparation time and eventually the legal entity on the receipt.")
    public ResponseEntity<DeliveryFeeView> quote(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestBody DeliveryFeeQuoteRequest body) {

        DeliveryFeeResolution resolution = resolver.simulate(new DeliveryFeeQuery(
                tenantId,
                brandId,
                locationId,
                null,
                new GeoPoint(body.lat(), body.lon()),
                body.currency(),
                body.subtotalMinor() == null ? 0 : body.subtotalMinor(),
                PricingAuthority.HORECAOS,
                clock.instant()));

        return ResponseEntity.ok().body(DeliveryFeeView.of(resolution));
    }

    /**
     * The point and basket to price delivery for.
     *
     * @param subtotalMinor boxed and defaults to 0 when absent -- Jackson 3
     *     refuses a missing primitive outright, and a basket total is
     *     genuinely optional here: this is asked before there may even be a
     *     priced cart.
     */
    public record DeliveryFeeQuoteRequest(
            double lat,
            double lon,
            String currency,
            @Nullable Long subtotalMinor) {}

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
    @Operation(
            summary = "Simulate a fee at a point, a basket, and an instant",
            description = "Runs the full resolver with a fixed clock and writes nothing. The "
                    + "returned evidence is the same shape the stored evidence takes, so what is "
                    + "seen before activation is what will be recorded after it.")
    public SimulationView simulate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam UUID locationId,
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam String currency,
            @RequestParam(defaultValue = "0") long subtotalMinor,
            @RequestParam(required = false) Instant at) {

        DeliveryFeeResolution resolution = resolver.simulate(new DeliveryFeeQuery(
                tenantId,
                brandId,
                locationId,
                null,
                new GeoPoint(lat, lon),
                currency,
                subtotalMinor,
                PricingAuthority.HORECAOS,
                at == null ? clock.instant() : at));

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
    @Operation(
            summary = "Every fee resolution recorded against one quote",
            description = "Zone version, tariff version, band, time rule, distance, distance "
                    + "source, and the zones that contained the address and lost the ranking. "
                    + "Carries no address and no coordinates (ADR 0029).")
    public List<EvidenceView> evidence(@PathVariable UUID tenantId, @PathVariable UUID quoteId) {
        return resolutions.forQuote(tenantId, quoteId).stream()
                .map(EvidenceView::of)
                .toList();
    }

    /**
     * The wire shape the storefront branches on.
     *
     * <p>{@code outcome} is a stable code and never rendered. The storefront maps
     * it to wording, which is what lets the wording change without a release here.
     */
    public record DeliveryFeeView(
            String outcome,
            @Nullable String reasonCode,
            boolean available,
            @Nullable Long feeMinor,
            String currency,
            @Nullable Long minBasketMinor,
            @Nullable Long freeDeliveryFromMinor,
            @Nullable Integer distanceMeters,
            @Nullable String distanceSource) {

        static DeliveryFeeView of(DeliveryFeeResolution resolution) {
            return new DeliveryFeeView(
                    resolution.outcome().name(),
                    resolution.reasonCode(),
                    !resolution.outcome().isRefusal(),
                    resolution.finalFeeMinor(),
                    resolution.currency(),
                    resolution.minBasketMinor(),
                    resolution.freeDeliveryFromMinor(),
                    resolution.distanceMeters(),
                    resolution.distanceSource() == null
                            ? null
                            : resolution.distanceSource().name());
        }
    }

    public record SimulationView(DeliveryFeeView fee, java.util.Map<String, Object> evidence) {}

    public record EvidenceView(
            UUID resolutionId,
            int resolutionVersion,
            String outcome,
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
            Long computedFeeMinor,
            Long finalFeeMinor,
            String currency,
            List<UUID> losingZoneIds,
            Instant createdAt) {

        static EvidenceView of(JdbcDeliveryFeeResolutionStore.ResolutionRow row) {
            return new EvidenceView(
                    row.id(),
                    row.resolutionVersion(),
                    row.outcome().name(),
                    row.reasonCode(),
                    row.zoneId(),
                    row.zoneVersion(),
                    row.tariffId(),
                    row.tariffVersion(),
                    row.bandSequence(),
                    row.timeRuleSequence(),
                    row.distanceMeters(),
                    row.distanceMode(),
                    row.distanceSource(),
                    row.routingProvider(),
                    row.computedFeeMinor(),
                    row.finalFeeMinor(),
                    row.currency(),
                    row.losingZoneIds(),
                    row.createdAt());
        }
    }
}
