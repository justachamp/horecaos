package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;

/**
 * Which partner is asked first when several answered (ADR 0014 "Provider
 * selection").
 *
 * <p>A pure function, for the reason {@link SourcingPlanner} is one: ADR 0014
 * requires a selection to be "explainable and reproducible from stored evidence",
 * and that is only true of a ranking whose every input is an argument. The
 * evidence is the {@code delivery_quotes} rows and this class's version.
 *
 * <h2>What this ranks on, and what it deliberately does not</h2>
 *
 * <p>ADR 0014 lists six scoring inputs. Three of them — historical acceptance,
 * cancellation and lateness rates — need a history no partner has yet produced
 * here, and a weight applied to an empty history is a number that looks
 * considered and decides nothing. They are left out rather than defaulted, and
 * the version below is what makes their arrival visible as a change of decision
 * rather than as a drift.
 *
 * <p>What is left is ordered by how badly getting it wrong hurts:
 *
 * <ol>
 *   <li><b>Can this partner be at the branch in time at all.</b> A quote whose
 *       pickup ETA lands after the pickup window has closed is not a cheaper
 *       option, it is a late delivery with a commission attached. Excluded, not
 *       ranked last, because ranking it last still books it when it is the only
 *       one left.</li>
 *   <li><b>An expired quote is not an answer.</b> Both verified partners state a
 *       price with no TTL of their own, so the TTL is HorecaOS's; a quote past it is
 *       excluded rather than used, because the alternative is booking against a
 *       number the partner never promised to honour.</li>
 *   <li><b>Price.</b> The customer's fee is snapshotted at checkout and never
 *       raised (ADR 0013), so every som of difference between two partners is a
 *       som the tenant or the platform absorbs. This is the whole reason to quote
 *       more than one.</li>
 *   <li><b>Pickup ETA, then the configured binding order, then the binding id.</b>
 *       Deterministic tie-breaking, so two identical situations rank identically
 *       on any machine and after any {@code VACUUM FULL}.</li>
 * </ol>
 *
 * <p>A partner that returned no quote is ranked <em>after</em> every priced one
 * but is not excluded. Neither verified partner has to answer a quote call, and a
 * fleet-less tenant whose only partner cannot quote must still be able to source
 * an order — falling back to the configured binding order is the honest
 * degradation, and the {@code REFUSED} quote row says which happened.
 */
public final class QuoteScoring {

    /** Bumped when the ordering above changes, so a re-ranking is visible as one. */
    public static final int SCORING_VERSION = 1;

    /** No quote at all. Not a refusal by the partner — nobody asked, or nobody could. */
    public static final String NOT_QUOTED = "NOT_QUOTED";

    /** Priced, but the courier cannot reach the branch before the window shuts. */
    public static final String PICKUP_ETA_MISSES_WINDOW = "PICKUP_ETA_MISSES_WINDOW";

    /** Priced, and the price is older than the TTL it was given. */
    public static final String QUOTE_EXPIRED = "QUOTE_EXPIRED";

    /** The partner said no, and {@code failureCode} says why. */
    public static final String PARTNER_REFUSED = "PARTNER_REFUSED";

    public static final String QUOTED = "QUOTED";

    private QuoteScoring() {}

    /**
     * Ranks every configured partner for one plan, from its quotes and the pickup plan.
     *
     * @param partners the branch's bindings in the order ADR 0026 resolved them,
     *                 narrowest first. That order is the tie-break of last resort
     *                 and the whole answer when nothing could be quoted
     * @param quotes   at most one per binding. A second for the same binding is a
     *                 caller bug rather than a case to resolve here, and the last
     *                 one wins so the newest answer is the one scored
     */
    public static List<ScoredPartner> rank(
            List<PartnerOption> partners, List<DeliveryQuote> quotes, PickupPlan plan, Instant now) {

        Map<UUID, DeliveryQuote> byBinding = quotes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        DeliveryQuote::bindingId, Function.identity(), (first, second) -> second));

        List<ScoredPartner> scored = new ArrayList<>(partners.size());
        for (int position = 0; position < partners.size(); position++) {
            PartnerOption partner = partners.get(position);
            scored.add(score(partner, byBinding.get(partner.bindingId()), plan, now, position));
        }
        scored.sort(RANKING);
        return List.copyOf(scored);
    }

    /**
     * Every partner in rank order, ineligible ones last.
     *
     * <p>The whole list rather than the eligible half, because the planner tells
     * "this branch has no delivery binding at all" apart from "every one of them
     * refused this plan" by whether the list it was given was empty — and those
     * two send an operator to two different screens.
     */
    public static List<PartnerOption> ranked(List<ScoredPartner> scored) {
        return scored.stream().map(ScoredPartner::partner).toList();
    }

    /** The partners that may actually be booked, cheapest first. */
    public static List<PartnerOption> order(List<ScoredPartner> scored) {
        return scored.stream()
                .filter(ScoredPartner::eligible)
                .map(ScoredPartner::partner)
                .toList();
    }

    private static ScoredPartner score(
            PartnerOption partner,
            @Nullable DeliveryQuote quote,
            PickupPlan plan,
            Instant now,
            int configuredPosition) {

        if (quote == null) {
            return new ScoredPartner(partner, null, true, NOT_QUOTED, configuredPosition);
        }
        if (!quote.priced()) {
            return new ScoredPartner(partner, quote, false, PARTNER_REFUSED, configuredPosition);
        }
        if (!quote.usableAt(now)) {
            return new ScoredPartner(partner, quote, false, QUOTE_EXPIRED, configuredPosition);
        }
        if (quote.pickupEtaSeconds() != null
                && now.plusSeconds(quote.pickupEtaSeconds()).isAfter(plan.pickupWindowEnd())) {
            return new ScoredPartner(partner, quote, false, PICKUP_ETA_MISSES_WINDOW, configuredPosition);
        }
        return new ScoredPartner(partner, quote, true, QUOTED, configuredPosition);
    }

    /**
     * Eligible first, then priced before unpriced, then cheapest, then soonest at
     * the branch, then the configured order, then the id.
     *
     * <p>Every step after price exists so that two runs over the same evidence
     * produce the same list. A comparator that stops at price leaves ties to
     * whatever order the rows arrived in, and a selection that depends on that is
     * one nobody can reproduce.
     */
    private static final Comparator<ScoredPartner> RANKING = Comparator.comparing(
                    (ScoredPartner scored) -> !scored.eligible())
            .thenComparing(scored -> scored.quote() == null || !scored.quote().priced())
            .thenComparingLong(ScoredPartner::priceOrMax)
            .thenComparingLong(ScoredPartner::pickupEtaOrMax)
            .thenComparingInt(ScoredPartner::configuredPosition)
            .thenComparing(scored -> scored.partner().bindingId());

    /**
     * One partner, its quote, and why it did or did not make the cut.
     *
     * @param eligible whether this partner may be booked at all. False is a
     *                 refusal to book, not a preference against it
     * @param reason   a stable code, kept even for the winner, because "why was
     *                 this one chosen" and "why was that one not" are the same
     *                 question asked from two ends
     */
    public record ScoredPartner(
            PartnerOption partner,
            @Nullable DeliveryQuote quote,
            boolean eligible,
            String reason,
            int configuredPosition) {

        public ScoredPartner {
            Objects.requireNonNull(partner, "A partner option is required");
            Objects.requireNonNull(reason, "A scoring reason is required");
        }

        long priceOrMax() {
            return quote == null || quote.priceMinor() == null ? Long.MAX_VALUE : quote.priceMinor();
        }

        long pickupEtaOrMax() {
            return quote == null || quote.pickupEtaSeconds() == null ? Long.MAX_VALUE : quote.pickupEtaSeconds();
        }
    }
}
