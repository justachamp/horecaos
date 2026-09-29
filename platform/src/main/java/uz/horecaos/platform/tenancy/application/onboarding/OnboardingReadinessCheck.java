package uz.horecaos.platform.tenancy.application.onboarding;

import java.util.UUID;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;

/**
 * A read-only readiness check that {@link OnboardingService#validate} runs
 * beside the {@code VALIDATING}-phase steps without being one of them (gap map
 * row {@code 10.0}).
 *
 * <p>Why not an {@code OnboardingStep}: ADR 0008's step catalogue is a released
 * contract. A new step needs a migration, a sequence number, and a decision
 * about whether it retroactively blocks a tenant that has already activated —
 * none of which a settings-home readiness list needs answered to be useful.
 * These checks ride in the same informational, read-only dry run every step
 * already returns from, persist nothing, and never gate {@code READY} or
 * activation.
 *
 * <p>Same result shape as a step, deliberately: {@link StepResult#completed}
 * when nothing is wrong, otherwise {@link StepResult#failedWithFindings} naming
 * every offending item rather than the first, so the readiness panel can count
 * and deep-link each one. A check that reads another module's tables does so by
 * name, for the reason {@link OnboardingStepHandlers}' class doc gives.
 */
public interface OnboardingReadinessCheck {

    /**
     * Stands in for a step key in the dry-run report, so the panel and the
     * control plane can say which check a finding came from.
     */
    String checkKey();

    /**
     * Whether a finding from this check is worth an operator's attention but
     * does not stop the tenant trading (settings.md §10.0: severity
     * {@code Advisory}). An advisory finding still fails its own row; it is
     * left out of {@link OnboardingService.ValidationOutcome#allPassed()}.
     */
    boolean advisory();

    /**
     * Looks at the tenant's current configuration. Must only read.
     *
     * @return {@code COMPLETED} when nothing is wrong, otherwise a
     *         {@code FAILED} result carrying its findings
     */
    StepResult check(UUID tenantId);
}
