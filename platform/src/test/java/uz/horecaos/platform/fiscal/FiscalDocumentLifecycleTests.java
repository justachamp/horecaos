package uz.horecaos.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.fiscal.application.FiscalDocumentService;
import uz.horecaos.platform.fiscal.application.FiscalReportingPolicyService;
import uz.horecaos.platform.fiscal.domain.FiscalCoverage;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;
import uz.horecaos.platform.fiscal.domain.FiscalReasonCode;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.FiscalDocumentRow;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReason;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;

/**
 * ADR 0038's fiscal document lifecycle, against PostgreSQL.
 *
 * <p>Every case here is one of the four things the ADR says an implementer gets
 * wrong. Several documents per order rather than a unique index on {@code order_id};
 * a sweeper that turns silence into visible work; a non-zero {@code status_code}
 * that is evidence of no receipt rather than of one; and cash recorded as
 * {@code NOT_APPLICABLE} with a reason so that a reversal is a query.
 *
 * <p>The payments module's own store is used to write the provider-side outcomes,
 * deliberately. It writes through the compatibility view V0039 left behind, so
 * these tests are also the check that the view is genuinely updatable and that a
 * late callback still resolves a document this module blocked.
 */
class FiscalDocumentLifecycleTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000000f1");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-0000000000f2");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-2000-7000-8000-0000000000f3");
    private static final UUID CUSTOMER = UUID.fromString("018f6f4e-2000-7000-8000-0000000000f4");
    private static final UUID ENTITY = UUID.fromString("018f6f4e-2000-7000-8000-0000000000f5");

    /** 14:00 in Tashkent on 22 August 2026, comfortably inside a business date. */
    private static final Instant NOON = Instant.parse("2026-08-22T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcFiscalLifecycleStore store;
    private JdbcFiscalDocumentStore paymentsStore;
    private FiscalDocumentService service;
    private RecordingAudit audit;
    private StubPartner partner;
    private UUID channelId;
    private UUID publicationId;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("DELETE FROM fiscal.fiscal_documents").update();
        jdbc.sql("DELETE FROM tenant.policy_current WHERE key_code = :k")
                .param("k", FiscalReportingPolicyService.REPORTING_DEADLINE.code())
                .update();
        jdbc.sql("DELETE FROM tenant.policies WHERE key_code = :k")
                .param("k", FiscalReportingPolicyService.REPORTING_DEADLINE.code())
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();

        store = new JdbcFiscalLifecycleStore(jdbc);
        paymentsStore = new JdbcFiscalDocumentStore(jdbc);
        audit = new RecordingAudit();
        partner = new StubPartner();
        service = build(NOON.plusSeconds(3600));
    }

    private FiscalDocumentService build(Instant now) {
        return new FiscalDocumentService(
                store,
                new FiscalReportingPolicyService(
                        new JdbcPolicyResolver(jdbc, JsonMapper.builder().build())),
                partner,
                audit,
                Clock.fixed(now, ZoneOffset.UTC),
                "Asia/Tashkent",
                java.time.Duration.ofMinutes(1));
    }

    // ------------------------------------------------------------- the sweep

    @Test
    @DisplayName("a document nobody answered is blocked with a reason an operator can act on")
    void anOverdueSubmittedDocumentBecomesBlockedWork() {
        UUID order = seedOrder("S-1", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);

        // One second past the sixty-minute default.
        int blocked = build(NOON.plusSeconds(3601)).sweepOverdueReports(50);

        assertThat(blocked).isEqualTo(1);
        FiscalDocumentRow row = row(document);
        assertThat(row.state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(row.reasonCode()).isEqualTo(FiscalReasonCode.PROVIDER_REPORT_OVERDUE);
        assertThat(row.blockedAt())
                .as("a worklist ordered by when the order was placed answers the wrong question")
                .isNotNull();
        assertThat(row.reportingDeadlineAt())
                .as("the deadline actually applied is recorded, or a false positive is unarguable")
                .isEqualTo(NOON.plusSeconds(3600));
    }

    @Test
    @DisplayName("a document still inside its deadline is left alone")
    void aDocumentInsideItsDeadlineIsUntouched() {
        UUID order = seedOrder("S-2", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);

        int blocked = build(NOON.plusSeconds(3599)).sweepOverdueReports(50);

        assertThat(blocked).isZero();
        assertThat(row(document).state()).isEqualTo(FiscalDocumentState.SUBMITTED);
    }

    @Test
    @DisplayName("the sweep is idempotent: running it again changes nothing")
    void theSweepIsReEntrant() {
        UUID order = seedOrder("S-3", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);

        FiscalDocumentService sweeper = build(NOON.plusSeconds(7200));
        assertThat(sweeper.sweepOverdueReports(50)).isEqualTo(1);
        FiscalDocumentRow first = row(document);

        assertThat(sweeper.sweepOverdueReports(50))
                .as("a second pass must not re-block, or every version and every blocked_at "
                        + "moves on every sweep and the worklist ordering becomes meaningless")
                .isZero();
        assertThat(row(document)).isEqualTo(first);
    }

    @Test
    @DisplayName("the business date is an absolute backstop on a generous policy")
    void aLongPolicyStillBlocksAtTheEndOfTheBusinessDate() {
        setReportingPolicy(12 * 60);

        UUID order = seedOrder("S-4", "PAYME");
        // 23:30 Tashkent. A twelve-hour policy would run to 11:30 the next morning.
        UUID document = submittedDocument(order, "PAYME", Instant.parse("2026-08-22T18:30:00Z"));

        // 00:05 Tashkent, five minutes into the next business date.
        int blocked = build(Instant.parse("2026-08-22T19:05:00Z")).sweepOverdueReports(50);

        assertThat(blocked)
                .as("a tax obligation belongs to a business date; asking a provider next "
                        + "morning what happened last night is a different conversation")
                .isEqualTo(1);
        assertThat(row(document).reasonNote()).contains("business date");
    }

    @Test
    @DisplayName("a cash document is never swept and never blocked")
    void aCashDocumentIsNeverSwept() {
        UUID order = seedOrder("S-5", null);
        UUID document = cashDocument(order);

        assertThat(build(NOON.plusSeconds(86_400)).sweepOverdueReports(50)).isZero();

        FiscalDocumentRow row = row(document);
        assertThat(row.state()).isEqualTo(FiscalDocumentState.NOT_APPLICABLE);
        assertThat(row.reasonCode()).isEqualTo(FiscalReasonCode.CASH_TENDER_NO_PROVIDER_FISCALIZATION);
        assertThat(row.blockedAt()).isNull();
        assertThat(row.responsibility())
                .as("a cash leg still owes a receipt, from the restaurant's own equipment")
                .isEqualTo("TERMINAL");
    }

    // --------------------------------------------------- the late callback

    @Test
    @DisplayName("a callback arriving after the block clears it")
    void aLateCallbackResolvesABlockedDocument() {
        UUID order = seedOrder("L-1", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);
        build(NOON.plusSeconds(3601)).sweepOverdueReports(50);
        assertThat(row(document).state()).isEqualTo(FiscalDocumentState.BLOCKED);

        // Exactly what PaymeMerchantApi does on a SetFiscalData with status_code 0.
        boolean written = paymentsStore.recordEvidence(
                TENANT,
                document,
                FiscalStatus.ISSUED,
                FiscalReason.PARTNER_FISCALIZED,
                new FiscalDocument.FiscalEvidence(
                        "receipt-1",
                        "sign-1",
                        "terminal-1",
                        null,
                        NOON.plusSeconds(4200),
                        "https://ofd.soliq.uz/epi?t=1",
                        "0",
                        null),
                null,
                NOON.plusSeconds(4200));

        assertThat(written)
                .as("the sweeper marks a document as needing a human, not as finished")
                .isTrue();
        assertThat(row(document).state()).isEqualTo(FiscalDocumentState.ISSUED);
        assertThat(row(document).blockedAt())
                .as("how long the provider took is the evidence that decides whether the "
                        + "deadline is set correctly, so clearing the block does not erase it")
                .isNotNull();
    }

    @Test
    @DisplayName("a non-zero status_code is evidence of no receipt, and never of one")
    void aNonZeroStatusCodeLeavesTheDocumentFailed() {
        UUID order = seedOrder("L-2", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);
        build(NOON.plusSeconds(3601)).sweepOverdueReports(50);

        // A SetFiscalData that arrives and reports an OFD registration failure. The
        // defect this guards against passes every test written against the
        // happy-path example in Payme's own documentation.
        paymentsStore.recordEvidence(
                TENANT,
                document,
                FiscalStatus.FAILED,
                FiscalReason.PROVIDER_REJECTED,
                new FiscalDocument.FiscalEvidence(null, null, null, null, null, null, "3", "ОФД не принял чек"),
                null,
                NOON.plusSeconds(4200));

        FiscalDocumentRow row = row(document);
        assertThat(row.state()).isEqualTo(FiscalDocumentState.FAILED);
        assertThat(row.hasEvidence())
                .as("a status code is not a receipt: neither identifier the tax authority "
                        + "recognises is on this row")
                .isFalse();
    }

    // -------------------------------------------- several documents per order

    @Test
    @DisplayName("a Payme PERFORM and its CANCEL are two rows, and the sale keeps its evidence")
    void aCancellationIsASecondDocumentAndNotAnOverwrite() {
        UUID order = seedOrder("M-1", "PAYME");
        UUID sale = submittedDocument(order, "PAYME", NOON);
        paymentsStore.recordEvidence(
                TENANT,
                sale,
                FiscalStatus.ISSUED,
                FiscalReason.PARTNER_FISCALIZED,
                new FiscalDocument.FiscalEvidence("receipt-1", "sign-1", null, null, NOON, null, "0", null),
                null,
                NOON);

        UUID refund = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id,
                    payment_intent_id, provider_type, document_type, corrects_document_id,
                    status, reason_code, reason_note, created_at, updated_at)
                VALUES (:id, :t, :o, :e, :i, 'PAYME', 'REFUND', :corrects, 'SUBMITTED',
                    'AWAITING_PROVIDER', 'Payme reported a cancellation receipt', :at, :at)
                """)
                .param("id", refund)
                .param("t", TENANT)
                .param("o", order)
                .param("e", ENTITY)
                .param("i", intentId(order))
                .param("corrects", sale)
                .param("at", NOON.plusSeconds(600).atOffset(ZoneOffset.UTC))
                .update();

        List<FiscalDocumentRow> documents = service.forOrder(TENANT, order);

        assertThat(documents).hasSize(2);
        assertThat(row(sale).state())
                .as("writing the cancel over the perform destroys the only record that the "
                        + "sale was ever fiscalized")
                .isEqualTo(FiscalDocumentState.ISSUED);
        assertThat(row(sale).hasEvidence()).isTrue();
        assertThat(row(refund).correctsDocumentId()).isEqualTo(sale);
    }

    @Test
    @DisplayName("a second sale document for the same settlement leg is refused by the database")
    void oneSalePerSettledTender() {
        UUID order = seedOrder("M-2", "PAYME");
        submittedDocument(order, "PAYME", NOON);

        assertThatThrownBy(() -> submittedDocument(order, "PAYME", NOON.plusSeconds(60)))
                .as("two sale receipts for one payment can only be corrected with the tax "
                        + "authority, never deleted")
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---------------------------------------------------------- the coverage

    @Test
    @DisplayName("coverage reports the cash share beside the issued one, never folded into it")
    void anUnreceiptedMajorityIsNotPresentedAsFine() {
        UUID issuedOrder = seedOrder("C-1", "PAYME");
        UUID issued = submittedDocument(issuedOrder, "PAYME", NOON);
        paymentsStore.recordEvidence(
                TENANT,
                issued,
                FiscalStatus.ISSUED,
                FiscalReason.PARTNER_FISCALIZED,
                new FiscalDocument.FiscalEvidence("r", "s", null, null, NOON, null, "0", null),
                null,
                NOON);

        cashDocument(seedOrder("C-2", null));
        cashDocument(seedOrder("C-3", null));

        UUID blockedOrder = seedOrder("C-4", "PAYME");
        submittedDocument(blockedOrder, "PAYME", NOON);
        build(NOON.plusSeconds(3601)).sweepOverdueReports(50);

        FiscalCoverage coverage = service.coverage(TENANT, NOON.minusSeconds(3600), NOON.plusSeconds(86_400));

        assertThat(coverage.saleDocuments()).isEqualTo(4);
        assertThat(coverage.issued()).isEqualTo(1);
        assertThat(coverage.notApplicableCash()).isEqualTo(2);
        assertThat(coverage.blocked()).isEqualTo(1);
        assertThat(coverage.unreceipted())
                .as("cash is not counted as unreceipted: it owes a receipt from the "
                        + "restaurant's equipment, which is a different problem with a "
                        + "different owner")
                .isEqualTo(1);
        assertThat(coverage.issuedBasisPoints()).isEqualTo(2_500);
        assertThat(coverage.notApplicableBasisPoints()).isEqualTo(5_000);
        assertThat(coverage.providerPathIsMinority())
                .as("ADR 0038 predicts this stays true for the whole pilot, and a prediction "
                        + "nobody checks is a prediction nobody notices coming true")
                .isTrue();
        assertThat(coverage.partnerFiscalizationWired()).isFalse();
    }

    // ---------------------------------------------------- operator commands

    @Test
    @DisplayName("an unwired provider adapter reports itself rather than reporting success")
    void aRetryWithNoAdapterWiredSaysSo() {
        UUID order = seedOrder("R-1", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);
        build(NOON.plusSeconds(3601)).sweepOverdueReports(50);

        FiscalDocumentRow before = row(document);
        var result = service.retry(
                TENANT,
                document,
                before.version(),
                "idem-1",
                ActorRef.user("finance@example.test", "Finance"),
                "tax inspection",
                null);

        assertThat(result.outcome()).isEqualTo(PartnerFiscalizationPort.Outcome.NOT_WIRED);
        assertThat(row(document).attemptCount()).isEqualTo(1);
        assertThat(row(document).state())
                .as("a retry that reached nothing must not move the document")
                .isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(audit.facts).singleElement().satisfies(fact -> {
            assertThat(fact.actionCode()).isEqualTo("fiscal.document.retry");
            assertThat(fact.outcome()).isEqualTo(AuditFact.Outcome.FAILED);
            assertThat(fact.reason()).isEqualTo("tax inspection");
        });
    }

    @Test
    @DisplayName("two operators retrying the same document reach the provider once")
    void concurrentRetriesAskOnce() {
        UUID order = seedOrder("R-5", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);
        build(NOON.plusSeconds(3601)).sweepOverdueReports(50);

        int version = row(document).version();
        ActorRef operator = ActorRef.user("finance@example.test", "Finance");

        service.retry(TENANT, document, version, "idem-a", operator, "first", null);

        assertThatThrownBy(() -> service.retry(
                        TENANT, document, version, "idem-b", operator, "second, from the other screen", null))
                .as("both operators read the same version; the loser must send nothing, or "
                        + "one payment acquires two sale receipts")
                .isInstanceOf(FiscalDocumentService.StaleDocumentException.class);

        assertThat(partner.asks).hasSize(1);
        assertThat(row(document).attemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an issued document is never asked for again")
    void anIssuedDocumentIsNotRetryable() {
        UUID order = seedOrder("R-2", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);
        paymentsStore.recordEvidence(
                TENANT,
                document,
                FiscalStatus.ISSUED,
                FiscalReason.PARTNER_FISCALIZED,
                new FiscalDocument.FiscalEvidence("r", "s", null, null, NOON, null, "0", null),
                null,
                NOON);

        assertThatThrownBy(() -> service.retry(
                        TENANT,
                        document,
                        row(document).version(),
                        "idem-2",
                        ActorRef.user("finance@example.test", "Finance"),
                        "curiosity",
                        null))
                .isInstanceOf(FiscalDocumentService.NotRetryableException.class);
        assertThat(partner.asks).isEmpty();
    }

    @Test
    @DisplayName("unblocking returns the document to the queue and clears its expired deadline")
    void unblockingReopensTheDocument() {
        UUID order = seedOrder("R-3", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);
        build(NOON.plusSeconds(3601)).sweepOverdueReports(50);

        service.reopen(
                TENANT,
                document,
                row(document).version(),
                ActorRef.user("finance@example.test", "Finance"),
                "cashbox reconnected",
                null);

        FiscalDocumentRow row = row(document);
        assertThat(row.state()).isEqualTo(FiscalDocumentState.PENDING);
        assertThat(row.reportingDeadlineAt())
                .as("keeping an expired deadline would block the document again the instant " + "it is submitted")
                .isNull();
        assertThat(row.blockedAt()).isNotNull();
        assertThat(audit.facts)
                .singleElement()
                .satisfies(fact -> assertThat(fact.actionCode()).isEqualTo("fiscal.document.unblock"));
    }

    @Test
    @DisplayName("a command against a version somebody else has moved is refused")
    void aStaleCommandIsRefused() {
        UUID order = seedOrder("R-4", "PAYME");
        UUID document = submittedDocument(order, "PAYME", NOON);
        build(NOON.plusSeconds(3601)).sweepOverdueReports(50);

        assertThatThrownBy(() -> service.reopen(
                        TENANT, document, 1, ActorRef.user("finance@example.test", "Finance"), "stale", null))
                .isInstanceOf(FiscalDocumentService.StaleDocumentException.class);
    }

    @Test
    @DisplayName("the blocked worklist is scoped to its tenant and ordered by waiting time")
    void theWorklistIsTenantScopedAndOldestFirst() {
        UUID early = seedOrder("W-1", "PAYME");
        UUID late = seedOrder("W-2", "PAYME");
        submittedDocument(early, "PAYME", NOON);
        submittedDocument(late, "PAYME", NOON.plusSeconds(600));

        build(NOON.plusSeconds(7200)).sweepOverdueReports(50);

        List<FiscalDocumentRow> worklist = service.blocked(TENANT, null, 50);
        assertThat(worklist).hasSize(2);
        assertThat(worklist.get(0).orderId()).isEqualTo(early);

        assertThat(service.blocked(UUID.randomUUID(), null, 50))
                .as("every read made on behalf of a person carries the tenant predicate")
                .isEmpty();
    }

    // -------------------------------------------------------------- fixtures

    private FiscalDocumentRow row(UUID documentId) {
        return store.find(TENANT, documentId).orElseThrow();
    }

    private void setReportingPolicy(int minutes) {
        UUID policyId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.policies (id, key_code, scope_type, tenant_id, version, status,
                    document, document_hash, valid_from, created_by)
                VALUES (:id, :key, 'TENANT', :t, 1, 'ACTIVE', CAST(:doc AS jsonb), :hash,
                    :from, 'test')
                """)
                .param("id", policyId)
                .param("key", FiscalReportingPolicyService.REPORTING_DEADLINE.code())
                .param("t", TENANT)
                .param("doc", "{\"deadlineMinutes\": %d}".formatted(minutes))
                .param("hash", "a".repeat(64))
                .param("from", NOON.minusSeconds(86_400).atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.policy_current (key_code, scope_type, tenant_id, policy_id,
                    policy_version, activated_by)
                VALUES (:key, 'TENANT', :t, :id, 1, 'test')
                """)
                .param("key", FiscalReportingPolicyService.REPORTING_DEADLINE.code())
                .param("t", TENANT)
                .param("id", policyId)
                .update();
    }

    /** A document in the state the Payme path leaves behind: asked, and no answer. */
    private UUID submittedDocument(UUID orderId, String providerType, Instant submittedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, legal_entity_id,
                    payment_intent_id, provider_type, document_type, status, reason_code,
                    reason_note, submitted_at, created_at, updated_at)
                VALUES (:id, :t, :o, :e, :i, :provider, 'SALE', 'SUBMITTED',
                    'AWAITING_PROVIDER', 'awaiting the provider''s report', :at, :at, :at)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("o", orderId)
                .param("e", ENTITY)
                .param("i", intentId(orderId))
                .param("provider", providerType)
                .param("at", submittedAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    /** The cash answer, written exactly as the payments module writes it. */
    private UUID cashDocument(UUID orderId) {
        FiscalDocument document = FiscalDocument.notApplicableForCash(
                UUID.randomUUID(), TENANT, orderId, ENTITY, intentId(orderId), NOON);
        paymentsStore.insert(document);
        return document.id();
    }

    private UUID intentId(UUID orderId) {
        return jdbc.sql("SELECT id FROM payments.payment_intents WHERE tenant_id = :t " + "AND order_id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(UUID.class)
                .single();
    }

    private UUID seedOrder(String seed, String providerType) {
        UUID orderId = UUID.nameUUIDFromBytes(("order:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID cartId = UUID.nameUUIDFromBytes(("cart:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID quoteId = UUID.nameUUIDFromBytes(("quote:" + seed).getBytes(StandardCharsets.UTF_8));

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at,
                    converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'CONVERTED', :at, :orderId)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("at", NOON.atOffset(ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id,
                    customer_account_id, currency, status, catalog_publication_id,
                    calculation_version, context_hash, subtotal_minor, tax_minor, fee_minor,
                    discount_minor, total_minor, expires_at, accepted_at)
                VALUES (:id, :t, :b, :loc, :cust, 'UZS', 'ACCEPTED', :pub, 1, :hash,
                    50000, 0, 0, 0, 50000, :at, :at)
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("cust", CUSTOMER)
                .param("pub", publicationId)
                .param("hash", "hash-" + seed)
                .param("at", NOON.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, discount_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, promise_basis, version, created_at, confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'TELEGRAM', :cust, 'DELIVERY',
                    'AUTO_CONFIRM', 'NONE', 'CONFIRMED', 'UZS', 50000, 0, 0, 0, 50000,
                    :quote, :hash, :pub, :cart, :key, 'NOT_PROMISED', 1, :at, :at)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("at", NOON.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id,
                    location_id, legal_entity_id, tender, payment_method_code, provider_type,
                    requested_amount_minor, currency, status, capture_timing, idempotency_key,
                    version, created_at, updated_at)
                VALUES (:id, :t, :o, :b, :loc, :e, :tender, :code, :provider, 50000, 'UZS',
                    'PENDING', :timing, :key, 1, :at, :at)
                """)
                .param("id", UUID.nameUUIDFromBytes(("intent:" + seed).getBytes(StandardCharsets.UTF_8)))
                .param("t", TENANT)
                .param("o", orderId)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("e", ENTITY)
                .param("tender", providerType == null ? "CASH" : "PROVIDER")
                .param("code", providerType == null ? "CASH" : providerType)
                .param("provider", providerType)
                .param("timing", providerType == null ? "ON_HANDOVER" : "BEFORE_CONFIRMATION")
                .param("key", "intent-" + seed)
                .param("at", NOON.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'fiscal-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();

        channelId = UUID.nameUUIDFromBytes("fiscal-channel".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.nameUUIDFromBytes("fiscal-catalog".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.nameUUIDFromBytes("fiscal-publication".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'TELEGRAM', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
        // V0053 made fiscal_documents.legal_entity_id a real foreign key. A receipt
        // has to name the entity it was issued under, and until that migration the
        // column could name one that did not exist -- which is the same gap that
        // left the platform with no legal identity to fiscalise against at all.
        // Seeded here with the rest of the tenancy rather than beside each
        // document, because it is a fact about the tenant and not about a receipt.
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'FISCAL-LE', 'Fiskal MCHJ', '123456789', 'ACTIVE')
                ON CONFLICT DO NOTHING
                """).param("id", ENTITY).param("t", TENANT).update();
    }

    /** Records what it was asked, and reports that nothing was sent. */
    private static final class StubPartner implements PartnerFiscalizationPort {

        private final List<UUID> asks = new ArrayList<>();

        @Override
        public Outcome retry(UUID tenantId, UUID documentId, String idempotencyKey) {
            asks.add(documentId);
            return Outcome.NOT_WIRED;
        }

        @Override
        public boolean isWired() {
            return false;
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
