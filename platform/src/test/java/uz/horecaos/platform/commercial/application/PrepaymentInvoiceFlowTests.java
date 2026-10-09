package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.commercial.application.PlatformBillingSettingsService.BankDetails;
import uz.horecaos.platform.commercial.application.WalletService.WalletChangeOutcome;
import uz.horecaos.platform.commercial.domain.PlatformBillingSettings;
import uz.horecaos.platform.commercial.domain.PrepaymentInvoice;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0095: invoices for prepaid money, and the bank details they carry.
 *
 * <p>Two things must hold however the pieces are combined: an invoice never tells a tenant to pay a
 * placeholder or a number one person typed alone, and what an invoice has been paid is the ledger's own
 * sum — there is no status column beside the ledger for the two to disagree in.
 */
class PrepaymentInvoiceFlowTests extends WalletBillingFixture {

    // --------------------------------------------------------------- the bank details

    @Test
    void anInvoiceIsRefusedWhileTheBankDetailsAreStillAPlaceholder() {
        PlatformBillingSettings settings = billingSettings.current();
        assertThat(settings.configured()).isFalse();
        assertThat(settings.account()).startsWith("[");

        assertThatThrownBy(() -> inTx(() -> invoices.issue(PILOT, 1_000_000, TENANT_USER, "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .containsEntry("reason", "BANK_DETAILS_NOT_CONFIGURED"));
        assertThat(invoices.list(PILOT)).isEmpty();
    }

    @Test
    void bankDetailsChangeOnlyWithASecondPersonsSignatureAndTheCheckerSeesTheWholeProposal() {
        WalletChangeOutcome first = inTx(() -> billingSettings.proposeBankDetails(
                realBankDetails(), MAKER, "launch details, for the invoice run", "c"));

        assertThat(first.status()).isEqualTo(WalletChangeOutcome.AWAITING_APPROVAL);
        UUID requestId = java.util.Objects.requireNonNull(first.approvalRequestId());
        assertThat(billingSettings.current().configured())
                .as("nothing moved on one person's word")
                .isFalse();
        String subject = jdbc.sql("SELECT subject_json::text FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
        assertThat(subject)
                .as("the account is what is being signed, so the checker is shown it")
                .contains("20208000900123456001")
                .contains("Kapitalbank")
                .as("and the maker's prose stays off the queue (ADR 0029)")
                .doesNotContain("launch details");
        assertThatThrownBy(
                        () -> inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, MAKER, "me")))
                .as("a maker cannot sign their own proposal")
                .isInstanceOf(RuntimeException.class);

        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));
        WalletChangeOutcome applied = inTx(() -> billingSettings.proposeBankDetails(
                realBankDetails(), MAKER, "launch details, for the invoice run", "c"));

        assertThat(applied.status()).isEqualTo(WalletChangeOutcome.CHANGED);
        PlatformBillingSettings settings = billingSettings.current();
        assertThat(settings.configured()).isTrue();
        assertThat(settings.account()).isEqualTo("20208000900123456001");
        assertThat(settings.updatedBy()).isEqualTo(MAKER.subject());
        assertThat(settings.approvedBy()).isEqualTo(CHECKER.subject());
        assertThat(auditedActions("commercial.billing.")).containsExactly("commercial.billing.bank_details_changed");
    }

    @Test
    void theDatabaseRefusesRealDetailsThatCarryOneNameOrNone() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.platform_billing_settings SET configured = true")
                        .update())
                .as("real details with no approver at all")
                .hasMessageContaining("ck_platform_billing_four_eyes");
        assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE commercial.platform_billing_settings
                                   SET configured = true, approved_by = updated_by, approval_request_id = gen_random_uuid()
                                """).update())
                .as("real details the same person proposed and approved")
                .hasMessageContaining("ck_platform_billing_four_eyes");
        assertThatThrownBy(
                        () -> jdbc.sql("INSERT INTO commercial.platform_billing_settings (singleton, bank_beneficiary, "
                                        + "bank_name, bank_account, bank_mfo, bank_tax_id, updated_by, updated_at) "
                                        + "VALUES (false, 'a','b','c','d','e','x', now())")
                                .update())
                .as("there is one row, enforced by the key itself")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void invalidBankDetailsAreRefusedBeforeAnythingIsProposed() {
        for (BankDetails bad : List.of(
                new BankDetails(
                        "[beneficiary: set by HorecaOS finance]",
                        "Kapitalbank",
                        "20208000900123456001",
                        "01158",
                        "309876543"),
                new BankDetails("HorecaOS MCHJ", "", "20208000900123456001", "01158", "309876543"),
                new BankDetails("HorecaOS MCHJ", "Kapitalbank", "12", "01158", "309876543"),
                new BankDetails("HorecaOS MCHJ", "Kapitalbank", "2020 8000;DROP", "01158", "309876543"),
                new BankDetails("HorecaOS MCHJ", "Kapitalbank", "20208000900123456001", "0 1", "309876543"),
                new BankDetails("HorecaOS MCHJ", "Kapitalbank", "20208000900123456001", "01158", "[tax id]"))) {
            assertThatThrownBy(() -> inTx(() -> billingSettings.proposeBankDetails(bad, MAKER, "x", "c")))
                    .isInstanceOf(ApiException.class);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit.approval_requests")
                        .query(Long.class)
                        .single())
                .as("nothing reached the approvals queue")
                .isZero();
    }

    // ------------------------------------------------------------------ issuing an invoice

    @Test
    void anInvoiceIsFrozenAtIssueWithTheBankDetailsOfThatMoment() {
        configureBankDetails();
        PrepaymentInvoice first = inTx(() -> invoices.issue(PILOT, 1_000_000, TENANT_USER, "c"));
        assertThat(first.number()).matches("PI-202610-\\d{6}");
        assertThat(first.validUntil()).isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(first.bankAccount()).isEqualTo("20208000900123456001");
        assertThat(first.paidMinor()).isZero();
        assertThat(ledgerSize(PILOT)).as("asking for money moves none").isZero();

        BankDetails moved =
                new BankDetails("HorecaOS MCHJ", "Ipoteka Bank", "20208000111222333444", "00014", "309876543");
        applyChange(() -> billingSettings.proposeBankDetails(moved, MAKER, "the account moved", "c"));
        PrepaymentInvoice second = inTx(() -> invoices.issue(PILOT, 500_000, TENANT_USER, "c"));

        assertThat(invoices.find(PILOT, first.id()).bankAccount())
                .as("the tenant pays what the document said")
                .isEqualTo("20208000900123456001");
        assertThat(second.bankAccount()).isEqualTo("20208000111222333444");
        assertThat(second.number()).isNotEqualTo(first.number());
        assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.prepayment_invoices SET amount_minor = 1 WHERE id = :id")
                        .param("id", first.id())
                        .update())
                .hasMessageContaining("frozen");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM commercial.prepayment_invoices WHERE id = :id")
                        .param("id", first.id())
                        .update())
                .hasMessageContaining("never deleted");
        assertThat(auditedActions("commercial.wallet.prepayment_invoice")).hasSize(2);
    }

    @Test
    void theApplicationMayWriteNothingToAnInvoiceButItsOwnCancellation() {
        configureBankDetails();
        PrepaymentInvoice invoice = inTx(() -> invoices.issue(PILOT, 1_000_000, TENANT_USER, "c"));

        assertThatThrownBy(() -> application
                        .sql("UPDATE commercial.prepayment_invoices SET amount_minor = 1 WHERE id = :id")
                        .param("id", invoice.id())
                        .update())
                .as("not even the trigger is the only stop: the column grant refuses it first")
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM commercial.prepayment_invoices WHERE id = :id")
                        .param("id", invoice.id())
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThat(application.sql("""
                                UPDATE commercial.prepayment_invoices
                                   SET cancelled_by = 'u', cancelled_at = now(), cancel_reason = 'r' WHERE id = :id
                                """).param("id", invoice.id()).update()).isEqualTo(1);
    }

    @Test
    void anInvoiceIsForAPositiveAmountAndNumbersCarryTheMonth() {
        configureBankDetails();
        assertThatThrownBy(() -> inTx(() -> invoices.issue(PILOT, 0, TENANT_USER, "c")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(() -> invoices.issue(PILOT, -1, TENANT_USER, "c")))
                .isInstanceOf(ApiException.class);

        PrepaymentInvoice october = inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c"));
        clock.set(CLOSE);
        PrepaymentInvoice november = inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c"));

        assertThat(october.number()).startsWith("PI-202610-");
        assertThat(november.number()).startsWith("PI-202611-");
        assertThat(invoices.list(PILOT)).extracting(PrepaymentInvoice::id).containsExactly(november.id(), october.id());
    }

    @Test
    void tooManyInvoicesWaitingToBePaidAreRefusedAndExpiredOnesDoNotCount() {
        configureBankDetails();
        UUID first =
                inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c")).id();
        inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c"));
        inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c"));

        assertThatThrownBy(() -> inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "TOO_MANY_OPEN_INVOICES"));
        inTx(() -> invoices.issue(RIVAL, 100_000, TENANT_USER, "c"));

        inTx(() -> invoices.cancel(
                PILOT,
                first,
                TENANT_USER,
                "not needed",
                uz.horecaos.platform.iam.api.Capability.COMMERCIAL_WALLET_TOPUP,
                "c"));
        inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c"));

        clock.advance(Duration.ofDays(15));
        inTx(() -> invoices.issue(PILOT, 100_000, TENANT_USER, "c"));
        assertThat(invoices.list(PILOT)).hasSize(5);
    }

    // ---------------------------------------------------------- being paid, and not being

    @Test
    void aTransferNamingAnInvoicePaysItAndWhatItHasBeenPaidIsTheLedgersSum() {
        configureBankDetails();
        PrepaymentInvoice invoice = inTx(() -> invoices.issue(PILOT, 1_000_000, TENANT_USER, "c"));

        inTx(() -> invoices.recordTransfer(PILOT, invoice.number(), 400_000, "MT103-1", MAKER, "part", "c"));
        recordTransfer(PILOT, 90_000, "MT103-OTHER");

        PrepaymentInvoice part = invoices.find(PILOT, invoice.id());
        assertThat(part.paidMinor())
                .as("a transfer that does not name it is not counted")
                .isEqualTo(400_000);
        assertThat(part.statusAt(clock.instant())).isEqualTo(PrepaymentInvoice.PARTIALLY_PAID);
        assertThat(part.dueMinor()).isEqualTo(600_000);

        inTx(() ->
                invoices.recordTransfer(PILOT, " " + invoice.number() + " ", 600_000, "MT103-2", MAKER, "rest", "c"));

        PrepaymentInvoice paid = invoices.find(PILOT, invoice.id());
        assertThat(paid.statusAt(clock.instant())).isEqualTo(PrepaymentInvoice.PAID);
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND prepayment_invoice_id = :invoice
                        """)
                        .param("id", PILOT)
                        .param("invoice", invoice.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(paid.paidMinor())
                .isEqualTo(1_000_000);
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("the money is the tenant's wallet credit, the invoice only names which transfers asked for")
                .isEqualTo(1_090_000);
        assertThat(jdbc.sql(
                                "SELECT change_document::text FROM audit.audit_events WHERE action_code = 'commercial.wallet.transfer_recorded' AND change_document::text LIKE '%prepaymentInvoiceId%'")
                        .query(String.class)
                        .list())
                .hasSize(2);
    }

    @Test
    void aWireLargerThanTheInvoiceIsStillRecordedAndTheExcessIsWalletCredit() {
        configureBankDetails();
        PrepaymentInvoice invoice = inTx(() -> invoices.issue(PILOT, 1_000_000, TENANT_USER, "c"));

        inTx(() -> invoices.recordTransfer(PILOT, invoice.number(), 1_300_000, "MT103-BIG", MAKER, "wire", "c"));

        PrepaymentInvoice after = invoices.find(PILOT, invoice.id());
        assertThat(after.statusAt(clock.instant())).isEqualTo(PrepaymentInvoice.PAID);
        assertThat(after.dueMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(1_300_000);
    }

    @Test
    void aTransferPaysTheOldestStatementFirstWhetherOrNotItNamesAnInvoice() {
        startOnPlan(START, MONTHLY);
        configureBankDetails();
        issue(PILOT, "2026-10", CLOSE);
        PrepaymentInvoice invoice = inTx(() -> invoices.issue(PILOT, MONTHLY + 100_000, TENANT_USER, "c"));

        inTx(() -> invoices.recordTransfer(PILOT, invoice.number(), MONTHLY + 100_000, "MT103-S", MAKER, "wire", "c"));

        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(100_000);
        assertThat(invoices.find(PILOT, invoice.id()).paidMinor()).isEqualTo(MONTHLY + 100_000);
    }

    @Test
    void anInvoiceNothingHasPaidMayBeCancelledOnceAndNeverAfterItIsPaid() {
        configureBankDetails();
        PrepaymentInvoice unpaid = inTx(() -> invoices.issue(PILOT, 1_000_000, TENANT_USER, "c"));
        PrepaymentInvoice paid = inTx(() -> invoices.issue(PILOT, 200_000, TENANT_USER, "c"));
        inTx(() -> invoices.recordTransfer(PILOT, paid.number(), 50_000, "MT103-P", MAKER, "part", "c"));

        PrepaymentInvoice cancelled = inTx(() -> invoices.cancel(
                PILOT,
                unpaid.id(),
                TENANT_USER,
                "wrong amount",
                uz.horecaos.platform.iam.api.Capability.COMMERCIAL_WALLET_TOPUP,
                "c"));

        assertThat(cancelled.statusAt(clock.instant())).isEqualTo(PrepaymentInvoice.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("wrong amount");
        assertThatThrownBy(() -> inTx(() -> invoices.cancel(
                        PILOT,
                        unpaid.id(),
                        TENANT_USER,
                        "again",
                        uz.horecaos.platform.iam.api.Capability.COMMERCIAL_WALLET_TOPUP,
                        "c")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(() -> invoices.cancel(
                        PILOT,
                        paid.id(),
                        TENANT_USER,
                        "too late",
                        uz.horecaos.platform.iam.api.Capability.COMMERCIAL_WALLET_TOPUP,
                        "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "INVOICE_HAS_PAYMENTS"));
        assertThatThrownBy(() -> inTx(
                        () -> invoices.recordTransfer(PILOT, unpaid.number(), 1_000, "MT103-C", MAKER, "late", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "INVOICE_CANCELLED"));
        assertThatThrownBy(
                        () -> jdbc.sql("UPDATE commercial.prepayment_invoices SET cancel_reason = 'x' WHERE id = :id")
                                .param("id", unpaid.id())
                                .update())
                .as("a cancellation is not itself editable")
                .hasMessageContaining("never changed");
    }

    @Test
    void aTransferCannotNameAnotherTenantsInvoiceOrOneThatDoesNotExist() {
        configureBankDetails();
        PrepaymentInvoice rivals = inTx(() -> invoices.issue(RIVAL, 1_000_000, TENANT_USER, "c"));

        assertThatThrownBy(() ->
                        inTx(() -> invoices.recordTransfer(PILOT, rivals.number(), 1_000, "MT103-X", MAKER, "x", "c")))
                .as("another tenant's invoice is as unknown as a random number")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode().name()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThatThrownBy(() -> inTx(
                        () -> invoices.recordTransfer(PILOT, "PI-000000-000000", 1_000, "MT103-Y", MAKER, "x", "c")))
                .isInstanceOf(ApiException.class);
        assertThat(invoices.list(PILOT)).isEmpty();
        assertThat(invoices.list(RIVAL)).hasSize(1);
        assertThatThrownBy(() -> invoices.find(PILOT, rivals.id())).isInstanceOf(ApiException.class);
        assertThat(ledgerSize(PILOT)).isZero();
        assertThat(ledgerSize(RIVAL)).isZero();
    }

    @Test
    void theSchemaLetsOnlyMoneyInNameAnInvoiceAndOnlyItsOwnTenants() {
        configureBankDetails();
        PrepaymentInvoice invoice = inTx(() -> invoices.issue(PILOT, 1_000_000, TENANT_USER, "c"));

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO commercial.wallet_entries (
                                    id, tenant_id, money_kind, entry_type, amount_minor, currency, reason,
                                    recorded_by, approved_by, approval_request_id, created_at, prepayment_invoice_id)
                                VALUES (gen_random_uuid(), :tenant, 'PAID', 'ADJUSTMENT', 5, 'UZS', 'r', 'u', 'v',
                                        gen_random_uuid(), now(), :invoice)
                                """)
                        .param("tenant", PILOT)
                        .param("invoice", invoice.id())
                        .update())
                .hasMessageContaining("ck_wallet_entry_prepayment_invoice");
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO commercial.wallet_entries (
                                    id, tenant_id, money_kind, entry_type, amount_minor, currency, reason,
                                    external_reference, recorded_by, created_at, prepayment_invoice_id)
                                VALUES (gen_random_uuid(), :tenant, 'PAID', 'TOP_UP', 5, 'UZS', 'r', 'REF-RIVAL', 'u', now(), :invoice)
                                """)
                        .param("tenant", RIVAL)
                        .param("invoice", invoice.id())
                        .update())
                .as("a composite key: another tenant's money cannot pay this tenant's invoice")
                .hasMessageContaining("fk_wallet_entry_prepayment_invoice");
    }
}
