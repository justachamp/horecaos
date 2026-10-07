package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.commercial.domain.PlatformBillingSettings;
import uz.horecaos.platform.commercial.domain.PrepaymentInvoice;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlatformBillingSettingsStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPrepaymentInvoiceStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * An invoice for money a tenant wants to put in before it is owed (ADR 0095: "invoices for prepaid
 * money"). A tenant that pays HorecaOS by bank transfer needs a document to pay against before there is
 * anything to pay: the amount, where to send it, and a number to quote so finance can tell whose money
 * arrived.
 *
 * <p>It is a request for payment, not a tax invoice. The wallet computes no tax until finance says how
 * money held in advance is taxed (decision 7), so every figure is before tax and the document says so.
 *
 * <p><strong>Frozen at issue.</strong> The amount, the number and the bank details as they were at that
 * moment never change, because the tenant pays what the document said; the database refuses the change.
 * Only a cancellation can be written afterwards.
 *
 * <p><strong>Paid is the ledger's word.</strong> Finance records the transfer naming the invoice, and what
 * the invoice has been paid is the sum of the entries that name it. An invoice that is paid, part-paid or
 * expired is read, never stored.
 *
 * <p><strong>Never from a placeholder.</strong> Until HorecaOS finance has replaced the placeholder bank
 * details through the approved change, an invoice is refused: a tenant is never handed a document that
 * tells it to pay a sentence.
 */
@Service
public class PrepaymentInvoiceService {

    private final JdbcPrepaymentInvoiceStore invoices;
    private final JdbcPlatformBillingSettingsStore settings;
    private final JdbcWalletStore wallet;
    private final WalletService walletService;
    private final AuditRecorder audit;
    private final Clock clock;
    private final int validDays;
    private final int maxOpen;

    public PrepaymentInvoiceService(
            JdbcPrepaymentInvoiceStore invoices,
            JdbcPlatformBillingSettingsStore settings,
            JdbcWalletStore wallet,
            WalletService walletService,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.commercial.wallet.prepayment-invoice.valid-days:14}") int validDays,
            @Value("${horecaos.commercial.wallet.prepayment-invoice.max-open:10}") int maxOpen) {
        this.invoices = invoices;
        this.settings = settings;
        this.wallet = wallet;
        this.walletService = walletService;
        this.audit = audit;
        this.clock = clock;
        this.validDays = validDays;
        this.maxOpen = maxOpen;
    }

    @Transactional(readOnly = true)
    public List<PrepaymentInvoice> list(UUID tenantId) {
        return invoices.list(tenantId);
    }

    @Transactional(readOnly = true)
    public PrepaymentInvoice find(UUID tenantId, UUID invoiceId) {
        return invoices.find(tenantId, invoiceId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That tenant has no such invoice"));
    }

    /** Issues an invoice for {@code amountMinor} of the tenant's own billing currency. */
    @Transactional
    public PrepaymentInvoice issue(UUID tenantId, long amountMinor, ActorRef actor, String correlationId) {
        if (amountMinor <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An invoice is for a positive amount");
        }
        Instant now = clock.instant();
        wallet.lockBilling(tenantId, now);
        PlatformBillingSettings bank = settings.find();
        if (!bank.configured()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "HorecaOS has not yet published the bank details an invoice is paid into",
                    Map.of("reason", "BANK_DETAILS_NOT_CONFIGURED"));
        }
        if (invoices.countOpen(tenantId, now) >= maxOpen) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "There are already %d invoices waiting to be paid; cancel one or pay one first".formatted(maxOpen),
                    Map.of("reason", "TOO_MANY_OPEN_INVOICES"));
        }
        UUID id = Ids.newId();
        String currency = wallet.currencyOf(tenantId);
        PrepaymentInvoice draft = new PrepaymentInvoice(
                id,
                tenantId,
                "pending",
                amountMinor,
                currency,
                now.plus(Duration.ofDays(validDays)),
                bank.beneficiary(),
                bank.bankName(),
                bank.account(),
                bank.mfo(),
                bank.taxId(),
                subject(actor),
                now,
                null,
                null,
                null,
                0);
        String number = invoices.insert(draft);
        audit.record(AuditFact.of("commercial.wallet.prepayment_invoice_issued", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.prepayment_invoice", id)
                .because("the tenant asked for an invoice to pay money in advance by bank transfer")
                .changed(ChangeDocuments.created(Map.of(
                        "number", number,
                        "amountMinor", amountMinor,
                        "currency", currency,
                        "validUntil", draft.validUntil().toString())))
                .usingCapability(Capability.COMMERCIAL_WALLET_TOPUP.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return invoices.find(tenantId, id).orElseThrow();
    }

    /**
     * Withdraws an invoice nothing has paid. One that has been paid, even in part, is not withdrawn:
     * the money is on the ledger, and a document that says it was never asked for would be false.
     *
     * @param capability the capability the caller was admitted under, so the trail says whether the
     *     tenant or HorecaOS finance withdrew it
     */
    @Transactional
    public PrepaymentInvoice cancel(
            UUID tenantId, UUID invoiceId, ActorRef actor, String reason, Capability capability, String correlationId) {
        Instant now = clock.instant();
        wallet.lockBilling(tenantId, now);
        PrepaymentInvoice invoice = find(tenantId, invoiceId);
        if (invoice.cancelledAt() != null) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That invoice is already cancelled");
        }
        if (invoice.paidMinor() > 0) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Money has been recorded against that invoice, so it can no longer be cancelled",
                    Map.of("reason", "INVOICE_HAS_PAYMENTS"));
        }
        invoices.cancel(tenantId, invoiceId, subject(actor), reason, now);
        audit.record(AuditFact.of("commercial.wallet.prepayment_invoice_cancelled", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.prepayment_invoice", invoiceId)
                .because(reason)
                .changed(ChangeDocuments.diff(
                        Map.of("number", invoice.number(), "cancelled", false),
                        Map.of("number", invoice.number(), "cancelled", true)))
                .usingCapability(capability.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return find(tenantId, invoiceId);
    }

    /**
     * Records a bank transfer that pays a named invoice: finance's one-person audited act, with the
     * invoice named on the ledger entry. Paid in part, in full or beyond the invoice are all accepted — a
     * wire is what arrived — and what exceeds the invoice is simply wallet credit.
     */
    @Transactional
    public UUID recordTransfer(
            UUID tenantId,
            String invoiceNumber,
            long amountMinor,
            String bankReference,
            ActorRef actor,
            String reason,
            String correlationId) {
        wallet.lockBilling(tenantId, clock.instant());
        PrepaymentInvoice invoice = invoices.findByNumber(tenantId, invoiceNumber.trim())
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That tenant has no invoice with that number"));
        if (invoice.cancelledAt() != null) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "That invoice was cancelled; record the transfer without naming it, or ask for a new invoice",
                    Map.of("reason", "INVOICE_CANCELLED"));
        }
        if (!invoice.currency().equals(wallet.currencyOf(tenantId))) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "That invoice is in %s and the wallet holds %s"
                            .formatted(invoice.currency(), wallet.currencyOf(tenantId)),
                    Map.of("reason", "INVOICE_CURRENCY_DIFFERS"));
        }
        return walletService.recordTransfer(
                tenantId, amountMinor, bankReference, invoice.id(), actor, reason, correlationId);
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }
}
