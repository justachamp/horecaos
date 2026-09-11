package uz.horecaos.platform.tenancy.api.onboarding;

import java.util.List;
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

        /**
         * A FAILED result naming every offending item rather than only the
         * first (wave P31, gap map row {@code 10.0}) — a step whose check
         * loops over the tenant's locations, such as {@code
         * PaymentConfigurationValidate}, that used to {@code return} out of
         * the loop on the first bad one.
         *
         * <p>Carried in {@link #result} under {@link #FINDINGS_KEY}, which is
         * safe precisely because a FAILED result's {@code result} map is
         * never persisted: {@code OnboardingService#applyResult}'s {@code
         * FAILED} branch writes only {@code errorCode}/{@code detail} to
         * {@code tenant.onboarding_steps}, unlike the {@code COMPLETED}
         * branch, which serializes {@code result} to {@code
         * result_snapshot}. Only {@code OnboardingService#validate}'s dry
         * run reads this list, to expand one step into one countable,
         * deep-linkable {@code ValidationResult} row per finding.
         *
         * <p>{@link #errorCode} and {@link #detail} are always the first
         * finding's own — never a summary like "3 item(s) affected" — so a
         * caller that only reads those two fields (every existing test, and
         * {@code OnboardingService#applyResult}'s scheduler path) sees exactly
         * what it saw before this factory existed, whether one finding or many
         * came back.
         *
         * @param findings every offending item; must be non-empty
         */
        public static StepResult failedWithFindings(List<Finding> findings) {
            if (findings.isEmpty()) {
                throw new IllegalArgumentException("failedWithFindings requires at least one finding");
            }
            Finding first = findings.get(0);
            return new StepResult(
                    Outcome.FAILED,
                    Map.of(FINDINGS_KEY, List.copyOf(findings)),
                    null,
                    first.errorCode(),
                    first.detail());
        }

        /** The {@link #result} key {@link #failedWithFindings} stores its list under. */
        public static final String FINDINGS_KEY = "findings";

        /**
         * One offending item inside a {@link #failedWithFindings} result — one
         * location missing a legal entity, one location/provider pair with no
         * merchant binding, and so on.
         *
         * @param locationId the location this finding is about, when the check
         *                   is location-scoped, so the readiness panel can
         *                   deep-link to it; {@code null} for a tenant- or
         *                   brand-level finding
         */
        public record Finding(
                String errorCode, String detail, @Nullable UUID locationId) {}
    }
}
