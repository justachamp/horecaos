package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.order;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The order board across a brand's branches (gap map row {@code 1.1}, the
 * «Все филиалы» mode) and the filters wave 16 added to both boards, over HTTP:
 * who may ask, what they see, and that the answer never reaches a branch,
 * brand or tenant they were not given.
 *
 * <p>Query semantics — every filter against the rows it must and must not
 * return — belong to {@code OrderBoardBrandScopeQueryTests} and {@code
 * OrderBoardTogglesQueryTests}, which own a fixture for it. This suite proves the
 * wire: capability scopes, the shared validation, cursors that survive a hop
 * across branches, the per-row {@code actions[]}, and the binding read model's
 * names.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsBrandOrderBoardHttpTests {

    private static final UUID TENANT = UUID.fromString("018fa112-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fa112-4000-7000-8000-0000000000b1");
    private static final UUID CHILONZOR = UUID.fromString("018fa112-4000-7000-8000-0000000000c1");
    private static final UUID YUNUSOBOD = UUID.fromString("018fa112-4000-7000-8000-0000000000c2");
    private static final UUID SIBLING_BRAND = UUID.fromString("018fa112-4000-7000-8000-0000000000b2");
    private static final UUID SIBLING_BRANCH = UUID.fromString("018fa112-4000-7000-8000-0000000000c3");

    private static final UUID OTHER_TENANT = UUID.fromString("018fa112-4000-7000-8000-0000000000a9");
    private static final UUID OTHER_BRAND = UUID.fromString("018fa112-4000-7000-8000-0000000000b9");
    private static final UUID OTHER_BRANCH = UUID.fromString("018fa112-4000-7000-8000-0000000000c9");

    private static final UUID WOLT_BINDING = UUID.fromString("018fa112-4000-7000-8000-0000000000d1");
    private static final UUID YANDEX_BINDING = UUID.fromString("018fa112-4000-7000-8000-0000000000d2");

    private static final String BRAND_SUPERVISOR = "brand-board-supervisor";
    private static final String BRANCH_MANAGER = "brand-board-branch-manager";
    private static final String FINANCE = "brand-board-finance";
    /** Reads the whole brand, but may act at one branch only — the case a per-branch actions[] exists for. */
    private static final String MIXED = "brand-board-mixed";

    private static final String STRANGER = "brand-board-stranger";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BRAND_BOARD =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/orders/board";
    private static final String BRAND_BINDINGS =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/orders/marketplace-bindings";
    private static final String CHILONZOR_BOARD =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + CHILONZOR + "/orders/board";
    private static final String YUNUSOBOD_BOARD =
            "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + YUNUSOBOD + "/orders/board";
    private static final String CHILONZOR_BINDINGS = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
            + CHILONZOR + "/orders/marketplace-bindings";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the brand order board endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    /**
     * The detail read names an order's creator through the tenant's staff
     * directory (ADR 0139), which this suite stubs; the board rows it mostly
     * reads name no one.
     */
    @MockitoBean
    @SuppressWarnings("NullAway")
    private StaffDirectory staffDirectory;

    private OrderBoardFixtures fixtures;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        roleRegistry.synchronize();

        fixtures.tenant(TENANT, "brand-board-http", BRAND, CHILONZOR);
        fixtures.location(TENANT, BRAND, YUNUSOBOD, "YUN", "Yunusobod");
        fixtures.brand(TENANT, "brand-board-http", SIBLING_BRAND, SIBLING_BRANCH);
        fixtures.tenant(OTHER_TENANT, "brand-board-http-other", OTHER_BRAND, OTHER_BRANCH);
        fixtures.marketplaceBinding(TENANT, WOLT_BINDING, BRAND, CHILONZOR, "WOLT", "Wolt Chilonzor");
        fixtures.marketplaceBinding(TENANT, YANDEX_BINDING, BRAND, null, "YANDEX_EDA", "Yandex Eda");

        grant(BRAND_SUPERVISOR, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(BRANCH_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", CHILONZOR);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
        grant(MIXED, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(MIXED, PlatformRole.LOCATION_MANAGER, "LOCATION", CHILONZOR);
        grant(STRANGER, PlatformRole.BRAND_MANAGER, "BRAND", OTHER_BRAND, OTHER_TENANT);
    }

    // ---------------------------------------------------------------- who may ask

    @Test
    @DisplayName("a brand-scoped principal reads every branch of the brand in one request, each row naming its branch")
    void aBrandSupervisorReadsEveryBranch() throws Exception {
        UUID chilonzor = insert("HT-1", CHILONZOR);
        UUID yunusobod = insert("HT-2", YUNUSOBOD);
        UUID sibling = fixtures.insertOrder(order("HT-3").at(TENANT, SIBLING_BRAND, SIBLING_BRANCH));
        UUID stranger = fixtures.insertOrder(order("HT-4").at(OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH));

        JsonNode body = ok(get(BRAND_BOARD).with(tokenFor(BRAND_SUPERVISOR)));

        assertThat(itemIds(body))
                .containsExactlyInAnyOrder(chilonzor, yunusobod)
                .doesNotContain(sibling, stranger);
        assertThat(locationsByOrder(body))
                .as("the Филиал column's key travels on every row")
                .containsEntry(chilonzor, CHILONZOR)
                .containsEntry(yunusobod, YUNUSOBOD);
    }

    @Test
    @DisplayName(
            "a branch manager is refused the brand board, told which capability — and keeps their own branch board")
    void aBranchManagerKeepsTheSingleBranchBoard() throws Exception {
        UUID mine = insert("HT-5", CHILONZOR);
        insert("HT-6", YUNUSOBOD);

        MvcResult refused =
                mvc.perform(get(BRAND_BOARD).with(tokenFor(BRANCH_MANAGER))).andReturn();
        assertThat(refused.getResponse().getStatus())
                .as("ORDER_READ at LOCATION is not satisfied for a BRAND-scoped read — scopes never cover upwards")
                .isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_READ.code());

        assertThat(itemIds(ok(get(CHILONZOR_BOARD).with(tokenFor(BRANCH_MANAGER)))))
                .as("the refusal is a fallback, not a wall: their own branch still answers, with only its orders")
                .containsExactly(mine);
        assertThat(mvc.perform(get(YUNUSOBOD_BOARD).with(tokenFor(BRANCH_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("and a neighbouring branch stays refused")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("another tenant's supervisor cannot read this brand's board or its binding options")
    void aStrangerReadsNothing() throws Exception {
        insert("HT-7", CHILONZOR);

        for (String path : List.of(BRAND_BOARD, BRAND_BINDINGS, CHILONZOR_BOARD, CHILONZOR_BINDINGS)) {
            MvcResult refused = mvc.perform(get(path).with(tokenFor(STRANGER))).andReturn();
            assertThat(refused.getResponse().getStatus()).as(path).isEqualTo(403);
        }
    }

    // ------------------------------------------------------------- the branch set

    @Test
    @DisplayName("repeating locationId narrows the brand board to those branches")
    void theBranchFilterNarrowsTheBoard() throws Exception {
        UUID chilonzor = insert("HT-8", CHILONZOR);
        UUID yunusobod = insert("HT-9", YUNUSOBOD);

        assertThat(itemIds(ok(get(BRAND_BOARD)
                        .with(tokenFor(BRAND_SUPERVISOR))
                        .queryParam("locationId", YUNUSOBOD.toString()))))
                .containsExactly(yunusobod);
        assertThat(itemIds(ok(get(BRAND_BOARD)
                        .with(tokenFor(BRAND_SUPERVISOR))
                        .queryParam("locationId", CHILONZOR.toString(), YUNUSOBOD.toString()))))
                .containsExactlyInAnyOrder(chilonzor, yunusobod);
        assertThat(itemIds(ok(get(BRAND_BOARD)
                        .with(tokenFor(BRAND_SUPERVISOR))
                        .queryParam("locationId", SIBLING_BRANCH.toString()))))
                .as("naming another brand's branch reaches nothing of it")
                .isEmpty();
    }

    // ---------------------------------------------------------------- validation

    @Test
    @DisplayName("the brand board refuses the same typos the branch board does, and the new filters' own")
    void bothBoardsShareTheValidation() throws Exception {
        for (String path : List.of(BRAND_BOARD, CHILONZOR_BOARD)) {
            String actor = path.equals(BRAND_BOARD) ? BRAND_SUPERVISOR : BRANCH_MANAGER;
            for (Map.Entry<String, String> bad : Map.of(
                            "fulfillmentMode", "TELEPORT",
                            "origin", "ALIEN",
                            "paymentStatus", "MAYBE",
                            "fiscalStatus", "NOPE",
                            "status", "NOT_A_STATUS")
                    .entrySet()) {
                MvcResult refused = mvc.perform(
                                get(path).with(tokenFor(actor)).queryParam(bad.getKey(), bad.getValue()))
                        .andReturn();
                assertThat(refused.getResponse().getStatus())
                        .as("%s ?%s=%s", path, bad.getKey(), bad.getValue())
                        .isEqualTo(400);
                assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
            }
        }
    }

    // -------------------------------------------------------------- the filters

    @Test
    @DisplayName("the four toggles and the binding filter reach the brand board through HTTP")
    void theTogglesAndBindingFilterWork() throws Exception {
        Instant now = Instant.now();
        UUID late = insert("HT-10", CHILONZOR, now.minus(Duration.ofHours(3)));
        UUID onTime = insert("HT-11", CHILONZOR, now.minus(Duration.ofMinutes(5)));
        UUID problem = insert("HT-12", YUNUSOBOD, now.minus(Duration.ofMinutes(5)));
        fixtures.insertProcess(TENANT, problem, "ORDER_INVENTORY", "MANUAL_ACTION_REQUIRED");
        UUID callback = fixtures.insertOrder(order("HT-13")
                .at(TENANT, BRAND, YUNUSOBOD)
                .createdAt(now.minus(Duration.ofMinutes(5)))
                .promisedAt(now.plus(Duration.ofMinutes(30)))
                .callbackRequested());
        UUID fiscal = insert("HT-14", CHILONZOR, now.minus(Duration.ofMinutes(5)));
        fixtures.insertFiscalDocument(TENANT, fiscal, "FAILED");
        UUID wolt = fixtures.insertOrder(order("HT-15")
                .at(TENANT, BRAND, CHILONZOR)
                .marketplace(WOLT_BINDING)
                .createdAt(now.minus(Duration.ofMinutes(5)))
                .promisedAt(now.plus(Duration.ofMinutes(30))));

        assertThat(itemIds(ok(board("late", "true"))))
                .as("only the order past its promise; the rest are on time")
                .containsExactly(late)
                .doesNotContain(onTime, problem, callback, fiscal, wolt);
        assertThat(itemIds(ok(board("problem", "true")))).containsExactly(problem);
        assertThat(itemIds(ok(board("callbackRequested", "true")))).containsExactly(callback);
        assertThat(itemIds(ok(board("fiscalStatus", "FAILED", "BLOCKED")))).containsExactly(fiscal);
        assertThat(itemIds(ok(board("marketplaceBindingId", WOLT_BINDING.toString()))))
                .containsExactly(wolt);
        assertThat(itemIds(ok(board("marketplaceBindingId", YANDEX_BINDING.toString()))))
                .as("a binding no order arrived through")
                .isEmpty();
    }

    // -------------------------------------------------------------------- paging

    @Test
    @DisplayName("a cursor walks the brand board across branches, and is refused under a different filter set")
    void theCursorSurvivesAHopAcrossBranchesAndIsPinnedToItsFilters() throws Exception {
        List<UUID> newestFirst = new ArrayList<>();
        Instant base = Instant.now().minus(Duration.ofHours(1));
        for (int index = 0; index < 5; index++) {
            UUID location = index % 2 == 0 ? CHILONZOR : YUNUSOBOD;
            newestFirst.add(0, insert("HT-P" + index, location, base.plusSeconds(60L * index)));
        }

        List<UUID> seen = new ArrayList<>();
        @Nullable String cursor = null;
        @Nullable String firstCursor = null;
        do {
            MockHttpServletRequestBuilder request =
                    get(BRAND_BOARD).with(tokenFor(BRAND_SUPERVISOR)).queryParam("limit", "2");
            if (cursor != null) {
                request.queryParam("cursor", cursor);
            }
            JsonNode page = ok(request);
            seen.addAll(itemIds(page));
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asString();
            if (firstCursor == null) {
                firstCursor = cursor;
            }
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(newestFirst).doesNotHaveDuplicates();

        MvcResult stale = mvc.perform(get(BRAND_BOARD)
                        .with(tokenFor(BRAND_SUPERVISOR))
                        .queryParam("limit", "2")
                        .queryParam("fulfillmentMode", "PICKUP")
                        .queryParam("cursor", Objects.requireNonNull(firstCursor)))
                .andReturn();
        assertThat(stale.getResponse().getStatus())
                .as("a window cut for one filter set says nothing about another")
                .isEqualTo(400);
    }

    // --------------------------------------------------------------- actions[]

    @Test
    @DisplayName(
            "«Выставить счёт» is offered only for an unpaid online order, and only to a principal who can issue it")
    void issueInvoiceIsOfferedWhereTheEndpointCanSucceed() throws Exception {
        UUID unpaidOnline = fixtures.insertOrder(order("HT-I1")
                .at(TENANT, BRAND, CHILONZOR)
                .status("PAYMENT_AUTHORIZING")
                .paymentStatusProjection("PENDING"));
        fixtures.insertProviderIntent(TENANT, BRAND, CHILONZOR, unpaidOnline, "PENDING");
        UUID cash = insert("HT-I2", CHILONZOR);
        UUID paid =
                fixtures.insertOrder(order("HT-I3").at(TENANT, BRAND, CHILONZOR).paymentStatusProjection("CAPTURED"));
        UUID endedUnpaid = fixtures.insertOrder(order("HT-I4")
                .at(TENANT, BRAND, YUNUSOBOD)
                .status("PAYMENT_FAILED")
                .paymentStatusProjection("PENDING"));
        fixtures.insertProviderIntent(TENANT, BRAND, YUNUSOBOD, endedUnpaid, "PENDING");

        Map<UUID, Set<String>> asFinance = actionsByOrder(ok(get(BRAND_BOARD).with(tokenFor(FINANCE))));
        assertThat(asFinance.get(unpaidOnline)).contains("ISSUE_INVOICE");
        assertThat(asFinance.get(cash)).as("cash has no checkout surface").doesNotContain("ISSUE_INVOICE");
        assertThat(asFinance.get(paid)).as("already paid").doesNotContain("ISSUE_INVOICE");
        assertThat(asFinance.get(endedUnpaid)).as("the order has ended").doesNotContain("ISSUE_INVOICE");

        Map<UUID, Set<String>> asSupervisor = actionsByOrder(ok(get(BRAND_BOARD).with(tokenFor(BRAND_SUPERVISOR))));
        assertThat(asSupervisor.get(unpaidOnline))
                .as("BRAND_MANAGER holds no payment.initiate — the endpoint would refuse, so the button is not shown")
                .doesNotContain("ISSUE_INVOICE");

        assertThat(actionsByOrder(ok(get(CHILONZOR_BOARD).with(tokenFor(FINANCE))))
                        .get(unpaidOnline))
                .as("the branch board and the brand board give one answer")
                .contains("ISSUE_INVOICE");
    }

    /**
     * The projection stays {@code PENDING} through an attempt that aged out or one
     * whose outcome is unknown ({@code PaymentAttemptService.applyToIntent}
     * publishes nothing for {@code EXPIRED} or {@code UNCERTAIN}), so the row's
     * projection alone offers «Выставить счёт» on an order the endpoint answers
     * 404 {@code NO_PAYMENT_INTENT} or 409 {@code PAYMENT_IN_DOUBT}. The live
     * intent is what the endpoint reads, so it is what the action is gated on.
     */
    @Test
    @DisplayName("«Выставить счёт» is not offered once the payment is closed or in doubt, though the projection "
            + "still says PENDING")
    void issueInvoiceFollowsTheLiveIntentAndNotTheProjectionAlone() throws Exception {
        UUID merchant = fixtures.insertMerchantBinding(TENANT, WOLT_BINDING);

        UUID presented = awaitingOnline("HT-J1");
        UUID presentedIntent = fixtures.insertProviderIntent(TENANT, BRAND, CHILONZOR, presented, "AUTHORIZING");
        fixtures.insertPaymentAttempt(TENANT, presentedIntent, merchant, "PRESENTED");

        UUID reserved = awaitingOnline("HT-J2");
        UUID reservedIntent = fixtures.insertProviderIntent(TENANT, BRAND, CHILONZOR, reserved, "AUTHORIZING");
        fixtures.insertPaymentAttempt(TENANT, reservedIntent, merchant, "RESERVED");

        UUID notYetPresented = awaitingOnline("HT-J3");
        fixtures.insertProviderIntent(TENANT, BRAND, CHILONZOR, notYetPresented, "PENDING");

        UUID expired = awaitingOnline("HT-J4");
        UUID expiredIntent = fixtures.insertProviderIntent(TENANT, BRAND, CHILONZOR, expired, "EXPIRED");
        fixtures.insertPaymentAttempt(TENANT, expiredIntent, merchant, "EXPIRED");

        UUID uncertain = awaitingOnline("HT-J5");
        UUID uncertainIntent = fixtures.insertProviderIntent(TENANT, BRAND, CHILONZOR, uncertain, "AUTHORIZING");
        fixtures.insertPaymentAttempt(TENANT, uncertainIntent, merchant, "UNCERTAIN");

        UUID noIntentAtAll = awaitingOnline("HT-J6");

        Map<UUID, Set<String>> onTheBrandBoard =
                actionsByOrder(ok(get(BRAND_BOARD).with(tokenFor(FINANCE))));
        Map<UUID, Set<String>> onTheBranchBoard =
                actionsByOrder(ok(get(CHILONZOR_BOARD).with(tokenFor(FINANCE))));

        for (Map<UUID, Set<String>> board : List.of(onTheBrandBoard, onTheBranchBoard)) {
            assertThat(board.get(presented))
                    .as("an abandoned checkout is handed back")
                    .contains("ISSUE_INVOICE");
            assertThat(board.get(reserved))
                    .as("a customer already on the provider's page is sent back to it")
                    .contains("ISSUE_INVOICE");
            assertThat(board.get(notYetPresented))
                    .as("no attempt yet: the endpoint opens the first")
                    .contains("ISSUE_INVOICE");
            assertThat(board.get(expired))
                    .as("the reservation aged out: the endpoint answers 404 NO_PAYMENT_INTENT")
                    .doesNotContain("ISSUE_INVOICE");
            assertThat(board.get(uncertain))
                    .as("the outcome is unknown: the endpoint answers 409 and says do not re-present")
                    .doesNotContain("ISSUE_INVOICE");
            assertThat(board.get(noIntentAtAll)).as("no payment to present").doesNotContain("ISSUE_INVOICE");
        }

        assertThat(detailActions(presented))
                .as("the detail read agrees with the board")
                .contains("ISSUE_INVOICE");
        assertThat(detailActions(expired))
                .as("the detail read agrees with the board")
                .doesNotContain("ISSUE_INVOICE");
        assertThat(detailActions(uncertain))
                .as("the detail read agrees with the board")
                .doesNotContain("ISSUE_INVOICE");
    }

    /** An online order waiting on its payment: the state whose projection is {@code PENDING}. */
    private UUID awaitingOnline(String seed) {
        return fixtures.insertOrder(order(seed)
                .at(TENANT, BRAND, CHILONZOR)
                .status("PAYMENT_AUTHORIZING")
                .paymentStatusProjection("PENDING"));
    }

    private Set<String> detailActions(UUID orderId) throws Exception {
        JsonNode detail = ok(
                get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + CHILONZOR + "/orders/" + orderId)
                        .with(tokenFor(FINANCE)));
        Set<String> actions = new java.util.HashSet<>();
        detail.get("summary")
                .get("actions")
                .forEach(action -> actions.add(action.get("action").asString()));
        return actions;
    }

    @Test
    @DisplayName("a row's actions come from the caller's grants at that row's branch")
    void actionsAreComputedPerBranch() throws Exception {
        UUID atChilonzor = fixtures.insertOrder(order("HT-A1")
                .at(TENANT, BRAND, CHILONZOR)
                .status("CONFIRMED")
                .mode("PICKUP")
                .confirmed());
        UUID atYunusobod = fixtures.insertOrder(order("HT-A2")
                .at(TENANT, BRAND, YUNUSOBOD)
                .status("CONFIRMED")
                .mode("PICKUP")
                .confirmed());

        Map<UUID, Set<String>> actions = actionsByOrder(ok(get(BRAND_BOARD).with(tokenFor(MIXED))));

        assertThat(actions.get(atChilonzor))
                .as("they run Chilonzor: the row carries the buttons their grant there justifies")
                .contains("ADVANCE", "CANCEL");
        assertThat(actions.get(atYunusobod))
                .as("they only read Yunusobod: a brand-wide read must not lend the buttons of a branch they "
                        + "cannot act at, or the console would offer them and the endpoint would refuse")
                .doesNotContain("ADVANCE", "CANCEL");

        assertThat(actionsByOrder(ok(get(BRAND_BOARD).with(tokenFor(BRAND_SUPERVISOR))))
                        .get(atChilonzor))
                .as("and a pure brand reader gets none anywhere")
                .doesNotContain("ADVANCE", "CANCEL");
    }

    // ------------------------------------------------------ binding read model

    @Test
    @DisplayName("the binding options carry the provider and installation names, counts, and stay in scope")
    void theBindingOptionsAreNamedAndScoped() throws Exception {
        Instant now = Instant.now();
        fixtures.insertOrder(order("HT-M1")
                .at(TENANT, BRAND, CHILONZOR)
                .marketplace(WOLT_BINDING)
                .createdAt(now.minusSeconds(120)));
        fixtures.insertOrder(order("HT-M2")
                .at(TENANT, BRAND, CHILONZOR)
                .marketplace(WOLT_BINDING)
                .createdAt(now.minusSeconds(60)));
        fixtures.insertOrder(order("HT-M3")
                .at(TENANT, BRAND, YUNUSOBOD)
                .marketplace(YANDEX_BINDING)
                .createdAt(now.minusSeconds(30)));
        fixtures.insertOrder(order("HT-M4").at(TENANT, BRAND, CHILONZOR));

        JsonNode brandWide = ok(get(BRAND_BINDINGS).with(tokenFor(BRAND_SUPERVISOR)));
        assertThat(brandWide.get("items")).hasSize(2);
        JsonNode first = brandWide.get("items").get(0);
        assertThat(first.get("bindingId").asString()).isEqualTo(YANDEX_BINDING.toString());
        assertThat(first.get("providerType").asString()).isEqualTo("YANDEX_EDA");
        assertThat(first.get("displayName").asString()).isEqualTo("Yandex Eda");
        assertThat(first.get("orderCount").asLong()).isEqualTo(1);
        JsonNode second = brandWide.get("items").get(1);
        assertThat(second.get("bindingId").asString()).isEqualTo(WOLT_BINDING.toString());
        assertThat(second.get("orderCount").asLong()).isEqualTo(2);

        JsonNode oneBranch = ok(get(CHILONZOR_BINDINGS).with(tokenFor(BRANCH_MANAGER)));
        assertThat(oneBranch.get("items"))
                .as("one branch offers only what it has taken")
                .hasSize(1);
        assertThat(oneBranch.get("items").get(0).get("bindingId").asString()).isEqualTo(WOLT_BINDING.toString());

        assertThat(mvc.perform(get(BRAND_BINDINGS).with(tokenFor(BRANCH_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the brand-wide options need the brand-wide read")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder board(String parameter, String... values) {
        return get(BRAND_BOARD).with(tokenFor(BRAND_SUPERVISOR)).queryParam(parameter, values);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static List<UUID> itemIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items")
                .forEach(item -> ids.add(UUID.fromString(item.get("orderId").asString())));
        return ids;
    }

    private static Map<UUID, UUID> locationsByOrder(JsonNode page) {
        Map<UUID, UUID> byOrder = new java.util.HashMap<>();
        page.get("items")
                .forEach(item -> byOrder.put(
                        UUID.fromString(item.get("orderId").asString()),
                        UUID.fromString(item.get("locationId").asString())));
        return byOrder;
    }

    private static Map<UUID, Set<String>> actionsByOrder(JsonNode page) {
        Map<UUID, Set<String>> byOrder = new java.util.HashMap<>();
        page.get("items").forEach(item -> {
            Set<String> actions = new java.util.HashSet<>();
            item.get("actions")
                    .forEach(action -> actions.add(action.get("action").asString()));
            byOrder.put(UUID.fromString(item.get("orderId").asString()), actions);
        });
        return byOrder;
    }

    private UUID insert(String seed, UUID locationId) {
        return fixtures.insertOrder(order(seed).at(TENANT, BRAND, locationId));
    }

    /** An order placed at {@code createdAt}, promised thirty-five minutes later, in play. */
    private UUID insert(String seed, UUID locationId, Instant createdAt) {
        return fixtures.insertOrder(
                order(seed).at(TENANT, BRAND, locationId).createdAt(createdAt).promisedAt(createdAt.plusSeconds(2100)));
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        grant(subject, role, scopeType, scopeId, TENANT);
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'brand order board endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
