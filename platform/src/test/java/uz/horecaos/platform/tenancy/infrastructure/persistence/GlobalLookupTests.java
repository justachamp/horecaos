package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.partner.domain.ExternalReference;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcGlobalLookup.EntityType;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcGlobalLookup.Hit;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcGlobalLookup.MatchedOn;

/**
 * ADR 0083 against the migrated schema.
 *
 * <p>A lookup that matches nothing still runs every probe, so the miss cases
 * are what prove each probe's SQL agrees with the tables it reads — an order,
 * a courier, a fiscal document — without building a whole order to find.
 */
class GlobalLookupTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1483a1");
    private static final UUID OTHER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1483a2");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1483b1");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1483c1");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1483d1");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcGlobalLookup lookup;

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
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        lookup = new JdbcGlobalLookup(jdbc);

        tenant(TENANT, "oshxona-lookup", "Oshxona Lookup");
        tenant(OTHER, "somsa-lookup", "Somsa Markazi");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenant, 'OSH', 'osh', 'Oshxona Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenant", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenant, :brand, 'CHL', 'chl', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenant", TENANT)
                .param("brand", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('lookup-env', 'POS', 'clopos', 'https://pos.example', false, 'pos.example')
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :tenant, 'POS', 'clopos', 'lookup-env', 'Clopos Chilonzor', 'DRAFT')
                """).param("id", INSTALLATION).param("tenant", TENANT).update();
    }

    @Test
    void anIdFindsWhatItIsAndWhoseItIs() {
        assertThat(lookup.find(LOCATION.toString())).singleElement().satisfies(hit -> {
            assertThat(hit.type()).isEqualTo(EntityType.LOCATION);
            assertThat(hit.tenantName()).isEqualTo("Oshxona Lookup");
            assertThat(hit.label()).isEqualTo("Chilonzor");
            assertThat(hit.matchedOn()).isEqualTo(MatchedOn.ID);
        });
        assertThat(lookup.find(INSTALLATION.toString())).extracting(Hit::type).containsExactly(EntityType.INSTALLATION);
        assertThat(lookup.find(BRAND.toString())).extracting(Hit::type).containsExactly(EntityType.BRAND);
        assertThat(lookup.find(" " + TENANT + " ")).extracting(Hit::type).containsExactly(EntityType.TENANT);
    }

    @Test
    void anUnknownIdRunsEveryProbeAndFindsNothing() {
        assertThat(lookup.find(UUID.randomUUID().toString()))
                .as("every id probe ran against its table — orders, customers, couriers, devices, "
                        + "exports, fiscal documents — and none matched")
                .isEmpty();
    }

    @Test
    void textFindsATenantBySlugOrNameAndRunsTheReferenceProbesWithoutMatching() {
        assertThat(lookup.find("oshxona-lookup")).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo(TENANT);
            assertThat(hit.matchedOn()).isEqualTo(MatchedOn.SLUG);
        });
        assertThat(lookup.find("Somsa")).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo(OTHER);
            assertThat(hit.matchedOn()).isEqualTo(MatchedOn.NAME);
        });
        List<Hit> none = lookup.find("A-1042");
        assertThat(none)
                .as("order number, courier reference, provider and partner reference probes all ran")
                .isEmpty();
    }

    @Test
    void aPartnerReferenceIsMatchedInTheFormThePartnerModuleStoresIt() {
        for (String raw : List.of("#ab-12 34", "  wolt-9981 ", "XY12", "#-a b-c")) {
            assertThat(JdbcGlobalLookup.partnerForm(raw))
                    .as("the lookup's rule must answer exactly what ExternalReference stored for %s", raw)
                    .isEqualTo(ExternalReference.normalise(raw));
        }
        assertThat(JdbcGlobalLookup.partnerForm(" - ")).isNull();
    }

    private void tenant(UUID id, String slug, String name) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).param("name", name).update();
    }
}
