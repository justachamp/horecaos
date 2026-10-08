package uz.horecaos.platform.integration.web.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0146 Decision 4 and 5, over HTTP, unauthenticated, as a gateway would call it.
 *
 * <p>No JWT anywhere: the endpoint is {@code permitAll} on the filter chain and
 * authenticates inside, so a request that carried a token would prove the wrong
 * thing. Both a generic gateway that can carry a secret and VAS, which cannot, are
 * enabled here; {@link SmsReceiptDisabledByDefaultEndpointTests} is the same
 * endpoint as it ships.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmsReceiptEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-5000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-5000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-5000-7000-8000-0000000000b2");
    private static final UUID GENERIC_INSTALLATION = UUID.fromString("018f9b20-5000-7000-8000-0000000000c1");
    private static final UUID VAS_INSTALLATION = UUID.fromString("018f9b20-5000-7000-8000-0000000000c2");
    private static final UUID OTHER_INSTALLATION = UUID.fromString("018f9b20-5000-7000-8000-0000000000c3");
    private static final String SECRET = "a-receipt-callback-secret";
    private static final String HEADER = "X-HorecaOS-Receipt-Secret";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add("horecaos.sms.receipts.enabled-provider-types", () -> "GENERIC_SMS, SMSGW_VAS");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SecretIngressGateway door;

    private SmsReceiptFixture fixture;
    private UUID genericBinding;
    private UUID vasBinding;
    private UUID otherBinding;

    @BeforeEach
    void seed() {
        fixture = new SmsReceiptFixture(jdbc);
        fixture.truncate();
        fixture.tenantWithBrand(TENANT, BRAND, "receipts-one");
        fixture.tenantWithBrand(OTHER_TENANT, OTHER_BRAND, "receipts-two");
        fixture.environment("sms-generic-env", "GENERIC_SMS");
        fixture.environment("sms-vas-env", "SMSGW_VAS");

        String reference = door.write(SecretCategory.PROVIDER_NOTIFICATION, "tenant-receipts", SecretValue.of(SECRET))
                .toString();
        genericBinding = fixture.installation(
                GENERIC_INSTALLATION, TENANT, BRAND, "GENERIC_SMS", "sms-generic-env", "ACTIVE", reference);
        vasBinding = fixture.installation(VAS_INSTALLATION, TENANT, BRAND, "SMSGW_VAS", "sms-vas-env", "ACTIVE", null);
        otherBinding = fixture.installation(
                OTHER_INSTALLATION, OTHER_TENANT, OTHER_BRAND, "GENERIC_SMS", "sms-generic-env", "ACTIVE", reference);
    }

    @Test
    @DisplayName("a delivered receipt advances the attempt it names, with the provider's own word kept")
    void aDeliveredReceiptAdvancesTheAttempt() throws Exception {
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-1", null);

        assertThat(callGeneric(SECRET, """
                {"messageId":"gen-1","status":"DELIVERED"}""").getResponse().getStatus()).isEqualTo(200);

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("DELIVERED");
        assertThat(fixture.eventStatuses(attempt)).containsExactly("ACCEPTED", "DELIVERED");
        assertThat(jdbc.sql("SELECT acknowledged_at IS NOT NULL FROM notifications.delivery_attempts WHERE id = :id")
                        .param("id", attempt)
                        .query(Boolean.class)
                        .single())
                .as("only a confirmed delivery earns an acknowledgement time")
                .isTrue();
    }

    @Test
    @DisplayName("the same receipt twice is one effect")
    void aDuplicateIsANoOp() throws Exception {
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-dup", null);
        String body = """
                {"messageId":"gen-dup","status":"DELIVERED"}""";

        callGeneric(SECRET, body);
        long eventsAfterFirst = fixture.count("notifications.delivery_status_events");
        long inboxAfterFirst = fixture.count("integration.inbox_messages");
        assertThat(callGeneric(SECRET, body).getResponse().getStatus()).isEqualTo(200);

        assertThat(fixture.count("notifications.delivery_status_events")).isEqualTo(eventsAfterFirst);
        assertThat(fixture.count("integration.inbox_messages"))
                .isEqualTo(inboxAfterFirst)
                .isEqualTo(1);
        assertThat(fixture.eventStatuses(attempt)).containsExactly("ACCEPTED", "DELIVERED");
    }

    @Test
    @DisplayName("DELIVERED then a late SENT does not regress the attempt")
    void anOutOfOrderReceiptNeverRegresses() throws Exception {
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-order", null);

        callGeneric(SECRET, """
                {"messageId":"gen-order","status":"DELIVERED"}""");
        callGeneric(SECRET, """
                {"messageId":"gen-order","status":"SENT"}""");

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("DELIVERED");
        assertThat(fixture.eventStatuses(attempt))
                .as("the late fact would not advance anything, so it is not stored")
                .containsExactly("ACCEPTED", "DELIVERED");
    }

    @Test
    @DisplayName("SENT then DELIVERED advances in order, and nothing is resent either way")
    void anInOrderLadderAdvances() throws Exception {
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-ladder", null);

        callGeneric(SECRET, """
                {"messageId":"gen-ladder","status":"SENT"}""");
        assertThat(fixture.attemptStatus(attempt))
                .as("handed to the operator is not delivered")
                .isEqualTo("ACCEPTED");
        callGeneric(SECRET, """
                {"messageId":"gen-ladder","status":"DELIVERED"}""");

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("DELIVERED");
        assertThat(fixture.eventStatuses(attempt)).containsExactly("ACCEPTED", "DISPATCHED", "DELIVERED");
        assertThat(fixture.count("notifications.delivery_attempts"))
                .as("a receipt never makes a second attempt")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a forged id is dropped, and a callback creates no data")
    void aForgedIdCreatesNothing() throws Exception {
        accepted(genericBinding, "GENERIC_SMS", "gen-real", null);
        long attempts = fixture.count("notifications.delivery_attempts");
        long notifications = fixture.count("notifications.notifications");
        long events = fixture.count("notifications.delivery_status_events");
        long suppressions = fixture.count("marketing.suppressions");

        MvcResult result = callGeneric(SECRET, """
                {"messageId":"never-sent","status":"BLACKLISTED"}""");

        assertThat(result.getResponse().getStatus())
                .as("the provider must not retry a hopeless body")
                .isEqualTo(200);
        assertThat(fixture.count("notifications.delivery_attempts")).isEqualTo(attempts);
        assertThat(fixture.count("notifications.notifications")).isEqualTo(notifications);
        assertThat(fixture.count("notifications.delivery_status_events")).isEqualTo(events);
        assertThat(fixture.count("marketing.suppressions")).isEqualTo(suppressions);
    }

    @Test
    @DisplayName("a wrong secret, a missing one and an unknown installation are one and the same 404")
    void everyRefusalLooksAlike() throws Exception {
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-guarded", null);
        String body = """
                {"messageId":"gen-guarded","status":"DELIVERED"}""";

        MvcResult wrong = callGeneric("not-the-secret", body);
        MvcResult missing = callGeneric(null, body);
        MvcResult unknown = mvc.perform(post("/providers/sms/" + UUID.randomUUID() + "/receipts")
                        .header(HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(wrong.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(wrong.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("an installation that is not active hears nothing")
    void aSuspendedInstallationIsRefused() throws Exception {
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-susp", null);
        jdbc.sql("UPDATE integration.installations SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", GENERIC_INSTALLATION)
                .update();

        assertThat(callGeneric(SECRET, """
                {"messageId":"gen-susp","status":"DELIVERED"}""").getResponse().getStatus()).isEqualTo(404);
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("a receipt cannot touch another tenant's attempt, even holding that tenant's own message id")
    void aReceiptIsMatchedUnderItsOwnInstallationOnly() throws Exception {
        // The other tenant's attempt carries the same provider id, which two
        // tenants' gateways can legitimately issue. The callback arrives on the
        // first tenant's installation and so concerns only the first tenant's.
        UUID mine = accepted(genericBinding, "GENERIC_SMS", "shared-id", null);
        UUID theirs = fixture.acceptedAttempt(
                OTHER_TENANT, OTHER_BRAND, otherBinding, "GENERIC_SMS", "shared-id", null, Instant.now());
        UUID onlyTheirs = fixture.acceptedAttempt(
                OTHER_TENANT, OTHER_BRAND, otherBinding, "GENERIC_SMS", "their-private-id", null, Instant.now());

        callGeneric(SECRET, """
                {"messageId":"shared-id","status":"DELIVERED"}""");
        callGeneric(SECRET, """
                {"messageId":"their-private-id","status":"DELIVERED"}""");

        assertThat(fixture.attemptStatus(mine)).isEqualTo("DELIVERED");
        assertThat(fixture.attemptStatus(theirs)).isEqualTo("ACCEPTED");
        assertThat(fixture.attemptStatus(onlyTheirs))
                .as("naming another tenant's message id on this installation is a forged id")
                .isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("a blacklist receipt fails the attempt and suppresses that customer's SMS, once")
    void aBlacklistReceiptSuppressesNarrowly() throws Exception {
        UUID account = fixture.customerAccount(TENANT);
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-bl-1", account);
        UUID second = accepted(genericBinding, "GENERIC_SMS", "gen-bl-2", account);

        callGeneric(SECRET, """
                {"messageId":"gen-bl-1","status":"BLACKLISTED"}""");
        callGeneric(SECRET, """
                {"messageId":"gen-bl-2","status":"BLACKLISTED"}""");

        assertThat(fixture.attemptStatus(attempt)).isEqualTo("FAILED");
        assertThat(fixture.attemptStatus(second)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_code FROM notifications.delivery_attempts WHERE id = :id")
                        .param("id", attempt)
                        .query(String.class)
                        .single())
                .isEqualTo("RECEIVER_UNREACHABLE");
        assertThat(jdbc.sql("""
                        SELECT reason || '/' || channel || '/' || applied_by_type
                          FROM marketing.suppressions WHERE tenant_id = :t AND customer_account_id = :a
                        """)
                        .param("t", TENANT)
                        .param("a", account)
                        .query(String.class)
                        .list())
                .as("one narrow suppression for the number, not one per message")
                .containsExactly("HARD_BOUNCE/SMS/PROVIDER");
    }

    @Test
    @DisplayName("a late receipt clears a 'no receipt' mark, because somebody is evidently listening")
    void aLateReceiptClearsNoReceipt() throws Exception {
        UUID attempt = accepted(genericBinding, "GENERIC_SMS", "gen-late", null);
        jdbc.sql("UPDATE notifications.delivery_attempts SET receipt_state = 'NO_RECEIPT' WHERE id = :id")
                .param("id", attempt)
                .update();

        callGeneric(SECRET, """
                {"messageId":"gen-late","status":"SENT"}""");

        assertThat(fixture.receiptState(attempt)).isNull();
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName(
            "VAS: the documented callback is read once enabled, authenticated by the edge and by the attempt match")
    void vasCallbackIsReadWhenEnabled() throws Exception {
        UUID attempt = accepted(vasBinding, "SMSGW_VAS", "555555", null);
        UUID foreign = fixture.acceptedAttempt(
                OTHER_TENANT, OTHER_BRAND, otherBinding, "GENERIC_SMS", "555556", null, Instant.now());

        MvcResult real = mvc.perform(post("/providers/sms/" + VAS_INSTALLATION + "/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "login": "admin", "key": "", "id": 555555, "code": 4, "description": "DLVRD" }"""))
                .andReturn();
        mvc.perform(post("/providers/sms/" + VAS_INSTALLATION + "/receipts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "login": "admin", "key": "", "id": 555556, "code": 4, "description": "DLVRD" }"""));

        assertThat(real.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("DELIVERED");
        assertThat(fixture.attemptStatus(foreign))
                .as("with nothing in the request to verify, what limits a forgery is the attempt match")
                .isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT payload::text FROM integration.inbox_messages")
                        .query(String.class)
                        .list())
                .as("the callback body is never stored: its `key` field is a credential field, empty or not")
                .allSatisfy(
                        payload -> assertThat(payload).doesNotContain("admin").doesNotContain("key"));
    }

    @Test
    @DisplayName("a body the adapter cannot read is answered 200 and counted, never retried forever")
    void anUnreadableBodyIsAcknowledged() throws Exception {
        UUID attempt = accepted(vasBinding, "SMSGW_VAS", "777", null);

        MvcResult undocumentedCode = mvc.perform(post("/providers/sms/" + VAS_INSTALLATION + "/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "id": 777, "code": 99 }"""))
                .andReturn();
        MvcResult notJson = mvc.perform(post("/providers/sms/" + VAS_INSTALLATION + "/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json at all"))
                .andReturn();

        assertThat(undocumentedCode.getResponse().getStatus()).isEqualTo(200);
        assertThat(notJson.getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.attemptStatus(attempt)).isEqualTo("ACCEPTED");
        assertThat(fixture.eventStatuses(attempt)).containsExactly("ACCEPTED");
    }

    // ------------------------------------------------------------------ helpers

    private UUID accepted(UUID binding, String providerType, String messageId, @Nullable UUID account) {
        return fixture.acceptedAttempt(TENANT, BRAND, binding, providerType, messageId, account, Instant.now());
    }

    private MvcResult callGeneric(@Nullable String secret, String body) throws Exception {
        var request = post("/providers/sms/" + GENERIC_INSTALLATION + "/receipts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (secret != null) {
            request.header(HEADER, secret);
        }
        return mvc.perform(request).andReturn();
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
