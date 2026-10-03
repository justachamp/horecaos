package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0140's door for the payment method, over the real HTTP stack:
 * {@code PUT .../carts/{cartId}/payment-method}, called with the JSON the storefront apps send.
 *
 * <p>A promotion can read the payment method ("5% off when paying by Click"), so the method has to
 * be on the cart before it is priced. Both storefronts select it this way when the customer
 * chooses; before they did, a cart never carried one and checkout could only refuse. What is proved
 * here is the contract those apps rely on: the field is optional on the wire (a body that omits it,
 * or sends null, clears the selection rather than being refused as malformed), a method is
 * normalised and remembered only if the cart's channel sells it, the write is a version-checked one
 * that clears the price, and another customer's cart answers as if it did not exist.
 *
 * <p>What checkout does with the method on the cart -- accept a method no promotion reads, refuse a
 * swap that moves the total -- is {@code OrderAmendmentAndOutcomeTests}, which owns the checkout
 * fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CartPaymentMethodHttpTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    private static final UUID TENANT = UUID.fromString("018fe100-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe100-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe100-5000-7000-8000-0000000000c1");
    private static final UUID CHANNEL = UUID.fromString("018fe100-5000-7000-8000-0000000000d1");

    private static final String OWNER = "payment-method-owner";
    private static final String STRANGER = "payment-method-stranger";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the cart payment method test");
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

    private UUID ownerAccount = UUID.randomUUID();

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
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
                VALUES (:id, 'payment-method', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'CENTRE', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", CHANNEL).param("t", TENANT).update();
        // ADR 0036's channel payment matrix: this channel sells cash and a counter terminal, and not
        // the code a test below tries to select.
        for (String code : new String[] {"CASH", "TERMINAL"}) {
            jdbc.sql("""
                    INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                    VALUES (:id, :t, :code, :code, 'OPERATOR', 'ACTIVE')
                    ON CONFLICT ON CONSTRAINT uq_payment_method_code DO NOTHING
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", TENANT)
                    .param("code", code)
                    .update();
            jdbc.sql("""
                    INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                    VALUES (:t, :ch, :code, true)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("t", TENANT)
                    .param("ch", CHANNEL)
                    .param("code", code)
                    .update();
        }

        ownerAccount = account(OWNER);
        account(STRANGER);
    }

    @Test
    @DisplayName("the storefront's JSON selects a method: normalised, remembered, version moved, price cleared")
    void selectsAMethodTheChannelSells() throws Exception {
        UUID cart = cart(ownerAccount, 3);
        attachQuote(cart);

        MvcResult selected = select(cart, OWNER, "\"3\"", """
                {"paymentMethodCode":" terminal "}""");

        assertThat(selected.getResponse().getStatus())
                .as(selected.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = JSON.readTree(selected.getResponse().getContentAsString());
        assertThat(body.path("paymentMethodCode").asText()).isEqualTo("TERMINAL");
        assertThat(body.path("version").asInt())
                .as("a cart with a method is another cart")
                .isEqualTo(4);
        assertThat(storedMethod(cart)).isEqualTo("TERMINAL");
        assertThat(jdbc.sql("SELECT pricing_quote_id FROM ordering.carts WHERE id = :c")
                        .param("c", cart)
                        .query()
                        .singleRow()
                        .get("pricing_quote_id"))
                .as("a quote priced before the method was named does not survive it")
                .isNull();
    }

    @Test
    @DisplayName(
            "a body that omits the code, or sends null, clears the selection instead of being refused as malformed")
    void anOmittedOrNullCodeClearsTheSelection() throws Exception {
        UUID cart = cart(ownerAccount, 1);
        assertThat(select(cart, OWNER, "\"1\"", "{\"paymentMethodCode\":\"CASH\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(storedMethod(cart)).isEqualTo("CASH");

        MvcResult omitted = select(cart, OWNER, "\"2\"", "{}");
        assertThat(omitted.getResponse().getStatus())
                .as(omitted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(storedMethod(cart)).isNull();

        assertThat(select(cart, OWNER, "\"3\"", "{\"paymentMethodCode\":\"CASH\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MvcResult nulled = select(cart, OWNER, "\"4\"", "{\"paymentMethodCode\":null}");
        assertThat(nulled.getResponse().getStatus())
                .as(nulled.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(storedMethod(cart)).isNull();
    }

    @Test
    @DisplayName("a method the channel does not sell is refused by name and leaves the previous selection")
    void aMethodTheChannelDoesNotSellIsRefused() throws Exception {
        UUID cart = cart(ownerAccount, 1);
        assertThat(select(cart, OWNER, "\"1\"", "{\"paymentMethodCode\":\"CASH\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult refused = select(cart, OWNER, "\"2\"", "{\"paymentMethodCode\":\"NOPE\"}");

        assertThat(refused.getResponse().getStatus())
                .as("a request the cart's channel refuses, with the reason the apps translate")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("PAYMENT_METHOD_UNAVAILABLE");
        assertThat(storedMethod(cart)).isEqualTo("CASH");
    }

    @Test
    @DisplayName("a stale If-Match is refused and selects nothing")
    void aStaleVersionSelectsNothing() throws Exception {
        UUID cart = cart(ownerAccount, 3);

        MvcResult stale = select(cart, OWNER, "\"2\"", "{\"paymentMethodCode\":\"CASH\"}");

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(storedMethod(cart)).isNull();
    }

    @Test
    @DisplayName("another customer's cart answers as if it did not exist")
    void aStrangersCartIsNotFound() throws Exception {
        UUID owners = cart(ownerAccount, 1);

        MvcResult refused = select(owners, STRANGER, "\"1\"", "{\"paymentMethodCode\":\"CASH\"}");

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(storedMethod(owners)).isNull();
    }

    @Test
    @DisplayName("the version and the idempotency key are both required, and so is signing in")
    void theWriteIsVersionedIdempotentAndAuthenticated() throws Exception {
        UUID cart = cart(ownerAccount, 1);
        String json = "{\"paymentMethodCode\":\"CASH\"}";

        MvcResult noVersion = mvc.perform(put(path(cart))
                        .with(token(OWNER))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
        MvcResult noKey = mvc.perform(put(path(cart))
                        .with(token(OWNER))
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
        MvcResult noSignIn = mvc.perform(put(path(cart))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();

        assertThat(noVersion.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noSignIn.getResponse().getStatus()).isEqualTo(401);
        assertThat(storedMethod(cart)).isNull();
    }

    // ------------------------------------------------------------------------------ helpers

    private MvcResult select(UUID cartId, String subject, String ifMatch, String json) throws Exception {
        return mvc.perform(put(path(cartId))
                        .with(token(subject))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private static String path(UUID cartId) {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/carts/" + cartId + "/payment-method";
    }

    private @Nullable String storedMethod(UUID cartId) {
        return (String) jdbc.sql("SELECT payment_method_code FROM ordering.carts WHERE id = :c")
                .param("c", cartId)
                .query()
                .singleRow()
                .get("payment_method_code");
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

    private UUID cart(UUID accountId, int version) {
        UUID cartId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO ordering.carts (
                    id, tenant_id, brand_id, location_id, channel_id, customer_account_id,
                    fulfillment_mode, currency, status, version, expires_at, created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :ch, :accountId, 'PICKUP', 'UZS', 'ACTIVE', :version,
                    :expiresAt, :now, :now)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", CHANNEL)
                .param("accountId", accountId)
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
                VALUES (:id, :t, :b, :cat, 'STOREFRONT', 'PUBLISHED', 'hash', now())
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
                .param("loc", LOCATION)
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
