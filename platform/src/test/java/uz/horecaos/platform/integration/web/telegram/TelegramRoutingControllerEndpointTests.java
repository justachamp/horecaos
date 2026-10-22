package uz.horecaos.platform.integration.web.telegram;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.loyalty.api.LoyaltySyncDiscrepancyOpened;
import uz.horecaos.platform.reviews.api.ReviewSubmitted;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Wave P26, second-pass adversarial review: {@code TelegramRoutingController}
 * (ADR 0058, gap map row 10.9b) was new this integration with no HTTP-level
 * test at all -- no test file for {@code TelegramRoutingAdminService} exists
 * either, so neither the service nor the capability wiring was proven
 * through a real request before this.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelegramRoutingControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-7000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000e1");
    private static final UUID INSTALLATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000c1");
    private static final UUID BINDING = UUID.fromString("018f9b20-7000-7000-8000-0000000000d1");

    private static final String MANAGER = "telegram-routing-manager";
    private static final String READ_ONLY = "telegram-routing-read-only";

    private static final String ROUTING = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/notification-routing";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenancyAndBinding();
        // TENANT_OWNER carries both NOTIFICATION_READ and NOTIFICATION_ROUTING_MANAGE.
        grant(MANAGER, PlatformRole.TENANT_OWNER);
        // SUPPORT_AGENT carries NOTIFICATION_READ but never NOTIFICATION_ROUTING_MANAGE.
        grant(READ_ONLY, PlatformRole.SUPPORT_AGENT);
    }

    @Test
    void readOnlyCanListButNotMutate() throws Exception {
        MvcResult bindings = mvc.perform(get(ROUTING + "/bindings").with(tokenFor(READ_ONLY)))
                .andReturn();
        assertThat(bindings.getResponse().getStatus()).isEqualTo(200);
        assertThat(bindings.getResponse().getContentAsString()).contains(BINDING.toString());

        MvcResult refused = mvc.perform(post(ROUTING + "/bindings/" + BINDING + "/subscriptions")
                        .with(tokenFor(READ_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventClass":"ORDER_AWAITING_APPROVAL","enabled":false}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.NOTIFICATION_ROUTING_MANAGE.code());
    }

    @Test
    void readOnlyCannotUnbindOrChangeTopic() throws Exception {
        MvcResult topicRefused = mvc.perform(post(ROUTING + "/bindings/" + BINDING + "/topic")
                        .with(tokenFor(READ_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":42}"))
                .andReturn();
        assertThat(topicRefused.getResponse().getStatus()).isEqualTo(403);

        MvcResult unbindRefused = mvc.perform(
                        post(ROUTING + "/bindings/" + BINDING + "/unbind").with(tokenFor(READ_ONLY)))
                .andReturn();
        assertThat(unbindRefused.getResponse().getStatus()).isEqualTo(403);

        assertThat(bindingStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void aManagerCanSubscribeMoveAndUnbind() throws Exception {
        MvcResult eventClasses = mvc.perform(get(ROUTING + "/event-classes").with(tokenFor(MANAGER)))
                .andReturn();
        assertThat(eventClasses.getResponse().getStatus()).isEqualTo(200);

        MvcResult subscribed = mvc.perform(post(ROUTING + "/bindings/" + BINDING + "/subscriptions")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "subscribe-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventClass":"ORDER_AWAITING_APPROVAL","enabled":true}
                                """))
                .andReturn();
        assertThat(subscribed.getResponse().getStatus()).isEqualTo(204);

        MvcResult moved = mvc.perform(post(ROUTING + "/bindings/" + BINDING + "/topic")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "topic-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":7}"))
                .andReturn();
        assertThat(moved.getResponse().getStatus()).isEqualTo(204);

        MvcResult unbound = mvc.perform(post(ROUTING + "/bindings/" + BINDING + "/unbind")
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "unbind-ok"))
                .andReturn();
        assertThat(unbound.getResponse().getStatus()).isEqualTo(204);
        assertThat(bindingStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    void theReviewAndTillClassesAreOfferedAndAChatCanSubscribeToThem() throws Exception {
        MvcResult eventClasses = mvc.perform(get(ROUTING + "/event-classes").with(tokenFor(MANAGER)))
                .andReturn();
        String body = eventClasses.getResponse().getContentAsString();
        for (String eventClass : java.util.List.of("REVIEW_POSITIVE", "REVIEW_NEGATIVE", "LOYALTY_SYNC_DISCREPANCY")) {
            assertThat(body)
                    .as(eventClass + " is offered to the routing screen")
                    .contains(eventClass);
        }

        for (String eventClass : java.util.List.of("REVIEW_POSITIVE", "REVIEW_NEGATIVE", "LOYALTY_SYNC_DISCREPANCY")) {
            MvcResult subscribed = mvc.perform(post(ROUTING + "/bindings/" + BINDING + "/subscriptions")
                            .with(tokenFor(MANAGER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "subscribe-" + eventClass)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventClass\":\"" + eventClass + "\",\"enabled\":true}"))
                    .andReturn();
            assertThat(subscribed.getResponse().getStatus()).as(eventClass).isEqualTo(204);
        }

        assertThat(jdbc.sql("""
                        SELECT event_class FROM integration.telegram_binding_events
                         WHERE tenant_id = :t AND binding_id = :b AND enabled
                           AND event_class IN ('REVIEW_POSITIVE', 'REVIEW_NEGATIVE', 'LOYALTY_SYNC_DISCREPANCY')
                        """)
                        .param("t", TENANT)
                        .param("b", BINDING)
                        .query(String.class)
                        .list())
                .as("the database's own check admits all three, and a chat hears only what it subscribed to")
                .containsExactlyInAnyOrder("REVIEW_POSITIVE", "REVIEW_NEGATIVE", "LOYALTY_SYNC_DISCREPANCY");
    }

    @Test
    void aReviewReachesOnlyTheChatSubscribedToItsClassAndOnlyOnceTheReviewIsCommitted() throws Exception {
        subscribe("REVIEW_NEGATIVE");
        UUID twoStars = UUID.randomUUID();
        UUID fiveStars = UUID.randomUUID();

        // A review the database refused is not news: the submission rolled back, and no chat hears of it.
        UUID rolledBack = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.publishEvent(reviewSubmitted(rolledBack, 1));
            status.setRollbackOnly();
        });
        assertThat(alertsAbout(rolledBack))
                .as("a review that rolled back tells nobody")
                .isEmpty();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.publishEvent(reviewSubmitted(twoStars, 2));
            events.publishEvent(reviewSubmitted(fiveStars, 5));
        });

        List<Map<String, Object>> negative = alertsAbout(twoStars);
        assertThat(negative)
                .as("a two-star review reaches the chat that subscribed to REVIEW_NEGATIVE, and it is durable")
                .hasSize(1);
        assertThat(negative.getFirst().get("template_key")).isEqualTo("REVIEW_NEGATIVE");
        assertThat(String.valueOf(negative.getFirst().get("variables")))
                .contains(twoStars.toString())
                .contains("\"rating\"")
                .as("the alert carries the review's id and its stars and nothing about the customer")
                .doesNotContain("comment")
                .doesNotContain("customer");
        assertThat(alertsAbout(fiveStars))
                .as("the chat did not subscribe to REVIEW_POSITIVE, so a five-star review is not told to it")
                .isEmpty();

        subscribe("REVIEW_POSITIVE");
        UUID fourStars = UUID.randomUUID();
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> events.publishEvent(reviewSubmitted(fourStars, 4)));
        assertThat(alertsAbout(fourStars)).hasSize(1);
        assertThat(alertsAbout(fourStars).getFirst().get("template_key")).isEqualTo("REVIEW_POSITIVE");
    }

    @Test
    void aTillDifferenceReachesTheChatSubscribedToItOnceTheTransactionThatFoundItCommits() throws Exception {
        subscribe("LOYALTY_SYNC_DISCREPANCY");
        UUID loyaltyBinding = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        UUID ordinary = UUID.randomUUID();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            events.publishEvent(new LoyaltySyncDiscrepancyOpened(
                    TENANT, BRAND, loyaltyBinding, held, UUID.randomUUID(), "CREDIT_HELD"));
            // A customer known only to one side is counted in the report and not announced.
            events.publishEvent(new LoyaltySyncDiscrepancyOpened(
                    TENANT, BRAND, UUID.randomUUID(), ordinary, UUID.randomUUID(), "PLATFORM_ONLY"));
        });

        List<Map<String, Object>> alerts = alertsAbout(loyaltyBinding);
        assertThat(alerts)
                .as("a held credit is told to the chat, and it is durable")
                .hasSize(1);
        assertThat(alerts.getFirst().get("template_key")).isEqualTo("LOYALTY_SYNC_DISCREPANCY");
        assertThat(String.valueOf(alerts.getFirst().get("variables")))
                .contains(held.toString())
                .contains("CREDIT_HELD")
                .as("the alert carries ids and a kind, never the customer's account")
                .doesNotContain("accountId");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("the ordinary kind told nobody")
                .isEqualTo(1L);
    }

    private void subscribe(String eventClass) throws Exception {
        // A key of its own: the idempotency store is not cleared with the tenant, and a key another test already used
        // with the same body would be replayed as a 204 that writes nothing.
        MvcResult subscribed = mvc.perform(post(ROUTING + "/bindings/" + BINDING + "/subscriptions")
                        .with(tokenFor(MANAGER))
                        .header(
                                IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER,
                                "subscribe-" + eventClass + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventClass\":\"" + eventClass + "\",\"enabled\":true}"))
                .andReturn();
        assertThat(subscribed.getResponse().getStatus()).as(eventClass).isEqualTo(204);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM integration.telegram_binding_events
                         WHERE tenant_id = :t AND binding_id = :b AND event_class = :c AND enabled
                        """)
                        .param("t", TENANT)
                        .param("b", BINDING)
                        .param("c", eventClass)
                        .query(Long.class)
                        .single())
                .as("the chat is subscribed to " + eventClass)
                .isEqualTo(1L);
    }

    private static ReviewSubmitted reviewSubmitted(UUID reviewId, int rating) {
        return new ReviewSubmitted(
                UUID.randomUUID(),
                TENANT,
                reviewId,
                UUID.randomUUID(),
                BRAND,
                LOCATION,
                rating,
                true,
                2,
                Instant.now());
    }

    private List<Map<String, Object>> alertsAbout(UUID reviewId) {
        return jdbc.sql("""
                        SELECT template_key, subject_type, variables::text AS variables
                          FROM notifications.notifications
                         WHERE tenant_id = :t AND subject_id = :s
                        """).param("t", TENANT).param("s", reviewId).query().listOfRows();
    }

    // ------------------------------------------------------------------ fixtures

    private String bindingStatus() {
        return jdbc.sql("SELECT status FROM integration.bindings WHERE tenant_id = :t AND id = :id")
                .param("t", TENANT)
                .param("id", BINDING)
                .query(String.class)
                .single();
    }

    private void insertTenancyAndBinding() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'telegram-routing-endpoint', 'Telegram Routing', 'Telegram Routing',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'main', 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('telegram-routing-endpoint', 'NOTIFICATION', 'TELEGRAM_BOT_API',
                        'http://127.0.0.1:1', false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'telegram-routing-endpoint',
                        'Pilot bot', 'ACTIVE',
                        'horecaos:test:provider_notification:platform:telegram-routing-endpoint',
                        'horecaos:test:provider_notification:platform:telegram-routing-endpoint')
                """).param("id", INSTALLATION).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO integration.telegram_bindings (binding_id, tenant_id, chat_id, audience)
                VALUES (:bindingId, :tenantId, -100987654321, 'OPERATIONS')
                """).param("bindingId", BINDING).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'BRAND', :brandId,
                        'ACTIVE', 'test-fixture', 'telegram routing endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
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
