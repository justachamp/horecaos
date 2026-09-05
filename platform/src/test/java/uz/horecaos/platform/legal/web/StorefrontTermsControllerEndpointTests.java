package uz.horecaos.platform.legal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The storefront's read of ADR 0067's terms, unauthenticated.
 *
 * <p>Proves the {@code SecurityConfiguration} wiring, not just the controller:
 * this hits the real filter chain with no token at all, the same way a
 * customer who has not signed in yet does from the sign-in screen's own
 * "Terms of service" link. {@code /accept} and {@code /acceptance-status}
 * are exercised at the service layer ({@code TermsAcceptanceServiceTests}) —
 * they require a customer session, which is a different, already-tested
 * authentication mechanism this class does not re-prove.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontTermsControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a10-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9a10-4000-7000-8000-0000000000b1");

    private static final String TERMS_URL = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/terms";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the terms endpoint test");
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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'storefront-terms', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    @Test
    void anUnauthenticatedCallerReadsThePlatformDefaultWithNoTenantAuthored() throws Exception {
        MvcResult result = mvc.perform(get(TERMS_URL).queryParam("locale", "en").queryParam("brandName", "Osh Markazi"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"isPlatformDefault\":true")
                .contains("Osh Markazi")
                .doesNotContain("JizBiz");
    }

    @Test
    void aMissingBrandNameIsRejected() throws Exception {
        MvcResult result = mvc.perform(get(TERMS_URL).queryParam("locale", "en").queryParam("brandName", ""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void anUnknownLocaleIsRejected() throws Exception {
        MvcResult result = mvc.perform(get(TERMS_URL).queryParam("locale", "fr").queryParam("brandName", "Osh Markazi"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void acceptWithNoCustomerSessionIsUnauthenticated() throws Exception {
        MvcResult result = mvc.perform(post(TERMS_URL + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locale":"en","brandName":"Osh Markazi"}
                                """))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("accepting is a customer's own act; there is nobody to accept on behalf of without a session")
                .isEqualTo(401);
    }
}
