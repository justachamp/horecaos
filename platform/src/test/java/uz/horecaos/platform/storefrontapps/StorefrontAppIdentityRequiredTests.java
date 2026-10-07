package uz.horecaos.platform.storefrontapps;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppHeaders;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0070's rollout stage three: the identity is required, and a request without it is
 * refused by name, on the anonymous browse surface as much as anywhere.
 *
 * <p>A separate class because the switch is a property of the whole application context
 * ({@code horecaos.storefront.app-identity.required}), and the other suite's
 * stage-one tolerance is a behaviour this one must not be able to disturb. The app here is
 * inserted straight into the registry: how an app gets registered is that suite's business, and
 * this one asks only what the check does once the requirement is on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontAppIdentityRequiredTests {

    private static final UUID TENANT = UUID.fromString("018fb100-7000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb100-7000-7000-8000-0000000000b1");
    private static final String ORIGIN = "https://shop.example.uz";

    // NullAway does not recognise @DynamicPropertySource as a field initializer the way
    // it does @BeforeAll/@BeforeEach; `db` is always set there before any @Test method runs.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront app identity test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.storefront.app-identity.required", () -> "true");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private UUID appId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE storefront_app.authorisations").update();
        jdbc.sql("DELETE FROM storefront_app.apps").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'identity-required', 'Legal', 'Required', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        appId = Ids.newId();
        jdbc.sql("""
                INSERT INTO storefront_app.apps
                    (id, name, vendor, client_type, origin_allowlist, status, registered_by)
                VALUES (:id, 'Required Shop', 'Acme', 'PUBLIC', ARRAY[:origin]::text[], 'ACTIVE', 'fixture')
                """).param("id", appId).param("origin", ORIGIN).update();
        jdbc.sql("""
                INSERT INTO storefront_app.authorisations
                    (id, tenant_id, brand_id, app_id, status, granted_by, granted_at)
                VALUES (:id, :tenantId, :brandId, :appId, 'ACTIVE', 'fixture', :now)
                """)
                .param("id", Ids.newId())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("appId", appId)
                .param("now", Instant.now().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Test
    @DisplayName("a storefront request with no app identity is refused by name, even on the anonymous browse paths")
    void aRequestWithNoIdentityIsRefusedByName() throws Exception {
        for (String path : new String[] {
            "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/support/faq",
            "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/terms",
            "/api/v1/storefront/channel-hostnames/shop.example.uz",
            "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/me"
        }) {
            MvcResult refused = mvc.perform(get(path)).andReturn();
            assertThat(refused.getResponse().getStatus()).as(path).isEqualTo(401);
            assertThat(refused.getResponse().getContentAsString()).as(path).contains("APP_IDENTITY_REQUIRED");
        }
    }

    @Test
    void aRegisteredAuthorisedAppIsStillServed() throws Exception {
        MvcResult served = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/support/faq")
                        .header(StorefrontAppHeaders.APP_ID, appId.toString())
                        .header(HttpHeaders.ORIGIN, ORIGIN))
                .andReturn();

        assertThat(served.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theRequirementIsTheStorefrontSurfacesAndNobodyElses() throws Exception {
        MvcResult health = mvc.perform(get("/actuator/health")).andReturn();

        assertThat(health.getResponse().getContentAsString(UTF_8)).doesNotContain("APP_IDENTITY_REQUIRED");
        assertThat(health.getResponse().getStatus()).isNotEqualTo(401);
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
