package uz.horecaos.platform.tenancy.api.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The listing sibling of {@code OnboardingScheduler}'s own {@code
 * stalled.age.seconds} gauge (ADR 0008): "how long is the longest-stuck run
 * stuck" as one number for a metric is not "which runs, so a control-plane
 * chat can name them" — this is where the second question is answered, for
 * {@code notifications}'s ADR 0058 control-plane trigger.
 *
 * <p>Excludes exactly what the gauge excludes: a run parked on {@code
 * TENANT_ACTIVATE} is waiting for a person to approve it, not stuck, and
 * {@code OnboardingScheduler}'s own Javadoc explains at length why that
 * exclusion is by step name rather than by the far-future {@code
 * available_at} that step is parked with.
 */
public interface OnboardingStuckRunDirectory {

    /**
     * Runs with a due step that has not moved for at least {@code
     * minimumAge}, oldest first.
     */
    List<StuckRun> stuckRuns(Instant now, Duration minimumAge, int limit);

    record StuckRun(UUID runId, UUID tenantId, Instant stuckSince) {}
}
