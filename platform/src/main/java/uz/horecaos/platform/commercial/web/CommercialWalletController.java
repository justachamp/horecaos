package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.application.WalletService;
import uz.horecaos.platform.commercial.application.WalletService.WalletChangeOutcome;
import uz.horecaos.platform.commercial.domain.BonusGrantBalance;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's wallet (ADR 0095): its two balances, its ledger, its live bonus
 * grants, each statement's paid and due amounts, and its payment method.
 *
 * <p>Reading is the tenant's own, under {@code commercial.wallet.read}, like
 * {@code CommercialStatementController}'s reads under {@code
 * commercial.usage.read}. Every write is HorecaOS staff's alone, under
 * {@code commercial.wallet.manage}: recording a transfer or a deposit is one
 * person's audited act; an adjustment, a bonus grant and a refund are
 * proposed under the same capability and wait for a <em>different</em>
 * holder of it to approve them through the ADR 0027 approval console — the
 * first call answers {@code AWAITING_APPROVAL}, and the identical call again
 * after approval performs the change, exactly as {@code
 * TenantProfileController#changeCountry} does for a change of country.
 */
@RestController
@Tag(
        name = "Commercial wallet",
        description = "A tenant's paid and bonus balances, its ledger, and how it is collected")
public class CommercialWalletController {

    private final WalletService wallet;
    private final CurrentActor currentActor;

    public CommercialWalletController(WalletService wallet, CurrentActor currentActor) {
        this.wallet = wallet;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------------- reads

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/wallet")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "A tenant's wallet: both balances and its payment method",
            description = "Each balance is the SUM of the tenant's own ledger entries; no balance is stored.")
    public ResponseEntity<WalletOverviewView> overview(@PathVariable UUID tenantId) {
        WalletBalances balances = wallet.balances(tenantId);
        TenantBilling billing = wallet.billing(tenantId);
        return ResponseEntity.ok(new WalletOverviewView(
                ApiMoney.of(balances.paidMinor(), balances.currency()),
                ApiMoney.of(balances.bonusMinor(), balances.currency()),
                billing.paymentMethod().name(),
                billing.cardTokenReference()));
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/wallet/ledger")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's ledger, newest first",
            description = "Append-only and never edited or deleted (ADR 0095). Cursor-paginated: pass the "
                    + "previous page's nextCursor back as cursor.")
    public ResponseEntity<Page<WalletEntryView>> ledger(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(required = false) Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        List<WalletEntry> entries = wallet.ledger(tenantId, cursor, pageSize);
        String nextCursor = entries.size() == pageSize ? entries.getLast().id().toString() : null;
        return ResponseEntity.ok(
                new Page<>(entries.stream().map(WalletEntryView::of).toList(), nextCursor));
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/wallet/grants")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Live bonus grants with an unspent remainder",
            description = "Earliest expiry first — the order a statement is paid from bonus money in.")
    public ResponseEntity<List<BonusGrantView>> grants(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                wallet.liveGrants(tenantId).stream().map(BonusGrantView::of).toList());
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/wallet/statements")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every issued statement's paid and due amounts",
            description = "Derived from the ledger; issued statements carry no payment columns of their own "
                    + "(ADR 0088, ADR 0095). Newest month first.")
    public ResponseEntity<List<StatementPaymentView>> statementPayments(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(wallet.statementPayments(tenantId).stream()
                .map(StatementPaymentView::of)
                .toList());
    }

    // ------------------------------------------------------------- money in

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/wallet/transfers")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Record a bank transfer HorecaOS finance received",
            description = "One person's audited act, like issuing a statement — not a correction. Pays the "
                    + "oldest open statement at once.")
    public ResponseEntity<WalletEntryRecorded> recordTransfer(
            @PathVariable UUID tenantId, @Valid @RequestBody RecordTransferRequest body) {
        UUID id = wallet.recordTransfer(
                tenantId, body.amountMinor(), body.bankReference(), actor(), body.reason(), correlationId());
        return ResponseEntity.ok(new WalletEntryRecorded(id));
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/wallet/deposit")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Record the tenant's activation deposit as paid",
            description = "Pays exactly what the live subscription's deposit_due_minor names; refused when "
                    + "nothing is due. The first statement is then paid from it, like any other paid money "
                    + "(ADR 0093, ADR 0095).")
    public ResponseEntity<WalletEntryRecorded> recordDeposit(
            @PathVariable UUID tenantId, @Valid @RequestBody RecordDepositRequest body) {
        UUID id = wallet.recordDeposit(tenantId, body.bankReference(), actor(), body.reason(), correlationId());
        return ResponseEntity.ok(new WalletEntryRecorded(id));
    }

    // -------------------------------------------------- manual changes: two people

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/wallet/adjustments")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Propose a correction to the tenant's wallet",
            description = "Either money kind, up or down; a correction of bonus money names the grant it "
                    + "corrects, and one of paid money names none. Neither may take a balance below zero. "
                    + "Needs a second signature: the first call answers AWAITING_APPROVAL, and the identical "
                    + "call again after a different person approves it applies the correction.")
    public ResponseEntity<WalletChangeResponse> proposeAdjustment(
            @PathVariable UUID tenantId, @Valid @RequestBody AdjustmentRequest body) {
        WalletChangeOutcome outcome = wallet.proposeAdjustment(
                tenantId,
                body.moneyKind(),
                body.grantId(),
                body.amountMinor(),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.ok(WalletChangeResponse.of(outcome));
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/wallet/bonus-grants")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Propose a bonus money grant, with the date it lapses on",
            description = "Needs a second signature, the same way a correction does. Spent bonus-first, the "
                    + "grant expiring soonest first, when a statement is paid.")
    public ResponseEntity<WalletChangeResponse> proposeBonusGrant(
            @PathVariable UUID tenantId, @Valid @RequestBody BonusGrantRequest body) {
        WalletChangeOutcome outcome = wallet.proposeBonusGrant(
                tenantId, body.amountMinor(), parseInstant(body.expiresAt()), actor(), body.reason(), correlationId());
        return ResponseEntity.ok(WalletChangeResponse.of(outcome));
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/wallet/refunds")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Propose a refund of paid money, naming the payout",
            description = "Needs a second signature. Refused when it would take the paid balance below zero. "
                    + "Bonus money is never refunded — it lapses, and it was never the tenant's to begin with.")
    public ResponseEntity<WalletChangeResponse> proposeRefund(
            @PathVariable UUID tenantId, @Valid @RequestBody RefundRequest body) {
        WalletChangeOutcome outcome = wallet.proposeRefund(
                tenantId, body.amountMinor(), body.payoutReference(), actor(), body.reason(), correlationId());
        return ResponseEntity.ok(WalletChangeResponse.of(outcome));
    }

    // ---------------------------------------------------------- payment method

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/wallet/payment-method")
    @RequiresCapability(value = Capability.COMMERCIAL_WALLET_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Change how a tenant is collected",
            description = "INVOICE waits for a bank transfer, WALLET waits for a top-up, CARD is charged "
                    + "automatically once a merchant account exists — until then a CARD tenant's remainder "
                    + "stays due, exactly like INVOICE. Staff-changeable with a reason; audited.")
    public ResponseEntity<Void> setPaymentMethod(
            @PathVariable UUID tenantId, @Valid @RequestBody PaymentMethodRequest body) {
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(body.paymentMethod());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown payment method %s".formatted(body.paymentMethod()));
        }
        wallet.setPaymentMethod(tenantId, method, body.cardTokenReference(), actor(), body.reason(), correlationId());
        return ResponseEntity.noContent().build();
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

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException malformed) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "expiresAt is UTC ISO-8601, such as 2027-01-01T00:00:00Z");
        }
    }

    // --------------------------------------------------------------- wire records

    public record WalletOverviewView(
            ApiMoney paidBalance,
            ApiMoney bonusBalance,
            String paymentMethod,
            @Nullable String cardTokenReference) {}

    public record WalletEntryView(
            UUID entryId,
            String moneyKind,
            String entryType,
            ApiMoney amount,
            @Nullable UUID statementId,
            @Nullable UUID grantId,
            @Nullable String expiresAt,
            @Nullable String externalReference,
            String reason,
            String recordedBy,
            @Nullable String approvedBy,
            String createdAt) {

        static WalletEntryView of(WalletEntry entry) {
            return new WalletEntryView(
                    entry.id(),
                    entry.moneyKind(),
                    entry.entryType(),
                    ApiMoney.of(entry.amountMinor(), entry.currency()),
                    entry.statementId(),
                    entry.grantId(),
                    text(entry.expiresAt()),
                    entry.externalReference(),
                    entry.reason(),
                    entry.recordedBy(),
                    entry.approvedBy(),
                    entry.createdAt().toString());
        }
    }

    public record BonusGrantView(UUID grantId, ApiMoney granted, ApiMoney remaining, String expiresAt, String reason) {
        static BonusGrantView of(BonusGrantBalance grant) {
            return new BonusGrantView(
                    grant.grantId(),
                    ApiMoney.of(grant.grantedMinor(), grant.currency()),
                    ApiMoney.of(grant.remainingMinor(), grant.currency()),
                    grant.expiresAt().toString(),
                    grant.reason());
        }
    }

    public record StatementPaymentView(
            UUID statementId, String number, String periodKey, ApiMoney total, ApiMoney paid, ApiMoney due) {
        static StatementPaymentView of(StatementPayment payment) {
            return new StatementPaymentView(
                    payment.statementId(),
                    payment.number(),
                    payment.periodKey(),
                    ApiMoney.of(payment.totalMinor(), payment.currency()),
                    ApiMoney.of(payment.paidMinor(), payment.currency()),
                    ApiMoney.of(payment.dueMinor(), payment.currency()));
        }
    }

    /** The id a wallet entry was filed under. */
    public record WalletEntryRecorded(UUID entryId) {}

    /** What a manual change did. */
    public record WalletChangeResponse(
            String status, @Nullable UUID approvalRequestId) {
        static WalletChangeResponse of(WalletChangeOutcome outcome) {
            return new WalletChangeResponse(outcome.status(), outcome.approvalRequestId());
        }
    }

    private static @Nullable String text(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    public record RecordTransferRequest(
            @Min(1) long amountMinor,
            @NotBlank @Size(max = 128) String bankReference,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RecordDepositRequest(
            @NotBlank @Size(max = 128) String bankReference,
            @NotBlank @Size(max = 1000) String reason) {}

    /** {@code grantId} names the bonus grant corrected; required for BONUS and refused for PAID. */
    public record AdjustmentRequest(
            @NotBlank String moneyKind,
            @Nullable UUID grantId,
            long amountMinor,
            @NotBlank @Size(max = 1000) String reason) {}

    public record BonusGrantRequest(
            @Min(1) long amountMinor,
            @NotNull @Size(max = 40) String expiresAt,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RefundRequest(
            @Min(1) long amountMinor,
            @NotBlank @Size(max = 128) String payoutReference,
            @NotBlank @Size(max = 1000) String reason) {}

    public record PaymentMethodRequest(
            @NotBlank String paymentMethod,
            @Nullable @Size(max = 128) String cardTokenReference,
            @NotBlank @Size(max = 1000) String reason) {}
}
