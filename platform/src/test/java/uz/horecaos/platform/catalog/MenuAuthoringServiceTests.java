package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
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
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.MenuAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.MenuRow;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Row 4.4a: the named {@code Menu} entity — creation, membership (single add
 * and the filtered select-all), copy, and binding to a branch.
 *
 * <p>See {@code JdbcMenuStore} and {@code V0389}/{@code V0390}'s own class
 * docs for why this is additive to ADR 0016 rather than a redesign of it;
 * {@code StorefrontCatalogQueryTests} proves the storefront read side (a
 * bound branch publishes from its menu; an unbound one is unaffected).
 */
class MenuAuthoringServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();

    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String ACTOR_SUBJECT = "menu-authoring-test";
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore catalogStore;
    private CatalogAuthoringService authoring;
    private JdbcMenuStore menuStore;
    private MenuAuthoringService menus;
    private UUID catalogId;
    private UUID channelId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the menu authoring tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.branch_menu_bindings, catalog.menu_items, catalog.menus, "
                        + "catalog.translations, catalog.category_products, catalog.categories, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        catalogStore = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                catalogStore,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());
        menuStore = new JdbcMenuStore(jdbc);
        SalesChannelLookup channels = new JdbcSalesChannelStore(jdbc);
        menus = new MenuAuthoringService(
                menuStore,
                catalogStore,
                channels,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                Clock.systemUTC());

        insertTenancy(TENANT, BRAND, LOCATION, "menu-authoring-tenant", "MAIN");
        insertLocation(TENANT, BRAND, OTHER_LOCATION, "SECOND");
        insertTenancy(OTHER_TENANT, OTHER_BRAND, UUID.randomUUID(), "menu-authoring-other-tenant", "OTHER-MAIN");
        channelId = insertChannel(TENANT, "STOREFRONT", "WEB");

        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
    }

    // ------------------------------------------------------------------ menus

    @Test
    @DisplayName("a created menu starts in DRAFT with no membership")
    void createdMenuStartsInDraft() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);

        assertThat(menu.status()).isEqualTo("DRAFT");
        assertThat(menu.version()).isEqualTo(1);
        assertThat(menus.listItems(TENANT, BRAND, menu.id())).isEmpty();
    }

    @Test
    @DisplayName("two menus sharing a name for one brand is refused")
    void aDuplicateNameIsRefused() {
        menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);

        Throwable failure = catchThrowable(() -> menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("another tenant's menus never appear in this tenant's list")
    void anotherTenantsMenusNeverAppear() {
        menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        menus.createMenu(OTHER_TENANT, OTHER_BRAND, "Main menu", ACTOR_SUBJECT);

        assertThat(menus.listMenus(TENANT, BRAND)).hasSize(1);
        assertThat(menus.listMenus(OTHER_TENANT, OTHER_BRAND)).hasSize(1);
    }

    @Test
    @DisplayName("another tenant's menu is not reachable by id, even knowing it")
    void anotherTenantsMenuIsNotReachableById() {
        MenuRow theirs = menus.createMenu(OTHER_TENANT, OTHER_BRAND, "Their menu", ACTOR_SUBJECT);

        assertThatThrownBy(() -> menus.requireMenu(TENANT, BRAND, theirs.id()))
                .isInstanceOf(MenuAuthoringService.UnknownMenuException.class);
    }

    @Test
    @DisplayName("renaming at the wrong version is refused rather than silently overwriting a concurrent change")
    void updatingAtTheWrongVersionIsRefused() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);

        Throwable failure = catchThrowable(() ->
                menus.updateMenu(TENANT, BRAND, menu.id(), "Renamed", "ACTIVE", menu.version() + 1, ACTOR_SUBJECT));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
    }

    @Test
    @DisplayName("renaming and activating a menu persists at a new version")
    void updatingPersists() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);

        MenuRow updated = menus.updateMenu(TENANT, BRAND, menu.id(), "Ramadan menu", "ACTIVE", 1, ACTOR_SUBJECT);

        assertThat(updated.name()).isEqualTo("Ramadan menu");
        assertThat(updated.status()).isEqualTo("ACTIVE");
        assertThat(updated.version()).isEqualTo(2);
    }

    // ------------------------------------------------------------- membership

    @Test
    @DisplayName("adding a variant already on the menu re-sorts and re-defaults it rather than duplicating")
    void addingAnAlreadyPresentVariantReSorts() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        var burger = createProduct("BURGER", "Burger");

        menus.addItem(TENANT, BRAND, menu.id(), burger.defaultVariantId(), 0, "AVAILABLE", ACTOR_SUBJECT);
        menus.addItem(TENANT, BRAND, menu.id(), burger.defaultVariantId(), 5, "UNAVAILABLE", ACTOR_SUBJECT);

        List<JdbcMenuStore.MenuItemRow> items = menus.listItems(TENANT, BRAND, menu.id());
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().sortOrder()).isEqualTo(5);
        assertThat(items.getFirst().availabilityDefault()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("adding a variant from another brand is refused, not a 500 from a raw constraint violation")
    void addingAnUnknownVariantIsRefused() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);

        assertThatThrownBy(
                        () -> menus.addItem(TENANT, BRAND, menu.id(), UUID.randomUUID(), 0, "AVAILABLE", ACTOR_SUBJECT))
                .isInstanceOf(MenuAuthoringService.UnknownVariantException.class);
    }

    @Test
    @DisplayName("removing a variant is idempotent")
    void removingIsIdempotent() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        var burger = createProduct("BURGER", "Burger");
        menus.addItem(TENANT, BRAND, menu.id(), burger.defaultVariantId(), 0, "AVAILABLE", ACTOR_SUBJECT);

        menus.removeItem(TENANT, BRAND, menu.id(), burger.defaultVariantId(), ACTOR_SUBJECT);
        menus.removeItem(TENANT, BRAND, menu.id(), burger.defaultVariantId(), ACTOR_SUBJECT);

        assertThat(menus.listItems(TENANT, BRAND, menu.id())).isEmpty();
    }

    @Test
    @DisplayName("filtered select-all adds every matching active variant in one command, and is safe to re-run")
    void filteredSelectAllAddsEveryMatch() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 0);
        UUID cold = authoring.createCategory(TENANT, BRAND, catalogId, null, "COLD", "Sovuq", LOCALE, 1);

        var soup = createProduct("SOUP", "Shurpa");
        authoring.placeProductInCategory(TENANT, BRAND, hot, soup.productId(), 0);
        var salad = createProduct("SALAD", "Salat");
        authoring.placeProductInCategory(TENANT, BRAND, cold, salad.productId(), 0);
        // Archived: filtered select-all only ever matches ACTIVE products/variants.
        var archived = createProduct("OLD", "Eski taom");
        authoring.setProductStatus(TENANT, BRAND, archived.productId(), Status.ARCHIVED);

        int added = menus.addByFilter(TENANT, BRAND, menu.id(), hot, null, "AVAILABLE", LOCALE, ACTOR_SUBJECT);

        assertThat(added).isEqualTo(1);
        assertThat(menus.listItems(TENANT, BRAND, menu.id()))
                .extracting(JdbcMenuStore.MenuItemRow::variantId)
                .containsExactly(soup.defaultVariantId());

        // Re-running the same filter re-defaults the already-present row rather
        // than adding a duplicate or erroring.
        int addedAgain = menus.addByFilter(TENANT, BRAND, menu.id(), hot, null, "UNAVAILABLE", LOCALE, ACTOR_SUBJECT);
        assertThat(addedAgain).isEqualTo(1);
        assertThat(menus.listItems(TENANT, BRAND, menu.id())).hasSize(1);
        assertThat(menus.listItems(TENANT, BRAND, menu.id()).getFirst().availabilityDefault())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("filtered select-all by search matches product name case-insensitively, not just category")
    void filteredSelectAllBySearchMatchesByName() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        var burger = createProduct("BURGER", "Cheeseburger");
        createProduct("PIZZA", "Margherita");

        int added = menus.addByFilter(TENANT, BRAND, menu.id(), null, "cheese", "AVAILABLE", LOCALE, ACTOR_SUBJECT);

        assertThat(added).isEqualTo(1);
        assertThat(menus.listItems(TENANT, BRAND, menu.id()))
                .extracting(JdbcMenuStore.MenuItemRow::variantId)
                .containsExactly(burger.defaultVariantId());
    }

    // -------------------------------------------------------------------- copy

    @Test
    @DisplayName("copying a menu produces a new menu with the same membership, independent of the source")
    void copyingProducesTheSameMembership() {
        MenuRow source = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        var burger = createProduct("BURGER", "Burger");
        var pizza = createProduct("PIZZA", "Pizza");
        menus.addItem(TENANT, BRAND, source.id(), burger.defaultVariantId(), 0, "AVAILABLE", ACTOR_SUBJECT);
        menus.addItem(TENANT, BRAND, source.id(), pizza.defaultVariantId(), 1, "UNAVAILABLE", ACTOR_SUBJECT);

        MenuRow copy = menus.copyMenu(TENANT, BRAND, source.id(), "Second branch menu", ACTOR_SUBJECT);

        assertThat(copy.id()).isNotEqualTo(source.id());
        assertThat(menus.listItems(TENANT, BRAND, copy.id()))
                .extracting(JdbcMenuStore.MenuItemRow::variantId, JdbcMenuStore.MenuItemRow::availabilityDefault)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(burger.defaultVariantId(), "AVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple(pizza.defaultVariantId(), "UNAVAILABLE"));

        // Independent: editing the copy does not touch the source.
        menus.removeItem(TENANT, BRAND, copy.id(), burger.defaultVariantId(), ACTOR_SUBJECT);
        assertThat(menus.listItems(TENANT, BRAND, source.id())).hasSize(2);
        assertThat(menus.listItems(TENANT, BRAND, copy.id())).hasSize(1);
    }

    // ------------------------------------------------------------------ binding

    @Test
    @DisplayName("binding a menu as a branch's default, then binding a second menu, replaces the first")
    void bindingReplacesTheDefault() {
        MenuRow first = menus.createMenu(TENANT, BRAND, "First menu", ACTOR_SUBJECT);
        MenuRow second = menus.createMenu(TENANT, BRAND, "Second menu", ACTOR_SUBJECT);

        menus.bindToBranch(TENANT, BRAND, LOCATION, null, first.id(), ACTOR_SUBJECT);
        menus.bindToBranch(TENANT, BRAND, LOCATION, null, second.id(), ACTOR_SUBJECT);

        List<JdbcMenuStore.BranchMenuBindingRow> bindings = menus.listBindings(TENANT, BRAND);
        assertThat(bindings).hasSize(1);
        assertThat(bindings.getFirst().menuId()).isEqualTo(second.id());
        assertThat(bindings.getFirst().channelId()).isNull();
    }

    @Test
    @DisplayName("a channel-specific binding coexists with the branch's default binding")
    void channelBindingCoexistsWithDefault() {
        MenuRow defaultMenu = menus.createMenu(TENANT, BRAND, "Default menu", ACTOR_SUBJECT);
        MenuRow deliveryMenu = menus.createMenu(TENANT, BRAND, "Delivery-only menu", ACTOR_SUBJECT);

        menus.bindToBranch(TENANT, BRAND, LOCATION, null, defaultMenu.id(), ACTOR_SUBJECT);
        menus.bindToBranch(TENANT, BRAND, LOCATION, channelId, deliveryMenu.id(), ACTOR_SUBJECT);

        List<JdbcMenuStore.BranchMenuBindingRow> bindings = menus.listBindings(TENANT, BRAND);
        assertThat(bindings).hasSize(2);
        assertThat(bindings)
                .extracting(JdbcMenuStore.BranchMenuBindingRow::menuId)
                .containsExactlyInAnyOrder(defaultMenu.id(), deliveryMenu.id());
    }

    @Test
    @DisplayName("binding an archived menu to a branch is refused")
    void bindingAnArchivedMenuIsRefused() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Retired menu", ACTOR_SUBJECT);
        menus.updateMenu(TENANT, BRAND, menu.id(), menu.name(), "ARCHIVED", 1, ACTOR_SUBJECT);

        Throwable failure =
                catchThrowable(() -> menus.bindToBranch(TENANT, BRAND, LOCATION, null, menu.id(), ACTOR_SUBJECT));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
    }

    @Test
    @DisplayName("unbinding is idempotent and leaves the branch's other scope untouched")
    void unbindingIsIdempotentAndScoped() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        menus.bindToBranch(TENANT, BRAND, LOCATION, null, menu.id(), ACTOR_SUBJECT);
        menus.bindToBranch(TENANT, BRAND, LOCATION, channelId, menu.id(), ACTOR_SUBJECT);

        menus.unbindBranch(TENANT, BRAND, LOCATION, channelId, ACTOR_SUBJECT);
        menus.unbindBranch(TENANT, BRAND, LOCATION, channelId, ACTOR_SUBJECT);

        List<JdbcMenuStore.BranchMenuBindingRow> bindings = menus.listBindings(TENANT, BRAND);
        assertThat(bindings).hasSize(1);
        assertThat(bindings.getFirst().channelId()).isNull();
    }

    @Test
    @DisplayName("a bind and an unbind each record an audit fact; a no-op unbind records nothing")
    void bindAndUnbindAreAudited() {
        MenuRow menu = menus.createMenu(TENANT, BRAND, "Main menu", ACTOR_SUBJECT);
        List<AuditFact> audited = new java.util.ArrayList<>();
        MenuAuthoringService capturing = new MenuAuthoringService(
                menuStore, catalogStore, new JdbcSalesChannelStore(jdbc), audited::add, Clock.systemUTC());

        capturing.bindToBranch(TENANT, BRAND, LOCATION, null, menu.id(), ACTOR_SUBJECT);
        assertThat(audited).extracting(AuditFact::actionCode).contains("catalog.menu.bound");

        audited.clear();
        capturing.unbindBranch(TENANT, BRAND, LOCATION, null, ACTOR_SUBJECT);
        assertThat(audited).extracting(AuditFact::actionCode).contains("catalog.menu.unbound");

        audited.clear();
        capturing.unbindBranch(TENANT, BRAND, LOCATION, null, ACTOR_SUBJECT);
        assertThat(audited).isEmpty();
    }

    // ------------------------------------------------------------------------ fixture

    private CatalogAuthoringService.ProductCreated createProduct(String code, String name) {
        return authoring.createProduct(
                TENANT, BRAND, catalogId, code, name, null, LOCALE, "SKU-" + code, "PIECE", UNCLASSIFIED, ACTOR);
    }

    private void insertTenancy(UUID tenantId, UUID brandId, UUID locationId, String tenantSlug, String brandCode) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", tenantSlug).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", brandCode)
                .param("slug", brandCode.toLowerCase(Locale.ROOT))
                .update();

        insertLocation(tenantId, brandId, locationId, brandCode);
    }

    private void insertLocation(UUID tenantId, UUID brandId, UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param(
                        "slug",
                        code.toLowerCase(Locale.ROOT) + "-"
                                + locationId.toString().substring(0, 8))
                .update();
    }

    private UUID insertChannel(UUID tenantId, String code, String systemType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (
                    id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("systemType", systemType)
                .update();
        return id;
    }
}
