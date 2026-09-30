package uz.horecaos.platform.tenancy.application.onboarding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.domain.Brand;

/**
 * The settings-home readiness conditions that had no read behind them (gap
 * map row {@code 10.0}, settings.md §10.0): fiscal classification coverage,
 * channel payment-method coverage and secret-rotation age (batch 15), then
 * channel fulfilment-mode coverage and location service-binding coverage
 * (batch 16).
 *
 * <p>Each is an {@link OnboardingReadinessCheck}: it names every offending item
 * in the {@link StepResult#failedWithFindings} shape the {@code VALIDATING}
 * steps use, so {@code OnboardingService#validate} expands it into one countable
 * row per item, and the console deep-links each one by its error code. None
 * writes anything.
 *
 * <p>The per-location <em>fiscal assignment</em> check settings.md lists first
 * is not here because it already exists: {@code
 * OnboardingStepHandlers.PaymentConfigurationValidate} resolves each
 * location's seller through {@code LegalEntityDirectory#sellerFor}, which reads
 * {@code tenant.location_fiscal_assignments}, and reports {@code
 * NO_LEGAL_ENTITY} for every location without an active one. A second check on
 * the same table would say the same thing twice.
 *
 * <p>Reads {@code catalog}, {@code payments} and {@code integration} tables by
 * name rather than importing those modules' types, for the reason {@link
 * OnboardingStepHandlers}' class doc gives: each already depends on {@code
 * tenancy.api}, so the reverse import would close a module cycle.
 */
public final class OnboardingReadinessChecks {

    private OnboardingReadinessChecks() {}

    /**
     * Brands that can sell, and how much of each menu still lacks the four
     * fiscal fields ADR 0038's provider contracts require: ИКПУ, package code,
     * fiscal unit code and fiscal name.
     *
     * <p><strong>Advisory.</strong> {@code CatalogValidator} still reports an
     * incomplete classification as a warning, not a publication blocker,
     * because ADR 0038's rollout enables the blocking rules per brand once its
     * coverage report is clean. A readiness list that called it blocking would
     * contradict the publish gate that lets the same menu through.
     *
     * <p>Counts the same nodes {@code JdbcCatalogStore#fiscalCoverageNodes}
     * does — active variants of active products, active modifier options with no
     * linked variant of their own, and an existing delivery fee — so this and
     * the Fiscalization screen's own coverage tab cannot disagree about a
     * brand. One difference is deliberate: that read vivifies the brand's fee
     * row on first visit, and a dry run must write nothing, so a brand whose
     * fee row does not exist yet is simply not counted here.
     *
     * <p>Judged over the brands that can sell as a result of activating, by the
     * same rule as the payment and delivery steps ({@link
     * TenantControlPlaneStore#findActiveBrands}). A brand with no classifiable
     * node at all is skipped: an empty menu is the catalog check's finding, not
     * this one's.
     */
    @Component
    public static class FiscalClassificationCoverage implements OnboardingReadinessCheck {

        static final String KEY = "FISCAL_CLASSIFICATION_COVERAGE_VALIDATE";
        static final String INCOMPLETE = "FISCAL_CLASSIFICATION_INCOMPLETE";

        private final TenantControlPlaneStore tenants;
        private final JdbcClient jdbc;

        public FiscalClassificationCoverage(TenantControlPlaneStore tenants, JdbcClient jdbc) {
            this.tenants = tenants;
            this.jdbc = jdbc;
        }

        @Override
        public String checkKey() {
            return KEY;
        }

        @Override
        public boolean advisory() {
            return true;
        }

        @Override
        public StepResult check(UUID tenantId) {
            List<StepResult.Finding> findings = new ArrayList<>();
            for (Brand brand : tenants.findActiveBrands(new TenantId(tenantId))) {
                Coverage coverage = coverageOf(tenantId, brand.id().value());
                if (coverage.unclassified() > 0) {
                    findings.add(new StepResult.Finding(
                            INCOMPLETE,
                            "Brand %s has %d of %d menu items without a complete fiscal classification"
                                    .formatted(brand.code(), coverage.unclassified(), coverage.total()),
                            null));
                }
            }
            return findings.isEmpty() ? StepResult.completed(Map.of(), null) : StepResult.failedWithFindings(findings);
        }

        private Coverage coverageOf(UUID tenantId, UUID brandId) {
            return jdbc.sql("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE unclassified) AS unclassified
                      FROM (
                            SELECT (fc.id IS NULL OR fc.mxik_code IS NULL OR fc.package_code IS NULL
                                      OR fc.fiscal_unit_code IS NULL OR fc.fiscal_name IS NULL) AS unclassified
                              FROM catalog.variants v
                              JOIN catalog.products p
                                ON p.id = v.product_id AND p.tenant_id = v.tenant_id AND p.brand_id = v.brand_id
                              LEFT JOIN catalog.fiscal_classifications fc
                                ON fc.priceable_type = 'VARIANT' AND fc.priceable_id = v.id
                                   AND fc.tenant_id = v.tenant_id
                             WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId
                               AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'
                            UNION ALL
                            SELECT (fc.id IS NULL OR fc.mxik_code IS NULL OR fc.package_code IS NULL
                                      OR fc.fiscal_unit_code IS NULL OR fc.fiscal_name IS NULL) AS unclassified
                              FROM catalog.modifier_options o
                              LEFT JOIN catalog.fiscal_classifications fc
                                ON fc.priceable_type = 'MODIFIER_OPTION' AND fc.priceable_id = o.id
                                   AND fc.tenant_id = o.tenant_id
                             WHERE o.tenant_id = :tenantId AND o.brand_id = :brandId
                               AND o.status = 'ACTIVE' AND o.linked_variant_id IS NULL
                            UNION ALL
                            SELECT (fc.id IS NULL OR fc.mxik_code IS NULL OR fc.package_code IS NULL
                                      OR fc.fiscal_unit_code IS NULL OR fc.fiscal_name IS NULL) AS unclassified
                              FROM catalog.fees f
                              LEFT JOIN catalog.fiscal_classifications fc
                                ON fc.priceable_type = 'FEE' AND fc.priceable_id = f.id
                                   AND fc.tenant_id = f.tenant_id
                             WHERE f.tenant_id = :tenantId AND f.brand_id = :brandId AND f.status = 'ACTIVE'
                           ) nodes
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .query((row, number) -> new Coverage(row.getLong("total"), row.getLong("unclassified")))
                    .single();
        }

        private record Coverage(long total, long unclassified) {}
    }

    /**
     * Every active sales channel that has no payment method enabled
     * (settings.md §10.0: «Channel active with no enabled payment method»).
     *
     * <p><strong>Blocking.</strong> {@code SalesChannelLookup}'s own contract is
     * that a channel with no matrix offers nothing rather than everything, so an
     * active channel with zero enabled rows in {@code
     * tenant.channel_payment_methods} can open a cart and never finish one.
     * {@code PaymentConfigurationValidate} covers the opposite shape — a method
     * that is enabled but has no merchant account behind it — and passes a
     * channel with none enabled at all, which is what this closes.
     *
     * <p>Tenant-scoped, not location-scoped: a channel is a route to market for
     * the whole tenant, so the finding carries no {@code locationId}. It names
     * the channel as its {@link StepResult.Subject subject}, so the console
     * links to that channel's own setup rather than to the list; an older
     * console that ignores the subject still links to the sales-channels list.
     */
    @Component
    public static class ChannelPaymentCoverage implements OnboardingReadinessCheck {

        static final String KEY = "CHANNEL_PAYMENT_COVERAGE_VALIDATE";
        static final String NO_PAYMENT_METHOD = "CHANNEL_NO_PAYMENT_METHOD";

        private final JdbcClient jdbc;

        public ChannelPaymentCoverage(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String checkKey() {
            return KEY;
        }

        @Override
        public boolean advisory() {
            return false;
        }

        @Override
        public StepResult check(UUID tenantId) {
            List<StepResult.Finding> findings = jdbc.sql("""
                            SELECT sc.id, sc.code
                              FROM tenant.sales_channels sc
                             WHERE sc.tenant_id = :tenantId AND sc.status = 'ACTIVE'
                               AND NOT EXISTS (
                                   SELECT 1 FROM tenant.channel_payment_methods cpm
                                    WHERE cpm.tenant_id = sc.tenant_id AND cpm.channel_id = sc.id
                                      AND cpm.enabled)
                             ORDER BY sc.code
                            """)
                    .param("tenantId", tenantId)
                    .query((row, number) -> StepResult.Finding.about(
                            NO_PAYMENT_METHOD,
                            "Sales channel %s has no enabled payment method".formatted(row.getString("code")),
                            StepResult.Subject.salesChannel(row.getObject("id", UUID.class))))
                    .list();
            return findings.isEmpty() ? StepResult.completed(Map.of(), null) : StepResult.failedWithFindings(findings);
        }
    }

    /**
     * Provider credentials past their rotation period (ADR 0094, settings.md
     * §10.0: «Secret past its rotation period»).
     *
     * <p><strong>Advisory.</strong> Providers do not announce when a credential
     * expires, so the rule is age: a credential not rotated through the platform
     * within {@code horecaos.integration.credential-rotation-interval}
     * (default 180 days), counted from its last rotation or, if it never was,
     * from when it was set up. That is exactly the rule {@code
     * CredentialRotationController} applies, with the same property, so the
     * readiness panel and the control plane's credentials-due list cannot
     * disagree about which credentials are due. Retired installations and
     * retired merchant bindings are not counted: nothing uses their credential.
     *
     * <p>Reads only the dates beside the secret references, never a value (ADR
     * 0028), and names each credential by its provider type and by what tells
     * two of them apart — an installation's own display name, a merchant
     * account's legal entity code (one ACTIVE account per entity per provider, so
     * the pair is unique among live accounts) — never the merchant account reference or the secret
     * reference. The panel shows one row per credential, so two rows must not
     * read alike.
     */
    @Component
    public static class SecretRotationAge implements OnboardingReadinessCheck {

        static final String KEY = "SECRET_ROTATION_AGE_VALIDATE";
        static final String INSTALLATION_DUE = "INSTALLATION_SECRET_ROTATION_DUE";
        static final String MERCHANT_DUE = "MERCHANT_SECRET_ROTATION_DUE";

        private final JdbcClient jdbc;
        private final Clock clock;
        private final Duration rotationInterval;

        public SecretRotationAge(
                JdbcClient jdbc,
                Clock clock,
                @Value("${horecaos.integration.credential-rotation-interval:P180D}") Duration rotationInterval) {
            this.jdbc = jdbc;
            this.clock = clock;
            this.rotationInterval = rotationInterval;
        }

        @Override
        public String checkKey() {
            return KEY;
        }

        @Override
        public boolean advisory() {
            return true;
        }

        @Override
        public StepResult check(UUID tenantId) {
            Instant now = clock.instant();
            OffsetDateTime cutoff = OffsetDateTime.ofInstant(now.minus(rotationInterval), ZoneOffset.UTC);
            List<StepResult.Finding> findings = jdbc.sql("""
                            SELECT 'INSTALLATION' AS kind, display_name AS label, provider_type,
                                   coalesce(last_secret_rotated_at, created_at) AS since
                              FROM integration.installations
                             WHERE tenant_id = :tenantId AND secret_reference IS NOT NULL
                               AND status <> 'RETIRED'
                               AND coalesce(last_secret_rotated_at, created_at) < :cutoff
                            UNION ALL
                            SELECT 'MERCHANT_ACCOUNT' AS kind, le.code AS label, mb.provider_type,
                                   coalesce(mb.last_secret_rotated_at, mb.created_at) AS since
                              FROM payments.merchant_bindings mb
                              JOIN tenant.legal_entities le
                                ON le.tenant_id = mb.tenant_id AND le.id = mb.legal_entity_id
                             WHERE mb.tenant_id = :tenantId AND mb.status <> 'RETIRED'
                               AND coalesce(mb.last_secret_rotated_at, mb.created_at) < :cutoff
                             ORDER BY since, kind, label
                            """)
                    .param("tenantId", tenantId)
                    .param("cutoff", cutoff)
                    .query((row, number) -> {
                        long days = Math.max(
                                0,
                                Duration.between(
                                                row.getObject("since", OffsetDateTime.class)
                                                        .toInstant(),
                                                now)
                                        .toDays());
                        String label = row.getString("label");
                        String providerType = row.getString("provider_type");
                        long period = rotationInterval.toDays();
                        if ("INSTALLATION".equals(row.getString("kind"))) {
                            return new StepResult.Finding(
                                    INSTALLATION_DUE,
                                    "Provider connection %s (%s) has a credential %d days old; the rotation period is %d days"
                                            .formatted(label, providerType, days, period),
                                    null);
                        }
                        return new StepResult.Finding(
                                MERCHANT_DUE,
                                "The %s merchant account of legal entity %s has a credential %d days old; the rotation period is %d days"
                                        .formatted(providerType, label, days, period),
                                null);
                    })
                    .list();
            return findings.isEmpty() ? StepResult.completed(Map.of(), null) : StepResult.failedWithFindings(findings);
        }
    }

    /**
     * Every active sales channel must be able to serve at least one fulfilment
     * mode somewhere (settings.md §10.0: «Channel active with no enabled
     * fulfilment mode», widened by the row's own note to «bound at some
     * location»).
     *
     * <p><strong>Blocking.</strong> ADR 0036's resolver refuses an order on a
     * channel whose requested mode is not enabled (rule 3), and refuses it again
     * at a location that has no schedule bound for that mode. A channel where
     * neither ever holds opens a cart nobody can complete, which is the same
     * failure {@link ChannelPaymentCoverage} names for payment methods. Two
     * shapes, two codes, because they are fixed on different screens:
     *
     * <ul>
     *   <li>{@value #NO_MODE} — zero enabled rows in {@code
     *       tenant.channel_fulfillment_modes}: fixed in the channel's own setup;
     *   <li>{@value #NO_SERVICEABLE_MODE} — enabled modes, but none of them has a
     *       schedule bound ({@code tenant.location_service_bindings}) at an
     *       active location the channel is switched on for: fixed in the
     *       location's hours or in the channel's location list.
     * </ul>
     *
     * <p>Tenant-scoped, not location-scoped, like every channel finding: each
     * names its channel as a {@link StepResult.Subject subject} so the console
     * can open that channel's setup.
     */
    @Component
    public static class ChannelFulfillmentCoverage implements OnboardingReadinessCheck {

        static final String KEY = "CHANNEL_FULFILLMENT_COVERAGE_VALIDATE";
        static final String NO_MODE = "CHANNEL_NO_FULFILLMENT_MODE";
        static final String NO_SERVICEABLE_MODE = "CHANNEL_NO_SERVICEABLE_MODE";

        private final JdbcClient jdbc;

        public ChannelFulfillmentCoverage(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String checkKey() {
            return KEY;
        }

        @Override
        public boolean advisory() {
            return false;
        }

        @Override
        public StepResult check(UUID tenantId) {
            List<ChannelRow> channels = jdbc.sql("""
                            SELECT sc.id, sc.code,
                                   EXISTS (
                                       SELECT 1 FROM tenant.channel_fulfillment_modes cfm
                                        WHERE cfm.tenant_id = sc.tenant_id AND cfm.channel_id = sc.id
                                          AND cfm.enabled) AS has_mode,
                                   EXISTS (
                                       SELECT 1
                                         FROM tenant.channel_fulfillment_modes cfm
                                         JOIN tenant.sales_channel_locations scl
                                           ON scl.tenant_id = cfm.tenant_id AND scl.channel_id = cfm.channel_id
                                          AND scl.status = 'ACTIVE'
                                         JOIN tenant.locations l
                                           ON l.tenant_id = scl.tenant_id AND l.id = scl.location_id
                                          AND l.status = 'ACTIVE'
                                         JOIN tenant.location_service_bindings b
                                           ON b.tenant_id = l.tenant_id AND b.location_id = l.id
                                          AND b.fulfillment_mode = cfm.fulfillment_mode
                                        WHERE cfm.tenant_id = sc.tenant_id AND cfm.channel_id = sc.id
                                          AND cfm.enabled) AS serviceable
                              FROM tenant.sales_channels sc
                             WHERE sc.tenant_id = :tenantId AND sc.status = 'ACTIVE'
                             ORDER BY sc.code
                            """)
                    .param("tenantId", tenantId)
                    .query((row, number) -> new ChannelRow(
                            row.getObject("id", UUID.class),
                            row.getString("code"),
                            row.getBoolean("has_mode"),
                            row.getBoolean("serviceable")))
                    .list();
            List<StepResult.Finding> findings = new ArrayList<>();
            for (ChannelRow channel : channels) {
                StepResult.Subject subject = StepResult.Subject.salesChannel(channel.id());
                if (!channel.hasMode()) {
                    findings.add(StepResult.Finding.about(
                            NO_MODE,
                            "Sales channel %s has no enabled fulfilment mode".formatted(channel.code()),
                            subject));
                } else if (!channel.serviceable()) {
                    findings.add(StepResult.Finding.about(
                            NO_SERVICEABLE_MODE,
                            ("Sales channel %s has enabled fulfilment modes, but none has a schedule bound "
                                            + "at an active location the channel serves")
                                    .formatted(channel.code()),
                            subject));
                }
            }
            return findings.isEmpty() ? StepResult.completed(Map.of(), null) : StepResult.failedWithFindings(findings);
        }

        private record ChannelRow(UUID id, String code, boolean hasMode, boolean serviceable) {}
    }

    /**
     * Every active location must have a service schedule bound for each
     * fulfilment mode a channel can sell there (settings.md §10.0: «Location
     * with no schedule bound for an enabled mode»).
     *
     * <p><strong>Blocking.</strong> With no {@code tenant.location_service_bindings}
     * row for a (location, mode), the resolver has no opening hours to consult
     * and refuses the order, so the location is closed for that mode without
     * anyone having closed it — the quietest way for a branch to be dark.
     *
     * <p>"Enabled mode" is read from where a customer meets it: a mode enabled on
     * an active channel that is switched on at this location. A location no
     * channel reaches has no such mode, so it is held to the plainer rule the
     * row's title states — it must have some schedule bound — instead of passing
     * for having nothing to bind. One finding per location, naming every mode it
     * lacks, with the location as its {@code locationId} so the console opens the
     * location where its hours are set.
     */
    @Component
    public static class LocationServiceBindingCoverage implements OnboardingReadinessCheck {

        static final String KEY = "LOCATION_SERVICE_BINDING_COVERAGE_VALIDATE";
        static final String NO_SCHEDULE = "LOCATION_NO_SERVICE_SCHEDULE";

        private final JdbcClient jdbc;

        public LocationServiceBindingCoverage(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public String checkKey() {
            return KEY;
        }

        @Override
        public boolean advisory() {
            return false;
        }

        @Override
        public StepResult check(UUID tenantId) {
            List<LocationMode> rows = jdbc.sql("""
                            SELECT l.id AS location_id, l.code, m.mode,
                                   CASE WHEN m.mode IS NULL THEN NULL
                                        ELSE EXISTS (
                                            SELECT 1 FROM tenant.location_service_bindings b
                                             WHERE b.tenant_id = l.tenant_id AND b.location_id = l.id
                                               AND b.fulfillment_mode = m.mode) END AS bound,
                                   EXISTS (
                                       SELECT 1 FROM tenant.location_service_bindings b
                                        WHERE b.tenant_id = l.tenant_id AND b.location_id = l.id) AS any_bound
                              FROM tenant.locations l
                              LEFT JOIN LATERAL (
                                   SELECT DISTINCT cfm.fulfillment_mode AS mode
                                     FROM tenant.channel_fulfillment_modes cfm
                                     JOIN tenant.sales_channel_locations scl
                                       ON scl.tenant_id = cfm.tenant_id AND scl.channel_id = cfm.channel_id
                                      AND scl.location_id = l.id AND scl.status = 'ACTIVE'
                                     JOIN tenant.sales_channels sc
                                       ON sc.tenant_id = cfm.tenant_id AND sc.id = cfm.channel_id
                                      AND sc.status = 'ACTIVE'
                                    WHERE cfm.tenant_id = l.tenant_id AND cfm.enabled
                              ) m ON true
                             WHERE l.tenant_id = :tenantId AND l.status = 'ACTIVE'
                             ORDER BY l.code, l.id, m.mode
                            """)
                    .param("tenantId", tenantId)
                    .query((row, number) -> new LocationMode(
                            row.getObject("location_id", UUID.class),
                            row.getString("code"),
                            row.getString("mode"),
                            (Boolean) row.getObject("bound"),
                            row.getBoolean("any_bound")))
                    .list();

            Map<UUID, List<LocationMode>> byLocation = new java.util.LinkedHashMap<>();
            for (LocationMode row : rows) {
                byLocation
                        .computeIfAbsent(row.locationId(), id -> new ArrayList<>())
                        .add(row);
            }
            List<StepResult.Finding> findings = new ArrayList<>();
            for (List<LocationMode> location : byLocation.values()) {
                LocationMode first = location.get(0);
                if (first.mode() == null) {
                    if (!first.anyBound()) {
                        findings.add(new StepResult.Finding(
                                NO_SCHEDULE,
                                "Location %s has no service schedule bound".formatted(first.code()),
                                first.locationId()));
                    }
                    continue;
                }
                List<String> missing = location.stream()
                        .filter(mode -> !Boolean.TRUE.equals(mode.bound()))
                        .map(LocationMode::mode)
                        .toList();
                if (!missing.isEmpty()) {
                    findings.add(new StepResult.Finding(
                            NO_SCHEDULE,
                            "Location %s has no schedule bound for %s"
                                    .formatted(first.code(), String.join(", ", missing)),
                            first.locationId()));
                }
            }
            return findings.isEmpty() ? StepResult.completed(Map.of(), null) : StepResult.failedWithFindings(findings);
        }

        /** One (location, mode) pair; {@code mode} and {@code bound} are null for a location no channel reaches. */
        private record LocationMode(
                UUID locationId,
                String code,
                @Nullable String mode,
                @Nullable Boolean bound,
                boolean anyBound) {}
    }
}
