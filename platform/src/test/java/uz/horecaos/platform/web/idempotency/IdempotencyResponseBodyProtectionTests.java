package uz.horecaos.platform.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0029 against ADR 0031: what the idempotency table is allowed to remember.
 *
 * <p>{@code platform.idempotency_records.response_body} stores the verbatim
 * response of every effectful endpoint for at least twenty-four hours. Three of
 * those endpoints answer with personal data that the envelope stack had just
 * finished decrypting — the customer's own address and their display name — so
 * the plaintext an ADR 0029 column exists to prevent was being written back out
 * beside it, in a table that is not even tenant-scoped.
 *
 * <p>The defect belongs to the shared mechanism rather than to any controller.
 * The storefront address endpoints did not create it; they gave it its worst
 * payload, and a controller-by-controller fix would leave the next author to
 * rediscover it. So the assertions below are about the column, not about the
 * handler: whatever an endpoint answers, the line the customer typed is not
 * readable in {@code response_body}.
 *
 * <p>Both directions are held. A body carrying personal data must not be
 * legible in the table, and a replay must still return that same body to the
 * caller it belongs to — a fix that merely stopped storing the body would pass
 * the first assertion and silently turn every retried address save into an
 * empty 201.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyResponseBodyProtectionTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String OWNER = "protection-owner-subject";

    /**
     * The line the envelope exists to keep out of everything else, and the one
     * the reproduction greps for. Distinctive enough that finding it in a text
     * column cannot be a coincidence.
     */
    private static final String OWNERS_STREET = "Chinobod ko'chasi 12A, kvartira 47";

    /** A second protected field on the same response, so a partial fix fails too. */
    private static final String OWNERS_INSTRUCTION = "Ring the top bell";

    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required to read the idempotency table this test is about");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        // A real key-encryption key. Stubbing FieldProtection here would make the
        // ciphertext agree with itself by construction, and the assertion that a
        // replay decrypts would then be about the stub rather than about ADR 0029.
        registry.add("horecaos.secrets.data_encryption.platform.kek",
                () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM customer.addresses WHERE tenant_id = :t").param("t", TENANT).update();
        jdbc.sql("DELETE FROM customer.principal_links WHERE tenant_id = :t").param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id = :t").param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM platform.idempotency_records WHERE tenant_id = :t")
                .param("t", TENANT).update();

        seedEstate();
        account(OWNER);
    }

    // ----------------------------------------------------------- the reproduction

    @Test
    @DisplayName("an address saved through an idempotent endpoint is not legible in the table")
    void theIdempotencyTableDoesNotHoldTheAddressInClear() throws Exception {
        MvcResult created = saveAddress("save-the-address");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getContentAsString())
                .as("the response really does carry the decrypted address, or this test proves "
                        + "nothing about what was stored")
                .contains(OWNERS_STREET);

        List<String> stored = storedBodies();

        assertThat(stored)
                .as("the endpoint is @Idempotent, so a record was written and there is "
                        + "something to inspect")
                .isNotEmpty();
        assertThat(stored)
                .as("""
                        ADR 0029: this text exists nowhere in clear. It was written to
                        platform.idempotency_records.response_body as plain text and kept
                        for twenty-four hours by IdempotencyPurgeJob.""")
                .noneSatisfy(body -> assertThat(body).contains(OWNERS_STREET));
        assertThat(stored)
                .as("a delivery instruction is personal data too, and is encrypted in its "
                        + "own column for the same reason")
                .noneSatisfy(body -> assertThat(body).contains(OWNERS_INSTRUCTION));

        // Absence of the address is not evidence that this works: dropping the
        // body entirely would satisfy every assertion above. So say what the
        // column now holds, rather than only what it does not.
        assertThat(jdbc.sql("""
                SELECT response_body_protected
                  FROM platform.idempotency_records
                 WHERE tenant_id = :t AND response_body IS NOT NULL
                """).param("t", TENANT).query(Boolean.class).list())
                .as("the record says it holds an envelope")
                .isNotEmpty()
                .containsOnly(true);
        assertThat(stored)
                .allSatisfy(body -> assertThat(ProtectedValue.deserialize(body).ciphertext())
                        .as("and what it holds really is one -- a body that had merely been "
                                + "dropped would have passed everything above")
                        .isNotEmpty());
    }

    @Test
    @DisplayName("the whole row carries no plaintext, not merely the column the fix touched")
    void noColumnOfTheRecordHoldsTheAddress() throws Exception {
        saveAddress("save-the-address-again");

        List<String> rows = jdbc.sql("""
                SELECT concat_ws('|', scope_key, idempotency_key, principal_subject,
                                 request_hash, status, response_status::text, response_body)
                  FROM platform.idempotency_records
                 WHERE tenant_id = :t
                """).param("t", TENANT).query(String.class).list();

        assertThat(rows).isNotEmpty();
        assertThat(rows)
                .as("moving the plaintext to another column of the same row would not be a fix")
                .noneSatisfy(row -> assertThat(row).contains(OWNERS_STREET));
    }

    // -------------------------------------------------------------- the guarantee

    @Test
    @DisplayName("a retried address save replays the first response, address and all")
    void aReplayStillReturnsTheAddressItRecorded() throws Exception {
        MvcResult first = saveAddress("a-retried-save");
        MvcResult replay = saveAddress("a-retried-save");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus())
                .as("a replay reproduces the recorded status, not a fresh 201 from a second run")
                .isEqualTo(201);
        assertThat(replay.getResponse().getHeader(IdempotencyInterceptor.REPLAYED_HEADER))
                .as("and says it was a replay rather than a second execution")
                .isEqualTo("true");

        assertThat(replay.getResponse().getContentAsString())
                .as("""
                        ADR 0031 promises the retry the same answer. Storing no body would
                        satisfy the reproduction above and break this: the customer would
                        get an empty 201 and their addresses screen would render nothing.""")
                .isEqualTo(first.getResponse().getContentAsString());

        JsonNode body = JSON.readTree(replay.getResponse().getContentAsString());
        assertThat(body.path("fields").path("line1").asText())
                .as("the replayed body is the plaintext the owner is entitled to, so the "
                        + "record round-tripped through the envelope rather than being dropped")
                .isEqualTo(OWNERS_STREET);
        assertThat(body.path("deliveryInstructions").asText()).isEqualTo(OWNERS_INSTRUCTION);

        assertThat(addressCount())
                .as("and the retry caused no second address, which is what the key is for")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refusal from a classified endpoint is protected too, and still replays")
    void evenTheRefusalFromAClassifiedEndpointIsProtected() throws Exception {
        // The classification is a property of the handler's declared response
        // type, so it covers whatever that handler actually wrote -- including a
        // 400. That over-approximates, and deliberately: a validation refusal is
        // one message away from quoting the field it rejected, and deciding per
        // response what a body "really" contains is the guess this fix exists to
        // stop making. The cost is that this one problem document is no longer
        // greppable in the table; the endpoint still returns it in full.
        String refusal = """
                {"label":"Uy","fields":{"line1":"Somewhere"},
                 "latitude":41.3,"longitude":69.2,"coordinateSource":"GEOCODER"}
                """;

        MvcResult first = mvc.perform(post(me() + "/addresses").with(token(OWNER))
                .header("Idempotency-Key", "a-refusal").contentType(MediaType.APPLICATION_JSON)
                .content(refusal)).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(400);
        assertThat(first.getResponse().getContentAsString()).contains("VALIDATION_FAILED");

        assertThat(storedBodies())
                .as("a business refusal is a settled outcome and is recorded, so there is a "
                        + "body here to have got wrong")
                .isNotEmpty();

        MvcResult replay = mvc.perform(post(me() + "/addresses").with(token(OWNER))
                .header("Idempotency-Key", "a-refusal").contentType(MediaType.APPLICATION_JSON)
                .content(refusal)).andReturn();

        assertThat(replay.getResponse().getHeader(IdempotencyInterceptor.REPLAYED_HEADER))
                .isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString())
                .as("the recorded refusal comes back intact through the envelope, rather than "
                        + "as ciphertext or as an empty 400")
                .isEqualTo(first.getResponse().getContentAsString());
    }

    // -------------------------------------------------------------------- fixtures

    private List<String> storedBodies() {
        return jdbc.sql("""
                SELECT coalesce(response_body, '')
                  FROM platform.idempotency_records
                 WHERE tenant_id = :t
                """).param("t", TENANT).query(String.class).list();
    }

    private int addressCount() {
        return jdbc.sql("SELECT count(*) FROM customer.addresses WHERE tenant_id = :t")
                .param("t", TENANT).query(Integer.class).single();
    }

    private MvcResult saveAddress(String idempotencyKey) throws Exception {
        return mvc.perform(post(me() + "/addresses").with(token(OWNER))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Uy","fields":{"line1":"%s","city":"Toshkent"},
                                 "deliveryInstructions":"%s","coordinateSource":"LANDMARK_ONLY"}
                                """.formatted(OWNERS_STREET, OWNERS_INSTRUCTION)))
                .andReturn();
    }

    private void seedEstate() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", TENANT).param("slug", "protection-tenant").update();
        jdbc.sql("""
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from)
                VALUES (:id, :tenantId, 1, 'TENANT_SHARED', TIMESTAMPTZ '2020-01-01T00:00:00Z')
                ON CONFLICT DO NOTHING
                """).param("id", UUID.nameUUIDFromBytes(TENANT.toString().getBytes()))
                .param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                ON CONFLICT (id) DO NOTHING
                """).param("id", BRAND).param("tenantId", TENANT)
                .param("slug", "main".toLowerCase(Locale.ROOT)).update();
    }

    private void account(String subject) {
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id,
                    identity_partition_brand_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, NULL, 'ACTIVE', :now, :now)
                """).param("id", accountId).param("tenantId", TENANT).param("now", now).update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id,
                    identity_partition_brand_id, customer_account_id, issuer, subject, status,
                    linked_at)
                VALUES (:id, :tenantId, NULL, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT)
                .param("accountId", accountId).param("issuer", ISSUER).param("subject", subject)
                .param("now", now).update();
    }

    private static String me() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/me";
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        /** Avoids contacting a real issuer; this suite exercises the MVC chain. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none")
                    .claim("sub", "unused").build();
        }
    }
}
