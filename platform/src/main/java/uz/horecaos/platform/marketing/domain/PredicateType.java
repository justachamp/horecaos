package uz.horecaos.platform.marketing.domain;

import java.util.Set;

/**
 * The closed catalogue an audience may ask questions from (ADR 0044).
 *
 * <p>Each constant carries the projection column it reads. That column name is the
 * only identifier that ever reaches a SQL string, it comes from this enum and
 * never from a request, and the values are always bound parameters. A query
 * builder over the customer schema was the alternative and was rejected: it hands
 * a marketing user arbitrary read of the tenant's customer base, defeats ADR 0025
 * scoping, and makes every segment an unversioned artefact that cannot be
 * replayed.
 *
 * <p>What is <em>absent</em> is as much of the decision as what is present. A
 * predicate may not reference free text of any kind, including review bodies and
 * delivery notes; a raw date of birth, as opposed to the derived
 * {@code birth_month_day}; any contact value; payment instrument data; anything
 * from ADR 0043's behavioural telemetry, whose lawful basis and retention are
 * still open under ADR 0029; or the content of the legacy {@code search_histories}
 * table. A predicate over what somebody searched for is a behavioural profile, and
 * this catalogue is deliberately not one.
 *
 * <p>Three predicates ADR 0044 names are not here, and each is absent because its
 * source is: home location has no column on the projection yet, an active benefit
 * grant needs {@code pricing.benefit_grants}, which this database does not have,
 * and brand is the audience's own scope rather than something to filter within.
 */
public enum PredicateType {

    /**
     * Recency, in whole days. The R of RFM, and the one an inactivity campaign is
     * built on.
     */
    RECENCY_DAYS("days_since_last_order", ValueKind.NUMERIC),

    ORDER_COUNT("order_count", ValueKind.NUMERIC),
    COMPLETED_ORDER_COUNT("completed_order_count", ValueKind.NUMERIC),

    /** Integer minor units. For UZS a minor unit is a whole som, never a tiyin. */
    NET_SPEND_MINOR("net_spend_minor", ValueKind.NUMERIC),
    AVERAGE_CHECK_MINOR("average_check_minor", ValueKind.NUMERIC),

    ACQUISITION_CHANNEL("acquisition_channel", ValueKind.TEXT_SET),
    REGISTERED_BETWEEN("registered_at", ValueKind.DATE_RANGE),

    /**
     * Days either side of today in the brand timezone, matched on the derived
     * {@code MM-DD} selector rather than on a date of birth.
     */
    BIRTHDAY_WITHIN_DAYS("birth_month_day", ValueKind.NUMERIC),

    PREFERRED_LOCALE("preferred_locale", ValueKind.TEXT_SET),

    /**
     * Inclusion or exclusion of another audience.
     *
     * <p>Resolved against that audience's latest completed snapshot rather than by
     * re-evaluating its predicates. That makes a cycle unrepresentable instead of
     * merely unlikely, and it means the referenced membership is a stated set an
     * approver can look at rather than a recursion whose cost nobody can predict.
     */
    AUDIENCE_MEMBERSHIP(null, ValueKind.AUDIENCE);

    /** The shape of the value a predicate of this type carries. */
    public enum ValueKind {
        NUMERIC,
        DATE_RANGE,
        TEXT_SET,
        AUDIENCE
    }

    private static final Set<PredicateOperator> RANGE_OPERATORS =
            Set.of(PredicateOperator.AT_LEAST, PredicateOperator.AT_MOST, PredicateOperator.BETWEEN);
    private static final Set<PredicateOperator> SET_OPERATORS =
            Set.of(PredicateOperator.IN, PredicateOperator.NOT_IN);

    private final String projectionColumn;
    private final ValueKind valueKind;

    PredicateType(String projectionColumn, ValueKind valueKind) {
        this.projectionColumn = projectionColumn;
        this.valueKind = valueKind;
    }

    /**
     * The {@code marketing.customer_metrics} column this predicate reads, or null
     * for {@link #AUDIENCE_MEMBERSHIP}, which reads a snapshot instead.
     */
    public String projectionColumn() {
        return projectionColumn;
    }

    public ValueKind valueKind() {
        return valueKind;
    }

    /** The operators that mean anything for this type. */
    public Set<PredicateOperator> allowedOperators() {
        return switch (valueKind) {
            case NUMERIC -> RANGE_OPERATORS;
            case DATE_RANGE -> Set.of(PredicateOperator.BETWEEN);
            case TEXT_SET, AUDIENCE -> SET_OPERATORS;
        };
    }
}
