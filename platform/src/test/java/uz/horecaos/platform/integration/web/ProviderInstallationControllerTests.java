package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.api.secrets.SecretWriter;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.provider.ProviderCapabilityReconciliationService;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/** ADR 0026's activation gate is tested against the database snapshot it consumes. */
class ProviderInstallationControllerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121601");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121602");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);

    /** Neither test in this class reaches the capability-reconciliation path. */
    private static final SecretResolver UNUSED_SECRETS = new SecretResolver() {
        @Override
        public SecretValue resolve(SecretReference reference) {
            throw new UnsupportedOperationException("not exercised by these tests");
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            throw new UnsupportedOperationException("not exercised by these tests");
        }
    };

    /** Neither test in this class reaches the rotate-secret path. */
    private static final ProviderInstallationLookup UNUSED_INSTALLATIONS = new ProviderInstallationLookup() {
        @Override
        public Optional<BindingRef> primaryBinding(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            throw new UnsupportedOperationException("not exercised by these tests");
        }

        @Override
        public List<BindingRef> candidateBindings(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            throw new UnsupportedOperationException("not exercised by these tests");
        }

        @Override
        public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
            throw new UnsupportedOperationException("not exercised by these tests");
        }
    };

    /** Neither test in this class reaches the value-rotation path. */
    private static final SecretWriter UNUSED_WRITER = (reference, value) -> {
        throw new UnsupportedOperationException("not exercised by these tests");
    };

    /**
     * Neither test in this class reaches the webhook-registration endpoint. A
     * concrete class, not an interface like the collaborators above, so there
     * is no throwing stand-in to build — null is safe here because {@link
     * ProviderInstallationController} never dereferences it outside {@code
     * registerWebhook}.
     */
    @SuppressWarnings("NullAway")
    private static final uz.horecaos.platform.integration.provider.telegram.TelegramWebhookRegistrationService
            UNUSED_WEBHOOK_REGISTRATION = null;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ProviderInstallationController controller;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE integration.provider_capability_probes CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        hierarchy();
        controller = new ProviderInstallationController(
                jdbc,
                fact -> {},
                () -> new AuthenticatedActor("operator", Set.of(), Map.of()),
                CLOCK,
                // Neither test here calls the capability-reconciliation endpoint, so
                // this is a real but idle collaborator rather than a stand-in for one:
                // an empty catalogue and a resolver that is never asked to resolve.
                new ProviderCapabilityReconciliationService(jdbc, List.of(), UNUSED_SECRETS, new ObjectMapper(), CLOCK),
                // Same posture as the two collaborators above: neither test here
                // calls the rotate-secret endpoint, so these are real but idle
                // rather than stand-ins for one.
                UNUSED_INSTALLATIONS,
                UNUSED_SECRETS,
                new TelegramBotApiClient(new ObjectMapper()),
                new SecretIngressGateway(UNUSED_WRITER, "test"),
                // Neither test here calls the webhook-registration endpoint.
                UNUSED_WEBHOOK_REGISTRATION);
    }

    @Test
    void activationRequiresEveryEnabledCapabilityToHaveSupportedSnapshotEvidence() {
        UUID installation = installation("sms-one");
        UUID binding = binding(installation);
        capability(binding, "SEND_SMS");
        successfulPreflight(installation, "{\"SEND_SMS\":{\"support\":\"UNSUPPORTED\"}}");

        assertThatThrownBy(() -> controller.activateBinding(
                        TENANT, installation, binding, new ProviderInstallationController.ReasonRequest("ready")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Every enabled binding capability");
        assertThat(status(binding)).isEqualTo("SUSPENDED");

        successfulPreflight(installation, "{\"SEND_SMS\":{\"support\":\"SUPPORTED\"}}");
        assertThat(controller
                        .activateBinding(
                                TENANT,
                                installation,
                                binding,
                                new ProviderInstallationController.ReasonRequest("verified"))
                        .getBody())
                .containsEntry("outcome", "activated");
        assertThat(status(binding)).isEqualTo("ACTIVE");
        assertThat(status(installation)).isEqualTo("ACTIVE");
    }

    /**
     * Gap-map row 10.8a's fix path, belt-and-braces half: a POS or DELIVERY
     * binding with zero {@code integration.binding_capabilities} rows makes
     * the "every enabled capability is verified" gate above vacuously true
     * (its {@code EXISTS} ranges over zero rows), so a binding that somehow
     * reached {@code SUSPENDED} with no capabilities -- a direct insert, or
     * {@code bind()} before this fix -- must still be refused here rather than
     * activating into a row {@code effectiveBindings}' INNER JOIN can never see.
     */
    @Test
    void activationRefusesAPosBindingWithZeroEnabledCapabilities() {
        UUID installation = fakePosInstallation("fake-pos-activate-empty-test");
        UUID binding = binding(installation);
        successfulPreflight(installation, "{}");

        assertThatThrownBy(() -> controller.activateBinding(
                        TENANT, installation, binding, new ProviderInstallationController.ReasonRequest("ready")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("needs at least one enabled capability");
        assertThat(status(binding))
                .as("the dead binding must stay SUSPENDED, not activate invisibly")
                .isEqualTo("SUSPENDED");
    }

    /**
     * The other half of the same belt-and-braces rule: PAYMENT and NOTIFICATION
     * have no wired capability catalogue and legitimately bind with an empty
     * capability set (the console's connect drawer sends {@code []} for them
     * unchanged), so the new zero-capability gate in {@code activateBinding}
     * must not start refusing them.
     */
    @Test
    void activationStillAllowsAZeroCapabilityBindingForACategoryWithNoCatalogue() {
        UUID installation = installation("sms-zero-cap-activate-test");
        UUID binding = binding(installation);
        successfulPreflight(installation, "{}");

        assertThat(controller
                        .activateBinding(
                                TENANT,
                                installation,
                                binding,
                                new ProviderInstallationController.ReasonRequest("no catalogue for NOTIFICATION"))
                        .getBody())
                .containsEntry("outcome", "activated");
        assertThat(status(binding)).isEqualTo("ACTIVE");
    }

    /**
     * ADR 0106: an analytics installation's one declared non-secret field
     * lands under its own key in {@code non_sensitive_config} — not appended
     * as a bare string, and not lost, which a test that only checked "the
     * field is somewhere in there" would not catch if the key were wrong.
     */
    @Test
    void installStoresAnAnalyticsInstallationsNonSecretFieldUnderItsDeclaredKey() {
        environment("ga4-test", "ANALYTICS", "GOOGLE_ANALYTICS_4");

        controller.install(
                TENANT,
                new ProviderInstallationController.InstallRequest(
                        uz.horecaos.platform.integration.api.provider.ProviderCategory.ANALYTICS,
                        "GOOGLE_ANALYTICS_4",
                        "ga4-test",
                        "Storefront GA4",
                        null,
                        "G-ABC1234"));

        String config =
                jdbc.sql("""
                SELECT non_sensitive_config::text FROM integration.installations
                 WHERE tenant_id = :tenantId AND provider_type = 'GOOGLE_ANALYTICS_4'
                """).param("tenantId", TENANT).query(String.class).single();
        assertThat(config).isEqualTo("{\"ga4MeasurementId\": \"G-ABC1234\"}");
    }

    /**
     * ADR 0106: a blank non-secret field keeps its position rather than
     * shifting the next field into its slot — the correction
     * {@code connect-provider-panel.ts}'s own submit() now makes on the
     * frontend, proven here on the backend half of the same join.
     */
    @Test
    void installKeepsABlankNonSecretFieldsPositionRatherThanShiftingTheNextOneLeft() {
        environment("click-test", "PAYMENT", "CLICK");

        controller.install(
                TENANT,
                new ProviderInstallationController.InstallRequest(
                        uz.horecaos.platform.integration.api.provider.ProviderCategory.PAYMENT,
                        "CLICK",
                        "click-test",
                        "Click prod",
                        null,
                        // merchantId blank, serviceId filled — the position the
                        // frontend's own trimmed join now preserves as "/service-9".
                        "/service-9"));

        String config =
                jdbc.sql("""
                SELECT non_sensitive_config::text FROM integration.installations
                 WHERE tenant_id = :tenantId AND provider_type = 'CLICK'
                """).param("tenantId", TENANT).query(String.class).single();
        assertThat(config)
                .as("serviceId landed under its own key, not merchantId's")
                .isEqualTo("{\"serviceId\": \"service-9\"}");
    }

    /**
     * A request that names an approved provider in a different case than the
     * seeded {@code provider_environments} row still installs (the existence
     * check is deliberately case-insensitive — see {@code install()}'s own
     * comment), but everything the install persists or looks up must use the
     * row's own canonical casing, never the request's. Otherwise the install
     * both silently drops every non-secret field the operator supplied (the
     * declaration is case-sensitive matched and finds nothing for
     * {@code "Click"}) and leaves behind a {@code provider_type} that can
     * never again match this class's own exact-case gates
     * ({@code TELEGRAM_BOT_API.equals(...)}, {@code CLOPOS_PROVIDER_TYPE.equals(...)}).
     */
    @Test
    void installNormalizesACaseMismatchedProviderTypeToTheApprovedEnvironmentsCanonicalCasing() {
        environment("click-test", "PAYMENT", "CLICK");

        controller.install(
                TENANT,
                new ProviderInstallationController.InstallRequest(
                        uz.horecaos.platform.integration.api.provider.ProviderCategory.PAYMENT,
                        "Click",
                        "click-test",
                        "Click prod",
                        null,
                        "merchant-1/service-9"));

        Map<String, Object> row =
                jdbc.sql("""
                SELECT provider_type, non_sensitive_config::text AS config
                  FROM integration.installations
                 WHERE tenant_id = :tenantId
                """).param("tenantId", TENANT).query().singleRow();
        assertThat(row)
                .as("persisted casing must be the seeded row's canonical form, not the request's")
                .containsEntry("provider_type", "CLICK");
        assertThat(row.get("config"))
                // jsonb key order is by key length then value, not insertion order —
                // "serviceId" (9 chars) sorts before "merchantId" (10), same as
                // installKeepsABlankNonSecretFieldsPositionRatherThanShiftingTheNextOneLeft above.
                .as("the non-secret fields must still be captured under the canonical provider type")
                .isEqualTo("{\"serviceId\": \"service-9\", \"merchantId\": \"merchant-1\"}");
    }

    private void environment(String code, String category, String providerType) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, :category, :providerType, 'https://example.test', false, '')
                ON CONFLICT (code) DO NOTHING
                """)
                .param("code", code)
                .param("category", category)
                .param("providerType", providerType)
                .update();
    }

    @Test
    void theCloposClerkApprovalSettingDefaultsToTrueAndCanBeToggled() {
        UUID clopos = cloposInstallation("clopos-settings-one");

        assertThat(controller.settings(TENANT, clopos).getBody())
                .as("nothing has been set yet, so the safe posture — the clerk decides — applies")
                .isEqualTo(new ProviderInstallationController.CloposSettingsView(true));

        ProviderInstallationController.CloposSettingsView afterDisable = controller
                .updateSettings(TENANT, clopos, new ProviderInstallationController.UpdateCloposSettingsRequest(false))
                .getBody();
        assertThat(afterDisable).isEqualTo(new ProviderInstallationController.CloposSettingsView(false));
        assertThat(controller.settings(TENANT, clopos).getBody())
                .as("the write persisted, so a fresh read agrees with it")
                .isEqualTo(new ProviderInstallationController.CloposSettingsView(false));

        ProviderInstallationController.CloposSettingsView afterReenable = controller
                .updateSettings(TENANT, clopos, new ProviderInstallationController.UpdateCloposSettingsRequest(true))
                .getBody();
        assertThat(afterReenable).isEqualTo(new ProviderInstallationController.CloposSettingsView(true));
    }

    @Test
    void theCloposSettingsEndpointRefusesAnInstallationOfAnotherProviderType() {
        UUID sms = installation("clopos-settings-two");

        assertThatThrownBy(() -> controller.settings(TENANT, sms))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("clopos installations only");
        assertThatThrownBy(() -> controller.updateSettings(
                        TENANT, sms, new ProviderInstallationController.UpdateCloposSettingsRequest(false)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("clopos installations only");
    }

    @Test
    void aBindingCannotBeActivatedThroughAnotherInstallationPath() {
        UUID first = installation("sms-one");
        UUID second = installation("sms-two");
        UUID binding = binding(second);
        capability(binding, "SEND_SMS");
        successfulPreflight(first, "{\"SEND_SMS\":{\"support\":\"SUPPORTED\"}}");

        assertThatThrownBy(() -> controller.activateBinding(
                        TENANT, first, binding, new ProviderInstallationController.ReasonRequest("wrong installation")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Installation or binding is not available");
        assertThat(status(binding)).isEqualTo("SUSPENDED");
    }

    /**
     * Activating or suspending a binding takes its id, and nothing listed one
     * before; this is what an operator finds it by.
     */
    @Test
    void anInstallationsBindingsAreListedAndOnlyItsOwn() {
        UUID first = installation("sms-one");
        UUID second = installation("sms-two");
        UUID mine = binding(first);
        binding(second);

        assertThat(controller.bindings(TENANT, first)).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(mine);
            assertThat(view.brandId()).isEqualTo(BRAND);
            assertThat(view.status()).isEqualTo("SUSPENDED");
        });
        assertThat(controller.bindings(UUID.randomUUID(), first))
                .as("the same installation id under another tenant is simply absent")
                .isEmpty();
    }

    /**
     * Gap-map row 10.8a: the resolver already prefers a location binding over
     * its brand's (proven at the store layer by {@code
     * JdbcProviderInstallationLookupTests.aLocationBindingWinsOverItsBrand});
     * this is the same precedence, over HTTP, for the per-branch hub read.
     */
    @Test
    void effectiveBindingsPrefersALocationBindingOverTheBrandDefault() {
        UUID location = location("branch-one");
        UUID brandInstallation = installation("sms-one");
        UUID locationInstallation = installation("sms-two");
        UUID brandBinding = activeBinding(brandInstallation, null);
        UUID locationBinding = activeBinding(locationInstallation, location);
        primaryCapability(brandBinding, "SEND_SMS");
        primaryCapability(locationBinding, "SEND_SMS");

        assertThat(controller.effectiveBindings(TENANT, BRAND)).singleElement().satisfies(view -> {
            assertThat(view.locationId()).isEqualTo(location);
            assertThat(view.capabilityCode()).isEqualTo("SEND_SMS");
            assertThat(view.installationId())
                    .as("the location's own binding wins, not the brand default")
                    .isEqualTo(locationInstallation);
            assertThat(view.bindingId()).isEqualTo(locationBinding);
            assertThat(view.locationScoped()).isTrue();
        });
    }

    @Test
    void effectiveBindingsFallsBackToTheBrandDefaultWhenNoLocationBindingExists() {
        UUID location = location("branch-two");
        UUID brandInstallation = installation("sms-three");
        UUID brandBinding = activeBinding(brandInstallation, null);
        primaryCapability(brandBinding, "SEND_SMS");

        assertThat(controller.effectiveBindings(TENANT, BRAND)).singleElement().satisfies(view -> {
            assertThat(view.locationId()).isEqualTo(location);
            assertThat(view.installationId()).isEqualTo(brandInstallation);
            assertThat(view.bindingId()).isEqualTo(brandBinding);
            assertThat(view.locationScoped())
                    .as("inherited from the brand's own default, not bound to this branch")
                    .isFalse();
        });
    }

    @Test
    void effectiveBindingsOmitsABranchWithNothingBoundAtEitherScope() {
        location("branch-three");

        assertThat(controller.effectiveBindings(TENANT, BRAND))
                .as("no installation at brand or location scope means no row, not a null one")
                .isEmpty();
    }

    /**
     * Gap-map row 10.8a's fix path, HTTP-level: the branch-binding dialog's
     * capability-assignment picker reads this to default to "every
     * capability the installation's provider declares" for a POS
     * installation -- built against a fresh controller wired with a real
     * {@link uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup}
     * and a real {@link uz.horecaos.platform.pos.infrastructure.PosProviderCapabilityCatalog}
     * rather than the class's own shared {@code controller}, whose {@link
     * #UNUSED_INSTALLATIONS} throws on the installation lookup this endpoint
     * needs.
     */
    @Test
    void capabilityCatalogueReturnsThePosAdaptersDeclaredCeilingSorted() {
        UUID installation = fakePosInstallation("fake-pos-catalogue-test");
        ProviderInstallationController withRealInstallationLookup = new ProviderInstallationController(
                jdbc,
                fact -> {},
                () -> new AuthenticatedActor("operator", Set.of(), Map.of()),
                CLOCK,
                new ProviderCapabilityReconciliationService(
                        jdbc,
                        List.of(new uz.horecaos.platform.pos.infrastructure.PosProviderCapabilityCatalog(
                                List.of(new uz.horecaos.platform.pos.FakePosAdapter()))),
                        UNUSED_SECRETS,
                        new ObjectMapper(),
                        CLOCK),
                new uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup(
                        jdbc, CLOCK, new uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup(jdbc)),
                UNUSED_SECRETS,
                new TelegramBotApiClient(new ObjectMapper()),
                new SecretIngressGateway(UNUSED_WRITER, "test"),
                UNUSED_WEBHOOK_REGISTRATION);

        var view = java.util.Objects.requireNonNull(withRealInstallationLookup
                .capabilityCatalogue(TENANT, installation)
                .getBody());

        assertThat(view.category()).isEqualTo(uz.horecaos.platform.integration.api.provider.ProviderCategory.POS);
        assertThat(view.providerType()).isEqualTo(uz.horecaos.platform.pos.FakePosAdapter.PROVIDER_TYPE);
        assertThat(view.capabilities())
                .as("every capability the fake adapter declares, before any reconciliation has run")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(uz.horecaos.platform.pos.api.PosCapability.values())
                                .map(uz.horecaos.platform.pos.api.PosCapability::code)
                                .toList())
                .isSorted();
    }

    @Test
    void capabilityCatalogueIsA404ForAnUnknownInstallation() {
        ProviderInstallationController withRealInstallationLookup = new ProviderInstallationController(
                jdbc,
                fact -> {},
                () -> new AuthenticatedActor("operator", Set.of(), Map.of()),
                CLOCK,
                new ProviderCapabilityReconciliationService(jdbc, List.of(), UNUSED_SECRETS, new ObjectMapper(), CLOCK),
                new uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup(
                        jdbc, CLOCK, new uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup(jdbc)),
                UNUSED_SECRETS,
                new TelegramBotApiClient(new ObjectMapper()),
                new SecretIngressGateway(UNUSED_WRITER, "test"),
                UNUSED_WEBHOOK_REGISTRATION);

        assertThatThrownBy(() -> withRealInstallationLookup.capabilityCatalogue(TENANT, UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    /**
     * Gap-map row 10.8a's fix path, HTTP-level half: {@code bind()} must not
     * accept an empty {@code capabilities} array for a POS or DELIVERY
     * installation, because that is exactly the dead, INNER-JOIN-invisible
     * binding the capability-catalogue endpoint and console picker exist to
     * stop creating -- and {@code BindRequest.capabilities} carries no
     * {@code @NotEmpty}, so nothing but this check enforces it for a caller
     * other than the console (a script, a stale build, a future UI regression).
     */
    @Test
    void bindRefusesAnEmptyCapabilitySetForAPosInstallation() {
        UUID installation = fakePosInstallation("fake-pos-bind-empty-test");
        UUID location = location("bind-empty-branch");
        ProviderInstallationController withRealInstallationLookup = controllerWithRealInstallationLookup();

        assertThatThrownBy(() -> withRealInstallationLookup.bind(
                        TENANT,
                        installation,
                        new ProviderInstallationController.BindRequest(BRAND, location, null, List.of(), List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("needs at least one capability");
        assertThat(jdbc.sql("SELECT count(*) FROM integration.bindings WHERE installation_id = :id")
                        .param("id", installation)
                        .query(Integer.class)
                        .single())
                .as("no dead binding row was left behind")
                .isEqualTo(0);
    }

    /**
     * The other half of the same rule: PAYMENT and NOTIFICATION have no wired
     * capability catalogue, so {@code bind()} must keep accepting the connect
     * drawer's own empty {@code capabilities} array for them, unchanged.
     */
    @Test
    void bindStillAllowsAnEmptyCapabilitySetForACategoryWithNoCatalogue() {
        UUID installation = installation("sms-zero-cap-bind-test");
        UUID location = location("bind-no-catalogue-branch");
        ProviderInstallationController withRealInstallationLookup = controllerWithRealInstallationLookup();

        Map<String, Object> body = withRealInstallationLookup
                .bind(
                        TENANT,
                        installation,
                        new ProviderInstallationController.BindRequest(BRAND, location, null, List.of(), List.of()))
                .getBody();

        assertThat(body).containsEntry("status", "SUSPENDED");
    }

    /** Same fresh-controller wiring {@code capabilityCatalogueReturnsThePosAdaptersDeclaredCeilingSorted} builds by hand. */
    private ProviderInstallationController controllerWithRealInstallationLookup() {
        return new ProviderInstallationController(
                jdbc,
                fact -> {},
                () -> new AuthenticatedActor("operator", Set.of(), Map.of()),
                CLOCK,
                new ProviderCapabilityReconciliationService(jdbc, List.of(), UNUSED_SECRETS, new ObjectMapper(), CLOCK),
                new uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup(
                        jdbc, CLOCK, new uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup(jdbc)),
                UNUSED_SECRETS,
                new TelegramBotApiClient(new ObjectMapper()),
                new SecretIngressGateway(UNUSED_WRITER, "test"),
                UNUSED_WEBHOOK_REGISTRATION);
    }

    private UUID installation(String code) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', 'GENERIC_SMS', 'https://sms.example', false, 'sms.example')
                """).param("code", code).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'GENERIC_SMS', :environment,
                        'Test SMS', 'DRAFT', 'horecaos:test:provider_notification:tenant:sms')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("environment", code)
                .update();
        return id;
    }

    /** A POS installation of provider type {@code clopos}, config-empty, the way {@code install()} leaves one. */
    private UUID cloposInstallation(String code) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'POS', 'clopos', 'https://api.clopos.com', false, 'api.clopos.com')
                """).param("code", code).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'POS', 'clopos', :environment,
                        'Test Clopos', 'DRAFT', 'horecaos:test:provider_pos:tenant:clopos')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("environment", code)
                .update();
        return id;
    }

    /**
     * A POS installation of provider type {@link uz.horecaos.platform.pos.FakePosAdapter#PROVIDER_TYPE} --
     * unlike {@link #cloposInstallation}, this one matches an adapter a test
     * actually wires into {@link uz.horecaos.platform.pos.infrastructure.PosProviderCapabilityCatalog},
     * so {@code capabilityCatalogue} has a real, non-empty declaration to find.
     */
    private UUID fakePosInstallation(String code) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'POS', :providerType, 'https://fake-pos.example', false, 'fake-pos.example')
                """)
                .param("code", code)
                .param("providerType", uz.horecaos.platform.pos.FakePosAdapter.PROVIDER_TYPE)
                .update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'POS', :providerType, :environment,
                        'Test fake POS', 'DRAFT', 'horecaos:test:provider_pos:tenant:fake')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("providerType", uz.horecaos.platform.pos.FakePosAdapter.PROVIDER_TYPE)
                .param("environment", code)
                .update();
        return id;
    }

    private UUID binding(UUID installation) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'SUSPENDED')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("installationId", installation)
                .param("brandId", BRAND)
                .update();
        return id;
    }

    /** An ACTIVE binding {@code effectiveBindings} can resolve, at the brand ({@code location} null) or a branch. */
    private UUID activeBinding(UUID installation, @Nullable UUID location) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("installationId", installation)
                .param("brandId", BRAND)
                .param("locationId", location)
                .update();
        jdbc.sql("UPDATE integration.installations SET status = 'ACTIVE' WHERE id = :id")
                .param("id", installation)
                .update();
        return id;
    }

    private void capability(UUID binding, String code) {
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities
                    (binding_id, tenant_id, capability_code, enabled, is_primary)
                VALUES (:bindingId, :tenantId, :code, true, false)
                """)
                .param("bindingId", binding)
                .param("tenantId", TENANT)
                .param("code", code)
                .update();
    }

    /** A primary, enabled capability — what {@code effectiveBindings} requires to resolve at all. */
    private void primaryCapability(UUID binding, String code) {
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities
                    (binding_id, tenant_id, capability_code, enabled, is_primary)
                VALUES (:bindingId, :tenantId, :code, true, true)
                """)
                .param("bindingId", binding)
                .param("tenantId", TENANT)
                .param("code", code)
                .update();
    }

    private UUID location(String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :displayName, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code.toUpperCase(java.util.Locale.ROOT))
                .param("slug", code)
                .param("displayName", code)
                .update();
        return id;
    }

    private void successfulPreflight(UUID installation, String snapshot) {
        jdbc.sql("""
                UPDATE integration.installations
                   SET last_connection_status = 'SUCCEEDED', capability_snapshot = cast(:snapshot AS jsonb)
                 WHERE id = :id
                """).param("id", installation).param("snapshot", snapshot).update();
    }

    private String status(UUID id) {
        return jdbc.sql("""
                SELECT status FROM integration.bindings WHERE id = :id
                """)
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseGet(() -> jdbc.sql("SELECT status FROM integration.installations WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single());
    }

    private void hierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'controller-test', 'Controller Test', 'Controller Test',
                        'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'TEST', 'test', 'Test', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }
}
