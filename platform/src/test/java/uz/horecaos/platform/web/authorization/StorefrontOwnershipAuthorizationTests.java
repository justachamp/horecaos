package uz.horecaos.platform.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The storefront is authorised by ownership, and the operator surface is not.
 *
 * <p>A customer placing an order is acting on their own cart, not exercising
 * delegated authority over somebody else's. There is no ADR 0025 grant row per
 * customer and there is not meant to be one — so while these endpoints declared
 * {@code ORDER_PLACE}, {@code PAYMENT_INITIATE} and
 * {@code NOTIFICATION_PREFERENCE_MANAGE} they answered 403 to precisely the
 * caller they exist for, and every assertion below that expects 200 or 404 would
 * have read 403 instead. That is the bug this suite pins shut.
 *
 * <p>Three properties, and they fail in different directions.
 *
 * <p><strong>The customer gets through.</strong> Enforcement is left at its
 * default of on, because a suite that switched it off would pass just as happily
 * against the broken version.
 *
 * <p><strong>A stranger does not, and is told nothing.</strong> Every refusal is
 * 404 rather than 403. A forbidden answer distinguishes "this is not yours" from
 * "this does not exist", and the identifiers are the only thing standing between
 * somebody guessing an id and confirming that a cart, an order or a customer
 * account is real.
 *
 * <p><strong>Idempotency survived.</strong> ADR 0031's replay protection used to
 * be readable only off {@code @RequiresCapability(mutating = true)}, so removing
 * the capability would have silently removed the {@code Idempotency-Key}
 * requirement with it. On the payment path that is a second attempt against one
 * order for anyone who double-taps.
 *
 * <p>The payment service and the order directory are the only stand-ins. What is
 * under test is which caller reaches the service, and a test that had to seed an
 * order's whole foreign-key chain to say so would be a test of the schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontOwnershipAuthorizationTests {

    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();

    private static final String OWNER_SUBJECT = "owner-subject";
    private static final String STRANGER_SUBJECT = "stranger-subject";
    private static final String NO_ACCOUNT_SUBJECT = "never-signed-up";

    /**
     * A private database on the JVM's one shared PostgreSQL, handed to Spring as
     * properties.
     *
     * <p>Not {@code @ServiceConnection}. That annotation takes precedence over
     * every {@code spring.datasource.*} property, so a URL registered below would
     * be silently ignored and this suite would go on running against a container
     * of its own — the conversion would look done and change nothing.
     *
     * <p>Assigned in {@link #properties} rather than in a field initializer: a
     * field initializer runs at class load, which is before the {@code @BeforeAll}
     * that skips this class when Docker is absent, and would turn a clean skip
     * into an {@code ExceptionInInitializerError}.
     *
     * <p>Never closed. Hikari holds connections to it and Spring caches the
     * context past the last test in this class, so dropping the database here
     * would surface as a failure in whichever class ran next. It dies with the
     * container.
     *
     * <p>Boot's Flyway autoconfiguration is left on. Against a clone already at
     * the latest version it is a validate, not a migration, and it is the only
     * thing in this suite that would notice a clone that arrived at the wrong one.
     */
    // @DynamicPropertySource is a static hook Spring's test runner guarantees
    // runs before context startup and every test method, which NullAway cannot see.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront ownership test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // Pinned rather than left at the default, because (issuer, subject) is
        // the identity and the seeded principal link has to name the same issuer
        // the resolver will ask with.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private PaymentCheckoutService paymentCheckout;

    @MockitoBean
    private OrderDirectory orders;

    private UUID ownerAccount;
    private UUID strangerAccount;
    private UUID ownersCart;
    private UUID strangersCart;
    private UUID guestCart;

    @BeforeEach
    void seed() {
        seedEstate();
        jdbc.sql("DELETE FROM notifications.notification_preferences WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM ordering.carts WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.principal_links WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM platform.idempotency_records WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();

        ownerAccount = account(OWNER_SUBJECT);
        strangerAccount = account(STRANGER_SUBJECT);
        ownersCart = cart(ownerAccount, null);
        strangersCart = cart(strangerAccount, null);
        // A cart opened before anyone signed in. It has no account to compare
        // against, and "nobody owns this" must not read as "everybody does".
        guestCart = cart(null, "a-keyed-hash-of-a-device-reference");
    }

    // ---------------------------------------------------------------- the cart

    @Test
    @DisplayName("a customer reads their own cart, holding no capability at all")
    void theOwnerReadsTheirOwnCart() throws Exception {
        MvcResult result = mvc.perform(get(cartPath(ownersCart)).with(token(OWNER_SUBJECT)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("ORDER_PLACE is delegated staff authority; declaring it here answered 403 "
                        + "to every customer this endpoint was written for")
                .isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains(ownersCart.toString());
    }

    @Test
    @DisplayName("somebody else's cart is not found rather than forbidden")
    void anotherCustomersCartIsNotFound() throws Exception {
        MvcResult result = mvc.perform(get(cartPath(strangersCart)).with(token(OWNER_SUBJECT)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result))
                .as("403 would confirm the cart id is real to whoever guessed it")
                .isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("an ownerless cart is nobody's, not everybody's")
    void aGuestCartIsNotReachableByASignedInCustomer() throws Exception {
        assertThat(statusOf(get(cartPath(guestCart)).with(token(OWNER_SUBJECT))))
                .as("a cart created before sign-in has a null account, and a null must not "
                        + "match the caller who happens to have no account either")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a principal with no account at this brand is not found, not refused")
    void aPrincipalWithoutAnAccountIsNotFound() throws Exception {
        MvcResult result = mvc.perform(get(cartPath(ownersCart)).with(token(NO_ACCOUNT_SUBJECT)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("no principal at all still cannot reach a cart")
    void anAnonymousCallerIsRefusedByTheFilterChain() throws Exception {
        assertThat(statusOf(get(cartPath(ownersCart))))
                .as("ownership is checked in the handler, so the handler must not be reached "
                        + "without a principal to own anything")
                .isEqualTo(401);
    }

    // -------------------------------------------------------- idempotency kept

    @Test
    @DisplayName("checkout still requires an Idempotency-Key without a capability to imply one")
    void checkoutStillRequiresAnIdempotencyKey() throws Exception {
        MvcResult result = mvc.perform(post(brandPath() + "/checkouts")
                        .with(token(OWNER_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result))
                .as("the key requirement used to ride on mutating = true; losing it here means "
                        + "a retried checkout is a second order")
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName("a payment session still requires an Idempotency-Key")
    void aPaymentSessionStillRequiresAnIdempotencyKey() throws Exception {
        MvcResult result = mvc.perform(post(paymentSessionPath(UUID.randomUUID()))
                        .with(token(OWNER_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName("a repeated payment key is replayed rather than run again")
    void aRepeatedPaymentSessionKeyIsReplayed() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orders.summary(TENANT, orderId)).thenReturn(Optional.empty());

        mvc.perform(paymentSession(orderId, "a-key")).andReturn();
        MvcResult second = mvc.perform(paymentSession(orderId, "a-key")).andReturn();

        assertThat(second.getResponse().getHeader("Idempotency-Replayed"))
                .as("the whole replay path still runs, not just the header check")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("one key against two orders is two operations, not a replay")
    void theScopeKeyStillDistinguishesTwoOrders() throws Exception {
        // The key names the operation and the resource, and that string is longer
        // than the scope_key column for a path carrying three UUIDs. Keeping only
        // its leading characters would fit, and would make these two requests one:
        // the second customer would be handed the first one's payment link, amount
        // and merchant transaction id.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(orders.summary(TENANT, first)).thenReturn(Optional.of(order(ownerAccount)));
        when(orders.summary(TENANT, second)).thenReturn(Optional.of(order(ownerAccount)));
        when(paymentCheckout.openOrRePresent(any(), any(), any(), any()))
                .thenReturn(new PaymentCheckoutService.PaymentSession(
                        UUID.randomUUID(),
                        "merchant-trans-id",
                        PaymentProviderType.CLICK,
                        PresentationKind.PAYMENT_LINK,
                        "https://checkout.example/pay",
                        null,
                        Instant.now().plusSeconds(900),
                        42_000L,
                        "UZS",
                        false,
                        1));

        mvc.perform(paymentSession(first, "one-key-two-orders")).andReturn();
        MvcResult other =
                mvc.perform(paymentSession(second, "one-key-two-orders")).andReturn();

        assertThat(other.getResponse().getHeader("Idempotency-Replayed"))
                .as("order B must get its own attempt, not order A's response")
                .isNull();
        verify(paymentCheckout).openOrRePresent(eq(TENANT), eq(second), eq(ownerAccount), any());
    }

    // ----------------------------------------------------- the order behind it

    @Test
    @DisplayName("a payment session for somebody else's order is not found")
    void aPaymentSessionForAnotherCustomersOrderIsNotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orders.summary(TENANT, orderId)).thenReturn(Optional.of(order(strangerAccount)));

        assertThat(statusOf(paymentSession(orderId, "key-for-a-stranger"))).isEqualTo(404);
        verify(paymentCheckout, never()).openOrRePresent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an order nobody's account owns is not payable from the storefront")
    void aPaymentSessionForAnOperatorPlacedOrderIsNotFound() throws Exception {
        // A phone order taken by an agent has a null customer account. The payment
        // service compares the two accounts only when the order has one — right for
        // its operator caller, and on this surface it would mean any signed-in
        // customer of the brand could open a payment link against a stranger's
        // ticket.
        UUID orderId = UUID.randomUUID();
        when(orders.summary(TENANT, orderId)).thenReturn(Optional.of(order(null)));

        MvcResult result =
                mvc.perform(paymentSession(orderId, "key-for-a-phone-order")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString())
                .as("refused for not being the caller's, not for lacking a payment intent")
                .contains("No such order");
        verify(paymentCheckout, never()).openOrRePresent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the order's own customer reaches the payment service")
    void theOwnerReachesThePaymentService() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orders.summary(TENANT, orderId)).thenReturn(Optional.of(order(ownerAccount)));
        when(paymentCheckout.openOrRePresent(any(), any(), any(), any()))
                .thenReturn(new PaymentCheckoutService.PaymentSession(
                        UUID.randomUUID(),
                        "merchant-trans-id",
                        PaymentProviderType.CLICK,
                        PresentationKind.PAYMENT_LINK,
                        "https://checkout.example/pay",
                        null,
                        Instant.now().plusSeconds(900),
                        42_000L,
                        "UZS",
                        false,
                        1));

        int status = statusOf(paymentSession(orderId, "key-for-the-owner"));

        assertThat(status).isEqualTo(200);
        verify(paymentCheckout).openOrRePresent(eq(TENANT), eq(orderId), eq(ownerAccount), any());
    }

    // -------------------------------------------------- notification preferences

    @Test
    @DisplayName("a customer reads their own notification preferences")
    void theOwnerReadsTheirOwnPreferences() throws Exception {
        assertThat(statusOf(get(preferencesPath(ownerAccount)).with(token(OWNER_SUBJECT))))
                .as("NOTIFICATION_PREFERENCE_MANAGE is an agent's capability; the customer "
                        + "whose settings these are held nothing and was refused")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("another customer's preferences are not found rather than forbidden")
    void anotherAccountsPreferencesAreNotFound() throws Exception {
        MvcResult result = mvc.perform(get(preferencesPath(strangerAccount)).with(token(OWNER_SUBJECT)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the capability was tenant-scoped while the account is in the path, so "
                        + "nothing in the handler ever compared the two")
                .isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("setting a preference still requires an Idempotency-Key")
    void settingAPreferenceStillRequiresAnIdempotencyKey() throws Exception {
        MvcResult result = mvc.perform(put(preferencesPath(ownerAccount) + "/MARKETING/SMS")
                        .with(token(OWNER_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName("a customer writes their own preference through the whole chain")
    void theOwnerSetsTheirOwnPreference() throws Exception {
        int status = statusOf(put(preferencesPath(ownerAccount) + "/MARKETING/SMS")
                .with(token(OWNER_SUBJECT))
                .header("Idempotency-Key", "preference-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"));

        assertThat(status).isEqualTo(204);
    }

    // ---------------------------------------------------------- the other side

    @Test
    @DisplayName("the operator surface still refuses a principal holding no capability")
    void theOperatorSurfaceIsUnchanged() throws Exception {
        // The same token that now sails through the storefront. An agent acting on
        // somebody else's order is exactly what a capability is for, and loosening
        // the customer's side must not loosen this one.
        MvcResult result = mvc.perform(
                        get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders")
                                .with(token(OWNER_SUBJECT)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("INSUFFICIENT_CAPABILITY");
    }

    // ------------------------------------------------------------------ fixture

    /**
     * The tenant, brand, location and channel a cart row has foreign keys to.
     *
     * <p>Inserted once and left alone between tests. The rows carry nothing this
     * suite asserts on — they exist so that a cart may exist — and re-creating
     * them per test would only be a slower way to arrive at the same estate.
     */
    private void seedEstate() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'ownership-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Branch', 'Asia/Tashkent',
                    'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', false)
                ON CONFLICT (id) DO NOTHING
                """).param("id", CHANNEL).param("tenantId", TENANT).update();
    }

    private UUID account(String subject) {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, 'ACTIVE', :now, :now)
                """)
                .param("id", accountId)
                .param("tenantId", TENANT)
                .param("now", now)
                .update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (
                    id, tenant_id, customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :tenantId, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .param("issuer", ISSUER)
                .param("subject", subject)
                .param("now", now)
                .update();
        return accountId;
    }

    private UUID cart(@Nullable UUID accountId, @Nullable String guestHash) {
        UUID cartId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO ordering.carts (
                    id, tenant_id, brand_id, location_id, channel_id, customer_account_id,
                    guest_reference_hash, fulfillment_mode, currency, status, version,
                    expires_at, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :accountId,
                    :guestHash, 'DELIVERY', 'UZS', 'ACTIVE', 1, :expiresAt, :now, :now)
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", CHANNEL)
                .param("accountId", accountId)
                .param("guestHash", guestHash)
                .param("expiresAt", now.plusHours(4))
                .param("now", now)
                .update();
        return cartId;
    }

    private static OrderDirectory.OrderSummary order(@Nullable UUID accountId) {
        return new OrderDirectory.OrderSummary(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                "A-1",
                accountId,
                accountId == null ? "a-guest-hash" : null,
                "RECEIVED",
                "UZS",
                42_000L,
                1);
    }

    private MockHttpServletRequestBuilder paymentSession(UUID orderId, String key) {
        return post(paymentSessionPath(orderId))
                .with(token(OWNER_SUBJECT))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private static String brandPath() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;
    }

    private static String cartPath(UUID cartId) {
        return brandPath() + "/carts/" + cartId;
    }

    private static String paymentSessionPath(UUID orderId) {
        return brandPath() + "/orders/" + orderId + "/payment-sessions";
    }

    private static String preferencesPath(UUID accountId) {
        return "/api/v1/tenants/" + TENANT + "/customers/" + accountId + "/notification-preferences";
    }

    private static String checkoutBody() {
        return """
                {"cartId":"%s","cartVersion":1,"quoteId":"%s","contextHash":"abc"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String codeOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        int at = body.indexOf("\"code\":\"");
        return at < 0 ? body : body.substring(at + 8, body.indexOf('"', at + 8));
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
