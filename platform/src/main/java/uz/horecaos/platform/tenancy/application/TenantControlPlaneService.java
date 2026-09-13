package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.grants.ScopedGrantDirectory;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.tenancy.api.BrandCreated;
import uz.horecaos.platform.tenancy.api.BrandDeleted;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.BrandRevised;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.LocationCreated;
import uz.horecaos.platform.tenancy.api.LocationDeleted;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.LocationRevised;
import uz.horecaos.platform.tenancy.api.TenantCreated;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.application.port.TenantStatusCache;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.BrandProfile;
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

    private static final Logger log = LoggerFactory.getLogger(TenantControlPlaneService.class);

    private final TenantControlPlaneStore store;
    private final TenantAccessPolicy accessPolicy;
    private final TenantStatusCache suspensions;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final TransactionTemplate transactions;
    private final OrganizationProvisioner organizationProvisioner;
    private final ScopedGrantDirectory scopedGrants;

    // Public rather than package-private: uz.horecaos.platform.tenancy.application.onboarding
    // (a subpackage of this same module) needs to construct this in
    // OnboardingFullRunIntegrationTests exactly the way it already constructs
    // JdbcTenantControlPlaneStore, and OnboardingService itself takes it as a
    // collaborator (see that class's activateDraftBrandsAndLocations).
    //
    // transactions/organizationProvisioner exist for exactly one thing:
    // changeTenantStatus's Keycloak reconciliation. @Transactional cannot
    // express "commit this write, then call an external system outside the
    // transaction" from inside a single bean, because a method calling its own
    // annotated method skips the proxy entirely -- the same reason
    // PaymentAttemptService and OnboardingService already carry a
    // TransactionTemplate alongside their @Transactional methods rather than
    // trying to express everything through the annotation.
    public TenantControlPlaneService(
            TenantControlPlaneStore store,
            TenantAccessPolicy accessPolicy,
            TenantStatusCache suspensions,
            Clock clock,
            ApplicationEventPublisher events,
            AuditRecorder audit,
            CurrentActor currentActor,
            TransactionTemplate transactions,
            OrganizationProvisioner organizationProvisioner,
            ScopedGrantDirectory scopedGrants) {
        this.store = store;
        this.accessPolicy = accessPolicy;
        this.suspensions = suspensions;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.currentActor = currentActor;
        this.transactions = transactions;
        this.organizationProvisioner = organizationProvisioner;
        this.scopedGrants = scopedGrants;
    }

    /**
     * Records an ADR 0027 audit fact in the same transaction as the change.
     *
     * <p>An outbox event tells other modules what happened; an audit fact records
     * who caused it and why. They are not substitutes: the event carries no actor
     * and is subject to topic retention.
     */
    private void recordAudit(
            String actionCode,
            ResourceScope scope,
            String targetType,
            UUID targetId,
            String reason,
            Map<String, Object> changes) {

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
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
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
                "Tenant",
                tenant.id().value(),
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

    /**
     * A page of the control-plane tenant directory (IA 2.1), oldest first.
     *
     * <p>Platform-admin only, the same bar {@link #findTenantBySlug} holds: a
     * directory across every tenant is exactly the cross-tenant read ADR 0025's
     * capability model reserves to platform scope, never to organization
     * membership in one tenant.
     */
    @Transactional(readOnly = true)
    public List<TenantSummaryView> listTenants(@Nullable TenantId afterTenantId, int limit) {
        accessPolicy.requirePlatformAdministrator();
        return store.listTenants(afterTenantId, limit).stream()
                .map(TenantControlPlaneService::toView)
                .toList();
    }

    private static TenantSummaryView toView(TenantControlPlaneStore.TenantSummary row) {
        return new TenantSummaryView(
                row.id().value(),
                row.slug().value(),
                row.legalName(),
                row.displayName(),
                row.defaultCurrency(),
                row.defaultTimezone(),
                row.status(),
                row.createdAt(),
                row.countryCode(),
                row.businessType());
    }

    /**
     * Finds a tenant by its slug, platform-admin only.
     *
     * <p>Exists for idempotent provisioning tooling: a script driving a fixed,
     * known slug (never a fresh random one) has to be able to discover whether a
     * previous run already created it, and reconcile against that tenant's real
     * id, rather than either guessing an id or attempting a second {@code
     * createTenant} that only ever answers "slug is already in use" with nothing
     * to recover from it. Platform-admin only, unlike {@link #getTenant}, because
     * there is no tenant-scoped path variable here to check organization
     * membership against — a caller who does not yet know the id cannot prove
     * membership by it.
     */
    @Transactional(readOnly = true)
    public Optional<TenantView> findTenantBySlug(String slug) {
        accessPolicy.requirePlatformAdministrator();
        return store.findTenantBySlug(new Slug(slug)).map(tenant -> {
            CustomerIdentityMode identityMode = store.findCurrentCustomerIdentityMode(tenant.id(), clock.instant())
                    .orElseThrow(() -> new IllegalStateException("Tenant has no current customer identity policy"));
            return toView(tenant, identityMode);
        });
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
                "Tenant",
                tenantId.value(),
                "Keycloak organization reconciliation",
                Map.of("keycloakOrganizationId", organizationId));
        CustomerIdentityMode identityMode = store.findCurrentCustomerIdentityMode(tenantId, clock.instant())
                .orElseThrow(() -> new IllegalStateException("Tenant has no current customer identity policy"));
        return toView(tenant, identityMode);
    }

    /**
     * Stops a tenant trading, and makes that mean something.
     *
     * <p>Platform-admin only, and deliberately not a tenant capability: a tenant
     * cannot be trusted to lift its own suspension, and the reasons for
     * suspending (non-payment, abuse, a legal instruction) are the platform's
     * side of the relationship, not the restaurant's.
     *
     * <p>The status write is only half of it. Until wave 79 the other half did
     * not exist anywhere: {@code Tenant.suspend()} set a column that no request
     * path read, so a suspended tenant's staff kept signing in and kept every
     * capability they held. {@code JdbcAuthorizationService} now drops
     * tenant-scoped grants for a suspended tenant, and this evicts the cache
     * that answer is read from, so the refusal starts at the next request rather
     * than at the end of a TTL.
     *
     * <p>Also reconciles the tenant's Keycloak organization to disabled — see
     * {@link #changeTenantStatus} for why that is a second, best-effort call
     * rather than part of this write, and {@link
     * OrganizationProvisioner#setOrganizationEnabled} for why the flag it
     * flips is not what stops a suspended tenant's people: that is ADR 0078's
     * grant filter, proven against a live realm to keep working regardless of
     * this flag.
     */
    public TenantView suspendTenant(TenantId tenantId, String reason) {
        return changeTenantStatus(tenantId, reason, Tenant::suspend, "tenant.suspended", false);
    }

    /**
     * Lets a tenant trade again.
     *
     * <p>Reachable while the tenant is suspended because the caller is a
     * platform administrator, whose authority is the Keycloak realm role and a
     * platform-scoped grant — neither of which suspension touches. If
     * suspension could lock out the person who lifts it, it would be a one-way
     * door, and this is the test that says it is not.
     *
     * <p>Reconciles the tenant's Keycloak organization back to enabled; see
     * {@link #suspendTenant} for what that flag does and does not do.
     */
    public TenantView reactivateTenant(TenantId tenantId, String reason) {
        return changeTenantStatus(
                tenantId,
                reason,
                tenant -> {
                    // Tenant.activate() also accepts PROVISIONING, because it is
                    // the same transition onboarding uses to take a tenant live.
                    // Reactivation is not that: lifting a suspension on a tenant
                    // that never finished onboarding would put it into service
                    // without the steps onboarding exists to run.
                    if (tenant.status() != TenantStatus.SUSPENDED) {
                        throw new IllegalStateException(
                                "Only a suspended tenant can be reactivated; this one is " + tenant.status());
                    }
                    tenant.activate();
                },
                "tenant.reactivated",
                true);
    }

    /**
     * Changes status in one committed transaction, then reconciles the
     * tenant's Keycloak organization outside it.
     *
     * <p>Two steps, deliberately never one. {@code
     * OrganizationProvisioner.setOrganizationEnabled} is a blocking HTTPS round
     * trip to Keycloak, and {@code ExternalCallTransactionBoundaryTests} exists
     * precisely because a transaction spanning a call like that holds one of
     * the platform's ten pooled connections for as long as Keycloak takes to
     * answer — every other module sharing that pool stalls with it. Not
     * {@code @Transactional} for the same reason {@code
     * uz.horecaos.platform.payments.application.PaymentAttemptService} and
     * {@link uz.horecaos.platform.tenancy.application.onboarding.OnboardingService}
     * are not on their own external-call paths: a method calling its own
     * annotated method never goes through the proxy, so the only way to commit
     * before calling out is to demarcate the transaction explicitly.
     *
     * <p>The tenant status row is the source of truth for suspension —
     * {@code JdbcAuthorizationService} and ADR 0078's grant filter read
     * {@code tenant.tenants.status}, never Keycloak's {@code enabled} flag — so
     * committing it here never waits on, and is never undone by, the Keycloak
     * call that follows. If that call fails, the tenant is suspended (or
     * reactivated) in every way that actually matters; an operator reading the
     * Keycloak console directly would see a stale flag until it is corrected.
     * That correction is deliberately not retried inline: {@link
     * uz.horecaos.platform.tenancy.application.identity.IdentityDriftReporter}
     * already runs a scheduled comparison of tenant status against the
     * organization's {@code enabled} flag in both directions, so the mismatch
     * this leaves behind is caught and audited on its next pass rather than
     * escalated from here — see that class's {@code
     * ORGANIZATION_ENABLED_WHILE_SUSPENDED} and {@code ORGANIZATION_DISABLED}
     * findings.
     */
    private TenantView changeTenantStatus(
            TenantId tenantId,
            String reason,
            java.util.function.Consumer<Tenant> transition,
            String actionCode,
            boolean keycloakEnabled) {

        StatusChange change = transactions.execute(ignored -> {
            Tenant tenant = requireTenant(tenantId);
            accessPolicy.requirePlatformAdministrator();
            TenantStatus before = tenant.status();
            transition.accept(tenant);
            store.updateTenantStatus(tenant);
            suspensions.evict(tenantId.value());
            recordAudit(
                    actionCode,
                    ResourceScope.tenant(tenantId.value()),
                    "Tenant",
                    tenantId.value(),
                    reason,
                    Map.of("from", before.name(), "to", tenant.status().name()));
            CustomerIdentityMode identityMode = store.findCurrentCustomerIdentityMode(tenantId, clock.instant())
                    .orElseThrow(() -> new IllegalStateException("Tenant has no current customer identity policy"));
            return new StatusChange(
                    toView(tenant, identityMode),
                    tenant.keycloakOrganizationId().orElse(null));
        });

        reconcileKeycloakOrganization(Objects.requireNonNull(change).organizationId(), keycloakEnabled, tenantId);
        return change.view();
    }

    /**
     * Best-effort by design; see {@link #changeTenantStatus} for why a failure
     * here must never undo, retry inline against, or fail the request for a
     * status change that already committed.
     */
    private void reconcileKeycloakOrganization(@Nullable String organizationId, boolean enabled, TenantId tenantId) {
        if (organizationId == null) {
            // Not linked yet -- IdentityDriftReporter's ORGANIZATION_UNLINKED
            // already names that gap for an ACTIVE tenant. Nothing to reconcile
            // here until a link exists.
            return;
        }
        try {
            organizationProvisioner.setOrganizationEnabled(organizationId, enabled);
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not reconcile Keycloak organization {} to enabled={} for tenant {} after its status "
                            + "committed; IdentityDriftReporter will report the mismatch on its next scheduled pass",
                    organizationId,
                    enabled,
                    tenantId.value(),
                    failure);
        }
    }

    /** What {@link #changeTenantStatus}'s committed transaction hands to its post-commit Keycloak call. */
    private record StatusChange(TenantView view, @Nullable String organizationId) {}

    @Transactional
    public BrandView createBrand(TenantId tenantId, CreateBrandCommand command) {
        Objects.requireNonNull(command, "Create brand command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(tenant, Capability.BRAND_WRITE, ResourceScope.tenant(tenantId.value()));

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
                "Brand",
                brand.id().value(),
                "Control-plane brand creation",
                Map.of(
                        "code",
                        brand.code(),
                        "slug",
                        brand.slug().value(),
                        "status",
                        brand.status().name()));
        return toView(brand, BrandProfile.empty());
    }

    /**
     * Activates a brand created {@link Brand#draft brand}, idempotently.
     *
     * <p>Nothing in this codebase moved a brand out of {@code DRAFT} before
     * this existed — creation always leaves one there, and DRAFT is exactly
     * what {@code JdbcStorefrontPickupLocationStore.nearestTo} and any future
     * ADR 0025-gated read that checks {@code status = 'ACTIVE'} were already
     * written to require. A brand's own onboarding readiness
     * ({@code BRANDS_AND_LOCATIONS_VALIDATE}) checks only that a brand and a
     * location exist, not their status, so a tenant could reach {@code ACTIVE}
     * with a brand no customer-facing discovery query would ever surface.
     * Idempotent (a no-op when already {@code ACTIVE}) because provisioning
     * tooling that reconciles an existing brand must not fail calling this a
     * second time.
     */
    @Transactional
    public BrandView activateBrand(TenantId tenantId, BrandId brandId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(tenant, Capability.BRAND_WRITE, ResourceScope.tenant(tenantId.value()));
        Brand brand = requireBrand(tenantId, brandId);
        if (brand.status() == OperatingUnitStatus.ACTIVE) {
            return toView(brand, store.findBrandProfile(tenantId, brandId));
        }
        brand.activate();
        store.updateBrandStatus(brand);
        recordAudit(
                "brand.activated",
                ResourceScope.tenant(tenantId.value()),
                "Brand",
                brandId.value(),
                "Control-plane brand activation",
                Map.of("status", brand.status().name()));
        // Re-read: the status write moved the stored version on, and a view
        // carrying the old one would make the next correction fail as stale.
        return toView(requireBrand(tenantId, brandId), store.findBrandProfile(tenantId, brandId));
    }

    /**
     * Corrects a brand's name, and while it is a draft its code and slug.
     *
     * <p>Brand scope, like activation: correcting a brand is work on that
     * brand. The expected version is the caller's {@code If-Match} (ADR 0031),
     * so two people fixing the same brand at once cannot silently overwrite
     * each other.
     */
    @Transactional
    public BrandView reviseBrand(TenantId tenantId, BrandId brandId, long expectedVersion, ReviseBrandCommand command) {
        Objects.requireNonNull(command, "Revise brand command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.BRAND_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);
        requireVersion(expectedVersion, brand.version());

        Map<String, Object> before = identityOf(brand.code(), brand.slug(), brand.displayName(), null);
        brand.revise(command.code(), new Slug(command.slug()), command.displayName());
        if (store.brandCodeOrSlugTakenByAnother(brand)) {
            throw new TenantResourceConflictException("Brand code or slug is already in use for this tenant");
        }
        if (!store.updateBrandIdentity(brand)) {
            throw staleBrand(tenantId, brandId, expectedVersion);
        }
        events.publishEvent(new BrandRevised(
                UUID.randomUUID(),
                tenantId,
                brandId,
                clock.instant(),
                brand.code(),
                brand.slug().value(),
                brand.displayName(),
                brand.status().name()));
        recordAudit(
                "brand.revised",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Brand",
                brandId.value(),
                "Control-plane brand correction",
                // Staff 9.3a: a per-field diff, not a root-level {before, after}
                // pair of whole snapshots — see ChangeDocuments.diff's own doc.
                ChangeDocuments.diff(before, identityOf(brand.code(), brand.slug(), brand.displayName(), null)));
        return toView(requireBrand(tenantId, brandId), store.findBrandProfile(tenantId, brandId));
    }

    /**
     * Corrects a brand's customer-facing profile: contact phone, Telegram
     * handle, logo, banner, and which languages its storefront supports
     * (10.1, 10.12).
     *
     * <p>Brand scope, like {@link #reviseBrand}, which this deliberately stays
     * separate from: renaming is a correction to the brand's identity, made
     * rarely and under an {@code If-Match}; this is storefront content, edited
     * as often as a menu description and carrying no optimistic-lock version
     * of its own — the same split {@link #describeLocation} already draws
     * between a location's identity and its place.
     */
    @Transactional
    public BrandView updateBrandProfile(TenantId tenantId, BrandId brandId, UpdateBrandProfileCommand command) {
        Objects.requireNonNull(command, "Update brand profile command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.BRAND_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);

        BrandProfile before = store.findBrandProfile(tenantId, brandId);
        BrandProfile profile = command.toProfile();
        store.updateBrandProfile(tenantId, brandId, profile);
        recordAudit(
                "brand.profile_revised",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Brand",
                brandId.value(),
                "Control-plane brand profile correction",
                // Never contactPhone/telegramHandle here -- the same PII
                // exclusion describeLocation's own audit map keeps for
                // contactPhone, ADR 0029. Locale codes, a default marker and
                // whether media is set are not personal data.
                ChangeDocuments.diff(profileAudit(before), profileAudit(profile)));
        return toView(brand, profile);
    }

    private static Map<String, Object> profileAudit(BrandProfile profile) {
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("logoSet", profile.logoAssetId() != null);
        audited.put("bannerSet", profile.bannerAssetId() != null);
        audited.put(
                "locales",
                profile.locales().stream()
                        .map(BrandProfile.BrandLocale::locale)
                        .sorted()
                        .toList());
        // Map.copyOf (AuditFact's own compact constructor) refuses a null value
        // outright, and an unconfigured or just-cleared profile genuinely has no
        // default locale -- so the key is omitted rather than recorded as null,
        // the same fix describeLocation's own audit map needed for the same
        // reason.
        profile.locales().stream()
                .filter(BrandProfile.BrandLocale::isDefault)
                .map(BrandProfile.BrandLocale::locale)
                .findFirst()
                .ifPresent(locale -> audited.put("defaultLocale", locale));
        return audited;
    }

    /**
     * Deletes a brand that never left {@code DRAFT} and that nothing refers to.
     *
     * <p>Brand scope, like correcting and activating it: the brand's own
     * manager may take back a draft made by mistake. Each refusal says what to
     * do instead —
     * delete its locations first, revoke the access scoped to it, or remove
     * whatever the database names as still referring to it — and nothing is
     * ever removed along with it; see {@link OperatingUnitNotDeletableException}.
     */
    @Transactional
    public void deleteBrand(TenantId tenantId, BrandId brandId, long expectedVersion) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.BRAND_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);
        requireVersion(expectedVersion, brand.version());

        if (!brand.deletable()) {
            throw new OperatingUnitNotDeletableException(
                    OperatingUnitNotDeletableException.Reason.NOT_DRAFT,
                    null,
                    "Only a DRAFT brand can be deleted, and this one is " + brand.status());
        }
        int locations = store.findLocations(brand).size();
        if (locations > 0) {
            throw new OperatingUnitNotDeletableException(
                    OperatingUnitNotDeletableException.Reason.HAS_LOCATIONS,
                    null,
                    "This brand still has %d location(s); delete them first".formatted(locations));
        }
        requireNoScopedGrants(ResourceScope.brand(tenantId.value(), brandId.value()), "brand");
        if (!store.deleteBrand(brand)) {
            throw staleBrand(tenantId, brandId, expectedVersion);
        }
        events.publishEvent(new BrandDeleted(UUID.randomUUID(), tenantId, brandId, clock.instant(), brand.code()));
        recordAudit(
                "brand.deleted",
                ResourceScope.tenant(tenantId.value()),
                "Brand",
                brandId.value(),
                "Control-plane deletion of a draft brand",
                identityOf(brand.code(), brand.slug(), brand.displayName(), null));
    }

    @Transactional(readOnly = true)
    public List<BrandView> getBrands(TenantId tenantId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantRead(tenant);
        List<Brand> brands = store.findBrands(tenantId);
        // One batched read of every brand's profile rather than one query per
        // row -- the same N+1 the branch list's own service-state read is
        // this wave's other half of fixing, for a directory that is small
        // today but should not need a query per brand to stay that way.
        Map<BrandId, BrandProfile> profiles = store.findBrandProfiles(tenantId);
        return brands.stream()
                .map(brand -> toView(brand, profiles.getOrDefault(brand.id(), BrandProfile.empty())))
                .toList();
    }

    /**
     * One brand, for the operations Settings 10.1 profile screen — reads what
     * {@link #getBrands} already reads, filtered to one, so a screen showing a
     * single brand's own profile does not have to fetch and discard the rest
     * of the tenant's brands.
     */
    @Transactional(readOnly = true)
    public BrandView getBrand(TenantId tenantId, BrandId brandId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantRead(tenant);
        Brand brand = requireBrand(tenantId, brandId);
        return toView(brand, store.findBrandProfile(tenantId, brandId));
    }

    @Transactional
    public LocationView createLocation(TenantId tenantId, BrandId brandId, CreateLocationCommand command) {
        Objects.requireNonNull(command, "Create location command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.LOCATION_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);

        Location location = Location.draft(
                new LocationId(UUID.randomUUID()),
                tenantId,
                brandId,
                command.code(),
                new Slug(command.slug()),
                command.displayName(),
                zone(command.timezone()));
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
                "Location",
                location.id().value(),
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
            TenantId tenantId, BrandId brandId, LocationId locationId, DescribeLocationCommand command) {

        Objects.requireNonNull(command, "Describe location command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.LOCATION_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);

        Location location = store.findLocations(brand).stream()
                .filter(candidate -> candidate.id().equals(locationId))
                .findFirst()
                .orElseThrow(() -> new TenantResourceNotFoundException("Location was not found in this brand"));

        LocationPlace place = command.toPlace(location.place());
        location.describePlace(place);
        store.updateLocationPlace(location);

        // The point is audited as a value, not as "the address changed". Where a
        // branch claims to be is the fact somebody will later need to reconstruct
        // — when a zone stops matching, or when a courier was sent to the wrong
        // building — and an audit entry that only says a field was edited cannot
        // answer either question.
        //
        // Absent fields are omitted rather than recorded as an explicit null:
        // AuditFact's own compact constructor runs every changeDocument through
        // Map.copyOf, which refuses a null value outright, and a location's
        // point, city and district are all genuinely optional (LocationPlace's
        // own doc). Before this wave nothing had ever exercised this path with
        // one of them absent, so describeLocation on the branch every location
        // starts as -- unpinned -- threw out of a call that otherwise succeeded.
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("coordinateSource", place.coordinateSource().name());
        place.point().ifPresent(point -> {
            audited.put("latitude", point.latitude());
            audited.put("longitude", point.longitude());
        });
        if (place.city() != null) {
            audited.put("city", place.city());
        }
        if (place.district() != null) {
            audited.put("district", place.district());
        }
        recordAudit(
                "location.described",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Location",
                location.id().value(),
                "Control-plane location address and point",
                audited);

        return toView(location);
    }

    /**
     * Activates a location created {@link Location#draft draft}, idempotently.
     *
     * <p>Same gap as {@link #activateBrand}, one level down: creation always
     * leaves a location {@code DRAFT}, and pickup-location discovery
     * (`JdbcStorefrontPickupLocationStore.nearestTo`) requires {@code ACTIVE}.
     * A no-op when already {@code ACTIVE}, for the same reconciliation reason.
     */
    @Transactional
    public LocationView activateLocation(TenantId tenantId, BrandId brandId, LocationId locationId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.LOCATION_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);
        Location location = store.findLocations(brand).stream()
                .filter(candidate -> candidate.id().equals(locationId))
                .findFirst()
                .orElseThrow(() -> new TenantResourceNotFoundException("Location was not found in this brand"));
        if (location.status() == OperatingUnitStatus.ACTIVE) {
            return toView(location);
        }
        location.activate();
        store.updateLocationStatus(location);
        recordAudit(
                "location.activated",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Location",
                locationId.value(),
                "Control-plane location activation",
                Map.of("status", location.status().name()));
        // Re-read, for the reason activateBrand gives.
        return toView(requireLocation(brand, locationId));
    }

    /**
     * Corrects a location's name, and while it is a draft its code, slug and
     * timezone. The address and point are {@link #describeLocation}'s, and are
     * not touched here.
     */
    @Transactional
    public LocationView reviseLocation(
            TenantId tenantId,
            BrandId brandId,
            LocationId locationId,
            long expectedVersion,
            ReviseLocationCommand command) {
        Objects.requireNonNull(command, "Revise location command is required");
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.LOCATION_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);
        Location location = requireLocation(brand, locationId);
        requireVersion(expectedVersion, location.version());

        Map<String, Object> before = identityOf(
                location.code(),
                location.slug(),
                location.displayName(),
                location.timezone().getId());
        location.revise(command.code(), new Slug(command.slug()), command.displayName(), zone(command.timezone()));
        if (store.locationCodeOrSlugTakenByAnother(location)) {
            throw new TenantResourceConflictException("Location code or slug is already in use for this brand");
        }
        if (!store.updateLocationIdentity(location)) {
            throw staleLocation(brand, locationId, expectedVersion);
        }
        events.publishEvent(new LocationRevised(
                UUID.randomUUID(),
                tenantId,
                brandId,
                locationId,
                clock.instant(),
                location.code(),
                location.slug().value(),
                location.displayName(),
                location.timezone().getId(),
                location.status().name()));
        recordAudit(
                "location.revised",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Location",
                locationId.value(),
                "Control-plane location correction",
                // Staff 9.3a: a per-field diff, not a root-level {before, after}
                // pair of whole snapshots — see ChangeDocuments.diff's own doc.
                ChangeDocuments.diff(
                        before,
                        identityOf(
                                location.code(),
                                location.slug(),
                                location.displayName(),
                                location.timezone().getId())));
        return toView(requireLocation(brand, locationId));
    }

    /**
     * Deletes a location that never left {@code DRAFT} and that nothing refers
     * to. Brand scope, like creation; {@link #deleteBrand} describes the
     * refusals, less the one about locations.
     */
    @Transactional
    public void deleteLocation(TenantId tenantId, BrandId brandId, LocationId locationId, long expectedVersion) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantManagement(
                tenant, Capability.LOCATION_WRITE, ResourceScope.brand(tenantId.value(), brandId.value()));
        Brand brand = requireBrand(tenantId, brandId);
        Location location = requireLocation(brand, locationId);
        requireVersion(expectedVersion, location.version());

        if (!location.deletable()) {
            throw new OperatingUnitNotDeletableException(
                    OperatingUnitNotDeletableException.Reason.NOT_DRAFT,
                    null,
                    "Only a DRAFT location can be deleted, and this one is " + location.status());
        }
        requireNoScopedGrants(
                ResourceScope.location(tenantId.value(), brandId.value(), locationId.value()), "location");
        if (!store.deleteLocation(location)) {
            throw staleLocation(brand, locationId, expectedVersion);
        }
        events.publishEvent(new LocationDeleted(
                UUID.randomUUID(), tenantId, brandId, locationId, clock.instant(), location.code()));
        recordAudit(
                "location.deleted",
                ResourceScope.brand(tenantId.value(), brandId.value()),
                "Location",
                locationId.value(),
                "Control-plane deletion of a draft location",
                identityOf(
                        location.code(),
                        location.slug(),
                        location.displayName(),
                        location.timezone().getId()));
    }

    @Transactional(readOnly = true)
    public List<LocationView> getLocations(TenantId tenantId, BrandId brandId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantRead(tenant);
        Brand brand = requireBrand(tenantId, brandId);
        return store.findLocations(brand).stream()
                .map(TenantControlPlaneService::toView)
                .toList();
    }

    /**
     * One location, for the operations Settings 10.2 detail screen. Same
     * find-then-filter shape {@link #describeLocation} and {@link
     * #activateLocation} already use — {@code findLocations} is the only
     * lookup the store port offers, and a single extra query per location is
     * not worth widening the port for.
     */
    @Transactional(readOnly = true)
    public LocationView getLocation(TenantId tenantId, BrandId brandId, LocationId locationId) {
        Tenant tenant = requireTenant(tenantId);
        accessPolicy.requireTenantRead(tenant);
        Brand brand = requireBrand(tenantId, brandId);
        Location location = store.findLocations(brand).stream()
                .filter(candidate -> candidate.id().equals(locationId))
                .findFirst()
                .orElseThrow(() -> new TenantResourceNotFoundException("Location was not found in this brand"));
        return toView(location);
    }

    private Tenant requireTenant(TenantId tenantId) {
        return store.findTenant(Objects.requireNonNull(tenantId, "Tenant ID is required"))
                .orElseThrow(() -> new TenantResourceNotFoundException("Tenant was not found"));
    }

    private Brand requireBrand(TenantId tenantId, BrandId brandId) {
        return store.findBrand(tenantId, Objects.requireNonNull(brandId, "Brand ID is required"))
                .orElseThrow(() -> new TenantResourceNotFoundException("Brand was not found in this tenant"));
    }

    private Location requireLocation(Brand brand, LocationId locationId) {
        Objects.requireNonNull(locationId, "Location ID is required");
        return store.findLocations(brand).stream()
                .filter(candidate -> candidate.id().equals(locationId))
                .findFirst()
                .orElseThrow(() -> new TenantResourceNotFoundException("Location was not found in this brand"));
    }

    private static void requireVersion(long expected, long actual) {
        if (expected != actual) {
            throw new TenantResourceStaleException(expected, actual);
        }
    }

    /**
     * The write lost a race after the version check passed: somebody changed or
     * deleted the row in between. Re-read to say which.
     */
    private RuntimeException staleBrand(TenantId tenantId, BrandId brandId, long expected) {
        return store.findBrand(tenantId, brandId)
                .<RuntimeException>map(current -> new TenantResourceStaleException(expected, current.version()))
                .orElseGet(() -> new TenantResourceNotFoundException("Brand was not found in this tenant"));
    }

    private RuntimeException staleLocation(Brand brand, LocationId locationId, long expected) {
        return store.findLocations(brand).stream()
                .filter(candidate -> candidate.id().equals(locationId))
                .findFirst()
                .<RuntimeException>map(current -> new TenantResourceStaleException(expected, current.version()))
                .orElseGet(() -> new TenantResourceNotFoundException("Location was not found in this brand"));
    }

    private void requireNoScopedGrants(ResourceScope scope, String what) {
        int grants = scopedGrants.activeGrantsScopedTo(scope);
        if (grants > 0) {
            throw new OperatingUnitNotDeletableException(
                    OperatingUnitNotDeletableException.Reason.HAS_ACCESS_GRANTS,
                    null,
                    "%d staff grant(s) are scoped to this %s; revoke them first".formatted(grants, what));
        }
    }

    /**
     * An IANA zone, or a refusal a caller can act on. {@link ZoneId#of} throws
     * {@link DateTimeException}, which no handler maps, so a mistyped
     * {@code Asia/Tashkentt} used to come back as a 500 rather than a 400.
     */
    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(Objects.requireNonNull(timezone, "Timezone is required"));
        } catch (DateTimeException unknown) {
            throw new IllegalArgumentException("Unknown timezone: " + timezone, unknown);
        }
    }

    /** What an audit entry records of a unit's identity; names and codes, never an address. */
    private static Map<String, Object> identityOf(
            String code, Slug slug, String displayName, @Nullable String timezone) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("code", code);
        identity.put("slug", slug.value());
        identity.put("displayName", displayName);
        if (timezone != null) {
            identity.put("timezone", timezone);
        }
        return identity;
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

    private static BrandView toView(Brand brand, BrandProfile profile) {
        return new BrandView(
                brand.id().value(),
                brand.tenantId().value(),
                brand.code(),
                brand.slug().value(),
                brand.displayName(),
                brand.status(),
                profile.contactPhone(),
                profile.telegramHandle(),
                profile.logoAssetId(),
                profile.bannerAssetId(),
                profile.locales().stream()
                        .map(locale -> new BrandLocaleView(locale.locale(), locale.description(), locale.isDefault()))
                        .toList(),
                brand.version());
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
                location.place().coordinateSource(),
                location.version());
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

    public record CreateBrandCommand(String code, String slug, String displayName) {}

    /** The whole editable identity, as a form holds it; see {@link Brand#revise} for what may change when. */
    public record ReviseBrandCommand(String code, String slug, String displayName) {}

    /**
     * The whole editable profile, as a form holds it (10.1, 10.12) — a
     * whole-set write for {@code locales}, the same reason {@link
     * uz.horecaos.platform.tenancy.web.LocationServiceOperationsController
     * #replacePreparationBands} replaces its whole set rather than diffing:
     * the screen's own checkbox grid already knows the full set it wants.
     */
    public record UpdateBrandProfileCommand(
            @Nullable String contactPhone,
            @Nullable String telegramHandle,
            @Nullable UUID logoAssetId,
            @Nullable UUID bannerAssetId,
            List<BrandLocaleInput> locales) {

        public UpdateBrandProfileCommand {
            Objects.requireNonNull(locales, "Locales is required (empty, not null, to clear the set)");
        }

        public BrandProfile toProfile() {
            return new BrandProfile(
                    contactPhone,
                    telegramHandle,
                    logoAssetId,
                    bannerAssetId,
                    locales.stream()
                            .map(input -> new BrandProfile.BrandLocale(
                                    input.locale(), input.description(), input.isDefault()))
                            .toList());
        }
    }

    public record BrandLocaleInput(String locale, @Nullable String description, boolean isDefault) {}

    public record CreateLocationCommand(String code, String slug, String displayName, String timezone) {}

    /** The whole editable identity, as a form holds it; see {@link Location#revise} for what may change when. */
    public record ReviseLocationCommand(String code, String slug, String displayName, String timezone) {}

    public record TenantView(
            UUID id,
            String slug,
            String legalName,
            String displayName,
            String defaultCurrency,
            String defaultTimezone,
            @Nullable String keycloakOrganizationId,
            TenantStatus status,
            CustomerIdentityMode customerIdentityMode) {}

    /**
     * One row of the control-plane tenant directory (IA 2.1).
     *
     * @param defaultCurrency the tenant-wide currency — there is no per-brand
     *                        currency in this schema yet (control-plane IA 2.3
     *                        names the gap)
     */
    public record TenantSummaryView(
            UUID id,
            String slug,
            String legalName,
            String displayName,
            String defaultCurrency,
            String defaultTimezone,
            TenantStatus status,
            java.time.Instant createdAt,
            String countryCode,
            String businessType) {}

    /**
     * @param version  what a correction or deletion sends back as {@code
     *                 If-Match} (ADR 0031) — for the brand's identity;
     *                 {@link #updateBrandProfile} carries no version of its
     *                 own, see that method's doc for why
     * @param locales  which languages this brand's storefront supports (10.12)
     *                 and each one's description (10.1); empty means
     *                 unconfigured, not "supports nothing"
     */
    public record BrandView(
            UUID id,
            UUID tenantId,
            String code,
            String slug,
            String displayName,
            OperatingUnitStatus status,
            @Nullable String contactPhone,
            @Nullable String telegramHandle,
            @Nullable UUID logoAssetId,
            @Nullable UUID bannerAssetId,
            List<BrandLocaleView> locales,
            long version) {}

    /** One entry of {@link BrandView#locales}. */
    public record BrandLocaleView(String locale, @Nullable String description, boolean isDefault) {}

    /**
     * Where a branch is, as a caller states it.
     *
     * <p>A whole-place write by design — see {@link
     * uz.horecaos.platform.tenancy.web.TenantControlPlaneController#describeLocation
     * the controller's own doc} for why a {@code PATCH} of individual fields is
     * refused. But "whole place" describes the fields this caller actually
     * edits (address, district, city, phone), not every field {@link
     * LocationPlace} carries: until wave P32, {@link #toPlace} took a caller
     * silent about the point as a caller who wanted it gone, and every
     * console edit reached this endpoint through {@code savePlace()} sending
     * only those four fields — silently moving a placed, surveyed branch back
     * onto the geocoding backlog, and erasing its landmark with it, on every
     * address or phone correction. {@link #toPlace(LocationPlace)} takes the
     * location's current place and carries the point, its source, and the
     * landmark through whenever this write is silent about them.
     *
     * @param coordinateSource who placed the pin. Supplied rather than inferred
     *                         because the platform genuinely cannot tell a surveyed
     *                         point from a guess, and the two have different lives:
     *                         a {@code NOT_GEOCODED} branch stays on the backfill's
     *                         work list, and a pinned one comes off it
     */
    public record DescribeLocationCommand(
            @Nullable String addressLine,
            @Nullable String district,
            @Nullable String city,
            @Nullable String landmark,
            @Nullable String contactPhone,
            @Nullable Double latitude,
            @Nullable Double longitude,
            @Nullable CoordinateSource coordinateSource,
            boolean clearLandmark) {

        /**
         * @param existing the location's place before this write, carried through
         *                 wherever this command is silent about the point or the
         *                 landmark
         */
        public LocationPlace toPlace(LocationPlace existing) {
            Objects.requireNonNull(existing, "Existing location place is required");
            // Half a coordinate is refused outright rather than nulled through.
            // A latitude alone points at the equator, and V0021 had to go back and
            // discard rows that reached customer.addresses exactly this way.
            if ((latitude == null) != (longitude == null)) {
                throw new IllegalArgumentException("A location needs both a latitude and a longitude, or neither");
            }

            GeoPoint point;
            CoordinateSource source;
            if (latitude != null) {
                // A point was supplied: an ordinary pin placement or correction.
                point = new GeoPoint(latitude, longitude);
                source = coordinateSource != null ? coordinateSource : CoordinateSource.MERCHANT_PIN;
            } else if (coordinateSource != null) {
                // No point, but the caller did state a source -- honoured
                // literally. NOT_GEOCODED here is how a pin is deliberately
                // cleared; LocationPlace's own invariant catches any other
                // source claimed without a point.
                point = null;
                source = coordinateSource;
            } else {
                // Neither a point nor a source: this write is not about the pin
                // at all (an address or phone correction). Carry the existing
                // one through rather than defaulting to NOT_GEOCODED, which is
                // the data-loss bug this branch exists to close.
                point = existing.coordinates();
                source = existing.coordinateSource();
            }

            // clearLandmark is the same escape hatch coordinateSource==NOT_GEOCODED
            // gives the point: an emptied form field collapses to a JSON body
            // with no "landmark" key at all (undefined drops from a request
            // body), which is indistinguishable on the wire from a caller that
            // never touched this field at all. Without an explicit signal,
            // "clear the landmark" and "I did not touch the landmark" both
            // read as landmark == null here, and the silent-carry-through
            // branch below would keep the stale value forever.
            String resolvedLandmark = clearLandmark ? null : (landmark != null ? landmark : existing.landmark());
            return new LocationPlace(addressLine, district, city, resolvedLandmark, contactPhone, point, source);
        }
    }

    /** @param version what a correction or deletion sends back as {@code If-Match} (ADR 0031) */
    public record LocationView(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String code,
            String slug,
            String displayName,
            String timezone,
            OperatingUnitStatus status,
            @Nullable String addressLine,
            @Nullable String district,
            @Nullable String city,
            @Nullable String landmark,
            @Nullable String contactPhone,
            @Nullable Double latitude,
            @Nullable Double longitude,
            CoordinateSource coordinateSource,
            long version) {}
}
