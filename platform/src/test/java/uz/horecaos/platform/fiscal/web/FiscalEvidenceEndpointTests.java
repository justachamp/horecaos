package uz.horecaos.platform.fiscal.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.fiscal.api.FiscalEvidencePolicies;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.payments.application.SentEvidenceRecorder;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalDocumentType;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.SentReceipt;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The fiscal evidence surface over HTTP (ADR 0166): what an operator handed a customer complaint or a
 * tax query can produce, who may see it in full, and the requests that fix a wrong receipt.
 *
 * <p>The lines and the tender split are written by the real {@code SentEvidenceRecorder} that
 * {@code payments} calls on submission, behind the real envelope protection, and read back through
 * the endpoint, so a green test is evidence about the path and not about a fixture that builds a
 * ciphertext by hand. The INN snapshot is taken by the trigger on insert, whoever the writer is.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FiscalEvidenceEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fe802-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fe802-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fe802-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe802-4000-7000-8000-0000000000c1");
    private static final UUID ENTITY_A = UUID.fromString("018fe802-4000-7000-8000-0000000000e1");
    private static final UUID ENTITY_B = UUID.fromString("018fe802-4000-7000-8000-0000000000e2");
    private static final UUID INSTALLATION = UUID.fromString("018fe802-4000-7000-8000-0000000000d1");
    private static final UUID INTEGRATION_BINDING = UUID.fromString("018fe802-4000-7000-8000-0000000000d2");
    private static final UUID MERCHANT_BINDING = UUID.fromString("018fe802-4000-7000-8000-0000000000d3");

    private static final String TIN_A = "123456789";
    private static final String TIN_B = "987654321";

    private static final String FINANCE = "evidence-finance";
    private static final String CHECKER = "evidence-checker";
    private static final String ADMIN = "evidence-administrator";
    private static final String OTHER_FINANCE = "evidence-other-tenant-finance";

    private static final String FISCAL_SIGN = "SIGN-9F3A7C21B6D8";
    private static final String RECEIPT_NUMBER = "R-5551234";
    private static final String TERMINAL_ID = "TERM-0042";
    private static final String RECEIPT_URL = "https://ofd.soliq.uz/epi?t=TERM-0042&r=R-5551234&s=SIGN-9F3A7C21B6D8";

    private static final String MARK_ONE = "0104600439931256215S6JgXJ5.T\u001d8005112000";
    private static final String MARK_TWO = "0104600439931256214Qw3Zk9Pe\u001d8005112000";

    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");

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
        // The envelope protection the sent lines are written behind.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private FieldProtection protection;

    @Autowired
    private SentEvidenceRecorder sentEvidence;

    @Autowired
    private JdbcFiscalDocumentStore payments;

    @Autowired
    private PolicyAuthor policyAuthor;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ObjectMapper json;

    private UUID channelId;
    private UUID publicationId;

    @BeforeEach
    void reset() {
        lastBody = json.readTree("{}");
        for (String table : List.of(
                "fiscal.fiscal_correction_requests",
                "fiscal.fiscal_documents",
                "fiscal.fiscal_terminals",
                "payments.merchant_bindings",
                "payments.payment_methods",
                "payments.payment_intents",
                "integration.bindings",
                "integration.installations",
                "tenant.location_fiscal_assignments",
                "tenant.legal_entities",
                "ordering.orders",
                "ordering.carts",
                "pricing.quotes",
                "catalog.publications",
                "catalog.catalogs",
                "tenant.sales_channels",
                "tenant.locations",
                "tenant.brands",
                "iam.grants")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        // A request for a second signature is tenant-owned and survives the fixtures it was raised about.
        jdbc.sql("DELETE FROM audit.approval_requests WHERE tenant_id IN (:a, :b)")
                .param("a", TENANT)
                .param("b", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:a, :b)")
                .param("a", TENANT)
                .param("b", OTHER_TENANT)
                .update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(TENANT, FINANCE, PlatformRole.TENANT_FINANCE);
        grant(TENANT, CHECKER, PlatformRole.TENANT_FINANCE);
        grant(TENANT, ADMIN, PlatformRole.TENANT_ADMIN);
        grant(OTHER_TENANT, OTHER_FINANCE, PlatformRole.TENANT_FINANCE);
    }

    // ------------------------------------------------------------------ evidence

    @Test
    @DisplayName("the evidence names the INN on the document, the lines as filed and how the payment was split")
    void theEvidenceNamesTheInnTheLinesAndTheSplit() throws Exception {
        UUID orderId = seedOrder("E-101");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash classic", 2, 25_000L, List.of()));

        JsonNode body = evidence(FINANCE, orderId, null);

        JsonNode document = body.path("documents").path(0);
        assertThat(document.path("documentId").asText()).isEqualTo(documentId.toString());
        assertThat(document.path("status").asText()).isEqualTo("ISSUED");
        assertThat(document.path("responsibility").asText()).isEqualTo("PARTNER");
        assertThat(document.path("providerType").asText()).isEqualTo("CLICK");

        assertThat(document.path("seller").path("tin").asText())
                .as("the INN that issued it, from the snapshot taken when the document was opened")
                .isEqualTo(TIN_A);
        assertThat(document.path("seller").path("name").asText()).isEqualTo("Fiskal A MCHJ");
        assertThat(document.path("seller").path("source").asText()).isEqualTo("OPENING");

        assertThat(document.path("merchantAccount").path("bindingId").asText()).isEqualTo(MERCHANT_BINDING.toString());
        assertThat(document.path("merchantAccount")
                        .path("accountReferenceMasked")
                        .asText())
                .as("the account reference names the account without being the account")
                .isEqualTo("****e-77");

        JsonNode lines = document.path("lines");
        assertThat(lines.path("state").asText()).isEqualTo("AVAILABLE");
        assertThat(lines.path("synthetic").asBoolean()).isFalse();
        JsonNode first = lines.path("items").path(0);
        assertThat(first.path("name").asText()).isEqualTo("Lavash classic");
        assertThat(first.path("ikpu").asText()).isEqualTo("10901001001000000");
        assertThat(first.path("packageCode").asText()).isEqualTo("1500123");
        assertThat(first.path("quantity").asInt()).isEqualTo(2);
        assertThat(first.path("unitPrice").path("amountMinor").asLong()).isEqualTo(25_000L);
        assertThat(first.path("unitPrice").path("currency").asText()).isEqualTo("UZS");
        assertThat(first.path("vatPercent").asInt()).isEqualTo(12);

        JsonNode split = document.path("tenderSplit");
        assertThat(split.path("card").path("amountMinor").asLong())
                .as("Click was told the whole amount was received by card")
                .isEqualTo(50_000L);
        assertThat(split.path("cash").path("amountMinor").asLong()).isZero();
        assertThat(split.path("ecash").path("amountMinor").asLong()).isZero();

        assertThat(body.path("retention").path("years").asInt()).isEqualTo(7);
        assertThat(body.path("retention").path("provisional").asBoolean())
                .as("a seven-year default nobody has confirmed says so")
                .isTrue();
    }

    @Test
    @DisplayName("identifiers are masked by default, and the masked read is the same read a branch manager may have")
    void identifiersAreMaskedByDefault() throws Exception {
        UUID orderId = seedOrder("E-102");
        issuedClickDocument(orderId, ENTITY_A, line("Lavash classic", 1, 25_000L, List.of()));

        MvcResult masked =
                mvc.perform(get(evidenceUrl(orderId)).with(tokenFor(ADMIN))).andReturn();

        assertThat(masked.getResponse().getStatus())
                .as("fiscal.document.read is all the masked read needs")
                .isEqualTo(200);
        assertThat(masked.getResponse().getHeader("Cache-Control")).contains("no-store");
        String text = masked.getResponse().getContentAsString();
        JsonNode identifiers = json.readTree(text).path("documents").path(0).path("identifiers");
        assertThat(identifiers.path("revealed").asBoolean()).isFalse();
        assertThat(identifiers.path("fiscalSign").asText()).isEqualTo("****B6D8");
        assertThat(identifiers.path("receiptNumber").asText()).isEqualTo("****1234");
        assertThat(identifiers.path("terminalId").asText()).isEqualTo("****0042");
        assertThat(text)
                .as("not one identifier in full anywhere in a masked response")
                .doesNotContain(FISCAL_SIGN)
                .doesNotContain(RECEIPT_NUMBER)
                .doesNotContain(RECEIPT_URL)
                .doesNotContain(TERMINAL_ID);
    }

    @Test
    @DisplayName("a reveal needs the capability (403), then a purpose from the closed set (422), and is audited")
    void aRevealNeedsTheCapabilityAndAPurposeAndIsAudited() throws Exception {
        UUID orderId = seedOrder("E-103");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash classic", 1, 25_000L, List.of()));

        // Without the capability: 403, naming it, and nothing learned about which purposes exist.
        MvcResult refused = mvc.perform(
                        get(evidenceUrl(orderId) + "?purpose=TAX_INQUIRY").with(tokenFor(ADMIN)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("fiscal.evidence.reveal")
                .doesNotContain(FISCAL_SIGN);
        assertThat(revealFacts(documentId))
                .as("a refused reveal opens nothing and records no reveal")
                .isEmpty();

        // With the capability and no purpose, or one that is not in the set: 422.
        for (String bad : List.of("", "CURIOSITY", "tax inquiry please")) {
            MvcResult unprocessable = mvc.perform(get(evidenceUrl(orderId) + "?purpose=" + bad.replace(" ", "%20"))
                            .with(tokenFor(FINANCE)))
                    .andReturn();
            assertThat(unprocessable.getResponse().getStatus())
                    .as("purpose '%s'", bad)
                    .isEqualTo(422);
            assertThat(unprocessable.getResponse().getContentAsString())
                    .contains("PURPOSE_REQUIRED")
                    .doesNotContain(FISCAL_SIGN);
        }
        assertThat(revealFacts(documentId)).isEmpty();

        // With both: full identifiers, and one SECURITY fact per document, read back.
        JsonNode revealed = evidence(FINANCE, orderId, "TAX_INQUIRY");
        JsonNode identifiers = revealed.path("documents").path(0).path("identifiers");
        assertThat(revealed.path("revealed").asBoolean()).isTrue();
        assertThat(revealed.path("purpose").asText()).isEqualTo("TAX_INQUIRY");
        assertThat(identifiers.path("revealed").asBoolean()).isTrue();
        assertThat(identifiers.path("fiscalSign").asText()).isEqualTo(FISCAL_SIGN);
        assertThat(identifiers.path("receiptNumber").asText()).isEqualTo(RECEIPT_NUMBER);
        assertThat(identifiers.path("receiptUrl").asText()).isEqualTo(RECEIPT_URL);
        assertThat(identifiers.path("terminalId").asText()).isEqualTo(TERMINAL_ID);

        List<Map<String, Object>> facts = revealFacts(documentId);
        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.get("audit_class")).isEqualTo("SECURITY");
            assertThat(fact.get("actor_subject")).isEqualTo(FINANCE);
            assertThat(fact.get("target_id")).isEqualTo(documentId);
            assertThat(fact.get("capability_used")).isEqualTo("fiscal.evidence.reveal");
            String change = (String) fact.get("change_document");
            assertThat(change)
                    .contains("TAX_INQUIRY")
                    .contains("fiscalSign")
                    .contains("receiptNumber")
                    .contains("receiptUrl")
                    .contains("terminalId")
                    .as("the fact names the fields and never a value")
                    .doesNotContain(FISCAL_SIGN)
                    .doesNotContain(RECEIPT_NUMBER)
                    .doesNotContain(RECEIPT_URL)
                    .doesNotContain(TERMINAL_ID);
        });
    }

    @Test
    @DisplayName("no marking code is in any response, masked or revealed, under any purpose: only a count")
    void noMarkingCodeIsReachable() throws Exception {
        UUID orderId = seedOrder("E-104");
        UUID documentId = issuedClickDocument(
                orderId, ENTITY_A, line("Cigarettes, pack", 2, 30_000L, List.of(MARK_ONE, MARK_TWO)));

        List<String> bodies = new ArrayList<>();
        bodies.add(mvc.perform(get(evidenceUrl(orderId)).with(tokenFor(FINANCE)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        for (String purpose : List.of("TAX_INQUIRY", "CUSTOMER_COMPLAINT", "AUDIT", "CORRECTION")) {
            bodies.add(mvc.perform(
                            get(evidenceUrl(orderId) + "?purpose=" + purpose).with(tokenFor(FINANCE)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        }
        for (String body : bodies) {
            assertThat(body)
                    .doesNotContain("S6JgXJ5")
                    .doesNotContain("4Qw3Zk9Pe")
                    .doesNotContain("0104600439931256")
                    .doesNotContain("8005112000");
            JsonNode line = json.readTree(body).path("documents").path(0).path("lines");
            assertThat(line.path("items").path(0).path("markingCodeCount").asInt())
                    .isEqualTo(2);
            assertThat(line.path("markingCodesTransmitted").asInt()).isEqualTo(2);
        }

        // Nor is one in what is stored: the protected request holds the count and not the codes.
        String stored = jdbc.sql("SELECT protected_request_reference FROM fiscal.fiscal_documents WHERE id = :id")
                .param("id", documentId)
                .query(String.class)
                .single();
        String plaintext = protection.reveal(
                TENANT,
                ProtectedValue.deserialize(stored),
                SentReceiptEvidence.requestRecord(documentId),
                "test: prove no code was stored");
        assertThat(plaintext)
                .doesNotContain("S6JgXJ5")
                .doesNotContain("4Qw3Zk9Pe")
                .contains("\"markingCodeCount\":2");
    }

    @Test
    @DisplayName("marking is a count where the mark store exists, and says 'not built' where it does not")
    void markingIsNotBuiltUntilTheMarkStoreExists() throws Exception {
        UUID orderId = seedOrder("E-105");
        issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));

        JsonNode marks = evidence(FINANCE, orderId, null).path("marks");

        assertThat(marks.path("state").asText()).isEqualTo("NOT_BUILT");
        assertThat(marks.has("required") && !marks.path("required").isNull())
                .as("no count is shown while there is no store to count: 0 of 0 would be a claim")
                .isFalse();
    }

    @Test
    @DisplayName("a document opened before a reassignment and a re-registration still shows the INN it was filed under")
    void theInnIsTheOneOnTheDocumentNotTheOneToday() throws Exception {
        UUID orderId = seedOrder("E-106");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));

        // The branch is handed to another company, and the first company is re-registered under a new INN.
        jdbc.sql("""
                UPDATE tenant.location_fiscal_assignments
                   SET effective_until = DATE '2026-10-08' WHERE legal_entity_id = :a
                """).param("a", ENTITY_A).update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id, location_id,
                    legal_entity_id, effective_from, approved_by)
                VALUES (:id, :t, :b, :l, :e, DATE '2026-10-08', 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("e", ENTITY_B)
                .update();
        jdbc.sql("UPDATE tenant.legal_entities SET tin = '555555555', legal_name = 'Re-registered' WHERE id = :a")
                .param("a", ENTITY_A)
                .update();

        JsonNode seller =
                evidence(FINANCE, orderId, null).path("documents").path(0).path("seller");

        assertThat(seller.path("tin").asText())
                .as("a join made a year later would answer for today's taxpayer")
                .isEqualTo(TIN_A);
        assertThat(seller.path("name").asText()).isEqualTo("Fiskal A MCHJ");
        assertThat(seller.path("source").asText()).isEqualTo("OPENING");
        assertThat(documentId).isNotNull();

        // And the database itself refuses to rewrite it.
        assertThatThrownBy(() -> jdbc.sql("UPDATE fiscal.fiscal_documents SET seller_tin = '555555555' WHERE id = :id")
                        .param("id", documentId)
                        .update())
                .hasMessageContaining("is evidence and is not rewritten");
    }

    @Test
    @DisplayName("every writer gets the snapshot: the cash decision written through the payments view has one too")
    void aCashDecisionWrittenThroughThePaymentsViewIsSnapshottedToo() throws Exception {
        UUID orderId = seedOrder("E-107");
        jdbc.sql("""
                INSERT INTO payments.fiscal_documents (id, tenant_id, order_id, legal_entity_id,
                    document_type, status, reason_code, reason_note, version, created_at, updated_at)
                VALUES (:id, :t, :o, :e, 'SALE', 'NOT_APPLICABLE',
                    'CASH_TENDER_NO_PROVIDER_FISCALIZATION', 'cash', 1, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("o", orderId)
                .param("e", ENTITY_A)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();

        JsonNode document = evidence(FINANCE, orderId, null).path("documents").path(0);

        assertThat(document.path("status").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(document.path("seller").path("tin").asText()).isEqualTo(TIN_A);
        assertThat(document.path("lines").path("state").asText())
                .as("nothing was sent for cash, and the surface says nothing was rather than showing a receipt")
                .isEqualTo("NOT_SENT");
        assertThat(document.path("identifiers").path("fiscalSign").isNull()).isTrue();
    }

    @Test
    @DisplayName("a row whose entity cannot be named says UNRESOLVED instead of borrowing today's taxpayer")
    void anUnresolvedSellerSaysSo() throws Exception {
        UUID orderId = seedOrder("E-108");
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, document_type, status,
                    reason_code, reason_note, blocked_at, created_at, updated_at)
                VALUES (:id, :t, :o, 'SALE', 'BLOCKED', 'NO_FISCAL_PATH', 'no seller', :now, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("o", orderId)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();

        JsonNode seller =
                evidence(FINANCE, orderId, null).path("documents").path(0).path("seller");

        assertThat(seller.path("source").asText()).isEqualTo("UNRESOLVED");
        assertThat(seller.path("tin").isNull()).isTrue();
    }

    @Test
    @DisplayName("another tenant's order is a 404 and another tenant's principal is refused, on every read")
    void tenantIsolation() throws Exception {
        UUID orderId = seedOrder("E-109");
        issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));

        // The order exists, in this tenant. Asked for under the other tenant's path, with a principal who
        // holds a grant there, it does not exist.
        MvcResult notFound = mvc.perform(
                        get("/api/v1/operations/tenants/" + OTHER_TENANT + "/orders/" + orderId + "/fiscal-evidence")
                                .with(tokenFor(OTHER_FINANCE)))
                .andReturn();
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(notFound.getResponse().getContentAsString())
                .doesNotContain(FISCAL_SIGN)
                .doesNotContain(TIN_A);

        // The other tenant's principal on this tenant's route holds nothing there.
        for (String path : List.of(
                evidenceUrl(orderId),
                "/api/v1/operations/tenants/" + TENANT + "/fiscal/payment-types",
                "/api/v1/operations/tenants/" + TENANT + "/fiscal/correction-requests")) {
            MvcResult refused =
                    mvc.perform(get(path).with(tokenFor(OTHER_FINANCE))).andReturn();
            assertThat(refused.getResponse().getStatus()).as(path).isEqualTo(403);
        }

        // A document of this tenant, named under the other tenant's route, is not found there.
        UUID documentId = jdbc.sql("SELECT id FROM fiscal.fiscal_documents WHERE order_id = :o")
                .param("o", orderId)
                .query(UUID.class)
                .single();
        MvcResult foreign = mvc.perform(post("/api/v1/operations/tenants/" + OTHER_TENANT + "/fiscal/documents/"
                                + documentId + "/correction-requests")
                        .with(tokenFor(OTHER_FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "foreign-1")
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CORRECTION\",\"reasonCode\":\"WRONG_AMOUNT\",\"reasonNote\":\"x\"}"))
                .andReturn();
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------ payment types

    @Test
    @DisplayName(
            "the payment-types table is derived from the registry, the bindings and the terminals, and cannot be set")
    void paymentTypesAreDerived() throws Exception {
        registerMethod("CLICK", "Click", "PARTNER", false);
        registerMethod("PAYME", "Payme", "PARTNER", false);
        registerMethod("CASH", "Cash", "TERMINAL", false);
        registerMethod("COUNTER_CASH", "Cash at the counter", "OPERATOR", false);
        registerMethod("MARKETPLACE", "Aggregator", "MARKETPLACE", false);
        registerMethod("LOYALTY_POINTS", "Points", "OPERATOR", true);
        // Click has an active merchant account that can issue; Payme has none.
        seedMerchantBinding("CLICK", ENTITY_A);
        // One entity has a capable terminal, so TERMINAL is live; another has a terminal that cannot issue.
        seedTerminal(ENTITY_A, true);
        seedTerminal(ENTITY_B, false);

        JsonNode types = json.readTree(mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/fiscal/payment-types")
                                .with(tokenFor(FINANCE)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("types");
        Map<String, JsonNode> byCode = new java.util.LinkedHashMap<>();
        types.forEach(type -> byCode.put(type.path("code").asText(), type));

        assertThat(type(byCode, "CLICK").path("path").asText()).isEqualTo("PARTNER_ACCOUNT");
        assertThat(type(byCode, "CLICK").path("live").asBoolean()).isTrue();
        assertThat(type(byCode, "CLICK").path("entries").path(0).path("tin").asText())
                .isEqualTo(TIN_A);

        assertThat(type(byCode, "PAYME").path("live").asBoolean()).isFalse();
        assertThat(type(byCode, "PAYME").path("reasonCode").asText()).isEqualTo("NO_FISCAL_PATH");

        assertThat(type(byCode, "CASH").path("path").asText()).isEqualTo("TERMINAL");
        assertThat(type(byCode, "CASH").path("live").asBoolean()).isTrue();
        assertThat(type(byCode, "CASH").path("entries")).hasSize(1);
        assertThat(type(byCode, "CASH")
                        .path("entries")
                        .path(0)
                        .path("capableTerminals")
                        .asLong())
                .isEqualTo(1);

        assertThat(type(byCode, "COUNTER_CASH").path("reasonCode").asText())
                .as("an OPERATOR cash method is an unevidenced obligation, never HorecaOS fiscalizing it")
                .isEqualTo("NO_FISCAL_PATH");
        assertThat(type(byCode, "COUNTER_CASH").path("note").asText())
                .isEqualTo("OPERATOR_RESPONSIBILITY_UNIMPLEMENTED");

        assertThat(type(byCode, "MARKETPLACE").path("path").asText()).isEqualTo("AGGREGATOR");
        assertThat(type(byCode, "MARKETPLACE").path("reasonCode").asText()).isEqualTo("NO_FISCAL_PATH");

        assertThat(type(byCode, "LOYALTY_POINTS").path("path").asText()).isEqualTo("NOT_A_FISCAL_PATH");
        assertThat(type(byCode, "LOYALTY_POINTS").path("reasonCode").isNull()).isTrue();

        // There is no way to set it: a setting that disagreed with the bindings would be a lie.
        for (MockHttpServletRequestBuilder write : List.of(
                post("/api/v1/operations/tenants/" + TENANT + "/fiscal/payment-types"),
                put("/api/v1/operations/tenants/" + TENANT + "/fiscal/payment-types"))) {
            int status = mvc.perform(write.with(tokenFor(FINANCE))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "k")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).isIn(404, 405);
        }
    }

    // ------------------------------------------------------ correction and void

    @Test
    @DisplayName("a correction request needs a second person; once approved the document moves, and it is audited")
    void aCorrectionRequestIsApprovedThenRecorded() throws Exception {
        UUID orderId = seedOrder("E-201");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));

        // The maker's first call: recorded and not performed, naming the request a checker will decide.
        MvcResult waiting = mvc.perform(correctionRequest(FINANCE, documentId, 1, "CORRECTION", "WRONG_LINES", "k-1"))
                .andReturn();
        assertThat(waiting.getResponse().getStatus()).isEqualTo(422);
        JsonNode problem = json.readTree(waiting.getResponse().getContentAsString());
        assertThat(problem.path("code").asText()).isEqualTo("SECOND_APPROVER_REQUIRED");
        UUID approvalRequestId =
                UUID.fromString(problem.path("approvalRequestId").asText());
        assertThat(documentStatus(documentId))
                .as("nothing moved while the checker has not decided")
                .isEqualTo("ISSUED");

        // The same person cannot be their own checker.
        assertThatThrownBy(() -> approvals.decide(
                        approvalRequestId, ApprovalService.Decision.APPROVE, ActorRef.user(FINANCE, null), "self"))
                .isInstanceOf(ApprovalService.SelfApprovalException.class);

        approvals.decide(approvalRequestId, ApprovalService.Decision.APPROVE, ActorRef.user(CHECKER, null), "ok");

        MvcResult raised = mvc.perform(correctionRequest(FINANCE, documentId, 1, "CORRECTION", "WRONG_LINES", "k-2"))
                .andReturn();
        assertThat(raised.getResponse().getStatus())
                .as(raised.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = json.readTree(raised.getResponse().getContentAsString());
        assertThat(body.path("request").path("status").asText()).isEqualTo("REQUESTED");
        assertThat(body.path("request").path("kind").asText()).isEqualTo("CORRECTION");
        assertThat(body.path("request").path("approvalId").asText()).isEqualTo(approvalRequestId.toString());
        assertThat(body.path("documentVersion").asInt()).isEqualTo(2);
        assertThat(documentStatus(documentId)).isEqualTo("CORRECTION_REQUESTED");

        // The original's evidence is untouched by being asked about.
        Map<String, Object> row = jdbc.sql(
                        "SELECT fiscal_sign, external_receipt_id, receipt_url FROM fiscal.fiscal_documents WHERE id = :id")
                .param("id", documentId)
                .query()
                .singleRow();
        assertThat(row.get("fiscal_sign")).isEqualTo(FISCAL_SIGN);
        assertThat(row.get("receipt_url")).isEqualTo(RECEIPT_URL);

        List<Map<String, Object>> facts = auditFacts("fiscal.correction.requested", documentId);
        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.get("audit_class")).isEqualTo("BUSINESS");
            assertThat(fact.get("approval_request_id")).isEqualTo(approvalRequestId);
            String change = (String) fact.get("change_document");
            assertThat(change)
                    .contains("CORRECTION_REQUESTED")
                    .contains("WRONG_LINES")
                    .doesNotContain(FISCAL_SIGN);
        });

        // It shows on the order's evidence, and on the ageing worklist.
        JsonNode correction =
                evidence(FINANCE, orderId, null).path("documents").path(0).path("correction");
        assertThat(correction.path("status").asText()).isEqualTo("REQUESTED");
        JsonNode worklist =
                json.readTree(mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/fiscal/correction-requests")
                                .with(tokenFor(FINANCE)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(worklist).hasSize(1);
        assertThat(worklist.path(0).path("documentId").asText()).isEqualTo(documentId.toString());
    }

    @Test
    @DisplayName(
            "one open request per document, only an issued sale can be asked about, and a stale version is refused")
    void aCorrectionRequestRefusesTheWrongStateAndAStaleVersion() throws Exception {
        UUID orderId = seedOrder("E-202");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));
        UUID blocked = blockedDocument(seedOrder("E-203"));

        // A receipt that was never issued has nothing to correct.
        approveAndRaise(blocked, 1, "CORRECTION", "WRONG_AMOUNT", "blk");
        // (approveAndRaise returned the final response status below.)
        assertThat(lastStatus).isEqualTo(409);
        assertThat(documentStatus(blocked)).isEqualTo("BLOCKED");

        // A stale If-Match is a STALE_VERSION, not a second look.
        approveAndRaise(documentId, 7, "CORRECTION", "WRONG_AMOUNT", "stale");
        assertThat(lastStatus).isEqualTo(409);
        assertThat(lastBody.path("code").asText()).isEqualTo("STALE_VERSION");
        assertThat(documentStatus(documentId)).isEqualTo("ISSUED");

        // The real request, and then a second one for the same document.
        approveAndRaise(documentId, 1, "CORRECTION", "WRONG_AMOUNT", "first");
        assertThat(lastStatus).isEqualTo(200);
        approveAndRaise(documentId, 2, "VOID", "DUPLICATE_RECEIPT", "second");
        assertThat(lastStatus)
                .as("a document already being corrected is not asked about again")
                .isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM fiscal.fiscal_correction_requests WHERE document_id = :d")
                        .param("d", documentId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "attaching the agent's reference creates a linked CORRECTION, closes the request, and never edits the original")
    void attachingCreatesALinkedDocumentAndNeverEditsTheOriginal() throws Exception {
        UUID orderId = seedOrder("E-204");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));
        approveAndRaise(documentId, 1, "CORRECTION", "WRONG_LINES", "ok");
        assertThat(lastStatus).as(lastBody.toString()).isEqualTo(200);
        UUID requestId =
                UUID.fromString(lastBody.path("request").path("requestId").asText());

        MvcResult attached = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/fiscal/correction-requests/"
                                + requestId + "/attachments")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "attach-1")
                        .header("If-Match", "W/\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fiscalSign":"SIGN-NEW-CORRECTION","receiptNumber":"R-9990001",
                                 "receiptUrl":"https://ofd.soliq.uz/epi?r=R-9990001",
                                 "terminalId":"TERM-0042"}
                                """))
                .andReturn();

        assertThat(attached.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(attached.getResponse().getContentAsString());
        UUID linkedId = UUID.fromString(body.path("linkedDocumentId").asText());
        assertThat(body.path("request").path("status").asText()).isEqualTo("ATTACHED");
        assertThat(body.path("documentVersion").asInt()).isEqualTo(3);

        assertThat(documentStatus(documentId)).isEqualTo("CORRECTED");
        Map<String, Object> original = jdbc.sql(
                        "SELECT fiscal_sign, external_receipt_id, receipt_url, seller_tin FROM fiscal.fiscal_documents WHERE id = :id")
                .param("id", documentId)
                .query()
                .singleRow();
        assertThat(original.get("fiscal_sign"))
                .as("the original's evidence is never rewritten")
                .isEqualTo(FISCAL_SIGN);
        assertThat(original.get("receipt_url")).isEqualTo(RECEIPT_URL);

        Map<String, Object> linked = jdbc.sql(
                        "SELECT document_type, status, corrects_document_id, fiscal_sign, seller_tin, merchant_binding_id"
                                + " FROM fiscal.fiscal_documents WHERE id = :id")
                .param("id", linkedId)
                .query()
                .singleRow();
        assertThat(linked.get("document_type")).isEqualTo("CORRECTION");
        assertThat(linked.get("status")).isEqualTo("ISSUED");
        assertThat(linked.get("corrects_document_id")).isEqualTo(documentId);
        assertThat(linked.get("fiscal_sign")).isEqualTo("SIGN-NEW-CORRECTION");
        assertThat(linked.get("seller_tin"))
                .as("issued by the same taxpayer, copied from the original's snapshot")
                .isEqualTo(TIN_A);
        assertThat(linked.get("merchant_binding_id")).isEqualTo(MERCHANT_BINDING);

        assertThat(auditFacts("fiscal.correction.attached", documentId)).hasSize(1);

        // The request is closed: a second attachment is a conflict, and nothing is created.
        MvcResult again = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/fiscal/correction-requests/"
                                + requestId + "/attachments")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "attach-2")
                        .header("If-Match", "W/\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fiscalSign":"SIGN-AGAIN","receiptNumber":"R-9990002",
                                 "receiptUrl":"https://ofd.soliq.uz/epi?r=R-9990002"}
                                """))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM fiscal.fiscal_documents WHERE order_id = :o")
                        .param("o", orderId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a void attaches as a linked REFUND and leaves the original VOIDED")
    void aVoidAttachesAsALinkedRefund() throws Exception {
        UUID orderId = seedOrder("E-205");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));
        approveAndRaise(documentId, 1, "VOID", "CUSTOMER_REFUND", "void");
        assertThat(lastStatus).isEqualTo(200);
        assertThat(documentStatus(documentId)).isEqualTo("VOID_REQUESTED");
        UUID requestId =
                UUID.fromString(lastBody.path("request").path("requestId").asText());

        MvcResult attached = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/fiscal/correction-requests/"
                                + requestId + "/attachments")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "void-attach")
                        .header("If-Match", "W/\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fiscalSign":"SIGN-VOID","receiptNumber":"R-9990003",
                                 "receiptUrl":"https://ofd.soliq.uz/epi?r=R-9990003"}
                                """))
                .andReturn();

        assertThat(attached.getResponse().getStatus()).isEqualTo(200);
        assertThat(documentStatus(documentId)).isEqualTo("VOIDED");
        assertThat(jdbc.sql("SELECT document_type FROM fiscal.fiscal_documents WHERE corrects_document_id = :d")
                        .param("d", documentId)
                        .query(String.class)
                        .single())
                .isEqualTo("REFUND");
    }

    @Test
    @DisplayName("asking needs fiscal.document.resolve: a reader who may only look is refused")
    void askingForACorrectionNeedsTheResolveCapability() throws Exception {
        UUID orderId = seedOrder("E-206");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));

        MvcResult refused = mvc.perform(correctionRequest(ADMIN, documentId, 1, "CORRECTION", "WRONG_AMOUNT", "adm-1"))
                .andReturn();

        // The administrator holds fiscal.document.read and not .resolve.
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("fiscal.document.resolve");
        assertThat(documentStatus(documentId)).isEqualTo("ISSUED");
    }

    // ---------------------------------------------------------- Decision 6

    @Test
    @DisplayName(
            "a retry that would file the placeholder outside a sandbox is a 409 FISCAL_LINES_NOT_BUILT, sends nothing and leaves the document as it was")
    void aRetryOutsideASandboxIsRefusedBeforeAnythingIsSent() throws Exception {
        UUID orderId = seedOrder("E-301");
        UUID documentId = pendingClickDocument(orderId);
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type,
                    base_url, is_production, egress_allowlist)
                VALUES ('click-evidence-prod', 'PAYMENT', 'CLICK', 'https://example.invalid', true,
                    'example.invalid')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("UPDATE integration.installations SET environment_code = 'click-evidence-prod' WHERE id = :id")
                .param("id", INSTALLATION)
                .update();
        // Even with the platform's permission, a production environment refuses.
        allowPlaceholders(true);
        try {
            MvcResult refused = mvc.perform(
                            post("/api/v1/tenants/" + TENANT + "/fiscal/documents/" + documentId + "/retries")
                                    .with(tokenFor(FINANCE))
                                    .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "retry-prod")
                                    .header("If-Match", "W/\"1\"")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"reason\":\"the customer asked for their receipt\"}"))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            JsonNode problem = json.readTree(refused.getResponse().getContentAsString());
            assertThat(problem.path("code").asText()).isEqualTo("RESOURCE_CONFLICT");
            assertThat(problem.path("reasonCode").asText()).isEqualTo("FISCAL_LINES_NOT_BUILT");

            Map<String, Object> row =
                    jdbc.sql("""
                            SELECT status, attempt_count, version, lines_synthetic, protected_request_reference
                              FROM fiscal.fiscal_documents WHERE id = :id
                            """).param("id", documentId).query().singleRow();
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("attempt_count"))
                    .as("the attempt claimed for the call was rolled back")
                    .isEqualTo(0);
            assertThat(row.get("version")).isEqualTo(1);
            assertThat(row.get("lines_synthetic")).isEqualTo(false);
            assertThat(row.get("protected_request_reference")).isNull();
        } finally {
            allowPlaceholders(false);
        }
    }

    // ----------------------------------------------------- the four new statuses

    @Test
    @DisplayName(
            "a document that is being corrected cannot be retried, its evidence cannot be rewritten, and it still counts as issued")
    void theStatesAfterIssueKeepTheReceipt() throws Exception {
        UUID orderId = seedOrder("E-207");
        UUID documentId = issuedClickDocument(orderId, ENTITY_A, line("Lavash", 1, 25_000L, List.of()));
        approveAndRaise(documentId, 1, "CORRECTION", "WRONG_AMOUNT", "state");
        assertThat(lastStatus).isEqualTo(200);

        // Retrying a receipt that exists would be the one action that could produce a second sale receipt.
        MvcResult retry = mvc.perform(post("/api/v1/tenants/" + TENANT + "/fiscal/documents/" + documentId + "/retries")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "retry-1")
                        .header("If-Match", "W/\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"try again\"}"))
                .andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(409);

        // A late duplicate of the provider's report cannot overwrite what is on file.
        boolean rewritten = payments.recordEvidence(
                TENANT,
                documentId,
                FiscalStatus.ISSUED,
                "PARTNER_FISCALIZED",
                new FiscalDocument.FiscalEvidence(
                        "R-LATE",
                        "SIGN-LATE-DUPLICATE",
                        "TERM-LATE",
                        "R-LATE",
                        NOW,
                        "https://late.invalid/r",
                        null,
                        null),
                null,
                NOW);
        assertThat(rewritten).isFalse();
        assertThat(jdbc.sql("SELECT fiscal_sign FROM fiscal.fiscal_documents WHERE id = :id")
                        .param("id", documentId)
                        .query(String.class)
                        .single())
                .isEqualTo(FISCAL_SIGN);

        // And the board's coverage still counts the sale as one that has a receipt.
        JsonNode coverage = json.readTree(mvc.perform(get("/api/v1/tenants/" + TENANT + "/fiscal/coverage")
                        .param("from", NOW.minus(Duration.ofDays(1)).toString())
                        .param("to", NOW.plus(Duration.ofDays(1)).toString())
                        .with(tokenFor(FINANCE)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(coverage.path("issued").asLong()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ helpers

    private int lastStatus;
    private JsonNode lastBody;

    /**
     * Raises a request the way a maker and a checker really do: the first call is answered by the approval
     * model, a second person approves, the maker repeats the call. Leaves the final answer in
     * {@link #lastStatus} and {@link #lastBody}; a call refused before approval (a wrong state, a stale version)
     * is its own final answer.
     */
    private void approveAndRaise(UUID documentId, int version, String kind, String reason, String key)
            throws Exception {
        MvcResult first = mvc.perform(correctionRequest(FINANCE, documentId, version, kind, reason, key + "-1"))
                .andReturn();
        JsonNode firstBody = json.readTree(first.getResponse().getContentAsString());
        if (first.getResponse().getStatus() == 422
                && "SECOND_APPROVER_REQUIRED".equals(firstBody.path("code").asText())) {
            approvals.decide(
                    UUID.fromString(firstBody.path("approvalRequestId").asText()),
                    ApprovalService.Decision.APPROVE,
                    ActorRef.user(CHECKER, null),
                    "approved in the test");
            MvcResult second = mvc.perform(correctionRequest(FINANCE, documentId, version, kind, reason, key + "-2"))
                    .andReturn();
            lastStatus = second.getResponse().getStatus();
            lastBody = json.readTree(second.getResponse().getContentAsString());
            return;
        }
        lastStatus = first.getResponse().getStatus();
        lastBody = firstBody;
    }

    private MockHttpServletRequestBuilder correctionRequest(
            String subject, UUID documentId, int version, String kind, String reason, String key) {
        return post("/api/v1/operations/tenants/" + TENANT + "/fiscal/documents/" + documentId + "/correction-requests")
                .with(tokenFor(subject))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                .header("If-Match", "W/\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"%s\",\"reasonCode\":\"%s\",\"reasonNote\":\"The printed total is wrong\"}"
                        .formatted(kind, reason));
    }

    private String evidenceUrl(UUID orderId) {
        return "/api/v1/operations/tenants/" + TENANT + "/orders/" + orderId + "/fiscal-evidence";
    }

    private JsonNode evidence(String subject, UUID orderId, @Nullable String purpose) throws Exception {
        MvcResult result = mvc.perform(get(evidenceUrl(orderId) + (purpose == null ? "" : "?purpose=" + purpose))
                        .with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode type(Map<String, JsonNode> byCode, String code) {
        return java.util.Objects.requireNonNull(byCode.get(code), "no payment type " + code);
    }

    private String documentStatus(UUID documentId) {
        return jdbc.sql("SELECT status FROM fiscal.fiscal_documents WHERE id = :id")
                .param("id", documentId)
                .query(String.class)
                .single();
    }

    private List<Map<String, Object>> revealFacts(UUID documentId) {
        return auditFacts("fiscal.evidence.revealed", documentId);
    }

    /** The audit trail is append-only and outlives the fixtures, so a fact is asked for by the document it is about. */
    private List<Map<String, Object>> auditFacts(String actionCode, UUID documentId) {
        return auditFacts(actionCode).stream()
                .filter(fact -> documentId.equals(fact.get("target_id")))
                .toList();
    }

    private List<Map<String, Object>> auditFacts(String actionCode) {
        return jdbc.sql("""
                        SELECT audit_class, actor_subject, target_id, capability_used, approval_request_id,
                               change_document::text AS change_document
                          FROM audit.audit_events
                         WHERE action_code = :code AND scope_id = :tenant
                         ORDER BY occurred_at
                        """)
                .param("code", actionCode)
                .param("tenant", TENANT)
                .query()
                .listOfRows();
    }

    private FiscalReceiptLine line(String name, int quantity, long unitPrice, List<String> markingCodes) {
        return new FiscalReceiptLine(
                name,
                "10901001001000000",
                "1500123",
                null,
                quantity,
                new SomAmount(unitPrice, "UZS"),
                new SomAmount(Math.round(unitPrice * quantity * 12 / 112.0), "UZS"),
                12,
                null,
                null,
                markingCodes,
                TIN_A,
                null);
    }

    /**
     * A Click document the way the platform produces it: opened {@code PENDING}, submitted (the real
     * recorder keeps the lines and the split behind the real protection), then issued with the provider's
     * evidence.
     */
    private UUID issuedClickDocument(UUID orderId, UUID entityId, FiscalReceiptLine... lines) {
        UUID documentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id, provider_type,
                    document_type, status, reason_code, reason_note, submitted_at, created_at, updated_at)
                VALUES (:id, :t, :o, :e, 'CLICK', 'SALE', 'SUBMITTED', 'AWAITING_PROVIDER', 'sent', :now, :now, :now)
                """)
                .param("id", documentId)
                .param("t", TENANT)
                .param("o", orderId)
                .param("e", entityId)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();

        FiscalDocument document = new FiscalDocument(
                documentId,
                TENANT,
                orderId,
                entityId,
                null,
                null,
                PaymentProviderType.CLICK,
                FiscalDocumentType.SALE,
                null,
                FiscalStatus.SUBMITTED,
                "AWAITING_PROVIDER",
                "sent",
                List.of(lines),
                null,
                1,
                NOW);
        long total = 0;
        for (FiscalReceiptLine line : lines) {
            total += line.lineTotal().value();
        }
        sentEvidence.record(
                document,
                merchantBinding(entityId),
                new SentReceipt(
                        List.of(lines), new SomAmount(total, "UZS"), new SomAmount(0, "UZS"), new SomAmount(0, "UZS")),
                false);

        jdbc.sql("""
                UPDATE fiscal.fiscal_documents
                   SET status = 'ISSUED', reason_code = 'PARTNER_FISCALIZED', reason_note = 'issued',
                       external_receipt_id = :receipt, fiscal_sign = :sign, terminal_id = :terminal,
                       receipt_reference = :receipt, registered_at = :now, receipt_url = :url, issued_at = :now
                 WHERE id = :id
                """)
                .param("id", documentId)
                .param("receipt", RECEIPT_NUMBER)
                .param("sign", FISCAL_SIGN)
                .param("terminal", TERMINAL_ID)
                .param("url", RECEIPT_URL)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return documentId;
    }

    private void allowPlaceholders(boolean allowed) {
        policyAuthor.author(
                FiscalEvidencePolicies.SYNTHETIC_LINES_ALLOWED,
                ResourceScope.platform(),
                new FiscalEvidencePolicies.SyntheticLines(allowed),
                ActorRef.user("platform-admin", "Platform"),
                "endpoint test");
    }

    private UUID pendingClickDocument(UUID orderId) {
        UUID documentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id, provider_type,
                    document_type, status, reason_code, reason_note, created_at, updated_at)
                VALUES (:id, :t, :o, :e, 'CLICK', 'SALE', 'PENDING', 'AWAITING_CAPTURE', 'awaiting', :now, :now)
                """)
                .param("id", documentId)
                .param("t", TENANT)
                .param("o", orderId)
                .param("e", ENTITY_A)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return documentId;
    }

    private UUID blockedDocument(UUID orderId) {
        UUID documentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id, provider_type,
                    document_type, status, reason_code, reason_note, submitted_at, blocked_at,
                    created_at, updated_at)
                VALUES (:id, :t, :o, :e, 'CLICK', 'SALE', 'BLOCKED', 'PROVIDER_REPORT_OVERDUE', 'late',
                    :now, :now, :now, :now)
                """)
                .param("id", documentId)
                .param("t", TENANT)
                .param("o", orderId)
                .param("e", ENTITY_A)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return documentId;
    }

    private ProviderBinding merchantBinding(UUID entityId) {
        return new ProviderBinding(
                MERCHANT_BINDING,
                TENANT,
                entityId,
                PaymentProviderType.CLICK,
                INSTALLATION,
                INTEGRATION_BINDING,
                "service-77",
                "4444",
                null,
                SecretReference.parse("horecaos:test:provider_payment:tenant:click"),
                "evidence-tests-click",
                true,
                true,
                LocalDate.of(2020, 1, 1),
                null);
    }

    private void registerMethod(String code, String name, String responsibility, boolean balance) {
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility,
                    settles_from_balance)
                VALUES (:id, :t, :code, :name, :responsibility, :balance)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("code", code)
                .param("name", name)
                .param("responsibility", responsibility)
                .param("balance", balance)
                .update();
    }

    private void seedMerchantBinding(String providerType, UUID entityId) {
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id, provider_type,
                    installation_id, binding_id, merchant_account_reference, merchant_user_reference,
                    secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :t, :e, :p, :installation, :binding, 'service-77', '4444',
                    'horecaos:test:provider_payment:tenant:click', 'evidence-tests-click', true, true,
                    'ACTIVE', DATE '2020-01-01')
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", MERCHANT_BINDING)
                .param("t", TENANT)
                .param("e", entityId)
                .param("p", providerType)
                .param("installation", INSTALLATION)
                .param("binding", INTEGRATION_BINDING)
                .update();
    }

    private void seedTerminal(UUID entityId, boolean capable) {
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_terminals (id, tenant_id, brand_id, location_id, legal_entity_id,
                    terminal_kind, provider_binding_id, terminal_reference, capability_snapshot, status)
                VALUES (:id, :t, :b, :l, :e, 'POS', :binding, :ref, CAST(:caps AS jsonb), 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("e", entityId)
                .param("binding", capable ? INTEGRATION_BINDING : null)
                .param("ref", "till-" + entityId)
                .param("caps", capable ? "{\"IssueFiscalReceipt\": true}" : "{}")
                .update();
    }

    private UUID seedOrder(String number) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'DELIVERY', 'UZS', 'ACTIVE', :guest,
                    now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 50000, 0, 50000,
                    now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, fee_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'WEB', :guest, 'DELIVERY',
                    'AUTO_CONFIRM', 'NONE', 'CONFIRMED', 'UZS', 50000, 0, 0, 50000, :quote,
                    :hash, :pub, :cart, :key, 1, :at, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return orderId;
    }

    private void seedTenancy() {
        for (UUID id : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", id)
                    .param("slug", "evidence-" + id.toString().substring(32))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();

        insertEntity(ENTITY_A, "FISKAL-A", "Fiskal A MCHJ", TIN_A);
        insertEntity(ENTITY_B, "FISKAL-B", "Fiskal B MCHJ", TIN_B);
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id, location_id,
                    legal_entity_id, effective_from, approved_by)
                VALUES (:id, :t, :b, :l, :e, DATE '2020-01-01', 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("e", ENTITY_A)
                .update();

        // The installation and its environment behind the Click merchant account every submitted
        // document in this class went through.
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type,
                    base_url, is_production, egress_allowlist)
                VALUES ('click-evidence-tests', 'PAYMENT', 'CLICK', 'https://example.invalid', false,
                    'example.invalid')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category, provider_type,
                    environment_code, display_name, status, secret_reference)
                VALUES (:id, :t, 'PAYMENT', 'CLICK', 'click-evidence-tests', 'Click (evidence tests)',
                    'ACTIVE', 'horecaos:test:provider_payment:tenant:click')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :i, :b, 'ACTIVE')
                """)
                .param("id", INTEGRATION_BINDING)
                .param("t", TENANT)
                .param("i", INSTALLATION)
                .param("b", BRAND)
                .update();
        // The merchant account every issued document names, so the FK on the submission holds.
        seedMerchantBinding("CLICK", ENTITY_A);
    }

    private void insertEntity(UUID id, String code, String name, String tin) {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, :code, :name, :tin, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("code", code)
                .param("name", name)
                .param("tin", tin)
                .update();
    }

    private void grant(UUID tenantId, String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :scopeId,
                        'ACTIVE', 'test-fixture', 'fiscal evidence endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", tenantId)
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
