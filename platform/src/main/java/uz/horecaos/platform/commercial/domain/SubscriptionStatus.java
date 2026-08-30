package uz.horecaos.platform.commercial.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The subscription lifecycle (ADR 0021).
 *
 * <p>Separate from {@code tenant.tenants.status} throughout. A tenant that has
 * not paid is a commercial problem; a tenant that has been archived is an
 * operational fact. Collapsing the two is how an unpaid invoice ends up deleting
 * a restaurant's menu.
 */
public enum SubscriptionStatus {

    /** Assembled but not yet in force. Entitlements resolve to catalogue defaults. */
    DRAFT,

    /** In force under trial terms until {@code trial_end_at}. */
    TRIALING,

    ACTIVE,

    /** Payment is late. Entitlements are unchanged: lateness is a conversation, not a switch. */
    PAST_DUE,

    /**
     * Degraded by decision. Capacity-increasing entitlements go to zero; nothing
     * is deleted and nothing already recorded becomes unreadable.
     */
    SUSPENDED,

    /** Ending at a known future instant, and withdrawable until then. */
    CANCELLATION_SCHEDULED,

    /** A trial that ran out without converting. */
    EXPIRED,

    TERMINATED;

    public boolean isTerminal() {
        return this == EXPIRED || this == TERMINATED;
    }

    /** Whether this status makes the plan's entitlements apply at all. */
    public boolean grantsPlanEntitlements() {
        return this == TRIALING || this == ACTIVE || this == PAST_DUE || this == CANCELLATION_SCHEDULED;
    }

    /**
     * The statuses reachable from this one.
     *
     * <p>Every terminal state is reachable from every live state, and no live
     * state is reachable from a terminal one: a terminated subscription is
     * restarted by starting a new subscription, so that the terms it restarts
     * under are recorded rather than assumed.
     */
    public Set<SubscriptionStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> EnumSet.of(TRIALING, ACTIVE, TERMINATED);
            case TRIALING -> EnumSet.of(ACTIVE, EXPIRED, SUSPENDED, CANCELLATION_SCHEDULED, TERMINATED);
            case ACTIVE -> EnumSet.of(PAST_DUE, SUSPENDED, CANCELLATION_SCHEDULED, TERMINATED);
            case PAST_DUE -> EnumSet.of(ACTIVE, SUSPENDED, TERMINATED);
            case SUSPENDED -> EnumSet.of(ACTIVE, TERMINATED);
            case CANCELLATION_SCHEDULED -> EnumSet.of(ACTIVE, TERMINATED);
            case EXPIRED, TERMINATED -> EnumSet.noneOf(SubscriptionStatus.class);
        };
    }

    public boolean canTransitionTo(SubscriptionStatus next) {
        return allowedNext().contains(next);
    }
}
