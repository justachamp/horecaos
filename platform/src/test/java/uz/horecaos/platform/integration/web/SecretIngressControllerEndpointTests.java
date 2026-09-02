package uz.horecaos.platform.integration.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0065's write-only door: the round trip against a real {@link
 * SecretResolver} bean, and every property that makes it a door rather than a
 * leak — no read-back path, no echo in the response, no echo in the audit
 * fact, and no echo in any log line captured across the whole call.
 *
 * <p>Deliberately not gated on Docker: this suite runs under the {@code
 * environment} secrets provider like every other {@code @SpringBootTest} here,
 * so {@link uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretWriter}
 * and {@link uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver}
 * are exactly what is under test — the same beans a real HTTP caller reaches in
 * this profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecretIngressControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-5000-7000-8000-0000000000f1");

    private static final String OWNER = "secret-door-owner";
    private static final String FINANCE = "secret-door-finance";

    /**
     * Its own principal, separate from {@link #OWNER}: the ADR 0033 limiter's
     * bucket is keyed by (operation, tenant, principal) and this class's
     * {@code RateLimiter} bean is a real singleton shared across every test
     * method in this class's cached Spring context, so a test that deliberately
     * exhausts the quota under {@link #OWNER} would starve every other test
     * method sharing that subject, in whatever order JUnit happens to run them.
     */
    private static final String RATE_LIMIT_PROBE = "secret-door-rate-limit-probe";

    private static final String DOOR = "/api/v1/control-plane/tenants/" + TENANT + "/integrations/secrets";

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
    private SecretResolver secrets;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'secret-door-endpoint', 'Secret Door', 'Secret Door',
                        'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        // TENANT_FINANCE proves the capability decided this, not tenant
        // membership -- the same fixture reasoning
        // ProviderInstallationSecretRotationEndpointTests uses.
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
        grant(RATE_LIMIT_PROBE, PlatformRole.TENANT_OWNER);
    }

    @Test
    void aWrittenValueResolvesToExactlyItselfAndNeverAppearsInTheResponse() throws Exception {
        MvcResult result = mvc.perform(post(DOOR)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "door-write-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", "a-real-bot-token")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("only a reference leaves this endpoint")
                .contains("\"reference\":\"horecaos:")
                .doesNotContain("a-real-bot-token");

        String reference = referenceFrom(body);
        assertThat(secrets.resolveFresh(SecretReference.parse(reference)).reveal())
                .as("what the door wrote is exactly what the resolver reads back")
                .isEqualTo("a-real-bot-token");
    }

    @Test
    void thereIsNoReadBackEndpointOfAnyKind() throws Exception {
        MvcResult write = mvc.perform(post(DOOR)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "door-write-noread")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", "no-readback-token")))
                .andReturn();
        String reference = referenceFrom(write.getResponse().getContentAsString());

        MvcResult getOnTheDoor = mvc.perform(get(DOOR).with(tokenFor(OWNER))).andReturn();
        assertThat(getOnTheDoor.getResponse().getStatus())
                .as("no GET mapping exists on this path at all")
                .isIn(404, 405);

        MvcResult getWithReference = mvc.perform(
                        get(DOOR + "/" + UUID.randomUUID()).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(getWithReference.getResponse().getStatus()).isIn(404, 405);
        assertThat(reference).isNotBlank();
    }

    @Test
    void theAuditFactNamesTheReferenceAndNeverTheValue() throws Exception {
        mvc.perform(post(DOOR)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "door-write-audit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(writeRequest("PROVIDER_PAYMENT", "CLICK", "a-click-secret-key")));

        List<Map<String, Object>> auditRows =
                jdbc.sql("""
                SELECT change_document FROM audit.audit_events
                WHERE action_code = 'integration.secret_written' AND actor_subject = :subject
                """).param("subject", OWNER).query().listOfRows();
        assertThat(auditRows).hasSize(1);
        String changeDocument = String.valueOf(auditRows.getFirst().get("change_document"));
        assertThat(changeDocument)
                .contains("PROVIDER_PAYMENT")
                .contains("CLICK")
                .contains("horecaos:")
                .doesNotContain("a-click-secret-key");
    }

    @Test
    void aPlatformOnlyCategoryIsRejectedNotWritten() throws Exception {
        MvcResult result = mvc.perform(post(DOOR)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "door-write-forbidden-category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest("DATABASE", "POSTGRES", "should-never-be-written")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .doesNotContain("should-never-be-written");
    }

    @Test
    void aPrincipalWithoutInstallationManageCannotWriteASecret() throws Exception {
        MvcResult refused = mvc.perform(post(DOOR)
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "door-write-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", "should-not-land")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.INTEGRATION_INSTALLATION_MANAGE.code())
                .doesNotContain("should-not-land");
    }

    @Test
    void writingWithoutAnIdempotencyKeyIsRejected() throws Exception {
        MvcResult result = mvc.perform(post(DOOR)
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", "no-key-token")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void repeatedWritesEventuallyHitTheRateLimit() throws Exception {
        int status = 200;
        int calls = 0;
        // WRITE_POLICY is strictPerMinute(20); 21 calls from the same
        // tenant+principal must trip it within one test run. RATE_LIMIT_PROBE,
        // not OWNER: this deliberately exhausts its own quota and must not
        // starve the other test methods sharing OWNER's bucket in this class's
        // cached Spring context.
        for (; calls < 25 && status != 429; calls++) {
            MvcResult result = mvc.perform(post(DOOR)
                            .with(tokenFor(RATE_LIMIT_PROBE))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "door-write-rate-" + calls)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", "rate-token-" + calls)))
                    .andReturn();
            status = result.getResponse().getStatus();
        }

        assertThat(status)
                .as("the strict per-minute policy must eventually deny this caller")
                .isEqualTo(429);
    }

    @Test
    void theRawValueNeverAppearsInAnyCapturedLogLine() throws Exception {
        ListAppender<ILoggingEvent> lines = captureAllLogs();
        String secretValue = "should-never-be-logged-anywhere-xyz123";
        try {
            mvc.perform(post(DOOR)
                    .with(tokenFor(OWNER))
                    .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "door-write-log-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", secretValue)));

            assertThat(lines.list)
                    .as("ADR 0028: no secret value appears in any captured output")
                    .noneMatch(event -> event.getFormattedMessage().contains(secretValue));
        } finally {
            releaseAllLogs(lines);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static String writeRequest(String category, String providerType, String value) {
        return "{\"category\":\"%s\",\"providerType\":\"%s\",\"value\":\"%s\"}"
                .formatted(category, providerType, value);
    }

    private static String referenceFrom(String body) {
        int start = body.indexOf("\"reference\":\"") + "\"reference\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'secret door endpoint test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    private static ListAppender<ILoggingEvent> captureAllLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger().addAppender(appender);
        return appender;
    }

    private static void releaseAllLogs(ListAppender<ILoggingEvent> appender) {
        rootLogger().detachAppender(appender);
        appender.stop();
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
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
