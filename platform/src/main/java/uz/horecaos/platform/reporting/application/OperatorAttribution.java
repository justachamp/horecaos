package uz.horecaos.platform.reporting.application;

import org.jspecify.annotations.Nullable;

/**
 * Resolves who "operated" an order for the 7.5 staff leaderboard (ADR 0043's
 * {@code operator_principal_id}), so a human operator and a machine channel
 * can be told apart without a name.
 *
 * <p><strong>Precedence.</strong> Whoever approved the order ({@code
 * accepted_by_actor_*}) is credited first: {@code OrderStateService}'s
 * approval-decision path (ADR 0039) writes that pair only when a {@code USER}
 * moved an order {@code AWAITING_APPROVAL -> CONFIRMED}, which is real work a
 * call-centre operator did on an order they did not necessarily create — an
 * aggregator or bot order somebody had to confirm by hand. Failing that,
 * whoever created it ({@code created_by_actor_*}) is credited — {@code
 * OperatorOrderingService}'s own phone-order path names the operator here as
 * {@code USER} instead of the account as {@code CUSTOMER}. An order with no
 * {@code USER} actor at either end never had a human operator: {@code
 * StorefrontOrderingController} and {@code CustomerBotOrderingAdapter} both
 * attribute their checkouts to the account as {@code CUSTOMER}, and that
 * account is not staff.
 *
 * <p><strong>Pseudo-operators.</strong> An order with no human actor is
 * credited to a pseudo-operator named after its own channel —
 * {@code "channel:BOT"}, {@code "channel:WEBSITE"} — so the bot and the
 * website appear on the same leaderboard as a comparison point instead of
 * disappearing from it (7.5's own stated purpose). There is no {@code
 * operator_principal_kind} column: the {@code "channel:"} prefix is the only
 * "kind" this build stores, and it already names itself — {@link
 * #isPseudoOperator} and {@link #channelOf} let a reader recover it without a
 * second column, and every real staff subject fails {@link #isPseudoOperator}
 * by construction, because {@code channel_code} is never {@code null} while a
 * Keycloak subject is never prefixed {@code "channel:"}.
 *
 * <p>Names are out of scope. Until the staff-identity ADR lands, a {@code
 * USER} row's {@code operatorPrincipalId} is a bare Keycloak subject, and a
 * surface renders it labelled — "Operator, staff subject &lt;id&gt;" — rather
 * than as an unexplained UUID (T12's brief). {@code reporting} carries no
 * {@code PERSONAL} field at all (ADR 0029), so it never will resolve one
 * itself.
 */
final class OperatorAttribution {

    /**
     * The only "kind" this build stores, in the id itself. Never a real
     * Keycloak subject: {@link uz.horecaos.platform.configuration.Ids} mints
     * v7 UUIDs, and Keycloak's own subjects are UUIDs too, neither of which
     * ever starts with a colon-terminated word.
     */
    static final String PSEUDO_OPERATOR_PREFIX = "channel:";

    private static final String USER_ACTOR_TYPE = "USER";

    private OperatorAttribution() {}

    /**
     * @param channelCode the order's own channel — never null on a real order,
     *                    so this method always returns a value; a caller never
     *                    sees "no operator" as a third state, because a
     *                    pseudo-operator is what "no human operator" means here
     */
    static String resolve(
            @Nullable String createdByActorType,
            @Nullable String createdByActorId,
            @Nullable String acceptedByActorType,
            @Nullable String acceptedByActorId,
            String channelCode) {
        if (USER_ACTOR_TYPE.equals(acceptedByActorType) && acceptedByActorId != null && !acceptedByActorId.isBlank()) {
            return acceptedByActorId;
        }
        if (USER_ACTOR_TYPE.equals(createdByActorType) && createdByActorId != null && !createdByActorId.isBlank()) {
            return createdByActorId;
        }
        return PSEUDO_OPERATOR_PREFIX + channelCode;
    }

    /** Whether this stored id names a channel rather than a staff subject. */
    static boolean isPseudoOperator(String operatorPrincipalId) {
        return operatorPrincipalId.startsWith(PSEUDO_OPERATOR_PREFIX);
    }

    /**
     * The channel a pseudo-operator id names.
     *
     * @throws IllegalArgumentException if {@code operatorPrincipalId} is not a
     *                                  pseudo-operator — call {@link
     *                                  #isPseudoOperator} first
     */
    static String channelOf(String operatorPrincipalId) {
        if (!isPseudoOperator(operatorPrincipalId)) {
            throw new IllegalArgumentException("\"%s\" is not a pseudo-operator id".formatted(operatorPrincipalId));
        }
        return operatorPrincipalId.substring(PSEUDO_OPERATOR_PREFIX.length());
    }
}
