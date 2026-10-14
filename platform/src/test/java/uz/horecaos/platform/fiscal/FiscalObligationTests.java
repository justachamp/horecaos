package uz.horecaos.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort;
import uz.horecaos.platform.fiscal.application.FiscalDocumentService;
import uz.horecaos.platform.fiscal.application.FiscalObligationService;
import uz.horecaos.platform.fiscal.application.FiscalObligationService.ClaimedSubmission;
import uz.horecaos.platform.fiscal.application.FiscalObligationSweeper;
import uz.horecaos.platform.fiscal.application.FiscalReportingPolicyService;
import uz.horecaos.platform.fiscal.application.MarksCompletedReopener;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;
import uz.horecaos.platform.fiscal.domain.FiscalReasonCode;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.FiscalDocumentRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.NewFiscalDocument;
import uz.horecaos.platform.ordering.api.OrderMarksCompleted;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcLegalEntityStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;

/**
 * The caller ADR 0038's rollout stage 4 was missing: a finished order acquires
 * the receipt it owes, under the company that sold it, exactly once.
 *
 * <p>Two failures are money-and-law rather than bugs, and every case here is one
 * of them. A receipt issued twice for one order cannot be withdrawn from a tax
 * authority, only corrected. A receipt issued under the wrong company names the
 * wrong taxpayer to a customer and to the state. So the assertions are mostly
 * about what does <em>not</em> happen: a second document, a submission that goes
 * out under a company nobody resolved, a claim that looks sent when nothing was.
 */
class FiscalObligationTests {

    private static final UUID TENANT = UUID.fromString("018f7b20-2000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018f7b20-2000-7000-8000-0000000000d2");
    private static final UUID LOCATION = UUID.fromString("018f7b20-2000-7000-8000-0000000000d3");
    private static final UUID CUSTOMER = UUID.fromString("018f7b20-2000-7000-8000-0000000000d4");

    /** 14:00 in Tashkent on 24 August 2026. */
    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcFiscalLifecycleStore store;
    private JdbcLegalEntityStore entities;
    private FiscalObligationService obligations;
    private FiscalObligationSweeper sweeper;
    private StubPartner partner;
    private UUID channelId;
    private UUID publicationId;
    private UUID firstCompany;
    private UUID secondCompany;

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
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.order_settlements, payments.payment_methods CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenancy();

        store = new JdbcFiscalLifecycleStore(jdbc);
        entities = new JdbcLegalEntityStore(jdbc);
        partner = new StubPartner();
        obligations = build(NOW);
        sweeper = new FiscalObligationSweeper(obligations, partner, 50);
    }

    /**
     * A year of lookback, so that the cases about <em>which</em> company sold an
     * order can use dates far enough apart to be unambiguous. The floor itself has
     * its own case below.
     */
    private FiscalObligationService build(Instant now) {
        return build(now, Duration.ofDays(365));
    }

    private FiscalObligationService build(Instant now, Duration lookback) {
        return new FiscalObligationService(
                store,
                entities,
                new FiscalReportingPolicyService(
                        new JdbcPolicyResolver(jdbc, JsonMapper.builder().build())),
                Clock.fixed(now, ZoneOffset.UTC),
                "Asia/Tashkent",
                lookback);
    }

    // ------------------------------------------------- opening the obligation

    @Test
    @DisplayName("a completed provider order acquires its obligation under the resolved company")
    void aCompletedOrderAcquiresItsObligation() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-1", "PAYME", "PAID", NOW.minusSeconds(3600));

        assertThat(obligations.openObligations(50)).isEqualTo(1);

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state()).isEqualTo(FiscalDocumentState.PENDING);
        assertThat(document.reasonCode()).isEqualTo(FiscalReasonCode.AWAITING_CAPTURE);
        assertThat(document.legalEntityId())
                .as("a receipt has to name a seller, and this is where it is fixed")
                .isEqualTo(firstCompany);
        assertThat(document.providerType()).isEqualTo("PAYME");
        assertThat(document.documentType()).isEqualTo("SALE");
    }

    @Test
    @DisplayName("running the opener twice leaves one document, not two")
    void theOpenerIsIdempotent() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-2", "CLICK", "PAID", NOW.minusSeconds(3600));

        assertThat(obligations.openObligations(50)).isEqualTo(1);
        assertThat(obligations.openObligations(50))
                .as("an order that keeps reappearing is the same defect as a pass that never runs")
                .isZero();
        assertThat(store.forOrder(TENANT, order)).hasSize(1);
    }

    @Test
    @DisplayName("two nodes opening the same obligation produce one document and the loser knows")
    void aSecondSaleDocumentForOneLegIsRefusedByTheDatabase() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-3", "CLICK", "PAID", NOW.minusSeconds(3600));

        assertThat(store.open(newDocument(order, FiscalDocumentState.PENDING, firstCompany)))
                .isTrue();
        assertThat(store.open(newDocument(order, FiscalDocumentState.PENDING, firstCompany)))
                .as("ON CONFLICT DO NOTHING against the NULLS NOT DISTINCT index: two sale "
                        + "receipts for one payment can only be corrected, never deleted")
                .isFalse();
        assertThat(store.forOrder(TENANT, order)).hasSize(1);
    }

    @Test
    @DisplayName("a branch with no company assigned is blocked, never opened under a default")
    void anUnassignedBranchBlocksRatherThanGuesses() {
        UUID order = completedOrder("O-4", "PAYME", "PAID", NOW.minusSeconds(3600));

        assertThat(obligations.openObligations(50)).isEqualTo(1);

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(document.reasonCode()).isEqualTo(FiscalReasonCode.NO_FISCAL_PATH);
        assertThat(document.legalEntityId())
                .as("an unissued receipt is work an operator can do; a receipt naming the wrong "
                        + "taxpayer is one nobody notices")
                .isNull();
        assertThat(document.blockedAt()).isNotNull();
    }

    @Test
    @DisplayName("a suspended company blocks its branch rather than falling through to another")
    void aSuspendedCompanyBlocksItsBranch() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        jdbc.sql("UPDATE tenant.legal_entities SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", firstCompany)
                .update();

        UUID order = completedOrder("O-5", "PAYME", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(document.reasonNote()).contains("not active");
    }

    @Test
    @DisplayName("the company is the one in force on the order's business date, not on today's")
    void aBranchThatChangedHandsDoesNotRestateWhoSoldTheOldOrder() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        assign(secondCompany, LocalDate.of(2026, 8, 20));

        // Closed on 4 July, well before the handover.
        UUID order = completedOrder("O-6", "CLICK", "PAID", Instant.parse("2026-07-04T10:00:00Z"));

        obligations.openObligations(50);

        assertThat(onlyDocument(order).legalEntityId())
                .as("nothing downstream re-resolves; a re-registration must not rewrite what a "
                        + "delivered order's receipt said")
                .isEqualTo(firstCompany);
    }

    @Test
    @DisplayName("an order closed after midnight Tashkent belongs to the branch's day, not UTC's")
    void theBusinessDateIsTheBranchesOwnDay() {
        // The handover is on 20 August. This order closed at 00:30 on 20 August in
        // Tashkent, which is still 19:30 on the 19th in UTC.
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        assign(secondCompany, LocalDate.of(2026, 8, 20));

        UUID order = completedOrder("O-7", "CLICK", "PAID", Instant.parse("2026-08-19T19:30:00Z"));

        obligations.openObligations(50);

        assertThat(onlyDocument(order).legalEntityId())
                .as("a UTC day rolls over at 05:00 in Tashkent, in the middle of a night service")
                .isEqualTo(secondCompany);
    }

    @Test
    @DisplayName("the opener keeps the tender the document covers and the business date the seller was resolved on")
    void theOpenerSnapshotsTheTenderAndTheBusinessDate() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        // 00:30 on 20 August in Tashkent is 19:30 on the 19th in UTC: the branch's day is the 20th.
        UUID order = completedOrder("O-tender", "CLICK", "PAID", Instant.parse("2026-08-19T19:30:00Z"));
        UUID tender = settleWithAMoneyTender(order);

        obligations.openObligations(50);

        var kept = jdbc.sql("SELECT tender_id, business_date FROM fiscal.fiscal_documents WHERE order_id = :o")
                .param("o", order)
                .query()
                .singleRow();
        assertThat(kept.get("tender_id"))
                .as("the money tender whose intent the document names, written and not inferred")
                .isEqualTo(tender);
        assertThat(java.util.Objects.requireNonNull(kept.get("business_date")).toString())
                .as("the branch's own calendar day, kept as it was resolved")
                .isEqualTo("2026-08-20");
    }

    @Test
    @DisplayName("an order with no tender row opens a document with no tender, and the date is still kept")
    void aDocumentWithNoUnambiguousTenderKeepsNone() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-no-tender", "CLICK", "PAID", NOW.minusSeconds(3600));

        obligations.openObligations(50);

        var kept = jdbc.sql("SELECT tender_id, business_date FROM fiscal.fiscal_documents WHERE order_id = :o")
                .param("o", order)
                .query()
                .singleRow();
        assertThat(kept.get("tender_id")).isNull();
        assertThat(kept.get("business_date")).isNotNull();
    }

    @Test
    @DisplayName("an order that completed with no payment record at all is blocked, not skipped")
    void anOrderWithNoPaymentRecordStillGetsAStatus() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-8", null, null, NOW.minusSeconds(3600));

        obligations.openObligations(50);

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state())
                .as("no accepted order may reach its end with no fiscal status at all")
                .isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(document.reasonCode()).isEqualTo(FiscalReasonCode.NO_FISCAL_PATH);
        assertThat(document.reasonNote()).contains("no payment record");
    }

    @Test
    @DisplayName("a cash order that already recorded its decision is left exactly alone")
    void aCashOrderIsNotReopened() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-9", null, null, NOW.minusSeconds(3600));
        cashIntent(order);
        UUID existing = cashDecision(order);

        assertThat(obligations.openObligations(50))
                .as("the cash NOT_APPLICABLE row is the payments seam's, written with the "
                        + "intent; opening a second document over it would be a second sale")
                .isZero();
        assertThat(store.forOrder(TENANT, order)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(existing);
            assertThat(row.state()).isEqualTo(FiscalDocumentState.NOT_APPLICABLE);
        });
    }

    @Test
    @DisplayName("an order that was rejected or cancelled owes no sale receipt")
    void onlyACompletedOrderOpensASaleObligation() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID cancelled = order("O-10", "PAYME", "CANCELLED", "CANCELLED", NOW.minusSeconds(3600));

        assertThat(obligations.openObligations(50))
                .as("a worklist filled with abandoned checkouts is a worklist nobody reads")
                .isZero();
        assertThat(store.forOrder(TENANT, cancelled)).isEmpty();
    }

    @Test
    @DisplayName("an order that completed before the lookback window is not this pass's to open")
    void thePassHasAFloorRatherThanScanningEveryOrderEverPlaced() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-11", "PAYME", "PAID", Instant.parse("2026-07-04T10:00:00Z"));

        assertThat(build(NOW, Duration.ofDays(7)).openObligations(50))
                .as("without a floor every run scans the whole order history to find nothing, "
                        + "and back-filling a year of orders is a migration rather than a sweep")
                .isZero();
        assertThat(store.forOrder(TENANT, order)).isEmpty();
    }

    // ---------------------------------------------------------- submitting it

    @Test
    @DisplayName("a captured obligation is claimed before it is sent, and the provider is asked once")
    void aCapturedObligationIsSentExactlyOnce() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("S-1", "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        sweeper.submitCapturedObligations();

        FiscalDocumentRow afterSubmission = onlyDocument(order);
        assertThat(afterSubmission.state())
                .as("claim first, send second: asking first and recording afterwards lets two "
                        + "nodes both reach Click")
                .isEqualTo(FiscalDocumentState.SUBMITTED);
        assertThat(afterSubmission.reportingDeadlineAt())
                .as("every document entering SUBMITTED carries the deadline it will be judged "
                        + "against, or a false positive is unarguable")
                .isEqualTo(NOW.plusSeconds(3600));
        assertThat(afterSubmission.attemptCount()).isEqualTo(1);

        sweeper.submitCapturedObligations();

        assertThat(partner.asks)
                .as("two sale receipts for one payment can only be corrected with the tax "
                        + "authority, never withdrawn")
                .containsExactly(afterSubmission.id());
    }

    @Test
    @DisplayName("an obligation whose payment has not captured is not sent")
    void anUncapturedObligationWaits() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("S-2", "CLICK", "AUTHORIZING", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        assertThat(obligations.claimSubmissions(50))
                .as("Click's submit_items needs a payment_id that does not exist before capture")
                .isEmpty();
        assertThat(onlyDocument(order).state()).isEqualTo(FiscalDocumentState.PENDING);
    }

    @Test
    @DisplayName("a document that names no company is blocked and never sent")
    void nothingIsSentWithoutASeller() {
        UUID order = completedOrder("S-3", "PAYME", "PAID", NOW.minusSeconds(3600));
        // Opened before an assignment existed, then given a provider path but no
        // seller — the shape a partially migrated tenant leaves behind.
        store.open(newDocument(order, FiscalDocumentState.PENDING, null));

        sweeper.submitCapturedObligations();

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state())
                .as("neither provider takes a seller as a request field, so an unresolved "
                        + "entity means the receipt is issued under whichever account is found")
                .isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(document.reasonCode()).isEqualTo(FiscalReasonCode.NO_FISCAL_PATH);
        assertThat(partner.asks).isEmpty();
    }

    @Test
    @DisplayName("an unwired partner returns the claim rather than leaving it looking submitted")
    void anUnsentClaimIsReleased() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("S-4", "PAYME", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        partner.answers = PartnerFiscalizationPort.Outcome.NOT_WIRED;
        sweeper.submitCapturedObligations();

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state())
                .as("left SUBMITTED it would be blocked an hour later as 'the provider did not "
                        + "report', about a provider nobody asked")
                .isEqualTo(FiscalDocumentState.PENDING);
        assertThat(document.submittedAt()).isNull();
        assertThat(document.reportingDeadlineAt()).isNull();
    }

    @Test
    @DisplayName("an uncertain answer leaves the document submitted, for the reporting sweep")
    void anUncertainAnswerIsNotResent() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("S-5", "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        ClaimedSubmission claim = obligations.claimSubmissions(50).getFirst();
        obligations.settle(claim, PartnerFiscalizationPort.Outcome.UNCERTAIN);

        assertThat(onlyDocument(order).state())
                .as("a request that may have arrived is never returned to the queue: a "
                        + "duplicate document with a tax authority cannot be withdrawn")
                .isEqualTo(FiscalDocumentState.SUBMITTED);
    }

    @Test
    @DisplayName("an entity with no merchant account for the method becomes visible work")
    void noMerchantAccountIsBlockedWork() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("S-6", "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        ClaimedSubmission claim = obligations.claimSubmissions(50).getFirst();
        obligations.settle(claim, PartnerFiscalizationPort.Outcome.NO_PROVIDER_PATH);

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(document.reasonCode()).isEqualTo(FiscalReasonCode.NO_FISCAL_PATH);
        assertThat(document.reasonNote()).contains("CLICK");
    }

    @ParameterizedTest(name = "{0} blocks the claim as {1}")
    @CsvSource({
        "BLOCKED_CLASSIFICATION, CLASSIFICATION_MISSING",
        "BLOCKED_TAX, VAT_NOT_EXPRESSIBLE",
        "BLOCKED_RECONCILIATION, RECEIPT_DOES_NOT_RECONCILE"
    })
    @DisplayName(
            "a claim whose receipt could not be built is blocked with the reason that names why, not 'the provider did not report'")
    void aReceiptThatCouldNotBeBuiltBlocksTheClaimWithTheTrueReason(
            PartnerFiscalizationPort.Outcome outcome, String expectedReason) {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("S-8-" + outcome.ordinal(), "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        ClaimedSubmission claim = obligations.claimSubmissions(50).getFirst();
        obligations.settle(claim, outcome);

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(document.reasonCode())
                .as("nothing was sent; an hour later the sweep would otherwise say a provider nobody "
                        + "asked failed to answer")
                .isEqualTo(expectedReason);
        assertThat(FiscalReasonCode.BLOCKING).contains(expectedReason);
    }

    @Test
    @DisplayName(
            "the historical LINES_NOT_BUILT reason stays blockable, so a document blocked before the builder existed can be worked")
    void theHistoricalReasonStaysAValidBlockingReason() {
        assertThat(FiscalReasonCode.BLOCKING).contains(FiscalReasonCode.LINES_NOT_BUILT);
    }

    @Test
    @DisplayName("the opener snapshots the INN of the company in force, and a later re-registration does not move it")
    void theOpenerSnapshotsTheTaxpayer() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("O-snap", "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        String before = jdbc.sql("SELECT seller_tin || '/' || seller_snapshot_source FROM fiscal.fiscal_documents"
                        + " WHERE order_id = :o")
                .param("o", order)
                .query(String.class)
                .single();
        jdbc.sql("UPDATE tenant.legal_entities SET tin = '999999999' WHERE id = :id")
                .param("id", firstCompany)
                .update();
        String after = jdbc.sql("SELECT seller_tin || '/' || seller_snapshot_source FROM fiscal.fiscal_documents"
                        + " WHERE order_id = :o")
                .param("o", order)
                .query(String.class)
                .single();

        assertThat(before).endsWith("/OPENING");
        assertThat(after).as("a snapshot, never a join").isEqualTo(before);
        assertThat(before).doesNotStartWith("999999999");
    }

    @Test
    @DisplayName("an issued document is left to the payments seam, which holds the evidence")
    void anIssuedOutcomeIsNotRewrittenHere() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("S-7", "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        ClaimedSubmission claim = obligations.claimSubmissions(50).getFirst();
        int versionBefore = onlyDocument(order).version();
        obligations.settle(claim, PartnerFiscalizationPort.Outcome.ISSUED);

        assertThat(onlyDocument(order).version())
                .as("a second writer for the evidence columns would be a second authority over "
                        + "what the tax authority was told")
                .isEqualTo(versionBefore);
    }

    // ------------------------------------------------- ADR 0161: marked goods

    @Test
    @DisplayName(
            "a marked order short of its codes is blocked for that reason, and a blocked document is not asked again")
    void aMarkedOrderShortOfCodesIsBlockedWithItsOwnReason() {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder("M-1", "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);

        partner.answers = PartnerFiscalizationPort.Outcome.BLOCKED_MARKS;
        sweeper.submitCapturedObligations();

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(document.reasonCode())
                .as("not NO_FISCAL_PATH and not CLASSIFICATION_MISSING: the obstacle is the scans")
                .isEqualTo(FiscalReasonCode.MARKS_INCOMPLETE);
        assertThat(document.reasonNote()).contains("scanned units").contains("nothing was sent");

        sweeper.submitCapturedObligations();

        assertThat(partner.asks)
                .as("a document blocked for its marks waits for them; it is not retried into the same refusal")
                .containsExactly(document.id());
    }

    @Test
    @DisplayName("when the marks complete the blocked document is reopened for the next submission pass")
    void completedMarksReopenTheBlockedDocument() {
        UUID order = blockedForMarks("M-2");
        marks.counts = List.of(new OrderMarksPort.LineMarkCount(UUID.randomUUID(), 2, 2));

        reopener().onMarksCompleted(completed(order));

        FiscalDocumentRow document = onlyDocument(order);
        assertThat(document.state()).isEqualTo(FiscalDocumentState.PENDING);
        assertThat(document.reasonCode()).isEqualTo(FiscalReasonCode.AWAITING_PROVIDER);
    }

    @Test
    @DisplayName("a removal that landed after the event keeps the document blocked: the event is a nudge, not a ledger")
    void aStaleNudgeDoesNotReopenAShortOrder() {
        UUID order = blockedForMarks("M-3");
        marks.counts = List.of(new OrderMarksPort.LineMarkCount(UUID.randomUUID(), 2, 1));

        reopener().onMarksCompleted(completed(order));

        assertThat(onlyDocument(order).state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(onlyDocument(order).reasonCode()).isEqualTo(FiscalReasonCode.MARKS_INCOMPLETE);
    }

    @Test
    @DisplayName("an order with no marked line to count never reopens anything")
    void noCountsNoReopen() {
        UUID order = blockedForMarks("M-4");
        marks.counts = List.of();

        reopener().onMarksCompleted(completed(order));

        assertThat(onlyDocument(order).state()).isEqualTo(FiscalDocumentState.BLOCKED);
    }

    @Test
    @DisplayName("a document blocked for another reason is left for the operator whatever the marks say")
    void aDocumentBlockedForAnotherReasonIsNotReopened() {
        UUID order = completedOrder("M-5", "PAYME", "PAID", NOW.minusSeconds(3600));
        store.open(newDocument(order, FiscalDocumentState.PENDING, null));
        sweeper.submitCapturedObligations();
        assertThat(onlyDocument(order).reasonCode()).isEqualTo(FiscalReasonCode.NO_FISCAL_PATH);
        marks.counts = List.of(new OrderMarksPort.LineMarkCount(UUID.randomUUID(), 1, 1));

        reopener().onMarksCompleted(completed(order));

        assertThat(onlyDocument(order).state()).isEqualTo(FiscalDocumentState.BLOCKED);
        assertThat(onlyDocument(order).reasonCode()).isEqualTo(FiscalReasonCode.NO_FISCAL_PATH);
    }

    private final FakeMarks marks = new FakeMarks();

    private UUID blockedForMarks(String number) {
        assign(firstCompany, LocalDate.of(2026, 1, 1));
        UUID order = completedOrder(number, "CLICK", "PAID", NOW.minusSeconds(3600));
        obligations.openObligations(50);
        partner.answers = PartnerFiscalizationPort.Outcome.BLOCKED_MARKS;
        sweeper.submitCapturedObligations();
        assertThat(onlyDocument(order).reasonCode()).isEqualTo(FiscalReasonCode.MARKS_INCOMPLETE);
        return order;
    }

    private MarksCompletedReopener reopener() {
        FiscalDocumentService service = new FiscalDocumentService(
                store,
                new FiscalReportingPolicyService(
                        new JdbcPolicyResolver(jdbc, JsonMapper.builder().build())),
                partner,
                fact -> {},
                event -> {},
                Clock.fixed(NOW, ZoneOffset.UTC),
                "Asia/Tashkent",
                Duration.ofMinutes(1));
        return new MarksCompletedReopener(store, service, marks);
    }

    private static OrderMarksCompleted completed(UUID orderId) {
        return new OrderMarksCompleted(
                UUID.randomUUID(), new TenantId(TENANT), orderId, NOW, BRAND, LOCATION, 1, 2, NOW);
    }

    private static final class FakeMarks implements OrderMarksPort {

        private List<LineMarkCount> counts = List.of();

        @Override
        public List<LineMarkCount> markCounts(UUID tenantId, UUID orderId) {
            return counts;
        }

        @Override
        public ReceiptSource receiptSource(UUID tenantId, UUID orderId) {
            throw new AssertionError("the reopener reads counts only and must never ask for the codes");
        }
    }

    // -------------------------------------------------------------- fixtures

    private FiscalDocumentRow onlyDocument(UUID orderId) {
        List<FiscalDocumentRow> rows = store.forOrder(TENANT, orderId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private NewFiscalDocument newDocument(UUID orderId, FiscalDocumentState state, @Nullable UUID entityId) {
        return new NewFiscalDocument(
                UUID.randomUUID(),
                TENANT,
                orderId,
                entityId,
                intentId(orderId),
                "PAYME",
                state,
                FiscalReasonCode.AWAITING_CAPTURE,
                "awaiting capture",
                NOW);
    }

    private void assign(UUID entityId, LocalDate from) {
        jdbc.sql("""
                UPDATE tenant.location_fiscal_assignments
                SET effective_until = :from
                WHERE tenant_id = :t AND location_id = :loc AND effective_until IS NULL
                """)
                .param("from", from)
                .param("t", TENANT)
                .param("loc", LOCATION)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id,
                    location_id, legal_entity_id, effective_from, approved_by)
                VALUES (:id, :t, :b, :loc, :e, :from, 'finance@example.test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("e", entityId)
                .param("from", from)
                .update();
    }

    /** A settlement with one settled money tender naming the order's payment intent, as checkout writes it. */
    private UUID settleWithAMoneyTender(UUID orderId) {
        UUID method = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        UUID tender = UUID.randomUUID();
        UUID intent = jdbc.sql("SELECT id FROM payments.payment_intents WHERE order_id = :o")
                .param("o", orderId)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility)
                VALUES (:id, :t, 'CLICK', 'Click', 'PARTNER')
                ON CONFLICT (tenant_id, code) DO NOTHING
                """).param("id", method).param("t", TENANT).update();
        UUID methodId = jdbc.sql("SELECT id FROM payments.payment_methods WHERE tenant_id = :t AND code = 'CLICK'")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO payments.order_settlements (id, tenant_id, order_id, currency, total_due_minor,
                    settled_minor, status, version)
                VALUES (:id, :t, :o, 'UZS', 50000, 50000, 'SETTLED', 1)
                """)
                .param("id", settlement)
                .param("t", TENANT)
                .param("o", orderId)
                .update();
        jdbc.sql("""
                INSERT INTO payments.tenders (id, tenant_id, settlement_id, sequence, payment_method_id,
                    settles_from_balance, amount_minor, currency, status, payment_intent_id, settled_at,
                    idempotency_key, version)
                VALUES (:id, :t, :s, 1, :m, false, 50000, 'UZS', 'SETTLED', :intent, now(), :key, 1)
                """)
                .param("id", tender)
                .param("t", TENANT)
                .param("s", settlement)
                .param("m", methodId)
                .param("intent", intent)
                .param("key", "tender-" + tender)
                .update();
        return tender;
    }

    private UUID completedOrder(
            String seed, @Nullable String providerType, @Nullable String intentStatus, Instant closedAt) {
        UUID orderId = order(seed, providerType, "COMPLETED", intentStatus, closedAt);
        return orderId;
    }

    private UUID order(
            String seed,
            @Nullable String providerType,
            String status,
            @Nullable String intentStatus,
            Instant closedAt) {
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
                .param("at", closedAt.atOffset(ZoneOffset.UTC))
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
                .param("at", closedAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, discount_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, promise_basis, version, created_at, confirmed_at,
                    closed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'TELEGRAM', :cust, 'DELIVERY',
                    'AUTO_CONFIRM', 'NONE', :status, 'UZS', 50000, 0, 0, 0, 50000,
                    :quote, :hash, :pub, :cart, :key, 'NOT_PROMISED', 1, :at, :at, :at)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("status", status)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("at", closedAt.atOffset(ZoneOffset.UTC))
                .update();

        if (providerType != null) {
            insertIntent(seed, orderId, "PROVIDER", providerType, providerType, intentStatus, closedAt);
        }
        return orderId;
    }

    private void cashIntent(UUID orderId) {
        insertIntent("cash-" + orderId, orderId, "CASH", "CASH", null, "PAID", NOW);
    }

    private void insertIntent(
            String seed,
            UUID orderId,
            String tender,
            String methodCode,
            @Nullable String providerType,
            @Nullable String status,
            Instant at) {
        boolean settled = !("PENDING".equals(status) || "AUTHORIZING".equals(status));
        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id,
                    location_id, legal_entity_id, tender, payment_method_code, provider_type,
                    requested_amount_minor, currency, status, capture_timing, idempotency_key,
                    settled_at, version, created_at, updated_at)
                VALUES (:id, :t, :o, :b, :loc, NULL, :tender, :code, :provider, 50000, 'UZS',
                    :status, :timing, :key, :settledAt, 1, :at, :at)
                """)
                .param("id", UUID.nameUUIDFromBytes(("intent:" + seed).getBytes(StandardCharsets.UTF_8)))
                .param("t", TENANT)
                .param("o", orderId)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("tender", tender)
                .param("code", methodCode)
                .param("provider", providerType)
                .param("status", status)
                .param("timing", providerType == null ? "ON_HANDOVER" : "BEFORE_CONFIRMATION")
                .param("key", "intent-" + seed)
                .param("settledAt", settled ? at.atOffset(ZoneOffset.UTC) : null)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Exactly the row the payments seam writes for a cash tender at intent creation. */
    private UUID cashDecision(UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, payment_intent_id,
                    document_type, status, reason_code, reason_note, created_at, updated_at)
                VALUES (:id, :t, :o, :i, 'SALE', 'NOT_APPLICABLE', :reason,
                    'no payment provider can fiscalize a cash tender', :at, :at)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("o", orderId)
                .param("i", intentId(orderId))
                .param("reason", FiscalReasonCode.CASH_TENDER_NO_PROVIDER_FISCALIZATION)
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID intentId(UUID orderId) {
        return jdbc.sql("SELECT id FROM payments.payment_intents WHERE tenant_id = :t " + "AND order_id = :o")
                .param("t", TENANT)
                .param("o", orderId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("no payment intent seeded for order " + orderId));
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'obligation-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent',
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

        channelId = UUID.nameUUIDFromBytes("obligation-channel".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.nameUUIDFromBytes("obligation-catalog".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.nameUUIDFromBytes("obligation-publication".getBytes(StandardCharsets.UTF_8));
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

        firstCompany = UUID.nameUUIDFromBytes("company:first".getBytes(StandardCharsets.UTF_8));
        secondCompany = UUID.nameUUIDFromBytes("company:second".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'FIRST', 'Birinchi MCHJ', '123456789', 'ACTIVE')
                """).param("id", firstCompany).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'SECOND', 'Ikkinchi MCHJ', '223456789', 'ACTIVE')
                """).param("id", secondCompany).param("t", TENANT).update();
    }

    /**
     * Records what it was asked and answers what the test sets.
     *
     * <p>{@code submit} is not overridden, deliberately: the port's default routes
     * it to {@code retry}, and a stub that split them would let an implementation
     * that only wired one of the two pass.
     */
    private static final class StubPartner implements PartnerFiscalizationPort {

        private final List<UUID> asks = new ArrayList<>();
        private Outcome answers = Outcome.ISSUED;

        @Override
        public Outcome retry(UUID tenantId, UUID documentId, String idempotencyKey) {
            asks.add(documentId);
            return answers;
        }
    }
}
