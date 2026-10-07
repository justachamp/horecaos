package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup;
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup.VariantAvailability;
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.catalog.api.MenuSearchPort;
import uz.horecaos.platform.catalog.api.MenuSearchPort.MenuSearchResult;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontMenuSearch;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0069's "a stated price must be a price the platform would actually charge
 * on that channel at that location", at the catalog end: {@link MenuSearchPort}
 * is the storefront's own assembled menu searched by name, and these tests prove
 * it adds nothing and decides nothing -- the price it returns is the one {@link
 * MenuPriceLookup} gave for that channel and that location, an unpriced variant
 * stays unpriced, and a dish that is not on the channel's menu is not found.
 */
class StorefrontMenuSearchTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private CatalogAuthoringService authoring;
    private CatalogPublicationService publication;
    private MenuSearchPort search;
    private final Map<UUID, Long> prices = new HashMap<>();
    private final List<String> channelsPriced = new ArrayList<>();
    private final List<UUID> locationsPriced = new ArrayList<>();
    private final Map<UUID, VariantAvailability> availability = new HashMap<>();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for catalog search tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.media_relations, catalog.translations, "
                        + "catalog.product_modifier_groups, catalog.variant_modifier_groups, "
                        + "catalog.category_products, catalog.catalog_products, catalog.modifier_options, "
                        + "catalog.modifier_groups, catalog.categories, catalog.fiscal_classifications, "
                        + "catalog.fees, catalog.branch_menu_bindings, catalog.menu_items, catalog.menus, "
                        + "catalog.variants, "
                        + "catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();
        prices.clear();
        channelsPriced.clear();
        locationsPriced.clear();
        availability.clear();

        JdbcCatalogStore store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());
        CatalogSnapshotLoader loader = new CatalogSnapshotLoader(
                store, (tenantId, assetIds) -> true, (tenantId, brandId, variantIds) -> variantIds, LOCALE);
        publication = new CatalogPublicationService(
                store,
                new CatalogValidator(),
                loader,
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC),
                AuditTrail.discarding());

        MenuPriceLookup priceLookup = (tenantId, brandId, locationId, channel, variantIds, optionIds) -> {
            channelsPriced.add(channel);
            locationsPriced.add(locationId);
            Map<UUID, Long> known = new HashMap<>();
            variantIds.forEach(id -> {
                if (prices.containsKey(id)) {
                    known.put(id, prices.get(id));
                }
            });
            return Optional.of(new MenuPriceLookup.MenuPrices("UZS", known, Map.of()));
        };
        MenuAvailabilityLookup availabilityLookup =
                (tenantId, brandId, locationId, channel, variantIds) -> availability;
        StorefrontCatalogQuery storefront = new StorefrontCatalogQuery(
                store,
                priceLookup,
                availabilityLookup,
                new JdbcMenuStore(jdbc),
                new uz.horecaos.platform.catalog.infrastructure.tenancy.JdbcCatalogTenantContext(jdbc),
                Clock.systemUTC(),
                new JdbcCommentPresetStore(jdbc));
        search = new StorefrontMenuSearch(storefront);
    }

    private UUID publishedDish(String name, long priceMinor, String channel) {
        UUID catalogId = authoring.createCatalog(
                TENANT,
                BRAND,
                "M" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "Menu",
                LOCALE);
        var dish = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "D" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                name,
                null,
                LOCALE,
                null,
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, dish.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        prices.put(dish.defaultVariantId(), priceMinor);
        publication.publish(TENANT, BRAND, catalogId, channel, null);
        return dish.defaultVariantId();
    }

    private MenuSearchResult find(String channel, String... terms) {
        return search.search(TENANT, BRAND, LOCATION, channel, List.of(LOCALE), List.of(terms), 5);
    }

    @Test
    @DisplayName(
            "a Latin question finds a Cyrillic dish, and the price returned is exactly the one the price lookup gave for this channel and branch")
    void theSearchReturnsThePriceLookupsPrice() {
        UUID variant = publishedDish("Плов самаркандский", 45_000, "STOREFRONT");

        MenuSearchResult found = find("STOREFRONT", "plov");

        assertThat(found.menuPublished()).isTrue();
        assertThat(found.currency()).isEqualTo("UZS");
        assertThat(found.hits()).singleElement().satisfies(dish -> {
            assertThat(dish.name()).isEqualTo("Плов самаркандский");
            assertThat(dish.forms()).singleElement().satisfies(form -> {
                assertThat(form.variantId()).isEqualTo(variant);
                assertThat(form.amountMinor()).isEqualTo(45_000L);
                assertThat(form.orderable()).isTrue();
            });
        });
        assertThat(channelsPriced)
                .as("priced on the caller's own channel, not a literal")
                .containsOnly("STOREFRONT");
        assertThat(locationsPriced).containsOnly(LOCATION);
    }

    @Test
    @DisplayName("when the price changes, the next search says so: nothing is remembered")
    void thePriceIsNotRemembered() {
        UUID variant = publishedDish("Plov", 45_000, "STOREFRONT");
        assertThat(find("STOREFRONT", "plov")
                        .hits()
                        .getFirst()
                        .forms()
                        .getFirst()
                        .amountMinor())
                .isEqualTo(45_000L);

        prices.put(variant, 52_000L);

        assertThat(find("STOREFRONT", "plov")
                        .hits()
                        .getFirst()
                        .forms()
                        .getFirst()
                        .amountMinor())
                .isEqualTo(52_000L);
    }

    @Test
    @DisplayName("a variant the price lookup has no price for stays unpriced: null, never zero")
    void anUnpricedVariantIsNull() {
        UUID variant = publishedDish("Plov", 45_000, "STOREFRONT");
        prices.remove(variant);

        assertThat(find("STOREFRONT", "plov")
                        .hits()
                        .getFirst()
                        .forms()
                        .getFirst()
                        .amountMinor())
                .isNull();
    }

    @Test
    @DisplayName("a dish published only to another channel is not on this channel's menu, and is not found")
    void aChannelOnlyFindsItsOwnMenu() {
        publishedDish("Kiosk Burger", 30_000, "KIOSK");
        publishedDish("Plov", 45_000, "STOREFRONT");

        assertThat(find("STOREFRONT", "burger").hits()).isEmpty();
        assertThat(find("KIOSK", "burger").hits()).hasSize(1);
    }

    @Test
    @DisplayName("a dish the branch does not offer is not found at that branch")
    void aBranchOnlyFindsWhatItOffers() {
        publishedDish("Plov", 45_000, "STOREFRONT");

        assertThat(search.search(TENANT, BRAND, OTHER_LOCATION, "STOREFRONT", List.of(LOCALE), List.of("plov"), 5)
                        .hits())
                .isEmpty();
    }

    @Test
    @DisplayName("a sold-out dish is found and reported not orderable, from the storefront's own inventory decision")
    void soldOutIsReported() {
        UUID variant = publishedDish("Plov", 45_000, "STOREFRONT");
        availability.put(variant, new VariantAvailability(false, null));

        assertThat(find("STOREFRONT", "plov")
                        .hits()
                        .getFirst()
                        .forms()
                        .getFirst()
                        .orderable())
                .isFalse();
    }

    @Test
    @DisplayName("with no live publication on the channel the answer is 'no menu', not 'no such dish'")
    void noMenuIsNotNoMatch() {
        assertThat(find("STOREFRONT", "plov").menuPublished()).isFalse();
        assertThat(find("STOREFRONT", "plov").hits()).isEmpty();
    }

    @Test
    @DisplayName("no terms, or a zero limit, match nothing rather than the whole menu")
    void emptyQueriesMatchNothing() {
        publishedDish("Plov", 45_000, "STOREFRONT");

        assertThat(search.search(TENANT, BRAND, LOCATION, "STOREFRONT", List.of(LOCALE), List.of(), 5)
                        .hits())
                .isEmpty();
        assertThat(search.search(TENANT, BRAND, LOCATION, "STOREFRONT", List.of(LOCALE), List.of("plov"), 0)
                        .hits())
                .isEmpty();
    }

    @Test
    @DisplayName("another tenant's menu is never searched: the tenant is part of every read")
    void anotherTenantFindsNothing() {
        publishedDish("Plov", 45_000, "STOREFRONT");

        assertThat(search.search(UUID.randomUUID(), BRAND, LOCATION, "STOREFRONT", List.of(LOCALE), List.of("plov"), 5)
                        .hits())
                .isEmpty();
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'catalog-search-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", "L" + location.toString().substring(0, 8).toUpperCase())
                    .param("slug", "loc-" + location.toString().substring(0, 12))
                    .update();
        }
        for (String[] channel : new String[][] {{"STOREFRONT", "WEB"}, {"KIOSK", "KIOSK"}}) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                    VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("code", channel[0])
                    .param("systemType", channel[1])
                    .update();
        }
    }
}
