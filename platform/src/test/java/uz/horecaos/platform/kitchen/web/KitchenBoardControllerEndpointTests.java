package uz.horecaos.platform.kitchen.web;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code GET .../kitchen/orders/{orderId}/events} through the real HTTP stack
 * (gap map row 1.2b) — second-pass adversarial review, P11.
 *
 * <p>{@code KitchenExecutionTests} calls {@code
 * KitchenBoardController.eventsForOrder} directly and in-process; no test in
 * this package (or anywhere under {@code kitchen/}) ever went through {@code
 * MockMvc}, so {@code @RequiresCapability(ORDER_READ)} on this route was
 * never proven wired to the real {@code CapabilityEnforcementInterceptor} — a
 * session holding no capability at all being let through would not have been
 * caught. Every {@code PlatformRole} this platform grants holds {@code
 * ORDER_READ} (it is the universal "may use Operations at all" bundle — see
 * {@code navigation.ts}'s own comment on the {@code /today} route) except
 * {@code KITCHEN_DEVICE} (ADR 0079: a kiosk/printer principal, holding only
 * {@code KITCHEN_TICKET_READ}/{@code KITCHEN_TICKET_ADVANCE} and nothing
 * else on purpose) — the negative case here, the same shape {@code
 * ReportingControllerCapabilityHttpTests} uses {@code COURIER_DISPATCHER}
 * for "authenticated, holds real capabilities, but never this one." Mirrors
 * that class's shape; fixtures are limited to
 * tenant/brand/location because {@code eventsForOrder} answers an empty,
 * still-200 body for an order that never opened a ticket (see {@code
 * anOrderWithNoTicketAnswersEmptyEvents} in {@code KitchenExecutionTests}) —
 * the capability/HTTP wiring is what this suite proves, not ticket
 * production.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenBoardControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb500-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb500-6000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb500-6000-7000-8000-0000000000c1");

    /** Holds {@code ORDER_READ} at {@code LOCATION} -- any role does; {@code LOCATION_STAFF} here. */
    private static final String READER = "kitchen-events-http-reader";

    /** {@code KITCHEN_DEVICE}: holds real kitchen capabilities, but never {@code ORDER_READ}. */
    private static final String DEVICE = "kitchen-events-http-device";

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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        seedTenancy();
        grant(READER, PlatformRole.LOCATION_STAFF);
        grant(DEVICE, PlatformRole.KITCHEN_DEVICE);
    }

    @Test
    @DisplayName("a principal holding ORDER_READ reaches the order-keyed kitchen events read")
    void succeedsWithOrderRead() throws Exception {
        MvcResult result = mvc.perform(get(eventsPath(UUID.randomUUID())).with(tokenFor(READER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // No ticket was ever opened for this order id -- an ordinary, still-200 state.
        assertThat(result.getResponse().getContentAsString()).contains("\"ticketId\":null");
    }

    @Test
    @DisplayName("ORDER_READ is required -- a KITCHEN_DEVICE principal, entitled to other kitchen reads, is refused")
    void withoutOrderReadTheCallIsRefused() throws Exception {
        MvcResult result = mvc.perform(get(eventsPath(UUID.randomUUID())).with(tokenFor(DEVICE)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_READ.code());
    }

    // ------------------------------------------------------------------ fixtures

    private String eventsPath(UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/kitchen/orders/"
                + orderId + "/events";
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'kitchen-events-http-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'kitchen events http endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + LOCATION).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", LOCATION)
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
