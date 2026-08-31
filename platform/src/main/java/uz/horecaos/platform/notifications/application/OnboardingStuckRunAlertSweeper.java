package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStuckRunDirectory;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStuckRunDirectory.StuckRun;

/**
 * The control-plane half of onboarding's existing stuck-run alert (ADR
 * 0058): {@code OnboardingScheduler}'s {@code
 * stalled.age.seconds} gauge, alerted on today by {@code
 * infra/observability/horecaos-probe.sh} alone, additionally reaches the
 * ADR 0058 control-plane audience through this sweeper.
 *
 * <p><strong>Additive, not a replacement.</strong> The gauge and the probe
 * script are untouched; this is a second, independent detector over the
 * same condition ({@link OnboardingStuckRunDirectory}, the listing sibling
 * of the gauge's own query), the same relationship {@code
 * ApprovalDeadlineWarningSweeper} has to {@code OrderProcessWorker}'s timer:
 * a small, separate, read-only sweeper rather than teaching the existing
 * mechanism a second concern.
 *
 * <p>Not {@link OperationsAlertFanoutService}: a stuck onboarding run's
 * audience is platform staff, never the tenant's own operations chat — see
 * {@link ControlPlaneAlertPort}'s own Javadoc for why that call is
 * currently a log line and a counter rather than a Telegram send.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.notifications.control-plane.onboarding-stuck-run.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OnboardingStuckRunAlertSweeper {

    /** The semantic event class a control-plane operator's tooling filters on. */
    public static final String ONBOARDING_RUN_STUCK = "ONBOARDING_RUN_STUCK";

    static final String SUBJECT_TYPE = "OnboardingRun";

    private static final Logger log = LoggerFactory.getLogger(OnboardingStuckRunAlertSweeper.class);

    private final OnboardingStuckRunDirectory stuckRuns;
    private final ControlPlaneAlertPort controlPlaneAlerts;
    private final Clock clock;
    private final Duration minimumAge;
    private final int batchSize;

    public OnboardingStuckRunAlertSweeper(
            OnboardingStuckRunDirectory stuckRuns,
            ControlPlaneAlertPort controlPlaneAlerts,
            Clock clock,
            // Matches ADR 0023's own onboarding-stalled probe threshold
            // (ONBOARDING_STALL_SECONDS), not reinvented: the gauge and
            // this sweeper have to agree on when "stuck" starts, or an
            // operator sees two different answers for the same run.
            @Value("${horecaos.notifications.control-plane.onboarding-stuck-run.minimum-age:PT30M}")
                    Duration minimumAge,
            @Value("${horecaos.notifications.control-plane.onboarding-stuck-run.batch-size:50}") int batchSize) {
        this.stuckRuns = stuckRuns;
        this.controlPlaneAlerts = controlPlaneAlerts;
        this.clock = clock;
        this.minimumAge = minimumAge;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.control-plane.onboarding-stuck-run.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.notifications.control-plane.onboarding-stuck-run.interval:PT1M}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The onboarding stuck-run control-plane sweep could not run", failure);
        }
    }

    /** @return how many stuck runs were alerted on this pass, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        List<StuckRun> stuck = stuckRuns.stuckRuns(now, minimumAge, batchSize);

        for (StuckRun run : stuck) {
            try {
                // Idempotent the same way ApprovalDeadlineWarningSweeper's
                // own re-scan is: raising this again for a run that is
                // still stuck on the next tick is safe to repeat because
                // ControlPlaneAlertPort's v1 sink (a log line, a counter)
                // has no duplicate-send cost the way a Telegram message
                // would; a durable implementation gains the same
                // subjectId-keyed dedup every other trigger in this build
                // has, at that seam and no other.
                controlPlaneAlerts.raise(new ControlPlaneAlert(
                        ONBOARDING_RUN_STUCK, SUBJECT_TYPE, run.runId().toString(), variablesFor(run, now), now));
            } catch (RuntimeException failure) {
                // One run's failure must not stop the sweep from raising
                // the alert for every other stuck run in this batch.
                log.error("Could not raise the control-plane alert for stuck onboarding run {}", run.runId(), failure);
            }
        }
        return stuck.size();
    }

    /**
     * The entire variable set this alert ever renders with — a run id, a
     * tenant id and how long it has been stuck, nothing about who at the
     * tenant is affected. Package-visible so a classification test can
     * assert that directly.
     */
    static Map<String, String> variablesFor(StuckRun run, Instant now) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("runId", run.runId().toString());
        variables.put("tenantId", run.tenantId().toString());
        variables.put(
                "stuckSeconds",
                String.valueOf(Duration.between(run.stuckSince(), now).getSeconds()));
        return variables;
    }
}
