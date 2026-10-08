package uz.horecaos.platform.commercial.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.StatementService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.domain.PlanTerms;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0095 through the real routes, the real JWT and the real capability interceptor: a merchant puts a
 * card on file, tops its wallet up, asks for an invoice and reads what it owes — and HorecaOS staff set up
 * the card account and the bank details it depends on.
 *
 * <p>What is checked here and nowhere else is the HTTP contract: who is refused (scope containment, not
 * only capability possession), that the card's token reference never leaves the server, that a body with a
 * missing field is a validation answer rather than a malformed-body one (Jackson 3 refuses an omitted
 * primitive), and that an {@code Idempotency-Key} replay is the same answer and not a second charge.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommercialWalletSelfServiceEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9d50-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9d50-4000-7000-8000-0000000000b2");

    private static final String OWNER = "wallet-owner";
    private static final String FINANCE = "wallet-finance";
    private static final String ADMIN = "wallet-admin";
    private static final String OTHER_OWNER = "wallet-other-owner";
    private static final String STAFF = "wallet-staff-1";
    private static final String STAFF_CHECKER = "wallet-staff-2";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant OCTOBER = Instant.parse("2026-10-08T09:00:00Z");
    private static final long MONTHLY = 1_200_000L;

    private static final String WALLET = "/api/v1/tenants/" + TENANT + "/commercial/wallet";
    private static final String ARREARS = "/api/v1/tenants/" + TENANT + "/commercial/arrears";
    private static final String STAFF_BASE = "/api/v1/platform-admin/commercial";

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
        registry.add("horecaos.commercial.card.allow-fake", () -> "true");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private PlanCatalogService plans;

    @Autowired
    private SubscriptionService subscriptions;

    @Autowired
    private StatementService statements;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private FakeCardProvider fake;

    @Autowired
    private MovableClock clock;

    @BeforeEach
    void reset() {
        clock.set(OCTOBER);
        fake.reset();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("""
                TRUNCATE TABLE commercial.wallet_entries, commercial.tenant_billing, commercial.card_charge_attempts,
                    commercial.card_top_ups, commercial.prepayment_invoices, commercial.platform_card_installations,
                    commercial.statement_lines, commercial.statements, commercial.subscriptions,
                    commercial.usage_events, commercial.usage_aggregates, commercial.usage_adjustments,
                    commercial.entitlement_overrides, commercial.tenant_modules, commercial.modules,
                    commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE
                """).update();
        jdbc.sql("""
                UPDATE commercial.platform_billing_settings
                   SET bank_beneficiary = '[beneficiary: set by HorecaOS finance]',
                       bank_name = '[bank: set by HorecaOS finance]', bank_account = '[account: set by HorecaOS finance]',
                       bank_mfo = '[MFO]', bank_tax_id = '[tax id]', configured = false, version = 0,
                       updated_by = 'test reset', approved_by = NULL, approval_request_id = NULL
                """).update();
        // Not TRUNCATE tenant.tenants CASCADE: that reaches audit.approval_policies and takes the policy
        // V0504 seeds for the bank details with it, and whether they need a second person at all is what
        // that policy decides.
        jdbc.sql("DELETE FROM iam.grants WHERE reason = 'wallet self-service endpoint test'")
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:tenant, :other)")
                .param("tenant", TENANT)
                .param("other", OTHER_TENANT)
                .update();
        roleRegistry.synchronize();

        insertTenant(TENANT, "wallet-tenant");
        insertTenant(OTHER_TENANT, "wallet-other-tenant");
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, TENANT);
        grant(ADMIN, PlatformRole.TENANT_ADMIN, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
        grantPlatformAdmin(STAFF);
        grantPlatformAdmin(STAFF_CHECKER);
    }

    // ------------------------------------------------------------------ who may read

    @Test
    void anOwnerReadsItsWalletAndNothingIsConnectedYet() throws Exception {
        MvcResult read = mvc.perform(get(WALLET).with(tokenFor(OWNER))).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode wallet = JSON.readTree(read.getResponse().getContentAsString());
        assertThat(wallet.get("paymentMethod").asString()).isEqualTo("INVOICE");
        assertThat(wallet.get("paidBalance").get("amountMinor").asLong()).isZero();
        assertThat(wallet.get("card").isNull()).isTrue();
        assertThat(wallet.get("cardPaymentsAvailable").asBoolean()).isFalse();
        assertThat(wallet.get("bankTransferAvailable").asBoolean()).isFalse();
        assertThat(wallet.get("lapsingGrants").size()).isZero();
    }

    @Test
    void aCallerWithoutWalletReadAndAnotherTenantsOwnerAreBothRefused() throws Exception {
        MvcResult noCapability = mvc.perform(get(WALLET).with(tokenFor(ADMIN))).andReturn();
        MvcResult otherTenant =
                mvc.perform(get(WALLET).with(tokenFor(OTHER_OWNER))).andReturn();

        assertThat(noCapability.getResponse().getStatus()).isEqualTo(403);
        assertThat(noCapability.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("commercial.wallet.read");
        assertThat(otherTenant.getResponse().getStatus())
                .as("holding the capability at a different tenant's scope is not holding it here")
                .isEqualTo(403);
        for (String path : List.of("/ledger", "/statements", "/payment-details", "/top-ups", "/invoices")) {
            assertThat(mvc.perform(get(WALLET + path).with(tokenFor(OTHER_OWNER)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(path)
                    .isEqualTo(403);
        }
    }

    // ------------------------------------------------------------ the card, through HTTP

    @Test
    void staffConnectTheFakeAndAnOwnerPutsACardOnFileWithoutTheReferenceEverLeavingTheServer() throws Exception {
        String installationId = staffActivatesTheFake();

        String begun = postAs(OWNER, WALLET + "/card/enrolments", "idem-begin", "{}", 200);
        String session = JSON.readTree(begun).get("sessionReference").asString();
        String confirmed = postAs(
                OWNER,
                WALLET + "/card/confirmations",
                "idem-confirm",
                "{\"sessionReference\":\"" + session + "\",\"providerToken\":\"" + FakeCardProvider.APPROVING_CARD
                        + "\",\"verificationCode\":\"" + FakeCardProvider.VERIFICATION_CODE + "\"}",
                200);

        JsonNode card = JSON.readTree(confirmed);
        assertThat(card.get("last4").asString()).isEqualTo("4242");
        assertThat(card.get("lapsed").asBoolean()).isFalse();
        String overview = mvc.perform(get(WALLET).with(tokenFor(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JSON.readTree(overview).get("card").get("last4").asString()).isEqualTo("4242");
        assertThat(JSON.readTree(overview).get("cardPaymentsAvailable").asBoolean())
                .isTrue();
        assertThat(begun + confirmed + overview)
                .as("never the reference, never the provider's token, never the account it was minted under")
                .doesNotContain("fake_card_")
                .doesNotContain(installationId);
        assertThat(mvc.perform(get(WALLET).with(tokenFor(OTHER_OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void theStaffOverviewOfAWalletSaysWhichCardAndNeverWhichReference() throws Exception {
        String installationId = staffActivatesTheFake();
        ownerBindsCard(FakeCardProvider.APPROVING_CARD);
        String storedReference = jdbc.sql(
                        "SELECT card_token_reference FROM commercial.tenant_billing" + " WHERE tenant_id = :tenant")
                .param("tenant", TENANT)
                .query(String.class)
                .single();
        assertThat(storedReference)
                .as("the premise: the column holds the installation and the provider's vault token")
                .startsWith(installationId + ":")
                .contains("fake_card_");

        MvcResult read = mvc.perform(get("/api/v1/control-plane/tenants/" + TENANT + "/wallet")
                        .with(tokenFor(STAFF)))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        String body = read.getResponse().getContentAsString();
        assertThat(body)
                .as("a support agent with commercial.wallet.read sees which card, never which reference")
                .doesNotContain(storedReference)
                .doesNotContain("fake_card_")
                .doesNotContain(installationId);
        JsonNode view = JSON.readTree(body);
        assertThat(view.get("cardTokenReference").isNull())
                .as("the field stays, so no client breaks (ADR 0031), and is always null")
                .isTrue();
        assertThat(view.get("card").get("last4").asString()).isEqualTo("4242");
        assertThat(view.get("card").get("lapsed").asBoolean()).isFalse();
        assertThat(view.get("hasCard").asBoolean()).isTrue();
    }

    @Test
    void withNoMerchantAccountTheTenantIsToldCardsAreNotAvailableYet() throws Exception {
        MvcResult refused = mvc.perform(post(WALLET + "/card/enrolments")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-none")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("CARDS_NOT_AVAILABLE");
    }

    @Test
    void aBodyThatOmitsAFieldIsAValidationAnswerAndNotAMalformedOne() throws Exception {
        staffActivatesTheFake();

        for (String body : List.of("{}", "{\"amountMinor\":null}", "{\"amountMinor\":0}", "{\"amountMinor\":-5}")) {
            MvcResult refused = mvc.perform(post(WALLET + "/top-ups")
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-" + body.hashCode())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as(body).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).as(body).contains("VALIDATION_FAILED");
        }
        MvcResult malformed = mvc.perform(post(WALLET + "/top-ups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":\"a lot\"}"))
                .andReturn();
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        MvcResult noConfirmFields = mvc.perform(post(WALLET + "/card/confirmations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-confirm-empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(noConfirmFields.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    // ---------------------------------------------------------------- money in, through HTTP

    @Test
    void anOwnerTopsUpFromTheCardAndAReplayOfTheSameKeyIsTheSameAnswerAndNotASecondCharge() throws Exception {
        staffActivatesTheFake();
        ownerBindsCard(FakeCardProvider.APPROVING_CARD);

        String first = postAs(OWNER, WALLET + "/top-ups", "idem-topup", "{\"amountMinor\":250000}", 200);
        String replay = postAs(OWNER, WALLET + "/top-ups", "idem-topup", "{\"amountMinor\":250000}", 200);

        JsonNode topUp = JSON.readTree(first);
        assertThat(topUp.get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(topUp.get("amount").get("amountMinor").asLong()).isEqualTo(250_000);
        assertThat(replay).isEqualTo(first);
        assertThat(fake.successfulCharges()).as("one charge for one key").hasSize(1);
        JsonNode wallet = JSON.readTree(mvc.perform(get(WALLET).with(tokenFor(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(wallet.get("paidBalance").get("amountMinor").asLong()).isEqualTo(250_000);
        String ledger = mvc.perform(get(WALLET + "/ledger").with(tokenFor(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JSON.readTree(ledger).get("items").get(0).get("entryType").asString())
                .isEqualTo("TOP_UP");
        assertThat(ledger)
                .as("the tenant's ledger does not name who at HorecaOS recorded a line")
                .doesNotContain("recordedBy");
        assertThat(JSON.readTree(ledger).get("items").get(0).has("reason"))
                .as("nor what a staff member typed beside it (ADR 0029)")
                .isFalse();
    }

    @Test
    void aDeclinedCardIsAnOutcomeNotAnErrorAndMovesNothing() throws Exception {
        staffActivatesTheFake();
        ownerBindsCard(FakeCardProvider.DECLINING_CARD);

        JsonNode declined =
                JSON.readTree(postAs(OWNER, WALLET + "/top-ups", "idem-decl", "{\"amountMinor\":90000}", 200));

        assertThat(declined.get("outcome").asString()).isEqualTo("FAILED");
        assertThat(declined.get("reason").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(JSON.readTree(mvc.perform(get(WALLET).with(tokenFor(OWNER)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                        .get("paidBalance")
                        .get("amountMinor")
                        .asLong())
                .isZero();
    }

    @Test
    void whoMayPutMoneyInAndHowItIsGuarded() throws Exception {
        staffActivatesTheFake();
        ownerBindsCard(FakeCardProvider.APPROVING_CARD);

        MvcResult adminRefused = mvc.perform(post(WALLET + "/top-ups")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000}"))
                .andReturn();
        MvcResult otherRefused = mvc.perform(post(WALLET + "/top-ups")
                        .with(tokenFor(OTHER_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000}"))
                .andReturn();
        MvcResult noKey = mvc.perform(post(WALLET + "/top-ups")
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000}"))
                .andReturn();

        assertThat(adminRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(adminRefused.getResponse().getContentAsString()).contains("commercial.wallet.topup");
        assertThat(otherRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(fake.successfulCharges()).isEmpty();
        // Finance holds both capabilities, as the owner does.
        assertThat(mvc.perform(post(WALLET + "/top-ups")
                                .with(tokenFor(FINANCE))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-fin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amountMinor\":1000}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void theMethodNeedsACardAndOnlyTheCardCapabilityChoosesIt() throws Exception {
        staffActivatesTheFake();

        MvcResult noCard = mvc.perform(post(WALLET + "/payment-method")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-m1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"CARD\"}"))
                .andReturn();
        assertThat(noCard.getResponse().getStatus()).isEqualTo(422);
        assertThat(noCard.getResponse().getContentAsString()).contains("NO_CARD_ON_FILE");

        ownerBindsCard(FakeCardProvider.APPROVING_CARD);
        postAs(OWNER, WALLET + "/payment-method", "idem-m2", "{\"paymentMethod\":\"CARD\"}", 200);
        assertThat(JSON.readTree(mvc.perform(get(WALLET).with(tokenFor(OWNER)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                        .get("paymentMethod")
                        .asString())
                .isEqualTo("CARD");
        assertThat(mvc.perform(post(WALLET + "/payment-method")
                                .with(tokenFor(ADMIN))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-m3")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"paymentMethod\":\"WALLET\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(post(WALLET + "/payment-method")
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-m4")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"paymentMethod\":\"CASH\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        String removed = postAs(OWNER, WALLET + "/card/removal", "idem-rm", "{}", 200);
        assertThat(JSON.readTree(removed).get("paymentMethod").asString())
                .as("CARD with no card is a promise nothing can keep")
                .isEqualTo("INVOICE");
    }

    @Test
    void staffAreToldWhichChargesAreStillWaitingBeforeTheyReplaceTheAccountAndMayAcknowledgeIt() throws Exception {
        String installationId = staffActivatesTheFake();
        ownerBindsCard(FakeCardProvider.UNANSWERING_CARD);
        String pending = postAs(OWNER, WALLET + "/top-ups", "idem-pending", "{\"amountMinor\":400000}", 200);
        assertThat(JSON.readTree(pending).get("outcome").asString()).isEqualTo("PENDING");
        String suspension = STAFF_BASE + "/billing/card-installations/" + installationId + "/suspension";

        // The console's body: the version and the reason, and nothing about acknowledging.
        String refused = staffPost(suspension, "idem-s1", "{\"expectedVersion\":1,\"reason\":\"replacing it\"}", 409);

        assertThat(refused)
                .contains("UNRESOLVED_CARD_CHARGES")
                .as("how many, so the person knows what they would be leaving")
                .contains("unresolvedTopUps");
        assertThat(JSON.readTree(mvc.perform(get("/api/v1/control-plane/billing/card-installations")
                                        .with(tokenFor(STAFF)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                        .get(0)
                        .get("status")
                        .asString())
                .as("nothing moved")
                .isEqualTo("ACTIVE");

        String suspended = staffPost(
                suspension,
                "idem-s2",
                "{\"expectedVersion\":1,\"reason\":\"the account is unreachable\",\"acknowledgeUnresolvedCharges\":true}",
                200);
        assertThat(JSON.readTree(suspended).get("status").asString()).isEqualTo("SUSPENDED");
    }

    // --------------------------------------------------------------------- invoices

    @Test
    void anInvoiceWaitsForBankDetailsThenIsPaidByAWireFinanceRecordsNamingIt() throws Exception {
        MvcResult early = mvc.perform(post(WALLET + "/invoices")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-inv0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000000}"))
                .andReturn();
        assertThat(early.getResponse().getStatus()).isEqualTo(422);
        assertThat(early.getResponse().getContentAsString()).contains("BANK_DETAILS_NOT_CONFIGURED");
        JsonNode placeholder =
                JSON.readTree(mvc.perform(get(WALLET + "/payment-details").with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(placeholder.get("configured").asBoolean()).isFalse();

        staffConfigureBankDetails();

        JsonNode invoice =
                JSON.readTree(postAs(OWNER, WALLET + "/invoices", "idem-inv1", "{\"amountMinor\":1000000}", 200));
        assertThat(invoice.get("number").asString()).matches("PI-\\d{6}-\\d{6}");
        assertThat(invoice.get("status").asString()).isEqualTo("OPEN");
        assertThat(invoice.get("paymentPurpose").asString())
                .isEqualTo(invoice.get("number").asString());
        assertThat(invoice.get("beforeTax").asBoolean()).isTrue();
        assertThat(invoice.get("paymentDetails").get("account").asString()).isEqualTo("20208000900123456001");
        String invoiceId = invoice.get("invoiceId").asString();

        String export = mvc.perform(
                        get(WALLET + "/invoices/" + invoiceId + "/export").with(tokenFor(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString(UTF_8);
        assertThat(export).startsWith("number,status,currency,amount_minor,paid_minor,due_minor");
        assertThat(mvc.perform(get("/api/v1/tenants/" + OTHER_TENANT + "/commercial/wallet/invoices/" + invoiceId)
                                .with(tokenFor(OTHER_OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("another tenant's invoice is as unknown as a random id")
                .isEqualTo(404);
        assertThat(mvc.perform(get(WALLET + "/invoices/" + invoiceId).with(tokenFor(OTHER_OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        String transferBody = "{\"amountMinor\":1000000,\"bankReference\":\"MT103-WIRE-1\",\"reason\":\"the wire\","
                + "\"prepaymentInvoiceNumber\":\"" + invoice.get("number").asString() + "\"}";
        staffPost(STAFF_BASE + "/tenants/" + TENANT + "/wallet/transfers", "idem-wire", transferBody, 200);

        JsonNode paid =
                JSON.readTree(mvc.perform(get(WALLET + "/invoices/" + invoiceId).with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("paid").get("amountMinor").asLong()).isEqualTo(1_000_000);
        MvcResult cancelPaid = mvc.perform(post(WALLET + "/invoices/" + invoiceId + "/cancel")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-cancel-paid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(cancelPaid.getResponse().getStatus()).isEqualTo(422);
        assertThat(cancelPaid.getResponse().getContentAsString()).contains("INVOICE_HAS_PAYMENTS");

        JsonNode second =
                JSON.readTree(postAs(OWNER, WALLET + "/invoices", "idem-inv2", "{\"amountMinor\":300000}", 200));
        JsonNode cancelled = JSON.readTree(postAs(
                OWNER,
                WALLET + "/invoices/" + second.get("invoiceId").asString() + "/cancel",
                "idem-cancel",
                "{}",
                200));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void theBankDetailsAndTheCardAccountAreStaffsAloneAndTheTenantCannotReachThem() throws Exception {
        for (String path : List.of(STAFF_BASE + "/billing/bank-details", STAFF_BASE + "/billing/card-installations")) {
            MvcResult refused = mvc.perform(post(path)
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "idem-" + path.hashCode())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as(path).isEqualTo(403);
        }
        for (String path : List.of(
                "/api/v1/control-plane/billing/bank-details", "/api/v1/control-plane/billing/card-installations")) {
            assertThat(mvc.perform(get(path).with(tokenFor(OWNER)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(path)
                    .isEqualTo(403);
        }
        MvcResult read = mvc.perform(
                        get("/api/v1/control-plane/billing/bank-details").with(tokenFor(STAFF)))
                .andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(read.getResponse().getContentAsString())
                        .get("configured")
                        .asBoolean())
                .isFalse();
    }

    // ------------------------------------------------------------------------ arrears

    @Test
    void theArrearsReadSaysWhatIsOwedAndHowToPayAndTheBoardSaysWhoHasPaidInFull() throws Exception {
        staffActivatesTheFake();
        ownerBindsCard(FakeCardProvider.APPROVING_CARD);
        UUID versionId = activePlan();
        clock.set(OCTOBER);
        subscriptions.start(TENANT, versionId, null, ActorRef.user(STAFF, null), "pilot", "c");
        clock.set(Instant.parse("2026-11-05T09:00:00Z"));
        statements.issue(TENANT, "2026-10", ActorRef.user(STAFF, null), "October close", "c");
        moveToPastDue();

        JsonNode owing = JSON.readTree(mvc.perform(get(ARREARS).with(tokenFor(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(owing.get("status").asString()).isEqualTo("PAST_DUE");
        assertThat(owing.get("owed").get("due").get("amountMinor").asLong()).isEqualTo(MONTHLY);
        assertThat(owing.get("owed").get("openStatements").asInt()).isEqualTo(1);
        assertThat(owing.get("waysToPay").get("cardOnFile").asBoolean()).isTrue();
        assertThat(owing.get("waysToPay").get("cardPaymentsAvailable").asBoolean())
                .isTrue();
        assertThat(owing.get("waysToPay").get("bankTransferAvailable").asBoolean())
                .isFalse();
        JsonNode onBoard = boardRow(STAFF);
        assertThat(onBoard.get("paidInFull").asBoolean()).isFalse();
        assertThat(onBoard.get("owed").get("due").get("amountMinor").asLong()).isEqualTo(MONTHLY);

        postAs(OWNER, WALLET + "/top-ups", "idem-clear", "{\"amountMinor\":" + MONTHLY + "}", 200);

        JsonNode cleared = JSON.readTree(mvc.perform(get(ARREARS).with(tokenFor(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(cleared.get("owed").isNull()).isTrue();
        assertThat(cleared.get("status").asString())
                .as("paying does not restore the subscription: nothing moves it by itself (ADR 0089)")
                .isEqualTo("PAST_DUE");
        JsonNode afterOnBoard = boardRow(STAFF);
        assertThat(afterOnBoard.get("paidInFull").asBoolean())
                .as("the cue for the person who restores it")
                .isTrue();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'commercial.arrears.paid_in_full'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(mvc.perform(get(ARREARS).with(tokenFor(OTHER_OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void theBoardDoesNotSayPaidInFullWhileTheActivationDepositIsStillDue() throws Exception {
        staffActivatesTheFake();
        ownerBindsCard(FakeCardProvider.APPROVING_CARD);
        UUID versionId = activePlan(300_000L);
        clock.set(OCTOBER);
        subscriptions.start(TENANT, versionId, null, ActorRef.user(STAFF, null), "pilot", "c");
        clock.set(Instant.parse("2026-11-05T09:00:00Z"));
        statements.issue(TENANT, "2026-10", ActorRef.user(STAFF, null), "October close", "c");
        moveToPastDue();

        postAs(OWNER, WALLET + "/top-ups", "idem-stmt", "{\"amountMinor\":" + MONTHLY + "}", 200);

        JsonNode row = boardRow(STAFF);
        assertThat(row.get("owed").isNull())
                .as("every issued statement is paid")
                .isTrue();
        assertThat(row.get("paidInFull").asBoolean())
                .as("the activation deposit is owed beside the statements, so this tenant has not paid in full")
                .isFalse();
        assertThat(row.get("depositDue").get("amountMinor").asLong())
                .as("and the board says what is still due")
                .isEqualTo(300_000L);
        assertThat(row.get("depositDue").get("currency").asString()).isEqualTo("UZS");
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'commercial.arrears.paid_in_full'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // --------------------------------------------------------------------- fixtures

    private JsonNode boardRow(String subject) throws Exception {
        JsonNode board =
                JSON.readTree(mvc.perform(get("/api/v1/control-plane/arrears").with(tokenFor(subject)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        for (JsonNode row : board.get("subscriptions")) {
            if (TENANT.toString().equals(row.get("tenantId").asString())) {
                return row;
            }
        }
        throw new AssertionError("the tenant is not on the arrears board: " + board);
    }

    private void moveToPastDue() {
        long version = subscriptions.live(TENANT).orElseThrow().version();
        subscriptions.transition(
                TENANT,
                uz.horecaos.platform.commercial.domain.SubscriptionStatus.PAST_DUE,
                version,
                null,
                null,
                ActorRef.user(STAFF, null),
                "late",
                "c");
    }

    private UUID activePlan() {
        return activePlan(0);
    }

    private UUID activePlan(long activationDepositMinor) {
        UUID planId = plans.createPlan("WALLET_EP", "Wallet endpoint plan", ActorRef.user(STAFF, null), "prices", "c");
        UUID versionId = plans.draftVersion(
                planId,
                "UZS",
                MONTHLY,
                "MONTHLY",
                null,
                Map.of(),
                new PlanTerms(null, activationDepositMinor, Map.of()),
                ActorRef.user(STAFF, null),
                "prices",
                "c");
        plans.activate(versionId, ActorRef.user(STAFF_CHECKER, null), "signed off", "c");
        return versionId;
    }

    private String staffActivatesTheFake() throws Exception {
        String created = staffPost(
                STAFF_BASE + "/billing/card-installations",
                "idem-inst-" + UUID.randomUUID(),
                "{\"providerType\":\"FAKE_CARD\",\"displayName\":\"Fake\",\"reason\":\"local\"}",
                200);
        String id = JSON.readTree(created).get("installationId").asString();
        String activated = staffPost(
                STAFF_BASE + "/billing/card-installations/" + id + "/activation",
                "idem-act-" + UUID.randomUUID(),
                "{\"expectedVersion\":0,\"reason\":\"local\"}",
                200);
        JsonNode view = JSON.readTree(activated);
        assertThat(view.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(view.get("secretConfigured").asBoolean()).isFalse();
        return id;
    }

    private void staffConfigureBankDetails() throws Exception {
        String body =
                "{\"beneficiary\":\"HorecaOS MCHJ\",\"bankName\":\"Kapitalbank\",\"account\":\"20208000900123456001\","
                        + "\"mfo\":\"01158\",\"taxId\":\"309876543\",\"reason\":\"launch details\"}";
        JsonNode first = JSON.readTree(staffPost(STAFF_BASE + "/billing/bank-details", "idem-bank-1", body, 200));
        assertThat(first.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        UUID requestId = UUID.fromString(first.get("approvalRequestId").asString());
        approvals.decide(requestId, ApprovalService.Decision.APPROVE, ActorRef.user(STAFF_CHECKER, null), "checked");
        JsonNode applied = JSON.readTree(staffPost(STAFF_BASE + "/billing/bank-details", "idem-bank-2", body, 200));
        assertThat(applied.get("status").asString()).isEqualTo("CHANGED");
    }

    private void ownerBindsCard(String providerToken) throws Exception {
        String begun = postAs(OWNER, WALLET + "/card/enrolments", "idem-b-" + UUID.randomUUID(), "{}", 200);
        String session = JSON.readTree(begun).get("sessionReference").asString();
        postAs(
                OWNER,
                WALLET + "/card/confirmations",
                "idem-c-" + UUID.randomUUID(),
                "{\"sessionReference\":\"" + session + "\",\"providerToken\":\"" + providerToken
                        + "\",\"verificationCode\":\"" + FakeCardProvider.VERIFICATION_CODE + "\"}",
                200);
    }

    private String postAs(String subject, String path, String key, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(path)
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s %s -> %s", path, body, result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    private String staffPost(String path, String key, String body, int expectedStatus) throws Exception {
        return postAs(STAFF, path, key, body, expectedStatus);
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'wallet self-service endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantPlatformAdmin(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'wallet self-service endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes(("platform-" + subject).getBytes(UTF_8)))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    /** The clock the whole context runs on, which a test moves to live through a month end. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
