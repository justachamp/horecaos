package uz.horecaos.platform.customers.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
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
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The guest-initiated callback (ADR 0111 §4), through the storefront's own door: a signed-in
 * customer asks the restaurant to ring her back and the request lands in the call centre's queue as
 * one lead linked to her own account.
 *
 * <p>Ownership is what authorises it -- there is no capability a guest holds -- so the cases that
 * matter are the refusals: no token is the filter chain's 401, a token with no account at the brand is
 * a 404 and not a 403, the key and the number are required, and a bad number is refused without being
 * repeated.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontCallbackRequestEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    private static final UUID TENANT = UUID.fromString("018f9f10-8000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-8000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-8000-7000-8000-0000000000c1");

    private static final String GUEST = "callback-guest";
    private static final String NO_ACCOUNT = "callback-never-signed-up";
    private static final String PHONE = "+998 90 123 45 67";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the callback request test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // (issuer, subject) is the identity, so the seeded principal link names the issuer the resolver asks with.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private UUID guestAccount;

    @BeforeEach
    void seed() {
        OrderBoardFixtures fixtures = new OrderBoardFixtures(jdbc);
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        fixtures.clean();
        fixtures.tenant(TENANT, "callback-requests", BRAND, LOCATION);

        guestAccount = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, identity_partition_brand_id, status,
                    created_at, updated_at)
                VALUES (:id, :t, NULL, 'ACTIVE', :now, :now)
                """)
                .param("id", guestAccount)
                .param("t", TENANT)
                .param("now", now)
                .update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id, identity_partition_brand_id,
                    customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :t, NULL, :account, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("account", guestAccount)
                .param("issuer", ISSUER)
                .param("subject", GUEST)
                .param("now", now)
                .update();
    }

    @Test
    @DisplayName("a signed-in guest's request is one lead in the call centre's queue, linked to her own account")
    void aCallbackRequestIsALeadLinkedToTheGuest() throws Exception {
        MvcResult asked = mvc.perform(post(path())
                        .with(token(GUEST))
                        .header("Idempotency-Key", "callback-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"note\":\"Please ring after six\"}".formatted(PHONE)))
                .andReturn();

        assertThat(asked.getResponse().getStatus())
                .as("202: what was created is a work item for a person, not a call")
                .isEqualTo(202);
        UUID leadId = UUID.fromString(JSON.readTree(asked.getResponse().getContentAsString())
                .path("leadId")
                .asText());
        var row = jdbc.sql("""
                SELECT source, status, customer_account_id, created_by, brand_id, phone_masked, phone_encrypted,
                       notes_encrypted
                  FROM customer.leads WHERE id = :id
                """).param("id", leadId).query().singleRow();
        assertThat(row)
                .containsEntry("source", "CALLBACK_REQUEST")
                .containsEntry("status", "NEW")
                .containsEntry("customer_account_id", guestAccount)
                .containsEntry("created_by", "storefront-customer:" + guestAccount)
                .containsEntry("brand_id", BRAND)
                .containsEntry("phone_masked", "+998 ** *** 45 67");
        assertThat(String.valueOf(row.get("phone_encrypted")) + row.get("notes_encrypted"))
                .as("the number and her note are ciphertext at rest")
                .doesNotContain("901234567")
                .doesNotContain("six");
        assertThat(jdbc.sql("SELECT actor_type || ':' || actor_subject FROM audit.audit_events "
                                + "WHERE action_code = 'customer.lead.registered'")
                        .query(String.class)
                        .single())
                .as("a service actor: the guest holds no capability, so none is claimed")
                .isEqualTo("SERVICE:storefront-customer:" + guestAccount);
        assertThat(jdbc.sql("SELECT event_type FROM integration.outbox_events")
                        .query(String.class)
                        .list())
                .containsExactly("LeadRegistered");
    }

    @Test
    @DisplayName("a request on behalf of a company is a catering enquiry")
    void aCompanyRequestIsACateringEnquiry() throws Exception {
        mvc.perform(post(path())
                        .with(token(GUEST))
                        .header("Idempotency-Key", "callback-company")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"forCompany\":true}".formatted(PHONE)))
                .andReturn();

        assertThat(jdbc.sql("SELECT source FROM customer.leads")
                        .query(String.class)
                        .single())
                .isEqualTo("B2B_CATERING_ENQUIRY");
    }

    @Test
    @DisplayName("a retried request under one key is one lead")
    void aRetriedRequestIsOneLead() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(path())
                            .with(token(GUEST))
                            .header("Idempotency-Key", "callback-retry")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"%s\"}".formatted(PHONE)))
                    .andReturn();
        }

        assertThat(jdbc.sql("SELECT count(*) FROM customer.leads")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("no token never reaches the handler; a token with no account here is not found, not refused")
    void theRefusalsAreTheOnesTheStorefrontAlwaysGives() throws Exception {
        assertThat(mvc.perform(post(path())
                                .header("Idempotency-Key", "anonymous")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"%s\"}".formatted(PHONE)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        MvcResult stranger = mvc.perform(post(path())
                        .with(token(NO_ACCOUNT))
                        .header("Idempotency-Key", "stranger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\"}".formatted(PHONE)))
                .andReturn();
        assertThat(stranger.getResponse().getStatus()).isEqualTo(404);

        assertThat(mvc.perform(post(path())
                                .with(token(GUEST))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"%s\"}".formatted(PHONE)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("no Idempotency-Key")
                .isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM customer.leads")
                        .query(Long.class)
                        .single())
                .as("none of the three left a lead behind")
                .isZero();
    }

    @Test
    @DisplayName("a number nobody can ring is refused without being repeated")
    void anUnusableNumberIsRefused() throws Exception {
        MvcResult refused = mvc.perform(post(path())
                        .with(token(GUEST))
                        .header("Idempotency-Key", "bad-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12ab\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("12ab");
    }

    private static String path() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/me/callback-requests";
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
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
