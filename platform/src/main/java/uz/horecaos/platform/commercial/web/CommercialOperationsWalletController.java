package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.application.CardEnrolment;
import uz.horecaos.platform.commercial.application.CardOnFileService;
import uz.horecaos.platform.commercial.application.CardTopUpService;
import uz.horecaos.platform.commercial.application.PlatformBillingSettingsService;
import uz.horecaos.platform.commercial.application.PrepaymentInvoiceService;
import uz.horecaos.platform.commercial.application.WalletService;
import uz.horecaos.platform.commercial.domain.CardOnFile;
import uz.horecaos.platform.commercial.domain.CardTopUp;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.PlatformBillingSettings;
import uz.horecaos.platform.commercial.domain.PrepaymentInvoice;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.PlatformCardGateway;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The merchant's own wallet and the ways to put money into it (ADR 0095): Finance 8/X.3.
 *
 * <p>{@code CommercialWalletController} serves the same ledger to HorecaOS staff at {@code
 * /api/v1/control-plane}, which the operations console's OpenAPI group cannot reach (ADR 0057). This is the
 * tenant's own door, at the path that console can reach, and it adds what only a tenant can do: keep a card
 * on file, top the wallet up from it, choose how it is collected, and ask for an invoice to pay by bank
 * transfer.
 *
 * <p>Reading is {@code commercial.wallet.read}; putting money in is {@code commercial.wallet.topup}; the
 * card and how it is collected are {@code commercial.card.manage}. None of it lets a tenant decide what it
 * owes or is owed: corrections, bonus grants, refunds and recording a bank transfer stay HorecaOS staff's,
 * and a tenant cannot credit itself without paying.
 *
 * <p>The card number never reaches this API. A tenant types it into the provider's own form and sends
 * back what that form returned (see {@link CardEnrolment}). The token reference HorecaOS stores is never
 * returned either: only the last four digits, the brand and the expiry.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/commercial/wallet")
@Tag(name = "Commercial wallet (merchant)", description = "The merchant's own wallet, card and prepayment invoices")
public class CommercialOperationsWalletController {

    /** Non-PII: a cancellation from this screen is the tenant's own act, like ending a module it bought. */
    private static final String SELF_SERVICE_CANCEL_REASON = "Cancelled from the operations console";

    private final WalletService wallet;
    private final CardOnFileService cardOnFile;
    private final CardTopUpService topUps;
    private final PrepaymentInvoiceService invoices;
    private final PlatformBillingSettingsService billingSettings;
    private final PlatformCardGateway cardGateway;
    private final CurrentActor currentActor;
    private final Clock clock;
    private final Duration expiryWarning;

    public CommercialOperationsWalletController(
            WalletService wallet,
            CardOnFileService cardOnFile,
            CardTopUpService topUps,
            PrepaymentInvoiceService invoices,
            PlatformBillingSettingsService billingSettings,
            PlatformCardGateway cardGateway,
            CurrentActor currentActor,
            Clock clock,
            @Value("${horecaos.commercial.wallet.expiry-warning-days:14}") int expiryWarningDays) {
        this.wallet = wallet;
        this.cardOnFile = cardOnFile;
        this.topUps = topUps;
        this.invoices = invoices;
        this.billingSettings = billingSettings;
        this.cardGateway = cardGateway;
        this.currentActor = currentActor;
        this.clock = clock;
        this.expiryWarning = Duration.ofDays(expiryWarningDays);
    }

    // ------------------------------------------------------------------- reads

    @GetMapping
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's wallet: both balances, the card on file and what is about to lapse",
            description = "Each balance is the SUM of the tenant's own ledger entries; none is stored. "
                    + "bonusSpendableBalance is what a statement could draw on now. lapsingGrants lists the bonus "
                    + "grants that lapse within the warning window with something left in them, soonest first: "
                    + "credit the tenant is about to lose. The card is described by its last four digits, brand "
                    + "and expiry only; its token reference is never returned.")
    public ResponseEntity<TenantWalletView> overview(@PathVariable UUID tenantId) {
        Instant now = clock.instant();
        WalletBalances balances = wallet.balances(tenantId);
        TenantBilling billing = wallet.billing(tenantId);
        Optional<CardOnFile> card = wallet.cardOnFile(tenantId);
        String currency = balances.currency();
        return ResponseEntity.ok(new TenantWalletView(
                ApiMoney.of(balances.paidMinor(), currency),
                ApiMoney.of(balances.bonusMinor(), currency),
                ApiMoney.of(wallet.spendableBonusMinor(tenantId), currency),
                billing.paymentMethod().name(),
                card.map(found -> CardOnFileView.of(found, now, expiryWarning)).orElse(null),
                wallet.grantsLapsingWithin(tenantId, expiryWarning).stream()
                        .map(grant -> new LapsingGrantView(
                                grant.grantId(),
                                ApiMoney.of(grant.remainingMinor(), grant.currency()),
                                grant.expiresAt().toString()))
                        .toList(),
                topUps.recent(tenantId, 1).stream()
                        .filter(top -> CardTopUp.PENDING.equals(top.outcome()))
                        .findFirst()
                        .map(TopUpView::of)
                        .orElse(null),
                cardGateway.configured(),
                billingSettings.current().configured()));
    }

    @GetMapping("/ledger")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's ledger, newest first",
            description = "Append-only and never edited or deleted. Cursor-paginated: pass the previous page's "
                    + "nextCursor back as cursor.")
    public ResponseEntity<Page<LedgerEntryView>> ledger(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        List<WalletEntry> entries = wallet.ledger(tenantId, cursor, pageSize);
        String nextCursor = entries.size() == pageSize ? entries.getLast().id().toString() : null;
        return ResponseEntity.ok(
                new Page<>(entries.stream().map(LedgerEntryView::of).toList(), nextCursor));
    }

    @GetMapping("/statements")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every issued statement's paid and due amounts, newest month first",
            description = "Derived from the ledger; an issued statement carries no payment columns of its own.")
    public ResponseEntity<List<StatementPaymentView>> statements(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(wallet.statementPayments(tenantId).stream()
                .map(payment -> new StatementPaymentView(
                        payment.statementId(),
                        payment.number(),
                        payment.periodKey(),
                        ApiMoney.of(payment.totalMinor(), payment.currency()),
                        ApiMoney.of(payment.paidMinor(), payment.currency()),
                        ApiMoney.of(payment.dueMinor(), payment.currency())))
                .toList());
    }

    @GetMapping("/payment-details")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Where to send a bank transfer to HorecaOS",
            description = "The bank details every invoice carries. While HorecaOS finance has not replaced the "
                    + "placeholder, configured is false and the values are the placeholder text, which no "
                    + "screen should present as an account.")
    public ResponseEntity<PaymentDetailsView> paymentDetails(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(PaymentDetailsView.of(billingSettings.current()));
    }

    @GetMapping("/top-ups")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(summary = "The tenant's recent card top-ups, newest first")
    public ResponseEntity<List<TopUpView>> recentTopUps(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                topUps.recent(tenantId, 50).stream().map(TopUpView::of).toList());
    }

    @GetMapping("/invoices")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's prepayment invoices, newest first",
            description = "A request for payment of money to be held in the wallet, before tax. How much each "
                    + "has been paid is the sum of the ledger entries that name it.")
    public ResponseEntity<List<PrepaymentInvoiceView>> listInvoices(@PathVariable UUID tenantId) {
        Instant now = clock.instant();
        return ResponseEntity.ok(invoices.list(tenantId).stream()
                .map(invoice -> PrepaymentInvoiceView.of(invoice, now))
                .toList());
    }

    @GetMapping("/invoices/{invoiceId}")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(summary = "One prepayment invoice")
    public ResponseEntity<PrepaymentInvoiceView> oneInvoice(@PathVariable UUID tenantId, @PathVariable UUID invoiceId) {
        return ResponseEntity.ok(PrepaymentInvoiceView.of(invoices.find(tenantId, invoiceId), clock.instant()));
    }

    @GetMapping(path = "/invoices/{invoiceId}/export", produces = "text/csv")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One prepayment invoice as CSV",
            description = "For the accounting system a payment is made from. Amounts are integer minor units.")
    public ResponseEntity<String> exportInvoice(@PathVariable UUID tenantId, @PathVariable UUID invoiceId) {
        PrepaymentInvoice invoice = invoices.find(tenantId, invoiceId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("invoice-" + invoice.number() + ".csv")
                                .build()
                                .toString())
                .body(csv(invoice, clock.instant()));
    }

    // ----------------------------------------------------- the card on file

    @PostMapping("/card/enrolments")
    @RequiresCapability(value = Capability.COMMERCIAL_CARD_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Start putting a card on file",
            description = "Opens a session with the card provider. The answer is what the browser needs to show "
                    + "the provider's own form; the card number is typed there and never reaches HorecaOS. "
                    + "Refused with CARDS_NOT_AVAILABLE while no merchant account is connected.")
    public ResponseEntity<EnrolmentStartedView> beginEnrolment(@PathVariable UUID tenantId) {
        CardEnrolment.BeginOutcome.Begun begun = cardOnFile.beginEnrolment(tenantId);
        return ResponseEntity.ok(new EnrolmentStartedView(
                begun.sessionReference(),
                begun.hostedFormUrl(),
                begun.clientParameters(),
                begun.expiresAt().toString()));
    }

    @PostMapping("/card/confirmations")
    @RequiresCapability(value = Capability.COMMERCIAL_CARD_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Finish putting a card on file",
            description = "Sends the provider the token its form returned and the code the cardholder's bank "
                    + "texted. The card replaces any other on file. How the tenant is collected does not change: "
                    + "a card to top up with is not consent to be charged for every statement.")
    public ResponseEntity<CardOnFileView> confirmEnrolment(
            @PathVariable UUID tenantId, @Valid @RequestBody ConfirmCardRequest body) {
        CardOnFile card = cardOnFile.confirmEnrolment(
                tenantId,
                body.sessionReference(),
                body.providerToken(),
                body.verificationCode(),
                actor(),
                correlationId());
        return ResponseEntity.ok(CardOnFileView.of(card, clock.instant(), expiryWarning));
    }

    @PostMapping("/card/removal")
    @RequiresCapability(value = Capability.COMMERCIAL_CARD_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Take the card off file",
            description = "A tenant collected by CARD becomes INVOICE in the same step, and the answer says so. "
                    + "Refused while a card top-up is still waiting for the provider.")
    public ResponseEntity<CardRemovedView> removeCard(@PathVariable UUID tenantId) {
        CardOnFileService.RemovedCard removed = cardOnFile.removeCard(tenantId, actor(), correlationId());
        return ResponseEntity.ok(new CardRemovedView(removed.paymentMethod().name()));
    }

    @PostMapping("/payment-method")
    @RequiresCapability(value = Capability.COMMERCIAL_CARD_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Choose how HorecaOS collects what the wallet does not cover",
            description = "INVOICE waits for a bank transfer, WALLET waits for a top-up, CARD charges the card on "
                    + "file for each statement's remainder, which is the tenant's own consent to recurring charges "
                    + "and needs a card on file. Choosing CARD collects anything already due at once.")
    public ResponseEntity<PaymentMethodView> choosePaymentMethod(
            @PathVariable UUID tenantId, @Valid @RequestBody ChoosePaymentMethodRequest body) {
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(body.paymentMethod());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown payment method %s".formatted(body.paymentMethod()));
        }
        cardOnFile.chooseMethod(tenantId, method, actor(), correlationId());
        if (method == PaymentMethod.CARD) {
            // Once the choice is committed, never inside its transaction (ADR 0095).
            wallet.settleCardRemainders(tenantId);
        }
        return ResponseEntity.ok(new PaymentMethodView(method.name()));
    }

    // --------------------------------------------------------- money in

    @PostMapping("/top-ups")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_TOPUP, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Top the wallet up from the card on file",
            description = "Charges the card and, once the provider has answered, credits the wallet and pays what "
                    + "is owed from it. outcome is SUCCEEDED, FAILED (reason says why), NOT_CONFIGURED, or PENDING "
                    + "when the provider has not answered yet and the platform will resolve it on its own — the "
                    + "tenant is never charged twice for it.")
    public ResponseEntity<TopUpView> topUp(@PathVariable UUID tenantId, @Valid @RequestBody TopUpRequest body) {
        return ResponseEntity.ok(
                TopUpView.of(topUps.requestTopUp(tenantId, body.amountMinor(), actor(), correlationId())));
    }

    @PostMapping("/invoices")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_TOPUP, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Ask for an invoice to pay money in advance by bank transfer",
            description = "Frozen at issue with the bank details of that moment. A request for payment, not a tax "
                    + "invoice: every figure is before tax. Refused with BANK_DETAILS_NOT_CONFIGURED until HorecaOS "
                    + "finance has replaced the placeholder bank details.")
    public ResponseEntity<PrepaymentInvoiceView> issueInvoice(
            @PathVariable UUID tenantId, @Valid @RequestBody TopUpRequest body) {
        PrepaymentInvoice issued = invoices.issue(tenantId, body.amountMinor(), actor(), correlationId());
        return ResponseEntity.ok(PrepaymentInvoiceView.of(issued, clock.instant()));
    }

    @PostMapping("/invoices/{invoiceId}/cancel")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_TOPUP, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Withdraw an invoice nothing has paid",
            description = "Refused once any money has been recorded against it.")
    public ResponseEntity<PrepaymentInvoiceView> cancelInvoice(
            @PathVariable UUID tenantId, @PathVariable UUID invoiceId) {
        PrepaymentInvoice cancelled = invoices.cancel(
                tenantId,
                invoiceId,
                actor(),
                SELF_SERVICE_CANCEL_REASON,
                Capability.COMMERCIAL_WALLET_TOPUP,
                correlationId());
        return ResponseEntity.ok(PrepaymentInvoiceView.of(cancelled, clock.instant()));
    }

    // ------------------------------------------------------------------ helpers

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    /** One header row and one value row, the way a statement is exported. */
    static String csv(PrepaymentInvoice invoice, Instant now) {
        return String.join(
                        "\r\n",
                        "number,status,currency,amount_minor,paid_minor,due_minor,valid_until,issued_at,"
                                + "beneficiary,bank,account,mfo,tax_id",
                        String.join(
                                ",",
                                field(invoice.number()),
                                field(invoice.statusAt(now)),
                                field(invoice.currency()),
                                Long.toString(invoice.amountMinor()),
                                Long.toString(invoice.paidMinor()),
                                Long.toString(invoice.dueMinor()),
                                field(invoice.validUntil().toString()),
                                field(invoice.issuedAt().toString()),
                                field(invoice.bankBeneficiary()),
                                field(invoice.bankName()),
                                field(invoice.bankAccount()),
                                field(invoice.bankMfo()),
                                field(invoice.bankTaxId())))
                + "\r\n";
    }

    /** Quoted, quotes doubled, and a leading formula character neutralised, so a spreadsheet never runs a cell. */
    private static String field(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    // --------------------------------------------------------------- wire records

    public record TenantWalletView(
            ApiMoney paidBalance,
            ApiMoney bonusBalance,
            ApiMoney bonusSpendableBalance,
            String paymentMethod,
            @Nullable CardOnFileView card,
            List<LapsingGrantView> lapsingGrants,
            @Nullable TopUpView pendingTopUp,
            boolean cardPaymentsAvailable,
            boolean bankTransferAvailable) {}

    /** Which card, never which reference. {@code lapsed} and {@code lapsesSoon} are read from the expiry, so a screen need not. */
    public record CardOnFileView(
            @Nullable String last4,
            @Nullable String brand,
            @Nullable Integer expiryMonth,
            @Nullable Integer expiryYear,
            boolean lapsed,
            boolean lapsesSoon,
            @Nullable String boundAt) {

        static CardOnFileView of(CardOnFile card, Instant now, Duration warning) {
            Instant horizon = now.plus(warning);
            return new CardOnFileView(
                    card.last4(),
                    card.brand(),
                    card.expiryMonth(),
                    card.expiryYear(),
                    card.lapsedBy(now),
                    card.lapsesBefore(now, horizon),
                    card.boundAt() == null ? null : card.boundAt().toString());
        }
    }

    public record LapsingGrantView(UUID grantId, ApiMoney remaining, String expiresAt) {}

    public record LedgerEntryView(
            UUID entryId,
            String moneyKind,
            String entryType,
            ApiMoney amount,
            @Nullable UUID statementId,
            @Nullable UUID grantId,
            @Nullable String expiresAt,
            String reason,
            String createdAt) {

        static LedgerEntryView of(WalletEntry entry) {
            return new LedgerEntryView(
                    entry.id(),
                    entry.moneyKind(),
                    entry.entryType(),
                    ApiMoney.of(entry.amountMinor(), entry.currency()),
                    entry.statementId(),
                    entry.grantId(),
                    entry.expiresAt() == null ? null : entry.expiresAt().toString(),
                    entry.reason(),
                    entry.createdAt().toString());
        }
    }

    public record StatementPaymentView(
            UUID statementId, String number, String periodKey, ApiMoney total, ApiMoney paid, ApiMoney due) {}

    public record PaymentDetailsView(
            boolean configured, String beneficiary, String bankName, String account, String mfo, String taxId) {

        static PaymentDetailsView of(PlatformBillingSettings settings) {
            return new PaymentDetailsView(
                    settings.configured(),
                    settings.beneficiary(),
                    settings.bankName(),
                    settings.account(),
                    settings.mfo(),
                    settings.taxId());
        }
    }

    public record TopUpView(
            UUID topUpId,
            ApiMoney amount,
            String outcome,
            @Nullable String reason,
            @Nullable UUID walletEntryId,
            String requestedAt,
            @Nullable String settledAt) {

        static TopUpView of(CardTopUp topUp) {
            // The provider's reference is shown only for what it is — a decline's reason code. On a success it
            // is the ledger entry's own reference and a tenant has no use for it here.
            return new TopUpView(
                    topUp.id(),
                    ApiMoney.of(topUp.amountMinor(), topUp.currency()),
                    topUp.outcome(),
                    CardTopUp.FAILED.equals(topUp.outcome()) ? topUp.providerDetail() : null,
                    topUp.walletEntryId(),
                    topUp.requestedAt().toString(),
                    topUp.settledAt() == null ? null : topUp.settledAt().toString());
        }
    }

    public record PrepaymentInvoiceView(
            UUID invoiceId,
            String number,
            String status,
            ApiMoney amount,
            ApiMoney paid,
            ApiMoney due,
            String validUntil,
            String issuedAt,
            @Nullable String cancelledAt,
            PaymentDetailsView paymentDetails,
            String paymentPurpose,
            boolean beforeTax) {

        static PrepaymentInvoiceView of(PrepaymentInvoice invoice, Instant now) {
            return new PrepaymentInvoiceView(
                    invoice.id(),
                    invoice.number(),
                    invoice.statusAt(now),
                    ApiMoney.of(invoice.amountMinor(), invoice.currency()),
                    ApiMoney.of(invoice.paidMinor(), invoice.currency()),
                    ApiMoney.of(invoice.dueMinor(), invoice.currency()),
                    invoice.validUntil().toString(),
                    invoice.issuedAt().toString(),
                    invoice.cancelledAt() == null ? null : invoice.cancelledAt().toString(),
                    new PaymentDetailsView(
                            true,
                            invoice.bankBeneficiary(),
                            invoice.bankName(),
                            invoice.bankAccount(),
                            invoice.bankMfo(),
                            invoice.bankTaxId()),
                    // What the tenant writes in the payment's purpose so finance can tell whose money arrived.
                    invoice.number(),
                    true);
        }
    }

    public record EnrolmentStartedView(
            String sessionReference,
            @Nullable String hostedFormUrl,
            Map<String, String> clientParameters,
            String expiresAt) {}

    public record CardRemovedView(String paymentMethod) {}

    public record PaymentMethodView(String paymentMethod) {}

    public record ConfirmCardRequest(
            @NotBlank @Size(max = 256) String sessionReference,
            @NotBlank @Size(max = 512) String providerToken,
            @NotBlank @Size(max = 16) String verificationCode) {}

    public record ChoosePaymentMethodRequest(
            @NotBlank @Size(max = 16) String paymentMethod) {}

    /** Boxed, so a body that omits the amount is a validation answer and not a malformed-body one. */
    public record TopUpRequest(@NotNull @Positive Long amountMinor) {}
}
