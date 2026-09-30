package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.order;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.api.MarketplaceBindingLookup;
import uz.horecaos.platform.ordering.application.OrderBoardReadModels;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyService;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessLevel;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessThresholds;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.PromiseBasis;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.Lateness;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.LatenessThreshold;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderBoardRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderListQuery;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The four secondary toggles of orders.md §2.4 that the board's query did not
 * read before wave 16: «Только опаздывающие», «С проблемой», «Требуется звонок»
 * and «Фискализация» (gap map row {@code 1.1c}).
 *
 * <p>Each is asserted with rows that differ in exactly the attribute under test
 * and an assertion naming both what must come back and what must not. The
 * lateness predicate, which is policy applied to a clock and therefore the one
 * that can be quietly wrong, is additionally held to the domain rule it restates:
 * the same orders are put through {@link OrderLatenessPolicy#evaluate} and the two
 * answers must be one set, so the toolbar's filter and a row's late tint cannot
 * disagree.
 */
class OrderBoardTogglesQueryTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f6f4e-5000-7000-8000-0000000000a2");
    private static final UUID CHILONZOR = UUID.fromString("018f6f4e-5000-7000-8000-0000000000a3");
    private static final UUID YUNUSOBOD = UUID.fromString("018f6f4e-5000-7000-8000-0000000000a4");
    private static final UUID SIBLING_BRAND = UUID.fromString("018f6f4e-5000-7000-8000-0000000000a5");
    private static final UUID SIBLING_BRANCH = UUID.fromString("018f6f4e-5000-7000-8000-0000000000a6");

    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-5000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-5000-7000-8000-0000000000b2");
    private static final UUID OTHER_BRANCH = UUID.fromString("018f6f4e-5000-7000-8000-0000000000b3");

    /** The instant every lateness case is judged at. */
    private static final Instant NOW = Instant.parse("2026-09-10T14:00:00Z");

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

        fixtures.tenant(TENANT, "toggles", BRAND, CHILONZOR);
        fixtures.location(TENANT, BRAND, YUNUSOBOD, "YUN", "Yunusobod");
        fixtures.brand(TENANT, "toggles", SIBLING_BRAND, SIBLING_BRANCH);
        fixtures.tenant(OTHER_TENANT, "toggles-other", OTHER_BRAND, OTHER_BRANCH);
    }

    // ---------------------------------------------------------- Требуется звонок

    @Test
    @DisplayName("«Требуется звонок» returns the orders still owed a call and no others")
    void theCallbackFilter() {
        UUID owed = insert(order("CB-1").callbackRequested(), CHILONZOR);
        UUID quiet = insert(order("CB-2"), CHILONZOR);
        UUID owedElsewhere = insert(order("CB-3").callbackRequested(), YUNUSOBOD);

        assertThat(idsOf(query(CHILONZOR).callbackRequested(true)))
                .containsExactly(owed)
                .doesNotContain(quiet, owedElsewhere);
        assertThat(idsOf(query().callbackRequested(true)))
                .as("across branches, the brand board's toggle finds both")
                .containsExactlyInAnyOrder(owed, owedElsewhere);
        assertThat(idsOf(query(CHILONZOR)))
                .as("off, the toggle filters nothing")
                .containsExactlyInAnyOrder(owed, quiet);
    }

    // -------------------------------------------------------------- С проблемой

    @Test
    @DisplayName("«С проблемой» returns orders with a process that needs an operator or is failing, and no others")
    void theProblemFilter() {
        UUID blocked = insert(order("PR-1"), CHILONZOR);
        UUID retrying = insert(order("PR-2"), CHILONZOR);
        UUID healthy = insert(order("PR-3"), CHILONZOR);
        UUID untouched = insert(order("PR-4"), CHILONZOR);
        fixtures.insertProcess(TENANT, blocked, "ORDER_INVENTORY", "MANUAL_ACTION_REQUIRED");
        fixtures.insertProcess(TENANT, retrying, "ORDER_PAYMENT", "FAILED_RETRYABLE");
        fixtures.insertProcess(TENANT, healthy, "ORDER_PAYMENT", "COMPLETED");

        assertThat(idsOf(query(CHILONZOR).problemOnly(true)))
                .containsExactlyInAnyOrder(blocked, retrying)
                .as("a finished process is not a problem, and an order with no process states has none")
                .doesNotContain(healthy, untouched);
    }

    @Test
    @DisplayName("an order with two failing processes is one row, not two")
    void theProblemFilterDoesNotDuplicateRows() {
        UUID both = insert(order("PR-5"), CHILONZOR);
        fixtures.insertProcess(TENANT, both, "ORDER_INVENTORY", "MANUAL_ACTION_REQUIRED");
        fixtures.insertProcess(TENANT, both, "ORDER_PAYMENT", "FAILED_RETRYABLE");

        assertThat(idsOf(query(CHILONZOR).problemOnly(true)))
                .as("the predicate is an EXISTS, not a join, so paging cannot repeat an order")
                .containsExactly(both);
    }

    // -------------------------------------------------------------- Фискализация

    @Test
    @DisplayName("«Фискализация» returns orders with a fiscal document in one of the given statuses")
    void theFiscalStatusFilter() {
        UUID failed = insert(order("FS-1"), CHILONZOR);
        UUID blocked = insert(order("FS-2"), CHILONZOR);
        UUID issued = insert(order("FS-3"), CHILONZOR);
        UUID notApplicable = insert(order("FS-4"), CHILONZOR);
        UUID noDocument = insert(order("FS-5"), CHILONZOR);
        fixtures.insertFiscalDocument(TENANT, failed, "FAILED");
        fixtures.insertFiscalDocument(TENANT, blocked, "BLOCKED");
        fixtures.insertFiscalDocument(TENANT, issued, "ISSUED");
        fixtures.insertFiscalDocument(TENANT, notApplicable, "NOT_APPLICABLE");

        assertThat(idsOf(query(CHILONZOR).fiscalStatuses("FAILED")))
                .containsExactly(failed)
                .doesNotContain(blocked, issued, notApplicable, noDocument);
        assertThat(idsOf(query(CHILONZOR).fiscalStatuses("FAILED", "BLOCKED")))
                .as("the needs-attention pair operators actually ask for")
                .containsExactlyInAnyOrder(failed, blocked);
        assertThat(idsOf(query(CHILONZOR).fiscalStatuses("ISSUED"))).containsExactly(issued);
        assertThat(idsOf(query(CHILONZOR)))
                .as("no fiscal filter, no fiscal predicate — an order with no document is still on the board")
                .contains(noDocument);
    }

    @Test
    @DisplayName("a fiscal document of another tenant never matches this tenant's board")
    void aFiscalDocumentDoesNotCrossTenants() {
        insert(order("FT-1"), CHILONZOR);
        UUID theirs = fixtures.insertOrder(order("FT-2").at(OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH));
        fixtures.insertFiscalDocument(OTHER_TENANT, theirs, "FAILED");

        assertThat(idsOf(query(CHILONZOR).fiscalStatuses("FAILED")))
                .as("the document exists, at another tenant (order " + theirs + "), and this board must not see it")
                .isEmpty();
    }

    // ------------------------------------------------------- Только опаздывающие

    /**
     * The whole lateness matrix, judged at {@link #NOW}. Two branches with
     * different policy, three fulfilment modes, promised and unpromised orders, a
     * grace period, and terminal orders that must never be flagged.
     */
    @Test
    @DisplayName("«Только опаздывающие» is the domain's lateness rule, applied in the database")
    void theLateFilterAgreesWithTheDomainRule() {
        Map<UUID, OrderLatenessPolicy> policies = policies();
        Map<String, UUID> ids = new java.util.LinkedHashMap<>();
        List<Spec> specs = List.of(
                new Spec("L01", CHILONZOR, "DELIVERY", "RECEIVED", NOW.minusSeconds(600), null),
                new Spec("L02", CHILONZOR, "DELIVERY", "PREPARING", NOW.plusSeconds(600), null),
                new Spec("L03", CHILONZOR, "DELIVERY", "READY", NOW, null),
                new Spec("L04", CHILONZOR, "PICKUP", "CONFIRMED", NOW.minusSeconds(300), null),
                new Spec("L05", CHILONZOR, "PICKUP", "CONFIRMED", NOW.minusSeconds(900), null),
                new Spec("L06", YUNUSOBOD, "DELIVERY", "PREPARING", NOW.minusSeconds(600), null),
                new Spec("L07", YUNUSOBOD, "DELIVERY", "PREPARING", NOW.minusSeconds(2400), null),
                new Spec("L08", CHILONZOR, "DELIVERY", "RECEIVED", null, NOW.minusSeconds(3600)),
                new Spec("L09", CHILONZOR, "DELIVERY", "RECEIVED", null, NOW.minusSeconds(600)),
                new Spec("L10", CHILONZOR, "PICKUP", "RECEIVED", null, NOW.minusSeconds(2400)),
                new Spec("L11", YUNUSOBOD, "DELIVERY", "RECEIVED", null, NOW.minusSeconds(3000)),
                new Spec("L12", CHILONZOR, "DELIVERY", "COMPLETED", NOW.minusSeconds(600), null),
                new Spec("L13", CHILONZOR, "DELIVERY", "CANCELLED", null, NOW.minusSeconds(10_800)),
                new Spec("L14", CHILONZOR, "DINE_IN", "PREPARING", NOW.minusSeconds(60), null),
                new Spec("L15", CHILONZOR, "DELIVERY", "PAYMENT_FAILED", NOW.minusSeconds(600), null));

        for (Spec spec : specs) {
            ids.put(spec.seed, insert(spec.toOrder(), spec.location));
        }
        // Late orders that belong to a sibling brand and to another tenant: a
        // predicate that lost its tenant/brand scope would return them.
        UUID siblingLate = fixtures.insertOrder(
                order("L90").at(TENANT, SIBLING_BRAND, SIBLING_BRANCH).promisedAt(NOW.minusSeconds(7200)));
        UUID strangerLate = fixtures.insertOrder(
                order("L91").at(OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH).promisedAt(NOW.minusSeconds(7200)));

        Set<String> expected = Set.of("L01", "L05", "L07", "L08", "L10", "L14");
        Set<String> byDomainRule = specs.stream()
                .filter(spec -> Objects.requireNonNull(policies.get(spec.location))
                                .evaluate(
                                        FulfillmentMode.valueOf(spec.mode),
                                        spec.promise(),
                                        OrderStatus.valueOf(spec.status),
                                        spec.createdAt(),
                                        NOW)
                        == LatenessLevel.LATE)
                .map(spec -> spec.seed)
                .collect(Collectors.toSet());
        assertThat(byDomainRule)
                .as("the explicit matrix above and OrderLatenessPolicy.evaluate are one rule — if this fails, "
                        + "the fixture or the domain moved, not the SQL")
                .isEqualTo(expected);

        List<UUID> late = idsOf(query().lateOnly(true).lateness(policies));

        assertThat(late)
                .as("exactly the orders the domain rule calls LATE, across both branches")
                .containsExactlyInAnyOrderElementsOf(
                        expected.stream().map(ids::get).toList());
        assertThat(late)
                .as("terminal orders are never late, whatever their history; other brands and tenants are unreachable")
                .doesNotContain(
                        ids.get("L12"), ids.get("L13"), ids.get("L15"), ids.get("L03"), siblingLate, strangerLate);
    }

    @Test
    @DisplayName("the late filter composes with the branch set and the other filters")
    void theLateFilterComposes() {
        Map<UUID, OrderLatenessPolicy> policies = policies();
        UUID lateAtChilonzor =
                insert(order("LC-1").promisedAt(NOW.minusSeconds(900)).mode("PICKUP"), CHILONZOR);
        UUID lateAtYunusobod =
                insert(order("LC-2").promisedAt(NOW.minusSeconds(7200)).mode("PICKUP"), YUNUSOBOD);
        UUID lateDelivery =
                insert(order("LC-3").promisedAt(NOW.minusSeconds(900)).mode("DELIVERY"), CHILONZOR);

        assertThat(idsOf(query(CHILONZOR).lateOnly(true).lateness(policies)))
                .containsExactlyInAnyOrder(lateAtChilonzor, lateDelivery)
                .doesNotContain(lateAtYunusobod);
        assertThat(idsOf(query().lateOnly(true).lateness(policies).fulfillmentMode("PICKUP")))
                .as("late and pickup, brand-wide")
                .containsExactlyInAnyOrder(lateAtChilonzor, lateAtYunusobod)
                .doesNotContain(lateDelivery);
    }

    @Test
    @DisplayName("a late-only query with no thresholds resolved is refused, not answered with an empty board")
    void aLateQueryWithoutThresholdsIsRefused() {
        OrderListQuery unresolved = query().lateOnly(true).build();

        assertThatThrownBy(() -> store.listForLocation(unresolved, null, null, 10))
                .as("an empty board reads as \"nothing is late\"; better to fail than to say it")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the toggles are part of the cursor's fingerprint, and the clock is not")
    void theFingerprintCoversTheTogglesButNotTheClock() {
        String plain = query().build().fingerprint();

        assertThat(List.of(
                        plain,
                        query().lateOnly(true).build().fingerprint(),
                        query().problemOnly(true).build().fingerprint(),
                        query().callbackRequested(true).build().fingerprint(),
                        query().fiscalStatuses("FAILED").build().fingerprint(),
                        query().fiscalStatuses("FAILED", "BLOCKED").build().fingerprint()))
                .doesNotHaveDuplicates();
        assertThat(query().fiscalStatuses("BLOCKED", "FAILED").build().fingerprint())
                .as("the fiscal set in the other order is the same query and the same cursor")
                .isEqualTo(query().fiscalStatuses("FAILED", "BLOCKED").build().fingerprint());
        assertThat(query().lateOnly(true).lateness(policies()).build().fingerprint())
                .as("resolving thresholds at some instant must not change which cursor a page carries")
                .isEqualTo(query().lateOnly(true).build().fingerprint());
    }

    // ---------------------------------------------------- resolving the thresholds

    @Test
    @DisplayName("the read model resolves policy for the named branches only, per fulfilment mode")
    void theReadModelResolvesTheNamedBranches() {
        OrderLatenessPolicyService policyService = mock(OrderLatenessPolicyService.class);
        when(policyService.resolve(TENANT, BRAND, CHILONZOR))
                .thenReturn(new OrderLatenessPolicyService.Effective(
                        new OrderLatenessPolicy(
                                new LatenessThresholds(300, 0, 2700),
                                new LatenessThresholds(300, 600, 1800),
                                new LatenessThresholds(300, 0, 2700)),
                        null,
                        0));
        OrderBoardReadModels readModels = readModels(policyService);

        OrderListQuery resolved =
                readModels.withLatenessResolved(query(CHILONZOR).lateOnly(true).build());

        Lateness lateness = Objects.requireNonNull(resolved.lateness());
        assertThat(lateness.now()).isEqualTo(NOW);
        assertThat(lateness.thresholds())
                .extracting(LatenessThreshold::mode, LatenessThreshold::lateAfterSeconds)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(FulfillmentMode.DELIVERY, 0),
                        org.assertj.core.groups.Tuple.tuple(FulfillmentMode.PICKUP, 600),
                        org.assertj.core.groups.Tuple.tuple(FulfillmentMode.DINE_IN, 0));
        verify(policyService, never()).resolve(TENANT, BRAND, YUNUSOBOD);
    }

    @Test
    @DisplayName("across the whole brand the read model resolves only branches that hold an open order")
    void theReadModelResolvesBranchesWithOpenOrders() {
        OrderLatenessPolicyService policyService = mock(OrderLatenessPolicyService.class);
        when(policyService.resolve(any(), any(), any()))
                .thenReturn(new OrderLatenessPolicyService.Effective(OrderLatenessPolicy.platformDefault(), null, 0));
        insert(order("RM-1").status("PREPARING"), CHILONZOR);
        insert(order("RM-2").status("COMPLETED"), YUNUSOBOD);
        fixtures.insertOrder(order("RM-3").at(TENANT, SIBLING_BRAND, SIBLING_BRANCH));

        OrderListQuery resolved = readModels(policyService)
                .withLatenessResolved(query().lateOnly(true).build());

        assertThat(Objects.requireNonNull(resolved.lateness()).thresholds())
                .extracting(LatenessThreshold::locationId)
                .as("Chilonzor has an order in play; Yunusobod has only a finished one and the sibling brand "
                        + "is not this brand's")
                .containsOnly(CHILONZOR);
        verify(policyService, never()).resolve(TENANT, BRAND, YUNUSOBOD);
        verify(policyService, never()).resolve(TENANT, SIBLING_BRAND, SIBLING_BRANCH);
    }

    @Test
    @DisplayName("a query that does not ask for the late filter is returned untouched")
    void anOrdinaryQueryIsNotResolved() {
        OrderLatenessPolicyService policyService = mock(OrderLatenessPolicyService.class);
        OrderListQuery ordinary = query(CHILONZOR).build();

        assertThat(readModels(policyService).withLatenessResolved(ordinary)).isSameAs(ordinary);
        verify(policyService, never()).resolve(any(), any(), any());
    }

    // ------------------------------------------------------------------ fixtures

    /** One lateness case: an order's branch, mode, status and either a promise or a creation time. */
    private record Spec(
            String seed,
            UUID location,
            String mode,
            String status,
            @Nullable Instant promisedAt,
            @Nullable Instant unpromisedCreatedAt) {

        OrderBoardFixtures.OrderSpec toOrder() {
            OrderBoardFixtures.OrderSpec spec =
                    order(seed).mode(mode).status(status).createdAt(createdAt());
            return promisedAt == null ? spec.unpromised() : spec.promisedAt(promisedAt);
        }

        Instant createdAt() {
            return promisedAt != null
                    ? promisedAt.minusSeconds(2100)
                    : Objects.requireNonNull(unpromisedCreatedAt, "an unpromised case needs a creation time");
        }

        OrderPromise promise() {
            return promisedAt == null
                    ? OrderPromise.notPromised()
                    : new OrderPromise(promisedAt, PromiseBasis.PREPARATION_BAND, 35, null);
        }
    }

    /** Chilonzor: platform-like, with a ten-minute grace on pickup. Yunusobod: thirty minutes of grace and a longer fallback. */
    private static Map<UUID, OrderLatenessPolicy> policies() {
        Map<UUID, OrderLatenessPolicy> policies = new java.util.HashMap<>();
        policies.put(
                CHILONZOR,
                new OrderLatenessPolicy(
                        new LatenessThresholds(300, 0, 2700),
                        new LatenessThresholds(300, 600, 1800),
                        new LatenessThresholds(300, 0, 2700)));
        LatenessThresholds relaxed = new LatenessThresholds(300, 1800, 3600);
        policies.put(YUNUSOBOD, new OrderLatenessPolicy(relaxed, relaxed, relaxed));
        return policies;
    }

    private OrderBoardReadModels readModels(OrderLatenessPolicyService policyService) {
        return new OrderBoardReadModels(
                store, policyService, mock(MarketplaceBindingLookup.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UUID insert(OrderBoardFixtures.OrderSpec spec, UUID locationId) {
        return fixtures.insertOrder(spec.at(TENANT, BRAND, locationId));
    }

    private static List<UUID> idsOf(List<OrderBoardRow> rows) {
        return rows.stream().map(row -> row.order().orderId()).toList();
    }

    private List<UUID> idsOf(Query query) {
        return idsOf(store.listForLocation(query.build(), null, null, 100));
    }

    private static Query query(UUID... locationIds) {
        return new Query(List.of(locationIds));
    }

    /** Keeps the twenty-argument query record out of every assertion above. */
    private static final class Query {
        private final List<UUID> locationIds;
        private @Nullable String fulfillmentMode;
        private boolean lateOnly;
        private boolean problemOnly;
        private boolean callbackRequested;
        private List<String> fiscalStatuses = List.of();
        private @Nullable Lateness lateness;

        Query(List<UUID> locationIds) {
            this.locationIds = locationIds;
        }

        Query fulfillmentMode(String value) {
            this.fulfillmentMode = value;
            return this;
        }

        Query lateOnly(boolean value) {
            this.lateOnly = value;
            return this;
        }

        Query problemOnly(boolean value) {
            this.problemOnly = value;
            return this;
        }

        Query callbackRequested(boolean value) {
            this.callbackRequested = value;
            return this;
        }

        Query fiscalStatuses(String... values) {
            this.fiscalStatuses = List.of(values);
            return this;
        }

        /** Resolves the thresholds the way {@code OrderBoardReadModels} would, from a fixed policy per branch. */
        Query lateness(Map<UUID, OrderLatenessPolicy> policies) {
            List<LatenessThreshold> thresholds = new ArrayList<>();
            for (Map.Entry<UUID, OrderLatenessPolicy> entry : policies.entrySet()) {
                for (FulfillmentMode mode : FulfillmentMode.values()) {
                    LatenessThresholds forMode = entry.getValue().forMode(mode);
                    thresholds.add(new LatenessThreshold(
                            entry.getKey(), mode, forMode.lateAfterSeconds(), forMode.noPromiseFallbackSeconds()));
                }
            }
            this.lateness = new Lateness(NOW, thresholds);
            return this;
        }

        OrderListQuery build() {
            return new OrderListQuery(
                    TENANT,
                    BRAND,
                    locationIds,
                    List.of(),
                    null,
                    null,
                    null,
                    fulfillmentMode,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    lateOnly,
                    problemOnly,
                    callbackRequested,
                    fiscalStatuses,
                    lateness);
        }
    }
}
