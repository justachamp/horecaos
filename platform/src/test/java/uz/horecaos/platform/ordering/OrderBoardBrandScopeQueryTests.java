package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.NOON;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.order;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.MarketplaceBindingUsage;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderBoardRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderListQuery;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The brand-scoped board is the branch board's statement with a wider set of
 * branches (gap map row {@code 1.1}, wave 16), and the aggregator-binding filter
 * beside the origin toggle (row {@code 1.1c}).
 *
 * <p>Every assertion names the orders that must come back <em>and</em> the ones
 * that must not, in a tenant that has a second branch, a second brand and a
 * second tenant — a location predicate that quietly stopped applying would return
 * a superset and pass an assertion that only listed the wanted rows.
 */
class OrderBoardBrandScopeQueryTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f6f4e-4000-7000-8000-0000000000a2");
    private static final UUID CHILONZOR = UUID.fromString("018f6f4e-4000-7000-8000-0000000000a3");
    private static final UUID YUNUSOBOD = UUID.fromString("018f6f4e-4000-7000-8000-0000000000a4");
    private static final UUID SIBLING_BRAND = UUID.fromString("018f6f4e-4000-7000-8000-0000000000a5");
    private static final UUID SIBLING_BRAND_BRANCH = UUID.fromString("018f6f4e-4000-7000-8000-0000000000a6");

    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_BRANCH = UUID.fromString("018f6f4e-4000-7000-8000-0000000000b3");

    private static final UUID WOLT_CHILONZOR = UUID.fromString("018f6f4e-4000-7000-8000-0000000000c1");
    private static final UUID YANDEX_BRAND_WIDE = UUID.fromString("018f6f4e-4000-7000-8000-0000000000c2");
    private static final UUID OTHER_TENANT_BINDING = UUID.fromString("018f6f4e-4000-7000-8000-0000000000c3");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcOrderStore store;
    private OrderBoardFixtures fixtures;

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
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        store = new JdbcOrderStore(jdbc);

        fixtures.tenant(TENANT, "brand-board", BRAND, CHILONZOR);
        fixtures.location(TENANT, BRAND, YUNUSOBOD, "YUN", "Yunusobod");
        fixtures.brand(TENANT, "brand-board", SIBLING_BRAND, SIBLING_BRAND_BRANCH);
        fixtures.tenant(OTHER_TENANT, "brand-board-other", OTHER_BRAND, OTHER_BRANCH);

        fixtures.marketplaceBinding(TENANT, WOLT_CHILONZOR, BRAND, CHILONZOR, "WOLT", "Wolt Chilonzor");
        fixtures.marketplaceBinding(TENANT, YANDEX_BRAND_WIDE, BRAND, null, "YANDEX_EDA", "Yandex Eda");
        fixtures.marketplaceBinding(OTHER_TENANT, OTHER_TENANT_BINDING, OTHER_BRAND, null, "WOLT", "Someone else");
    }

    // ------------------------------------------------------------ the branch set

    @Test
    @DisplayName("an empty branch set reads every branch of the brand, and nothing of any other brand or tenant")
    void aBrandWideQueryReadsEveryBranchOfTheBrandAndNothingElse() {
        UUID chilonzor = insert("A-1", TENANT, BRAND, CHILONZOR);
        UUID yunusobod = insert("A-2", TENANT, BRAND, YUNUSOBOD);
        UUID sibling = insert("A-3", TENANT, SIBLING_BRAND, SIBLING_BRAND_BRANCH);
        UUID stranger = insert("A-4", OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH);

        assertThat(idsOf(brandQuery()))
                .as("both of this brand's branches — the whole point of «Все филиалы»")
                .containsExactlyInAnyOrder(chilonzor, yunusobod)
                .as("but not the same tenant's other brand, and not another tenant at all")
                .doesNotContain(sibling, stranger);
    }

    @Test
    @DisplayName("a named branch set narrows the board to those branches")
    void aNamedBranchSetNarrowsTheBoard() {
        UUID chilonzor = insert("B-1", TENANT, BRAND, CHILONZOR);
        UUID yunusobod = insert("B-2", TENANT, BRAND, YUNUSOBOD);

        assertThat(idsOf(brandQuery(CHILONZOR))).containsExactly(chilonzor).doesNotContain(yunusobod);
        assertThat(idsOf(brandQuery(YUNUSOBOD))).containsExactly(yunusobod);
        assertThat(idsOf(brandQuery(CHILONZOR, YUNUSOBOD))).containsExactlyInAnyOrder(chilonzor, yunusobod);
    }

    @Test
    @DisplayName("a branch of another brand or tenant, named in the set, matches nothing")
    void aBranchThatIsNotThisBrandsMatchesNothing() {
        insert("C-1", TENANT, BRAND, CHILONZOR);
        UUID sibling = insert("C-2", TENANT, SIBLING_BRAND, SIBLING_BRAND_BRANCH);
        UUID stranger = insert("C-3", OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH);

        assertThat(idsOf(brandQuery(SIBLING_BRAND_BRANCH)))
                .as("the brand predicate is unconditional: naming the sibling brand's branch does not reach " + sibling)
                .isEmpty();
        assertThat(idsOf(brandQuery(OTHER_BRANCH)))
                .as("nor another tenant's (" + stranger + ") — and the answer is the empty page, not a "
                        + "distinguishable refusal")
                .isEmpty();
    }

    @Test
    @DisplayName("the branch query the console has always made still reads exactly one branch")
    void theBranchQueryStillNeverWidens() {
        UUID chilonzor = insert("D-1", TENANT, BRAND, CHILONZOR);
        UUID yunusobod = insert("D-2", TENANT, BRAND, YUNUSOBOD);

        OrderListQuery branch = new OrderListQuery(
                TENANT, BRAND, CHILONZOR, List.of(), null, null, null, null, null, null, null, null, null, null);

        assertThat(idsOf(store.listForLocation(branch, null, null, 50)))
                .containsExactly(chilonzor)
                .doesNotContain(yunusobod);
    }

    @Test
    @DisplayName("every other filter applies across branches exactly as it does within one")
    void theSameFiltersApplyAcrossBranches() {
        UUID pickupAtChilonzor = insertWith(order("E-1").mode("PICKUP"), CHILONZOR);
        UUID pickupAtYunusobod = insertWith(order("E-2").mode("PICKUP"), YUNUSOBOD);
        UUID deliveryAtYunusobod = insertWith(order("E-3").mode("DELIVERY"), YUNUSOBOD);

        OrderListQuery pickups = new OrderListQuery(
                TENANT, BRAND, List.of(), List.of(), null, null, null, "PICKUP", null, null, null, null, null, null,
                null, false, false, false, List.of(), null);

        assertThat(idsOf(store.listForLocation(pickups, null, null, 50)))
                .containsExactlyInAnyOrder(pickupAtChilonzor, pickupAtYunusobod)
                .doesNotContain(deliveryAtYunusobod);
    }

    // ---------------------------------------------------------------- the cursor

    @Test
    @DisplayName("paging across branches shows every order once, newest first, whichever branch it is at")
    void theCursorWalksAcrossBranches() {
        List<UUID> newestFirst = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            UUID location = index % 2 == 0 ? CHILONZOR : YUNUSOBOD;
            newestFirst.add(0, insertWith(order("F-" + index).createdAt(NOON.plusSeconds(60L * index)), location));
        }

        List<UUID> seen = new ArrayList<>();
        @Nullable UUID cursor = null;
        while (true) {
            var before = cursor == null
                    ? null
                    : store.boardOrderCursor(TENANT, BRAND, List.of(), cursor).orElseThrow();
            List<OrderBoardRow> page = store.listForLocation(brandQuery(), before, cursor, 3);
            if (page.isEmpty()) {
                break;
            }
            seen.addAll(idsOf(page));
            cursor = page.getLast().order().orderId();
        }

        assertThat(seen).containsExactlyElementsOf(newestFirst).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a cursor resolves inside the brand and the branch set, or not at all")
    void aCursorIsResolvedInsideTheCallersScope() {
        UUID chilonzor = insert("G-1", TENANT, BRAND, CHILONZOR);
        UUID sibling = insert("G-2", TENANT, SIBLING_BRAND, SIBLING_BRAND_BRANCH);
        UUID stranger = insert("G-3", OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH);

        assertThat(store.boardOrderCursor(TENANT, BRAND, List.of(), chilonzor)).isPresent();
        assertThat(store.boardOrderCursor(TENANT, BRAND, List.of(), sibling))
                .as("the same tenant's other brand")
                .isEmpty();
        assertThat(store.boardOrderCursor(TENANT, BRAND, List.of(), stranger))
                .as("another tenant")
                .isEmpty();
        assertThat(store.boardOrderCursor(TENANT, BRAND, List.of(YUNUSOBOD), chilonzor))
                .as("and the branch set is part of that scope: a cursor cannot walk into a branch the caller "
                        + "narrowed away from")
                .isEmpty();
    }

    @Test
    @DisplayName("the branch set is part of the cursor's fingerprint, in any order")
    void theFingerprintCoversTheBranchSet() {
        String wholeBrand = brandQuery().fingerprint();
        String one = brandQuery(CHILONZOR).fingerprint();
        String two = brandQuery(CHILONZOR, YUNUSOBOD).fingerprint();

        assertThat(List.of(wholeBrand, one, two)).doesNotHaveDuplicates();
        assertThat(brandQuery(YUNUSOBOD, CHILONZOR).fingerprint())
                .as("the same set named in the other order is the same query and the same cursor")
                .isEqualTo(two);
    }

    // ------------------------------------------------------- the binding filter

    @Test
    @DisplayName("the binding filter returns the orders of that one aggregator binding and no other")
    void theBindingFilterPicksOneAggregator() {
        UUID wolt = insertWith(order("H-1").marketplace(WOLT_CHILONZOR), CHILONZOR);
        UUID yandex = insertWith(order("H-2").marketplace(YANDEX_BRAND_WIDE), CHILONZOR);
        UUID yandexElsewhere = insertWith(order("H-3").marketplace(YANDEX_BRAND_WIDE), YUNUSOBOD);
        UUID native_ = insertWith(order("H-4"), CHILONZOR);

        assertThat(idsOf(bindingQuery(WOLT_CHILONZOR, CHILONZOR)))
                .containsExactly(wolt)
                .doesNotContain(yandex, yandexElsewhere, native_);
        assertThat(idsOf(bindingQuery(YANDEX_BRAND_WIDE)))
                .as("a brand-wide binding carries orders from several branches")
                .containsExactlyInAnyOrder(yandex, yandexElsewhere);
        assertThat(idsOf(bindingQuery(YANDEX_BRAND_WIDE, CHILONZOR)))
                .as("and narrowing the branch set narrows it")
                .containsExactly(yandex);
    }

    @Test
    @DisplayName("another tenant's binding id matches none of this tenant's orders")
    void aForeignBindingMatchesNothing() {
        insertWith(order("I-1").marketplace(WOLT_CHILONZOR), CHILONZOR);
        UUID theirs = fixtures.insertOrder(
                order("I-2").marketplace(OTHER_TENANT_BINDING).at(OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH));

        assertThat(idsOf(bindingQuery(OTHER_TENANT_BINDING)))
                .as("the tenant predicate is what keeps a guessed binding id from reading someone else's order "
                        + theirs)
                .isEmpty();
    }

    @Test
    @DisplayName("the binding is part of the cursor's fingerprint")
    void theFingerprintCoversTheBinding() {
        assertThat(bindingQuery(WOLT_CHILONZOR).fingerprint())
                .isNotEqualTo(brandQuery().fingerprint())
                .isNotEqualTo(bindingQuery(YANDEX_BRAND_WIDE).fingerprint());
    }

    // ------------------------------------------------------ the binding read model

    @Test
    @DisplayName("the read model counts each binding's orders, most recently used first")
    void theBindingUsageCountsAndOrders() {
        insertWith(order("J-1").marketplace(WOLT_CHILONZOR).createdAt(NOON), CHILONZOR);
        insertWith(order("J-2").marketplace(WOLT_CHILONZOR).createdAt(NOON.plusSeconds(60)), CHILONZOR);
        insertWith(order("J-3").marketplace(YANDEX_BRAND_WIDE).createdAt(NOON.plusSeconds(600)), YUNUSOBOD);
        insertWith(order("J-4"), CHILONZOR);

        List<MarketplaceBindingUsage> usage = store.marketplaceBindingUsage(TENANT, BRAND, List.of());

        assertThat(usage)
                .extracting(MarketplaceBindingUsage::bindingId)
                .as("Yandex was used last, so it leads; the tenant's own order is not an aggregator's")
                .containsExactly(YANDEX_BRAND_WIDE, WOLT_CHILONZOR);
        assertThat(usage).extracting(MarketplaceBindingUsage::orderCount).containsExactly(1L, 2L);
        assertThat(usage.get(1).lastOrderAt()).isEqualTo(NOON.plusSeconds(60));
    }

    @Test
    @DisplayName("the read model is scoped to the branch set, the brand and the tenant")
    void theBindingUsageIsScoped() {
        insertWith(order("K-1").marketplace(WOLT_CHILONZOR), CHILONZOR);
        insertWith(order("K-2").marketplace(YANDEX_BRAND_WIDE), YUNUSOBOD);
        fixtures.insertOrder(
                order("K-3").marketplace(OTHER_TENANT_BINDING).at(OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH));

        assertThat(store.marketplaceBindingUsage(TENANT, BRAND, List.of(CHILONZOR)))
                .extracting(MarketplaceBindingUsage::bindingId)
                .as("one branch's board offers only the bindings that branch has taken orders through")
                .containsExactly(WOLT_CHILONZOR);
        assertThat(store.marketplaceBindingUsage(TENANT, BRAND, List.of()))
                .extracting(MarketplaceBindingUsage::bindingId)
                .containsExactlyInAnyOrder(WOLT_CHILONZOR, YANDEX_BRAND_WIDE)
                .doesNotContain(OTHER_TENANT_BINDING);
        assertThat(store.marketplaceBindingUsage(TENANT, SIBLING_BRAND, List.of()))
                .as("a sibling brand that never took an aggregator order has none")
                .isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private UUID insert(String seed, UUID tenantId, UUID brandId, UUID locationId) {
        return fixtures.insertOrder(order(seed).at(tenantId, brandId, locationId));
    }

    private UUID insertWith(OrderBoardFixtures.OrderSpec spec, UUID locationId) {
        return fixtures.insertOrder(spec.at(TENANT, BRAND, locationId));
    }

    private static List<UUID> idsOf(List<OrderBoardRow> rows) {
        return rows.stream().map(row -> row.order().orderId()).toList();
    }

    private List<UUID> idsOf(OrderListQuery query) {
        return idsOf(store.listForLocation(query, null, null, 50));
    }

    /** The brand board's query with no other filter: the named branches, or every branch when none. */
    private static OrderListQuery brandQuery(UUID... locationIds) {
        return new OrderListQuery(
                TENANT,
                BRAND,
                List.of(locationIds),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                List.of(),
                null);
    }

    private static OrderListQuery bindingQuery(UUID bindingId, UUID... locationIds) {
        return new OrderListQuery(
                TENANT,
                BRAND,
                List.of(locationIds),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                bindingId,
                false,
                false,
                false,
                List.of(),
                null);
    }
}
