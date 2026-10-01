package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;

/**
 * The {@code fulfillment.dispatch_rules} policy document (ADR 0142, ADR 0030).
 *
 * <p>An ordered list of rules with a mandatory default. A rule is data in a
 * closed vocabulary, never code: its {@code when} is a conjunction over a fixed
 * set of facts about an order, its {@code then} is a sourcing mode, a partner set
 * and how one is chosen, a dispatch start, and a grouping policy. There is no
 * expression language, no script, and no provider name -- a rule names an ADR 0026
 * <em>installation</em> by id, and the evaluator can only order or remove the
 * candidates {@code ShipmentBookingPort} already returned, so a rule cannot book a
 * partner the branch has no active binding for.
 *
 * <p>Published whole through {@code PolicyAuthor} and resolved replace-not-merge
 * (ADR 0030: "no partial merging of policy documents by default"), so the version
 * a plan recorded is the entire document it ran under.
 *
 * <p>The document is stored as JSON, so every optional piece is absent or empty
 * rather than a sentinel, and a reader that meets a document written by an older
 * writer sees defaults rather than a failure.
 *
 * @param schema   the document shape, {@value #SCHEMA} today
 * @param rules    evaluated in order; the first enabled rule whose conditions all
 *                 hold wins
 * @param fallback what applies when no rule does. Serialised as {@code "default"} (a Java keyword
 *                 cannot name a component); the rename lives in {@code DispatchRulesDocumentMixin}, in the
 *                 persistence adapter, because the domain imports no serialisation types
 */
public record DispatchRulesDocument(int schema, List<Rule> rules, Action fallback) {

    public static final int SCHEMA = 1;

    /**
     * The cap on rules in one document. ADR 0142 names "a scope regularly holds more
     * than about a hundred rules" as the revisit trigger for a row-per-rule table, so
     * this is the size the whole-document design is built for.
     */
    public static final int MAX_RULES = 100;

    public DispatchRulesDocument {
        rules = rules == null ? List.of() : List.copyOf(rules);
        Objects.requireNonNull(fallback, "A dispatch rule document needs a default action");
    }

    /**
     * What is in force when nothing was published: no rules, and a default that
     * reproduces today's behaviour exactly -- the fleet first, partners in binding
     * order and cheapest quote, sourcing started at the in-house lead.
     */
    public static DispatchRulesDocument builtIn() {
        return new DispatchRulesDocument(SCHEMA, List.of(), Action.builtInDefault());
    }

    // ------------------------------------------------------------------ rule

    /**
     * One rule.
     *
     * @param id      stable, operator-visible, unique within the document, and what a
     *                plan records as the rule that matched
     * @param enabled a disabled rule keeps its place in the order and never matches
     */
    public record Rule(String id, String name, boolean enabled, Conditions when, Action then) {

        public Rule {
            Objects.requireNonNull(id, "A rule id is required");
            name = name == null ? "" : name;
            when = when == null ? Conditions.any() : when;
            Objects.requireNonNull(then, "A rule needs an action");
        }
    }

    /**
     * What an order must look like for a rule to apply. An omitted condition matches
     * anything; every present condition must match.
     *
     * @param sources        {@code tenant.sales_channels.system_type} names (ADR 0036's
     *                       closed set)
     * @param channelIds     specific sales channels
     * @param zoneIds        delivery zones, from the order's fee-resolution evidence. A rule
     *                       that names zones does not match an order with no zone evidence
     * @param locationIds    branches, narrower than the document's own scope
     * @param prepMinutes    the kitchen's preparation estimate, whole minutes
     * @param distanceMeters branch to door
     * @param localTime      the day and time of confirmation in the branch's timezone
     * @param prepaid        whether HorecaOS already took the money
     */
    public record Conditions(
            List<String> sources,
            List<UUID> channelIds,
            List<UUID> zoneIds,
            List<UUID> locationIds,
            @Nullable IntRange prepMinutes,
            @Nullable IntRange distanceMeters,
            @Nullable TimeWindow localTime,
            @Nullable Boolean prepaid) {

        public Conditions {
            sources = sources == null ? List.of() : List.copyOf(sources);
            channelIds = channelIds == null ? List.of() : List.copyOf(channelIds);
            zoneIds = zoneIds == null ? List.of() : List.copyOf(zoneIds);
            locationIds = locationIds == null ? List.of() : List.copyOf(locationIds);
        }

        /** The empty conjunction: matches every order. */
        public static Conditions any() {
            return new Conditions(List.of(), List.of(), List.of(), List.of(), null, null, null, null);
        }
    }

    /** An inclusive range with either end open. At least one end is present in a valid rule. */
    public record IntRange(@Nullable Integer min, @Nullable Integer max) {

        public boolean contains(int value) {
            return (min == null || value >= min) && (max == null || value <= max);
        }

        /** Whether every value of {@code inner} lies in this range. An open end of {@code inner} needs an open end here. */
        public boolean covers(IntRange inner) {
            boolean lowOk = min == null || (inner.min != null && inner.min >= min);
            boolean highOk = max == null || (inner.max != null && inner.max <= max);
            return lowOk && highOk;
        }
    }

    /**
     * Days of the week and a time-of-day window, read against the branch's clock at
     * the moment of confirmation.
     *
     * <p>{@code from} is inclusive and {@code to} exclusive, both {@code HH:mm}; {@code to}
     * may be {@code 24:00} for the end of the day. A window whose {@code from} is later
     * than its {@code to} wraps midnight, and the part after midnight belongs to the day
     * the window <em>started</em> on -- "Friday 22:00 to 02:00" includes Saturday 01:00,
     * which is how a shift is spoken of. An empty {@code days} means every day.
     */
    public record TimeWindow(List<Weekday> days, String from, String to) {

        public TimeWindow {
            days = days == null ? List.of() : List.copyOf(days);
            Objects.requireNonNull(from, "A time window needs a start");
            Objects.requireNonNull(to, "A time window needs an end");
        }

        /** Minute of the day for a {@code HH:mm} value, or -1 when it is not one. {@code 24:00} is 1440. */
        public static int minuteOfDay(String hhmm) {
            if (hhmm == null || !hhmm.matches("^\\d{2}:\\d{2}$")) {
                return -1;
            }
            int hour = Integer.parseInt(hhmm.substring(0, 2));
            int minute = Integer.parseInt(hhmm.substring(3, 5));
            if (minute > 59 || hour > 24 || (hour == 24 && minute != 0)) {
                return -1;
            }
            return hour * 60 + minute;
        }

        /** Whether this window holds the given local day and minute of the day. */
        public boolean contains(DayOfWeek day, int minuteOfDay) {
            int start = minuteOfDay(from);
            int end = minuteOfDay(to);
            if (start < 0 || end < 0 || start == end) {
                return false;
            }
            boolean inside;
            DayOfWeek anchor = day;
            if (start < end) {
                inside = minuteOfDay >= start && minuteOfDay < end;
            } else {
                inside = minuteOfDay >= start || minuteOfDay < end;
                if (minuteOfDay < end) {
                    anchor = day.minus(1);
                }
            }
            return inside && (days.isEmpty() || days.contains(Weekday.of(anchor)));
        }

        /** Whether every instant of {@code inner} is also an instant of this window. Conservative: wrapping windows must be identical. */
        public boolean covers(TimeWindow inner) {
            int start = minuteOfDay(from);
            int end = minuteOfDay(to);
            int innerStart = minuteOfDay(inner.from);
            int innerEnd = minuteOfDay(inner.to);
            if (start < 0 || end < 0 || innerStart < 0 || innerEnd < 0) {
                return false;
            }
            boolean daysCovered = days.isEmpty() || (!inner.days.isEmpty() && days.containsAll(inner.days));
            if (!daysCovered) {
                return false;
            }
            boolean wraps = start > end;
            boolean innerWraps = innerStart > innerEnd;
            if (wraps || innerWraps) {
                return start == innerStart && end == innerEnd;
            }
            return start <= innerStart && innerEnd <= end;
        }
    }

    /** Monday to Sunday as the document spells them. */
    public enum Weekday {
        MON,
        TUE,
        WED,
        THU,
        FRI,
        SAT,
        SUN;

        public static Weekday of(DayOfWeek day) {
            return values()[day.getValue() - 1];
        }
    }

    // ---------------------------------------------------------------- action

    /**
     * What a matched rule does.
     *
     * @param mode             which lanes run and in what order (ADR 0142 Decision 9)
     * @param partners         which installations, in what order, and how one is chosen
     * @param dispatchAt       when sourcing starts, relative to the order's own timeline
     * @param grouping         null for none. Refused at publish until the pay treatment
     *                         for a run of several orders is decided (ADR 0142 open input,
     *                         finance)
     * @param holdBeforeConfirm reserved for a hold race, and refused at publish while
     *                         {@code SourcingPlanner} cannot emit a hold (ADR 0142 Decision 5)
     */
    public record Action(
            SourcingMode mode,
            PartnerSet partners,
            Start dispatchAt,
            @Nullable Grouping grouping,
            @Nullable Boolean holdBeforeConfirm) {

        public Action {
            Objects.requireNonNull(mode, "A dispatch action needs a sourcing mode");
            partners = partners == null ? PartnerSet.bindingOrder() : partners;
            dispatchAt = dispatchAt == null ? Start.lead() : dispatchAt;
        }

        /** Today's behaviour, exactly: fleet first, partners in binding order and the cheapest quote, the in-house lead. */
        public static Action builtInDefault() {
            return new Action(SourcingMode.FLEET_FIRST, PartnerSet.bindingOrder(), Start.lead(), null, null);
        }

        /** Whether this action ever asks a partner. */
        public boolean enablesPartnerLane() {
            return mode.usesPartners();
        }
    }

    /** How a partner is chosen when several remain. */
    public enum PartnerSelection {

        /** The given order, asking no quote. */
        LADDER,

        /** A non-binding quote from every eligible partner, and the winner is booked. ADR 0014's {@code QuoteScoring}, unchanged. */
        CHEAPEST
    }

    /**
     * The partners a rule allows.
     *
     * @param order     ADR 0026 installation ids. Empty means every bound delivery installation,
     *                  in the binding order ADR 0026 already returns. Non-empty restricts the set
     *                  to exactly these, tried in this order
     * @param exclude   installations never used by this rule
     * @param selection {@link PartnerSelection}
     */
    public record PartnerSet(List<UUID> order, List<UUID> exclude, PartnerSelection selection) {

        public PartnerSet {
            order = order == null ? List.of() : List.copyOf(order);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
            selection = selection == null ? PartnerSelection.CHEAPEST : selection;
        }

        /** Every bound installation in binding order, cheapest quote wins. */
        public static PartnerSet bindingOrder() {
            return new PartnerSet(List.of(), List.of(), PartnerSelection.CHEAPEST);
        }

        /**
         * Applies this set to the partners a branch actually has bound, and says what it
         * could not honour.
         *
         * <p>The one place a rule meets the live candidate list: the runtime ({@code
         * DeliverySourcingService}), the plan-creation record and the simulator all call
         * it, which is what makes "what the simulator shows is what happens" true. A named
         * installation with no active binding at the branch is a <em>skip</em>, recorded,
         * never an error and never a different partner.
         */
        public Ladder apply(List<PartnerOption> bound) {
            List<PartnerOption> usable =
                    bound.stream().filter(option -> !isExcluded(option)).toList();
            if (order.isEmpty()) {
                return new Ladder(usable, List.of());
            }
            java.util.ArrayList<PartnerOption> ordered = new java.util.ArrayList<>();
            java.util.ArrayList<Skip> skips = new java.util.ArrayList<>();
            for (UUID installationId : order) {
                if (exclude.contains(installationId)) {
                    skips.add(new Skip(installationId, Skip.EXCLUDED));
                    continue;
                }
                List<PartnerOption> matching = usable.stream()
                        .filter(option -> installationId.equals(option.installationId()))
                        .toList();
                if (matching.isEmpty()) {
                    skips.add(new Skip(installationId, Skip.NO_ACTIVE_BINDING));
                } else {
                    ordered.addAll(matching);
                }
            }
            return new Ladder(List.copyOf(ordered), List.copyOf(skips));
        }

        private boolean isExcluded(PartnerOption option) {
            return option.installationId() != null && exclude.contains(option.installationId());
        }
    }

    /** The candidates left once a {@link PartnerSet} has been applied, and what it skipped. */
    public record Ladder(List<PartnerOption> options, List<Skip> skips) {}

    /**
     * An installation a rule named that could not be used.
     *
     * @param reason {@link #NO_ACTIVE_BINDING} or {@link #EXCLUDED}
     */
    public record Skip(UUID installationId, String reason) {

        /** The rule names it and the branch has no active binding for it. */
        public static final String NO_ACTIVE_BINDING = "NO_ACTIVE_BINDING";

        /** The rule names it in both its order and its exclude list. */
        public static final String EXCLUDED = "EXCLUDED";
    }

    /** The instant sourcing starts is measured from this. */
    public enum StartBasis {

        /** Today's formula: the estimated ready time less the in-house lead and the safety buffer. */
        LEAD,

        /** At once, the moment the order is confirmed. */
        CONFIRMATION,

        /** The estimated ready time. */
        READY
    }

    /**
     * A named basis plus a bounded offset from it.
     *
     * @param offsetSeconds between {@value #MIN_OFFSET_SECONDS} and {@value #MAX_OFFSET_SECONDS}:
     *                      an hour earlier to half an hour later
     */
    public record Start(StartBasis basis, int offsetSeconds) {

        public static final int MIN_OFFSET_SECONDS = -3600;
        public static final int MAX_OFFSET_SECONDS = 1800;

        public Start {
            basis = basis == null ? StartBasis.LEAD : basis;
        }

        public static Start lead() {
            return new Start(StartBasis.LEAD, 0);
        }
    }

    /**
     * Courier order grouping, an assignment bias for the in-house fleet (ADR 0142 Decision 6).
     *
     * @param mergeRadiusMeters how close two drop-offs must be to share a run
     * @param maxOrdersPerRun   the ceiling on orders one run carries
     * @param maxWaitSeconds    the longest an order waits for such a courier, and never
     *                          past the promise
     */
    public record Grouping(int mergeRadiusMeters, int maxOrdersPerRun, int maxWaitSeconds) {

        public static final int MIN_RADIUS_METERS = 50;
        public static final int MAX_RADIUS_METERS = 5000;
        public static final int MIN_ORDERS_PER_RUN = 2;
        public static final int MAX_ORDERS_PER_RUN = 4;
        public static final int MAX_WAIT_SECONDS = 900;
    }
}
