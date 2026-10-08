package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceDocumentReference;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorAccount;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.api.EInvoiceSendOutcome;
import uz.horecaos.platform.commercial.api.EInvoiceStateOutcome;
import uz.horecaos.platform.commercial.api.EInvoicingOperator;
import uz.horecaos.platform.commercial.domain.EInvoiceDelivery;
import uz.horecaos.platform.commercial.domain.EInvoicingInstallation;
import uz.horecaos.platform.commercial.domain.EInvoicingLineClassification;
import uz.horecaos.platform.commercial.domain.StatementEInvoice;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcEInvoiceStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.tenancy.api.LegalParty;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0096 against PostgreSQL: an issued statement is sent to an operator by staff, the
 * attempt is durable before the operator is called, and what the operator answers -- or does not
 * -- is recorded without ever sending the same statement twice.
 *
 * <p>The operator is a fake that records what it was handed and, at the moment it is asked, what
 * the database held and whether a transaction was open. Those two facts are the point: the
 * attempt must already be committed as {@code PENDING} when the operator is called, and no
 * pooled connection may be held across the call.
 */
class EInvoicingServiceTests {

    private static final UUID TENANT = UUID.fromString("018f9c10-6000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9c10-6000-7000-8000-0000000000a2");
    private static final UUID DIDOX_ID = UUID.fromString("018f9c10-5000-7000-8000-0000000000d1");
    private static final UUID FAKTURA_ID = UUID.fromString("018f9c10-5000-7000-8000-0000000000f1");
    private static final UUID COMPANY = UUID.fromString("018f9c10-6000-7000-8000-0000000000c1");
    private static final UUID SECOND_COMPANY = UUID.fromString("018f9c10-6000-7000-8000-0000000000c2");

    private static final String REFERENCE = "horecaos:production:provider_einvoicing:platform:operator-1";
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final Instant ISSUED = Instant.parse("2026-10-01T08:00:00Z");

    private static final ActorRef STAFF = ActorRef.user("finance.staff", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private List<AuditFact> facts;
    private FakeOperator didox;
    private Directory directory;
    private EInvoicingService service;
    private JdbcEInvoiceStore store;

    /** One standing statement per tenant and month: each statement a test makes gets a month of its own. */
    private int periods;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE commercial.statements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenant)
                    .param("slug", "t-" + tenant.toString().substring(30))
                    .update();
        }
        // The seeded state: both installations unbound, every classification provisional.
        jdbc.sql("""
                UPDATE commercial.einvoicing_installations
                   SET status = 'DRAFT', secret_reference = NULL, non_sensitive_config = '{}'::jsonb, version = 0
                """).update();
        jdbc.sql("""
                UPDATE commercial.einvoicing_line_classifications
                   SET vat_rate_bp = 1200, provisional = true, confirmed_by = NULL, confirmed_at = NULL,
                       version = 0, item_label = 'Подписка', catalog_code = '10305011001000000'
                """).update();

        periods = 0;
        clock = new MovableClock(NOW);
        facts = new ArrayList<>();
        AuditRecorder audit = facts::add;
        store = new JdbcEInvoiceStore(jdbc, JsonMapper.builder().build());
        directory = new Directory(List.of(party(COMPANY, "MAIN", "301234567", "Non uyi MCHJ")));
        didox = new FakeOperator("DIDOX");
        service = new EInvoicingService(
                store,
                new JdbcStatementStore(jdbc),
                directory,
                List.of(didox),
                audit,
                new TransactionTemplate(new DataSourceTransactionManager(db.dataSource())),
                clock);
    }

    // ----------------------------------------------------------- the accounts

    @Test
    @DisplayName("HorecaOS has no account with either operator yet: both installations are seeded unbound")
    void bothInstallationsStartUnbound() {
        List<EInvoicingService.InstallationDetail> installations = service.installations();

        assertThat(installations).hasSize(2);
        for (EInvoicingService.InstallationDetail detail : installations) {
            assertThat(detail.installation().status()).isEqualTo("DRAFT");
            assertThat(detail.installation().connected()).isFalse();
            assertThat(detail.installation().secretReference()).isNull();
            assertThat(detail.installation().missing())
                    .containsExactly("SECRET_REFERENCE", "SELLER_TAXPAYER_NUMBER", "SELLER_NAME");
            assertThat(Objects.requireNonNull(detail.environment()).baseUrl()).startsWith("https://");
        }
        assertThat(installations.stream()
                        .filter(d -> d.adapterWired())
                        .map(d -> d.installation().providerType()))
                .as("only the adapter this test wires is wired")
                .containsExactly("DIDOX");
    }

    @Test
    @DisplayName("an unbound account is never called: the send is refused, says why, and writes nothing")
    void anUnboundOperatorIsNotCalled() {
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan(), module());

        assertThatThrownBy(() -> send(statement)).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
            assertThat(refusal.properties()).containsEntry("reason", "OPERATOR_NOT_CONNECTED");
            assertThat(refusal.properties()).containsEntry("operator", "DIDOX");
        });

        assertThat(didox.sent).isEmpty();
        assertThat(count("commercial.statement_einvoices")).isZero();
    }

    @Test
    @DisplayName(
            "an account is bound by a secret reference in its own category and the seller's identity, then activated")
    void anAccountIsBoundThenActivated() {
        EInvoicingInstallation installation =
                service.installationDetail(DIDOX_ID).installation();

        assertThatThrownBy(
                        () -> service.activateInstallation(DIDOX_ID, installation.version(), STAFF, "go live", "corr"))
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
                    assertThat(refusal.properties()).containsEntry("reason", "OPERATOR_NOT_CONNECTED");
                    assertThat(refusal.properties().get("missing"))
                            .isEqualTo(List.of("SECRET_REFERENCE", "SELLER_TAXPAYER_NUMBER", "SELLER_NAME"));
                });

        EInvoicingInstallation bound = service.updateInstallation(
                DIDOX_ID, 0, "Didox", REFERENCE, sellerConfig(true), STAFF, "account opened", "corr");
        assertThat(bound.missing()).isEmpty();
        assertThat(bound.status()).as("binding is not activating").isEqualTo("DRAFT");
        assertThat(bound.version()).isEqualTo(1);

        EInvoicingInstallation live = service.activateInstallation(DIDOX_ID, 1, STAFF, "go live", "corr");
        assertThat(live.status()).isEqualTo("ACTIVE");
        assertThat(live.connected()).isTrue();
        assertThat(live.version()).isEqualTo(2);

        assertThat(facts.stream().map(AuditFact::actionCode))
                .containsExactly(
                        "commercial.einvoicing.installation.updated", "commercial.einvoicing.installation.active");
        AuditFact updated = facts.get(0);
        assertThat(updated.capabilityUsed()).isEqualTo("commercial.einvoicing.manage");
        assertThat(updated.reason()).isEqualTo("account opened");
        assertThat(updated.changeDocument().toString())
                .as("the reference is a reference and is redacted in the trail all the same")
                .doesNotContain("operator-1")
                .contains("secretReference");
    }

    @Test
    @DisplayName("what is stored is checked: the reference, the taxpayer number, the language and the keys")
    void theAccountSettingsAreValidated() {
        assertThatThrownBy(() -> service.updateInstallation(
                        DIDOX_ID, 0, "Didox", "horecaos:production:provider_payment:t:x", Map.of(), STAFF, "r", "c"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("provider_einvoicing");
        assertThatThrownBy(() ->
                        service.updateInstallation(DIDOX_ID, 0, "Didox", "not a reference", Map.of(), STAFF, "r", "c"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("secret reference");
        assertThatThrownBy(() -> service.updateInstallation(
                        DIDOX_ID, 0, "Didox", null, Map.of("sellerTaxpayerNumber", "12345"), STAFF, "r", "c"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("9 digits");
        assertThatThrownBy(() ->
                        service.updateInstallation(DIDOX_ID, 0, "Didox", null, Map.of("locale", "en"), STAFF, "r", "c"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ru or uz");
        assertThatThrownBy(() -> service.updateInstallation(
                        DIDOX_ID, 0, "Didox", null, Map.of("password", "hunter2"), STAFF, "r", "c"))
                .as(
                        "an unknown key is refused rather than stored, so a password typed into the wrong box is never saved")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown account setting");
        assertThat(service.installationDetail(DIDOX_ID).installation().version())
                .isZero();
    }

    @Test
    @DisplayName("an account moved since it was read is a stale version, and an active one keeps what it needs")
    void staleVersionsAndActiveAccounts() {
        service.updateInstallation(DIDOX_ID, 0, "Didox", REFERENCE, sellerConfig(true), STAFF, "r", "c");

        assertThatThrownBy(() -> service.updateInstallation(
                        DIDOX_ID, 0, "Didox", REFERENCE, sellerConfig(true), STAFF, "again", "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        stale -> assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));

        service.activateInstallation(DIDOX_ID, 1, STAFF, "live", "c");
        assertThatThrownBy(() -> service.updateInstallation(
                        DIDOX_ID, 2, "Didox", "", sellerConfig(true), STAFF, "drop the reference", "c"))
                .as("an active account is suspended before it is unbound")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal ->
                                assertThat(refusal.properties()).containsEntry("reason", "ACTIVE_NEEDS_CONFIGURATION"));

        EInvoicingInstallation rotated = service.updateInstallation(
                DIDOX_ID,
                2,
                "Didox",
                "horecaos:production:provider_einvoicing:platform:operator-2",
                sellerConfig(true),
                STAFF,
                "rotated to a new reference",
                "c");
        assertThat(rotated.status()).isEqualTo("ACTIVE");
        assertThat(rotated.secretReference()).endsWith("operator-2");

        assertThat(service.updateInstallation(
                                DIDOX_ID,
                                rotated.version(),
                                "Didox",
                                rotated.secretReference(),
                                sellerConfig(true),
                                STAFF,
                                "nothing changed",
                                "c")
                        .version())
                .as("saving what is already there writes nothing and records nothing")
                .isEqualTo(rotated.version());
    }

    @Test
    @DisplayName("an absent reference keeps the one on file, because the screen never learns it; a blank one clears it")
    void anAbsentReferenceKeepsTheOneOnFile() {
        service.updateInstallation(DIDOX_ID, 0, "Didox", REFERENCE, sellerConfig(true), STAFF, "opened", "c");

        EInvoicingInstallation renamed = service.updateInstallation(
                DIDOX_ID, 1, "Didox (production)", null, sellerConfig(true), STAFF, "renamed", "c");

        assertThat(renamed.secretReference()).isEqualTo(REFERENCE);
        assertThat(renamed.displayName()).isEqualTo("Didox (production)");
        assertThat(facts.getLast().changeDocument().toString())
                .as("only what changed is in the trail")
                .contains("displayName")
                .doesNotContain("secretReference");

        EInvoicingInstallation cleared = service.updateInstallation(
                DIDOX_ID, 2, "Didox (production)", "  ", sellerConfig(true), STAFF, "unbound", "c");

        assertThat(cleared.secretReference()).isNull();
        assertThat(cleared.missing()).contains("SECRET_REFERENCE");
    }

    @Test
    @DisplayName("an account with no adapter wired cannot be activated, and suspending stops all sending")
    void noAdapterAndSuspension() {
        service.updateInstallation(FAKTURA_ID, 0, "Faktura.uz", REFERENCE, sellerConfig(true), STAFF, "r", "c");
        assertThatThrownBy(() -> service.activateInstallation(FAKTURA_ID, 1, STAFF, "live", "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "NO_ADAPTER"));

        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        service.suspendInstallation(DIDOX_ID, 2, STAFF, "operator outage", "c");

        assertThatThrownBy(() -> send(statement))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "OPERATOR_NOT_CONNECTED"));
        assertThat(didox.sent).isEmpty();
        assertThatThrownBy(() -> service.suspendInstallation(DIDOX_ID, 3, STAFF, "again", "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    // --------------------------------------------------------- the line kinds

    @Test
    @DisplayName("the classification of every kind starts provisional, and confirming one records who and when")
    void classificationsAreProvisionalUntilConfirmed() {
        List<EInvoicingLineClassification> all = service.classifications();
        assertThat(all.stream().map(EInvoicingLineClassification::lineKind))
                .containsExactlyInAnyOrder("PLAN", "MODULE", "OVERAGE", "EARLY_EXIT", "DEPOSIT");
        assertThat(all).allSatisfy(classification -> {
            assertThat(classification.provisional()).isTrue();
            assertThat(classification.confirmedBy()).isNull();
            assertThat(classification.vatRateBp()).isEqualTo(1200);
        });

        EInvoicingLineClassification confirmed = service.updateClassification(
                "PLAN",
                0,
                "Подписка",
                "10305011001000000",
                "Услуги",
                "1500002",
                "услуга",
                1200,
                true,
                STAFF,
                "finance confirmed the treatment",
                "c");
        assertThat(confirmed.provisional()).isFalse();
        assertThat(confirmed.confirmedBy()).isEqualTo("finance.staff");
        assertThat(confirmed.confirmedAt()).isEqualTo(NOW);
        assertThat(confirmed.version()).isEqualTo(1);

        EInvoicingLineClassification edited = service.updateClassification(
                "PLAN",
                1,
                "Подписка",
                "10305011001000000",
                "Услуги",
                "1500002",
                "услуга",
                0,
                false,
                STAFF,
                "zero rated pending a ruling",
                "c");
        assertThat(edited.provisional())
                .as("an edit that is not confirmed withdraws the confirmation")
                .isTrue();
        assertThat(edited.confirmedBy()).isNull();
        assertThat(edited.vatRateBp()).isZero();

        assertThat(facts.stream().map(AuditFact::actionCode))
                .containsExactly(
                        "commercial.einvoicing.classification.updated", "commercial.einvoicing.classification.updated");
        assertThat(facts.get(1).changeDocument().toString()).contains("vatRateBp");
    }

    @Test
    @DisplayName("a classification refuses an unknown kind, a rate outside 0-100 percent, blanks and a stale version")
    void classificationsAreValidated() {
        assertThatThrownBy(() -> classify("EXTRA", 0, 1200)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> classify("PLAN", 0, 10_001)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> classify("PLAN", 0, -1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
                        service.updateClassification("PLAN", 0, " ", "c", "n", "p", "pn", 1200, false, STAFF, "r", "c"))
                .isInstanceOf(ApiException.class);
        classify("PLAN", 0, 1200);
        assertThatThrownBy(() -> classify("PLAN", 0, 1200))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        stale -> assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
    }

    private EInvoicingLineClassification classify(String kind, long version, int vatRateBp) {
        return service.updateClassification(
                kind,
                version,
                "Подписка",
                "10305011001000000",
                "Услуги",
                "1500002",
                "услуга",
                vatRateBp,
                false,
                STAFF,
                "r",
                "c");
    }

    // ----------------------------------------------------------- sending

    @Test
    @DisplayName(
            "the attempt is committed as PENDING before the operator is called, with no transaction open, and then concluded")
    void theAttemptIsDurableBeforeTheCall() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan(), module());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));

        StatementEInvoice sent = send(statement);

        assertThat(didox.sent).hasSize(1);
        assertThat(didox.deliveryAtCall).as("committed before the call").isEqualTo("PENDING");
        assertThat(didox.transactionActiveAtCall)
                .as("a slow operator must not hold one of ten pooled connections")
                .isFalse();
        assertThat(sent.delivery()).isEqualTo(EInvoiceDelivery.SUBMITTED);
        assertThat(sent.operatorDocumentId()).isEqualTo("DOC-1");
        assertThat(sent.operatorState()).isEqualTo(EInvoiceOperatorState.DRAFT);
        assertThat(sent.operatorStatus()).isEqualTo("created");
        assertThat(sent.live()).isTrue();
        assertThat(sent.stateCheckedAt()).isEqualTo(NOW);
        assertThat(sent.sentBy()).isEqualTo("finance.staff");
        assertThat(sent.sendReason()).isEqualTo("month closed");
        assertThat(sent.providerType()).isEqualTo("DIDOX");

        // The buyer is the tenant's company; the seller is HorecaOS from the account.
        assertThat(sent.legalEntityId()).isEqualTo(COMPANY);
        assertThat(sent.buyerTaxpayerNumber()).isEqualTo("301234567");
        assertThat(sent.buyerName()).isEqualTo("Non uyi MCHJ");
        assertThat(sent.sellerTaxpayerNumber()).isEqualTo("305000001");

        // The document: number, dates, the lines under their classification with VAT on top.
        EInvoiceDocument document = didox.sent.getFirst();
        assertThat(document.documentNumber()).isEqualTo("S-2026-09-000001");
        assertThat(document.documentDate().toString()).isEqualTo("2026-10-07");
        assertThat(document.contractNumber()).isEqualTo("S-2026-09-000001");
        assertThat(document.contractDate().toString()).isEqualTo("2026-10-01");
        assertThat(document.currency()).isEqualTo("UZS");
        assertThat(document.clientReference()).isEqualTo(sent.id().toString().replace("-", ""));
        assertThat(document.lines()).hasSize(2);
        assertThat(document.lines().get(0).netMinor()).isEqualTo(50_000_000);
        assertThat(document.lines().get(0).vatMinor()).isEqualTo(6_000_000);
        assertThat(document.lines().get(0).classificationCode()).isEqualTo("10305011001000000");
        assertThat(document.lines().get(0).name()).startsWith("Подписка: BASIC");
        assertThat(document.lines().get(1).quantity()).isEqualTo(3);
        assertThat(document.lines().get(1).netMinor()).isEqualTo(45_000_000);
        assertThat(sent.netMinor()).isEqualTo(95_000_000);
        assertThat(sent.vatMinor()).isEqualTo(11_400_000);
        assertThat(sent.totalMinor()).isEqualTo(106_400_000);
        assertThat(sent.classificationProvisional())
                .as("nothing has been confirmed by finance")
                .isTrue();

        // What was sent is what the record holds.
        assertThat(sent.sentDocument()).isEqualTo(document);
        assertThat(store.find(TENANT, sent.id()).orElseThrow().sentDocument()).isEqualTo(document);

        // Audited as the request, then the outcome, against the tenant, with the capability.
        assertThat(facts.stream().map(AuditFact::actionCode))
                .containsExactly("commercial.einvoice.send_requested", "commercial.einvoice.sent");
        for (AuditFact fact : facts) {
            assertThat(fact.capabilityUsed()).isEqualTo("commercial.einvoice.send");
            assertThat(fact.targetType()).isEqualTo("commercial.einvoice");
            assertThat(fact.targetId()).isEqualTo(sent.id());
            assertThat(fact.scope().tenantId()).isEqualTo(TENANT);
        }
        assertThat(facts.get(0).reason()).isEqualTo("month closed");
        assertThat(facts.get(1).changeDocument().toString()).contains("DOC-1");
    }

    @Test
    @DisplayName("VAT is added on top at each kind's rate, and not at all when the seller is not registered for VAT")
    void vatDependsOnTheSellersRegistration() {
        connect(false);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));

        StatementEInvoice unregistered = send(statement);

        assertThat(unregistered.vatMinor()).isZero();
        assertThat(unregistered.totalMinor()).isEqualTo(50_000_000);
        assertThat(didox.sent.getFirst().vatApplies()).isFalse();
        assertThat(didox.sent.getFirst().lines().getFirst().vatRateBp()).isZero();

        // Registered, at a different rate for one kind, rounded half up to a tiyin.
        service.updateInstallation(
                DIDOX_ID, 2, "Didox", REFERENCE, sellerConfig(true), STAFF, "registered for VAT", "c");
        jdbc.sql(
                        "UPDATE commercial.statement_einvoices SET delivery = 'FAILED', failure_code = 'x', operator_state = NULL")
                .update();
        service.suspendInstallation(DIDOX_ID, 3, STAFF, "reconfigure", "c");
        service.activateInstallation(DIDOX_ID, 4, STAFF, "live", "c");
        classify("PLAN", 0, 1250);
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-2", EInvoiceOperatorState.DRAFT, "created"));

        StatementEInvoice registered = send(statement);

        assertThat(registered.vatMinor()).isEqualTo(6_250_000);
        assertThat(didox.sent.getLast().vatApplies()).isTrue();
    }

    @Test
    @DisplayName("VAT is rounded half up to a whole minor unit")
    void vatRounding() {
        assertThat(EInvoicingService.vatOn(1_000, 1200)).isEqualTo(120);
        assertThat(EInvoicingService.vatOn(1, 1200)).as("0.12 tiyin").isZero();
        assertThat(EInvoicingService.vatOn(5, 1000)).as("0.5 rounds up").isEqualTo(1);
        assertThat(EInvoicingService.vatOn(4, 1000)).as("0.4 rounds down").isZero();
        assertThat(EInvoicingService.vatOn(50_000_000, 0)).isZero();
        assertThat(EInvoicingService.vatOn(Long.MAX_VALUE / 10_000, 10_000)).isPositive();
    }

    @Test
    @DisplayName("a line that bills nothing is not invoiced, and a statement of nothing but such lines is refused")
    void zeroLinesAreNotInvoiced() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", line("PLAN", "TRIAL", 0, 50_000_000), module());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));

        StatementEInvoice sent = send(statement);

        assertThat(didox.sent.getFirst().lines()).hasSize(1);
        assertThat(didox.sent.getFirst().lines().getFirst().number()).isEqualTo(1);
        assertThat(sent.netMinor()).isEqualTo(45_000_000);

        UUID nothing = statement("S-2026-09-000002", "ISSUED", "UZS", line("PLAN", "TRIAL", 0, 50_000_000));
        assertThatThrownBy(() -> send(nothing))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "NOTHING_TO_INVOICE"));
        assertThat(didox.sent).hasSize(1);
        assertThat(count("commercial.statement_einvoices")).isEqualTo(1);
    }

    @Test
    @DisplayName("only an issued statement in sums is sent, and one that is not another tenant's")
    void whatMayBeSent() {
        connect(true);
        UUID voided = statement("S-2026-09-000001", "VOID", "UZS", plan());
        UUID dollars = statement("S-2026-09-000002", "ISSUED", "USD", plan());
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> send(voided))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "STATEMENT_NOT_ISSUED"));
        assertThatThrownBy(() -> send(dollars))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal ->
                                assertThat(refusal.properties()).containsEntry("reason", "CURRENCY_NOT_INVOICEABLE"));
        assertThatThrownBy(() -> send(missing))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        UUID issued = statement("S-2026-09-000003", "ISSUED", "UZS", plan());
        assertThatThrownBy(() -> service.send(
                        new EInvoicingService.SendRequest(OTHER_TENANT, issued, "DIDOX", null, "r"), STAFF, "c"))
                .as("a statement is found within its own tenant only")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.send(
                        new EInvoicingService.SendRequest(TENANT, issued, "NOWHERE", null, "r"), STAFF, "c"))
                .isInstanceOf(ApiException.class);
        assertThat(didox.sent).isEmpty();
    }

    @Test
    @DisplayName("the buyer is the tenant's only company, or the one chosen, and never a guess")
    void buyerSelection() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());

        directory.parties = List.of();
        assertThatThrownBy(() -> send(statement))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal ->
                                assertThat(refusal.properties()).containsEntry("reason", "TENANT_HAS_NO_LEGAL_ENTITY"));

        directory.parties = List.of(
                party(COMPANY, "MAIN", "301234567", "Non uyi MCHJ"),
                party(SECOND_COMPANY, "BRANCH", "307654321", "Non uyi Savdo MCHJ"));
        assertThatThrownBy(() -> send(statement)).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.properties()).containsEntry("reason", "BUYER_CHOICE_REQUIRED");
            assertThat(refusal.properties().get("candidates")).isEqualTo(List.of("MAIN", "BRANCH"));
        });
        assertThatThrownBy(() -> service.send(
                        new EInvoicingService.SendRequest(TENANT, statement, "DIDOX", UUID.randomUUID(), "r"),
                        STAFF,
                        "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(didox.sent).isEmpty();

        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = service.send(
                new EInvoicingService.SendRequest(TENANT, statement, "DIDOX", SECOND_COMPANY, "branch is billed"),
                STAFF,
                "c");
        assertThat(sent.legalEntityId()).isEqualTo(SECOND_COMPANY);
        assertThat(sent.buyerTaxpayerNumber()).isEqualTo("307654321");
        assertThat(didox.sent.getFirst().buyer().name()).isEqualTo("Non uyi Savdo MCHJ");
    }

    @Test
    @DisplayName("a statement has one live invoice: a second send is refused before the operator is called")
    void oneLiveDocumentPerStatement() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        send(statement);

        assertThatThrownBy(() -> send(statement)).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
            assertThat(refusal.properties()).containsEntry("reason", "EINVOICE_LIVE");
        });
        assertThat(didox.sent).as("not asked a second time").hasSize(1);
        assertThat(count("commercial.statement_einvoices")).isEqualTo(1);
    }

    @Test
    @DisplayName("an operator that refuses the document leaves the statement free to be sent again")
    void aRefusalFreesTheStatement() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Refused("PROVIDER_REJECTED", "buyer is not registered"));
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));

        StatementEInvoice refused = send(statement);

        assertThat(refused.delivery()).isEqualTo(EInvoiceDelivery.FAILED);
        assertThat(refused.failureCode()).isEqualTo("PROVIDER_REJECTED");
        assertThat(refused.failureDetail()).isEqualTo("buyer is not registered");
        assertThat(refused.operatorDocumentId()).isNull();
        assertThat(refused.live()).isFalse();
        assertThat(facts.stream().map(AuditFact::actionCode))
                .containsExactly("commercial.einvoice.send_requested", "commercial.einvoice.send_failed");

        StatementEInvoice again = send(statement);
        assertThat(again.delivery()).isEqualTo(EInvoiceDelivery.SUBMITTED);
        assertThat(service.forStatement(TENANT, statement)).hasSize(2);
        assertThat(service.forStatement(TENANT, statement).getFirst().id())
                .as("newest first")
                .isEqualTo(again.id());
        assertThat(service.forTenant(TENANT)).hasSize(2);
        assertThat(service.forTenant(OTHER_TENANT)).isEmpty();
    }

    @Test
    @DisplayName("a send that never reached the operator is not sent, and is not live")
    void notSent() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.NotSent("CIRCUIT_OPEN", "Circuit open"));

        StatementEInvoice failed = send(statement);

        assertThat(failed.delivery()).isEqualTo(EInvoiceDelivery.FAILED);
        assertThat(failed.failureCode()).isEqualTo("CIRCUIT_OPEN");
        assertThat(failed.live()).isFalse();
    }

    @Test
    @DisplayName("a send whose answer was lost holds the statement; asking finds it, and nothing is sent twice")
    void anUncertainSendIsResolvedByAsking() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(
                () -> new EInvoiceSendOutcome.Uncertain("READ_TIMEOUT", "No response after the request was sent"));

        StatementEInvoice uncertain = send(statement);

        assertThat(uncertain.delivery()).isEqualTo(EInvoiceDelivery.UNCERTAIN);
        assertThat(uncertain.failureCode()).isEqualTo("READ_TIMEOUT");
        assertThat(uncertain.live()).as("the operator may hold it").isTrue();
        assertThat(uncertain.open()).isTrue();
        assertThatThrownBy(() -> send(statement))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "EINVOICE_LIVE"));
        assertThat(didox.sent).hasSize(1);

        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-9", EInvoiceOperatorState.SENT, "1"));
        EInvoicingService.Refreshed refreshed = service.refresh(TENANT, uncertain.id(), STAFF, "c");

        StatementEInvoice found = refreshed.einvoice();
        assertThat(refreshed.unavailableCode()).isNull();
        assertThat(found.delivery()).isEqualTo(EInvoiceDelivery.SUBMITTED);
        assertThat(found.operatorDocumentId()).isEqualTo("DOC-9");
        assertThat(found.operatorState()).isEqualTo(EInvoiceOperatorState.SENT);
        assertThat(found.failureCode()).isNull();
        assertThat(didox.asked.getFirst().operatorDocumentId())
                .as("asked by the number and client reference, having no identifier yet")
                .isNull();
        assertThat(didox.asked.getFirst().clientReference())
                .isEqualTo(uncertain.id().toString().replace("-", ""));
        assertThat(didox.asked.getFirst().documentNumber()).isEqualTo("S-2026-09-000001");
        assertThat(didox.sent).as("asking is not sending").hasSize(1);
    }

    @Test
    @DisplayName("an operator that holds nothing under the number is believed only after it has had time to list it")
    void aLostSendNotFoundAtTheOperator() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Uncertain("READ_TIMEOUT", "lost"));
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-2", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice uncertain = send(statement);

        didox.states.add(EInvoiceStateOutcome.NotFound::new);
        clock.advance(Duration.ofMinutes(2));
        StatementEInvoice early =
                service.refresh(TENANT, uncertain.id(), STAFF, "c").einvoice();
        assertThat(early.delivery())
                .as("a list can lag the create that made it")
                .isEqualTo(EInvoiceDelivery.UNCERTAIN);
        assertThat(early.stateCheckedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));

        didox.states.add(EInvoiceStateOutcome.NotFound::new);
        clock.advance(Duration.ofMinutes(4));
        StatementEInvoice settled =
                service.refresh(TENANT, uncertain.id(), STAFF, "c").einvoice();
        assertThat(settled.delivery()).isEqualTo(EInvoiceDelivery.FAILED);
        assertThat(settled.failureCode()).isEqualTo("NOT_FOUND_AT_OPERATOR");
        assertThat(settled.live()).isFalse();
        assertThat(facts.stream().map(AuditFact::actionCode)).contains("commercial.einvoice.send_failed");

        assertThat(send(statement).delivery()).as("free to be sent again").isEqualTo(EInvoiceDelivery.SUBMITTED);
    }

    @Test
    @DisplayName(
            "a lost send is asked about with the documents of the statement's earlier attempts, and never adopts one")
    void aLostSendNeverAdoptsAnEarlierAttemptsDocument() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-A", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice first = send(statement);
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-A", EInvoiceOperatorState.REFUSED, "3"));
        service.refresh(TENANT, first.id(), STAFF, "c");

        // The statement is sent again as B under the same number, and B's answer is lost.
        didox.sends.add(() -> new EInvoiceSendOutcome.Uncertain("READ_TIMEOUT", "lost"));
        StatementEInvoice second = send(statement);
        assertThat(second.delivery()).isEqualTo(EInvoiceDelivery.UNCERTAIN);

        // An adapter that picked the first row of a number-keyed list would answer with A's document.
        didox.asked.clear();
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-A", EInvoiceOperatorState.REFUSED, "3"));
        EInvoicingService.Refreshed refreshed = service.refresh(TENANT, second.id(), STAFF, "c");

        assertThat(didox.asked.getFirst().otherAttemptDocumentIds())
                .as("the lookup is told which documents already belong to other attempts")
                .containsExactly("DOC-A");
        assertThat(refreshed.unavailableCode()).isEqualTo("DOCUMENT_OF_ANOTHER_ATTEMPT");
        StatementEInvoice held = store.find(TENANT, second.id()).orElseThrow();
        assertThat(held.delivery()).isEqualTo(EInvoiceDelivery.UNCERTAIN);
        assertThat(held.operatorDocumentId()).isNull();
        assertThat(held.live())
                .as("still holds the statement; a third send would duplicate B")
                .isTrue();
        assertThatThrownBy(() -> send(statement))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "EINVOICE_LIVE"));

        // The right document, once the operator names it, is adopted.
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-B", EInvoiceOperatorState.DRAFT, "0"));
        StatementEInvoice found =
                service.refresh(TENANT, second.id(), STAFF, "c").einvoice();
        assertThat(found.delivery()).isEqualTo(EInvoiceDelivery.SUBMITTED);
        assertThat(found.operatorDocumentId()).isEqualTo("DOC-B");
    }

    @Test
    @DisplayName("an adapter that throws has not said whether the operator acted: uncertain, with the class name only")
    void anAdapterThatThrowsIsUncertain() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> {
            throw new IllegalStateException("the body was 305000001 and the password");
        });

        StatementEInvoice sent = send(statement);

        assertThat(sent.delivery()).isEqualTo(EInvoiceDelivery.UNCERTAIN);
        assertThat(sent.failureCode()).isEqualTo("ADAPTER_FAILURE");
        assertThat(sent.failureDetail()).isEqualTo("IllegalStateException");
    }

    // ------------------------------------------------------------- the state

    @Test
    @DisplayName(
            "the state is recorded as the operator reports it, moves only when it changes, and a refusal frees the statement")
    void theOperatorsStateIsRecorded() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);
        Instant changedAtSend = sent.stateChangedAt();

        clock.advance(Duration.ofMinutes(20));
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.SENT, "1"));
        StatementEInvoice signedBySeller =
                service.refresh(TENANT, sent.id(), STAFF, "c").einvoice();
        assertThat(signedBySeller.operatorState()).isEqualTo(EInvoiceOperatorState.SENT);
        assertThat(signedBySeller.operatorStatus()).isEqualTo("1");
        assertThat(signedBySeller.stateChangedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
        assertThat(signedBySeller.stateChangedAt()).isAfter(changedAtSend);

        clock.advance(Duration.ofMinutes(20));
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.SENT, "1"));
        StatementEInvoice unchanged =
                service.refresh(TENANT, sent.id(), STAFF, "c").einvoice();
        assertThat(unchanged.stateChangedAt())
                .as("the same answer moves when it was asked, not when it changed")
                .isEqualTo(signedBySeller.stateChangedAt());
        assertThat(unchanged.stateCheckedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(40)));

        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.REFUSED, "3"));
        StatementEInvoice refused =
                service.refresh(TENANT, sent.id(), STAFF, "c").einvoice();
        assertThat(refused.operatorState()).isEqualTo(EInvoiceOperatorState.REFUSED);
        assertThat(refused.live()).as("a refusal releases the statement").isFalse();

        assertThat(facts.stream().map(AuditFact::actionCode).filter("commercial.einvoice.state_changed"::equals))
                .as("recorded when it changed, not each time it was asked")
                .hasSize(2);

        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-2", EInvoiceOperatorState.DRAFT, "created"));
        assertThat(send(statement).operatorDocumentId()).isEqualTo("DOC-2");
    }

    @Test
    @DisplayName("an operator that cannot be asked changes nothing and says why")
    void anUnavailableOperatorChangesNothing() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);

        didox.states.add(() -> new EInvoiceStateOutcome.Unavailable("PROVIDER_UNAVAILABLE", "502"));
        EInvoicingService.Refreshed refreshed = service.refresh(TENANT, sent.id(), STAFF, "c");

        assertThat(refreshed.unavailableCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(refreshed.einvoice().version()).isEqualTo(sent.version());
        assertThat(refreshed.einvoice().operatorState()).isEqualTo(EInvoiceOperatorState.DRAFT);
    }

    @Test
    @DisplayName("an operator that no longer finds a document it named reads as unknown, keeping its identifier")
    void aNamedDocumentThatVanishes() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);

        didox.states.add(EInvoiceStateOutcome.NotFound::new);
        StatementEInvoice gone = service.refresh(TENANT, sent.id(), STAFF, "c").einvoice();

        assertThat(gone.operatorState()).isEqualTo(EInvoiceOperatorState.UNKNOWN);
        assertThat(gone.operatorStatus()).isEqualTo("NOT_FOUND_AT_OPERATOR");
        assertThat(gone.operatorDocumentId()).isEqualTo("DOC-1");
        assertThat(gone.live())
                .as("still holds the statement until somebody can say it is gone")
                .isTrue();
    }

    @Test
    @DisplayName("a settled document is not asked about again, and nothing the operator says moves it")
    void aSettledDocumentIsNotRefreshed() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.SIGNED, "2"));
        StatementEInvoice signed =
                service.refresh(TENANT, sent.id(), STAFF, "c").einvoice();
        assertThat(signed.operatorState()).isEqualTo(EInvoiceOperatorState.SIGNED);
        didox.asked.clear();

        // A status code the adapter does not map reads as UNKNOWN; before the guard one click rewrote the invoice.
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.UNKNOWN, "5"));
        assertThatThrownBy(() -> service.refresh(TENANT, sent.id(), STAFF, "c"))
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
                    assertThat(refusal.properties()).containsEntry("reason", "SETTLED_AT_OPERATOR");
                });

        assertThat(didox.asked).as("a settled document is not even asked about").isEmpty();
        StatementEInvoice unchanged = store.find(TENANT, sent.id()).orElseThrow();
        assertThat(unchanged.operatorState()).isEqualTo(EInvoiceOperatorState.SIGNED);
        assertThat(unchanged.operatorStatus()).isEqualTo("2");
        assertThat(unchanged.version()).isEqualTo(signed.version());
    }

    @Test
    @DisplayName("a refused attempt is not revived into the live index while its statement stands on another")
    void aRefusedAttemptStaysDead() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-A", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice first = send(statement);
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-A", EInvoiceOperatorState.REFUSED, "3"));
        StatementEInvoice refused =
                service.refresh(TENANT, first.id(), STAFF, "c").einvoice();
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-B", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice second = send(statement);

        // The operator no longer lists a refused document: before the guard A became UNKNOWN, hence live,
        // beside B, and the update ran into the live index.
        didox.states.add(EInvoiceStateOutcome.NotFound::new);
        assertThatThrownBy(() -> service.refresh(TENANT, first.id(), STAFF, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "SETTLED_AT_OPERATOR"));

        StatementEInvoice after = store.find(TENANT, first.id()).orElseThrow();
        assertThat(after.operatorState()).isEqualTo(EInvoiceOperatorState.REFUSED);
        assertThat(after.live()).isFalse();
        assertThat(after.version()).isEqualTo(refused.version());
        assertThat(store.find(TENANT, second.id()).orElseThrow().live()).isTrue();
    }

    @Test
    @DisplayName("the database write itself refuses to move a settled document, whoever asks")
    void theStoreDoesNotMoveASettledDocument() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.CANCELLED, "4"));
        StatementEInvoice cancelled =
                service.refresh(TENANT, sent.id(), STAFF, "c").einvoice();

        assertThat(store.recordState(
                        cancelled.id(), cancelled.version(), "DOC-1", EInvoiceOperatorState.UNKNOWN, "5", NOW))
                .isFalse();
        assertThat(store.touchChecked(cancelled.id(), cancelled.version(), NOW)).isFalse();
        assertThat(store.find(TENANT, sent.id()).orElseThrow().operatorState())
                .isEqualTo(EInvoiceOperatorState.CANCELLED);
    }

    @Test
    @DisplayName(
            "a draft deleted at the operator holds its statement until staff release it, and then it can be sent again")
    void aDraftDeletedAtTheOperatorIsReleasedByStaff() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        JdbcStatementStore statements = new JdbcStatementStore(jdbc);
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);

        // Staff delete the draft in the operator's product to fix its classification; it is asked about next.
        didox.states.add(EInvoiceStateOutcome.NotFound::new);
        StatementEInvoice gone = service.refresh(TENANT, sent.id(), STAFF, "c").einvoice();
        assertThat(gone.operatorState()).isEqualTo(EInvoiceOperatorState.UNKNOWN);
        assertThat(gone.operatorStatus()).isEqualTo("NOT_FOUND_AT_OPERATOR");
        assertThat(gone.live()).as("nothing but staff can free it").isTrue();
        assertThat(statements.hasLiveEInvoice(TENANT, statement)).isTrue();
        assertThatThrownBy(() -> send(statement))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "EINVOICE_LIVE"));
        facts.clear();

        StatementEInvoice released = service.release(
                TENANT, gone.id(), gone.version(), STAFF, "draft deleted to correct its classification", "corr-r");

        assertThat(released.operatorState()).isEqualTo(EInvoiceOperatorState.CANCELLED);
        assertThat(released.operatorStatus()).as("a release says it is one").isEqualTo("RELEASED_BY_STAFF");
        assertThat(released.operatorDocumentId())
                .as("the record of what was sent is kept")
                .isEqualTo("DOC-1");
        assertThat(released.live()).isFalse();
        assertThat(released.version()).isEqualTo(gone.version() + 1);
        assertThat(statements.hasLiveEInvoice(TENANT, statement)).isFalse();
        assertThat(facts.stream().map(AuditFact::actionCode)).containsExactly("commercial.einvoice.released");
        AuditFact fact = facts.getFirst();
        assertThat(fact.capabilityUsed()).isEqualTo("commercial.einvoice.send");
        assertThat(fact.reason()).isEqualTo("draft deleted to correct its classification");
        assertThat(fact.changeDocument().toString()).contains("UNKNOWN", "CANCELLED", "RELEASED_BY_STAFF");

        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-2", EInvoiceOperatorState.DRAFT, "created"));
        assertThat(send(statement).delivery()).as("free to be sent again").isEqualTo(EInvoiceDelivery.SUBMITTED);
        assertThatThrownBy(() -> service.refresh(TENANT, released.id(), STAFF, "c"))
                .as("a released document is settled and no answer moves it")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "SETTLED_AT_OPERATOR"));
    }

    @Test
    @DisplayName("only a document the operator says it does not hold is released, at the version staff read")
    void onlyAMissingDocumentIsReleased() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice held = send(statement);
        facts.clear();

        assertThatThrownBy(() -> service.release(TENANT, held.id(), held.version(), STAFF, "r", "c"))
                .as("the operator holds it as a draft: nothing to release")
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);
                    assertThat(refusal.properties()).containsEntry("reason", "NOT_RELEASABLE");
                });

        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.UNKNOWN, "5"));
        StatementEInvoice unreadable =
                service.refresh(TENANT, held.id(), STAFF, "c").einvoice();
        assertThat(unreadable.operatorState()).isEqualTo(EInvoiceOperatorState.UNKNOWN);
        facts.clear();
        assertThatThrownBy(() -> service.release(TENANT, unreadable.id(), unreadable.version(), STAFF, "r", "c"))
                .as("a status the adapter cannot read is a document the operator holds")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "NOT_RELEASABLE"));

        didox.states.add(EInvoiceStateOutcome.NotFound::new);
        StatementEInvoice gone = service.refresh(TENANT, held.id(), STAFF, "c").einvoice();
        assertThat(gone.operatorStatus()).isEqualTo("NOT_FOUND_AT_OPERATOR");
        facts.clear();
        assertThatThrownBy(() -> service.release(TENANT, gone.id(), gone.version() - 1, STAFF, "r", "c"))
                .as("the document moved since staff read it")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        stale -> assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThatThrownBy(() -> service.release(TENANT, UUID.randomUUID(), 0, STAFF, "r", "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        missing -> assertThat(missing.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(facts).as("a refusal leaves no fact").isEmpty();
        assertThat(store.find(TENANT, gone.id()).orElseThrow().version()).isEqualTo(gone.version());

        UUID second = statement("S-2026-10-000002", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Uncertain("READ_TIMEOUT", "lost"));
        StatementEInvoice lost = send(second);
        assertThat(lost.delivery()).isEqualTo(EInvoiceDelivery.UNCERTAIN);
        assertThatThrownBy(() -> service.release(TENANT, lost.id(), lost.version(), STAFF, "r", "c"))
                .as("a send whose answer was lost is resolved by asking, never by a release")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "NOT_RELEASABLE"));
    }

    @Test
    @DisplayName("only an attempt that reached an operator can be asked about, and only through a connected account")
    void whatMayBeRefreshed() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Refused("PROVIDER_REJECTED", "no"));
        StatementEInvoice failed = send(statement);

        assertThatThrownBy(() -> service.refresh(TENANT, failed.id(), STAFF, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "NOTHING_AT_OPERATOR"));
        assertThatThrownBy(() -> service.refresh(OTHER_TENANT, failed.id(), STAFF, "c"))
                .as("another tenant's attempt is not found")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);
        service.suspendInstallation(DIDOX_ID, 2, STAFF, "outage", "c");
        assertThatThrownBy(() -> service.refresh(TENANT, sent.id(), STAFF, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.properties()).containsEntry("reason", "OPERATOR_NOT_CONNECTED"));
    }

    // -------------------------------------------------------------- the sweep

    @Test
    @DisplayName("the sweep marks an attempt that was never concluded as uncertain and asks about documents still open")
    void theSweep() {
        connect(true);
        UUID first = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        UUID second = statement("S-2026-09-000002", "ISSUED", "UZS", plan());
        UUID third = statement("S-2026-09-000003", "ISSUED", "UZS", plan());

        // An attempt recorded and never concluded: the process died before the operator's answer.
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-2", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice open = send(second);
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-3", EInvoiceOperatorState.SENT, "1"));
        StatementEInvoice alreadySigned = send(third);
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-3", EInvoiceOperatorState.SIGNED, "2"));
        service.refresh(TENANT, alreadySigned.id(), STAFF, "c");

        UUID interrupted = UUID.randomUUID();
        String realDocument = jdbc.sql(
                        "SELECT CAST(sent_document AS text) FROM commercial.statement_einvoices" + " WHERE id = :id")
                .param("id", open.id())
                .query(String.class)
                .single();
        jdbc.sql("""
                INSERT INTO commercial.statement_einvoices (
                    id, tenant_id, statement_id, installation_id, provider_type, legal_entity_id, buyer_tin,
                    buyer_name, seller_tin, document_number, document_date, currency, net_minor, vat_minor,
                    total_minor, classification_provisional, sent_document, delivery, send_reason, sent_by,
                    created_at, updated_at, version)
                VALUES (:id, :tenant, :statement, :installation, 'DIDOX', :company, '301234567', 'Non uyi',
                    '305000001', 'S-2026-09-000001', '2026-10-07', 'UZS', 100, 12, 112, true,
                    CAST(:document AS jsonb), 'PENDING', 'r', 'finance.staff', :created, :created, 0)
                """)
                .param("id", interrupted)
                .param("tenant", TENANT)
                .param("statement", first)
                .param("installation", DIDOX_ID)
                .param("company", COMPANY)
                .param("document", realDocument)
                .param("created", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC))
                .update();

        clock.advance(Duration.ofMinutes(30));
        didox.states.clear();
        didox.asked.clear();
        // Asked in this order: the interrupted attempt first (never asked, so first in line), then the open one.
        didox.states.add(EInvoiceStateOutcome.NotFound::new);
        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-2", EInvoiceOperatorState.SIGNED, "2"));
        int moved = service.refreshOpen(50);

        StatementEInvoice concluded = store.find(TENANT, interrupted).orElseThrow();
        assertThat(concluded.delivery())
                .as("marked uncertain by the sweep, then asked about in the same pass, and the operator holds nothing")
                .isEqualTo(EInvoiceDelivery.FAILED);
        assertThat(concluded.failureCode()).isEqualTo("NOT_FOUND_AT_OPERATOR");
        assertThat(store.find(TENANT, open.id()).orElseThrow().operatorState()).isEqualTo(EInvoiceOperatorState.SIGNED);
        assertThat(didox.asked)
                .as("a settled document is not asked about again; the interrupted attempt had no operator document yet")
                .extracting(EInvoiceDocumentReference::documentNumber, EInvoiceDocumentReference::operatorDocumentId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("S-2026-09-000001", null),
                        org.assertj.core.groups.Tuple.tuple("S-2026-09-000002", "DOC-2"));
        assertThat(moved).isEqualTo(3);
        assertThat(facts.stream().map(AuditFact::actionCode))
                .contains("commercial.einvoice.send_uncertain", "commercial.einvoice.send_failed");
        assertThat(facts.stream()
                        .filter(fact -> fact.actionCode().equals("commercial.einvoice.send_uncertain"))
                        .map(fact -> fact.changeDocument().toString()))
                .anySatisfy(change -> assertThat(change).contains("SEND_INTERRUPTED"));
    }

    // ------------------------------------------------------------- the record

    @Test
    @DisplayName("what was sent is never rewritten and the record is never deleted")
    void aSentDocumentIsFrozen() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);

        for (String column : List.of(
                "buyer_name = 'Someone else'",
                "net_minor = 1, total_minor = 1 + vat_minor",
                "sent_document = '{}'::jsonb",
                "document_number = 'X'",
                "sent_by = 'nobody'")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.statement_einvoices SET " + column + " WHERE id = :id")
                            .param("id", sent.id())
                            .update())
                    .as(column)
                    .hasMessageContaining("never rewritten");
        }
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM commercial.statement_einvoices WHERE id = :id")
                        .param("id", sent.id())
                        .update())
                .hasMessageContaining("never deleted");
        assertThat(jdbc.sql("UPDATE commercial.statement_einvoices SET operator_status = '2', version = version + 1"
                                + " WHERE id = :id")
                        .param("id", sent.id())
                        .update())
                .as("the delivery bookkeeping and the operator's state are what moves")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the database holds one live invoice per statement whatever the service does, across both operators")
    void theLiveIndexHoldsAcrossOperators() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        send(statement);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO commercial.statement_einvoices (
                            id, tenant_id, statement_id, installation_id, provider_type, legal_entity_id, buyer_tin,
                            buyer_name, seller_tin, document_number, document_date, currency, net_minor, vat_minor,
                            total_minor, classification_provisional, sent_document, delivery, send_reason, sent_by,
                            created_at, updated_at, version)
                        VALUES (:id, :tenant, :statement, :faktura, 'FAKTURA_UZ', :company, '301234567', 'x',
                            '305000001', 'S-2026-09-000001', '2026-10-07', 'UZS', 100, 12, 112, true,
                            '{}'::jsonb, 'PENDING', 'r', 'someone', now(), now(), 0)
                        """)
                        .param("id", UUID.randomUUID())
                        .param("tenant", TENANT)
                        .param("statement", statement)
                        .param("faktura", FAKTURA_ID)
                        .param("company", COMPANY)
                        .update())
                .hasMessageContaining("ux_einvoice_live");
    }

    @Test
    @DisplayName("an account cannot be active without a secret reference, and an operator has one active account")
    void installationConstraints() {
        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE commercial.einvoicing_installations SET status = 'ACTIVE' WHERE id = :id")
                        .param("id", DIDOX_ID)
                        .update())
                .hasMessageContaining("ck_einvoicing_installation_active");
    }

    @Test
    @DisplayName("a statement with an invoice standing at an operator cannot be voided, and can once it is refused")
    void aLiveInvoiceKeepsItsStatementFromBeingVoided() {
        connect(true);
        UUID statement = statement("S-2026-09-000001", "ISSUED", "UZS", plan());
        JdbcStatementStore statements = new JdbcStatementStore(jdbc);
        didox.sends.add(() -> new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        StatementEInvoice sent = send(statement);

        assertThat(statements.hasLiveEInvoice(TENANT, statement)).isTrue();
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE commercial.statements SET status = 'VOID', voided_by = 'x', voided_at = now(),
                            void_reason = 'wrong' WHERE id = :id
                        """).param("id", statement).update())
                .as("the database refuses it even when the service is bypassed")
                .hasMessageContaining("cancel the invoice there first");

        didox.states.add(() -> new EInvoiceStateOutcome.Known("DOC-1", EInvoiceOperatorState.CANCELLED, "4"));
        service.refresh(TENANT, sent.id(), STAFF, "c");

        assertThat(statements.hasLiveEInvoice(TENANT, statement)).isFalse();
        assertThat(statements.voidStatement(TENANT, statement, "finance.staff", "wrong month", NOW))
                .isTrue();
    }

    // ----------------------------------------------------------------- fixtures

    private StatementEInvoice send(UUID statement) {
        return service.send(
                new EInvoicingService.SendRequest(TENANT, statement, "DIDOX", null, "month closed"), STAFF, "corr-1");
    }

    private static Map<String, String> sellerConfig(boolean vatRegistered) {
        Map<String, String> config = new java.util.LinkedHashMap<>();
        config.put("sellerTaxpayerNumber", "305000001");
        config.put("sellerName", "HorecaOS MCHJ");
        config.put("sellerAddress", "Toshkent, Amir Temur 1");
        if (vatRegistered) {
            config.put("sellerVatRegistrationCode", "326000000001");
        }
        config.put("sellerBankAccount", "20208000100000000001");
        config.put("sellerBankCode", "00014");
        config.put("locale", "ru");
        return config;
    }

    /** Binds and activates the Didox account; leaves the installation at version 2. */
    private void connect(boolean vatRegistered) {
        service.updateInstallation(
                DIDOX_ID, 0, "Didox", REFERENCE, sellerConfig(vatRegistered), STAFF, "account opened", "c");
        service.activateInstallation(DIDOX_ID, 1, STAFF, "live", "c");
        facts.clear();
    }

    private record L(String kind, String description, long quantity, long unitMinor) {}

    private static L plan() {
        return line("PLAN", "BASIC v1, MONTHLY", 1, 50_000_000);
    }

    private static L module() {
        return line("MODULE", "analytics, PER_LOCATION", 3, 15_000_000);
    }

    private static L line(String kind, String description, long quantity, long unitMinor) {
        return new L(kind, description, quantity, unitMinor);
    }

    private UUID statement(String number, String status, String currency, L... lines) {
        UUID id = UUID.randomUUID();
        long total = 0;
        for (L line : lines) {
            total += line.quantity() * line.unitMinor();
        }
        boolean voided = status.equals("VOID");
        jdbc.sql("""
                INSERT INTO commercial.statements (id, tenant_id, number, period_key, period_start, period_end,
                    currency, total_minor, status, issued_by, issued_at, issue_reason, voided_by, voided_at, void_reason)
                VALUES (:id, :tenant, :number, :period, '2026-09-01T00:00:00+05', '2026-10-01T00:00:00+05',
                    :currency, :total, :status, 'finance.staff', :issued, 'month closed',
                    :voidedBy, :voidedAt, :voidReason)
                """)
                .param("id", id)
                .param("tenant", TENANT)
                .param("number", number)
                .param("period", "2025-%02d".formatted(++periods))
                .param("currency", currency)
                .param("total", total)
                .param("status", status)
                .param("issued", java.time.OffsetDateTime.ofInstant(ISSUED, ZoneOffset.UTC))
                .param("voidedBy", voided ? "finance.staff" : null)
                .param("voidedAt", voided ? java.time.OffsetDateTime.ofInstant(ISSUED, ZoneOffset.UTC) : null)
                .param("voidReason", voided ? "wrong" : null)
                .update();
        int number1 = 1;
        for (L line : lines) {
            jdbc.sql("""
                    INSERT INTO commercial.statement_lines (tenant_id, statement_id, line_number, kind, reference_code,
                        description, quantity, unit_price_minor, amount_minor)
                    VALUES (:tenant, :statement, :line, :kind, 'ref', :description, :quantity, :unit, :amount)
                    """)
                    .param("tenant", TENANT)
                    .param("statement", id)
                    .param("line", number1++)
                    .param("kind", line.kind())
                    .param("description", line.description())
                    .param("quantity", line.quantity())
                    .param("unit", line.unitMinor())
                    .param("amount", line.quantity() * line.unitMinor())
                    .update();
        }
        return id;
    }

    private long count(String table) {
        return Objects.requireNonNull(
                jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
    }

    private static LegalParty party(UUID id, String code, String tin, String name) {
        return new LegalParty(id, TENANT, code, name, tin, true, "Toshkent, Navoi 5");
    }

    /** The tenant's companies, as the directory answers them. */
    private static final class Directory implements LegalEntityDirectory {

        List<LegalParty> parties;

        Directory(List<LegalParty> parties) {
            this.parties = parties;
        }

        @Override
        public java.util.Optional<uz.horecaos.platform.tenancy.api.FiscalSeller> sellerFor(
                UUID tenantId, UUID locationId, java.time.LocalDate businessDate) {
            return java.util.Optional.empty();
        }

        @Override
        public List<LegalParty> activeParties(UUID tenantId) {
            return tenantId.equals(TENANT) ? parties : List.of();
        }
    }

    /**
     * An operator that answers what it is told to and records what it was handed -- and, when
     * asked to send, what the database held and whether a transaction was open.
     */
    private final class FakeOperator implements EInvoicingOperator {

        final String type;
        final Deque<Supplier<EInvoiceSendOutcome>> sends = new ArrayDeque<>();
        final Deque<Supplier<EInvoiceStateOutcome>> states = new ArrayDeque<>();
        final List<EInvoiceDocument> sent = new ArrayList<>();
        final List<EInvoiceDocumentReference> asked = new ArrayList<>();

        @Nullable
        String deliveryAtCall;

        boolean transactionActiveAtCall = true;

        FakeOperator(String type) {
            this.type = type;
        }

        @Override
        public String providerType() {
            return type;
        }

        @Override
        public String adapterVersion() {
            return "fake-v1";
        }

        @Override
        public EInvoiceSendOutcome send(EInvoiceOperatorAccount account, EInvoiceDocument document) {
            transactionActiveAtCall = TransactionSynchronizationManager.isActualTransactionActive();
            deliveryAtCall = jdbc.sql("""
                            SELECT delivery FROM commercial.statement_einvoices
                             WHERE document_number = :number ORDER BY created_at DESC LIMIT 1
                            """)
                    .param("number", document.documentNumber())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            sent.add(document);
            assertThat(account.secretReference()).isEqualTo(REFERENCE);
            assertThat(account.baseUrl()).startsWith("https://");
            return Objects.requireNonNull(sends.pop()).get();
        }

        @Override
        public EInvoiceStateOutcome state(EInvoiceOperatorAccount account, EInvoiceDocumentReference reference) {
            asked.add(reference);
            return Objects.requireNonNull(states.pop()).get();
        }
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
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
}
