package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Grouping;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.IntRange;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSet;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.TimeWindow;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;

/**
 * Why a dispatch rule document may not be published (ADR 0142 "Publish validation").
 *
 * <p>Sentences a person can act on, never codes; empty when the document may be published.
 * Pure: everything it needs to know about the tenant -- which installations, zones, channels and
 * branches exist -- arrives in {@link Context}, so the same rules run at publish, in the
 * simulator for a draft, and in a test without a database.
 *
 * <p>Three kinds of refusal, in the order a wrong set of rules does damage:
 *
 * <ol>
 *   <li><b>Names that do not exist or are not this tenant's.</b> Every installation must be this
 *       tenant's, category {@code DELIVERY} and not retired; every zone must exist and be a
 *       delivery zone; sources must be members of ADR 0036's closed set. A rule cannot send an
 *       order to a partner the tenant does not have.</li>
 *   <li><b>Rules that can never fire or never source.</b> A rule shadowed by an enabled rule above
 *       it that matches everything it matches; a start that leaves no time for a partner lane the
 *       rule enables; an installation both preferred and excluded.</li>
 *   <li><b>Options the record reserves.</b> {@code holdBeforeConfirm} while the planner cannot
 *       emit a hold; grouping until the pay treatment for a run is decided.</li>
 * </ol>
 *
 * Rule order matters, and a wrong set can send every order to the dearest partner or to nobody.
 * This and the simulator reduce that; they do not remove it (ADR 0142 "Negative consequences").
 */
public final class DispatchRulesValidator {

    /** A rule id is lower case, digits and dashes: it is typed by an operator, read on a board and stored in a column of 64. */
    public static final Pattern RULE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    public static final int MAX_NAME_LENGTH = 120;

    /** The longest a list of ids in one condition may be. A rule naming more than this is a list of everything. */
    public static final int MAX_LIST = 50;

    private static final int MAX_PREP_MINUTES = 1440;
    private static final int MAX_DISTANCE_METERS = 100_000;

    private DispatchRulesValidator() {}

    /**
     * What the validator needs to know about the tenant.
     *
     * @param installations ids of this tenant's non-retired {@code DELIVERY} installations
     * @param zones         ids of this tenant's delivery-role zones
     * @param channels      ids of this tenant's sales channels
     * @param locations     ids of the branches the document's scope covers
     * @param timing        the {@code fulfillment.sourcing} numbers in force at the scope, for the
     *                      "leaves no time for a partner" check
     * @param groupingAllowed whether this deployment lets a rule enable grouping (see the ADR's open
     *                      input on how a courier is paid for a run)
     */
    public record Context(
            Set<UUID> installations,
            Set<UUID> zones,
            Set<UUID> channels,
            Set<UUID> locations,
            DeliverySourcingPolicy timing,
            boolean groupingAllowed) {

        public Context {
            installations = Set.copyOf(installations);
            zones = Set.copyOf(zones);
            channels = Set.copyOf(channels);
            locations = Set.copyOf(locations);
            Objects.requireNonNull(timing, "The sourcing timing in force is required");
        }
    }

    public static List<String> violations(DispatchRulesDocument document, Context context) {
        List<String> found = new ArrayList<>();
        if (document.schema() != DispatchRulesDocument.SCHEMA) {
            found.add("The document schema must be " + DispatchRulesDocument.SCHEMA);
        }
        if (document.rules().size() > DispatchRulesDocument.MAX_RULES) {
            found.add("A document holds at most %d rules".formatted(DispatchRulesDocument.MAX_RULES));
        }

        Set<String> seen = new HashSet<>();
        for (Rule rule : document.rules()) {
            String label = "Rule \"" + rule.id() + "\"";
            if (!RULE_ID.matcher(rule.id()).matches()) {
                found.add("Rule id \"%s\" must be lower case letters, digits and dashes, at most 63 characters"
                        .formatted(abbreviated(rule.id())));
            } else if (!seen.add(rule.id())) {
                found.add("Rule id \"" + rule.id() + "\" is used more than once");
            }
            if (rule.name().isBlank() || rule.name().length() > MAX_NAME_LENGTH) {
                found.add(label + " needs a name of up to %d characters".formatted(MAX_NAME_LENGTH));
            }
            conditionViolations(label, rule.when(), context, found);
            actionViolations(label, rule.then(), rule.when(), context, found);
        }
        actionViolations("The default", document.fallback(), Conditions.any(), context, found);
        shadowViolations(document, found);
        return List.copyOf(found);
    }

    // ------------------------------------------------------------ conditions

    private static void conditionViolations(String label, Conditions when, Context context, List<String> found) {
        for (String source : when.sources()) {
            if (Arrays.stream(SalesChannelSystemType.values())
                    .noneMatch(type -> type.name().equals(source))) {
                found.add(label + ": \"" + abbreviated(source) + "\" is not a sales channel type");
            }
        }
        listViolations(label, "sources", when.sources().size(), distinct(when.sources()), found);
        idViolations(
                label, "channels", when.channelIds(), context.channels(), "a sales channel of this company", found);
        idViolations(label, "zones", when.zoneIds(), context.zones(), "a delivery zone of this company", found);
        idViolations(label, "branches", when.locationIds(), context.locations(), "a branch inside this scope", found);

        rangeViolations(label, "preparation minutes", when.prepMinutes(), MAX_PREP_MINUTES, found);
        rangeViolations(label, "distance in metres", when.distanceMeters(), MAX_DISTANCE_METERS, found);

        TimeWindow window = when.localTime();
        if (window != null) {
            int from = TimeWindow.minuteOfDay(window.from());
            int to = TimeWindow.minuteOfDay(window.to());
            if (from < 0 || from >= 1440 || to <= 0) {
                found.add(label + ": the time window must be HH:mm from 00:00 to 24:00");
            } else if (from == to) {
                found.add(label + ": the time window starts and ends at the same time");
            }
            if (!distinct(window.days())) {
                found.add(label + ": a weekday is listed twice");
            }
        }
    }

    private static void idViolations(
            String label, String what, List<UUID> ids, Set<UUID> allowed, String meaning, List<String> found) {
        listViolations(label, what, ids.size(), distinct(ids), found);
        for (UUID id : ids) {
            if (!allowed.contains(id)) {
                found.add(label + ": " + id + " is not " + meaning);
            }
        }
    }

    private static void listViolations(String label, String what, int size, boolean distinct, List<String> found) {
        if (size > MAX_LIST) {
            found.add(label + ": at most %d %s may be listed".formatted(MAX_LIST, what));
        }
        if (!distinct) {
            found.add(label + ": " + what + " lists the same value twice");
        }
    }

    private static void rangeViolations(
            String label, String what, @Nullable IntRange range, int ceiling, List<String> found) {
        if (range == null) {
            return;
        }
        if (range.min() == null && range.max() == null) {
            found.add(label + ": the " + what + " condition needs a minimum, a maximum, or neither");
            return;
        }
        if ((range.min() != null && range.min() < 0) || (range.max() != null && range.max() > ceiling)) {
            found.add(label + ": the " + what + " must be between 0 and " + ceiling);
        }
        if (range.min() != null && range.max() != null && range.min() > range.max()) {
            found.add(label + ": the " + what + " minimum is above its maximum");
        }
    }

    // --------------------------------------------------------------- actions

    private static void actionViolations(
            String label, Action action, Conditions when, Context context, List<String> found) {

        PartnerSet partners = action.partners();
        idViolations(
                label,
                "preferred partners",
                partners.order(),
                context.installations(),
                "an active delivery installation of this company",
                found);
        idViolations(
                label,
                "excluded partners",
                partners.exclude(),
                context.installations(),
                "an active delivery installation of this company",
                found);
        for (UUID id : partners.order()) {
            if (partners.exclude().contains(id)) {
                found.add(label + ": " + id + " is both preferred and excluded, so it could never be used");
            }
        }
        if (!partners.order().isEmpty() && !action.mode().usesPartners()) {
            found.add(label + ": partners are listed but " + action.mode() + " never asks one");
        }

        Start start = action.dispatchAt();
        if (start.offsetSeconds() < Start.MIN_OFFSET_SECONDS || start.offsetSeconds() > Start.MAX_OFFSET_SECONDS) {
            found.add(label
                    + ": the dispatch offset must be between %d and +%d minutes"
                            .formatted(Start.MIN_OFFSET_SECONDS / 60, Start.MAX_OFFSET_SECONDS / 60));
        } else if (action.mode() != SourcingMode.MANUAL) {
            timeViolation(label, action, when, context.timing(), found);
        }

        if (Boolean.TRUE.equals(action.holdBeforeConfirm())) {
            found.add(label
                    + ": holding a partner booking before confirming it is not available -- sourcing "
                    + "cannot emit a hold yet, and a hold nobody can reconcile is worse than the commission it saves");
        }

        Grouping grouping = action.grouping();
        if (grouping != null) {
            if (!context.groupingAllowed()) {
                found.add(label
                        + ": grouping is not available yet -- how a courier is paid for one run of several orders "
                        + "has to be decided first");
            }
            if (grouping.mergeRadiusMeters() < Grouping.MIN_RADIUS_METERS
                    || grouping.mergeRadiusMeters() > Grouping.MAX_RADIUS_METERS) {
                found.add(label
                        + ": the merge radius must be between %d and %d metres"
                                .formatted(Grouping.MIN_RADIUS_METERS, Grouping.MAX_RADIUS_METERS));
            }
            if (grouping.maxOrdersPerRun() < Grouping.MIN_ORDERS_PER_RUN
                    || grouping.maxOrdersPerRun() > Grouping.MAX_ORDERS_PER_RUN) {
                found.add(label
                        + ": a run carries between %d and %d orders"
                                .formatted(Grouping.MIN_ORDERS_PER_RUN, Grouping.MAX_ORDERS_PER_RUN));
            }
            if (grouping.maxWaitSeconds() < 0 || grouping.maxWaitSeconds() > Grouping.MAX_WAIT_SECONDS) {
                found.add(label
                        + ": the longest wait for a grouped courier is %d minutes"
                                .formatted(Grouping.MAX_WAIT_SECONDS / 60));
            }
            if (!action.mode().usesFleet()) {
                found.add(label + ": grouping biases the in-house fleet, which " + action.mode() + " never asks");
            }
        }
    }

    /**
     * The dispatch start must leave time for the lane it enables. A start after the last moment a
     * partner could still arrive inside the pickup window books a partner that is late whatever
     * happens; one after the last assignment instant sources nothing at all.
     */
    private static void timeViolation(
            String label, Action action, Conditions when, DeliverySourcingPolicy timing, List<String> found) {

        Duration smallestPreparation = Duration.ofMinutes(
                when.prepMinutes() == null || when.prepMinutes().min() == null
                        ? 0
                        : when.prepMinutes().min());
        long fromReady = PickupPlan.secondsFromReady(smallestPreparation, timing, action.dispatchAt());

        if (action.enablesPartnerLane() && fromReady + timing.partnerLeadSeconds() > timing.pickupToleranceSeconds()) {
            found.add(label
                    + ": starting "
                    + describe(action.dispatchAt())
                    + " leaves no time for a partner -- one needs "
                    + timing.partnerLeadSeconds() / 60
                    + " minutes to arrive and the pickup window closes "
                    + timing.pickupToleranceSeconds() / 60
                    + " minutes after the food is ready");
        } else if (fromReady >= (long) timing.pickupToleranceSeconds() + timing.latestAssignmentSlackSeconds()) {
            found.add(label + ": starting " + describe(action.dispatchAt())
                    + " is after the last moment anyone can be assigned");
        }
    }

    private static String describe(Start start) {
        String basis =
                switch (start.basis()) {
                    case LEAD -> "at the in-house lead";
                    case CONFIRMATION -> "at confirmation";
                    case READY -> "at the ready time";
                };
        int minutes = start.offsetSeconds() / 60;
        return minutes == 0 ? basis : basis + (minutes > 0 ? " +" : " ") + minutes + " min";
    }

    // --------------------------------------------------------------- shadows

    /** A rule no order can reach because an enabled rule above it takes everything it would. */
    private static void shadowViolations(DispatchRulesDocument document, List<String> found) {
        List<Rule> rules = document.rules();
        for (int later = 1; later < rules.size(); later++) {
            Rule rule = rules.get(later);
            if (!rule.enabled()) {
                continue;
            }
            for (int earlier = 0; earlier < later; earlier++) {
                Rule above = rules.get(earlier);
                if (above.enabled() && covers(above.when(), rule.when())) {
                    found.add("Rule \"" + rule.id() + "\" can never match: \"" + above.id()
                            + "\" above it already takes every order it would");
                    break;
                }
            }
        }
    }

    /** Whether every order {@code inner} matches is also matched by {@code outer}. Conservative: it never claims a cover that is not one. */
    static boolean covers(Conditions outer, Conditions inner) {
        return subset(outer.sources(), inner.sources())
                && subset(outer.channelIds(), inner.channelIds())
                && subset(outer.zoneIds(), inner.zoneIds())
                && subset(outer.locationIds(), inner.locationIds())
                && (outer.prepMinutes() == null
                        || (inner.prepMinutes() != null && outer.prepMinutes().covers(inner.prepMinutes())))
                && (outer.distanceMeters() == null
                        || (inner.distanceMeters() != null
                                && outer.distanceMeters().covers(inner.distanceMeters())))
                && (outer.localTime() == null
                        || (inner.localTime() != null && outer.localTime().covers(inner.localTime())))
                && (outer.prepaid() == null || outer.prepaid().equals(inner.prepaid()));
    }

    /** An empty outer list is "anything"; otherwise the inner must be a non-empty subset. */
    private static <T> boolean subset(List<T> outer, List<T> inner) {
        if (outer.isEmpty()) {
            return true;
        }
        return !inner.isEmpty() && outer.containsAll(inner);
    }

    private static boolean distinct(List<?> values) {
        return new HashSet<>(values).size() == values.size();
    }

    private static String abbreviated(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }
}
