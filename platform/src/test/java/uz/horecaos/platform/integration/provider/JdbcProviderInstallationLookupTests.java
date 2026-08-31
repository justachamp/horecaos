package uz.horecaos.platform.integration.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0026. The scope and uniqueness tests matter most: which provider handles
 * a capability must never depend on row order, and a binding must never reach
 * another tenant.
 */
class JdbcProviderInstallationLookupTests {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121401");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121402");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121403");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121404");
    private static final UUID SIBLING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121405");
    private static final String CAPABILITY = "QuoteDelivery";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcProviderInstallationLookup lookup;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.provider_entity_mappings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        lookup = new JdbcProviderInstallationLookup(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        insertHierarchy();
        insertEnvironment("yandex-uz-prod", "DELIVERY", "yandex");
    }

    @Test
    void aLocationBindingWinsOverItsBrand() {
        UUID brandInstallation = insertInstallation("DELIVERY", "yandex", "brand account");
        UUID locationInstallation = insertInstallation("DELIVERY", "yandex", "location account");
        UUID brandBinding = insertBinding(brandInstallation, BRAND, null, 100);
        UUID locationBinding = insertBinding(locationInstallation, BRAND, LOCATION, 100);
        insertCapability(brandBinding, CAPABILITY, true);
        insertCapability(locationBinding, CAPABILITY, true);

        assertThat(lookup.primaryBinding(TENANT, BRAND, LOCATION, CAPABILITY))
                .map(BindingRef::bindingId)
                .as("a location binding overrides its brand's, matching ADR 0030 precedence")
                .contains(locationBinding);
    }

    @Test
    void aBrandBindingAppliesWhereTheLocationHasNone() {
        UUID installation = insertInstallation("DELIVERY", "yandex", "brand account");
        UUID binding = insertBinding(installation, BRAND, null, 100);
        insertCapability(binding, CAPABILITY, true);

        assertThat(lookup.primaryBinding(TENANT, BRAND, SIBLING, CAPABILITY))
                .map(BindingRef::bindingId)
                .contains(binding);
    }

    @Test
    void anUnboundCapabilityResolvesToNothing() {
        assertThat(lookup.primaryBinding(TENANT, BRAND, LOCATION, "CreateShipment"))
                .isEmpty();
    }

    @Test
    void everyEligibleProviderIsReturnedForQuoting() {
        UUID first = insertInstallation("DELIVERY", "yandex", "first");
        UUID second = insertInstallation("DELIVERY", "noor", "second");
        UUID firstBinding = insertBinding(first, BRAND, LOCATION, 100);
        UUID secondBinding = insertBinding(second, BRAND, LOCATION, 200);
        insertCapability(firstBinding, CAPABILITY, true);
        insertCapability(secondBinding, CAPABILITY, false);

        assertThat(lookup.candidateBindings(TENANT, BRAND, LOCATION, CAPABILITY))
                .as("ADR 0014 quotes several partners before booking exactly one")
                .extracting(BindingRef::bindingId)
                .containsExactly(firstBinding, secondBinding);
    }

    @Test
    void twoPrimaryBindingsForOneScopeAndCapabilityCannotCoexist() {
        UUID first = insertInstallation("DELIVERY", "yandex", "first");
        UUID second = insertInstallation("DELIVERY", "noor", "second");
        UUID firstBinding = insertBinding(first, BRAND, LOCATION, 100);
        UUID secondBinding = insertBinding(second, BRAND, LOCATION, 100);
        insertCapability(firstBinding, CAPABILITY, true);

        assertThatThrownBy(() -> insertCapability(secondBinding, CAPABILITY, true))
                .as("which provider handles a capability must not depend on row order")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aBindingCannotReferenceAnotherTenantsLocation() {
        UUID installation = insertInstallation("DELIVERY", "yandex", "account");

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE')
                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", OTHER_TENANT)
                        .param("installationId", installation)
                        .param("brandId", BRAND)
                        .param("locationId", LOCATION)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSuspendedBindingStopsResolving() {
        UUID installation = insertInstallation("DELIVERY", "yandex", "account");
        UUID binding = insertBinding(installation, BRAND, LOCATION, 100);
        insertCapability(binding, CAPABILITY, true);
        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", binding)
                .update();

        assertThat(lookup.primaryBinding(TENANT, BRAND, LOCATION, CAPABILITY))
                .as("rollback suspends a binding and returns operations to a manual path")
                .isEmpty();
    }

    @Test
    void mappingsResolveInBothDirections() {
        UUID installation = insertInstallation("POS", "clopos", "pos account");
        UUID binding = insertBinding(installation, BRAND, LOCATION, 100);
        UUID product = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1214ff");
        insertMapping(binding, installation, "PRODUCT", product, "EXT-42");

        assertThat(lookup.externalIdFor(binding, "PRODUCT", product)).contains("EXT-42");
        assertThat(lookup.horecaosIdFor(binding, "PRODUCT", "EXT-42")).contains(product);
    }

    @Test
    void anAmbiguousMappingIsRejectedRatherThanOverwritten() {
        UUID installation = insertInstallation("POS", "clopos", "pos account");
        UUID binding = insertBinding(installation, BRAND, LOCATION, 100);
        UUID product = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1214ff");
        UUID other = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1214fe");
        insertMapping(binding, installation, "PRODUCT", product, "EXT-42");

        assertThatThrownBy(() -> insertMapping(binding, installation, "PRODUCT", other, "EXT-42"))
                .as("an ambiguous mapping is a conflict to resolve, never last-write-wins")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anInstallationSnapshotCarriesAReferenceNotACredential() {
        UUID installation = insertInstallation("PAYMENT", "click", "merchant account");

        var snapshot = lookup.installation(TENANT, installation).orElseThrow();

        assertThat(snapshot.secretReference())
                .as("ADR 0028 owns the value; rotation changes what is behind this string")
                .startsWith("horecaos:");
        assertThat(snapshot.baseUrl()).startsWith("https://");
    }

    private void insertEnvironment(String code, String category, String type) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, :category, :type, 'https://provider.example', true, 'provider.example')
                ON CONFLICT DO NOTHING
                """)
                .param("code", code)
                .param("category", category)
                .param("type", type)
                .update();
    }

    private UUID insertInstallation(String category, String type, String name) {
        insertEnvironment(type + "-env", category, type);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, :category, :type, :environment, :name, 'ACTIVE', :secret)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("category", category)
                .param("type", type)
                .param("environment", type + "-env")
                .param("name", name)
                .param(
                        "secret",
                        "horecaos:local:provider_%s:%s:key".formatted(category.toLowerCase(java.util.Locale.ROOT), id))
                .update();
        return id;
    }

    private UUID insertBinding(UUID installationId, UUID brandId, @Nullable UUID locationId, int priority) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status, priority, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', :priority, :from)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("priority", priority)
                .param("from", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private void insertCapability(UUID bindingId, String capability, boolean primary) {
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities
                    (binding_id, tenant_id, capability_code, enabled, is_primary, verified_at)
                VALUES (:bindingId, :tenantId, :capability, true, :primary, :now)
                """)
                .param("bindingId", bindingId)
                .param("tenantId", TENANT)
                .param("capability", capability)
                .param("primary", primary)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertMapping(
            UUID bindingId, UUID installationId, String entityType, UUID horecaosId, String externalId) {
        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type,
                     horecaos_entity_id, external_entity_id, status, mapping_source)
                VALUES (:id, :tenantId, :installationId, :bindingId, :entityType,
                        :horecaosId, :externalId, 'ACTIVE', 'DISCOVERED')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("bindingId", bindingId)
                .param("entityType", entityType)
                .param("horecaosId", horecaosId)
                .param("externalId", externalId)
                .update();
    }

    private void insertHierarchy() {
        for (UUID tenant : new UUID[] {TENANT, OTHER_TENANT}) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "tenant-" + tenant.toString().substring(24))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_A', 'brand-a', 'Brand A', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (UUID location : new UUID[] {LOCATION, SIBLING}) {
            jdbc.sql("""
                    INSERT INTO tenant.locations
                        (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", "L" + location.toString().substring(24).toUpperCase(java.util.Locale.ROOT))
                    .param("slug", "l-" + location.toString().substring(24))
                    .update();
        }
    }
}
