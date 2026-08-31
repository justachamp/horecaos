package uz.horecaos.platform.integration.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.CorrelationIdFilter;

/**
 * ADR 0006's two single-item reads: {@code GET /outbox/{eventId}} and
 * {@code GET /inbox/{consumer}/{eventId}}.
 *
 * <p>Before these existed the record's own status line said an operator
 * "inspects one failure by filtering a list", and the runbooks reached for
 * {@code psql}. What this test pins down is not that the reads return a row —
 * that is the easy half — but the three things that make them more than a
 * convenience: the payload does not come back, another tenant's id is absent
 * rather than forbidden, and the inbox key is the pair and not the event.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FailureInspectionEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f01");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f02");

    private static final String OPERATOR = "failure-inspection-operator";
    private static final String OUTSIDER = "failure-inspection-outsider";

    private static final String FAILURES = "/api/v1/control-plane/integration/failures";

    /**
     * A synthetic sentinel, not a person. It exists so the assertion can be the
     * strong one — that this exact string is nowhere in the response — rather
     * than the weak one, that a field called {@code payload} is absent, which
     * would pass just as happily if the payload were returned under another
     * name.
     */
    private static final String SENTINEL_NAME = "PII-SENTINEL-DO-NOT-DISCLOSE";

    private static final String SENTINEL_PHONE = "+998900000000";

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
    private static TestDatabase.@Nullable Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the failure inspection endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        // The rows below are fixtures, not work. A running relay or listener
        // would move them underneath the assertions.
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("horecaos.messaging.inbox.listener.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        // CASCADE reaches iam.roles and iam.grants, both of which reference
        // tenants, so the registry is rebuilt through the production writer.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "tenant-failure-inspection");
        insertTenant(OTHER_TENANT, "tenant-failure-inspection-other");
        grantPlatformSupport(OPERATOR);
    }

    @Test
    void anOperatorCanInspectOneOutboxEventWithoutFilteringAList() throws Exception {
        UUID eventId = deadLetteredOutboxEvent(TENANT);

        MvcResult read = mvc.perform(get(FAILURES + "/outbox/" + eventId).with(tokenFor(OPERATOR)))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(read.getResponse().getContentAsString())
                .contains("\"eventId\":\"" + eventId + "\"")
                .contains("\"status\":\"DEAD_LETTER\"")
                .contains("\"errorCode\":\"TRANSIENT_INFRASTRUCTURE\"")
                .as("the partition key is what says whether one order or a whole stream is stuck behind this")
                .contains("\"partitionKey\"")
                .contains("\"attemptCount\":10");
    }

    @Test
    void theOutboxPayloadIsNotWhatAnOperatorIsShown() throws Exception {
        // ADR 0029. An outbox payload is a domain event and can carry a
        // customer's name, phone or address. Authority to work the failure
        // queue is not authority to read the customer record behind an item:
        // platform-support holds integration.failure.read across every tenant
        // and deliberately holds no customer.pii.reveal.
        UUID eventId = deadLetteredOutboxEvent(TENANT);

        String body = mvc.perform(get(FAILURES + "/outbox/" + eventId).with(tokenFor(OPERATOR)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("a single-item read is exactly where somebody renders the whole row 'to help debugging'")
                .doesNotContain(SENTINEL_NAME)
                .doesNotContain(SENTINEL_PHONE)
                .doesNotContain("\"payload\"")
                .doesNotContain("\"traceContext\"");
        assertThat(body)
                .as("the aggregate id is carried instead, so the business object is reachable "
                        + "through the API that owns it and checks its own authorization")
                .contains("\"aggregateId\"");
    }

    @Test
    void theInboxPayloadIsNotWhatAnOperatorIsShownEither() throws Exception {
        // Stronger than the outbox case: an inbox payload was written by a
        // producer this consumer does not control, so nothing in it has been
        // through HorecaOS's own classification.
        UUID eventId = deadLetteredInboxMessage("orders-consumer", UUID.randomUUID(), TENANT, 3);

        String body = mvc.perform(
                        get(FAILURES + "/inbox/orders-consumer/" + eventId).with(tokenFor(OPERATOR)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(SENTINEL_NAME)
                .doesNotContain(SENTINEL_PHONE)
                .doesNotContain("\"payload\"");
        assertThat(body)
                .as("the hash stands in for the payload: it discloses nothing and it is the one "
                        + "payload fact a hash-collision decision needs")
                .contains("\"payloadSha256\"");
    }

    @Test
    void anotherTenantsEventIdIsAbsentRatherThanForbidden() throws Exception {
        // The event id is a UUID the caller types. Answering 403 for a real row
        // in another tenant and 404 for an id that exists nowhere would confirm
        // which identifiers are real by watching which status comes back.
        UUID theirs = deadLetteredOutboxEvent(OTHER_TENANT);

        // The same correlation id on both, because ADR 0031 stamps a fresh one
        // into every problem body and two random ones would make the comparison
        // below fail for a reason that has nothing to do with what it tests.
        MvcResult crossTenant = mvc.perform(get(FAILURES + "/outbox/" + theirs + "?tenantId=" + TENANT)
                        .header(CorrelationIdFilter.HEADER_NAME, "failure-inspection-oracle")
                        .with(tokenFor(OPERATOR)))
                .andReturn();
        MvcResult neverExisted = mvc.perform(get(FAILURES + "/outbox/" + UUID.randomUUID() + "?tenantId=" + TENANT)
                        .header(CorrelationIdFilter.HEADER_NAME, "failure-inspection-oracle")
                        .with(tokenFor(OPERATOR)))
                .andReturn();

        assertThat(crossTenant.getResponse().getStatus())
                .as("403 here would tell the caller the id exists")
                .isEqualTo(404);
        assertThat(neverExisted.getResponse().getStatus()).isEqualTo(404);
        assertThat(crossTenant.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");

        // Status alone is not enough. A body that differed — a different detail
        // string, an extra property, anything — would restore the oracle the
        // matching status closed. RFC 9457's `instance` is the request URI and
        // therefore carries the id the caller themselves typed, which discloses
        // nothing; every other byte must match.
        assertThat(withoutRequestUri(crossTenant))
                .as("the two must be indistinguishable, body included")
                .isEqualTo(withoutRequestUri(neverExisted));
    }

    @Test
    void anotherTenantsInboxMessageIsAbsentTheSameWay() throws Exception {
        UUID theirs = deadLetteredInboxMessage("orders-consumer", UUID.randomUUID(), OTHER_TENANT, 3);

        assertThat(mvc.perform(get(FAILURES + "/inbox/orders-consumer/" + theirs + "?tenantId=" + TENANT)
                                .with(tokenFor(OPERATOR)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void theInboxKeyIsTheConsumerAndTheEventTogether() throws Exception {
        // One event reaches several consumers, each with its own attempt count,
        // its own error and its own decision to make. A read keyed on the event
        // alone would return whichever row the planner found first, and an
        // operator would retry the wrong one.
        UUID eventId = UUID.randomUUID();
        deadLetteredInboxMessage("orders-consumer", eventId, TENANT, 3);
        deadLetteredInboxMessage("notifications-consumer", eventId, TENANT, 7);

        String orders = mvc.perform(
                        get(FAILURES + "/inbox/orders-consumer/" + eventId).with(tokenFor(OPERATOR)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String notifications = mvc.perform(get(FAILURES + "/inbox/notifications-consumer/" + eventId)
                        .with(tokenFor(OPERATOR)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(orders).contains("\"consumerName\":\"orders-consumer\"").contains("\"attemptCount\":3");
        assertThat(notifications)
                .contains("\"consumerName\":\"notifications-consumer\"")
                .contains("\"attemptCount\":7");
    }

    @Test
    void aConsumerThatNeverSawTheEventIsNotFound() throws Exception {
        UUID eventId = deadLetteredInboxMessage("orders-consumer", UUID.randomUUID(), TENANT, 3);

        assertThat(mvc.perform(get(FAILURES + "/inbox/notifications-consumer/" + eventId)
                                .with(tokenFor(OPERATOR)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the pair is the key, so a real event id under the wrong consumer is simply absent")
                .isEqualTo(404);
    }

    @Test
    void aCallerWithoutTheCapabilityIsRefused() throws Exception {
        // The control. Without it every 404 above would pass just as happily in
        // a context where enforcement was off, and would prove nothing about
        // which answer the endpoint chose.
        UUID eventId = deadLetteredOutboxEvent(TENANT);

        assertThat(mvc.perform(get(FAILURES + "/outbox/" + eventId).with(tokenFor(OUTSIDER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    /** The problem body with `instance` — the caller's own request URI — removed. */
    private static String withoutRequestUri(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    private UUID deadLetteredOutboxEvent(UUID tenantId) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.outbox_events (
                    event_id, event_type, event_version, tenant_id, aggregate_type, aggregate_id,
                    topic, partition_key, correlation_id, occurred_at, payload, status,
                    attempt_count, dead_lettered_at, error_code, last_error)
                VALUES (
                    :eventId, 'OrderPlaced', 1, :tenantId, 'Order', :aggregateId,
                    'ordering.events', :aggregateId, 'correlation-1', now(),
                    CAST(:payload AS jsonb), 'DEAD_LETTER',
                    10, now(), 'TRANSIENT_INFRASTRUCTURE', 'broker unavailable')
                """)
                .param("eventId", eventId)
                .param("tenantId", tenantId)
                .param("aggregateId", UUID.randomUUID())
                .param("payload", personalPayload())
                .update();
        return eventId;
    }

    private UUID deadLetteredInboxMessage(String consumerName, UUID eventId, UUID tenantId, int attempts) {
        jdbc.sql("""
                INSERT INTO integration.inbox_messages (
                    id, consumer_name, event_id, topic, partition, record_offset, tenant_id,
                    event_type, event_version, aggregate_type, aggregate_id, correlation_id,
                    occurred_at, payload, payload_sha256, status, attempt_count,
                    dead_lettered_at, last_error_code, last_error)
                VALUES (
                    :id, :consumer, :eventId, 'ordering.events', 0, :offset, :tenantId,
                    'OrderPlaced', 1, 'Order', :aggregateId, 'correlation-1',
                    now(), CAST(:payload AS jsonb), :hash, 'DEAD_LETTER', :attempts,
                    now(), 'DOMAIN_REJECTED', 'handler refused the transition')
                """)
                .param("id", UUID.randomUUID())
                .param("consumer", consumerName)
                .param("eventId", eventId)
                .param("offset", Math.abs(consumerName.hashCode() % 1000))
                .param("tenantId", tenantId)
                .param("aggregateId", UUID.randomUUID())
                .param("payload", personalPayload())
                .param("hash", "b".repeat(64))
                .param("attempts", attempts)
                .update();
        return eventId;
    }

    private static String personalPayload() {
        return "{\"orderNumber\":\"A-17\",\"customerName\":\"" + SENTINEL_NAME + "\",\"customerPhone\":\""
                + SENTINEL_PHONE + "\"}";
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    /**
     * Platform support: cross-tenant read of the failure queue, and no mutation
     * and no PII reveal anywhere in the bundle. It is the role this endpoint is
     * actually for, so it is the one the test authorizes with.
     */
    private void grantPlatformSupport(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'failure inspection endpoint test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + PlatformRole.PLATFORM_SUPPORT.code()).getBytes(UTF_8)))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_SUPPORT))
                .update();
    }

    /**
     * Carries no realm role. {@code JdbcAuthorizationService} confers
     * {@code iam.grant.manage} on a {@code platform-admin} token, and a token
     * holding that role would make the refusal above prove nothing.
     */
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
