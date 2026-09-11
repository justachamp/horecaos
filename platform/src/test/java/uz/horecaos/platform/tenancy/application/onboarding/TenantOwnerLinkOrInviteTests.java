package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepContext;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitations;

/**
 * The owner step's half of ADR 0097: it decrypts the address the start
 * endpoint encrypted, links the owner, and asks for an invitation in the
 * operator's chosen language; a run started before the change, with the
 * address in clear, still works.
 */
class TenantOwnerLinkOrInviteTests {

    private static final UUID TENANT = UUID.randomUUID();

    private final FieldProtection protection = TestProtection.envelope();
    private final List<OrganizationProvisioner.EnsureMembership> memberships = new ArrayList<>();
    private final List<String> invitedLocales = new ArrayList<>();

    @Test
    @DisplayName("the encrypted address is decrypted for the owner and the invitation is asked for in their language")
    void theProtectedAddressReachesTheIdentityProvider() {
        String sealed = protection
                .protect(TENANT, DataClass.PERSONAL, OnboardingInputs.ownerEmailRecord(TENANT), "owner@example.uz")
                .serialize();

        StepResult result = handler()
                .execute(context(Map.of(
                        OnboardingInputs.OWNER_EMAIL_PROTECTED,
                        sealed,
                        OnboardingInputs.OWNER_LOCALE,
                        "uz-latn",
                        "ownerSubjectId",
                        "",
                        "organizationId",
                        "org-1")));

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(memberships)
                .extracting(OrganizationProvisioner.EnsureMembership::email)
                .containsExactly("owner@example.uz");
        assertThat(memberships.getFirst().existingSubjectId())
                .as("a blank subject is no subject")
                .isNull();
        assertThat(invitedLocales).containsExactly("uz");
        assertThat(result.result()).containsEntry("invitation", OwnerInvitations.QUEUED);
        assertThat(sealed).doesNotContain("owner@example.uz");
    }

    @Test
    @DisplayName("a run started before the address was encrypted still links its owner")
    void aLegacyClearAddressStillWorks() {
        StepResult result =
                handler().execute(context(Map.of("ownerEmail", "owner@example.uz", "organizationId", "org-1")));

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(memberships)
                .extracting(OrganizationProvisioner.EnsureMembership::email)
                .containsExactly("owner@example.uz");
        assertThat(invitedLocales).containsExactly("ru");
    }

    @Test
    @DisplayName("an address encrypted for another tenant does not decrypt here")
    void anAddressCannotMoveBetweenTenants() {
        String sealedElsewhere = protection
                .protect(
                        UUID.randomUUID(),
                        DataClass.PERSONAL,
                        OnboardingInputs.ownerEmailRecord(UUID.randomUUID()),
                        "owner@example.uz")
                .serialize();

        StepResult result = handler()
                .execute(context(
                        Map.of(OnboardingInputs.OWNER_EMAIL_PROTECTED, sealedElsewhere, "organizationId", "org-1")));

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("OWNER_EMAIL_UNREADABLE");
        assertThat(memberships).isEmpty();
    }

    private OnboardingStepHandlers.TenantOwnerLinkOrInvite handler() {
        OrganizationProvisioner provisioner = new OrganizationProvisioner() {
            @Override
            public OrganizationRef ensureOrganization(EnsureOrganization command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
                return Optional.empty();
            }

            @Override
            public MembershipRef ensureMembership(EnsureMembership command) {
                memberships.add(command);
                return new MembershipRef(command.organizationId(), "owner-subject", true);
            }

            @Override
            public void setOrganizationEnabled(String organizationId, boolean enabled) {}
        };
        OwnerInvitations invitations = (tenantId, subjectId, locale, runId) -> {
            invitedLocales.add(locale);
            return OwnerInvitations.QUEUED;
        };
        return new OnboardingStepHandlers.TenantOwnerLinkOrInvite(
                provisioner, (tenantId, subjectId, reason) -> {}, protection, invitations);
    }

    private static StepContext context(Map<String, Object> input) {
        return new StepContext(UUID.randomUUID(), TENANT, input, null, 1);
    }
}
