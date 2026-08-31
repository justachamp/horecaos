package uz.horecaos.platform.fulfillment.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.DeliveryFeePort;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.fulfillment.application.port.RoadDistancePort;
import uz.horecaos.platform.fulfillment.domain.BranchOrigin;
import uz.horecaos.platform.fulfillment.domain.DeliveryFeeResolution;
import uz.horecaos.platform.fulfillment.domain.Haversine;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryFeeCalculator;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceSource;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneCandidate;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;

/**
 * The one total order for the delivery fee (ADR 0037).
 *
 * <p>ADR 0037 exists because there were four plausible places a delivery fee could
 * come from and no document stating the precedence between them. This class is
 * that statement, in one method, executed once per quote. A second entry point
 * that skipped a step or reordered two would be two code paths computing two
 * defensible fees for one address, which is exactly the state the decision was
 * written to leave behind.
 *
 * <p>Steps 1 to 6 run here. Steps 7 to 9 — the minimum basket, the threshold
 * waiver and the promotion benefit — run inside ADR 0018's pipeline, because they
 * compare against the post-discount goods subtotal and that number does not exist
 * until the engine has produced it. What travels between the two is
 * {@link ResolvedDeliveryCharge}, which carries the thresholds without applying
 * them.
 *
 * <p>Nothing here throws for a business answer. An address outside every zone, a
 * branch with no pin, a brand with no tariff: all three are things a storefront
 * has to render, and an exception would turn each into a 500 for a customer who
 * did nothing wrong.
 */
@Service
public class DeliveryFeeResolver implements DeliveryFeePort {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFeeResolver.class);

    private final JdbcServiceZoneStore zones;
    private final JdbcDeliveryTariffStore tariffs;
    private final JdbcDeliveryFeeResolutionStore resolutions;
    private final RoadDistancePort routing;
    private final MeterRegistry meters;

    public DeliveryFeeResolver(
            JdbcServiceZoneStore zones,
            JdbcDeliveryTariffStore tariffs,
            JdbcDeliveryFeeResolutionStore resolutions,
            RoadDistancePort routing,
            MeterRegistry meters) {
        this.zones = zones;
        this.tariffs = tariffs;
        this.resolutions = resolutions;
        this.routing = routing;
        this.meters = meters;
    }

    @Override
    @Transactional
    public ResolvedDeliveryCharge resolve(DeliveryFeeQuery query) {
        DeliveryFeeResolution resolution = run(query);
        resolutions.insert(resolution);
        meters.counter(
                        "horecaos.delivery.fee.resolutions",
                        "outcome",
                        resolution.outcome().name())
                .increment();
        return resolution.toCharge();
    }

    /**
     * The same resolution with nothing written (ADR 0037's {@code simulate}).
     *
     * <p>The control plane has to be able to answer "what would this cost from
     * Chilonzor at 19:00" <em>before</em> activating a zone, not after a customer
     * finds out. It runs the identical method rather than a parallel
     * approximation: a simulator that agrees with the resolver only most of the
     * time is worse than no simulator, because it is believed.
     */
    @Transactional(readOnly = true)
    public DeliveryFeeResolution simulate(DeliveryFeeQuery query) {
        return run(query);
    }

    private DeliveryFeeResolution run(DeliveryFeeQuery query) {
        Map<String, Object> evidence = new LinkedHashMap<>();

        // ---- Step 1. Pricing authority.
        //
        // Before a zone or a tariff is looked up, and consulting nothing but the
        // order's own flag. Uzum Tezkor sets its own delivery price and that price
        // arrives inside the totals the partner supplied. An externally-priced
        // order that reached step 4 and found a zone whose tariff says TARIFF
        // would be charged a HorecaOS fee on top of the one the aggregator already
        // collected — and the order would still reconcile against its own stated
        // total, so nothing would ever fail.
        if (query.pricingAuthority() == PricingAuthority.EXTERNAL) {
            evidence.put("pricingAuthority", PricingAuthority.EXTERNAL.name());
            return refusal(query, DeliveryFeeOutcome.EXTERNALLY_PRICED, "PARTNER_PRICED_ORDER", evidence);
        }

        // ---- Step 1a. The branch must be somewhere.
        //
        // Not one of ADR 0037's numbered steps, and it has to be: every later step
        // measures from this point. A branch at (0, 0) satisfies every containment
        // and distance test in the system while being 6,000 km from every customer,
        // so it would fall out of step 5 as BEYOND_MAX_DISTANCE and send an
        // operator to widen a tariff that is not the problem.
        Optional<JdbcServiceZoneStore.Branch> branch = zones.findBranch(query.tenantId(), query.locationId());
        if (branch.isEmpty()) {
            return refusal(query, DeliveryFeeOutcome.LOCATION_NOT_LOCATED, "LOCATION_NOT_FOUND", evidence);
        }

        BranchOrigin origin;
        try {
            origin = branch.get().origin();
        } catch (BranchOrigin.UnlocatedBranchException unlocated) {
            // The message names which of the two failures it was and what to do
            // about it. Returning "no zones covered this address" instead — which
            // is what a resolver that simply found no candidates would report —
            // would be true and useless.
            evidence.put("refusalDetail", unlocated.getMessage());
            log.warn("Delivery fee resolution refused: location {} cannot originate a zone", query.locationId());
            return refusal(query, DeliveryFeeOutcome.LOCATION_NOT_LOCATED, "BRANCH_NOT_LOCATED", evidence);
        }

        // ---- Step 2a. The catchment guard.
        //
        // Delever's "не принимать заказы из других зон доставки". Evaluated before
        // the delivery zones rather than after, because a branch that will not
        // serve this part of the city should say so rather than price a fee first
        // and then refuse.
        var catchment = zones.catchmentCheck(query.tenantId(), query.locationId(), query.destination(), query.at());
        if (catchment.guardApplies() && !catchment.covered()) {
            evidence.put("catchmentZonesBound", catchment.bound());
            return refusal(query, DeliveryFeeOutcome.OUTSIDE_CATCHMENT, "OUTSIDE_BRANCH_CATCHMENT", evidence);
        }

        // ---- Step 2. Serviceability.
        List<ZoneCandidate> candidates = new ArrayList<>(
                zones.containingDeliveryZones(query.tenantId(), query.locationId(), query.destination(), query.at()));
        if (candidates.isEmpty()) {
            // Never re-homed to a location that does cover it. Substituting a
            // branch changes the menu, the prices, the preparation time and — once
            // per-branch INN exists — the legal entity issuing the receipt, and
            // the customer gets a confirmation for a restaurant they did not
            // choose. Serviceability search is a separate, explicit query that
            // returns candidates for a human to pick from.
            return refusal(query, DeliveryFeeOutcome.OUT_OF_ZONE, "NO_ZONE_COVERS_ADDRESS", evidence);
        }

        // ---- Step 3. Zone selection.
        //
        // Sorted here as well as in SQL. The database's ORDER BY is the same total
        // order, and applying it again in Java is not redundancy for its own sake:
        // it makes the rule testable without a database, and it means a planner
        // change or an index rebuild cannot silently become a pricing change.
        candidates.sort(ZoneCandidate.RANKING);
        ZoneCandidate winner = candidates.getFirst();
        List<UUID> losers =
                candidates.stream().skip(1).map(ZoneCandidate::zoneId).toList();
        evidence.put("candidateCount", candidates.size());
        evidence.put("winningZonePriority", winner.priority());

        // ---- Step 4. Rate table.
        TariffChoice choice = chooseTariff(query, winner);
        if (choice == null) {
            evidence.put("tariffRungsTried", List.of("ZONE", "LOCATION", "BRAND_DEFAULT"));
            return refusal(query, DeliveryFeeOutcome.NO_TARIFF, "NO_TARIFF_CONFIGURED", evidence, winner, losers);
        }
        DeliveryTariff tariff = choice.tariff();
        evidence.put("tariffRung", choice.rung());
        evidence.put("feeSource", tariff.feeSource().name());

        // ---- Step 5. Distance, then the distance gate.
        Distance distance = measure(origin, query, tariff);
        evidence.put("distanceSource", distance.source().name());
        if (distance.source() == DistanceSource.RADIUS_FALLBACK) {
            evidence.put("roadFactorBasisPoints", tariff.roadFactorBasisPoints());
        }
        if (distance.meters() >= tariff.maxDistanceMeters()) {
            // Inside the polygon and past the tariff's reach. A generously drawn
            // district polygon always contains a house no courier will serve at
            // the district price.
            //
            // The reach is half-open, matching the bands. V0032 made it so: an
            // inclusive reach over half-open bands leaves exactly one unpriceable
            // metre at max_distance_meters, which is the fault the tiling rule
            // exists to forbid everywhere else. A legacy branch whose max_distance
            // was inclusive imports as that value plus one, so nothing it used to
            // serve stops being served.
            return new DeliveryFeeResolution(
                    UUID.randomUUID(),
                    query.tenantId(),
                    query.quoteId(),
                    query.locationId(),
                    DeliveryFeeOutcome.BEYOND_MAX_DISTANCE,
                    "BEYOND_TARIFF_MAX_DISTANCE",
                    query.currency(),
                    winner.zoneId(),
                    winner.zoneVersion(),
                    tariff.tariffId(),
                    tariff.version(),
                    null,
                    null,
                    distance.meters(),
                    tariff.distanceMode(),
                    distance.source(),
                    distance.provider(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    winner.minBasketMinor(),
                    winner.freeDeliveryFromMinor(),
                    losers,
                    evidence);
        }

        // ---- Step 6. Computation.
        LocalDateTime localMoment =
                LocalDateTime.ofInstant(query.at(), branch.get().timezone());
        var computation = DeliveryFeeCalculator.compute(tariff, distance.meters(), localMoment);

        Long providerQuote = null;
        long computedFee = computation.computedFeeMinor();
        long finalFee = computation.finalFeeMinor();
        long tariffDiscount = computation.discountMinor();
        if (computation.discount() != null) {
            evidence.put("discountKind", computation.discount().kind().name());
        }
        if (computation.rule() != null && computation.rule().bandSet() != null) {
            // Which table priced this, not merely that a rule matched. A peak
            // window that substitutes the rate table produces a fee no reading of
            // the base bands can explain, and "why was this 24,000" is the question
            // this row exists to answer.
            evidence.put("bandSetInForce", computation.rule().bandSet());
        }
        if (tariff.feeSource() == FeeSource.PROVIDER_QUOTE) {
            // The ADR 0014 pre-quote is non-binding and, today, not wired: no
            // delivery provider adapter reaches this module. Falling back to the
            // tariff and recording it is the stated behaviour — never to zero,
            // which would hand the customer free delivery whenever a provider was
            // slow.
            evidence.put("providerQuoteAvailable", false);
            evidence.put("providerQuoteFallback", "TARIFF");
        }

        return new DeliveryFeeResolution(
                UUID.randomUUID(),
                query.tenantId(),
                query.quoteId(),
                query.locationId(),
                DeliveryFeeOutcome.RESOLVED,
                null,
                query.currency(),
                winner.zoneId(),
                winner.zoneVersion(),
                tariff.tariffId(),
                tariff.version(),
                computation.band().sequence(),
                computation.rule() == null ? null : computation.rule().sequence(),
                distance.meters(),
                tariff.distanceMode(),
                distance.source(),
                distance.provider(),
                providerQuote,
                computedFee,
                finalFee,
                tariffDiscount,
                computation.discount() == null ? null : computation.discount().sequence(),
                winner.minBasketMinor(),
                winner.freeDeliveryFromMinor(),
                losers,
                evidence);
    }

    /**
     * Step 4: the zone's tariff, else the location's, else the brand default, else
     * nothing.
     *
     * <p>Zone outranks location because that is what Delever does and, more to the
     * point, because a zone is the more specific statement: an operator who binds a
     * rate table to a district is saying something about that district that the
     * branch-wide table does not know.
     *
     * <p>Null means no rung answered. There is deliberately no fourth rung of zero:
     * a missing rate table and free delivery must never look alike, because one is
     * a fault somebody has to fix and the other is a commercial decision somebody
     * made.
     */
    private @Nullable TariffChoice chooseTariff(DeliveryFeeQuery query, ZoneCandidate winner) {
        if (winner.deliveryTariffId() != null) {
            Optional<DeliveryTariff> zoneTariff = tariffs.loadActive(query.tenantId(), winner.deliveryTariffId());
            if (zoneTariff.isPresent()) {
                return new TariffChoice("ZONE", zoneTariff.get());
            }
            // A zone naming a tariff with no live version is a configuration fault
            // and not an invitation to fall through: falling through would price
            // the district at the branch's general rate, which is a plausible
            // wrong answer nobody would notice.
            log.warn("Zone {} names tariff {} which has no ACTIVE version", winner.zoneId(), winner.deliveryTariffId());
            return null;
        }

        Optional<UUID> locationTariffId = tariffs.locationTariffId(query.tenantId(), query.locationId(), query.at());
        if (locationTariffId.isPresent()) {
            Optional<DeliveryTariff> found = tariffs.loadActive(query.tenantId(), locationTariffId.get());
            if (found.isPresent()) {
                return new TariffChoice("LOCATION", found.get());
            }
        }

        return tariffs.brandDefaultTariffId(query.tenantId(), query.brandId())
                .flatMap(id -> tariffs.loadActive(query.tenantId(), id))
                .map(found -> new TariffChoice("BRAND_DEFAULT", found))
                .orElse(null);
    }

    /**
     * Step 5's measurement.
     *
     * <p>{@code RADIUS} is haversine and cannot fail. {@code ROAD} asks the routing
     * port and, when it does not answer, multiplies the straight line by the
     * tariff's detour factor and records {@code RADIUS_FALLBACK}. The quote is
     * never failed for a routing timeout — a customer unable to check out because
     * a provider is slow is a worse outcome than a fee that is a little wrong and
     * says so on its own evidence row — and the fallback increments a metric,
     * because a fallback nobody is counting becomes the normal path.
     */
    private Distance measure(BranchOrigin origin, DeliveryFeeQuery query, DeliveryTariff tariff) {
        int straightLine = Haversine.metersBetween(origin.point(), query.destination());

        if (tariff.distanceMode() == DistanceMode.RADIUS) {
            return new Distance(straightLine, DistanceSource.RADIUS, null);
        }

        Optional<RoadDistancePort.RoadDistance> routed =
                routing.distance(origin.point(), query.destination(), tariff.routingProviderInstallationId());
        if (routed.isPresent()) {
            return new Distance(
                    routed.get().meters(), DistanceSource.ROAD, routed.get().provider());
        }

        meters.counter("horecaos.delivery.distance.fallbacks", "mode", DistanceMode.ROAD.name())
                .increment();
        long inflated = Math.multiplyExact((long) straightLine, tariff.roadFactorBasisPoints()) / 10_000L;
        return new Distance(Math.toIntExact(inflated), DistanceSource.RADIUS_FALLBACK, null);
    }

    private DeliveryFeeResolution refusal(
            DeliveryFeeQuery query, DeliveryFeeOutcome outcome, String reasonCode, Map<String, Object> evidence) {
        return refusal(query, outcome, reasonCode, evidence, null, List.of());
    }

    private DeliveryFeeResolution refusal(
            DeliveryFeeQuery query,
            DeliveryFeeOutcome outcome,
            String reasonCode,
            Map<String, Object> evidence,
            @Nullable ZoneCandidate winner,
            List<UUID> losers) {
        return new DeliveryFeeResolution(
                UUID.randomUUID(),
                query.tenantId(),
                query.quoteId(),
                query.locationId(),
                outcome,
                reasonCode,
                query.currency(),
                winner == null ? null : winner.zoneId(),
                winner == null ? null : winner.zoneVersion(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                winner == null ? null : winner.minBasketMinor(),
                winner == null ? null : winner.freeDeliveryFromMinor(),
                losers,
                evidence);
    }

    /** @param rung which precedence step answered, so the evidence explains itself */
    private record TariffChoice(String rung, DeliveryTariff tariff) {}

    private record Distance(int meters, DistanceSource source, @Nullable String provider) {}
}
