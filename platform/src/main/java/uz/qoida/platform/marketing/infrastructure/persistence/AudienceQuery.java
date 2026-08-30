package uz.qoida.platform.marketing.infrastructure.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import uz.qoida.platform.marketing.domain.AudiencePredicate;
import uz.qoida.platform.marketing.domain.PredicateOperator;
import uz.qoida.platform.marketing.domain.PredicateType;

/**
 * Turning a typed predicate set into one parameterised SQL statement (ADR 0044).
 *
 * <p>Every identifier in the output comes from {@link PredicateType}, which is an
 * enum, and every value is a bound named parameter. Nothing a client sends is ever
 * concatenated into the statement — not a column, not an operator, not a value.
 * That is the whole reason the catalogue is closed: a query builder over the
 * customer schema hands a marketing user arbitrary read of the tenant's base, and
 * this class is where that would otherwise leak in.
 *
 * <p>The tenant and brand predicates lead every statement and are not optional.
 * They are not derived from the predicate list either, so no combination of
 * predicates can produce a query without them.
 */
final class AudienceQuery {

    /** {@code MM-DD}, matching the derived selector's stored shape. */
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private AudienceQuery() {
    }

    /** One compiled statement and the parameters it binds. */
    record Compiled(String sql, Map<String, Object> parameters) { }

    /**
     * The candidate query: every projection row this audience's predicates match.
     *
     * <p>Joined to {@code customer.customer_accounts} for the lifecycle state,
     * which ADR 0044 allows a predicate to reference and which the first
     * subtraction reads. The account's contact details are not read here and
     * cannot be: this statement names no column of {@code customer.contact_points}
     * at all.
     *
     * @param today the brand-local date, passed in rather than taken from a clock
     *              so a birthday window is evaluated in the brand's timezone and
     *              not the server's
     */
    static Compiled candidates(UUID tenantId, UUID brandId, List<AudiencePredicate> predicates,
            LocalDate today) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);

        List<String> clauses = new ArrayList<>();
        int index = 0;
        for (AudiencePredicate predicate : predicates) {
            clauses.add(clauseFor(predicate, "p" + index, parameters, tenantId, today));
            index++;
        }

        StringBuilder sql = new StringBuilder("""
                SELECT m.customer_account_id,
                       m.preferred_locale,
                       m.completed_order_count,
                       m.net_spend_minor,
                       m.days_since_last_order,
                       a.status AS account_status,
                       a.merged_into_account_id,
                       a.anonymized_at
                  FROM marketing.customer_metrics m
                  JOIN customer.customer_accounts a
                    ON a.id = m.customer_account_id
                   AND a.tenant_id = m.tenant_id
                 WHERE m.tenant_id = :tenantId
                   AND m.brand_id = :brandId
                """);

        // Joined by AND. ADR 0044 does not offer OR, and adding it would turn a
        // flat list into a tree that a marketer cannot read back and an approver
        // cannot check.
        for (String clause : clauses) {
            sql.append("   AND ").append(clause).append('\n');
        }
        sql.append(" ORDER BY m.customer_account_id\n");

        return new Compiled(sql.toString(), parameters);
    }

    private static String clauseFor(AudiencePredicate predicate, String name,
            Map<String, Object> parameters, UUID tenantId, LocalDate today) {

        if (predicate.type() == PredicateType.BIRTHDAY_WITHIN_DAYS) {
            return birthdayClause(predicate, name, parameters, today);
        }
        if (predicate.type() == PredicateType.AUDIENCE_MEMBERSHIP) {
            return membershipClause(predicate, name, parameters, tenantId);
        }

        String column = "m." + predicate.type().projectionColumn();

        return switch (predicate.type().valueKind()) {
            case NUMERIC -> {
                parameters.put(name + "Low", predicate.numericLow());
                yield switch (predicate.operator()) {
                    case AT_LEAST -> column + " >= :" + name + "Low";
                    case AT_MOST -> column + " <= :" + name + "Low";
                    case BETWEEN -> {
                        parameters.put(name + "High", predicate.numericHigh());
                        yield column + " BETWEEN :" + name + "Low AND :" + name + "High";
                    }
                    // Unreachable: AudiencePredicate refuses a set operator on a
                    // numeric type at construction. Stated rather than defaulted,
                    // so adding an operator to the enum breaks the build here.
                    case IN, NOT_IN -> throw new IllegalStateException(
                            "A numeric predicate cannot use " + predicate.operator());
                };
            }
            case DATE_RANGE -> {
                parameters.put(name + "From", predicate.dateLow());
                parameters.put(name + "To", predicate.dateHigh());
                // Cast to date rather than compared as a timestamp range. It gives
                // up the index on registered_at, and it is what a marketer means:
                // "registered in March" includes everyone who registered at 23:50
                // on the thirty-first.
                yield column + "::date BETWEEN :" + name + "From AND :" + name + "To";
            }
            case TEXT_SET -> {
                parameters.put(name + "Set", predicate.textValues());
                // The NULL arm is written out because SQL's NOT IN over a nullable
                // column silently drops every row whose value is NULL, which is the
                // three-valued-logic hole that would quietly exclude every customer
                // who never had an acquisition channel recorded.
                yield predicate.operator() == PredicateOperator.IN
                        ? column + " IN (:" + name + "Set)"
                        : "(" + column + " IS NULL OR " + column + " NOT IN (:" + name + "Set))";
            }
            case AUDIENCE -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * A birthday window as a set of {@code MM-DD} strings.
     *
     * <p>Expanded in Java rather than computed in SQL. The alternative is date
     * arithmetic over a column that deliberately has no year, which needs a
     * synthetic year and then behaves differently across a leap day. Expanding the
     * window into at most a few hundred literals also lets the partial index on
     * {@code birth_month_day} serve the query.
     */
    private static String birthdayClause(AudiencePredicate predicate, String name,
            Map<String, Object> parameters, LocalDate today) {

        int window = Math.toIntExact(predicate.numericLow());
        if (window < 0 || window > 182) {
            throw new IllegalArgumentException(
                    "A birthday window of %d days is not a birthday campaign".formatted(window));
        }
        // A LinkedHashSet because 29 February collapses onto itself in a non-leap
        // year and a duplicated literal would be noise in the statement.
        Set<String> days = new LinkedHashSet<>();
        for (int offset = -window; offset <= window; offset++) {
            days.add(today.plusDays(offset).format(MONTH_DAY));
        }
        parameters.put(name + "Days", List.copyOf(days));
        return "m.birth_month_day IN (:" + name + "Days)";
    }

    /**
     * Membership of another audience, read from its latest completed snapshot.
     *
     * <p>Not a recursive evaluation of the other audience's predicates. A cycle
     * would then be possible, and preventing one needs a visited set that every
     * future caller has to remember to pass. Reading a snapshot makes the cycle
     * unrepresentable, and it also means the referenced set is one an approver can
     * look at rather than a recursion whose cost nobody can predict.
     */
    private static String membershipClause(AudiencePredicate predicate, String name,
            Map<String, Object> parameters, UUID tenantId) {

        parameters.put(name + "Audience", predicate.audienceId());
        parameters.put(name + "Tenant", tenantId);

        String subquery = """
                SELECT sm.customer_account_id
                  FROM marketing.audience_snapshot_members sm
                 WHERE sm.tenant_id = :%1$sTenant
                   AND sm.inclusion_status = 'INCLUDED'
                   AND sm.snapshot_id = (
                       SELECT s.id
                         FROM marketing.audience_snapshots s
                        WHERE s.tenant_id = :%1$sTenant
                          AND s.audience_id = :%1$sAudience
                          AND s.status = 'READY'
                          AND s.members_purged_at IS NULL
                        ORDER BY s.completed_at DESC
                        LIMIT 1)
                """.formatted(name);

        return predicate.operator() == PredicateOperator.IN
                ? "m.customer_account_id IN (" + subquery + ")"
                : "m.customer_account_id NOT IN (" + subquery + ")";
    }
}
