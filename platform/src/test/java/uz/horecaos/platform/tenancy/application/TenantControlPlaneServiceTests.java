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
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.tenancy.api.BrandCreated;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.LocationCreated;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantCreated;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.CreateBrandCommand;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.CreateLocationCommand;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.CreateTenantCommand;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityMode;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityPolicy;
import uz.horecaos.platform.tenancy.domain.Location;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;

class TenantControlPlaneServiceTests {

    @Test
    void createsATenantWithMultipleBrandsAndSingleBrandLocations() {
        InMemoryStore store = new InMemoryStore();
        AuthenticatedActor platformAdmin = new AuthenticatedActor("platform-user", Set.of("platform-admin"), Map.of());
        List<Object> events = new ArrayList<>();
        List<uz.horecaos.platform.audit.api.AuditFact> auditFacts = new ArrayList<>();
        TenantControlPlaneService service = new TenantControlPlaneService(
                store,
                new TenantAccessPolicy(() -> platformAdmin, denyAll(), false),
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                events::add,
                auditFacts::add,
                () -> platformAdmin);

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
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin);

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
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin);

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
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> tenantOwner);
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
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                event -> {},
                fact -> {},
                () -> platformAdmin);

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
                            Instant.EPOCH))
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
}
