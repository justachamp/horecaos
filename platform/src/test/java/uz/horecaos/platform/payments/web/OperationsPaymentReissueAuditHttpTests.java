package uz.horecaos.platform.payments.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.order;

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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.application.PaymentCheckoutService.CheckoutRefusedException;
import uz.horecaos.platform.payments.application.PaymentCheckoutService.PaymentSession;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * «Выставить счёт» (gap map row {@code 1.1e}, orders.md §4.9): an operator
 * re-issuing an unpaid online order's payment checkout — capability-gated,
 * idempotent, audited, and returning the payable link.
 *
 * <p>The checkout itself is {@code PaymentCheckoutService}'s, and its own
 * behaviour — one live attempt per intent, the refusals, the provider surfaces
 * — is {@code PaymentCheckoutSurfaceTests}'s. So the service is replaced here by a
 * double that answers a fixed session, and this suite proves what the controller
 * adds around it: who may call, that a replay of one {@code Idempotency-Key} hands
 * out one session and writes one audit fact, that a refusal hands out nothing and
 * records no issue, and that the facts name what was asked for and issued and
 * never the phone an invoice was pushed to (ADR 0029).
 *
 * <p>And the order of the two writes. The checkout is an external effect that no
 * transaction can take back, so the request is recorded <em>before</em> it
 * ({@code payment.checkout_reissue_requested}): an audit store that is down stops
 * the issue instead of leaving a payable link nobody recorded. What was issued is
 * recorded after it ({@code payment.checkout_reissued}), and losing that one
 * costs the detail, not the answer -- the operator is not told a link that exists
 * failed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsPaymentReissueAuditHttpTests {

    private static final UUID TENANT = UUID.fromString("018fa212-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fa212-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fa212-4000-7000-8000-0000000000c1");

    private static final String OWNER = "reissue-owner";
    private static final String ADMINISTRATOR = "reissue-administrator";

    private static final String REQUESTED = "payment.checkout_reissue_requested";

    private static final String PHONE = "998901234567";
    private static final String CHECKOUT_URL = "https://checkout.example.test/pay/abc123";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the payment re-issue endpoint test");
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

    @MockitoBean
    @SuppressWarnings("NullAway")
    private PaymentCheckoutService checkout;

    /** The real recorder, spied on so a test can make the audit store fail for one fact. */
    @MockitoSpyBean
    @SuppressWarnings("NullAway")
    private AuditRecorder auditRecorder;

    private UUID orderId;

    @BeforeEach
    void seed() {
        reset(checkout);
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        OrderBoardFixtures fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        roleRegistry.synchronize();
        fixtures.tenant(TENANT, "reissue-audit", BRAND, LOCATION);
        orderId = fixtures.insertOrder(order("RE-1")
                .at(TENANT, BRAND, LOCATION)
                .status("PAYMENT_AUTHORIZING")
                .paymentStatusProjection("PENDING"));
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);

        when(checkout.openOrRePresent(eq(TENANT), eq(orderId), isNull(), any()))
                .thenReturn(new PaymentSession(
                        UUID.fromString("018fa212-4000-7000-8000-0000000000e1"),
                        "mtid-1",
                        PaymentProviderType.CLICK,
                        PresentationKind.PAYMENT_LINK,
                        CHECKOUT_URL,
                        null,
                        Instant.parse("2026-09-30T12:00:00Z"),
                        101_000L,
                        "UZS",
                        false,
                        1));
    }

    @Test
    @DisplayName("a payment link is issued, the payable URL comes back, and one audit fact names who issued it")
    void anOperatorIssuesAPayableLinkAndTheIssueIsAudited() throws Exception {
        MvcResult issued =
                mvc.perform(reissue("issue-1", "{}").with(tokenFor(OWNER))).andReturn();

        assertThat(issued.getResponse().getStatus()).isEqualTo(200);
        assertThat(issued.getResponse().getContentAsString())
                .contains("\"checkoutUrl\":\"" + CHECKOUT_URL + "\"")
                .contains("\"provider\":\"CLICK\"")
                .contains("\"amountMinor\":101000");

        List<Map<String, Object>> facts = auditFacts();
        assertThat(facts).hasSize(1);
        Map<String, Object> fact = facts.getFirst();
        assertThat(fact)
                .containsEntry("actor_type", "USER")
                .containsEntry("actor_subject", OWNER)
                .containsEntry("audit_class", "BUSINESS")
                .containsEntry("scope_type", "LOCATION")
                .containsEntry("scope_id", LOCATION)
                .containsEntry("target_type", "ordering.order")
                .containsEntry("target_id", orderId)
                .containsEntry("capability_used", "payment.initiate");
        assertThat((String) fact.get("reason"))
                .as("the action is the reason when the operator gave none, and it must not be blank")
                .isNotBlank();
        assertThat(String.valueOf(fact.get("change_document")))
                .contains("provider")
                .contains("CLICK")
                .contains("PAYMENT_LINK")
                .contains("pushedToRecipient");
        assertThat(String.valueOf(fact.get("change_document")))
                .as("not the link — it bears the payment")
                .doesNotContain(CHECKOUT_URL);

        List<Map<String, Object>> requests = auditFacts(REQUESTED);
        assertThat(requests)
                .as("the request is on record too, written before the checkout")
                .hasSize(1);
        assertThat(requests.getFirst())
                .containsEntry("actor_subject", OWNER)
                .containsEntry("audit_class", "BUSINESS")
                .containsEntry("scope_type", "LOCATION")
                .containsEntry("scope_id", LOCATION)
                .containsEntry("target_id", orderId)
                .containsEntry("capability_used", "payment.initiate");
        assertThat(String.valueOf(requests.getFirst().get("change_document")))
                .contains("PAYMENT_LINK")
                .contains("pushedToRecipient")
                .doesNotContain(CHECKOUT_URL);
    }

    @Test
    @DisplayName("an invoice push records that a recipient was named and the operator's reason, never the phone")
    void aPushAuditsTheFactAndNeverThePhone() throws Exception {
        MvcResult issued = mvc.perform(reissue(
                                "issue-push",
                                "{\"presentation\":\"INVOICE_PUSH\",\"pushRecipient\":\"" + PHONE
                                        + "\",\"reason\":\"Customer's Click account is on another number\"}")
                        .with(tokenFor(OWNER)))
                .andReturn();

        assertThat(issued.getResponse().getStatus()).isEqualTo(200);
        List<Map<String, Object>> facts = auditFacts();
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().get("reason")).isEqualTo("Customer's Click account is on another number");
        assertThat(String.valueOf(facts.getFirst().get("change_document")))
                .contains("pushedToRecipient")
                .contains("true");
        assertThat(facts.getFirst().toString())
                .as("ADR 0029: a phone number reaches no audit row, under any column")
                .doesNotContain(PHONE);
        List<Map<String, Object>> requests = auditFacts(REQUESTED);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().get("reason")).isEqualTo("Customer's Click account is on another number");
        assertThat(String.valueOf(requests.getFirst().get("change_document")))
                .contains("INVOICE_PUSH")
                .contains("pushedToRecipient");
        assertThat(requests.getFirst().toString())
                .as("ADR 0029: the request fact names no phone either")
                .doesNotContain(PHONE);
    }

    @Test
    @DisplayName("replaying one Idempotency-Key hands out one session and writes one audit fact")
    void aReplayIsOneIssueAndOneFact() throws Exception {
        MvcResult first =
                mvc.perform(reissue("issue-replay", "{}").with(tokenFor(OWNER))).andReturn();
        MvcResult second =
                mvc.perform(reissue("issue-replay", "{}").with(tokenFor(OWNER))).andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        verify(checkout, times(1)).openOrRePresent(eq(TENANT), eq(orderId), isNull(), any());
        assertThat(auditFacts())
                .as("the replay is the first answer again, not a second issue")
                .hasSize(1);
        assertThat(auditFacts(REQUESTED)).as("and not a second request").hasSize(1);
    }

    @Test
    @DisplayName("a call with no Idempotency-Key is refused before anything is issued")
    void aMissingKeyIsRefused() throws Exception {
        MvcResult refused = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/orders/" + orderId
                                + "/payment/re-presentations")
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
        verify(checkout, never()).openOrRePresent(any(), any(), any(), any());
        assertThat(auditFacts()).isEmpty();
        assertThat(auditFacts(REQUESTED)).isEmpty();
    }

    @Test
    @DisplayName("a refused issue hands nothing out: the request is on record, no issue is")
    void aRefusedIssueWritesNoFact() throws Exception {
        reset(checkout);
        when(checkout.openOrRePresent(any(), any(), any(), any()))
                .thenThrow(new CheckoutRefusedException("ALREADY_PAID", "This order is already paid"));

        MvcResult refused = mvc.perform(reissue("issue-refused", "{}").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("ALREADY_PAID");
        assertThat(auditFacts())
                .as("nothing was issued, so no issue is recorded")
                .isEmpty();
        assertThat(auditFacts(REQUESTED))
                .as("the operator did ask, and a refused ask is part of the trail")
                .hasSize(1);
    }

    @Test
    @DisplayName("an audit store that is down stops the issue before the checkout is opened, and a retry succeeds")
    void anAuditFailureBeforeTheCheckoutStopsItAndTheRetryIsServed() throws Exception {
        doThrow(new DataAccessResourceFailureException("audit store unreachable"))
                .when(auditRecorder)
                .record(any());

        assertThatThrownBy(() -> mvc.perform(reissue(
                                "issue-audit-down",
                                "{\"presentation\":\"INVOICE_PUSH\",\"pushRecipient\":\"" + PHONE + "\"}")
                        .with(tokenFor(OWNER))))
                .as("the request fails: no 2xx, so no operator is told a link was issued")
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);
        verify(checkout, never()).openOrRePresent(any(), any(), any(), any());
        assertThat(auditFacts()).isEmpty();
        assertThat(auditFacts(REQUESTED)).isEmpty();

        reset(auditRecorder);
        MvcResult retried = mvc.perform(reissue("issue-audit-down", "{}").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(retried.getResponse().getStatus())
                .as("a 5xx releases the Idempotency-Key, so the same key is served once the store is back")
                .isEqualTo(200);
        verify(checkout, times(1)).openOrRePresent(eq(TENANT), eq(orderId), isNull(), any());
        assertThat(auditFacts(REQUESTED)).hasSize(1);
        assertThat(auditFacts()).hasSize(1);
    }

    @Test
    @DisplayName("losing the record of what was issued does not turn an issued link into an error")
    void anOutcomeFactThatFailsAfterTheIssueDoesNotFailTheResponse() throws Exception {
        doThrow(new DataAccessResourceFailureException("audit store unreachable"))
                .when(auditRecorder)
                .record(argThat(fact -> fact != null && "payment.checkout_reissued".equals(fact.actionCode())));

        MvcResult issued = mvc.perform(reissue("issue-outcome-lost", "{}").with(tokenFor(OWNER)))
                .andReturn();

        assertThat(issued.getResponse().getStatus())
                .as("the link exists and the customer can pay it; a 500 would say it does not")
                .isEqualTo(200);
        assertThat(issued.getResponse().getContentAsString()).contains("\"checkoutUrl\":\"" + CHECKOUT_URL + "\"");
        assertThat(auditFacts(REQUESTED))
                .as("the action is on record regardless, because the request was written first")
                .hasSize(1);
        assertThat(auditFacts()).isEmpty();
    }

    @Test
    @DisplayName("a principal without payment.initiate is refused, and nothing is issued or audited")
    void anAdministratorCannotIssue() throws Exception {
        MvcResult refused = mvc.perform(reissue("issue-denied", "{}").with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("payment.initiate");
        verify(checkout, never()).openOrRePresent(any(), any(), any(), any());
        assertThat(auditFacts()).isEmpty();
        assertThat(auditFacts(REQUESTED)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder reissue(String idempotencyKey, String body) {
        return post("/api/v1/operations/tenants/" + TENANT + "/orders/" + orderId + "/payment/re-presentations")
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** The facts that say a checkout was issued. */
    private List<Map<String, Object>> auditFacts() {
        return auditFacts("payment.checkout_reissued");
    }

    private List<Map<String, Object>> auditFacts(String actionCode) {
        return jdbc.sql("""
                        SELECT actor_type, actor_subject, audit_class, scope_type, scope_id, target_type,
                               target_id, capability_used, reason, change_document::text AS change_document
                          FROM audit.audit_events
                         WHERE action_code = :actionCode
                        """).param("actionCode", actionCode).query().listOfRows();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'payment reissue endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Carries no realm role, so a refusal proves the ADR 0025 grant decided it. */
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
