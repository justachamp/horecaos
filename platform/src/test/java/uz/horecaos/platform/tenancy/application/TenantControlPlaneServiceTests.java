package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.tenancy.api.BrandCreated;
import uz.horecaos.platform.tenancy.api.BrandDeleted;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.BrandRevised;
import uz.horecaos.platform.tenancy.api.LocationCreated;
import uz.horecaos.platform.tenancy.api.LocationDeleted;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.LocationRevised;
import uz.horecaos.platform.tenancy.api.TenantCreated;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.CreateBrandCommand;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.CreateLocationCommand;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.CreateTenantCommand;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.ReviseBrandCommand;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.ReviseLocationCommand;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityMode;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityPolicy;
import uz.horecaos.platform.tenancy.domain.Location;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;
import uz.horecaos.platform.tenancy.domain.TenantStatus;

class TenantControlPlaneServiceTests {

    @Test
    @DisplayName("a platform administrator can suspend a tenant and lift it again")
    void suspendingAndReactivatingATenantIsReachableAndAudited() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        List<uz.horecaos.platform.audit.api.AuditFact> auditFacts = new ArrayList<>();
        List<java.util.UUID> evicted = new ArrayList<>();
        RecordingOrganizationProvisioner provisioner = new RecordingOrganizationProvisioner();
        TenantControlPlaneService service = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                evicted::add,
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                auditFacts::add,
                () -> platformAdmin,
                noTransactions(),
                provisioner,
                scope -> 0);

        var created = service.createTenant(new CreateTenantCommand(
                "food-group",
                "Food Group LLC",
                "Food Group",
                "UZS",
                "Asia/Tashkent",
                CustomerIdentityMode.TENANT_SHARED));
        TenantId tenantId = new TenantId(created.id());
        service.linkKeycloakOrganization(tenantId, "keycloak-organization-food-group");
        takeLive(store, tenantId);

        var suspended = service.suspendTenant(tenantId, "non-payment");

        assertThat(suspended.status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(store.findTenant(tenantId).orElseThrow().status())
                .as("the status has to be persisted, not just returned -- until this existed "
                        + "Tenant.suspend() had no store method to write through")
                .isEqualTo(TenantStatus.SUSPENDED);
        assertThat(evicted)
                .as("a suspension that waits out a cache TTL is a suspension that is not yet in force")
                .contains(tenantId.value());
        assertThat(auditFacts).extracting(fact -> fact.actionCode()).contains("tenant.suspended");
        assertThat(provisioner.calls)
                .as("ADR 0009: suspension reconciles the tenant's Keycloak organization to disabled")
                .containsExactly(Map.entry("keycloak-organization-food-group", false));

        // The other half of the rule: whoever suspends must still be able to
        // undo it. A platform administrator's authority is the realm role, which
        // suspension does not touch, so this call must succeed against a
        // suspended tenant.
        var reactivated = service.reactivateTenant(tenantId, "paid");

        assertThat(reactivated.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(store.findTenant(tenantId).orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(auditFacts).extracting(fact -> fact.actionCode()).contains("tenant.reactivated");
        assertThat(provisioner.calls)
                .as("reactivation reconciles the same organization back to enabled")
                .containsExactly(
                        Map.entry("keycloak-organization-food-group", false),
                        Map.entry("keycloak-organization-food-group", true));
    }

    /**
     * The whole reason the Keycloak reconciliation is a second, best-effort
     * call rather than part of the status transaction: a suspension must land
     * in full even when Keycloak refuses it, because the status row -- not the
     * organization's {@code enabled} flag -- is what {@code
     * JdbcAuthorizationService} and ADR 0078's grant filter actually read.
     */
    @Test
    @DisplayName("a suspension is not undone, retried inline, or failed when Keycloak reconciliation fails")
    void keycloakReconciliationFailureDoesNotUndoOrBlockTheStatusChange() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        RecordingOrganizationProvisioner provisioner = new RecordingOrganizationProvisioner();
        provisioner.failNextCall = true;
        TenantControlPlaneService service = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                tenantId -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin,
                noTransactions(),
                provisioner,
                scope -> 0);

        var created = service.createTenant(new CreateTenantCommand(
                "food-group-2",
                "Food Group LLC",
                "Food Group",
                "UZS",
                "Asia/Tashkent",
                CustomerIdentityMode.TENANT_SHARED));
        TenantId tenantId = new TenantId(created.id());
        service.linkKeycloakOrganization(tenantId, "keycloak-organization-food-group-2");
        takeLive(store, tenantId);

        var suspended = service.suspendTenant(tenantId, "non-payment");

        assertThat(suspended.status())
                .as("Keycloak refusing the call must not be visible to the caller as a failed suspension")
                .isEqualTo(TenantStatus.SUSPENDED);
        assertThat(store.findTenant(tenantId).orElseThrow().status())
                .as("and the write it depends on must actually be durable, not rolled back with the exception")
                .isEqualTo(TenantStatus.SUSPENDED);
        assertThat(provisioner.attempts)
                .as("exactly one attempt -- a failure here is left for IdentityDriftReporter, not retried inline")
                .isEqualTo(1);
        assertThat(provisioner.calls)
                .as("the failed attempt never recorded a completed reconciliation")
                .isEmpty();
    }

    @Test
    @DisplayName("a tenant may not lift its own suspension")
    void suspendingIsPlatformAdminOnly() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        TenantControlPlaneService asPlatform = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                tenantId -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin,
                noTransactions(),
                new RecordingOrganizationProvisioner(),
                scope -> 0);
        var created = asPlatform.createTenant(new CreateTenantCommand(
                "food-group",
                "Food Group LLC",
                "Food Group",
                "UZS",
                "Asia/Tashkent",
                CustomerIdentityMode.TENANT_SHARED));
        TenantId tenantId = new TenantId(created.id());
        takeLive(store, tenantId);

        // No global roles: requirePlatformAdministrator asks for exactly one, and
        // an actor without it is what every non-platform caller looks like.
        AuthenticatedActor owner = new AuthenticatedActor("tenant-owner", Set.of(), Map.of());
        TenantControlPlaneService asOwner = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> owner, denyAll(), false),
                tenantId2 -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> owner,
                noTransactions(),
                new RecordingOrganizationProvisioner(),
                scope -> 0);

        assertThatThrownBy(() -> asOwner.suspendTenant(tenantId, "trying it on"))
                .as("the reasons a tenant is suspended are the platform's side of the relationship")
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(store.findTenant(tenantId).orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void createsATenantWithMultipleBrandsAndSingleBrandLocations() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        List<Object> events = new ArrayList<>();
        List<uz.horecaos.platform.audit.api.AuditFact> auditFacts = new ArrayList<>();
        TenantControlPlaneService service = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                tenantId -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                events::add,
                auditFacts::add,
                () -> platformAdmin,
                noTransactions(),
                new RecordingOrganizationProvisioner(),
                scope -> 0);

        var tenant = service.createTenant(new CreateTenantCommand(
                "food-group",
                "Food Group LLC",
                "Food Group",
                "UZS",
                "Asia/Tashkent",
                CustomerIdentityMode.TENANT_SHARED));
        service.linkKeycloakOrganization(new TenantId(tenant.id()), "keycloak-organization-food-group");

        var firstBrand =
                service.createBrand(new TenantId(tenant.id()), new CreateBrandCommand("BRAND_A", "brand-a", "Brand A"));
        var secondBrand =
                service.createBrand(new TenantId(tenant.id()), new CreateBrandCommand("BRAND_B", "brand-b", "Brand B"));
        var location = service.createLocation(
                new TenantId(tenant.id()),
                new BrandId(firstBrand.id()),
                new CreateLocationCommand("TASHKENT_1", "tashkent-1", "Tashkent One", "Asia/Tashkent"));

        assertThat(service.getBrands(new TenantId(tenant.id())))
                .extracting(TenantControlPlaneService.BrandView::id)
                .containsExactly(firstBrand.id(), secondBrand.id());
        assertThat(service.getLocations(new TenantId(tenant.id()), new BrandId(firstBrand.id())))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.id()).isEqualTo(location.id());
                    assertThat(saved.brandId()).isEqualTo(firstBrand.id());
                });

        // Operations Settings 10.1/10.2 read one brand or one location rather than the
        // whole tenant's list — the same rows getBrands/getLocations already found, filtered.
        assertThat(service.getBrand(new TenantId(tenant.id()), new BrandId(secondBrand.id()))
                        .id())
                .isEqualTo(secondBrand.id());
        assertThat(service.getLocation(
                                new TenantId(tenant.id()), new BrandId(firstBrand.id()), new LocationId(location.id()))
                        .id())
                .isEqualTo(location.id());
        assertThatThrownBy(() -> service.getLocation(
                        new TenantId(tenant.id()), new BrandId(secondBrand.id()), new LocationId(location.id())))
                .as("a location from a different brand must not resolve")
                .isInstanceOf(TenantResourceNotFoundException.class);
        assertThat(store.identityModes.get(new TenantId(tenant.id()))).isEqualTo(CustomerIdentityMode.TENANT_SHARED);
        assertThat(auditFacts)
                .as("ADR 0027: every control-plane creation records who caused it and why")
                .extracting(uz.horecaos.platform.audit.api.AuditFact::actionCode)
                .containsExactly(
                        "tenant.created",
                        "tenant.keycloak_organization_linked",
                        "brand.created",
                        "brand.created",
                        "location.created");
        assertThat(auditFacts).allSatisfy(fact -> assertThat(fact.reason()).isNotBlank());

        assertThat(events)
                .extracting(Object::getClass)
                .containsExactly(TenantCreated.class, BrandCreated.class, BrandCreated.class, LocationCreated.class);
        assertThat(events.getFirst()).isInstanceOfSatisfying(TenantCreated.class, event -> {
            assertThat(event.tenantId().value()).isEqualTo(tenant.id());
            assertThat(event.eventVersion()).isEqualTo(1);
            assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-19T00:00:00Z"));
        });
    }

    @Test
    void findsATenantBySlugOrAnswersEmpty() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        TenantControlPlaneService service = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                tenantId -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin,
                noTransactions(),
                new RecordingOrganizationProvisioner(),
                scope -> 0);

        var tenant = service.createTenant(new CreateTenantCommand(
                "horecaos", "HorecaOS LLC", "HorecaOS", "UZS", "Asia/Tashkent", CustomerIdentityMode.TENANT_SHARED));

        assertThat(service.findTenantBySlug("horecaos"))
                .as("a provisioning tool re-running against a known slug must recover the same id")
                .get()
                .extracting(TenantControlPlaneService.TenantView::id)
                .isEqualTo(tenant.id());
        assertThat(service.findTenantBySlug("no-such-tenant")).isEmpty();
    }

    @Test
    void listsTenantsForTheControlPlaneDirectoryButRefusesAnyoneNotPlatformAdmin() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        TenantControlPlaneService service = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                tenantId -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin,
                noTransactions(),
                new RecordingOrganizationProvisioner(),
                scope -> 0);

        var first = service.createTenant(new CreateTenantCommand(
                "directory-a",
                "Directory A LLC",
                "Directory A",
                "UZS",
                "Asia/Tashkent",
                CustomerIdentityMode.TENANT_SHARED));
        var second = service.createTenant(new CreateTenantCommand(
                "directory-b",
                "Directory B LLC",
                "Directory B",
                "UZS",
                "Asia/Tashkent",
                CustomerIdentityMode.TENANT_SHARED));

        assertThat(service.listTenants(null, 50))
                .as("the directory holds every tenant this platform-admin session created")
                .extracting(TenantControlPlaneService.TenantSummaryView::id)
                .contains(first.id(), second.id());

        AuthenticatedActor tenantOwner = new AuthenticatedActor("tenant-owner", Set.of(), Map.of());
        TenantControlPlaneService asOwner = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> tenantOwner, denyAll(), false),
                tenantId -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> tenantOwner,
                noTransactions(),
                new RecordingOrganizationProvisioner(),
                scope -> 0);
        assertThatThrownBy(() -> asOwner.listTenants(null, 50))
                .as("the directory is a cross-tenant read; organization membership in one tenant "
                        + "must never substitute for platform scope")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void activatesADraftBrandAndLocationAndIsIdempotent() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        TenantControlPlaneService service = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                tenantId -> {},
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin,
                noTransactions(),
                new RecordingOrganizationProvisioner(),
                scope -> 0);

        var tenant = service.createTenant(new CreateTenantCommand(
                "horecaos-2", "HorecaOS LLC", "HorecaOS", "UZS", "Asia/Tashkent", CustomerIdentityMode.TENANT_SHARED));
        var brand = service.createBrand(
                new TenantId(tenant.id()), new CreateBrandCommand("BRAND_A", "brand-a2", "Brand A"));
        var location = service.createLocation(
                new TenantId(tenant.id()),
                new BrandId(brand.id()),
                new CreateLocationCommand("LOC_A", "loc-a2", "Location A", "Asia/Tashkent"));

        assertThat(brand.status())
                .as("BRANDS_AND_LOCATIONS_VALIDATE only checks existence, so nothing else in "
                        + "onboarding ever moves a brand out of DRAFT")
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.DRAFT);

        var activatedBrand = service.activateBrand(new TenantId(tenant.id()), new BrandId(brand.id()));
        assertThat(activatedBrand.status()).isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.ACTIVE);

        var activatedLocation = service.activateLocation(
                new TenantId(tenant.id()), new BrandId(brand.id()), new LocationId(location.id()));
        assertThat(activatedLocation.status())
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.ACTIVE);

        // Idempotent: a reconciling caller must be able to activate an
        // already-ACTIVE brand/location again without the domain's own
        // requireStatus(DRAFT, SUSPENDED) guard throwing.
        assertThat(service.activateBrand(new TenantId(tenant.id()), new BrandId(brand.id()))
                        .status())
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.ACTIVE);
        assertThat(service.activateLocation(
                                new TenantId(tenant.id()), new BrandId(brand.id()), new LocationId(location.id()))
                        .status())
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.ACTIVE);
    }

    // ------------------------------------------------ correcting and deleting units

    /** A platform administrator's view of one tenant, with every effect the service has kept. */
    private static final class Harness {
        final InMemoryStore store = new InMemoryStore();
        final List<Object> published = new ArrayList<>();
        final List<uz.horecaos.platform.audit.api.AuditFact> audited = new ArrayList<>();
        int grantsOnAnyScope;
        final TenantControlPlaneService service;
        final TenantId tenantId;

        Harness() {
            AuthenticatedActor admin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
            service = new TenantControlPlaneService(
                    store,
                    new TenantAccessPolicy(() -> admin, denyAll(), false),
                    evicted -> {},
                    Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC),
                    published::add,
                    audited::add,
                    () -> admin,
                    noTransactions(),
                    new RecordingOrganizationProvisioner(),
                    scope -> grantsOnAnyScope);
            tenantId = new TenantId(service.createTenant(new CreateTenantCommand(
                            "units", "Units LLC", "Units", "UZS", "Asia/Tashkent", CustomerIdentityMode.TENANT_SHARED))
                    .id());
        }

        BrandId brand(String code, String slug) {
            return new BrandId(service.createBrand(tenantId, new CreateBrandCommand(code, slug, code + " name"))
                    .id());
        }

        LocationId location(BrandId brandId, String code) {
            return new LocationId(service.createLocation(
                            tenantId,
                            brandId,
                            new CreateLocationCommand(
                                    code, code.toLowerCase(java.util.Locale.ROOT), code, "Asia/Tashkent"))
                    .id());
        }

        List<String> actions() {
            return audited.stream().map(fact -> fact.actionCode()).toList();
        }
    }

    @Test
    @DisplayName("a draft brand's whole identity can be corrected, and the correction is published and audited")
    void aDraftBrandIsCorrected() {
        Harness h = new Harness();
        BrandId brandId = h.brand("OSHXONA", "oshxona");

        var revised = h.service.reviseBrand(
                h.tenantId, brandId, 0, new ReviseBrandCommand("OSHXONA_1", "oshxona-1", "Oshxona No.1"));

        assertThat(revised.code()).isEqualTo("OSHXONA_1");
        assertThat(revised.slug()).isEqualTo("oshxona-1");
        assertThat(revised.displayName()).isEqualTo("Oshxona No.1");
        assertThat(h.published)
                .filteredOn(BrandRevised.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(BrandRevised.class, event -> {
                    assertThat(event.code()).isEqualTo("OSHXONA_1");
                    assertThat(event.displayName()).isEqualTo("Oshxona No.1");
                });
        assertThat(h.actions()).contains("brand.revised");

        // Staff 9.3a: a per-field diff, not the root-level {before, after} pair
        // of whole snapshots ChangeDocuments.diff replaced.
        var fact = h.audited.stream()
                .filter(f -> f.actionCode().equals("brand.revised"))
                .findFirst()
                .orElseThrow();
        assertThat(fact.changeDocument()).doesNotContainKeys("before", "after");
        @SuppressWarnings("unchecked")
        var codeChange = (java.util.Map<String, Object>) fact.changeDocument().get("code");
        assertThat(codeChange).containsEntry("before", "OSHXONA").containsEntry("after", "OSHXONA_1");
    }

    @Test
    @DisplayName("a live brand can be renamed but keeps the code and slug things are built on")
    void aLiveBrandKeepsItsCodeAndSlug() {
        Harness h = new Harness();
        BrandId brandId = h.brand("CAFE", "cafe");
        h.service.activateBrand(h.tenantId, brandId);

        assertThatThrownBy(() ->
                        h.service.reviseBrand(h.tenantId, brandId, 0, new ReviseBrandCommand("CAFE_2", "cafe", "Cafe")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
        assertThatThrownBy(() ->
                        h.service.reviseBrand(h.tenantId, brandId, 0, new ReviseBrandCommand("CAFE", "cafe-2", "Cafe")))
                .isInstanceOf(IllegalStateException.class);

        var renamed = h.service.reviseBrand(h.tenantId, brandId, 0, new ReviseBrandCommand("cafe", "cafe", "Cafe Bar"));
        assertThat(renamed.displayName()).isEqualTo("Cafe Bar");
        assertThat(renamed.code())
                .as("the code arrives in any case and is compared after normalising, so an "
                        + "unchanged code typed in lower case is not a change")
                .isEqualTo("CAFE");
    }

    @Test
    @DisplayName("a correction from an out-of-date form is refused with both versions, and changes nothing")
    void aStaleCorrectionIsRefused() {
        Harness h = new Harness();
        BrandId brandId = h.brand("KEBAB", "kebab");

        assertThatThrownBy(() -> h.service.reviseBrand(
                        h.tenantId, brandId, 3, new ReviseBrandCommand("KEBAB", "kebab", "Somebody else's")))
                .isInstanceOfSatisfying(TenantResourceStaleException.class, stale -> {
                    assertThat(stale.expected()).isEqualTo(3);
                    assertThat(stale.actual()).isZero();
                });
        assertThat(h.store.findBrand(h.tenantId, brandId).orElseThrow().displayName())
                .isEqualTo("KEBAB name");
        assertThat(h.published).noneMatch(BrandRevised.class::isInstance);
    }

    @Test
    @DisplayName("a code another brand of the tenant holds is refused")
    void aTakenCodeIsRefused() {
        Harness h = new Harness();
        h.brand("PIZZA", "pizza");
        BrandId other = h.brand("BURGER", "burger");

        assertThatThrownBy(() -> h.service.reviseBrand(
                        h.tenantId, other, 0, new ReviseBrandCommand("PIZZA", "burger", "Burger")))
                .isInstanceOf(TenantResourceConflictException.class);
    }

    /**
     * Each refusal in the order an operator meets them, and each with the
     * reason the console turns into what to do next. Nothing is deleted until
     * every one of them is out of the way.
     */
    @Test
    @DisplayName("a brand is deleted only once it owns no locations and nobody holds access to it")
    void deletingABrandWaitsForItsLocationsAndItsGrants() {
        Harness h = new Harness();
        BrandId brandId = h.brand("TYPO", "typo");
        LocationId locationId = h.location(brandId, "CHILONZOR");

        assertThatThrownBy(() -> h.service.deleteBrand(h.tenantId, brandId, 0))
                .isInstanceOfSatisfying(
                        OperatingUnitNotDeletableException.class,
                        refused -> assertThat(refused.reason())
                                .isEqualTo(OperatingUnitNotDeletableException.Reason.HAS_LOCATIONS));

        h.service.deleteLocation(h.tenantId, brandId, locationId, 0);
        assertThat(h.published).anyMatch(LocationDeleted.class::isInstance);

        h.grantsOnAnyScope = 2;
        assertThatThrownBy(() -> h.service.deleteBrand(h.tenantId, brandId, 0))
                .isInstanceOfSatisfying(OperatingUnitNotDeletableException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(OperatingUnitNotDeletableException.Reason.HAS_ACCESS_GRANTS);
                    assertThat(refused.getMessage()).contains("2 staff grant(s)");
                });
        assertThat(h.store.findBrand(h.tenantId, brandId)).isPresent();

        h.grantsOnAnyScope = 0;
        h.service.deleteBrand(h.tenantId, brandId, 0);

        assertThat(h.store.findBrand(h.tenantId, brandId)).isEmpty();
        assertThat(h.published)
                .filteredOn(BrandDeleted.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(
                        BrandDeleted.class, event -> assertThat(event.code()).isEqualTo("TYPO"));
        assertThat(h.actions()).contains("location.deleted", "brand.deleted");
    }

    @Test
    @DisplayName("a brand or location that has been live is never deleted")
    void aLiveUnitIsNeverDeleted() {
        Harness h = new Harness();
        BrandId brandId = h.brand("LIVE", "live");
        LocationId locationId = h.location(brandId, "YUNUSOBOD");
        h.service.activateLocation(h.tenantId, brandId, locationId);
        h.service.activateBrand(h.tenantId, brandId);

        assertThatThrownBy(() -> h.service.deleteLocation(h.tenantId, brandId, locationId, 0))
                .isInstanceOfSatisfying(
                        OperatingUnitNotDeletableException.class,
                        refused -> assertThat(refused.reason())
                                .isEqualTo(OperatingUnitNotDeletableException.Reason.NOT_DRAFT));
        assertThatThrownBy(() -> h.service.deleteBrand(h.tenantId, brandId, 0))
                .isInstanceOfSatisfying(
                        OperatingUnitNotDeletableException.class,
                        refused -> assertThat(refused.reason())
                                .isEqualTo(OperatingUnitNotDeletableException.Reason.NOT_DRAFT));
        assertThat(h.published).noneMatch(event -> event instanceof BrandDeleted || event instanceof LocationDeleted);
    }

    @Test
    @DisplayName("a draft location's timezone can be corrected; a live one's cannot")
    void aLocationsTimezoneIsFixedOnceItIsLive() {
        Harness h = new Harness();
        BrandId brandId = h.brand("ZONE", "zone");
        LocationId locationId = h.location(brandId, "SAMARQAND");

        var moved = h.service.reviseLocation(
                h.tenantId,
                brandId,
                locationId,
                0,
                new ReviseLocationCommand("SAMARQAND", "samarqand", "Samarqand", "Asia/Samarkand"));
        assertThat(moved.timezone()).isEqualTo("Asia/Samarkand");
        assertThat(h.published)
                .filteredOn(LocationRevised.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(
                        LocationRevised.class,
                        event -> assertThat(event.timezone()).isEqualTo("Asia/Samarkand"));

        h.service.activateLocation(h.tenantId, brandId, locationId);
        assertThatThrownBy(() -> h.service.reviseLocation(
                        h.tenantId,
                        brandId,
                        locationId,
                        0,
                        new ReviseLocationCommand("SAMARQAND", "samarqand", "Samarqand", "Asia/Tashkent")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timezone");
    }

    /**
     * {@code ZoneId.of} throws {@code DateTimeException}, which no handler
     * maps, so a mistyped zone came back from location creation as a 500. An
     * {@code IllegalArgumentException} is what this module's handler turns into
     * a 400 the console can explain.
     */
    @Test
    @DisplayName("a mistyped timezone is the caller's mistake, not a server error")
    void aMistypedTimezoneIsRefusedAsInvalid() {
        Harness h = new Harness();
        BrandId brandId = h.brand("TZ", "tz");

        assertThatThrownBy(() -> h.service.createLocation(
                        h.tenantId, brandId, new CreateLocationCommand("TYPO", "typo", "Typo", "Asia/Tashkentt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Asia/Tashkentt");

        LocationId locationId = h.location(brandId, "OK");
        assertThatThrownBy(() -> h.service.reviseLocation(
                        h.tenantId,
                        brandId,
                        locationId,
                        0,
                        new ReviseLocationCommand("OK", "ok", "Ok", "Mars/Olympus")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Moves a freshly created tenant from {@code PROVISIONING} to
     * {@code ACTIVE}, which is what makes it suspendable.
     *
     * <p>Production does this in {@code OnboardingService}, at the end of a run,
     * with a direct {@code UPDATE}. This goes through the domain transition and
     * the store method instead — the same state change by the supported route —
     * so that a test about suspension does not depend on the whole onboarding
     * workflow to reach the only status a tenant can be suspended from.
     */
    private static void takeLive(InMemoryStore store, TenantId tenantId) {
        Tenant tenant = store.findTenant(tenantId).orElseThrow();
        tenant.activate();
        store.updateTenantStatus(tenant);
    }

    private static final class InMemoryStore implements TenantControlPlaneStore {

        private final Map<TenantId, Tenant> tenants = new LinkedHashMap<>();
        private final Map<TenantId, CustomerIdentityMode> identityModes = new LinkedHashMap<>();
        private final Map<BrandId, Brand> brands = new LinkedHashMap<>();
        private final List<Location> locations = new ArrayList<>();

        @Override
        public boolean tenantSlugExists(Slug slug) {
            return tenants.values().stream().anyMatch(tenant -> tenant.slug().equals(slug));
        }

        @Override
        public void insertTenant(Tenant tenant) {
            tenants.put(tenant.id(), tenant);
        }

        @Override
        public Optional<Tenant> findTenant(TenantId tenantId) {
            return Optional.ofNullable(tenants.get(tenantId));
        }

        @Override
        public void updateTenantStatus(Tenant tenant) {
            // The aggregate is the same instance the service transitioned, so
            // there is nothing to copy -- but it must be present, because a
            // status write for a tenant this store never saw is the bug the
            // JDBC implementation's row-count guard exists to catch.
            if (!tenants.containsKey(tenant.id())) {
                throw new IllegalStateException("No such tenant: " + tenant.id());
            }
            tenants.put(tenant.id(), tenant);
        }

        @Override
        public Optional<Tenant> findTenantBySlug(Slug slug) {
            return tenants.values().stream()
                    .filter(tenant -> tenant.slug().equals(slug))
                    .findFirst();
        }

        @Override
        public List<TenantControlPlaneStore.TenantSummary> listTenants(
                @org.jspecify.annotations.Nullable TenantId afterTenantId, int limit) {
            return tenants.values().stream()
                    .sorted(java.util.Comparator.comparing(tenant -> tenant.id().value()))
                    .filter(tenant ->
                            afterTenantId == null || tenant.id().value().compareTo(afterTenantId.value()) > 0)
                    .limit(limit)
                    .map(tenant -> new TenantControlPlaneStore.TenantSummary(
                            tenant.id(),
                            tenant.slug(),
                            tenant.legalName(),
                            tenant.displayName(),
                            tenant.defaultCurrency().getCurrencyCode(),
                            tenant.defaultTimezone().getId(),
                            tenant.status(),
                            Instant.EPOCH,
                            "UZ",
                            "RESTAURANT"))
                    .toList();
        }

        @Override
        public void linkKeycloakOrganization(Tenant tenant) {
            tenants.put(tenant.id(), tenant);
        }

        @Override
        public void insertCustomerIdentityPolicy(CustomerIdentityPolicy policy) {
            identityModes.put(policy.tenantId(), policy.mode());
        }

        @Override
        public Optional<CustomerIdentityMode> findCurrentCustomerIdentityMode(TenantId tenantId, Instant at) {
            return Optional.ofNullable(identityModes.get(tenantId));
        }

        @Override
        public boolean brandCodeOrSlugExists(TenantId tenantId, String code, Slug slug) {
            return brands.values().stream()
                    .anyMatch(brand -> brand.tenantId().equals(tenantId)
                            && (brand.code().equals(code) || brand.slug().equals(slug)));
        }

        @Override
        public void insertBrand(Brand brand) {
            brands.put(brand.id(), brand);
        }

        @Override
        public Optional<Brand> findBrand(TenantId tenantId, BrandId brandId) {
            return Optional.ofNullable(brands.get(brandId))
                    .filter(brand -> brand.tenantId().equals(tenantId));
        }

        @Override
        public List<Brand> findBrands(TenantId tenantId) {
            return brands.values().stream()
                    .filter(brand -> brand.tenantId().equals(tenantId))
                    .toList();
        }

        /**
         * Same reasoning as {@link #updateLocationPlace}: the aggregate held here
         * is the instance the service just mutated, so the write is already
         * visible.
         */
        @Override
        public void updateBrandStatus(Brand brand) {}

        @Override
        public boolean brandCodeOrSlugTakenByAnother(Brand brand) {
            return brands.values().stream()
                    .anyMatch(other -> other.tenantId().equals(brand.tenantId())
                            && !other.id().equals(brand.id())
                            && (other.code().equals(brand.code())
                                    || other.slug().equals(brand.slug())));
        }

        /** The aggregate is held by reference, so the revision is already in place; versions are the JDBC store's. */
        @Override
        public boolean updateBrandIdentity(Brand brand) {
            return brands.containsKey(brand.id());
        }

        @Override
        public boolean deleteBrand(Brand brand) {
            return brands.remove(brand.id()) != null;
        }

        @Override
        public boolean locationCodeOrSlugExists(Brand brand, String code, Slug slug) {
            return locations.stream()
                    .anyMatch(location -> location.brandId().equals(brand.id())
                            && (location.code().equals(code) || location.slug().equals(slug)));
        }

        @Override
        public void insertLocation(Location location) {
            locations.add(location);
        }

        /**
         * The aggregate held here is the same instance the service mutated, so the
         * write is already visible. Kept as an explicit no-op rather than left to
         * the reader, since a silently empty override in a fake is how a store test
         * comes to pass without a store.
         */
        @Override
        public void updateLocationPlace(Location location) {}

        /** Same reasoning as {@link #updateLocationPlace}. */
        @Override
        public void updateLocationStatus(Location location) {}

        @Override
        public boolean locationCodeOrSlugTakenByAnother(Location location) {
            return locations.stream()
                    .anyMatch(other -> other.brandId().equals(location.brandId())
                            && !other.id().equals(location.id())
                            && (other.code().equals(location.code())
                                    || other.slug().equals(location.slug())));
        }

        @Override
        public boolean updateLocationIdentity(Location location) {
            return locations.contains(location);
        }

        @Override
        public boolean deleteLocation(Location location) {
            return locations.remove(location);
        }

        @Override
        public List<Location> findLocations(Brand brand) {
            return locations.stream()
                    .filter(location -> location.tenantId().equals(brand.tenantId()))
                    .filter(location -> location.brandId().equals(brand.id()))
                    .toList();
        }
    }

    /**
     * A resolver that grants nothing, so these tests exercise the ADR 0003 rule
     * that is actually in force rather than accidentally passing on capabilities.
     */
    private static uz.horecaos.platform.iam.api.AuthorizationService denyAll() {
        return new uz.horecaos.platform.iam.api.AuthorizationService() {
            @Override
            public boolean has(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                return false;
            }

            @Override
            public void require(
                    String subject,
                    uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                throw new AccessDeniedException(capability, scope);
            }

            @Override
            public uz.horecaos.platform.iam.api.CapabilityView viewFor(String subject, java.util.UUID tenantId) {
                return new uz.horecaos.platform.iam.api.CapabilityView(
                        subject, "", java.util.Set.of(), java.util.List.of(), 0);
            }
        };
    }

    /**
     * A {@link TransactionTemplate} with nothing behind it: every collaborator
     * here is {@link InMemoryStore}, which has no real transactionality to
     * demarcate, so this exists only to satisfy {@code changeTenantStatus}'s
     * dependency on the type without pulling a real database into what is
     * otherwise a fast, Docker-free unit test -- the same reason {@code
     * PaymentAttemptService} and {@code OnboardingService} are exercised
     * against a real one only in their Testcontainers-backed suites.
     */
    private static TransactionTemplate noTransactions() {
        return new TransactionTemplate(new NoTransactionManager());
    }

    private static final class NoTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }

    /** Records what it was asked to reconcile, and can be told to fail once. */
    private static final class RecordingOrganizationProvisioner implements OrganizationProvisioner {

        private final List<Map.Entry<String, Boolean>> calls = new ArrayList<>();
        private int attempts;
        private boolean failNextCall;

        @Override
        public OrganizationRef ensureOrganization(EnsureOrganization command) {
            throw new UnsupportedOperationException("Not exercised by these tests");
        }

        @Override
        public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
            throw new UnsupportedOperationException("Not exercised by these tests");
        }

        @Override
        public MembershipRef ensureMembership(EnsureMembership command) {
            throw new UnsupportedOperationException("Not exercised by these tests");
        }

        @Override
        public void setOrganizationEnabled(String organizationId, boolean enabled) {
            attempts++;
            if (failNextCall) {
                failNextCall = false;
                throw new IllegalStateException("Keycloak is unreachable");
            }
            calls.add(Map.entry(organizationId, enabled));
        }
    }
}
