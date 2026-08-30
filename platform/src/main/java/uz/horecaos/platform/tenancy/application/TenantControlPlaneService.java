package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandCreated;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.LocationCreated;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantCreated;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.CoordinateSource;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityMode;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityPolicy;
import uz.horecaos.platform.tenancy.domain.Location;
import uz.horecaos.platform.tenancy.domain.LocationPlace;
import uz.horecaos.platform.tenancy.domain.OperatingUnitStatus;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;
import uz.horecaos.platform.tenancy.domain.TenantStatus;

@Service
public class TenantControlPlaneService {

    private final TenantControlPlaneStore store;
    private final TenantAccessPolicy accessPolicy;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;

    TenantControlPlaneService(
            TenantControlPlaneStore store,
            TenantAccessPolicy accessPolicy,
            Clock clock,
            ApplicationEventPublisher events,
            AuditRecorder audit,
            CurrentActor currentActor) {
        this.store = store;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.currentActor = currentActor;
    }

    /**
     * Records an ADR 0027 audit fact in the same transaction as the change.
     *
     * <p>An outbox event tells other modules what happened; an audit fact records
     * who caused it and why. They are not substitutes: the event carries no actor
     * and is subject to topic retention.
     */
    private void recordAudit(
            String actionCode, ResourceScope scope, String targetType, UUID targetId,
            String reason, Map<String, Object> changes) {

        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(scope)
                .target(targetType, targetId)
                .because(reason)
                .changed(changes)
                .correlatedBy(correlationId())
                .occurredAt(clock.instant())
                .build());
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
    }

    @Transactional
    public TenantView createTenant(CreateTenantCommand command) {
        Objects.requireNonNull(command, "Create tenant command is required");
        accessPolicy.requirePlatformAdministrator();

        Slug slug = new Slug(command.slug());
        if (store.tenantSlugExists(slug)) {
            throw new TenantResourceConflictException("Tenant slug is already in use");
        }

        Tenant tenant = Tenant.provision(
                new TenantId(UUID.randomUUID()),
                slug,
                command.legalName(),
                command.displayName(),
                Currency.getInstance(command.defaultCurrency().strip().toUpperCase(Locale.ROOT)),
                ZoneId.of(command.defaultTimezone()));
        var occurredAt = clock.instant();
        CustomerIdentityPolicy identityPolicy = CustomerIdentityPolicy.initial(
                UUID.randomUUID(), tenant.id(), command.customerIdentityMode(), occurredAt);

        store.insertTenant(tenant);
        store.insertCustomerIdentityPolicy(identityPolicy);
        events.publishEvent(new TenantCreated(
                UUID.randomUUID(),
                tenant.id(),
                occurredAt,
                tenant.slug().value(),
                tenant.legalName(),
                tenant.displayName(),
                tenant.defaultCurrency().getCurrencyCode(),
                tenant.defaultTimezone().getId(),
                tenant.status().name(),
                identityPolicy.mode().name()));
        recordAudit(
                "tenant.created",
                ResourceScope.tenant(tenant.id().value()),
                "Tenant", tenant.id().value(),
                "Control-plane tenant creation",
                Map.of(
                        "slug", tenant.slug().value(),
                        "status", tenant.status().name(),
                        "customerIdentityMode", identityPolicy.mode().name()));
        return toView(tenant, identityPolicy.mode());
    }

    @Transactional(readOnly = true)
    public TenantView getTenant(TenantId tenantId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantRead(tenant);
        CustomerIdentityMode identityMode = store.findCurrentCustomerIdentityMode(tenantId, clock.instant())
                .orElseThrow(() -> new IllegalStateException("Tenant has no current customer identity policy"));
        return toView(tenant, identityMode);
    }

    @Transactional
    public TenantView linkKeycloakOrganization(TenantId tenantId, String organizationId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requirePlatformAdministrator();
        tenant.linkKeycloakOrganization(organizationId);
        store.linkKeycloakOrganization(tenant);
        recordAudit(
                "tenant.keycloak_organization_linked",
                ResourceScope.tenant(tenantId.value()),
                "Tenant", tenantId.value(),
                "Keycloak organization reconciliation",
                Map.of("keycloakOrganizationId", organizationId));
        CustomerIdentityMode identityMode = store.findCurrentCustomerIdentityMode(tenantId, clock.instant())
                .orElseThrow(() -> new IllegalStateException("Tenant has no current customer identity policy"));
        return toView(tenant, identityMode);
    }

    @Transactional
    public BrandView createBrand(TenantId tenantId, CreateBrandCommand command) {
        Objects.requireNonNull(command, "Create brand command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(tenant);

        Brand brand = Brand.draft(
                new BrandId(UUID.randomUUID()),
                tenantId,
                command.code(),
                new Slug(command.slug()),
                command.displayName());
        if (store.brandCodeOrSlugExists(tenantId, brand.code(), brand.slug())) {
            throw new TenantResourceConflictException("Brand code or slug is already in use for this tenant");
        }
        store.insertBrand(brand);
        events.publishEvent(new BrandCreated(
                UUID.randomUUID(),
                tenantId,
                brand.id(),
                clock.instant(),
                brand.code(),
                brand.slug().value(),
                brand.displayName(),
                brand.status().name()));
        recordAudit(
                "brand.created",
                ResourceScope.tenant(tenantId.value()),
                "Brand", brand.id().value(),
                "Control-plane brand creation",
                Map.of("code", brand.code(), "slug", brand.slug().value(), "status", brand.status().name()));
        return toView(brand);
    }

    @Transactional(readOnly = true)
    public List<BrandView> getBrands(TenantId tenantId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantRead(tenant);
        return store.findBrands(tenantId).stream().map(TenantControlPlaneService::toView).toList();
    }

    @Transactional
    public LocationView createLocation(
            TenantId tenantId,
            BrandId brandId,
            CreateLocationCommand command) {
        Objects.requireNonNull(command, "Create location command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(tenant);
        Brand brand = requireBrand(tenantId, brandId);

        Location location = Location.draft(
                new LocationId(UUID.randomUUID()),
                tenantId,
                brandId,
                command.code(),
                new Slug(command.slug()),
                command.displayName(),
                ZoneId.of(command.timezone()));
        if (store.locationCodeOrSlugExists(brand, location.code(), location.slug())) {
            throw new TenantResourceConflictException("Location code or slug is already in use for this brand");
        }
        store.insertLocation(location);
        events.publishEvent(new LocationCreated(
                UUID.randomUUID(),
                tenantId,
                brandId,
                location.id(),
                clock.instant(),
                location.code(),
                location.slug().value(),
                location.displayName(),
                location.timezone().getId(),
                location.status().name()));
        recordAudit(
                "location.created",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Location", location.id().value(),
                "Control-plane location creation",
                Map.of(
                        "code", location.code(),
                        "slug", location.slug().value(),
                        "timezone", location.timezone().getId(),
                        "status", location.status().name()));
        return toView(location);
    }

    /**
     * Records where a branch is: its address, its telephone and its point.
     *
     * <p>Separate from creation because it arrives separately. A branch is
     * registered from a spreadsheet during onboarding and visited later, and
     * demanding a coordinate up front would either block the registration or
     * collect a guess — and a guessed pin is worse than an absent one, because
     * nothing downstream can tell it apart from a surveyed one.
     *
     * <p>Requires tenant management rather than read. A pin decides which orders a
     * branch is offered under ADR 0037 and where couriers are sent, so moving one
     * is a commercial act, not an edit to a display name.
     */
    @Transactional
    public LocationView describeLocation(
            TenantId tenantId,
            BrandId brandId,
            LocationId locationId,
            DescribeLocationCommand command) {

        Objects.requireNonNull(command, "Describe location command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(tenant);
        Brand brand = requireBrand(tenantId, brandId);

        Location location = store.findLocations(brand).stream()
                .filter(candidate -> candidate.id().equals(locationId))
                .findFirst()
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "Location was not found in this brand"));

        LocationPlace place = command.toPlace();
        location.describePlace(place);
        store.updateLocationPlace(location);

        // The point is audited as a value, not as "the address changed". Where a
        // branch claims to be is the fact somebody will later need to reconstruct
        // — when a zone stops matching, or when a courier was sent to the wrong
        // building — and an audit entry that only says a field was edited cannot
        // answer either question.
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("coordinateSource", place.coordinateSource().name());
        audited.put("latitude", place.point().map(GeoPoint::latitude).orElse(null));
        audited.put("longitude", place.point().map(GeoPoint::longitude).orElse(null));
        audited.put("city", place.city());
        audited.put("district", place.district());
        recordAudit(
                "location.described",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Location", location.id().value(),
                "Control-plane location address and point",
                audited);

        return toView(location);
    }

    @Transactional(readOnly = true)
    public List<LocationView> getLocations(TenantId tenantId, BrandId brandId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantRead(tenant);
        Brand brand = requireBrand(tenantId, brandId);
        return store.findLocations(brand).stream().map(TenantControlPlaneService::toView).toList();
    }

    private Tenant requireTenant(TenantId tenantId) {
        return store.findTenant(Objects.requireNonNull(tenantId, "Tenant ID is required"))
                .orElseThrow(() -> new TenantResourceNotFoundException("Tenant was not found"));
    }

    private Brand requireBrand(TenantId tenantId, BrandId brandId) {
        return store.findBrand(tenantId, Objects.requireNonNull(brandId, "Brand ID is required"))
                .orElseThrow(() -> new TenantResourceNotFoundException("Brand was not found in this tenant"));
    }

    private static TenantView toView(Tenant tenant, CustomerIdentityMode identityMode) {
        return new TenantView(
                tenant.id().value(),
                tenant.slug().value(),
                tenant.legalName(),
                tenant.displayName(),
                tenant.defaultCurrency().getCurrencyCode(),
                tenant.defaultTimezone().getId(),
                tenant.keycloakOrganizationId().orElse(null),
                tenant.status(),
                identityMode);
    }

    private static BrandView toView(Brand brand) {
        return new BrandView(
                brand.id().value(),
                brand.tenantId().value(),
                brand.code(),
                brand.slug().value(),
                brand.displayName(),
                brand.status());
    }

    private static LocationView toView(Location location) {
        return new LocationView(
                location.id().value(),
                location.tenantId().value(),
                location.brandId().value(),
                location.code(),
                location.slug().value(),
                location.displayName(),
                location.timezone().getId(),
                location.status(),
                location.place().addressLine(),
                location.place().district(),
                location.place().city(),
                location.place().landmark(),
                location.place().contactPhone(),
                location.place().point().map(GeoPoint::latitude).orElse(null),
                location.place().point().map(GeoPoint::longitude).orElse(null),
                location.place().coordinateSource());
    }

    public record CreateTenantCommand(
            String slug,
            String legalName,
            String displayName,
            String defaultCurrency,
            String defaultTimezone,
            CustomerIdentityMode customerIdentityMode) {

        public CreateTenantCommand {
            Objects.requireNonNull(customerIdentityMode, "Customer identity mode is required");
        }
    }

    public record CreateBrandCommand(String code, String slug, String displayName) { }

    public record CreateLocationCommand(String code, String slug, String displayName, String timezone) { }

    public record TenantView(
            UUID id,
            String slug,
            String legalName,
            String displayName,
            String defaultCurrency,
            String defaultTimezone,
            String keycloakOrganizationId,
            TenantStatus status,
            CustomerIdentityMode customerIdentityMode) { }

    public record BrandView(
            UUID id,
            UUID tenantId,
            String code,
            String slug,
            String displayName,
            OperatingUnitStatus status) { }

    /**
     * Where a branch is, as a caller states it.
     *
     * @param coordinateSource who placed the pin. Supplied rather than inferred
     *                         because the platform genuinely cannot tell a surveyed
     *                         point from a guess, and the two have different lives:
     *                         a {@code NOT_GEOCODED} branch stays on the backfill's
     *                         work list, and a pinned one comes off it
     */
    public record DescribeLocationCommand(
            String addressLine,
            String district,
            String city,
            String landmark,
            String contactPhone,
            Double latitude,
            Double longitude,
            CoordinateSource coordinateSource) {

        public LocationPlace toPlace() {
            // Half a coordinate is refused outright rather than nulled through.
            // A latitude alone points at the equator, and V0021 had to go back and
            // discard rows that reached customer.addresses exactly this way.
            if ((latitude == null) != (longitude == null)) {
                throw new IllegalArgumentException(
                        "A location needs both a latitude and a longitude, or neither");
            }
            GeoPoint point = latitude == null ? null : new GeoPoint(latitude, longitude);
            CoordinateSource source = coordinateSource != null ? coordinateSource
                    : (point == null ? CoordinateSource.NOT_GEOCODED
                            : CoordinateSource.MERCHANT_PIN);
            return new LocationPlace(addressLine, district, city, landmark, contactPhone,
                    point, source);
        }
    }

    public record LocationView(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String code,
            String slug,
            String displayName,
            String timezone,
            OperatingUnitStatus status,
            String addressLine,
            String district,
            String city,
            String landmark,
            String contactPhone,
            Double latitude,
            Double longitude,
            CoordinateSource coordinateSource) { }
}
