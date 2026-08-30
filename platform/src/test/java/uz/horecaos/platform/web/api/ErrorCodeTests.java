package uz.horecaos.platform.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ProblemDetail;

/** ADR 0031: every error is Problem Details carrying a registered stable code. */
class ErrorCodeTests {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeHasAStableTypeUriAndTitle(ErrorCode code) {
        assertThat(code.typeUri()).startsWith("https://docs.horecaos.uz/problems/");
        assertThat(code.title()).isNotBlank();
        assertThat(code.status().value()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void problemCarriesTheMachineReadableCode() {
        ProblemDetail problem = ApiProblem.of(ErrorCode.STALE_VERSION, "changed");

        assertThat(problem.getProperties()).containsEntry("code", "STALE_VERSION");
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType().toString()).endsWith("stale-version");
    }

    @Test
    void capabilityFailureNamesTheCapabilityAndScopeButNotThePolicy() {
        ProblemDetail problem = ApiProblem.withProperties(
                ErrorCode.INSUFFICIENT_CAPABILITY,
                "Requires order.approve at LOCATION scope",
                ApiException.insufficientCapability("order.approve", "LOCATION").properties());

        assertThat(problem.getProperties())
                .containsEntry("requiredCapability", "order.approve")
                .containsEntry("requiredScope", "LOCATION");
        assertThat(problem.getDetail()).doesNotContain("policy");
    }

    @Test
    void capabilityAndEntitlementFailuresAreDistinguishable() {
        assertThat(ApiException.insufficientCapability("order.approve", "LOCATION").errorCode())
                .isNotEqualTo(ApiException.entitlementRequired("pos.integrations.enabled").errorCode());
    }

    @Test
    void staleVersionReportsBothVersions() {
        assertThat(ApiException.staleVersion(3, 5).properties())
                .containsEntry("expectedVersion", 3L)
                .containsEntry("currentVersion", 5L);
    }
}
