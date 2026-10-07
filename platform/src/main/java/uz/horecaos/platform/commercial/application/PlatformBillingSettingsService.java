package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.commercial.application.WalletService.WalletChangeOutcome;
import uz.horecaos.platform.commercial.domain.PlatformBillingSettings;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlatformBillingSettingsStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Where an invoice tells a tenant to send money (ADR 0095, the open input "the bank details an invoice
 * shows", closed on its default: a platform setting with a placeholder the control plane fills).
 *
 * <p>The setting starts as a placeholder, and invoices are refused until it is replaced. Replacing it is
 * proposed by one person and approved by a different one through the approval model (ADR 0027), raised at
 * {@code PLATFORM} scope like every other decision HorecaOS takes about money: the first call answers
 * {@code AWAITING_APPROVAL}, and the identical call again after approval writes the details and spends the
 * signature. The checker is shown the whole proposal, because the account number <em>is</em> what is being
 * signed; only the maker's reason is withheld (ADR 0029).
 *
 * <p>Already-issued invoices never change: each carries the details of its own moment.
 */
@Service
public class PlatformBillingSettingsService {

    private static final Pattern ACCOUNT = Pattern.compile("[0-9A-Za-z ]{5,64}");
    private static final Pattern CODE = Pattern.compile("[0-9A-Za-z]{3,32}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} .,'\"&()/\\-]{2,255}");

    private final JdbcPlatformBillingSettingsStore settings;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;

    public PlatformBillingSettingsService(
            JdbcPlatformBillingSettingsStore settings, ApprovalService approvals, AuditRecorder audit, Clock clock) {
        this.settings = settings;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    /** What an invoice would say now; the placeholder, flagged as one, until finance fills it. */
    @Transactional(readOnly = true)
    public PlatformBillingSettings current() {
        return settings.find();
    }

    /** Proposes the bank details every invoice will carry from now on. Nothing changes until a different person approves. */
    @Transactional
    public WalletChangeOutcome proposeBankDetails(
            BankDetails details, ActorRef actor, String reason, String correlationId) {
        BankDetails clean = validated(details);
        ApprovalParameters.Signed signed = ApprovalParameters.of(new BankDetailsCommand(
                        clean.beneficiary(), clean.bankName(), clean.account(), clean.mfo(), clean.taxId(), reason))
                .excluding()
                .withholding("reason")
                .sign();
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.BILLING_BANK_DETAILS.code(),
                signed.hash(),
                ResourceScope.platform(),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY,
                signed.subject()));

        if (approval instanceof ApprovalOutcome.Declined declined) {
            return new WalletChangeOutcome(WalletChangeOutcome.DECLINED, declined.requestId());
        }
        if (!approval.mayProceed()) {
            UUID requestId = approval instanceof ApprovalOutcome.Pending pending ? pending.requestId() : null;
            return new WalletChangeOutcome(WalletChangeOutcome.AWAITING_APPROVAL, requestId);
        }
        if (!(approval instanceof ApprovalOutcome.Approved approved)) {
            throw new ApiException(
                    ErrorCode.APPROVAL_POLICY_REQUIRED, "Bank details change only with a second person's approval");
        }

        PlatformBillingSettings before = settings.lock();
        approval.consume();
        Instant now = clock.instant();
        settings.configure(
                new PlatformBillingSettings(
                        clean.beneficiary(),
                        clean.bankName(),
                        clean.account(),
                        clean.mfo(),
                        clean.taxId(),
                        true,
                        before.version() + 1,
                        approved.requestedBy(),
                        now,
                        approved.approvedBy()),
                approved.requestedBy(),
                approved.approvedBy(),
                approved.requestId(),
                now);
        audit.record(AuditFact.of("commercial.billing.bank_details_changed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target(
                        "commercial.platform_billing_settings",
                        UUID.nameUUIDFromBytes(
                                "platform_billing_settings".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .because(reason)
                .changed(ChangeDocuments.diff(display(before), display(clean, true)))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .underApproval(approved.requestId())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return new WalletChangeOutcome(WalletChangeOutcome.CHANGED, approved.requestId());
    }

    private static Map<String, Object> display(PlatformBillingSettings settings) {
        return display(
                new BankDetails(
                        settings.beneficiary(),
                        settings.bankName(),
                        settings.account(),
                        settings.mfo(),
                        settings.taxId()),
                settings.configured());
    }

    private static Map<String, Object> display(BankDetails details, boolean configured) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("bankBeneficiary", details.beneficiary());
        shown.put("bankName", details.bankName());
        shown.put("bankAccount", details.account());
        shown.put("bankMfo", details.mfo());
        shown.put("bankTaxId", details.taxId());
        shown.put("detailsConfigured", configured);
        return shown;
    }

    private static BankDetails validated(@Nullable BankDetails details) {
        if (details == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bank details are required");
        }
        String beneficiary = field(details.beneficiary(), NAME, "beneficiary");
        String bankName = field(details.bankName(), NAME, "bankName");
        String account = field(details.account(), ACCOUNT, "account");
        String mfo = field(details.mfo(), CODE, "mfo");
        String taxId = field(details.taxId(), CODE, "taxId");
        return new BankDetails(beneficiary, bankName, account, mfo, taxId);
    }

    private static String field(@Nullable String value, Pattern shape, String name) {
        String trimmed = value == null ? "" : value.trim();
        // A placeholder starts with a bracket, and a real value never does: refusing it here is what
        // stops "[account: set by HorecaOS finance]" being proposed back as the details themselves.
        if (!shape.matcher(trimmed).matches() || trimmed.startsWith("[")) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s is not a valid value for this field".formatted(name),
                    Map.of("field", name));
        }
        return trimmed;
    }

    /** The five things an invoice prints about where to pay. */
    public record BankDetails(String beneficiary, String bankName, String account, String mfo, String taxId) {}

    private record BankDetailsCommand(
            String beneficiary, String bankName, String account, String mfo, String taxId, String reason) {}
}
