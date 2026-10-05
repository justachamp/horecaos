package uz.horecaos.platform.telemetry.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.telemetry.infrastructure.realtime.SseStreamRegistry;

/**
 * The brand-wide order queue (gap map row {@code 1.1}) through the real stream endpoint: the
 * authorization the controller's unit test fakes, and the delivery its registry test drives
 * without a socket.
 *
 * <p>A board over every branch of a brand asks for {@code scope=BRAND:<id>}. Whether it gets
 * the stream is decided by the principal's grant at the brand -- {@code BRAND_MANAGER} holds
 * {@code order.read} there, {@code LOCATION_MANAGER} holds it at one branch only -- and a
 * signal published at the brand reaches the stream that asked, while the same principal's
 * branch stream is untouched by it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsStreamBrandScopeHttpTests {

    private static final UUID TENANT = UUID.fromString("018fe200-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe200-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018fe200-4000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018fe200-4000-7000-8000-0000000000c1");

    private static final String BRAND_BOARD = "stream-brand-http-brand-manager";
    private static final String BRANCH_BOARD = "stream-brand-http-location-manager";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the stream endpoint test");
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

    @Autowired
    @SuppressWarnings("NullAway")
    private SseStreamRegistry registry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'stream-brand', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        for (UUID brand : List.of(BRAND, OTHER_BRAND)) {
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :t, :code, :slug, 'Brand', 'ACTIVE', 0)
                    """)
                    .param("id", brand)
                    .param("t", TENANT)
                    .param("code", brand.equals(BRAND) ? "MAIN" : "OTHER")
                    .param("slug", brand.equals(BRAND) ? "main" : "other")
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        grant(BRAND_BOARD, PlatformRole.BRAND_MANAGER, ScopeType.BRAND, BRAND);
        grant(BRANCH_BOARD, PlatformRole.LOCATION_MANAGER, ScopeType.LOCATION, LOCATION);
    }

    @Test
    @DisplayName("a principal who reads the brand opens the brand-wide queue, and a change at the brand reaches it")
    void aBrandReaderGetsTheBrandQueueAndItsSignals() throws Exception {
        int before = registry.openStreams();

        MvcResult opened = open(BRAND_BOARD, "BRAND:" + BRAND);

        assertThat(opened.getRequest().isAsyncStarted())
                .as("a stream is opened, not refused: %s", opened.getResponse().getContentAsString())
                .isTrue();
        assertThat(registry.openStreams()).isEqualTo(before + 1);

        Instant now = Instant.now();
        UUID order = UUID.randomUUID();
        registry.onSignal(
                RealtimeSignal.of(TENANT, StreamChannel.ORDER_QUEUE, ScopeKey.brand(BRAND), "Order", order, 3L, now));
        registry.tick(now.plus(Duration.ofSeconds(1)));

        String wire = opened.getResponse().getContentAsString(UTF_8);
        assertThat(wire)
                .contains("event:signal")
                .contains("\"channel\":\"order_queue\"")
                .contains("\"scope\":\"BRAND:" + BRAND + "\"")
                .contains(order.toString());
    }

    @Test
    @DisplayName(
            "the brand stream does not hear a change published only at a branch, and the branch stream does not hear the brand's copy")
    void eachStreamHearsOnlyItsOwnScope() throws Exception {
        MvcResult brandStream = open(BRAND_BOARD, "BRAND:" + BRAND);
        MvcResult branchStream = open(BRAND_BOARD, "LOCATION:" + LOCATION);
        assertThat(brandStream.getRequest().isAsyncStarted()).isTrue();
        assertThat(branchStream.getRequest().isAsyncStarted()).isTrue();

        Instant now = Instant.now();
        registry.onSignal(RealtimeSignal.of(
                TENANT, StreamChannel.ORDER_QUEUE, ScopeKey.location(LOCATION), "Order", UUID.randomUUID(), 1L, now));
        registry.tick(now.plus(Duration.ofSeconds(1)));

        assertThat(branchStream.getResponse().getContentAsString(UTF_8))
                .contains("\"scope\":\"LOCATION:" + LOCATION + "\"");
        assertThat(brandStream.getResponse().getContentAsString(UTF_8))
                .as("the brand's board hears the brand's copy, which ordering publishes beside the branch's")
                .doesNotContain("event:signal");
    }

    @Test
    @DisplayName("a principal who reads one branch is refused the brand-wide queue, and keeps the branch's")
    void aBranchReaderIsRefusedTheBrandQueue() throws Exception {
        int before = registry.openStreams();

        MvcResult refused = open(BRANCH_BOARD, "BRAND:" + BRAND);

        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isEqualTo(403);
        assertThat(registry.openStreams()).as("nothing was opened").isEqualTo(before);

        MvcResult branch = open(BRANCH_BOARD, null);
        assertThat(branch.getRequest().isAsyncStarted())
                .as("the branch board is unaffected: %s", branch.getResponse().getContentAsString())
                .isTrue();
    }

    @Test
    @DisplayName("a brand key naming another brand than the one in the path is refused before any capability is asked")
    void aForeignBrandKeyIsRefused() throws Exception {
        int before = registry.openStreams();

        MvcResult refused = open(BRAND_BOARD, "BRAND:" + OTHER_BRAND);

        assertThat(refused.getRequest().isAsyncStarted()).isFalse();
        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isBetween(400, 403);
        assertThat(registry.openStreams()).isEqualTo(before);
    }

    // ------------------------------------------------------------------------------ helpers

    private MvcResult open(String subject, @Nullable String scope) throws Exception {
        var request = get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                        + "/operations/streams")
                .param("channels", "order_queue")
                .accept("text/event-stream")
                .with(tokenFor(subject));
        if (scope != null) {
            request = request.param("scope", scope);
        }
        return mvc.perform(request).andReturn();
    }

    private void grant(String subject, PlatformRole role, ScopeType scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'brand stream http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType.name())
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
