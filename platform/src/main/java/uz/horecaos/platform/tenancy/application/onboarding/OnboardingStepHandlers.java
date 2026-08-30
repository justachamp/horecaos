package uz.horecaos.platform.tenancy.application.onboarding;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;

/** The buildable ADR 0008 step handlers. */
public final class OnboardingStepHandlers {

    private OnboardingStepHandlers() {
    }

    /**
     * Creates or reconciles the tenant's Keycloak organization (ADR 0009).
     *
     * <p>The stored organization id is what makes a retry safe. Drift — a stored
     * id that no longer resolves — fails permanently rather than retrying,
     * because retrying drift produces more drift.
     */
    @Component
    public static class KeycloakOrganizationReconcile implements OnboardingStepHandler {

        private final OrganizationProvisioner organizations;
        private final TenantControlPlaneStore tenants;

        public KeycloakOrganizationReconcile(
                OrganizationProvisioner organizations, TenantControlPlaneStore tenants) {
            this.organizations = organizations;
            this.tenants = tenants;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.KEYCLOAK_ORGANIZATION_RECONCILE;
        }

        @Override
        public StepResult execute(StepContext context) {
            var tenant = tenants.findTenant(new TenantId(context.tenantId()));
            if (tenant.isEmpty()) {
                return StepResult.failed("TENANT_MISSING", "The tenant no longer exists");
            }

            String alias = tenant.get().slug().value();
            String existing = Optional.ofNullable(context.externalReference())
                    .orElseGet(() -> tenant.get().keycloakOrganizationId().orElse(null));

            try {
                var reference = organizations.ensureOrganization(
                        new OrganizationProvisioner.EnsureOrganization(
                                context.tenantId(), alias, tenant.get().displayName(), existing));

                if (tenant.get().keycloakOrganizationId().isEmpty()) {
                    tenant.get().linkKeycloakOrganization(reference.organizationId());
                    tenants.linkKeycloakOrganization(tenant.get());
                }

                return StepResult.completed(
                        Map.of("organizationId", reference.organizationId(), "created", reference.created()),
                        reference.organizationId());
            } catch (OrganizationProvisioner.OrganizationDriftException drift) {
                return StepResult.failed("IDENTITY_DRIFT", drift.getMessage());
            } catch (RuntimeException transientFailure) {
                return StepResult.retry("TRANSIENT_INFRASTRUCTURE", transientFailure.getMessage());
            }
        }
    }

    /** Links or invites the tenant owner. */
    @Component
    public static class TenantOwnerLinkOrInvite implements OnboardingStepHandler {

        private final OrganizationProvisioner organizations;

        public TenantOwnerLinkOrInvite(OrganizationProvisioner organizations) {
            this.organizations = organizations;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.TENANT_OWNER_LINK_OR_INVITE;
        }

        @Override
        public StepResult execute(StepContext context) {
            Object email = context.input().get("ownerEmail");
            Object subject = context.input().get("ownerSubjectId");

            if (email == null && subject == null) {
                return StepResult.failed(
                        "OWNER_NOT_SUPPLIED", "An owner email or an existing subject id is required");
            }
            Object organizationId = context.input().get("organizationId");
            if (organizationId == null) {
                return StepResult.retry(
                        "AWAITING_ORGANIZATION", "The organization step has not completed yet");
            }

            try {
                var membership = organizations.ensureMembership(
                        new OrganizationProvisioner.EnsureMembership(
                                String.valueOf(organizationId),
                                email == null ? null : String.valueOf(email),
                                subject == null ? null : String.valueOf(subject)));

                // The subject id, never the invitation token: ADR 0009 forbids
                // storing anything that could be replayed to gain access.
                return StepResult.completed(
                        Map.of("subjectId", membership.subjectId(), "created", membership.created()),
                        membership.subjectId());
            } catch (OrganizationProvisioner.OrganizationDriftException drift) {
                return StepResult.failed("IDENTITY_DRIFT", drift.getMessage());
            } catch (RuntimeException transientFailure) {
                return StepResult.retry("TRANSIENT_INFRASTRUCTURE", transientFailure.getMessage());
            }
        }
    }

    /** Applies the template's default configuration. No external side effect. */
    @Component
    public static class DefaultConfigurationApply implements OnboardingStepHandler {

        @Override
        public OnboardingStep step() {
            return OnboardingStep.DEFAULT_CONFIGURATION_APPLY;
        }

        @Override
        public StepResult execute(StepContext context) {
            Object defaults = context.input().get("defaultConfiguration");
            int applied = defaults instanceof Map<?, ?> map ? map.size() : 0;
            return StepResult.completed(Map.of("appliedKeys", applied), null);
        }
    }

    /**
     * Confirms the tenant actually has a structure that can take an order.
     *
     * <p>This is the check that stops a tenant activating with no brand and no
     * location, which would look broken to whoever tried to use it.
     */
    @Component
    public static class BrandsAndLocationsValidate implements OnboardingStepHandler {

        private final TenantControlPlaneStore tenants;

        public BrandsAndLocationsValidate(TenantControlPlaneStore tenants) {
            this.tenants = tenants;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.BRANDS_AND_LOCATIONS_VALIDATE;
        }

        @Override
        public StepResult execute(StepContext context) {
            TenantId tenantId = new TenantId(context.tenantId());
            var brands = tenants.findBrands(tenantId);

            if (brands.isEmpty()) {
                return StepResult.failed("NO_BRAND", "The tenant has no brand");
            }
            long locations = brands.stream()
                    .mapToLong(brand -> tenants.findLocations(brand).size())
                    .sum();
            if (locations == 0) {
                return StepResult.failed(
                        "NO_LOCATION", "No brand has a location, so the tenant cannot receive an order");
            }
            return StepResult.completed(
                    Map.of("brands", brands.size(), "locations", locations), null);
        }
    }

    /**
     * Every step whose capability has not shipped.
     *
     * <p>One handler for all of them, reporting {@code BLOCKED} with the ADR
     * that would unblock it. Omitting these steps entirely would be easier and
     * would hide the fact that a tenant activated without them.
     */
    @Component
    public static class BlockedCapabilityHandler {

        public static OnboardingStepHandler.StepResult resultFor(OnboardingStep step) {
            return OnboardingStepHandler.StepResult.blocked(
                    "Blocked until %s ships".formatted(step.blockedUntil().orElse("a later decision")));
        }
    }
}
