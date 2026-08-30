package uz.horecaos.platform.marketing.domain;

import java.util.Map;
import java.util.Set;

/**
 * Where a campaign is, and where it may go next (ADR 0044).
 *
 * <p>The transitions live here rather than in the service for the reason ADR 0019
 * gives for the order state machine: a transition table in one place can be
 * asserted against the ADR, and a service that decides its own transitions
 * acquires an extra one every time somebody adds a button.
 *
 * <p>Two properties matter more than the rest. Nothing reaches {@link #SENDING}
 * except through {@link #APPROVED}, so a campaign cannot send without a second
 * signature. And {@link #HALTED_BUDGET} and {@link #HALTED_OPERATOR} are terminal
 * rather than resumable: a campaign that stopped at its ceiling and can be
 * restarted is a ceiling that only delays the overspend.
 */
public enum CampaignStatus {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    SCHEDULED,
    SENDING,
    PAUSED,
    SENT,
    PARTIALLY_SENT,
    HALTED_BUDGET,
    HALTED_OPERATOR,
    CANCELLED;

    private static final Map<CampaignStatus, Set<CampaignStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(IN_REVIEW, CANCELLED),
            IN_REVIEW, Set.of(APPROVED, DRAFT, CANCELLED),
            APPROVED, Set.of(SCHEDULED, SENDING, CANCELLED),
            SCHEDULED, Set.of(SENDING, CANCELLED),
            SENDING, Set.of(PAUSED, SENT, PARTIALLY_SENT, HALTED_BUDGET, HALTED_OPERATOR),
            PAUSED, Set.of(SENDING, HALTED_OPERATOR),
            SENT, Set.of(),
            PARTIALLY_SENT, Set.of(),
            HALTED_BUDGET, Set.of(),
            HALTED_OPERATOR, Set.of());

    public boolean canTransitionTo(CampaignStatus next) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    /** Whether the campaign is finished, whatever the outcome. */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }

    /** Whether a batch may be claimed against this campaign right now. */
    public boolean isExpanding() {
        return this == SENDING;
    }
}
