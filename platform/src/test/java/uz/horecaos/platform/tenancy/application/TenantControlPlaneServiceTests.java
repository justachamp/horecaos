package uz.horecaos.platform.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

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
        AuthenticatedActor platformAdmin = new AuthenticatedActor(
            "platform-user", Set.of("platform-admin"), Map.of());
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
        service.linkKeycloakOrganization(
            new TenantId(tenant.id()), "keycloak-organization-food-group");

        var firstBrand = service.createBrand(
            new TenantId(tenant.id()), new CreateBrandCommand("BRAND_A", "brand-a", "Brand A"));
        var secondBrand = service.createBrand(
            new TenantId(tenant.id()), new CreateBrandCommand("BRAND_B", "brand-b", "Brand B"));
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
        assertThat(store.identityModes.get(new TenantId(tenant.id())))
            .isEqualTo(CustomerIdentityMode.TENANT_SHARED);
        assertThat(auditFacts)
            .as("ADR 0027: every control-plane creation records who caused it and why")
            .extracting(uz.horecaos.platform.audit.api.AuditFact::actionCode)
            .containsExactly(
                "tenant.created",
                "tenant.keycloak_organization_linked",
                "brand.created",
                "brand.created",
                "location.created");
        assertThat(auditFacts)
            .allSatisfy(fact -> assertThat(fact.reason()).isNotBlank());

        assertThat(events)
            .extracting(Object::getClass)
            .containsExactly(TenantCreated.class, BrandCreated.class, BrandCreated.class, LocationCreated.class);
        assertThat(events.getFirst())
            .isInstanceOfSatisfying(TenantCreated.class, event -> {
                assertThat(event.tenantId().value()).isEqualTo(tenant.id());
                assertThat(event.eventVersion()).isEqualTo(1);
                assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-19T00:00:00Z"));
            });
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
        public void linkKeycloakOrganization(Tenant tenant) {
            tenants.put(tenant.id(), tenant);
        }

        @Override
        public void insertCustomerIdentityPolicy(CustomerIdentityPolicy policy) {
            identityModes.put(policy.tenantId(), policy.mode());
        }

        @Override
        public Optional<CustomerIdentityMode> findCurrentCustomerIdentityMode(
                TenantId tenantId, Instant at) {
            return Optional.ofNullable(identityModes.get(tenantId));
        }

        @Override
        public boolean brandCodeOrSlugExists(TenantId tenantId, String code, Slug slug) {
            return brands.values().stream().anyMatch(brand -> brand.tenantId().equals(tenantId)
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

        @Override
        public boolean locationCodeOrSlugExists(Brand brand, String code, Slug slug) {
            return locations.stream().anyMatch(location -> location.brandId().equals(brand.id())
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
        public void updateLocationPlace(Location location) {
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
            public boolean has(String subject, uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                return false;
            }

            @Override
            public void require(String subject, uz.horecaos.platform.iam.api.Capability capability,
                    uz.horecaos.platform.iam.api.ResourceScope scope) {
                throw new AccessDeniedException(capability, scope);
            }

            @Override
            public uz.horecaos.platform.iam.api.CapabilityView viewFor(String subject, java.util.UUID tenantId) {
                return new uz.horecaos.platform.iam.api.CapabilityView(
                        subject, null, java.util.Set.of(), java.util.List.of(), 0);
            }
        };
    }
}
