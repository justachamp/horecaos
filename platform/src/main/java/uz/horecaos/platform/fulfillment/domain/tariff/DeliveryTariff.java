package uz.horecaos.platform.fulfillment.domain.tariff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import uz.horecaos.platform.fulfillment.domain.VersionStatus;

/**
 * A named, versioned rate table (ADR 0037).
 *
 * <p>The version is the thing a quote pins, and it is immutable once activated.
 * ADR 0037's physical sketch keyed bands by {@code (tariff_id, sequence)}, which
 * cannot meet the ADR's own exit criterion: reconstructing a months-old fee
 * "without executing today's rates" is impossible if editing a band rewrites the
 * rows the old resolution points at. So bands and time rules hang off a version,
 * exactly as a zone's geometry does.
 *
 * <p>V0032 widened this in three places, each because the legacy configuration
 * this model claimed to be "a field mapping" for does something the model could
 * not say: bands belong to a named set so a peak window can substitute a whole
 * table, the accrual and the rounding step are per tariff rather than assumed, and
 * a rate table may carry its own time-windowed discounts.
 *
 * @param maxDistanceMeters the tariff's reach, half-open: it prices
 *                          {@code [0, maxDistanceMeters)} and refuses at and past
 *                          it, even inside the polygon, because a generously drawn
 *                          district always contains a house no courier will serve
 *                          at the district price. Half-open to match the bands —
 *                          an inclusive reach over half-open bands leaves exactly
 *                          one unpriceable metre at the boundary, and the whole
 *                          point of the tiling rule is that there is no such metre.
 *                          A legacy branch whose {@code max_distance} was inclusive
 *                          imports as that value plus one
 * @param maxFeeMinor       null means uncapped. Under {@link FeeSource#PROVIDER_QUOTE}
 *                          this is the cap that stops surge reaching the customer
 * @param feeRoundingStepMinor the multiple every fee lands on, or null for none.
 *                          Paired with {@code feeRoundingRule}: a step with no rule
 *                          and a rule with no step are both half a decision
 */
public record DeliveryTariff(
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
        RoundingRule feeRoundingRule,
        List<TariffBand> bands,
        List<TariffTimeRule> timeRules,
        List<TariffDiscount> discounts) {

    /**
     * A tariff on HorecaOS's own defaults: started kilometres, no rounding step, no
     * standing discount. Every tariff authored in the control plane before V0032
     * meant exactly this.
     */
    public DeliveryTariff(UUID tariffId, int version, VersionStatus status, String currency,
            FeeSource feeSource, DistanceMode distanceMode, int roadFactorBasisPoints,
            UUID routingProviderInstallationId, int maxDistanceMeters, long minFeeMinor,
            Long maxFeeMinor, List<TariffBand> bands, List<TariffTimeRule> timeRules) {
        this(tariffId, version, status, currency, feeSource, distanceMode, roadFactorBasisPoints,
                routingProviderInstallationId, maxDistanceMeters, minFeeMinor, maxFeeMinor,
                DistanceAccrual.STARTED_KILOMETRE, null, null, bands, timeRules, List.of());
    }

    public DeliveryTariff {
        bands = bands == null ? List.of() : List.copyOf(bands);
        timeRules = timeRules == null ? List.of() : List.copyOf(timeRules);
        discounts = discounts == null ? List.of() : List.copyOf(discounts);
        distanceAccrual = distanceAccrual == null ? DistanceAccrual.STARTED_KILOMETRE : distanceAccrual;
        if ((feeRoundingStepMinor == null) != (feeRoundingRule == null)) {
            throw new IllegalArgumentException(
                    "A rounding step and a rounding rule are one decision; set both or neither");
        }
        if (feeRoundingStepMinor != null && feeRoundingStepMinor <= 0) {
            throw new IllegalArgumentException(
                    "A rounding step must be positive; null means no rounding");
        }
    }

    /**
     * Everything that must be true before a version may go live.
     *
     * <p>Returns the problems rather than throwing on the first, so an operator
     * fixing a rate table is shown every fault at once instead of discovering
     * them one activation attempt at a time.
     *
     * <p>The tiling check is the substantial one, and V0032 made it run once per
     * band set. Bands must cover {@code [0, maxDistanceMeters)} with no gap: a gap
     * is what makes 4,700 m unpriceable while 4,600 m and 4,800 m both price fine,
     * and nobody finds that until a customer reports it. A peak set is a complete
     * rate table in its own right, so a peak set with a hole is the same fault
     * confined to a four-hour window — which is strictly harder to find, not
     * easier.
     */
    public List<String> activationProblems() {
        List<String> problems = new ArrayList<>();

        if (bands.isEmpty()) {
            problems.add("A tariff with no bands prices nothing; add at least one band");
            return problems;
        }
        if (distanceMode == DistanceMode.ROAD && routingProviderInstallationId == null) {
            problems.add("ROAD distance needs a routing binding (ADR 0026); none is installed");
        }
        if (maxFeeMinor != null && maxFeeMinor < minFeeMinor) {
            problems.add("The maximum fee is below the minimum fee");
        }

        // Every set a rule can put in force has to be a complete rate table, and
        // the base set has to exist even when every rule substitutes something
        // else — the hours outside every window are still hours.
        Set<String> required = new LinkedHashSet<>();
        required.add(TariffBand.BASE_SET);
        timeRules.forEach(rule -> required.add(rule.effectiveBandSet()));
        for (String set : required) {
            problems.addAll(tilingProblems(set));
        }

        Set<String> known = new LinkedHashSet<>(bands.stream().map(TariffBand::bandSet).toList());
        for (String orphan : known) {
            if (!required.contains(orphan)) {
                problems.add(("Band set '%s' is not put in force by any time rule, so its bands "
                        + "would never price anything").formatted(orphan));
            }
        }

        for (TariffDiscount discount : discounts) {
            if (discount.kind() == TariffDiscount.Kind.DISTANCE_ALLOWANCE
                    && discount.allowanceMeters() > maxDistanceMeters) {
                problems.add(("A distance allowance of %d m exceeds the tariff's %d m reach, so it "
                        + "would waive a fee no address can be charged")
                        .formatted(discount.allowanceMeters(), maxDistanceMeters));
            }
        }
        return problems;
    }

    private List<String> tilingProblems(String set) {
        List<String> problems = new ArrayList<>();
        List<TariffBand> ordered = new ArrayList<>(bandsOf(set));
        if (ordered.isEmpty()) {
            problems.add("Band set '%s' has no bands, so nothing prices while it is in force"
                    .formatted(set));
            return problems;
        }
        ordered.sort(Comparator.comparingInt(TariffBand::fromMeters));

        if (ordered.getFirst().fromMeters() != 0) {
            problems.add("Band set '%s' must start at 0 m; it starts at %d m, so every address "
                    .formatted(set, ordered.getFirst().fromMeters())
                    + "closer than that is unpriceable");
        }
        for (int i = 1; i < ordered.size(); i++) {
            int previousEnd = ordered.get(i - 1).toMeters();
            int nextStart = ordered.get(i).fromMeters();
            if (nextStart != previousEnd) {
                problems.add("Band set '%s' leaves a gap between %d m and %d m; an address in it "
                        .formatted(set, previousEnd, nextStart)
                        + "prices at nothing while its neighbours price fine");
            }
        }
        int coveredTo = ordered.getLast().toMeters();
        if (coveredTo < maxDistanceMeters) {
            problems.add("Band set '%s' covers to %d m but the tariff reaches %d m, leaving the "
                    .formatted(set, coveredTo, maxDistanceMeters)
                    + "last %d m unpriceable".formatted(maxDistanceMeters - coveredTo));
        }
        return problems;
    }

    /** The bands of one set, in distance order. */
    public List<TariffBand> bandsOf(String set) {
        return bands.stream()
                .filter(band -> band.bandSet().equals(set))
                .sorted(Comparator.comparingInt(TariffBand::fromMeters))
                .toList();
    }

    /** The band of the base set containing this distance. Kept for callers that name no set. */
    public Optional<TariffBand> bandFor(int meters) {
        return bandsOf(TariffBand.BASE_SET).stream().filter(band -> band.contains(meters)).findFirst();
    }
}
