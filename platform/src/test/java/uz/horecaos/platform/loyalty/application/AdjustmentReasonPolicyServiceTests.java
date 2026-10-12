package uz.horecaos.platform.loyalty.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy;
import uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy.Mode;
import uz.horecaos.platform.support.InMemoryPlatformPolicies;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The reason-code list on its own (ADR 0165, Decision 3): what it permits, what it counts, what it
 * refuses, and the ways it is allowed to fail.
 *
 * <p>The ledger-level cases, where an accepted code actually moves points, are in {@code
 * LoyaltyLedgerAndSplitTenderTests}; the HTTP mapping is in {@code
 * LoyaltyAdjustmentReasonCodesEndpointTests}. What is checked here is the part that needs no
 * database.
 */
class AdjustmentReasonPolicyServiceTests {

    private final InMemoryPlatformPolicies policies = new InMemoryPlatformPolicies();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AdjustmentReasonPolicyService service = new AdjustmentReasonPolicyService(policies, policies, meters);

    private static final ActorRef PLATFORM = ActorRef.user("platform-1", "Platform");

    @Test
    @DisplayName("with nothing authored the list is the five proposed codes, report-only, and carries no policy id")
    void theBuiltInDefaultIsReportOnlyWithTheFiveCodes() {
        AdjustmentReasonPolicyService.Effective effective = service.effective();

        assertThat(effective.policy().mode()).isEqualTo(Mode.REPORT_ONLY);
        assertThat(effective.policy().codes())
                .containsExactly(
                        "GOODWILL", "SERVICE_RECOVERY", "CORRECTION", "ACCOUNT_MERGE", "LEGACY_OPENING_BALANCE");
        assertThat(effective.policyId()).isNull();
        assertThat(effective.policyVersion()).isZero();
    }

    @Test
    @DisplayName("an authored document replaces the default and brings its id and version")
    void anAuthoredDocumentWins() {
        ResolvedPolicy<AdjustmentReasonPolicy> published = policies.author(
                AdjustmentReasonPolicyService.REASON_CODES,
                ResourceScope.platform(),
                new AdjustmentReasonPolicy(Mode.ENFORCE, List.of("GOODWILL", "COMPLAINT")),
                PLATFORM,
                "enforce");

        AdjustmentReasonPolicyService.Effective effective = service.effective();

        assertThat(effective.policy().mode()).isEqualTo(Mode.ENFORCE);
        assertThat(effective.policy().codes()).containsExactly("GOODWILL", "COMPLAINT");
        assertThat(effective.policyId()).isEqualTo(published.policyId());
        assertThat(effective.policyVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "the key is settable at PLATFORM and nowhere lower, so a tenant cannot add a code that means 'paid in cash'")
    void onlyThePlatformMayWidenTheList() {
        assertThat(AdjustmentReasonPolicyService.REASON_CODES.settableScopes()).containsExactly(ScopeType.PLATFORM);
    }

    @Test
    @DisplayName("a listed code passes in both modes without being counted; an unlisted one is counted in both")
    void theCounterCountsTheUnlistedInEitherMode() {
        service.admit("GOODWILL");
        service.admit("CASH_RECEIVED");
        policies.author(
                AdjustmentReasonPolicyService.REASON_CODES,
                ResourceScope.platform(),
                new AdjustmentReasonPolicy(Mode.ENFORCE, AdjustmentReasonPolicy.DEFAULT_CODES),
                PLATFORM,
                "enforce");
        service.admit("CORRECTION");
        assertThatThrownBy(() -> service.admit("CASH_RECEIVED")).isInstanceOf(ApiException.class);

        assertThat(count("REPORT_ONLY")).isEqualTo(1.0);
        assertThat(count("ENFORCE")).isEqualTo(1.0);
        assertThat(meters.find(AdjustmentReasonPolicyService.UNLISTED_COUNTER_NAME)
                        .counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags()).hasSize(1));
    }

    @Test
    @DisplayName("a code is compared exactly: a different case or a trailing space is not on the list")
    void aCodeIsComparedExactly() {
        policies.author(
                AdjustmentReasonPolicyService.REASON_CODES,
                ResourceScope.platform(),
                new AdjustmentReasonPolicy(Mode.ENFORCE, AdjustmentReasonPolicy.DEFAULT_CODES),
                PLATFORM,
                "enforce");

        for (String near : List.of("goodwill", "GOODWILL ", " GOODWILL", "GOOD_WILL")) {
            assertThatThrownBy(() -> service.admit(near))
                    .as("'%s'", near)
                    .isInstanceOfSatisfying(ApiException.class, refusal -> {
                        assertThat(refusal.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(refusal.getMessage()).doesNotContain(near.strip());
                    });
        }
    }

    @Test
    @DisplayName("an unreadable stored document does not stop every adjustment: the report-only default applies")
    void anUnreadableDocumentFallsBackToReportOnly() {
        PolicyResolver broken = new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                throw new IllegalStateException("Stored policy document does not match");
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                return Optional.empty();
            }
        };
        AdjustmentReasonPolicyService degraded = new AdjustmentReasonPolicyService(broken, policies, meters);

        assertThat(degraded.effective().policy().mode()).isEqualTo(Mode.REPORT_ONLY);
        assertThat(degraded.admit("CASH_RECEIVED").policyId()).isNull();
    }

    @Test
    @DisplayName("a document must list at least one well-formed code, once each")
    void aDocumentIsValidated() {
        assertThatThrownBy(() -> new AdjustmentReasonPolicy(Mode.ENFORCE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdjustmentReasonPolicy(Mode.ENFORCE, List.of("paid in cash")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdjustmentReasonPolicy(Mode.ENFORCE, List.of("GOODWILL", "GOODWILL")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private double count(String mode) {
        var counter = meters.find(AdjustmentReasonPolicyService.UNLISTED_COUNTER_NAME)
                .tag("mode", mode)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
