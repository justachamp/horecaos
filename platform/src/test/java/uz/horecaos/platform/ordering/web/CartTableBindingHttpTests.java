package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.application.QrEntryService;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0047's cart-to-table binding, through the real HTTP stack:
 * {@code PUT .../carts/{cartId}/table}.
 *
 * <p>What is proved here is the door, not the room. The table a cart is bound to
 * is read from the guest token a real scan minted -- through the real dine-in
 * adapter over the real tables, not a stand-in -- and never from the request; a
 * token from another branch, a {@code VIEW_ONLY} code, and a token that has ended
 * each refuse; another customer's cart answers as if it did not exist; and the
 * write is a version-checked one that clears the price, like every other edit to
 * what a cart is.
 *
 * <p>What checkout does with the binding -- refuse an order for a table nobody is
 * sitting at, put the order on the bill in its own transaction, roll back when it
 * cannot -- is {@code CartCheckoutAndOrderTests}, which owns the checkout fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CartTableBindingHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";
    private static final String TOKEN_HEADER = "X-Dine-In-Token";

    private static final UUID TENANT = UUID.fromString("018fda00-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fda00-4000-7000-8000-0000000000b1");
    private static final UUID CENTRE = UUID.fromString("018fda00-4000-7000-8000-0000000000c1");
    private static final UUID NORTH = UUID.fromString("018fda00-4000-7000-8000-0000000000c2");
    private static final UUID VIEW_ONLY_BRANCH = UUID.fromString("018fda00-4000-7000-8000-0000000000c3");
    private static final UUID CHANNEL = UUID.fromString("018fda00-4000-7000-8000-0000000000d1");

    private static final String OWNER = "table-binding-owner";
    private static final String STRANGER = "table-binding-stranger";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the cart table binding test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private FloorPlanService floorPlan;

    @Autowired
    @SuppressWarnings("NullAway")
    private QrEntryService qr;

    @Autowired
    @SuppressWarnings("NullAway")
    private TableSessionService sessions;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate transactions;

    private UUID ownerAccount = UUID.randomUUID();
    private UUID strangerAccount = UUID.randomUUID();
    private UUID centreTable = UUID.randomUUID();
    private UUID secondCentreTable = UUID.randomUUID();
    private UUID northTable = UUID.randomUUID();
    private UUID viewOnlyTable = UUID.randomUUID();

    private String centreGuestToken = "";
    private String secondCentreGuestToken = "";
    private String northGuestToken = "";
    private String viewOnlyGuestToken = "";

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        // The customer schema does not hang off the tenant row by a cascading key, so
        // a previous test's accounts and their sign-in links survive the truncate below
        // and collide with this one's.
        jdbc.sql("DELETE FROM ordering.carts WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.principal_links WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'table-binding', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        location(CENTRE, "CENTRE");
        location(NORTH, "NORTH");
        location(VIEW_ONLY_BRANCH, "QUIET");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'QRTABLE', 'QR_TABLE', 'QR table', 'ACTIVE')
                """).param("id", CHANNEL).param("t", TENANT).update();

        ownerAccount = account(OWNER);
        strangerAccount = account(STRANGER);

        // The real floor plan and the real scan: the token the endpoint is given is
        // one QrEntryService minted, so the binding is proved against the adapter it
        // will run against in production.
        UUID centreHall = hall(CENTRE);
        centreTable = table(CENTRE, centreHall, "T1");
        secondCentreTable = table(CENTRE, centreHall, "T2");
        northTable = table(NORTH, hall(NORTH), "N1");
        viewOnlyTable = table(VIEW_ONLY_BRANCH, hall(VIEW_ONLY_BRANCH), "Q1");
        orderingOn(CENTRE);
        orderingOn(NORTH);
        centreGuestToken = scan(CENTRE, centreTable);
        secondCentreGuestToken = scan(CENTRE, secondCentreTable);
        northGuestToken = scan(NORTH, northTable);
        // VIEW_ONLY is the default a branch has until somebody turns ordering on.
        viewOnlyGuestToken = scan(VIEW_ONLY_BRANCH, viewOnlyTable);
    }

    @Test
    @DisplayName("a guest binds a DINE_IN cart to the table their token was minted for, and the price is cleared")
    void bindsTheCartToTheTokensTable() throws Exception {
        UUID cart = cart(ownerAccount, CENTRE, "DINE_IN", 5);
        attachQuote(cart);

        MvcResult bound = bind(cart, OWNER, centreGuestToken, "\"5\"");

        assertThat(bound.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(bound.getResponse().getContentAsString())
                        .path("version")
                        .asInt())
                .as("a bound cart is another cart: the version moves")
                .isEqualTo(6);
        assertThat(boundTable(cart)).isEqualTo(centreTable);
        assertThat(jdbc.sql("SELECT fulfillment_mode FROM ordering.cart_fulfillment WHERE cart_id = :c")
                        .param("c", cart)
                        .query(String.class)
                        .single())
                .isEqualTo("DINE_IN");
        assertThat(count("SELECT count(*) FROM ordering.carts WHERE id = '" + cart + "' AND pricing_quote_id IS NULL"))
                .as("a quote priced before the bind does not survive it")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("scanning another table of the same branch replaces the binding")
    void scanningAnotherTableReplacesTheBinding() throws Exception {
        UUID cart = cart(ownerAccount, CENTRE, "DINE_IN", 1);
        assertThat(bind(cart, OWNER, centreGuestToken, "\"1\"").getResponse().getStatus())
                .isEqualTo(200);

        MvcResult moved = bind(cart, OWNER, secondCentreGuestToken, "\"2\"");

        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(boundTable(cart)).isEqualTo(secondCentreTable);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.cart_fulfillment WHERE cart_id = :c")
                        .param("c", cart)
                        .query(Long.class)
                        .single())
                .as("one binding per cart")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a stale If-Match is refused and binds nothing")
    void aStaleVersionBindsNothing() throws Exception {
        UUID cart = cart(ownerAccount, CENTRE, "DINE_IN", 3);

        MvcResult stale = bind(cart, OWNER, centreGuestToken, "\"2\"");

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM ordering.cart_fulfillment WHERE cart_id = '" + cart + "'"))
                .as("the binding rolled back with the refused version")
                .isZero();
    }

    @Test
    @DisplayName("only a DINE_IN cart is bound to a table")
    void aDeliveryCartHasNoTable() throws Exception {
        UUID delivery = cart(ownerAccount, CENTRE, "DELIVERY", 1);

        MvcResult refused = bind(delivery, OWNER, centreGuestToken, "\"1\"");

        assertThat(refused.getResponse().getStatus())
                .as("a well-formed request the cart's own identity refuses, like a destination on a pickup cart")
                .isEqualTo(409);
        assertThat(count("SELECT count(*) FROM ordering.cart_fulfillment WHERE cart_id = '" + delivery + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a table of another branch cannot be bound to this branch's cart")
    void aTableOfAnotherBranchIsRefused() throws Exception {
        UUID cart = cart(ownerAccount, CENTRE, "DINE_IN", 1);

        MvcResult refused = bind(cart, OWNER, northGuestToken, "\"1\"");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(boundTableOrNull(cart)).isNull();
    }

    @Test
    @DisplayName("a VIEW_ONLY code has no cart to bind, and says so like an unknown code does")
    void aViewOnlyCodeBindsNothing() throws Exception {
        UUID cart = cart(ownerAccount, VIEW_ONLY_BRANCH, "DINE_IN", 1);

        MvcResult refused = bind(cart, OWNER, viewOnlyGuestToken, "\"1\"");

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(boundTableOrNull(cart)).isNull();
    }

    @Test
    @DisplayName("a token that has ended is refused as unauthenticated, the cue to scan again")
    void anEndedTokenIsUnauthenticated() throws Exception {
        UUID cart = cart(ownerAccount, CENTRE, "DINE_IN", 1);
        jdbc.sql("UPDATE dinein.qr_guest_sessions SET revoked_at = now(), revoked_reason = 'OPERATOR_REVOKED' "
                        + "WHERE table_id = :t")
                .param("t", centreTable)
                .update();

        MvcResult refused = bind(cart, OWNER, centreGuestToken, "\"1\"");

        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(boundTableOrNull(cart)).isNull();
        assertThat(bind(cart, OWNER, "not-a-token-anybody-minted", "\"1\"")
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("another customer's cart answers as if it did not exist, and the token does not change that")
    void aStrangersCartIsNotFound() throws Exception {
        UUID owners = cart(ownerAccount, CENTRE, "DINE_IN", 1);

        MvcResult refused = bind(owners, STRANGER, centreGuestToken, "\"1\"");

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(boundTableOrNull(owners)).isNull();
        assertThat(strangerAccount).isNotEqualTo(ownerAccount);
    }

    @Test
    @DisplayName("the token header and the version are both required")
    void bothCredentialsAreRequired() throws Exception {
        UUID cart = cart(ownerAccount, CENTRE, "DINE_IN", 1);

        MvcResult noToken = mvc.perform(put(path(cart))
                        .with(token(OWNER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\""))
                .andReturn();
        MvcResult noVersion = mvc.perform(put(path(cart))
                        .with(token(OWNER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header(TOKEN_HEADER, centreGuestToken))
                .andReturn();
        MvcResult noSignIn = mvc.perform(put(path(cart))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .header(TOKEN_HEADER, centreGuestToken))
                .andReturn();

        assertThat(noToken.getResponse().getStatus()).isEqualTo(400);
        assertThat(noVersion.getResponse().getStatus()).isEqualTo(400);
        assertThat(noSignIn.getResponse().getStatus())
                .as("the table token proves the table, never the customer")
                .isEqualTo(401);
        assertThat(boundTableOrNull(cart)).isNull();
    }

    // ------------------------------------------------------------------------ the schema

    @Test
    @DisplayName("the schema keeps a doorstep and a table apart: exactly one kind of row per cart")
    void theSchemaRefusesAMixedFulfillmentRow() {
        UUID dineIn = cart(ownerAccount, CENTRE, "DINE_IN", 1);
        UUID delivery = cart(ownerAccount, CENTRE, "DELIVERY", 1);

        // A table beside a doorstep on one row.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ordering.cart_fulfillment (cart_id, tenant_id, fulfillment_mode,
                            dinein_table_id, address_encrypted, latitude, longitude)
                        VALUES (:c, :t, 'DINE_IN', :table, 'ciphertext', 41.3, 69.2)
                        """)
                        .param("c", dineIn)
                        .param("t", TENANT)
                        .param("table", centreTable)
                        .update())
                .hasMessageContaining("ck_cart_fulfillment_one_kind");

        // A DINE_IN binding with no table at all.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.sql("""
                        INSERT INTO ordering.cart_fulfillment (cart_id, tenant_id, fulfillment_mode)
                        VALUES (:c, :t, 'DINE_IN')
                        """).param("c", dineIn).param("t", TENANT).update())
                .hasMessageContaining("ck_cart_fulfillment_one_kind");

        // A delivery destination that names a table.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ordering.cart_fulfillment (cart_id, tenant_id, fulfillment_mode,
                            dinein_table_id, address_encrypted, latitude, longitude)
                        VALUES (:c, :t, 'DELIVERY', :table, 'ciphertext', 41.3, 69.2)
                        """)
                        .param("c", delivery)
                        .param("t", TENANT)
                        .param("table", centreTable)
                        .update())
                .hasMessageContaining("ck_cart_fulfillment_one_kind");

        // A table binding on a cart that is not eaten at one: the mode travels in the key.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ordering.cart_fulfillment (cart_id, tenant_id, fulfillment_mode,
                            dinein_table_id)
                        VALUES (:c, :t, 'DINE_IN', :table)
                        """)
                        .param("c", delivery)
                        .param("t", TENANT)
                        .param("table", centreTable)
                        .update())
                .hasMessageContaining("fk_cart_fulfillment_cart");

        // The two legal shapes still go in.
        assertThat(jdbc.sql("""
                        INSERT INTO ordering.cart_fulfillment (cart_id, tenant_id, fulfillment_mode,
                            dinein_table_id)
                        VALUES (:c, :t, 'DINE_IN', :table)
                        """)
                        .param("c", dineIn)
                        .param("t", TENANT)
                        .param("table", centreTable)
                        .update())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        INSERT INTO ordering.cart_fulfillment (cart_id, tenant_id, fulfillment_mode,
                            address_encrypted, latitude, longitude)
                        VALUES (:c, :t, 'DELIVERY', 'ciphertext', 41.3, 69.2)
                        """).param("c", delivery).param("t", TENANT).update())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------ helpers

    private MvcResult bind(UUID cartId, String subject, String guestToken, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = put(path(cartId))
                .with(token(subject))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", ifMatch)
                .header(TOKEN_HEADER, guestToken);
        return mvc.perform(request).andReturn();
    }

    private static String path(UUID cartId) {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/carts/" + cartId + "/table";
    }

    private UUID boundTable(UUID cartId) {
        UUID bound = boundTableOrNull(cartId);
        if (bound == null) {
            throw new AssertionError("cart " + cartId + " is not bound to a table");
        }
        return bound;
    }

    private @Nullable UUID boundTableOrNull(UUID cartId) {
        return jdbc.sql("SELECT dinein_table_id FROM ordering.cart_fulfillment WHERE cart_id = :c")
                .param("c", cartId)
                .query((row, number) -> row.getObject("dinein_table_id", UUID.class))
                .optional()
                .orElse(null);
    }

    private long count(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0L : value;
    }

    private void location(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("code", code)
                .param("slug", code.toLowerCase())
                .update();
    }

    private UUID hall(UUID location) {
        return floorPlan
                .createSection(new FloorPlanService.NewSection(TENANT, BRAND, location, "HALL", "Hall", 0))
                .id();
    }

    private UUID table(UUID location, UUID section, String code) {
        return floorPlan
                .createTable(new FloorPlanService.NewTable(
                        TENANT, BRAND, location, section, code, "Table " + code, 4, false, null, null))
                .id();
    }

    private void orderingOn(UUID location) {
        transactions.executeWithoutResult(status -> floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, location, "ORDER_AND_PAY", null, null, null),
                "manager",
                "QR ordering on"));
    }

    /** A real scan: issue the printed code, then exchange it for a guest token. */
    private String scan(UUID location, UUID tableId) {
        int version = jdbc.sql("SELECT version FROM dinein.tables WHERE id = :id")
                .param("id", tableId)
                .query(Integer.class)
                .single();
        String printed = transactions
                .execute(status -> floorPlan.rotateQrToken(TENANT, location, tableId, version, "manager", "Printed"))
                .plaintext();
        return transactions.execute(status -> qr.exchange(printed)).guestToken();
    }

    private UUID account(String subject) {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, created_at, updated_at)
                VALUES (:id, :t, 'ACTIVE', :now, :now)
                """)
                .param("id", accountId)
                .param("t", TENANT)
                .param("now", now)
                .update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (
                    id, tenant_id, customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :t, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("accountId", accountId)
                .param("issuer", ISSUER)
                .param("subject", subject)
                .param("now", now)
                .update();
        return accountId;
    }

    private UUID cart(UUID accountId, UUID location, String mode, int version) {
        UUID cartId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO ordering.carts (
                    id, tenant_id, brand_id, location_id, channel_id, customer_account_id,
                    fulfillment_mode, currency, status, version, expires_at, created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :ch, :accountId, :mode, 'UZS', 'ACTIVE', :version,
                    :expiresAt, :now, :now)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", location)
                .param("ch", CHANNEL)
                .param("accountId", accountId)
                .param("mode", mode)
                .param("version", version)
                .param("expiresAt", now.plusHours(4))
                .param("now", now)
                .update();
        return cartId;
    }

    /** Prices the cart, as far as the cart row can tell: a quote and its hash are attached. */
    private void attachQuote(UUID cartId) {
        UUID catalogId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'QRTABLE', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, 'hash', 10000, 0, 10000,
                        now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", CENTRE)
                .param("pub", publicationId)
                .update();
        jdbc.sql("""
                UPDATE ordering.carts
                SET pricing_quote_id = :quote, pricing_context_hash = 'hash',
                    catalog_publication_id = :pub
                WHERE id = :id
                """)
                .param("quote", quoteId)
                .param("pub", publicationId)
                .param("id", cartId)
                .update();
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
    }

    /** Avoids contacting a real issuer; this suite exercises the MVC chain, not Keycloak. */
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
