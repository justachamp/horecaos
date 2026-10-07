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
            PlatformRole.KITCHEN_DEVICE, PlatformRole.SUPPORT_SESSION_VIEW, PlatformRole.SUPPORT_SESSION_ASSIST
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
