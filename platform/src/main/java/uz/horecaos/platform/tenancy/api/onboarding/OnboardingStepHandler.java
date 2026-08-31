package uz.horecaos.platform.tenancy.api.onboarding;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Performs one onboarding step (ADR 0008).
 *
 * <p>Every handler must be safe to run again. A step that timed out may already
 * have succeeded externally, so a re-run reconciles against the stored
 * {@code externalReference} rather than creating a second object.
 */
public interface OnboardingStepHandler {

    OnboardingStep step();

    default int stepVersion() {
        return 1;
    }

    StepResult execute(StepContext context);

    /**
     * The inputs to one attempt at one step.
     *
     * @param externalReference the immutable identifier the external system
     *                          already assigned, when a previous attempt got
     *                          that far
     */
    record StepContext(
            UUID runId,
            UUID tenantId,
            Map<String, Object> input,
            @Nullable String externalReference,
            int attemptCount) {}

    /**
     * The outcome of one attempt at one step.
     *
     * @param externalReference persisted so the next attempt reconciles instead
     *                          of recreating
     */
    record StepResult(
            Outcome outcome,
            Map<String, Object> result,
            @Nullable String externalReference,
            @Nullable String errorCode,
            @Nullable String detail) {

        public enum Outcome {
            COMPLETED,
            /** Transient; the step becomes due again. */
            RETRY,
            /** Permanent; a human must look. */
            FAILED,
            /** The capability does not exist yet. Never reported as success. */
            BLOCKED
        }

        public static StepResult completed(Map<String, Object> result, @Nullable String externalReference) {
            return new StepResult(Outcome.COMPLETED, result, externalReference, null, null);
        }

        public static StepResult retry(String errorCode, @Nullable String detail) {
            return new StepResult(Outcome.RETRY, Map.of(), null, errorCode, detail);
        }

        public static StepResult failed(String errorCode, @Nullable String detail) {
            return new StepResult(Outcome.FAILED, Map.of(), null, errorCode, detail);
        }

        public static StepResult blocked(@Nullable String detail) {
            return new StepResult(Outcome.BLOCKED, Map.of(), null, "CAPABILITY_ABSENT", detail);
        }
    }
}
