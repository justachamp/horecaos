package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** What one tenant is on right now (ADR 0021). */
public record Subscription(
        UUID id,
        UUID tenantId,
        UUID planVersionId,
        SubscriptionStatus status,
        Instant startAt,
        Instant trialEndAt,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant cancelAt,
        Instant suspendedAt,
        String suspensionReason,
        Instant endedAt,
        String externalBillingReference,
        long version) {

    public Subscription {
        Objects.requireNonNull(id, "A subscription id is required");
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(planVersionId, "A plan version is required");
        Objects.requireNonNull(status, "A status is required");
    }

    /**
     * Whether the trial has run out at {@code instant}.
     *
     * <p>Asked rather than scheduled. A trial that expires because a job ran is a
     * trial whose end date depends on whether the job ran, and the tenant reads
     * the difference as arbitrary.
     */
    public boolean trialLapsed(Instant instant) {
        return status == SubscriptionStatus.TRIALING
                && trialEndAt != null && !instant.isBefore(trialEndAt);
    }
}
