package uz.horecaos.platform.integration.provider.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.reviews.api.CustomerReviewPort;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * The ADR 0075 callback boundary, against real PostgreSQL.
 *
 * <p>What is asserted here is what {@link CustomerBotActionAuthorizer} exists
 * for, and it is not the happy path: a customer button spends the tapper's own
 * money, so every test below is about a tap that must <em>not</em> act. The
 * ordering and review ports are recorders rather than real services precisely
 * so "nothing was called" is assertable — the services themselves are covered
 * where they live, and a stub that happily returned an order would let a
 * refusal test pass while the refusal never happened.
 */
class CustomerBotActionAuthorizerTests {

    private static final UUID TENANT = UUID.fromString("018fa100-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fa100-0000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fa100-0000-7000-8000-0000000000b1");
    private static final UUID ACCOUNT = UUID.fromString("018fa100-0000-7000-8000-0000000000c1");
    private static final UUID ORDER = UUID.fromString("018fa100-0000-7000-8000-0000000000d1");

    private static final long CUSTOMER_CHAT = 900_100_001L;
    private static final long STRANGER = 900_100_002L;

    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @SuppressWarnings("NullAway")
    private BotActionTokenStore tokens;

    @SuppressWarnings("NullAway")
    private RecordingOrdering ordering;

    @SuppressWarnings("NullAway")
    private RecordingReviews reviews;

    @SuppressWarnings("NullAway")
    private CustomerBotActionAuthorizer authorizer;

    private boolean entitled = true;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.bot_action_tokens CASCADE").update();
        jdbc.sql("TRUNCATE TABLE notifications.recipient_endpoints CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.telegram_bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        tokens = new BotActionTokenStore(jdbc, clock);
        ordering = new RecordingOrdering();
        reviews = new RecordingReviews();
        entitled = true;

        seedTenant(TENANT, "bot-actions");
        seedTenant(OTHER_TENANT, "bot-actions-other");
        seedBrand(TENANT, BRAND);
        seedCustomer(TENANT, ACCOUNT);
        seedCustomerChat(TENANT, BRAND, ACCOUNT, CUSTOMER_CHAT);

        authorizer = new CustomerBotActionAuthorizer(
                tokens,
                new TelegramBindingStore(jdbc, clock, noAudit()),
                togglableEntitlements(),
                ordering,
                reviews,
                new InProcessRateLimiter(clock),
                noAudit(),
                clock);
    }

    @Test
    @DisplayName("a customer's own tap in their own chat runs the action")
    void theOwnersTapRuns() {
        String token = mint("STATUS", CUSTOMER_CHAT, ORDER, null);

        var outcome =
                authorizer.perform(token, CUSTOMER_CHAT, CUSTOMER_CHAT, true).orElseThrow();

        // The premise for every refusal below: this exact shape does work.
        assertThat(outcome.result()).isEqualTo(CustomerBotActionAuthorizer.Result.DONE);
        assertThat(ordering.latestOrderCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("a forwarded button tapped by somebody else resolves to nothing at all")
    void aStrangersTapResolvesToNothing() {
        String token = mint("REPEAT", CUSTOMER_CHAT, ORDER, null);

        // Empty, not a refusal: the account predicate is inside the query, so the
        // stranger cannot learn the token was ever real.
        assertThat(authorizer.perform(token, STRANGER, CUSTOMER_CHAT, true)).isEmpty();
        assertThat(ordering.repeatCalls).isZero();
    }

    @Test
    @DisplayName("a customer action tapped in a group is refused without touching the account")
    void aGroupTapIsRefused() {
        String token = mint("CHECKOUT", CUSTOMER_CHAT, null, null);

        var outcome = authorizer
                .perform(token, CUSTOMER_CHAT, -100_500_600_700L, false)
                .orElseThrow();

        assertThat(outcome.result()).isEqualTo(CustomerBotActionAuthorizer.Result.NOT_PRIVATE);
        assertThat(ordering.checkoutCalls)
                .as("a checkout in a group would spend somebody's money in front of strangers")
                .isZero();
    }

    @Test
    @DisplayName("an expired button acts on nothing and falls through to the next resolver")
    void anExpiredTokenFallsThrough() {
        String token = tokens.mintCustomerActionToken(
                TENANT, BRAND, CUSTOMER_CHAT, "REPEAT", ORDER, null, NOW.minus(Duration.ofMinutes(1)));

        assertThat(authorizer.perform(token, CUSTOMER_CHAT, CUSTOMER_CHAT, true))
                .isEmpty();
        assertThat(ordering.repeatCalls).isZero();
    }

    @Test
    @DisplayName("a token minted for another tenant cannot be redeemed against this chat")
    void aCrossTenantTokenIsRefused() {
        String token = tokens.mintCustomerActionToken(
                OTHER_TENANT, BRAND, CUSTOMER_CHAT, "CHECKOUT", null, null, NOW.plus(Duration.ofMinutes(15)));

        var outcome =
                authorizer.perform(token, CUSTOMER_CHAT, CUSTOMER_CHAT, true).orElseThrow();

        // The chat holds no CUSTOMER binding in OTHER_TENANT, so there is no
        // account for the action to run as — which is the whole isolation story.
        assertThat(outcome.result()).isEqualTo(CustomerBotActionAuthorizer.Result.NOT_LINKED);
        assertThat(ordering.checkoutCalls).isZero();
    }

    @Test
    @DisplayName("an unentitled tenant's button acts on nothing")
    void anUnentitledTenantIsRefused() {
        entitled = false;
        String token = mint("CHECKOUT", CUSTOMER_CHAT, null, null);

        var outcome =
                authorizer.perform(token, CUSTOMER_CHAT, CUSTOMER_CHAT, true).orElseThrow();

        assertThat(outcome.result()).isEqualTo(CustomerBotActionAuthorizer.Result.NOT_ENTITLED);
        assertThat(ordering.checkoutCalls).isZero();
    }

    @Test
    @DisplayName("a chat with no customer binding cannot act as anybody")
    void anUnlinkedChatIsRefused() {
        String token = mint("STATUS", 900_100_099L, ORDER, null);

        var outcome =
                authorizer.perform(token, 900_100_099L, 900_100_099L, true).orElseThrow();

        assertThat(outcome.result()).isEqualTo(CustomerBotActionAuthorizer.Result.NOT_LINKED);
        assertThat(ordering.latestOrderCalls).isZero();
    }

    @Test
    @DisplayName("a finger faster than the rate limit stops acting")
    void tapsAreRateLimited() {
        for (int tap = 0; tap < 20; tap++) {
            authorizer.perform(mint("STATUS", CUSTOMER_CHAT, ORDER, null), CUSTOMER_CHAT, CUSTOMER_CHAT, true);
        }
        int before = ordering.latestOrderCalls;

        var outcome = authorizer
                .perform(mint("STATUS", CUSTOMER_CHAT, ORDER, null), CUSTOMER_CHAT, CUSTOMER_CHAT, true)
                .orElseThrow();

        assertThat(outcome.result()).isEqualTo(CustomerBotActionAuthorizer.Result.RATE_LIMITED);
        assertThat(ordering.latestOrderCalls)
                .as("a refused tap must not still have run the action")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a rating tap carries the star value the button was minted for")
    void aRatingTapCarriesItsStars() {
        var outcome = authorizer
                .perform(mint("RATE", CUSTOMER_CHAT, ORDER, "4"), CUSTOMER_CHAT, CUSTOMER_CHAT, true)
                .orElseThrow();

        assertThat(outcome.result()).isEqualTo(CustomerBotActionAuthorizer.Result.DONE);
        assertThat(reviews.ratings).containsExactly(4);
    }

    // ------------------------------------------------------------------ fixtures

    private String mint(
            String action,
            long telegramUserId,
            @org.jspecify.annotations.Nullable UUID orderId,
            @org.jspecify.annotations.Nullable String argument) {
        return tokens.mintCustomerActionToken(
                TENANT, BRAND, telegramUserId, action, orderId, argument, NOW.plus(Duration.ofMinutes(15)));
    }

    private void seedTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void seedBrand(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    private void seedCustomer(UUID tenantId, UUID accountId) {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (
                    id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", accountId).param("tenantId", tenantId).update();
    }

    /**
     * The binding shape {@code TelegramBindingStore#customerAccountFor} reads:
     * a CUSTOMER-audience Telegram binding joined to a recipient endpoint that
     * names the account. Written directly rather than through the linking
     * handshake, because what is under test is the tap and not the link.
     */
    private void seedCustomerChat(UUID tenantId, UUID brandId, UUID accountId, long chatId) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('test', 'NOTIFICATION', 'TELEGRAM_BOT_API', 'http://127.0.0.1:1', false, '127.0.0.1')
                ON CONFLICT (code) DO NOTHING
                """).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'test',
                        'Test bot', 'ACTIVE', 'ref://test', 'ref://test')
                """).param("id", installationId).param("tenantId", tenantId).update();

        // The production linking path, not a hand-written pair of inserts. A
        // fixture that wrote its own binding and endpoint rows would prove the
        // authorizer works against a shape nothing in production produces —
        // which is exactly how the audit of 2026-08-26 found its defects.
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var notifications =
                new uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore(jdbc);
        var endpointSync = new uz.horecaos.platform.notifications.application.CustomerProviderBindingSyncService(
                notifications,
                new uz.horecaos.platform.notifications.application.NotificationPreferenceService(notifications, clock));
        new TelegramCustomerLinkService(
                        jdbc,
                        clock,
                        Duration.ofMinutes(15),
                        new TelegramBindingStore(jdbc, clock, noAudit()),
                        endpointSync,
                        noAudit())
                .link(tenantId, installationId, brandId, accountId, chatId, chatId, NOW);
    }

    private EntitlementService togglableEntitlements() {
        return new EntitlementService() {

            @Override
            public EntitlementSnapshot snapshot(UUID tenantId) {
                throw new UnsupportedOperationException("not needed by this suite");
            }

            @Override
            public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
                throw new UnsupportedOperationException("not needed by this suite");
            }

            @Override
            public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
                throw new UnsupportedOperationException("not needed by this suite");
            }

            @Override
            public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
                return entitled;
            }

            @Override
            public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {}
        };
    }

    private static AuditRecorder noAudit() {
        return new AuditRecorder() {
            @Override
            public void record(AuditFact fact) {}
        };
    }

    /** Counts what was called, so "nothing happened" is an assertion and not a hope. */
    private static final class RecordingOrdering implements CustomerBotOrderingPort {

        private int latestOrderCalls;
        private int repeatCalls;
        private int checkoutCalls;

        @Override
        public Optional<OrderCard> latestOrder(UUID tenantId, UUID brandId, UUID customerAccountId) {
            latestOrderCalls++;
            return Optional.of(
                    new OrderCard(ORDER, "0906-001", "CONFIRMED", "UZS", 100_000L, null, true, false, false));
        }

        @Override
        public Repeat repeat(UUID tenantId, UUID brandId, UUID customerAccountId, UUID orderId) {
            repeatCalls++;
            return new Repeat(Repeat.Result.BUILT, UUID.randomUUID(), 2, List.of());
        }

        @Override
        public Optional<CartCard> currentCart(UUID tenantId, UUID brandId, UUID customerAccountId) {
            return Optional.of(new CartCard(
                    UUID.randomUUID(), List.of(new CartCard.Item("Lavash", 1)), "UZS", 50_000L, true, null));
        }

        @Override
        public Checkout checkoutForCash(
                UUID tenantId, UUID brandId, UUID customerAccountId, UUID cartId, String idempotencyKey) {
            checkoutCalls++;
            return new Checkout(Checkout.Result.PLACED, "0906-002", null);
        }
    }

    private static final class RecordingReviews implements CustomerReviewPort {

        private final List<Integer> ratings = new ArrayList<>();

        @Override
        public Outcome rate(UUID tenantId, UUID brandId, UUID orderId, UUID customerAccountId, int rating) {
            ratings.add(rating);
            return Outcome.RECORDED;
        }

        @Override
        public boolean hasReview(UUID tenantId, UUID orderId) {
            return false;
        }
    }
}
