package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0036 Layer B's write half (gap map row 4.4b, wave P45):
 * {@code catalog.channel_offering_exclusions} (V0020) had {@link
 * JdbcCatalogStore#channelExcludedVariantIds} to read it and nothing to write
 * it — {@link ChannelOfferingExclusionsReaderTests} exercises the reader
 * against fixture rows inserted directly by SQL, because until this wave that
 * was the only way any row got there. This class is the same "read through a
 * real database" evidence for {@link CatalogAuthoringService#setChannelOffering}
 * and {@link CatalogAuthoringService#bulkSetChannelOffering}: an operator
 * saying "not on this channel" from the console, not from a database client.
 */
class ChannelOfferingExclusionWriteTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID SIBLING_BRAND = UUID.randomUUID();
    private static final UUID LOCATION_1 = UUID.randomUUID();
    private static final UUID LOCATION_2 = UUID.randomUUID();
    private static final String ACTOR = "channel-exclusion-writer-test";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;

    private UUID channel;
    private UUID otherTenantChannel;
    private UUID variant1;
    private UUID variant2;
    private UUID siblingBrandVariant;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the channel exclusion writer tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.channel_offering_exclusions, catalog.variants, " + "catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());

        insertTenant(TENANT, "channel-exclusion-tenant");
        insertBrand(BRAND, TENANT, "MAIN", "main");
        insertLocation(LOCATION_1, TENANT, BRAND, "MAIN01", "main-01");
        insertLocation(LOCATION_2, TENANT, BRAND, "MAIN02", "main-02");
        channel = insertChannel(TENANT, "UZUM_TEZKOR");

        variant1 = seedProductAndVariant(TENANT, BRAND, "BURGER");
        variant2 = seedProductAndVariant(TENANT, BRAND, "PIZZA");

        insertBrand(SIBLING_BRAND, TENANT, "SIBLING", "sibling");
        siblingBrandVariant = seedProductAndVariant(TENANT, SIBLING_BRAND, "SIBLING_DISH");

        insertTenant(OTHER_TENANT, "channel-exclusion-other-tenant");
        insertBrand(OTHER_BRAND, OTHER_TENANT, "MAIN", "main");
        otherTenantChannel = insertChannel(OTHER_TENANT, "UZUM_TEZKOR");
    }

    @Test
    @DisplayName("excluding a variant brand-wide hides it at every location and the read agrees")
    void excludingBrandWideHidesItEverywhere() {
        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, null, false, "SEASONAL", ACTOR);

        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_1))
                .contains(variant1);
        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_2))
                .as("location_id IS NULL means brand-wide, not location_1-only")
                .contains(variant1);
    }

    @Test
    @DisplayName("excluding a variant at one location leaves the other location offering it")
    void excludingAtOneLocationIsScoped() {
        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, LOCATION_1, false, "OUT_OF_STOCK_HERE", ACTOR);

        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_1))
                .contains(variant1);
        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_2))
                .as("the exclusion named LOCATION_1 only")
                .doesNotContain(variant1);
    }

    @Test
    @DisplayName("excluding an already-excluded variant is idempotent, not a duplicate row")
    void reExcludingIsIdempotent() {
        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, null, false, "SEASONAL", ACTOR);
        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, null, false, "SEASONAL", ACTOR);

        assertThat(jdbc.sql("SELECT count(*) FROM catalog.channel_offering_exclusions")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("including a variant back removes exactly the exclusion named, and only that one")
    void includingRemovesOnlyTheMatchingExclusion() {
        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, LOCATION_1, false, "OUT_OF_STOCK_HERE", ACTOR);
        authoring.setChannelOffering(TENANT, BRAND, channel, variant2, null, false, "SEASONAL", ACTOR);

        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, LOCATION_1, true, null, ACTOR);

        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_1))
                .as("variant1's location-scoped exclusion is gone; variant2's brand-wide one is untouched")
                .containsExactly(variant2);
    }

    @Test
    @DisplayName("including a variant that was never excluded changes nothing and does not throw")
    void includingAnAlreadyOfferedVariantIsANoOp() {
        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, null, true, null, ACTOR);

        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_1))
                .isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.channel_offering_exclusions")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("the mass-enable onboarding gesture excludes or includes many variants in one call")
    void bulkSetChannelOfferingChangesManyVariantsAtOnce() {
        int excludedCount = authoring.bulkSetChannelOffering(
                TENANT, BRAND, channel, java.util.List.of(variant1, variant2), null, false, "SEASONAL", ACTOR);
        assertThat(excludedCount).isEqualTo(2);
        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_1))
                .containsExactlyInAnyOrder(variant1, variant2);

        // The mass-enable itself: onboarding an aggregator turns everything
        // back on in one gesture rather than 600 individual clicks.
        int enabledCount = authoring.bulkSetChannelOffering(
                TENANT, BRAND, channel, java.util.List.of(variant1, variant2), null, true, null, ACTOR);
        assertThat(enabledCount).isEqualTo(2);
        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_1))
                .isEmpty();
    }

    @Test
    @DisplayName("a bulk call already in the requested state reports zero changed, not a false count")
    void bulkSetChannelOfferingReportsOnlyActualChanges() {
        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, null, false, "SEASONAL", ACTOR);

        int changed = authoring.bulkSetChannelOffering(
                TENANT, BRAND, channel, java.util.List.of(variant1, variant2), null, false, "SEASONAL", ACTOR);

        // variant1 was already excluded; only variant2 actually changed.
        assertThat(changed).isEqualTo(1);
    }

    @Test
    @DisplayName("excluding a variant on this tenant's channel never touches another tenant's exclusions")
    void tenantIsolationOnWrite() {
        UUID otherVariant = seedProductAndVariant(OTHER_TENANT, OTHER_BRAND, "OTHER_DISH");

        authoring.setChannelOffering(TENANT, BRAND, channel, variant1, null, false, "SEASONAL", ACTOR);

        Set<UUID> theirs = store.channelExclusionsAtLocation(OTHER_TENANT, OTHER_BRAND, otherTenantChannel, LOCATION_1);
        assertThat(theirs)
                .as("this tenant's exclusion must never appear under another tenant's own channel")
                .isEmpty();

        // And the negative direction: excluding under the other tenant's own
        // ids never lets that row surface under this tenant's read either.
        authoring.setChannelOffering(
                OTHER_TENANT, OTHER_BRAND, otherTenantChannel, otherVariant, null, false, "SEASONAL", ACTOR);
        assertThat(store.channelExclusionsAtLocation(TENANT, BRAND, channel, LOCATION_1))
                .as("this tenant's read must never see the other tenant's excluded variant id")
                .doesNotContain(otherVariant);
    }

    @Test
    @DisplayName("excluding a variant that belongs to a sibling brand under the same tenant is refused, not a raw 409")
    void crossBrandExclusionIsRefused() {
        assertThatThrownBy(() -> authoring.setChannelOffering(
                        TENANT, BRAND, channel, siblingBrandVariant, null, false, "SEASONAL", ACTOR))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);

        assertThat(store.channelExclusionsAtLocation(TENANT, SIBLING_BRAND, channel, LOCATION_1))
                .as("the write must not have landed under either brand")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "including a variant that belongs to a sibling brand under the same tenant is refused, not a silent no-op")
    void crossBrandInclusionIsRefused() {
        assertThatThrownBy(() -> authoring.setChannelOffering(
                        TENANT, BRAND, channel, siblingBrandVariant, null, true, null, ACTOR))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
    }

    @Test
    @DisplayName("bulk-excluding a mix that includes a sibling brand's variant is refused for the whole call")
    void crossBrandBulkExclusionIsRefused() {
        assertThatThrownBy(() -> authoring.bulkSetChannelOffering(
                        TENANT,
                        BRAND,
                        channel,
                        java.util.List.of(variant1, siblingBrandVariant),
                        null,
                        false,
                        "SEASONAL",
                        ACTOR))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
    }

    // ---------------------------------------------------------------- fixtures

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID id, UUID tenantId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void insertLocation(UUID id, UUID tenantId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private UUID insertChannel(UUID tenantId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, 'AGGREGATOR', :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
        return id;
    }

    private UUID seedProductAndVariant(UUID tenantId, UUID brandId, String code) {
        UUID productId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
        return variantId;
    }
}
