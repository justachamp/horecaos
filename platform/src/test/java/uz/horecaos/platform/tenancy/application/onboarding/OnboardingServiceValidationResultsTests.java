package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;

/**
 * {@link OnboardingService#validationResultsFor} — the wave P31 reshape
 * behind the gap map's row 10.0: a countable, deep-linkable list rather than
 * one row per step regardless of how many locations a check named.
 *
 * <p>Pure logic, no database: {@link OnboardingStepHandlersTests}
 * already proves a real handler accumulates every offending location instead
 * of returning on the first; this class proves the one step of plumbing
 * between that accumulation and the API response — {@code validate}'s own
 * loop is otherwise exercised end to end by {@code OnboardingServiceTests}.
 */
class OnboardingServiceValidationResultsTests {

    private static final UUID LOCATION_A = UUID.randomUUID();
    private static final UUID LOCATION_B = UUID.randomUUID();

    @Test
    void aPlainCompletedOutcomeIsStillExactlyOneRow() {
        StepResult outcome = StepResult.completed(Map.of(), null);

        List<OnboardingService.ValidationResult> results =
                OnboardingService.validationResultsFor(OnboardingStep.BRANDS_AND_LOCATIONS_VALIDATE, outcome);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.passed()).isTrue();
            assertThat(result.errorCode()).isNull();
            assertThat(result.locationId()).isNull();
        });
    }

    @Test
    void aPlainFailureWithNoFindingsListIsStillExactlyOneRow() {
        // The shape every non-location-looping handler still returns —
        // BRANDS_AND_LOCATIONS_VALIDATE's own NO_LOCATION, for instance.
        StepResult outcome = StepResult.failed("NO_LOCATION", "The tenant has no location");

        List<OnboardingService.ValidationResult> results =
                OnboardingService.validationResultsFor(OnboardingStep.BRANDS_AND_LOCATIONS_VALIDATE, outcome);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.passed()).isFalse();
            assertThat(result.errorCode()).isEqualTo("NO_LOCATION");
            assertThat(result.locationId()).isNull();
        });
    }

    @Test
    void aFailureWithMultipleFindingsExpandsIntoOneCountableDeepLinkableRowEach() {
        StepResult outcome = StepResult.failedWithFindings(List.of(
                new StepResult.Finding("NO_LEGAL_ENTITY", "Location A has no active legal entity assigned", LOCATION_A),
                new StepResult.Finding(
                        "NO_LEGAL_ENTITY", "Location B has no active legal entity assigned", LOCATION_B)));

        List<OnboardingService.ValidationResult> results =
                OnboardingService.validationResultsFor(OnboardingStep.PAYMENT_CONFIGURATION_VALIDATE, outcome);

        assertThat(results)
                .as("one row per offending location, not one row for the whole step")
                .hasSize(2)
                .allSatisfy(result -> {
                    assertThat(result.passed()).isFalse();
                    assertThat(result.stepKey()).isEqualTo("PAYMENT_CONFIGURATION_VALIDATE");
                    assertThat(result.errorCode()).isEqualTo("NO_LEGAL_ENTITY");
                });
        assertThat(results)
                .as("each row carries its own location, so the readiness panel can deep-link into it")
                .extracting(OnboardingService.ValidationResult::locationId)
                .containsExactlyInAnyOrder(LOCATION_A, LOCATION_B);
    }

    @Test
    void aRetryOutcomeIsStillExactlyOneRowEvenThoughItIsNotPassed() {
        StepResult outcome = StepResult.retry("TRANSIENT_INFRASTRUCTURE", "SQLException");

        List<OnboardingService.ValidationResult> results =
                OnboardingService.validationResultsFor(OnboardingStep.POS_BINDINGS_VALIDATE, outcome);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.passed())
                    .as("only COMPLETED counts as passed; RETRY is not silently green")
                    .isFalse();
            assertThat(result.errorCode()).isEqualTo("TRANSIENT_INFRASTRUCTURE");
        });
    }
}
