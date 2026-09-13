package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.TenantOrganizationDirectory;
import uz.horecaos.platform.iam.api.TenantRoleCatalog;
import uz.horecaos.platform.iam.application.AccessCheckService;
import uz.horecaos.platform.iam.application.AccessCheckService.AccessCheckAnswer;
import uz.horecaos.platform.iam.application.AccessCheckService.Verdict;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantView;
import uz.horecaos.platform.web.api.ApiException;

/** The two reads added for staff-and-access.md's Люди and Должности screens, and the IA 7.3 debugger. */
class GrantControllerTests {

    private final GrantManagementService grants = mock(GrantManagementService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final AccessCheckService accessCheck = mock(AccessCheckService.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final GrantController controller = new GrantController(
            grants, authorization, accessCheck, currentActor, mock(TenantOrganizationDirectory.class));

    @Test
    void listDefaultsToActiveOnlyAndForwardsTheFlagOtherwise() {
        UUID tenantId = UUID.randomUUID();

        controller.list(tenantId, false);
        verify(grants).listForTenant(tenantId, false);

        controller.list(tenantId, true);
        verify(grants).listForTenant(tenantId, true);
    }

    @Test
    void rolesReturnsTheStaticTenantRoleCatalogWithoutTouchingTheDatabase() {
        List<TenantRoleCatalog.RoleDescriptor> result = controller.roles(UUID.randomUUID());

        assertThat(result).isEqualTo(TenantRoleCatalog.tenantVisible());
    }

    @Test
    void debugAccessReusesTheSameViewForServiceAnySessionContextCallReuses() {
        UUID tenantId = UUID.randomUUID();
        CapabilityView view = new CapabilityView("keycloak|colleague", tenantId.toString(), Set.of(), List.of(), 1L);
        when(authorization.viewFor("keycloak|colleague", tenantId)).thenReturn(view);

        var response = controller.debugAccess("keycloak|colleague", tenantId, null, null, null);

        assertThat(response.view()).isSameAs(view);
        assertThat(response.requestedCapability()).isNull();
        assertThat(response.granted())
                .as("no capability was asked about, so there is nothing to answer yes or no to")
                .isNull();
    }

    @Test
    void debugAccessAnswersWhetherTheNamedCapabilityIsHeldAtTheNamedScope() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        when(authorization.viewFor(eq("keycloak|colleague"), eq(tenantId)))
                .thenReturn(new CapabilityView("keycloak|colleague", tenantId.toString(), Set.of(), List.of(), 1L));
        when(authorization.has("keycloak|colleague", Capability.CATALOG_AUTHOR, ResourceScope.brand(tenantId, brandId)))
                .thenReturn(true);

        var response = controller.debugAccess("keycloak|colleague", tenantId, brandId, null, Capability.CATALOG_AUTHOR);

        assertThat(response.requestedCapability()).isEqualTo(Capability.CATALOG_AUTHOR);
        assertThat(response.granted()).isTrue();
    }

    @Test
    void debugAccessFallsBackToPlatformScopeWithNoTenantId() {
        when(authorization.viewFor(eq("keycloak|colleague"), any()))
                .thenReturn(new CapabilityView("keycloak|colleague", null, Set.of(), List.of(), 1L));
        when(authorization.has("keycloak|colleague", Capability.PLATFORM_ADMIN, ResourceScope.platform()))
                .thenReturn(false);

        var response = controller.debugAccess("keycloak|colleague", null, null, null, Capability.PLATFORM_ADMIN);

        assertThat(response.granted()).isFalse();
    }

    // --------------------------------------------------------- accessCheck (Staff 9.5)

    /**
     * W03 adversarial-review finding: no test in the repository ever called
     * {@code controller.accessCheck(...)} at all — {@code
     * AccessCheckService}'s own scope-containment logic and {@code
     * askableScopeOf}'s PLATFORM refusal were exercised only at the
     * service-unit level, never through the controller a real request
     * actually reaches. Dropping {@code @RequiresCapability}, mis-wiring
     * {@code askableScopeOf}, or breaking {@code AccessCheckResponse.of}'s
     * mapping would all have compiled and passed every existing test.
     */
    @Test
    void accessCheckRefusesAPlatformScopeQuestion() {
        UUID tenantId = UUID.randomUUID();
        when(currentActor.get())
                .thenReturn(
                        new uz.horecaos.platform.iam.api.AuthenticatedActor("caller-1", Set.of(), java.util.Map.of()));

        assertThatThrownBy(() -> controller.accessCheck(
                        tenantId, "colleague", Capability.ORDER_APPROVE, ScopeType.PLATFORM, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PLATFORM");
    }

    @Test
    void accessCheckRefusesABrandQuestionWithNoBrandId() {
        UUID tenantId = UUID.randomUUID();
        when(currentActor.get())
                .thenReturn(
                        new uz.horecaos.platform.iam.api.AuthenticatedActor("caller-1", Set.of(), java.util.Map.of()));

        assertThatThrownBy(() -> controller.accessCheck(
                        tenantId, "colleague", Capability.ORDER_APPROVE, ScopeType.BRAND, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("brandId");
    }

    @Test
    void accessCheckRefusesALocationQuestionWithNoLocationId() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        when(currentActor.get())
                .thenReturn(
                        new uz.horecaos.platform.iam.api.AuthenticatedActor("caller-1", Set.of(), java.util.Map.of()));

        assertThatThrownBy(() -> controller.accessCheck(
                        tenantId, "colleague", Capability.ORDER_APPROVE, ScopeType.LOCATION, brandId, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("locationId");
    }

    @Test
    void accessCheckMapsAnAllowedAnswerThroughToTheResponseIncludingHeldElsewhere() {
        UUID tenantId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        when(currentActor.get())
                .thenReturn(
                        new uz.horecaos.platform.iam.api.AuthenticatedActor("caller-1", Set.of(), java.util.Map.of()));

        GrantView heldGrant = new GrantView(
                UUID.randomUUID(),
                "colleague",
                "location-manager",
                "LOCATION",
                locationId,
                "ACTIVE",
                "owner-1",
                "onboarding",
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                null,
                null);
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        AccessCheckAnswer answer =
                new AccessCheckAnswer(Verdict.ALLOWED, Capability.ORDER_APPROVE, scope, List.of(heldGrant), null);
        when(accessCheck.check("caller-1", "colleague", Capability.ORDER_APPROVE, scope, null))
                .thenReturn(answer);

        var response = controller.accessCheck(
                tenantId, "colleague", Capability.ORDER_APPROVE, ScopeType.LOCATION, brandId, locationId, null);

        assertThat(response.verdict()).isEqualTo(Verdict.ALLOWED);
        assertThat(response.capability()).isEqualTo(Capability.ORDER_APPROVE);
        assertThat(response.scopeType()).isEqualTo(ScopeType.LOCATION);
        assertThat(response.scopeId()).isEqualTo(locationId);
        assertThat(response.heldElsewhere()).containsExactly(heldGrant);
        assertThat(response.entitlement()).isNull();
    }

    @Test
    void accessCheckRefusesTheCallerWhenAccessCheckServiceThrows() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        when(currentActor.get())
                .thenReturn(
                        new uz.horecaos.platform.iam.api.AuthenticatedActor("caller-1", Set.of(), java.util.Map.of()));

        ResourceScope scope = ResourceScope.brand(tenantId, brandId);
        when(accessCheck.check("caller-1", "colleague", Capability.ORDER_APPROVE, scope, null))
                .thenThrow(new AuthorizationService.AccessDeniedException(Capability.IAM_GRANT_MANAGE, scope));

        assertThatThrownBy(() -> controller.accessCheck(
                        tenantId, "colleague", Capability.ORDER_APPROVE, ScopeType.BRAND, brandId, null, null))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);
    }
}
