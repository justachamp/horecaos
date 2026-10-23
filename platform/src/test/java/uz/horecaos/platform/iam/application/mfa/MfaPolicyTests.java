package uz.horecaos.platform.iam.application.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.mfa.MfaRequirementMode;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Requirement;

/** Who must hold a second factor (ADR 0148, Decision 4). */
class MfaPolicyTests {

    private static final String ADMIN = "platform-subject";
    private static final String PERSON = "tenant-subject";
    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-7000-8000-000000000002");

    private final Map<String, Set<String>> platformGrants = new HashMap<>();
    private final Map<UUID, Set<String>> tenantRoles = new HashMap<>();
    private final Map<UUID, MfaRequirementMode> modes = new HashMap<>();

    /** The tenant jobs the fixture's tenant has defined, by code, and what each carries (ADR 0186). */
    private final Map<String, Set<String>> tenantJobs = new HashMap<>();

    /** Every code the policy asked the facts about, so a test can say the OFF path never reads a job. */
    private final java.util.List<String> jobLookups = new java.util.ArrayList<>();

    private MfaPolicy policy(String enforcement) {
        StaffAccountFacts facts = new StaffAccountFacts() {
            @Override
            public boolean holdsPlatformGrant(String subject) {
                return platformGrants.containsKey(subject);
            }

            @Override
            public Map<UUID, Set<String>> rolesByTenant(String subject) {
                return PERSON.equals(subject) ? tenantRoles : Map.of();
            }

            @Override
            public Map<String, Set<String>> tenantJobCapabilities(UUID tenantId, Set<String> roleCodes) {
                jobLookups.addAll(roleCodes);
                Map<String, Set<String>> found = new HashMap<>();
                for (String code : roleCodes) {
                    if (tenantJobs.containsKey(code)) {
                        found.put(code, tenantJobs.get(code));
                    }
                }
                return found;
            }

            @Override
            public boolean holdsRoleInTenant(String subject, UUID tenantId, String roleCode) {
                return false;
            }

            @Override
            public Optional<String> uiLocale(String subject) {
                return Optional.empty();
            }
        };
        return new MfaPolicy(facts, tenantId -> modes.getOrDefault(tenantId, MfaRequirementMode.OFF), enforcement);
    }

    private static String code(PlatformRole role) {
        return role.code();
    }

    @Test
    @DisplayName(
            "a platform account is asked on the deploy setting's phase: OFF asks nobody, PROMPT offers, REQUIRED requires")
    void thePlatformRuleShipsInThreePhases() {
        platformGrants.put(ADMIN, Set.of(code(PlatformRole.PLATFORM_ADMIN)));

        assertThat(policy("OFF").requirementFor(ADMIN, false)).isEqualTo(Requirement.NOT_REQUIRED);
        assertThat(policy("PROMPT").requirementFor(ADMIN, false)).isEqualTo(Requirement.OFFERED);
        assertThat(policy("REQUIRED").requirementFor(ADMIN, false)).isEqualTo(Requirement.REQUIRED);
        assertThat(policy("required").requirementFor(ADMIN, false)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    @DisplayName("the bootstrap administrator, who may hold no grant row, is a platform account by the realm role")
    void theRealmRoleCountsAsAPlatformAccount() {
        assertThat(policy("REQUIRED").requirementFor("a-subject-with-no-grants", true))
                .isEqualTo(Requirement.REQUIRED);
        assertThat(policy("REQUIRED").requirementFor("a-subject-with-no-grants", false))
                .isEqualTo(Requirement.NOT_REQUIRED);
    }

    @Test
    @DisplayName(
            "a mistyped enforcement setting stops the platform at startup instead of quietly switching the control off")
    void aMistypedEnforcementSettingIsRefused() {
        assertThatThrownBy(() -> policy("REQUIRD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OFF, PROMPT or REQUIRED");
    }

    @Test
    @DisplayName("a tenant account is asked nothing while its tenant's setting is OFF, which is the default")
    void aTenantThatNeverOpenedTheSettingAsksNobody() {
        for (PlatformRole role : PlatformRole.values()) {
            tenantRoles.put(TENANT, Set.of(code(role)));
            assertThat(policy("REQUIRED").requirementFor(PERSON, false))
                    .as(role.name())
                    .isEqualTo(Requirement.NOT_REQUIRED);
        }
    }

    @Test
    @DisplayName(
            "SENSITIVE_ROLES asks the owner, the administrator, finance and the brand manager, and nobody on a terminal")
    void sensitiveRolesAreCodeOwned() {
        modes.put(TENANT, MfaRequirementMode.SENSITIVE_ROLES);

        for (PlatformRole role : new PlatformRole[] {
            PlatformRole.TENANT_OWNER,
            PlatformRole.TENANT_ADMIN,
            PlatformRole.TENANT_FINANCE,
            PlatformRole.BRAND_MANAGER
        }) {
            tenantRoles.put(TENANT, Set.of(code(role)));
            assertThat(policy("OFF").requirementFor(PERSON, false))
                    .as(role.name())
                    .isEqualTo(Requirement.REQUIRED);
        }
        for (PlatformRole role : new PlatformRole[] {
            PlatformRole.LOCATION_MANAGER,
            PlatformRole.LOCATION_STAFF,
            PlatformRole.SUPPORT_AGENT,
            PlatformRole.COURIER_DISPATCHER
        }) {
            tenantRoles.put(TENANT, Set.of(code(role)));
            assertThat(policy("OFF").requirementFor(PERSON, false))
                    .as(role.name())
                    .isEqualTo(Requirement.NOT_REQUIRED);
        }
    }

    @Test
    @DisplayName("a person holding one sensitive job among ordinary ones is asked")
    void oneSensitiveJobIsEnough() {
        modes.put(TENANT, MfaRequirementMode.SENSITIVE_ROLES);
        tenantRoles.put(TENANT, Set.of(code(PlatformRole.LOCATION_STAFF), code(PlatformRole.TENANT_FINANCE)));

        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    @DisplayName("ALL_STAFF asks everybody with a job, but a kitchen device and a support session are not people")
    void allStaffSkipsMachineGrants() {
        modes.put(TENANT, MfaRequirementMode.ALL_STAFF);

        tenantRoles.put(TENANT, Set.of(code(PlatformRole.LOCATION_STAFF)));
        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.REQUIRED);

        for (PlatformRole machine : new PlatformRole[] {
            PlatformRole.KITCHEN_DEVICE,
            PlatformRole.PRINT_AGENT,
            PlatformRole.KITCHEN_VDU_DEVICE,
            PlatformRole.KIOSK_DEVICE,
            PlatformRole.SUPPORT_SESSION_VIEW,
            PlatformRole.SUPPORT_SESSION_ASSIST
        }) {
            tenantRoles.put(TENANT, Set.of(code(machine)));
            assertThat(policy("OFF").requirementFor(PERSON, false))
                    .as(machine.name())
                    .isEqualTo(Requirement.NOT_REQUIRED);
        }
    }

    @Test
    @DisplayName("a person in two tenants is asked when either asks, and each tenant's setting is its own")
    void theStricterTenantWins() {
        modes.put(TENANT, MfaRequirementMode.OFF);
        modes.put(OTHER_TENANT, MfaRequirementMode.ALL_STAFF);
        tenantRoles.put(TENANT, Set.of(code(PlatformRole.TENANT_OWNER)));
        tenantRoles.put(OTHER_TENANT, Set.of(code(PlatformRole.LOCATION_STAFF)));

        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.REQUIRED);

        modes.put(OTHER_TENANT, MfaRequirementMode.OFF);
        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.NOT_REQUIRED);
    }

    @Test
    @DisplayName("the platform rule and the tenant setting meet by taking the stricter")
    void thePlatformAndTenantRulesTakeTheStricter() {
        platformGrants.put(PERSON, Set.of(code(PlatformRole.PLATFORM_SUPPORT)));
        modes.put(TENANT, MfaRequirementMode.ALL_STAFF);
        tenantRoles.put(TENANT, Set.of(code(PlatformRole.LOCATION_STAFF)));

        assertThat(policy("PROMPT").requirementFor(PERSON, false)).isEqualTo(Requirement.REQUIRED);
        modes.put(TENANT, MfaRequirementMode.OFF);
        assertThat(policy("PROMPT").requirementFor(PERSON, false)).isEqualTo(Requirement.OFFERED);
    }

    // ------------------------------------------------ a job the company composed (ADR 0186)

    private static final String FINANCIER = "job-3f9a1c0b2d";
    private static final String CASHIER = "job-8c01d7e5a4";

    @Test
    @DisplayName("SENSITIVE_ROLES asks the holder of a company-made job that carries a power only sensitive jobs carry")
    void aCompanyMadeJobCarryingMoneyIsAsked() {
        modes.put(TENANT, MfaRequirementMode.SENSITIVE_ROLES);
        // 'tenant-finance' copied without its iam.* rows and renamed: the powers are the finance job's.
        Set<String> financeWithoutIam = new java.util.TreeSet<>();
        for (var capability : PlatformRole.TENANT_FINANCE.capabilities()) {
            if (!capability.resourceType().equals("iam")
                    && !capability.resourceType().equals("platform")) {
                financeWithoutIam.add(capability.code());
            }
        }
        tenantJobs.put(FINANCIER, financeWithoutIam);
        tenantRoles.put(TENANT, Set.of(FINANCIER));

        assertThat(policy("OFF").requirementFor(PERSON, false))
                .as("a colleague holding the code-owned tenant-finance is asked, and so is the holder of its copy")
                .isEqualTo(Requirement.REQUIRED);
    }

    @Test
    @DisplayName("a company-made job carrying one approval of money is enough, among otherwise ordinary powers")
    void oneSensitivePowerIsEnough() {
        modes.put(TENANT, MfaRequirementMode.SENSITIVE_ROLES);
        tenantJobs.put(FINANCIER, Set.of("order.read", "order.cancel", "refund.approve"));
        tenantRoles.put(TENANT, Set.of(FINANCIER));

        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    @DisplayName("a company-made job of ordinary powers asks nobody, as the code-owned cook and cashier do not")
    void aCompanyMadeJobOfOrdinaryPowersIsNotAsked() {
        modes.put(TENANT, MfaRequirementMode.SENSITIVE_ROLES);
        // Every one of these a branch manager, who is not sensitive, already holds.
        tenantJobs.put(CASHIER, Set.of("order.read", "order.cancel", "refund.request", "customer.pii.reveal"));
        tenantRoles.put(TENANT, Set.of(CASHIER));

        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.NOT_REQUIRED);
    }

    @Test
    @DisplayName("a company-made job whose capabilities could not be read is asked, not waved through")
    void aJobWhoseCapabilitiesAreNotKnownIsAsked() {
        modes.put(TENANT, MfaRequirementMode.SENSITIVE_ROLES);
        tenantRoles.put(TENANT, Set.of(CASHIER));

        assertThat(policy("OFF").requirementFor(PERSON, false))
                .as("the facts know no such job: a read that RLS or a join cut short must not read as 'ordinary'")
                .isEqualTo(Requirement.REQUIRED);

        tenantJobs.put(CASHIER, Set.of());
        assertThat(policy("OFF").requirementFor(PERSON, false))
                .as("a job that carries nothing cannot be written, so it is a cut-short answer too")
                .isEqualTo(Requirement.REQUIRED);

        tenantJobs.put(CASHIER, Set.of("order.read", "a.capability.the.build.no.longer.declares"));
        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    @DisplayName("the tenant's other settings are unchanged by a company-made job: OFF asks nobody and reads no job")
    void aCompanyMadeJobDoesNotChangeTheOtherModes() {
        tenantJobs.put(FINANCIER, Set.of("refund.approve"));
        tenantRoles.put(TENANT, Set.of(FINANCIER));

        modes.put(TENANT, MfaRequirementMode.OFF);
        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.NOT_REQUIRED);
        assertThat(jobLookups).as("OFF has no use for what a job carries").isEmpty();

        tenantJobs.put(CASHIER, Set.of("order.read"));
        tenantRoles.put(TENANT, Set.of(CASHIER));
        modes.put(TENANT, MfaRequirementMode.ALL_STAFF);
        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.REQUIRED);
        assertThat(jobLookups).as("nor has ALL_STAFF").isEmpty();
    }

    @Test
    @DisplayName("a code-owned sensitive job short-circuits: the policy asks the facts about company-made jobs only")
    void codeOwnedJobsAreNotLookedUp() {
        modes.put(TENANT, MfaRequirementMode.SENSITIVE_ROLES);
        tenantJobs.put(CASHIER, Set.of("order.read"));
        tenantRoles.put(TENANT, Set.of(code(PlatformRole.LOCATION_STAFF), CASHIER));

        assertThat(policy("OFF").requirementFor(PERSON, false)).isEqualTo(Requirement.NOT_REQUIRED);
        assertThat(jobLookups).containsExactly(CASHIER);
    }

    @Test
    @DisplayName("the sensitive roles are exactly the four ADR 0148 names")
    void theSensitiveFlagIsPinned() {
        Set<PlatformRole> sensitive = new java.util.HashSet<>();
        for (PlatformRole role : PlatformRole.values()) {
            if (role.mfaSensitive()) {
                sensitive.add(role);
            }
        }
        assertThat(sensitive)
                .containsExactlyInAnyOrder(
                        PlatformRole.TENANT_OWNER,
                        PlatformRole.TENANT_ADMIN,
                        PlatformRole.TENANT_FINANCE,
                        PlatformRole.BRAND_MANAGER);
        assertThat(PlatformRole.isMfaSensitive("no-such-role")).isFalse();
    }
}
