package uz.horecaos.platform.iam.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0186's deny-list, which is derived from what the code-owned human jobs carry and so cannot
 * be written down twice and drift. These pin the three things it must keep true: a machine's,
 * HorecaOS staff's and the power-to-give-power capabilities are never composable, the catalogue is
 * exactly what the visible jobs carry, and a human job's ordinary capability is.
 */
class TenantJobRulesTests {

    @Test
    @DisplayName("the catalogue is exactly the union of what the tenant-visible code-owned jobs carry")
    void theCatalogueIsTheUnionOfTheVisibleJobs() {
        Set<Capability> union = EnumSet.noneOf(Capability.class);
        TenantRoleCatalog.tenantVisibleRoles().forEach(role -> union.addAll(role.capabilities()));

        assertThat(TenantJobRules.catalogue()).containsExactlyInAnyOrderElementsOf(union);
        assertThat(TenantRoleCatalog.tenantVisibleRoles())
                .as("the eight, and never a platform, device or support-session role")
                .hasSize(8)
                .doesNotContain(
                        PlatformRole.PLATFORM_ADMIN,
                        PlatformRole.PLATFORM_SUPPORT,
                        PlatformRole.KITCHEN_DEVICE,
                        PlatformRole.KITCHEN_VDU_DEVICE,
                        PlatformRole.PRINT_AGENT,
                        PlatformRole.KIOSK_DEVICE);
    }

    @Test
    @DisplayName("nothing in platform.* or iam.* is composable, whoever holds it")
    void thePowerToGivePowerIsNeverComposable() {
        for (Capability capability : Capability.values()) {
            if (capability.resourceType().equals("platform")
                    || capability.resourceType().equals("iam")) {
                assertThat(TenantJobRules.isComposable(capability))
                        .as(capability.code())
                        .isFalse();
            }
        }
        assertThat(TenantJobRules.catalogue())
                .as("but the owner's own powers are shown, locked, so the grid does not understate her job")
                .contains(Capability.IAM_GRANT_MANAGE, Capability.IAM_ROLE_MANAGE);
    }

    @Test
    @DisplayName("what only a machine, HorecaOS staff or a per-person grant ever holds is not composable")
    void whatNoHumanJobCarriesIsNotComposable() {
        assertThat(TenantJobRules.composable())
                .doesNotContain(
                        Capability.PRINT_JOB_CLAIM,
                        Capability.PRINT_JOB_REPORT,
                        Capability.KIOSK_DEVICE_READ,
                        Capability.COMMERCIAL_PLAN_MANAGE,
                        Capability.METRIC_MANAGE,
                        Capability.COURIER_TRACK_REVEAL,
                        Capability.COURIER_REGISTRATION_REVEAL,
                        Capability.MARKETPLACE_ORDER_RECEIVE);
    }

    @Test
    @DisplayName("every capability a branch's own job carries can be composed, so a company can make a job like it")
    void aHumanJobsOrdinaryCapabilitiesAreComposable() {
        PlatformRole.LOCATION_STAFF.capabilities().stream()
                .filter(capability -> !capability.resourceType().equals("iam"))
                .forEach(capability -> assertThat(TenantJobRules.isComposable(capability))
                        .as(capability.code())
                        .isTrue());
    }

    @Test
    @DisplayName("a generated code can never be a code-owned job's, and the limits are the ADR's")
    void theGeneratedCodeCannotCollideWithACodeOwnedRole() {
        for (PlatformRole role : PlatformRole.values()) {
            assertThat(role.code()).doesNotStartWith(TenantJobRules.CODE_PREFIX);
        }
        assertThat(TenantJobRules.MAX_ACTIVE_JOBS).isEqualTo(20);
        assertThat(TenantJobRules.MAX_NAME_LENGTH).isEqualTo(80);
    }

    // ------------------------------------------- the second factor for a job the company composed

    @Test
    @DisplayName(
            "the powers that ask for a second factor include money and customer data, and not what a branch manager has")
    void theMfaSensitivePowersAreTheOnesOnlySensitiveJobsHold() {
        assertThat(TenantJobRules.mfaSensitive())
                .contains(
                        Capability.REFUND_APPROVE,
                        Capability.CUSTOMER_PII_EXPORT,
                        Capability.COURIER_PAYOUT_AUTHORISE,
                        Capability.FISCAL_EVIDENCE_REVEAL,
                        Capability.IAM_STAFF_MFA_RESET)
                .doesNotContain(
                        Capability.ORDER_READ,
                        Capability.ORDER_CANCEL,
                        Capability.REFUND_REQUEST,
                        Capability.CUSTOMER_PII_REVEAL,
                        Capability.IAM_GRANT_MANAGE);

        Set<Capability> ordinary = EnumSet.noneOf(Capability.class);
        Set<Capability> sensitive = EnumSet.noneOf(Capability.class);
        for (PlatformRole role : TenantRoleCatalog.tenantVisibleRoles()) {
            (role.mfaSensitive() ? sensitive : ordinary).addAll(role.capabilities());
        }
        assertThat(TenantJobRules.mfaSensitive())
                .as("held by a sensitive job and by no ordinary one; recomputed here from the flag, not from the rule")
                .allSatisfy(capability -> {
                    assertThat(sensitive).contains(capability);
                    assertThat(ordinary).doesNotContain(capability);
                })
                .hasSize(sensitive.size()
                        - (int) sensitive.stream().filter(ordinary::contains).count());
    }

    @Test
    @DisplayName(
            "a copy of any code-owned job, less its iam.* rows, is asked for a second factor exactly as the original is")
    void aCopyOfACodeOwnedJobIsHeldToTheOriginalsRequirement() {
        for (PlatformRole role : TenantRoleCatalog.tenantVisibleRoles()) {
            Set<String> composable = role.capabilities().stream()
                    .filter(TenantJobRules::isComposable)
                    .map(Capability::code)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(TenantJobRules.isMfaSensitive(composable))
                    .as("a copy of %s", role.code())
                    .isEqualTo(role.mfaSensitive());
        }
    }

    @Test
    @DisplayName("nothing, or a code the build does not declare, is sensitive: a short answer is not an ordinary job")
    void anUnreadableJobIsSensitive() {
        assertThat(TenantJobRules.isMfaSensitive(Set.of())).isTrue();
        assertThat(TenantJobRules.isMfaSensitive(Set.of("order.read", "no.such.capability")))
                .isTrue();
        assertThat(TenantJobRules.isMfaSensitive(Set.of("order.read", "ORDER.CANCEL")))
                .as("a code is looked up the way Capability.find reads it")
                .isFalse();
    }
}
