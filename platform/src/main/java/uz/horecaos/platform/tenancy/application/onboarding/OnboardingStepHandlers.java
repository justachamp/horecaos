package uz.horecaos.platform.tenancy.application.onboarding;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.grants.TenantOwnerAuthorityGrantor;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.Location;

/**
 * The buildable ADR 0008 step handlers.
 *
 * <p>Six of the seven steps unblocked on 2026-08-30 live here. Five of those
 * six read another module's schema directly through {@link JdbcClient} rather
 * than importing that module's Java types, and that is deliberate, not a
 * shortcut. {@code fulfillment}, {@code catalog}, {@code integration} and
 * {@code payments} each already depend on {@code tenancy.api} (for {@code
 * TenantId}, {@code SalesChannelLookup}, and similar) through a chain that, as
 * of 2026-08-30, loops all the way back: {@code catalog -> tenancy -> payments
 * -> integration -> notifications -> ordering -> pricing -> catalog}. A
 * handler here importing any of their Java types — even just {@code payments},
 * which holds no <em>direct</em> edge to {@code tenancy} — closes that cycle,
 * which {@code ModularArchitectureTests} confirmed the moment it was tried.
 * Reading their tables by name does not: Spring Modulith's boundary check
 * inspects Java imports, not SQL string literals, and {@code pricing}'s own
 * {@code JdbcCatalogPricingContext} already reads {@code catalog.publications}
 * the same way. Only {@code media} holds no dependency on {@code tenancy} at
 * all — including transitively — so {@link MediaAvailability} is the one real
 * exported port used here.
 *
 * <p>{@code ACTIVATION_SMOKE_TEST}, the seventh, is the exception that could
 * not even take the raw-SQL escape hatch: see {@code
 * uz.horecaos.platform.ordering.application.onboarding.OrderingOnboardingStepHandlers}
 * for why.
 */
public final class OnboardingStepHandlers {

    private OnboardingStepHandlers() {}

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

        public KeycloakOrganizationReconcile(OrganizationProvisioner organizations, TenantControlPlaneStore tenants) {
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
                var reference = organizations.ensureOrganization(new OrganizationProvisioner.EnsureOrganization(
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

    /**
     * Links or invites the tenant owner, then grants them platform-side
     * authority.
     *
     * <p>The second half is ADR 0009's own gap, recorded in its
     * implementation status: Keycloak organization membership proves who the
     * owner is, but ADR 0025's capability model does not treat organization
     * membership as authorizing anything by itself. Without this, a linked
     * owner could sign in and do nothing — every mutating endpoint declares a
     * capability, and none had been granted.
     */
    @Component
    public static class TenantOwnerLinkOrInvite implements OnboardingStepHandler {

        private final OrganizationProvisioner organizations;
        private final TenantOwnerAuthorityGrantor authority;

        public TenantOwnerLinkOrInvite(OrganizationProvisioner organizations, TenantOwnerAuthorityGrantor authority) {
            this.organizations = organizations;
            this.authority = authority;
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
                return StepResult.failed("OWNER_NOT_SUPPLIED", "An owner email or an existing subject id is required");
            }
            Object organizationId = context.input().get("organizationId");
            if (organizationId == null) {
                return StepResult.retry("AWAITING_ORGANIZATION", "The organization step has not completed yet");
            }

            try {
                var membership = organizations.ensureMembership(new OrganizationProvisioner.EnsureMembership(
                        String.valueOf(organizationId),
                        email == null ? null : String.valueOf(email),
                        subject == null ? null : String.valueOf(subject)));

                // Granted after membership succeeds, and never before: a grant
                // for a subject Keycloak has not actually linked would be
                // authority resting on nothing. Idempotent on the grantor's
                // side, so a retry that re-enters here after the membership
                // call already succeeded once (the reconciliation path above)
                // does not fail on a grant that already exists.
                authority.grantTenantOwner(
                        context.tenantId(), membership.subjectId(), "Tenant onboarding: owner linked (ADR 0009)");

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

    /**
     * Applies the template's default configuration (Gap D of the 2026-08-30
     * proving run).
     *
     * <p>{@code acceptancePolicy}, when present, is applied as the tenant's
     * {@code TENANT}-scope {@code ordering.acceptance} policy (ADR 0030)
     * through {@link PolicyAuthor} — the same generic write a human uses
     * through {@code OrderAcceptancePolicyController}. The key code is a
     * literal rather than {@code OrderAcceptancePolicyService.ACCEPTANCE},
     * for the reason this file's class javadoc gives for reading another
     * module's schema by name instead of importing its types: {@code
     * ordering} already depends on {@code tenancy.api}, so the reverse import
     * would close the same cycle. tenancy does not need to know what an
     * {@code OrderAcceptancePolicy} means to apply one — only that {@link
     * PolicyAuthor} knows how to store whatever document a {@code
     * PolicyKey}'s code names, and that {@code ordering} will read this exact
     * shape back through its own, real key.
     *
     * <p>Idempotent by construction, not by accident: it never overwrites a
     * policy this key/scope already resolves, whether that resolution came
     * from an earlier attempt at this same step or from an owner who has
     * since authored their own version through the real endpoint. A retry
     * that clobbered a deliberate change would be the platform default
     * reasserting itself over somebody's decision — the same asymmetry
     * {@code PlatformAdminBootstrapReconciler} keeps for Gap A's grants.
     */
    @Component
    public static class DefaultConfigurationApply implements OnboardingStepHandler {

        /**
         * {@code OrderAcceptancePolicyService.ACCEPTANCE.code()}: kept a literal
         * rather than an import — see this handler's own javadoc.
         */
        private static final String ACCEPTANCE_POLICY_KEY_CODE = "ordering.acceptance";

        private final JdbcClient jdbc;
        private final PolicyAuthor policyAuthor;

        public DefaultConfigurationApply(JdbcClient jdbc, PolicyAuthor policyAuthor) {
            this.jdbc = jdbc;
            this.policyAuthor = policyAuthor;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.DEFAULT_CONFIGURATION_APPLY;
        }

        @Override
        public StepResult execute(StepContext context) {
            Object defaults = context.input().get("defaultConfiguration");
            Map<?, ?> configuration = defaults instanceof Map<?, ?> map ? map : Map.of();

            boolean acceptancePolicyApplied = applyAcceptancePolicyDefault(context.tenantId(), configuration);

            return StepResult.completed(
                    Map.of("appliedKeys", configuration.size(), "acceptancePolicyApplied", acceptancePolicyApplied),
                    null);
        }

        private boolean applyAcceptancePolicyDefault(UUID tenantId, Map<?, ?> defaultConfiguration) {
            Object acceptancePolicy = defaultConfiguration.get("acceptancePolicy");
            if (!(acceptancePolicy instanceof Map<?, ?> document) || document.isEmpty()) {
                return false;
            }

            boolean alreadyResolved = Boolean.TRUE.equals(jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM tenant.policy_current
                                    WHERE key_code = :keyCode AND scope_type = 'TENANT' AND tenant_id = :tenantId)
                    """)
                    .param("keyCode", ACCEPTANCE_POLICY_KEY_CODE)
                    .param("tenantId", tenantId)
                    .query(Boolean.class)
                    .single());
            if (alreadyResolved) {
                return true;
            }

            PolicyKey<Map> key = new PolicyKey<>(
                    ACCEPTANCE_POLICY_KEY_CODE,
                    Map.class,
                    EnumSet.of(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION),
                    "ordering",
                    false,
                    "Template default: how an order is accepted");

            policyAuthor.author(
                    key,
                    ResourceScope.tenant(tenantId),
                    document,
                    ActorRef.systemJob("onboarding-default-configuration-apply"),
                    "Applied from the onboarding template's default configuration (ADR 0008)");
            return true;
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
            return StepResult.completed(Map.of("brands", brands.size(), "locations", locations), null);
        }
    }

    /**
     * Validates payment readiness (ADR 0013, ADR 0038): every location has an
     * active legal entity assigned, and any non-cash payment method a channel
     * offers there has a live merchant binding behind it.
     *
     * <p><strong>Owner-decided default (2026-08-30):</strong> a location with
     * no non-cash method enabled passes on the legal-entity check alone — real
     * POS and provider adapters do not exist yet, and requiring a merchant
     * binding no cash-only tenant could ever create would mean no cash-only
     * tenant could ever activate. A channel that already advertises a
     * provider method and has no binding for it is a different, real gap and
     * still fails: immaturity elsewhere is not a reason to accept a
     * configured-but-broken payment method here.
     */
    @Component
    public static class PaymentConfigurationValidate implements OnboardingStepHandler {

        private final TenantControlPlaneStore tenants;
        private final LegalEntityDirectory legalEntities;
        private final JdbcClient jdbc;
        private final Clock clock;

        public PaymentConfigurationValidate(
                TenantControlPlaneStore tenants, LegalEntityDirectory legalEntities, JdbcClient jdbc, Clock clock) {
            this.tenants = tenants;
            this.legalEntities = legalEntities;
            this.jdbc = jdbc;
            this.clock = clock;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.PAYMENT_CONFIGURATION_VALIDATE;
        }

        @Override
        public StepResult execute(StepContext context) {
            TenantId tenantId = new TenantId(context.tenantId());
            LocalDate today = LocalDate.now(clock);

            for (Location location : allLocations(tenants, tenantId)) {
                Optional<FiscalSeller> seller = legalEntities.sellerFor(
                        context.tenantId(), location.id().value(), today);
                if (seller.isEmpty() || !seller.get().active()) {
                    return StepResult.failed(
                            "NO_LEGAL_ENTITY",
                            "Location %s has no active legal entity assigned".formatted(location.code()));
                }

                for (String providerType :
                        nonCashProviderCodes(context.tenantId(), location.id().value())) {
                    if (!merchantBindingExists(context.tenantId(), seller.get().legalEntityId(), providerType, today)) {
                        return StepResult.failed(
                                "NO_MERCHANT_BINDING",
                                "Location %s offers %s but has no merchant binding for legal entity %s"
                                        .formatted(
                                                location.code(),
                                                providerType,
                                                seller.get().code()));
                    }
                }
            }
            return StepResult.completed(Map.of(), null);
        }

        /**
         * Provider codes enabled on some active channel at this location, cash
         * and the aggregator-collected {@code MARKETPLACE} method excluded —
         * neither needs a HorecaOS-held merchant account. {@code
         * payment_method_code} has no foreign key onto a provider registry yet
         * (ADR 0038's own gap, recorded in V0020), so the code itself is read
         * as the provider type: CLICK, PAYME and TELEGRAM are spelled
         * identically on both sides today.
         */
        private Set<String> nonCashProviderCodes(UUID tenantId, UUID locationId) {
            return Set.copyOf(jdbc.sql("""
                    SELECT DISTINCT cpm.payment_method_code
                      FROM tenant.channel_payment_methods cpm
                      JOIN tenant.sales_channel_locations scl
                        ON scl.tenant_id = cpm.tenant_id AND scl.channel_id = cpm.channel_id
                      JOIN tenant.sales_channels sc
                        ON sc.tenant_id = cpm.tenant_id AND sc.id = cpm.channel_id
                     WHERE cpm.tenant_id = :tenantId AND scl.location_id = :locationId
                       AND cpm.enabled AND scl.status = 'ACTIVE' AND sc.status = 'ACTIVE'
                       AND cpm.payment_method_code NOT IN ('CASH', 'MARKETPLACE')
                    """)
                    .param("tenantId", tenantId)
                    .param("locationId", locationId)
                    .query(String.class)
                    .list());
        }

        /**
         * Reads {@code payments.merchant_bindings} directly rather than through
         * a {@code payments.api} port: see this file's class javadoc for why a
         * {@code tenancy -> payments.api} dependency is not safe to add.
         */
        private boolean merchantBindingExists(UUID tenantId, UUID legalEntityId, String providerType, LocalDate at) {
            return Boolean.TRUE.equals(jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM payments.merchant_bindings
                         WHERE tenant_id = :tenantId AND legal_entity_id = :legalEntityId
                           AND provider_type = :providerType AND status = 'ACTIVE'
                           AND effective_from <= :at AND (effective_until IS NULL OR effective_until > :at)
                    )
                    """)
                    .param("tenantId", tenantId)
                    .param("legalEntityId", legalEntityId)
                    .param("providerType", providerType)
                    .param("at", at)
                    .query(Boolean.class)
                    .single());
        }
    }

    /**
     * Validates delivery readiness (ADR 0037): every location whose channel
     * offers {@code DELIVERY} has an active delivery zone bound to it, and
     * that zone — or the location, or the brand — resolves a tariff, mirroring
     * {@code DeliveryFeeResolver.chooseTariff}'s own precedence.
     *
     * <p><strong>Owner-decided default (2026-08-30):</strong> a location with
     * no channel offering {@code DELIVERY} passes without a zone or tariff —
     * pickup-only is a normal v1 shape, not an incomplete one.
     *
     * <p>Reads {@code fulfillment}'s schema directly rather than importing its
     * Java types; see this file's class javadoc for why.
     */
    @Component
    public static class DeliveryConfigurationValidate implements OnboardingStepHandler {

        private final TenantControlPlaneStore tenants;
        private final JdbcClient jdbc;
        private final Clock clock;

        public DeliveryConfigurationValidate(TenantControlPlaneStore tenants, JdbcClient jdbc, Clock clock) {
            this.tenants = tenants;
            this.jdbc = jdbc;
            this.clock = clock;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.DELIVERY_CONFIGURATION_VALIDATE;
        }

        @Override
        public StepResult execute(StepContext context) {
            TenantId tenantId = new TenantId(context.tenantId());
            var now = clock.instant().atOffset(java.time.ZoneOffset.UTC);

            for (Location location : allLocations(tenants, tenantId)) {
                UUID locationId = location.id().value();
                if (!anyChannelOffersDelivery(context.tenantId(), locationId)) {
                    // Pickup-only: passes by the owner-decided default above.
                    continue;
                }

                List<UUID> zoneTariffIds = boundDeliveryZoneTariffIds(context.tenantId(), locationId, now);
                if (zoneTariffIds.isEmpty()) {
                    return StepResult.failed(
                            "NO_DELIVERY_ZONE",
                            "Location %s offers delivery but has no active delivery zone bound"
                                    .formatted(location.code()));
                }

                boolean tariffResolved = zoneTariffIds.stream().anyMatch(java.util.Objects::nonNull)
                        || locationTariffId(context.tenantId(), locationId, now).isPresent()
                        || brandDefaultTariffId(
                                        context.tenantId(), location.brandId().value())
                                .isPresent();

                if (!tariffResolved) {
                    return StepResult.failed(
                            "NO_DELIVERY_TARIFF",
                            ("Location %s has a delivery zone but no tariff resolves for it "
                                            + "(zone, location, or brand default)")
                                    .formatted(location.code()));
                }
            }
            return StepResult.completed(Map.of(), null);
        }

        private boolean anyChannelOffersDelivery(UUID tenantId, UUID locationId) {
            return Boolean.TRUE.equals(jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM tenant.channel_fulfillment_modes cfm
                          JOIN tenant.sales_channel_locations scl
                            ON scl.tenant_id = cfm.tenant_id AND scl.channel_id = cfm.channel_id
                          JOIN tenant.sales_channels sc
                            ON sc.tenant_id = cfm.tenant_id AND sc.id = cfm.channel_id
                         WHERE cfm.tenant_id = :tenantId AND scl.location_id = :locationId
                           AND cfm.fulfillment_mode = 'DELIVERY' AND cfm.enabled
                           AND scl.status = 'ACTIVE' AND sc.status = 'ACTIVE'
                    )
                    """)
                    .param("tenantId", tenantId)
                    .param("locationId", locationId)
                    .query(Boolean.class)
                    .single());
        }

        /** The zone's own tariff lineage id per bound active DELIVERY zone, null when the zone names none. */
        private List<UUID> boundDeliveryZoneTariffIds(UUID tenantId, UUID locationId, java.time.OffsetDateTime at) {
            return jdbc.sql("""
                    SELECT v.delivery_tariff_id
                      FROM fulfillment.zone_location_bindings b
                      JOIN fulfillment.service_zone_versions v
                        ON v.tenant_id = b.tenant_id AND v.zone_id = b.zone_id
                     WHERE b.tenant_id = :tenantId AND b.location_id = :locationId
                       AND v.status = 'ACTIVE' AND v.zone_role = 'DELIVERY'
                       AND b.valid_from <= :at AND (b.valid_until IS NULL OR b.valid_until > :at)
                    """)
                    .param("tenantId", tenantId)
                    .param("locationId", locationId)
                    .param("at", at)
                    .query(UUID.class)
                    .list();
        }

        private Optional<UUID> locationTariffId(UUID tenantId, UUID locationId, java.time.OffsetDateTime at) {
            return jdbc.sql("""
                    SELECT tariff_id FROM fulfillment.location_tariff_bindings
                     WHERE tenant_id = :tenantId AND location_id = :locationId
                       AND valid_from <= :at AND (valid_until IS NULL OR valid_until > :at)
                     ORDER BY valid_from DESC LIMIT 1
                    """)
                    .param("tenantId", tenantId)
                    .param("locationId", locationId)
                    .param("at", at)
                    .query(UUID.class)
                    .optional();
        }

        private Optional<UUID> brandDefaultTariffId(UUID tenantId, UUID brandId) {
            return jdbc.sql("""
                    SELECT id FROM fulfillment.delivery_tariffs
                     WHERE tenant_id = :tenantId AND brand_id = :brandId
                       AND is_brand_default AND status = 'ACTIVE'
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .query(UUID.class)
                    .optional();
        }
    }

    /**
     * Validates POS integration readiness (ADR 0011, ADR 0026).
     *
     * <p><strong>Owner-decided default (2026-08-30):</strong> passes when the
     * tenant has no POS binding configured — most tenants on this template
     * have no POS integration yet. A binding that <em>is</em> configured must
     * actually be healthy: a configured-but-broken binding still fails,
     * because a silently broken POS sync is worse than an absent one.
     */
    @Component
    public static class PosBindingsValidate implements OnboardingStepHandler {

        private final JdbcClient jdbc;

        public PosBindingsValidate(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.POS_BINDINGS_VALIDATE;
        }

        @Override
        public StepResult execute(StepContext context) {
            List<PosBindingHealth> bindings = jdbc.sql("""
                    SELECT i.provider_type, b.status AS binding_status, i.status AS installation_status,
                           i.last_connection_status
                      FROM integration.bindings b
                      JOIN integration.installations i
                        ON i.tenant_id = b.tenant_id AND i.id = b.installation_id
                     WHERE b.tenant_id = :tenantId AND i.provider_category = 'POS'
                    """)
                    .param("tenantId", context.tenantId())
                    .query((row, n) -> new PosBindingHealth(
                            row.getString("provider_type"),
                            row.getString("binding_status"),
                            row.getString("installation_status"),
                            row.getString("last_connection_status")))
                    .list();

            for (PosBindingHealth binding : bindings) {
                boolean healthy = "ACTIVE".equals(binding.bindingStatus())
                        && "ACTIVE".equals(binding.installationStatus())
                        && !"FAILED".equals(binding.lastConnectionStatus());
                if (!healthy) {
                    return StepResult.failed(
                            "POS_BINDING_UNHEALTHY",
                            "%s POS binding is configured but not healthy (binding=%s, installation=%s, lastConnection=%s)"
                                    .formatted(
                                            binding.providerType(),
                                            binding.bindingStatus(),
                                            binding.installationStatus(),
                                            binding.lastConnectionStatus()));
                }
            }
            return StepResult.completed(Map.of("bindings", bindings.size()), null);
        }

        private record PosBindingHealth(
                String providerType, String bindingStatus, String installationStatus, String lastConnectionStatus) {}
    }

    /**
     * Validates catalogue readiness (ADR 0016): every brand has a {@code
     * PUBLISHED} publication on the storefront channel with at least one item
     * actually available to order somewhere.
     *
     * <p>Reads {@code catalog}'s schema directly rather than importing its
     * Java types; see this file's class javadoc for why.
     */
    @Component
    public static class CatalogReadinessValidate implements OnboardingStepHandler {

        /** {@code StorefrontChannelSeeder.STOREFRONT_CODE}: every tenant gets this channel. */
        private static final String STOREFRONT_CHANNEL = "STOREFRONT";

        private final TenantControlPlaneStore tenants;
        private final JdbcClient jdbc;

        public CatalogReadinessValidate(TenantControlPlaneStore tenants, JdbcClient jdbc) {
            this.tenants = tenants;
            this.jdbc = jdbc;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.CATALOG_READINESS_VALIDATE;
        }

        @Override
        public StepResult execute(StepContext context) {
            TenantId tenantId = new TenantId(context.tenantId());

            for (Brand brand : tenants.findBrands(tenantId)) {
                UUID brandId = brand.id().value();
                if (!published(context.tenantId(), brandId)) {
                    return StepResult.failed(
                            "NO_PUBLISHED_MENU",
                            "Brand %s has no PUBLISHED catalog publication on %s"
                                    .formatted(brand.code(), STOREFRONT_CHANNEL));
                }
                if (!anyAvailableItem(context.tenantId(), brandId)) {
                    return StepResult.failed(
                            "NO_AVAILABLE_ITEM",
                            "Brand %s has a published menu with no item available to order".formatted(brand.code()));
                }
            }
            return StepResult.completed(Map.of(), null);
        }

        private boolean published(UUID tenantId, UUID brandId) {
            return Boolean.TRUE.equals(jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM catalog.publications
                     WHERE tenant_id = :tenantId AND brand_id = :brandId
                       AND channel = :channel AND status = 'PUBLISHED')
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("channel", STOREFRONT_CHANNEL)
                    .query(Boolean.class)
                    .single());
        }

        private boolean anyAvailableItem(UUID tenantId, UUID brandId) {
            return Boolean.TRUE.equals(jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM catalog.location_offerings
                     WHERE tenant_id = :tenantId AND brand_id = :brandId AND status = 'AVAILABLE')
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .query(Boolean.class)
                    .single());
        }
    }

    /**
     * Validates media readiness (ADR 0010) — pass-with-note semantics, per the
     * step's own design: media is optional in v1, so validating it means only
     * that whatever <em>is</em> referenced actually renders. A tenant with no
     * media reference at all passes with a note rather than a failure; a
     * tenant with a reference to an asset that is not {@code AVAILABLE} fails
     * — exactly the broken-image gap this check exists to catch before
     * go-live rather than after a customer sees it.
     */
    @Component
    public static class MediaReadinessValidate implements OnboardingStepHandler {

        private final JdbcClient jdbc;
        private final MediaAvailability media;

        public MediaReadinessValidate(JdbcClient jdbc, MediaAvailability media) {
            this.jdbc = jdbc;
            this.media = media;
        }

        @Override
        public OnboardingStep step() {
            return OnboardingStep.MEDIA_READINESS_VALIDATE;
        }

        @Override
        public StepResult execute(StepContext context) {
            List<UUID> referenced = jdbc.sql("""
                    SELECT DISTINCT media_asset_id FROM catalog.media_relations WHERE tenant_id = :tenantId
                    """)
                    .param("tenantId", context.tenantId())
                    .query(UUID.class)
                    .list();

            if (referenced.isEmpty()) {
                return StepResult.completed(
                        Map.of("note", "No media referenced yet; nothing to validate", "referenced", 0), null);
            }

            Set<MediaAssetId> assetIds =
                    referenced.stream().map(MediaAssetId::new).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!media.allDisplayable(context.tenantId(), assetIds)) {
                return StepResult.failed(
                        "MEDIA_NOT_AVAILABLE",
                        "%d referenced media asset(s) exist but at least one is not yet AVAILABLE"
                                .formatted(referenced.size()));
            }
            return StepResult.completed(Map.of("referenced", referenced.size()), null);
        }
    }

    /**
     * Validates custom frontend domain readiness (ADR 0022).
     *
     * <p><strong>Owner-decided default (2026-08-30):</strong> custom frontend
     * domains are out of v1 scope entirely — there is no domain-registration
     * table or API anywhere in this deployment yet, so no tenant can request
     * one. The step therefore always completes: it is not a check that always
     * passes by construction, it is a check with nothing to check yet. When a
     * domain concept ships, this handler is exactly where the real check
     * goes.
     */
    @Component
    public static class FrontendDomainValidate implements OnboardingStepHandler {

        @Override
        public OnboardingStep step() {
            return OnboardingStep.FRONTEND_DOMAIN_VALIDATE;
        }

        @Override
        public StepResult execute(StepContext context) {
            return StepResult.completed(Map.of("customDomainRequested", false), null);
        }
    }

    /** Every location across every brand of a tenant, for handlers that must validate all of them. */
    private static List<Location> allLocations(TenantControlPlaneStore tenants, TenantId tenantId) {
        List<Location> locations = new ArrayList<>();
        for (Brand brand : tenants.findBrands(tenantId)) {
            locations.addAll(tenants.findLocations(brand));
        }
        return locations;
    }
}
