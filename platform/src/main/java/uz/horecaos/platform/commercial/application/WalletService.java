package uz.horecaos.platform.commercial.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.domain.BonusGrantBalance;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardChargeAttemptStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore.ExpiredGrantRef;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant's wallet: an append-only ledger that keeps paid money apart from
 * bonus money HorecaOS grants (ADR 0095).
 *
 * <p>A balance is the SUM of a tenant's own entries, of one money kind; none
 * is stored beside them (see CLAUDE.md on the loyalty ledger's defects, all
 * of which lived exactly in that gap). Every mutation here is one INSERT.
 *
 * <p><strong>Locking.</strong> The ledger never takes a row lock: there is no
 * balance column on it to protect, only entries a SUM reads, and a row a
 * concurrent reader might lock does not exist until this transaction commits
 * it. Instead, every method that moves money starts by calling
 * {@link JdbcWalletStore#lockBilling}, which takes {@code FOR UPDATE} on the
 * tenant's {@code commercial.tenant_billing} row — a row that has UPDATE
 * granted to the application already, for the payment-method change this
 * class also performs. That serialises every wallet mutation for one tenant
 * onto one writer at a time, without ever locking the immutable ledger
 * itself.
 *
 * <p><strong>Manual changes.</strong> An adjustment, a bonus grant, a refund
 * and the reversal of a deposit recorded in error are proposed by one person
 * and approved by a different one, through the same ADR 0027 approval model
 * {@code TenantProfileService.changeCountry} uses for a change of country:
 * the first call raises the request and answers {@code AWAITING_APPROVAL};
 * the identical call again, after a different person approves it, performs
 * the change and spends the signature. Recording a transfer or a deposit is
 * not a correction — it is one person's audited act, like {@code
 * StatementService.issue}.
 *
 * <p><strong>Why those requests are raised at {@code PLATFORM} scope.</strong>
 * They concern a tenant's wallet but they are HorecaOS's own decisions, taken
 * under a capability no tenant role holds and against policies V0211 seeds at
 * platform scope. Raised at tenant scope they landed in the tenant's own
 * approvals worklist, where its finance manager read "HorecaOS proposes a
 * refund of your paid money" — with the maker's name on it — before anything
 * had moved and for a decision they could never sign. At platform scope they
 * are listed and decided only on the platform approvals queue.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    /** How a card charge ended, counted so a provider outage is a rate rather than a pile of arrears. */
    private static final String CARD_CHARGE_METRIC = "commercial.wallet.card_charge";

    /** {@code recorded_by} on an entry no person wrote — the settlement pass at issue time or later. */
    private static final String SYSTEM_SETTLEMENT = "system:wallet-settlement";

    private static final String SYSTEM_CARD_CHARGER = "system:card-charger";
    private static final String SYSTEM_BONUS_EXPIRY = "system:bonus-expiry-sweep";

    private final JdbcWalletStore wallet;
    private final JdbcSubscriptionStore subscriptions;
    private final JdbcCardChargeAttemptStore attempts;
    private final ApprovalService approvals;
    private final CardCharger cardCharger;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;

    // The template is for the one method that calls a provider. Its database
    // work has to be two committed transactions with the provider call between
    // them, and @Transactional cannot express that from inside a single bean --
    // a method calling its own annotated method skips the proxy entirely. Same
    // shape, and for the same reason, as PaymentAttemptService.
    public WalletService(
            JdbcWalletStore wallet,
            JdbcSubscriptionStore subscriptions,
            JdbcCardChargeAttemptStore attempts,
            ApprovalService approvals,
            CardCharger cardCharger,
            AuditRecorder audit,
            MeterRegistry meters,
            TransactionTemplate unitOfWork,
            Clock clock) {
        this.wallet = wallet;
        this.subscriptions = subscriptions;
        this.attempts = attempts;
        this.approvals = approvals;
        this.cardCharger = cardCharger;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.meters = meters;
        this.clock = clock;
    }

    // -------------------------------------------------------------- reads

    public WalletBalances balances(UUID tenantId) {
        return wallet.balances(tenantId, wallet.currencyOf(tenantId));
    }

    /**
     * The part of the bonus balance a statement could actually draw on right
     * now: the sum of the live grants' remainders.
     *
     * <p>Not the same number as {@link #balances}'s bonus figure, and
     * deliberately reported beside it rather than instead of it. The balance is
     * the ledger's own SUM and has no clock in it, which is ADR 0095 decision 1
     * and must stay true; a grant's remainder stops being spendable the instant
     * its expiry passes and stops being counted only when the hourly sweep
     * writes the lapse. In that window — and again whenever a voided statement
     * hands a draw back to an already-expired grant — the balance names money
     * no statement can use, and a screen that showed only the balance would be
     * telling a tenant it has credit it cannot spend.
     */
    public long spendableBonusMinor(UUID tenantId) {
        return wallet.liveGrants(tenantId, clock.instant()).stream()
                .mapToLong(BonusGrantBalance::remainingMinor)
                .sum();
    }

    public List<BonusGrantBalance> liveGrants(UUID tenantId) {
        return wallet.liveGrants(tenantId, clock.instant());
    }

    /** The tenant's payment method; {@code INVOICE} with no card token when nothing has ever been recorded. */
    public TenantBilling billing(UUID tenantId) {
        return wallet.findBilling(tenantId)
                .orElseGet(() -> new TenantBilling(tenantId, PaymentMethod.INVOICE, null, "system", Instant.EPOCH));
    }

    /** Every ISSUED statement's paid and due amounts, newest month first. */
    public List<StatementPayment> statementPayments(UUID tenantId) {
        return wallet.statementPayments(tenantId);
    }

    /** The ledger, newest first, keyset-paginated on the previous page's last entry id. */
    public List<WalletEntry> ledger(UUID tenantId, @Nullable UUID afterId, int limit) {
        return wallet.ledger(tenantId, afterId, limit);
    }

    // ------------------------------------------------------- money in: one person

    /**
     * Records a bank transfer HorecaOS finance received (ADR 0095, item 2).
     * One person's audited act, like issuing a statement — not a correction,
     * and not maker-checker.
     *
     * <p>The bank's reference identifies one transfer, so recording the same
     * reference twice for a tenant is refused: the second record would credit
     * money that never arrived and pay statements nobody paid.
     */
    @Transactional
    public UUID recordTransfer(
            UUID tenantId,
            long amountMinor,
            String bankReference,
            ActorRef actor,
            String reason,
            String correlationId) {
        if (amountMinor <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A transfer is a positive amount");
        }
        requireReference(bankReference, "A transfer carries the bank's reference");
        wallet.lockBilling(tenantId, clock.instant());
        Instant now = clock.instant();
        UUID id = Ids.newId();
        appendMoneyIn(new WalletEntry(
                id,
                tenantId,
                WalletEntry.PAID,
                WalletEntry.TOP_UP,
                amountMinor,
                wallet.currencyOf(tenantId),
                null,
                null,
                null,
                null,
                bankReference,
                reason,
                subject(actor),
                null,
                null,
                now));

        audit.record(AuditFact.of("commercial.wallet.transfer_recorded", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because(reason)
                .changed(Map.of("amountMinor", amountMinor))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        applyAvailableFunds(tenantId);
        return id;
    }

    /**
     * Records the payment of a live subscription's activation deposit (ADR
     * 0095/0093, item 6): a PAID top-up marked {@code DEPOSIT}, which clears
     * the subscription's due amount back to zero. The first statement issued
     * afterwards is paid from it, like any other paid money.
     *
     * <p>The amount is the subscription's, not the caller's, and it is named in
     * the plan version's currency while the wallet holds the tenant's. A wallet
     * takes money in in its own currency only, for the same reason it pays out
     * in its own currency only (ADR 0095): there is no rate here at which one
     * could become the other, and crediting a foreign face value would record
     * four cents' worth of som for a five-hundred-dollar receipt.
     *
     * <p>Nothing could collect such a deposit — the statement carries no
     * deposit line any more, so there is no invoice for it either — which is
     * why {@code SubscriptionService.start} refuses to sell a second-currency
     * version that carries one. The check below is what stands between a
     * mis-pointed subscription and a face value recorded in the wrong money;
     * today only a plan-version migration that does not exist yet could reach
     * it.
     */
    @Transactional
    public UUID recordDeposit(
            UUID tenantId, String bankReference, ActorRef actor, String reason, String correlationId) {
        requireReference(bankReference, "A deposit carries the bank's reference");
        wallet.lockBilling(tenantId, clock.instant());
        // The obligation, its amount and its currency in one locked statement.
        // Three statements let a concurrent terminate-and-start commit between
        // them, and the deposit then cleared an obligation it never read.
        JdbcSubscriptionStore.DepositObligation obligation = subscriptions
                .lockLiveDepositObligation(tenantId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The tenant has no live subscription"));
        long due = obligation.depositDueMinor();
        if (due <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The tenant has no activation deposit due");
        }
        String walletCurrency = wallet.currencyOf(tenantId);
        String depositCurrency = obligation.currency();
        if (!depositCurrency.equals(walletCurrency)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The deposit is priced in %s and the wallet holds %s; a wallet takes money in in its own "
                                    .formatted(depositCurrency, walletCurrency)
                            + "currency only, so this deposit is collected by invoice",
                    Map.of("depositCurrency", depositCurrency, "walletCurrency", walletCurrency));
        }

        Instant now = clock.instant();
        UUID id = Ids.newId();
        appendMoneyIn(new WalletEntry(
                id,
                tenantId,
                WalletEntry.PAID,
                WalletEntry.DEPOSIT,
                due,
                walletCurrency,
                null,
                null,
                // The obligation this money clears, recorded on the row that
                // clears it. A reversal re-arms this subscription and no other.
                obligation.subscriptionId(),
                null,
                bankReference,
                reason,
                subject(actor),
                null,
                null,
                now));
        if (!subscriptions.clearDepositDue(tenantId, obligation.subscriptionId(), due)) {
            throw new IllegalStateException("The obligation read a moment ago under this lock is no longer "
                    + "%d on subscription %s".formatted(due, obligation.subscriptionId()));
        }

        audit.record(AuditFact.of("commercial.wallet.deposit_recorded", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because(reason)
                // deposit_due_minor is not money, so the ledger cannot
                // reconstruct the obligation this cleared. Which subscription,
                // and from what to what, exactly as the reversal records it.
                .changed(Map.of(
                        "amountMinor",
                        due,
                        "subscriptionId",
                        obligation.subscriptionId().toString(),
                        "depositDueFromMinor",
                        due,
                        "depositDueToMinor",
                        0L))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        applyAvailableFunds(tenantId);
        return id;
    }

    // --------------------------------------------------- manual changes: two people

    /**
     * A correction to a tenant's wallet, of either money kind, up or down (ADR
     * 0095, item 4).
     *
     * <p>A correction of bonus money names the grant it corrects, and cannot
     * take that grant's remainder below zero; a correction of paid money names
     * no grant and cannot take the paid balance below zero. The grant is not a
     * formality: bonus money is spent grant by grant and lapses with the grant
     * it belongs to, so a bonus entry belonging to no grant would be a balance
     * no statement could ever draw on and no expiry could ever reach.
     */
    @Transactional
    public WalletChangeOutcome proposeAdjustment(
            UUID tenantId,
            String moneyKind,
            @Nullable UUID grantId,
            long amountMinor,
            ActorRef actor,
            String reason,
            String correlationId) {
        requireMoneyKind(moneyKind);
        if (amountMinor == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An adjustment must move a nonzero amount");
        }
        boolean bonus = WalletEntry.BONUS.equals(moneyKind);
        if (bonus == (grantId == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    bonus
                            ? "A correction of bonus money names the grant it corrects"
                            : "A correction of paid money names no grant");
        }
        if (grantId != null) {
            BonusGrantBalance grant = wallet.findGrant(tenantId, grantId)
                    .orElseThrow(() ->
                            new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That tenant has no such bonus grant"));
            if (!grant.expiresAt().isAfter(clock.instant())) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "That bonus grant has lapsed; grant a new one instead");
            }
        }
        AdjustmentCommand command =
                new AdjustmentCommand(tenantId, moneyKind, grantId, amountMinor, wallet.currencyOf(tenantId), reason);
        ApprovalParameters.Signed signed = ApprovalParameters.of(command)
                .and("entryType", WalletEntry.ADJUSTMENT)
                .excluding()
                .withholding("reason")
                .about("tenantId")
                .sign();
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.WALLET_ADJUSTMENT.code(),
                signed.hash(),
                ResourceScope.platform(),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY,
                signed.subject()));

        WalletChangeOutcome awaiting = notYetDecided(approval);
        if (awaiting != null) {
            return awaiting;
        }

        wallet.lockBilling(tenantId, clock.instant());
        // Under the lock, and before the signature is spent: a refusal here
        // leaves the approval unspent for a retry once the money is there.
        long available = grantId == null ? wallet.paidBalance(tenantId) : wallet.grantRemaining(tenantId, grantId);
        if (available + amountMinor < 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only %d is there to correct; a correction cannot take a balance below zero".formatted(available),
                    Map.of("availableMinor", available));
        }
        approval.consume();
        Instant now = clock.instant();
        UUID id = Ids.newId();
        UUID requestId = requestIdOf(approval);
        wallet.append(new WalletEntry(
                id,
                tenantId,
                moneyKind,
                WalletEntry.ADJUSTMENT,
                amountMinor,
                wallet.currencyOf(tenantId),
                null,
                grantId,
                null,
                null,
                null,
                reason,
                recordedBy(approval),
                approvedBy(approval),
                requestId,
                now));

        audit.record(AuditFact.of("commercial.wallet.adjusted", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because(reason)
                .changed(Map.of("moneyKind", moneyKind, "amountMinor", amountMinor))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .underApproval(requestId)
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        if (amountMinor > 0) {
            applyAvailableFunds(tenantId);
        }
        return new WalletChangeOutcome(WalletChangeOutcome.CHANGED, requestId);
    }

    /** A grant of bonus money, with the date it lapses on (ADR 0095, items 4 and 5). */
    @Transactional
    public WalletChangeOutcome proposeBonusGrant(
            UUID tenantId, long amountMinor, Instant expiresAt, ActorRef actor, String reason, String correlationId) {
        if (amountMinor <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A bonus grant is a positive amount");
        }
        Instant now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A bonus grant expires in the future");
        }
        BonusGrantCommand command =
                new BonusGrantCommand(tenantId, amountMinor, expiresAt, wallet.currencyOf(tenantId), reason);
        ApprovalParameters.Signed signed = ApprovalParameters.of(command)
                .and("entryType", WalletEntry.BONUS_GRANT)
                .and("moneyKind", WalletEntry.BONUS)
                .excluding()
                .withholding("reason")
                .about("tenantId")
                .sign();
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.WALLET_BONUS_GRANT.code(),
                signed.hash(),
                ResourceScope.platform(),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY,
                signed.subject()));

        WalletChangeOutcome awaiting = notYetDecided(approval);
        if (awaiting != null) {
            return awaiting;
        }

        wallet.lockBilling(tenantId, clock.instant());
        approval.consume();
        Instant appliedAt = clock.instant();
        UUID id = Ids.newId();
        UUID requestId = requestIdOf(approval);
        wallet.append(new WalletEntry(
                id,
                tenantId,
                WalletEntry.BONUS,
                WalletEntry.BONUS_GRANT,
                amountMinor,
                wallet.currencyOf(tenantId),
                null,
                null,
                null,
                expiresAt,
                null,
                reason,
                recordedBy(approval),
                approvedBy(approval),
                requestId,
                appliedAt));

        audit.record(AuditFact.of("commercial.wallet.bonus_granted", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because(reason)
                .changed(Map.of("amountMinor", amountMinor, "expiresAt", expiresAt.toString()))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .underApproval(requestId)
                .correlatedBy(correlationId)
                .occurredAt(appliedAt)
                .build());

        applyAvailableFunds(tenantId);
        return new WalletChangeOutcome(WalletChangeOutcome.CHANGED, requestId);
    }

    /**
     * A refund of paid money, naming the payout it left on (ADR 0095, items 4
     * and 5). Refused when it would take the paid balance below zero — checked
     * under the tenant's billing lock, and checked before the approval is
     * spent, so a refusal here leaves the signature unspent for a retry once
     * there is enough to refund.
     */
    @Transactional
    public WalletChangeOutcome proposeRefund(
            UUID tenantId,
            long amountMinor,
            String payoutReference,
            ActorRef actor,
            String reason,
            String correlationId) {
        if (amountMinor <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A refund is a positive amount");
        }
        if (payoutReference == null || payoutReference.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A refund names the payout it left on");
        }
        RefundCommand command =
                new RefundCommand(tenantId, -amountMinor, payoutReference, wallet.currencyOf(tenantId), reason);
        ApprovalParameters.Signed signed = ApprovalParameters.of(command)
                .and("entryType", WalletEntry.REFUND)
                .and("moneyKind", WalletEntry.PAID)
                .excluding()
                .withholding("reason")
                .about("tenantId")
                .sign();
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.WALLET_REFUND.code(),
                signed.hash(),
                ResourceScope.platform(),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY,
                signed.subject()));

        WalletChangeOutcome awaiting = notYetDecided(approval);
        if (awaiting != null) {
            return awaiting;
        }

        wallet.lockBilling(tenantId, clock.instant());
        long paid = wallet.paidBalance(tenantId);
        if (paid < amountMinor) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The paid balance is only %d; a refund cannot take it below zero".formatted(paid),
                    Map.of("paidBalanceMinor", paid));
        }
        approval.consume();

        Instant now = clock.instant();
        UUID id = Ids.newId();
        UUID requestId = requestIdOf(approval);
        wallet.append(new WalletEntry(
                id,
                tenantId,
                WalletEntry.PAID,
                WalletEntry.REFUND,
                -amountMinor,
                wallet.currencyOf(tenantId),
                null,
                null,
                null,
                null,
                payoutReference,
                reason,
                recordedBy(approval),
                approvedBy(approval),
                requestId,
                now));

        audit.record(AuditFact.of("commercial.wallet.refunded", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because(reason)
                .changed(Map.of("amountMinor", amountMinor))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .underApproval(requestId)
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return new WalletChangeOutcome(WalletChangeOutcome.CHANGED, requestId);
    }

    /**
     * Takes back an activation deposit recorded against the wrong tenant, and
     * makes that tenant's deposit due again in the same locked transaction
     * (ADR 0095, item 6).
     *
     * <p><strong>Why this exists rather than a downward correction.</strong>
     * Recording a deposit does two things: it appends PAID money to the ledger
     * and it clears {@code subscriptions.deposit_due_minor}. A plain
     * {@code ADJUSTMENT} mends only the first. The flag stayed at zero, so the
     * tenant's real deposit could never be recorded again — {@code
     * recordDeposit} refuses when nothing is due — and it is never billed
     * either, because the statement stopped carrying a deposit line. The money
     * was recoverable and the obligation was not, and the only remedy left was
     * hand-written SQL against production, which is the thing an append-only
     * ledger exists to make unnecessary.
     *
     * <p>The reversal names the same reference the deposit was recorded under,
     * so the two rows read as one act, and V0211 makes that reference unique
     * among reversals for the tenant: one deposit is taken back once, and a
     * second approved reversal cannot re-arm the obligation twice over.
     *
     * <p>Refused when the paid balance will not carry it, exactly as a refund
     * is. A deposit that has already paid a statement is not reversible on its
     * own: void the statement first, which gives the money back, then reverse.
     */
    @Transactional
    public WalletChangeOutcome proposeDepositReversal(
            UUID tenantId, UUID depositEntryId, ActorRef actor, String reason, String correlationId) {
        WalletEntry deposit = wallet.findEntryOfType(tenantId, depositEntryId, WalletEntry.DEPOSIT)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That tenant has no such recorded deposit"));
        UUID subscriptionId = Objects.requireNonNull(
                deposit.subscriptionId(),
                "V0211's ck_wallet_entry_subscription_ref makes a DEPOSIT name the subscription it cleared");

        DepositReversalCommand command = new DepositReversalCommand(
                tenantId, depositEntryId, subscriptionId, -deposit.amountMinor(), deposit.currency(), reason);
        ApprovalParameters.Signed signed = ApprovalParameters.of(command)
                .and("entryType", WalletEntry.DEPOSIT_REVERSAL)
                .and("moneyKind", WalletEntry.PAID)
                .excluding()
                .withholding("reason")
                .about("tenantId")
                .sign();
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.WALLET_DEPOSIT_REVERSAL.code(),
                signed.hash(),
                ResourceScope.platform(),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY,
                signed.subject()));

        WalletChangeOutcome awaiting = notYetDecided(approval);
        if (awaiting != null) {
            return awaiting;
        }

        wallet.lockBilling(tenantId, clock.instant());
        // Every refusal below runs under the lock and before the signature is
        // spent, so each leaves the approval unspent for a retry once whatever
        // it names has been put right.
        long paid = wallet.paidBalance(tenantId);
        if (paid < deposit.amountMinor()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    ("The paid balance is only %d and the deposit was %d; a reversal cannot take it below zero. "
                                    + "Void the statements it paid first, which gives the money back.")
                            .formatted(paid, deposit.amountMinor()),
                    Map.of("paidBalanceMinor", paid, "depositMinor", deposit.amountMinor()));
        }
        Subscription obligation = subscriptions
                .findById(tenantId, subscriptionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "The subscription this deposit paid is not this tenant's"));
        if (obligation.status().isTerminal()) {
            // Never retarget. Re-arming whichever subscription is live now is
            // how the old plan's 500 000 replaced the new plan's 5 000 000 with
            // nothing anywhere to show it: the statement carries no deposit
            // line, the ledger records money and not obligations, and the live
            // read only ever sees the survivor. A reversal that has no live
            // obligation to give back to is a decision for a person to take as
            // an ADJUSTMENT, with their name on it.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    ("The subscription this deposit paid is %s, so there is no obligation left to re-arm. "
                                    + "Correct the ledger with an adjustment instead, which says so on the row.")
                            .formatted(obligation.status().name()),
                    Map.of(
                            "subscriptionId",
                            subscriptionId.toString(),
                            "status",
                            obligation.status().name()));
        }
        String obligationCurrency = subscriptions
                .planCurrencyOf(tenantId, subscriptionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "The subscription this deposit paid names no plan version"));
        if (!obligationCurrency.equals(deposit.currency())) {
            // deposit_due_minor is copied verbatim from the plan version and
            // carries no currency of its own, so writing a som figure onto a
            // dollar obligation records a face value in the wrong money.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    ("The deposit was recorded in %s and the obligation is priced in %s; a reversal restores "
                                    + "the obligation in its own currency only")
                            .formatted(deposit.currency(), obligationCurrency),
                    Map.of("depositCurrency", deposit.currency(), "obligationCurrency", obligationCurrency));
        }
        approval.consume();

        Instant now = clock.instant();
        UUID id = Ids.newId();
        UUID requestId = requestIdOf(approval);
        appendMoneyIn(new WalletEntry(
                id,
                tenantId,
                WalletEntry.PAID,
                WalletEntry.DEPOSIT_REVERSAL,
                -deposit.amountMinor(),
                deposit.currency(),
                null,
                null,
                subscriptionId,
                null,
                deposit.externalReference(),
                reason,
                recordedBy(approval),
                approvedBy(approval),
                requestId,
                now));

        // Same transaction, under the same lock: the entry and the obligation
        // it re-arms are one act, and a crash between them is the drift this
        // whole path was written to end. Onto the subscription the deposit
        // cleared, by id, and additively -- see restoreDepositDue.
        long dueBefore = subscriptions.depositDueOf(tenantId, subscriptionId);
        if (!subscriptions.restoreDepositDue(tenantId, subscriptionId, deposit.amountMinor())) {
            throw new IllegalStateException(
                    "The subscription read a moment ago under this lock could not be re-armed: " + subscriptionId);
        }

        audit.record(AuditFact.of("commercial.wallet.deposit_reversed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because(reason)
                // The obligation move is the one fact the ledger cannot
                // reconstruct: it records money, and deposit_due_minor is not
                // money. Which subscription, and from what to what.
                .changed(Map.of(
                        "amountMinor", deposit.amountMinor(),
                        "reversedEntryId", depositEntryId.toString(),
                        "subscriptionId", subscriptionId.toString(),
                        "depositDueFromMinor", dueBefore,
                        "depositDueToMinor", dueBefore + deposit.amountMinor()))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .underApproval(requestId)
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return new WalletChangeOutcome(WalletChangeOutcome.CHANGED, requestId);
    }

    // ------------------------------------------------------------- payment method

    /** Changes how a tenant is collected (ADR 0095, item 8). Staff-changeable with a reason; audited. */
    @Transactional
    public void setPaymentMethod(
            UUID tenantId,
            PaymentMethod method,
            @Nullable String cardTokenReference,
            ActorRef actor,
            String reason,
            String correlationId) {
        if (method != PaymentMethod.CARD && cardTokenReference != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A card token is recorded only for CARD");
        }
        TenantBilling before = wallet.lockBilling(tenantId, clock.instant());
        Instant now = clock.instant();
        wallet.setPaymentMethod(tenantId, method, cardTokenReference, subject(actor), now);

        audit.record(AuditFact.of("commercial.wallet.payment_method_changed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_billing", tenantId)
                .because(reason)
                .changed(Map.of("from", before.paymentMethod().name(), "to", method.name()))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    // ---------------------------------------------------- statement settlement

    /**
     * Pays every open (ISSUED, not fully paid) statement from the wallet,
     * oldest first: bonus money before paid money, the grant expiring
     * soonest drawn first (ADR 0095, item 3). One {@code STATEMENT_PAYMENT}
     * entry per grant drawn from, plus at most one more from the paid
     * balance.
     *
     * <p>Called at issue time — so a newly issued statement is paid at once —
     * and after any money arrives that the wallet might now have to spend:
     * a transfer, a deposit, an approved upward adjustment or a bonus grant.
     * A downward adjustment or a refund only ever removes money, so neither
     * calls this.
     *
     * <p><strong>The wallet only.</strong> What a statement still owes after
     * this pass is collected from the tenant's card by
     * {@link #settleCardRemainders}, which is deliberately not part of this
     * transaction: it asks a provider we do not control, and a provider call
     * inside the transaction that recorded an operator's bank transfer means a
     * provider timeout rolls that transfer back. The statement's remaining due
     * is the source of truth, so a pass that does not happen is simply picked
     * up by the next one.
     *
     * @return how much this pass paid across every statement out of the
     *         wallet, in the tenant's billing currency's minor units
     */
    @Transactional
    public long applyAvailableFunds(UUID tenantId) {
        Instant now = clock.instant();
        wallet.lockBilling(tenantId, now);
        String currency = wallet.currencyOf(tenantId);
        List<StatementPayment> open = wallet.openStatementsOldestFirst(tenantId, currency);
        if (open.isEmpty()) {
            return 0;
        }

        List<BonusGrantBalance> grants = wallet.liveGrants(tenantId, now);
        Map<UUID, Long> remainingByGrant = new LinkedHashMap<>();
        for (BonusGrantBalance grant : grants) {
            remainingByGrant.put(grant.grantId(), grant.remainingMinor());
        }
        long paidBalance = wallet.paidBalance(tenantId);

        long totalPaid = 0;
        for (StatementPayment statement : open) {
            long remainingDue = statement.dueMinor();
            if (remainingDue <= 0) {
                continue;
            }
            for (BonusGrantBalance grant : grants) {
                if (remainingDue <= 0) {
                    break;
                }
                long available = remainingByGrant.getOrDefault(grant.grantId(), 0L);
                if (available <= 0) {
                    continue;
                }
                long draw = Math.min(available, remainingDue);
                wallet.append(new WalletEntry(
                        Ids.newId(),
                        tenantId,
                        WalletEntry.BONUS,
                        WalletEntry.STATEMENT_PAYMENT,
                        -draw,
                        currency,
                        statement.statementId(),
                        grant.grantId(),
                        null,
                        null,
                        null,
                        "statement %s paid from a bonus grant".formatted(statement.number()),
                        SYSTEM_SETTLEMENT,
                        null,
                        null,
                        now));
                remainingByGrant.put(grant.grantId(), available - draw);
                remainingDue -= draw;
                totalPaid += draw;
            }
            if (remainingDue > 0 && paidBalance > 0) {
                long draw = Math.min(paidBalance, remainingDue);
                wallet.append(new WalletEntry(
                        Ids.newId(),
                        tenantId,
                        WalletEntry.PAID,
                        WalletEntry.STATEMENT_PAYMENT,
                        -draw,
                        currency,
                        statement.statementId(),
                        null,
                        null,
                        null,
                        null,
                        "statement %s paid from the paid balance".formatted(statement.number()),
                        SYSTEM_SETTLEMENT,
                        null,
                        null,
                        now));
                paidBalance -= draw;
                remainingDue -= draw;
                totalPaid += draw;
            }
        }
        return totalPaid;
    }

    /**
     * Gives back what a voided statement drew from the wallet (ADR 0088 voids
     * a wrong statement and issues again; ADR 0095 pays one at issue).
     *
     * <p>Without this the money would be spent against a statement that no
     * longer stands: bonus drawn from a grant that can never be credited back,
     * paid money the tenant would have to transfer twice. The ledger is never
     * reopened, so each draw is given back by the opposite entry naming the
     * same statement — and the same grant, for bonus money — leaving both the
     * draw and its reversal on the record. Idempotent: a statement whose draws
     * are already reversed has nothing left to give back.
     *
     * @return how much this gave back
     */
    @Transactional
    public long reverseStatementPayments(UUID tenantId, UUID statementId, String statementNumber) {
        Instant now = clock.instant();
        wallet.lockBilling(tenantId, now);
        long returned = 0;
        for (JdbcWalletStore.StatementDraw draw : wallet.unreversedDraws(tenantId, statementId)) {
            wallet.append(new WalletEntry(
                    Ids.newId(),
                    tenantId,
                    draw.moneyKind(),
                    WalletEntry.STATEMENT_REVERSAL,
                    draw.drawnMinor(),
                    draw.currency(),
                    statementId,
                    draw.grantId(),
                    null,
                    null,
                    null,
                    "statement %s was voided; what it drew is given back".formatted(statementNumber),
                    SYSTEM_SETTLEMENT,
                    null,
                    null,
                    now));
            returned += draw.drawnMinor();
        }
        return returned;
    }

    /**
     * Charges a CARD tenant's stored card for whatever its open statements
     * still owe after bonus and paid money (ADR 0095, item 8), between two
     * committed transactions rather than inside one. {@code NotConfigured} —
     * the only answer today, with no merchant account — leaves the remainder
     * due, exactly like {@code INVOICE}.
     *
     * <p><strong>Why this is not part of {@link #applyAvailableFunds}.</strong>
     * It was, and that put a call to a third party inside the transaction that
     * had just recorded an operator's bank transfer. A provider timeout there
     * rolls the transfer back: the tenant's money is uncredited, the statement
     * ages into arrears and the operator sees a 500 with no row on file. The
     * attempt row went with it, so the one artefact V0214 exists to leave
     * behind — a committed {@code PENDING} key meaning "we asked and never
     * learned the answer" — could never survive the failure it was written
     * for. And it held a pooled connection, and the tenant's billing row lock,
     * across an uncontrolled network wait, which is the property {@code
     * ExternalCallTransactionBoundaryTests} enforces for every other provider
     * call in the platform.
     *
     * <p>So: one transaction opens the attempt and commits, the provider is
     * asked with nothing held, and a second transaction records the answer
     * together with the money. Dropping the billing lock around the call is
     * the real cost, so the second transaction takes it again and re-reads
     * what the statement still owes — a transfer that arrived meanwhile keeps
     * its payment, and a card charge for more than that leaves owed is a
     * surplus this method refuses to credit as paid balance: it is the shape
     * a card genuinely charged twice takes at this table, so it is audited
     * for finance to reconcile by hand instead (ADR 0095, V0222).
     *
     * <p>Never a fresh id for a statement with an unresolved attempt already
     * on file (ADR 0095, V0222): {@code beginCardAttempt} reuses it, and this
     * method asks the provider about it, once, before charging under it
     * again — a provider timeout or a settlement pass racing this one for
     * the same tenant used to each be free to open their own attempt and
     * their own key for the same remainder, and a provider honouring keys
     * genuinely charged the card twice.
     *
     * <p>Every outcome is answered for, and the four are told apart. A decline
     * used to collapse into the same silent {@code return 0} as "no merchant
     * account yet": the provider's reason was read by nobody, no audit fact was
     * written and no counter moved, so a card that declined every month
     * surfaced only weeks later as an arrears case with no cause attached, and
     * a provider outage looked like many unrelated tenants going quietly into
     * arrears. The shape here is {@code OwnerInvitationRelay}'s for {@code
     * MailOutcome}: name each arm, keep the reason code, and hold the expected
     * answer apart from the real failure.
     *
     * <p>Deliberately no control-plane incident per decline: one decline is a
     * normal collections event, and an alert per tenant per month is noise
     * that teaches operators to ignore the class. The systemic signal is the
     * counter's failure rate; the per-tenant conversation stays with {@code
     * CommercialArrearsReviewSweeper} (ADR 0089).
     *
     * @return how much of the statements' remainders the card actually paid
     */
    public long settleCardRemainders(UUID tenantId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "settleCardRemainders asks a provider HorecaOS does not control, so it runs between "
                            + "two committed transactions and never inside a caller's. Call it once the "
                            + "unit of work that recorded the money has committed.");
        }
        List<UUID> open = Objects.requireNonNull(unitOfWork.execute(status -> openCardStatements(tenantId)));
        long charged = 0;
        for (UUID statementId : open) {
            charged += settleOneCardRemainder(tenantId, statementId);
        }
        return charged;
    }

    /** The open statements a CARD tenant still owes something on, oldest first; empty for anyone else. */
    private List<UUID> openCardStatements(UUID tenantId) {
        if (billing(tenantId).paymentMethod() != PaymentMethod.CARD) {
            return List.of();
        }
        return wallet.openStatementsOldestFirst(tenantId, wallet.currencyOf(tenantId)).stream()
                .filter(statement -> statement.dueMinor() > 0)
                .map(StatementPayment::statementId)
                .toList();
    }

    private long settleOneCardRemainder(UUID tenantId, UUID statementId) {
        CardAttempt attempt = beginOrReuseCardAttempt(tenantId, statementId);
        if (attempt == null) {
            return 0;
        }
        if (attempt.reused()) {
            // Not fresh: either a previous pass never learned the provider's
            // answer, or a settlement pass racing this one already committed
            // it. Ask before charging again — the whole point of reusing the
            // id is that a retry under it is a replay, never a new charge, but
            // asking first means a provider that is sure it already succeeded
            // is recorded without being asked to act on the same key twice.
            CardCharger.StatusOutcome status;
            try {
                status = cardCharger.status(attempt.attemptId().toString());
            } catch (RuntimeException unanswered) {
                log.warn(
                        "Asking about a pending card charge for statement {} of tenant {} was never answered; "
                                + "replaying the charge under the same key",
                        statementId,
                        tenantId);
                log.debug("The card charger threw", unanswered);
                status = new CardCharger.StatusOutcome.NotSucceeded();
            }
            if (status instanceof CardCharger.StatusOutcome.Succeeded succeeded) {
                return Objects.requireNonNull(unitOfWork.execute(txStatus -> recordCardOutcome(
                        tenantId, attempt, new CardCharger.Outcome.Succeeded(succeeded.providerReference()))));
            }
            // Declined, unknown, or no merchant account: fall through and
            // replay the charge under the same idempotency key exactly as a
            // fresh attempt would be charged, below.
        }
        CardCharger.Outcome outcome;
        try {
            outcome = cardCharger.charge(
                    tenantId,
                    attempt.cardTokenReference(),
                    attempt.amountMinor(),
                    attempt.currency(),
                    attempt.attemptId().toString());
        } catch (RuntimeException unanswered) {
            // The row is already committed and stays PENDING, which is exactly
            // what PENDING means: we asked and never learned the answer. The
            // money may or may not have left the card, so nothing is written to
            // the ledger and nothing is recorded as declined — the next pass
            // reuses this same attempt and its key rather than asking again
            // under a new one. Never the token and never the amount beside the
            // tenant's name (ADR 0028, ADR 0029).
            log.warn("A card charge for statement {} of tenant {} was never answered", statementId, tenantId);
            log.debug("The card charger threw", unanswered);
            countCardCharge("unanswered");
            return 0;
        }
        return Objects.requireNonNull(unitOfWork.execute(status -> recordCardOutcome(tenantId, attempt, outcome)));
    }

    /**
     * Opens (or reuses) the attempt for this statement, each half in its own
     * committed transaction, the way {@link #settleOneCardRemainder} already
     * keeps the provider call outside either.
     *
     * <p>A concurrent caller for the same tenant can win the race {@link
     * #beginCardAttempt}'s insert loses (V0222's unique index is what makes
     * that a refusal rather than a second row): PostgreSQL aborts the
     * transaction that lost outright on a unique violation, so nothing
     * further can be read back through it. The winner's row is read back in
     * a transaction of its own instead, once {@link #beginCardAttempt}'s has
     * finished rolling back.
     */
    private @Nullable CardAttempt beginOrReuseCardAttempt(UUID tenantId, UUID statementId) {
        try {
            return Objects.requireNonNull(unitOfWork.execute(status -> beginCardAttempt(tenantId, statementId)))
                    .orElse(null);
        } catch (DuplicateKeyException raced) {
            return Objects.requireNonNull(
                            unitOfWork.execute(status -> reuseCardAttemptAfterRace(tenantId, statementId, raced)))
                    .orElse(null);
        }
    }

    /**
     * Opens one attempt and commits it, before anything is asked of a provider.
     *
     * <p>One attempt, one row, and the row's id is the key the provider is
     * handed — never the statement id, whose amount changes between settlement
     * passes. Null when there is nothing to charge after all: the tenant is no
     * longer on CARD, or the statement was paid while this pass was being set
     * up.
     *
     * <p><strong>Never a second key for a statement still unresolved.</strong>
     * A statement with an existing PENDING attempt gets that attempt back,
     * not a new one (ADR 0095, V0222): {@link #beginOrReuseCardAttempt}
     * decides whether it is safe to ask the provider about it again. A
     * {@link DuplicateKeyException} out of the insert below is left to
     * propagate rather than caught here — see {@link #beginOrReuseCardAttempt}.
     *
     * <p><strong>Never reused under a card it was not minted with.</strong> A
     * PENDING attempt's id is the idempotency key the provider is handed, and
     * that key was minted under whatever card was on file when the row was
     * inserted — {@code card_token_reference}, pinned on the row by {@link
     * JdbcCardChargeAttemptStore#begin}. {@code setPaymentMethod} never
     * touches this table, so a card swapped while the attempt sits PENDING
     * left the id on file pointing at a card it was never asked about; asking
     * the provider about it, or charging it again, under the tenant's
     * <em>current</em> token would hand the same key to two different cards,
     * which {@link CardCharger#charge} documents as fatal either way. When the
     * tenant's current token no longer matches the one on the row, the row is
     * settled {@code SUPERSEDED} out of band — never asked about again, never
     * retried — and a genuinely new attempt is minted under the card now on
     * file, exactly as if none had existed.
     */
    private Optional<CardAttempt> beginCardAttempt(UUID tenantId, UUID statementId) {
        Instant now = clock.instant();
        TenantBilling billing = wallet.lockBilling(tenantId, now);
        if (billing.paymentMethod() != PaymentMethod.CARD) {
            return Optional.empty();
        }
        String currency = wallet.currencyOf(tenantId);
        StatementPayment statement = openStatement(tenantId, currency, statementId);
        if (statement == null || statement.dueMinor() <= 0) {
            return Optional.empty();
        }
        Optional<JdbcCardChargeAttemptStore.PendingAttempt> pending = attempts.findPending(tenantId, statementId);
        if (pending.isPresent()) {
            JdbcCardChargeAttemptStore.PendingAttempt existing = pending.get();
            if (Objects.equals(existing.cardTokenReference(), billing.cardTokenReference())) {
                return Optional.of(reusedAttempt(existing, statementId, statement.number()));
            }
            return supersedeAndMintFresh(tenantId, statementId, statement, billing, currency, existing, now);
        }
        UUID attemptId = Ids.newId();
        attempts.begin(
                attemptId, tenantId, statementId, statement.dueMinor(), currency, billing.cardTokenReference(), now);
        return Optional.of(new CardAttempt(
                attemptId,
                statementId,
                statement.number(),
                statement.dueMinor(),
                currency,
                billing.cardTokenReference(),
                false));
    }

    /**
     * Reads back the attempt a concurrent caller committed for this
     * statement while this one's own insert was racing it and lost — in a
     * transaction of its own, since the one that lost is already aborted and
     * cannot be read through (see {@link #beginOrReuseCardAttempt}).
     *
     * <p>Subject to the same card-swap check as {@link #beginCardAttempt}: the
     * winner's row is only ever reused if its own pinned token still matches
     * the tenant's current one, and is superseded and replaced otherwise.
     */
    private Optional<CardAttempt> reuseCardAttemptAfterRace(
            UUID tenantId, UUID statementId, DuplicateKeyException raced) {
        Instant now = clock.instant();
        TenantBilling billing = wallet.lockBilling(tenantId, now);
        String currency = wallet.currencyOf(tenantId);
        StatementPayment statement = openStatement(tenantId, currency, statementId);
        JdbcCardChargeAttemptStore.PendingAttempt winner =
                attempts.findPending(tenantId, statementId).orElseThrow(() -> raced);
        if (Objects.equals(winner.cardTokenReference(), billing.cardTokenReference())) {
            String statementNumber = statement == null ? winner.id().toString() : statement.number();
            return Optional.of(reusedAttempt(winner, statementId, statementNumber));
        }
        return supersedeAndMintFresh(tenantId, statementId, statement, billing, currency, winner, now);
    }

    private static CardAttempt reusedAttempt(
            JdbcCardChargeAttemptStore.PendingAttempt pending, UUID statementId, String statementNumber) {
        return new CardAttempt(
                pending.id(),
                statementId,
                statementNumber,
                pending.amountMinor(),
                pending.currency(),
                pending.cardTokenReference(),
                true);
    }

    /**
     * Settles a PENDING attempt {@code SUPERSEDED} because the card token it
     * was minted under is no longer the one on file, and — when the statement
     * still owes something — mints a genuinely new attempt and a genuinely
     * new key against the card now on file.
     *
     * <p>Never charges the new card under the old key: two different cards
     * behind one idempotency key is exactly what {@link CardCharger#charge}
     * documents as fatal either way, a provider erroring on the changed
     * parameter or replaying the old card's result for a charge the new card
     * was never asked to make. Idempotent through {@link
     * JdbcCardChargeAttemptStore#settle}'s own {@code WHERE outcome =
     * 'PENDING'}: a concurrent report that already resolved this exact
     * attempt is left alone rather than superseded out from under it.
     *
     * <p>{@code statement} may be null or already fully paid — the caller
     * re-reads it fresh under the same lock — in which case the old attempt
     * is still superseded (it must never be left pointing at a stale card)
     * but nothing is minted in its place, exactly as {@link #beginCardAttempt}
     * mints nothing for a statement with nothing left due.
     */
    private Optional<CardAttempt> supersedeAndMintFresh(
            UUID tenantId,
            UUID statementId,
            @Nullable StatementPayment statement,
            TenantBilling billing,
            String currency,
            JdbcCardChargeAttemptStore.PendingAttempt superseded,
            Instant now) {
        UUID freshId = statement != null && statement.dueMinor() > 0 ? Ids.newId() : null;
        if (attempts.settle(superseded.id(), "SUPERSEDED", null, now)) {
            audit.record(AuditFact.of("commercial.wallet.card_attempt_superseded", AuditClass.BUSINESS)
                    .by(ActorRef.systemJob("wallet-settlement"))
                    .at(ResourceScope.tenant(tenantId))
                    .target("commercial.statement", statementId)
                    .because("the tenant's card token changed while this attempt was pending -- its own "
                            + "charge() call may still be outstanding against the old card, this fact is not "
                            + "proof no charge happened on it, and a late success for it will be recorded "
                            + "separately as commercial.wallet.card_charge_after_supersede for reconciliation")
                    .changed(
                            freshId == null
                                    ? Map.of(
                                            "supersededAttemptId",
                                            superseded.id().toString())
                                    : Map.of(
                                            "supersededAttemptId",
                                            superseded.id().toString(),
                                            "newAttemptId",
                                            freshId.toString()))
                    .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                    .correlatedBy(statementId.toString())
                    .occurredAt(now)
                    .build());
        }
        if (freshId == null || statement == null) {
            return Optional.empty();
        }
        long amountMinor = statement.dueMinor();
        attempts.begin(freshId, tenantId, statementId, amountMinor, currency, billing.cardTokenReference(), now);
        return Optional.of(new CardAttempt(
                freshId, statementId, statement.number(), amountMinor, currency, billing.cardTokenReference(), false));
    }

    /**
     * Records what the provider answered, together with the money, as one
     * transaction.
     *
     * <p><strong>Idempotent in the attempt, not only in the money.</strong>
     * {@link JdbcCardChargeAttemptStore#settle} only ever resolves a row once
     * — {@code WHERE outcome = 'PENDING'} — so when a reused attempt
     * (V0222) was asked about, or charged, by two racing settlement passes
     * and both were told the same answer, only the first to reach here
     * writes anything. The second's {@code settle} affects no row and this
     * method stops before any ledger entry or audit fact, for any outcome:
     * two truthful reports of the one thing that happened are still one
     * thing that happened.
     */
    private long recordCardOutcome(UUID tenantId, CardAttempt attempt, CardCharger.Outcome outcome) {
        Instant now = clock.instant();
        switch (outcome) {
            case CardCharger.Outcome.Failed failed -> {
                if (!attempts.settle(attempt.attemptId(), "FAILED", failed.reason(), now)) {
                    // Either a racing pass already told this exact attempt
                    // apart -- the ordinary "told twice" case -- or this
                    // attempt was superseded while its charge() call was
                    // still outstanding and the provider's real answer is a
                    // decline: a decline is nothing owed and nothing paid
                    // either way, on the old card or the new one, so there is
                    // nothing to record for it and no reconciliation for
                    // finance to do. Unlike the Succeeded case below, no
                    // read-back is needed to tell the two apart, because both
                    // resolve identically here: write nothing.
                    return 0;
                }
                // The tenant id and the statement, never the token and never the
                // amount beside a tenant's name (ADR 0028, ADR 0029). The reason
                // is the provider's own code, which is what an operator asked
                // "why is this in arrears" actually needs.
                log.warn(
                        "A card charge for statement {} of tenant {} was declined: {}",
                        attempt.statementNumber(),
                        tenantId,
                        failed.reason());
                audit.record(AuditFact.of("commercial.wallet.card_charge_declined", AuditClass.BUSINESS)
                        .by(ActorRef.systemJob("wallet-settlement"))
                        .at(ResourceScope.tenant(tenantId))
                        .target("commercial.statement", attempt.statementId())
                        .outcome(AuditFact.Outcome.REJECTED)
                        .because("the card charge was declined by the provider")
                        .changed(Map.of("reason", failed.reason()))
                        .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                        .correlatedBy(attempt.statementId().toString())
                        .occurredAt(now)
                        .build());
                countCardCharge("failed");
                return 0;
            }
            case CardCharger.Outcome.NotConfigured ignored -> {
                if (!attempts.settle(attempt.attemptId(), "NOT_CONFIGURED", null, now)) {
                    return 0;
                }
                // The expected answer until a merchant agreement exists, so it is
                // counted and not logged: a WARN per CARD tenant per statement
                // would drown the decline it has to be told apart from.
                countCardCharge("not_configured");
                return 0;
            }
            case CardCharger.Outcome.Succeeded succeeded -> {
                wallet.lockBilling(tenantId, now);
                if (!attempts.settle(attempt.attemptId(), "SUCCEEDED", succeeded.providerReference(), now)) {
                    return recordLateSucceededOutcome(tenantId, attempt, succeeded, now);
                }
                countCardCharge("succeeded");
                return applySuccessfulCharge(tenantId, attempt, succeeded, now);
            }
        }
    }

    /**
     * {@code attempts.settle} found {@code attempt} no longer {@code PENDING}
     * by the time the provider's {@code Succeeded} answer for it came back.
     * Two different histories look identical from that call alone, and only
     * a read-back of the row tells them apart:
     *
     * <ul>
     *   <li>the row is already {@code SUCCEEDED} — a racing settlement pass
     *       for this tenant already recorded this exact attempt: both asked
     *       under the same key, as ADR 0095 says a retry may, and both were
     *       told it succeeded. That is one charge, told twice, not two
     *       charges, and today's behaviour of writing nothing a second time
     *       is correct.
     *   <li>the row is {@code SUPERSEDED} — the tenant's card was swapped
     *       while this attempt's own {@code charge()} call was still
     *       outstanding, {@link #supersedeAndMintFresh} settled it
     *       {@code SUPERSEDED} and minted a fresh attempt under the new card
     *       believing the old charge would never land, and now it has: a
     *       real, previously unrecorded charge against a card the tenant no
     *       longer has on file. This is not the benign case above and must
     *       not be treated as one, or the money it moved vanishes from the
     *       books with no audit trail pointing at it.
     *   <li>the row is {@code FAILED} or {@code NOT_CONFIGURED} — a racing
     *       pass already told this exact attempt apart with a different
     *       answer and got there first. This {@code Succeeded} report no
     *       longer has anywhere to land; nothing is recorded for it.
     * </ul>
     */
    private long recordLateSucceededOutcome(
            UUID tenantId, CardAttempt attempt, CardCharger.Outcome.Succeeded succeeded, Instant now) {
        JdbcCardChargeAttemptStore.AttemptRecord stored = attempts.find(tenantId, attempt.attemptId())
                .orElseThrow(() -> new IllegalStateException("card charge attempt " + attempt.attemptId()
                        + " vanished after this settlement pass " + "itself read it"));
        return switch (stored.outcome()) {
            case "SUPERSEDED" -> recordChargeAfterSupersede(tenantId, attempt, succeeded, now);
            case "SUCCEEDED" -> {
                log.debug(
                        "A card charge for statement {} of tenant {} was already recorded by a racing "
                                + "settlement pass",
                        attempt.statementNumber(),
                        tenantId);
                yield 0L;
            }
            default -> {
                // FAILED or NOT_CONFIGURED: a racing pass told this attempt
                // apart with a different, and by definition equally true,
                // answer before this one arrived. Nothing to reconcile --
                // there is no second charge to account for, only one report
                // of the one thing that happened arriving late.
                log.debug(
                        "A card charge for statement {} of tenant {} reports Succeeded, but a racing pass had "
                                + "already settled the attempt {}; nothing to record for this late answer",
                        attempt.statementNumber(),
                        tenantId,
                        stored.outcome());
                yield 0L;
            }
        };
    }

    /**
     * A charge succeeded for real, on a card the tenant no longer has on
     * file, after its attempt had already been settled {@code SUPERSEDED}
     * and a fresh attempt charged and recorded in its place (ADR 0095). This
     * is real money the books must not drop: the row is moved from
     * {@code SUPERSEDED} to {@code SUCCEEDED} — keeping the provider's own
     * reference, so the row still names what actually happened — and the
     * amount is routed through exactly {@link #applySuccessfulCharge}, the
     * same clamp-before-append and surplus-refused path an ordinary success
     * takes. The statement this attempt was charged for is very likely
     * already paid in full by the attempt that superseded it, so most or all
     * of this money is expected to land as a refused surplus rather than a
     * ledger entry — which is exactly right: it still must not vanish
     * unaudited. An unambiguous audit fact names this attempt (and the fresh
     * one that replaced it, when it can still be found) so finance can
     * refund the old card by hand.
     */
    private long recordChargeAfterSupersede(
            UUID tenantId, CardAttempt attempt, CardCharger.Outcome.Succeeded succeeded, Instant now) {
        if (!attempts.settleSupersededSuccess(attempt.attemptId(), succeeded.providerReference(), now)) {
            // A racing report already moved this exact SUPERSEDED row to
            // SUCCEEDED -- the same "told twice" idempotency settle() already
            // gives a PENDING row, just entered through the SUPERSEDED door.
            log.debug(
                    "A card charge for statement {} of tenant {}, superseded while its own charge was in "
                            + "flight, was already recorded as charged by a racing report",
                    attempt.statementNumber(),
                    tenantId);
            return 0;
        }
        countCardCharge("succeeded_after_supersede");
        log.warn(
                "A card charge for statement {} of tenant {} succeeded on a card the tenant no longer has on "
                        + "file: attempt {} was superseded while its own charge() call was still outstanding, "
                        + "and its real success has only now arrived. Recorded as commercial.wallet"
                        + ".card_charge_after_supersede for finance to reconcile by hand.",
                attempt.statementNumber(),
                tenantId,
                attempt.attemptId());
        Optional<UUID> supersededBy =
                attempts.findMostRecentOtherAttempt(tenantId, attempt.statementId(), attempt.attemptId());
        audit.record(AuditFact.of("commercial.wallet.card_charge_after_supersede", AuditClass.BUSINESS)
                .by(ActorRef.systemJob("wallet-settlement"))
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.statement", attempt.statementId())
                .because("this attempt's own charge succeeded on the provider's side only after it had "
                        + "already been settled SUPERSEDED and replaced by a fresh attempt under a different "
                        + "card; the money is real, was never credited, and needs reconciling against the "
                        + "provider by hand")
                .changed(supersededBy
                        .<Map<String, Object>>map(newAttemptId -> Map.of(
                                "supersededAttemptId", attempt.attemptId().toString(),
                                "newAttemptId", newAttemptId.toString(),
                                "providerReference", succeeded.providerReference()))
                        .orElseGet(() -> Map.of(
                                "supersededAttemptId",
                                attempt.attemptId().toString(),
                                "providerReference",
                                succeeded.providerReference())))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .correlatedBy(attempt.statementId().toString())
                .occurredAt(now)
                .build());
        return applySuccessfulCharge(tenantId, attempt, succeeded, now);
    }

    /**
     * Credits the money for a card charge whose attempt is now durably
     * recorded {@code SUCCEEDED} — shared by the ordinary success path and
     * {@link #recordChargeAfterSupersede}, since both are the same thing from
     * here on: money the provider says it took, clamped to what the
     * statement still owes and never credited past that. What the statement
     * still owes is re-read under the lock this pass had to let go of around
     * the provider call, and clamped before anything is written — never
     * after. A transfer that arrived meanwhile has already paid part of it,
     * and money the statement no longer owes is never quietly credited as
     * extra paid balance: that is the one shape a card charged twice would
     * take at this table, so a surplus is refused here and audited instead,
     * for finance to reconcile against the provider by hand.
     */
    private long applySuccessfulCharge(
            UUID tenantId, CardAttempt attempt, CardCharger.Outcome.Succeeded succeeded, Instant now) {
        StatementPayment statement = openStatement(tenantId, attempt.currency(), attempt.statementId());
        long owed = statement == null ? 0 : Math.max(statement.dueMinor(), 0);
        long applied = Math.min(attempt.amountMinor(), owed);
        if (applied < attempt.amountMinor()) {
            long surplus = attempt.amountMinor() - applied;
            log.warn(
                    "A card charge for statement {} of tenant {} succeeded for {} but only {} was still "
                            + "owed; the surplus of {} is refused, not credited, and needs reconciling "
                            + "against the provider by hand",
                    attempt.statementNumber(),
                    tenantId,
                    attempt.amountMinor(),
                    applied,
                    surplus);
            audit.record(AuditFact.of("commercial.wallet.card_charge_surplus_refused", AuditClass.BUSINESS)
                    .by(ActorRef.systemJob("wallet-settlement"))
                    .at(ResourceScope.tenant(tenantId))
                    .target("commercial.statement", attempt.statementId())
                    .outcome(AuditFact.Outcome.REJECTED)
                    .because("the card charge succeeded for more than the statement still owed")
                    .changed(Map.of(
                            "amountMinor",
                            attempt.amountMinor(),
                            "appliedMinor",
                            applied,
                            "providerReference",
                            succeeded.providerReference()))
                    .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                    .correlatedBy(attempt.statementId().toString())
                    .occurredAt(now)
                    .build());
        }
        if (applied <= 0) {
            return 0;
        }
        audit.record(AuditFact.of("commercial.wallet.card_charged", AuditClass.BUSINESS)
                .by(ActorRef.systemJob("wallet-settlement"))
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.statement", attempt.statementId())
                .because("the statement's remainder was charged to the tenant's card")
                .changed(Map.of("amountMinor", applied, "providerReference", succeeded.providerReference()))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .correlatedBy(attempt.statementId().toString())
                .occurredAt(now)
                .build());
        // The money actually credited, never more than the statement
        // still owed: the provider's own reference is what proves the
        // charge happened, and V0211's unique index on it is what
        // stops a retry crediting it twice.
        appendMoneyIn(new WalletEntry(
                Ids.newId(),
                tenantId,
                WalletEntry.PAID,
                WalletEntry.TOP_UP,
                applied,
                attempt.currency(),
                null,
                null,
                null,
                null,
                succeeded.providerReference(),
                "card charge for statement %s".formatted(attempt.statementNumber()),
                SYSTEM_CARD_CHARGER,
                null,
                null,
                now));
        wallet.append(new WalletEntry(
                Ids.newId(),
                tenantId,
                WalletEntry.PAID,
                WalletEntry.STATEMENT_PAYMENT,
                -applied,
                attempt.currency(),
                attempt.statementId(),
                null,
                null,
                null,
                null,
                "statement %s paid by card".formatted(attempt.statementNumber()),
                SYSTEM_CARD_CHARGER,
                null,
                null,
                now));
        return applied;
    }

    private @Nullable StatementPayment openStatement(UUID tenantId, String currency, UUID statementId) {
        return wallet.openStatementsOldestFirst(tenantId, currency).stream()
                .filter(statement -> statement.statementId().equals(statementId))
                .findFirst()
                .orElse(null);
    }

    /**
     * One attempt about to be made, as the transaction that committed it
     * left it.
     *
     * @param reused whether this attempt already existed before this call —
     *               found on file for the statement, or discovered by a
     *               losing race on the insert below — rather than minted
     *               fresh here. A reused attempt's id is not new (ADR 0095,
     *               V0222): the caller asks the provider about it before
     *               charging again under the same key
     */
    private record CardAttempt(
            UUID attemptId,
            UUID statementId,
            String statementNumber,
            long amountMinor,
            String currency,
            @Nullable String cardTokenReference,
            boolean reused) {}

    private void countCardCharge(String outcome) {
        Counter.builder(CARD_CHARGE_METRIC)
                .description("ADR 0095 card collection of a statement's remainder, by how the charge ended")
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    // ------------------------------------------------------------ bonus expiry

    /** Expired bonus grants nothing has lapsed yet, oldest expiry first — for {@code WalletBonusExpirySweeper}. */
    public List<ExpiredGrantRef> findExpiredGrantCandidates(Instant now, int batchSize) {
        return wallet.expiredGrantCandidates(now, batchSize);
    }

    /**
     * Lapses one grant's unspent remainder, if it still has one, with a
     * {@code BONUS_EXPIRY} entry (ADR 0095, item 5). Idempotent: a grant
     * already fully drawn down before or at its expiry has nothing left to
     * lapse and this writes nothing for it.
     *
     * @return whether an entry was written
     */
    @Transactional
    public boolean expireGrantIfDue(UUID tenantId, UUID grantId, String currency, Instant now) {
        wallet.lockBilling(tenantId, now);
        long remaining = wallet.grantRemaining(tenantId, grantId);
        if (remaining <= 0) {
            return false;
        }
        UUID id = Ids.newId();
        wallet.append(new WalletEntry(
                id,
                tenantId,
                WalletEntry.BONUS,
                WalletEntry.BONUS_EXPIRY,
                -remaining,
                currency,
                null,
                grantId,
                null,
                null,
                null,
                "the bonus grant lapsed at its expiry",
                SYSTEM_BONUS_EXPIRY,
                null,
                null,
                now));

        audit.record(AuditFact.of("commercial.wallet.bonus_expired", AuditClass.BUSINESS)
                .by(ActorRef.systemJob("wallet-bonus-expiry-sweep"))
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because("the bonus grant lapsed at its expiry")
                .changed(Map.of("grantId", grantId.toString(), "lapsedMinor", remaining))
                .usingCapability(Capability.COMMERCIAL_WALLET_MANAGE.code())
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Appends money in, turning the database's refusal of a reference already
     * recorded for this tenant into a conflict the recorder can read.
     *
     * <p>V0211's unique index is what actually holds the line, because two
     * people recording the same transfer at the same moment would both read an
     * empty ledger before either wrote to it.
     */
    private void appendMoneyIn(WalletEntry entry) {
        String reference = Objects.requireNonNull(
                entry.externalReference(), "V0211's ck_wallet_entry_reference makes money in carry one");
        wallet.findMoneyInByNormalisedReference(entry.tenantId(), normalise(reference), uniqueAmong(entry.entryType()))
                .ifPresent(onFile -> {
                    throw alreadyRecorded(reference, onFile.externalReference());
                });
        try {
            wallet.append(entry);
        } catch (DuplicateKeyException raced) {
            // Two recorders inside the same millisecond, one of whom read the
            // ledger before the other wrote to it. The check above cannot see
            // that and the index can, so the index keeps the last word; the
            // message is the plainer one because there is nothing on file to
            // name yet from this transaction's point of view.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That reference is already recorded for this tenant; money in is recorded once");
        }
    }

    /**
     * Which entry types the reference has to be unique among, mirroring V0211's
     * two partial indexes: money in is unique among money in, and a deposit
     * reversal among deposit reversals — a reversal names the very deposit it
     * takes back, so the two must not collide with each other.
     */
    private static List<String> uniqueAmong(String entryType) {
        return WalletEntry.DEPOSIT_REVERSAL.equals(entryType)
                ? List.of(WalletEntry.DEPOSIT_REVERSAL)
                : List.of(WalletEntry.TOP_UP, WalletEntry.DEPOSIT);
    }

    /**
     * Tells a recorder which of two different things happened.
     *
     * <p>Uniqueness is on the normalised reference (V0211), so a second,
     * genuinely different wire whose reference differs from an earlier one only
     * in case, spacing, hyphens or a leading '#' is refused as well as a
     * re-typed one. Reported as "that reference is already recorded" it sent the
     * recorder to search the ledger for a string that is not in it, and the
     * money the tenant really paid went uncredited while its statement aged into
     * arrears. So the refusal names what is on file and says what to do: the
     * endpoint takes the reference verbatim, so a reference that tells the two
     * wires apart records the second one as the one-person audited transfer it
     * is, rather than laundering it through a maker-checker adjustment that
     * would carry no bank reference at all.
     */
    private static ApiException alreadyRecorded(String typed, @Nullable String onFile) {
        if (typed.equals(onFile)) {
            return new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "That reference is already recorded for this tenant; money in is recorded once");
        }
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                ("A different reference, \"%s\", is already recorded for this tenant and matches this one once "
                                + "case, spacing, hyphens and a leading '#' are ignored, so money in is already "
                                + "recorded once. If this is a second, genuine transfer, record it under a "
                                + "reference that tells it apart from that one.")
                        .formatted(onFile),
                Map.of("recordedReference", onFile == null ? "" : onFile, "typedReference", typed));
    }

    /** {@code null} when the approval let the caller proceed; the outcome to return otherwise. */
    private static @Nullable WalletChangeOutcome notYetDecided(ApprovalOutcome approval) {
        if (approval instanceof ApprovalOutcome.Declined declined) {
            return new WalletChangeOutcome(WalletChangeOutcome.DECLINED, declined.requestId());
        }
        if (!approval.mayProceed()) {
            UUID requestId = approval instanceof ApprovalOutcome.Pending pending ? pending.requestId() : null;
            return new WalletChangeOutcome(WalletChangeOutcome.AWAITING_APPROVAL, requestId);
        }
        return null;
    }

    private static @Nullable UUID requestIdOf(ApprovalOutcome approval) {
        return approval instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;
    }

    /**
     * Who gave the second signature.
     *
     * <p>{@code WALLET_ADJUSTMENT}, {@code WALLET_BONUS_GRANT}, {@code
     * WALLET_REFUND} and {@code WALLET_DEPOSIT_REVERSAL} are all fail-closed
     * and seeded at platform scope (V0211), so the only outcome that reaches
     * here is {@code Approved}. The other one {@code mayProceed()} admits is
     * {@code NotRequired}, which would mean somebody removed the policy — and
     * an entry written on one signature is exactly what ADR 0095 item 4
     * refuses, so this refuses it too rather than writing the requester's own
     * name into {@code approved_by}.
     */
    private static String approvedBy(ApprovalOutcome approval) {
        return approved(approval).approvedBy();
    }

    /**
     * Who proposed the change — the name the ledger row records as having made
     * it, whoever finally executed it.
     *
     * <p>Not the acting subject. V0071 settles that the executor need not be
     * the maker ("Ordinarily requested_by; the four-eyes rule governs who
     * decides, not who executes"), so the checker legitimately re-submits the
     * identical call themselves; writing the acting subject here would produce
     * a row naming one person as both recorder and approver of money leaving
     * the platform — which is what a finance reviewer reading
     * {@code commercial.wallet_entries} alone would have to take at face value.
     * V0211's {@code ck_wallet_entry_four_eyes} refuses such a row outright,
     * the way every other money table in this schema does; this is what keeps
     * the legitimate path from hitting it. Who actually executed is not lost:
     * {@code audit.approval_requests.consumed_by} records it, and the audit
     * fact beside each entry is recorded {@code .by(actor)}.
     */
    private static String recordedBy(ApprovalOutcome approval) {
        return approved(approval).requestedBy();
    }

    private static ApprovalOutcome.Approved approved(ApprovalOutcome approval) {
        if (approval instanceof ApprovalOutcome.Approved approved) {
            return approved;
        }
        throw new ApiException(
                ErrorCode.APPROVAL_POLICY_REQUIRED, "A wallet change moves nothing without a second person's approval");
    }

    /**
     * Refuses a reference that would leave nothing to be unique on.
     *
     * <p>{@code ux_wallet_entry_money_in_reference} is unique on the
     * <em>normalised</em> reference (V0211) — upper case, no whitespace, no
     * hyphens, no leading '#' — because "MT103-7" and " mt103 7" are one
     * transfer typed twice. A reference of nothing but those characters
     * normalises to the empty string, which would make every such record
     * collide with every other, so it is refused at the door instead.
     */
    private static void requireReference(@Nullable String reference, String message) {
        if (reference == null || reference.isBlank() || normalise(reference).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    /** The rule V0211's generated column applies, kept here only to reject a reference that normalises away. */
    private static String normalise(String reference) {
        return reference.replaceFirst("^#", "").replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static void requireMoneyKind(String moneyKind) {
        if (!WalletEntry.PAID.equals(moneyKind) && !WalletEntry.BONUS.equals(moneyKind)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "moneyKind is one of PAID or BONUS");
        }
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }

    // ------------------------------------------------------------- approval hashes

    // What the approval is bound to, and -- through ApprovalParameters.sign()
    // -- what the checker's console renders, from one pass over the same
    // components. amountMinor is the SIGNED amount as it will reach the ledger
    // in every one of the four, so the row the approver reads and the row the
    // ledger ends up holding say the same thing; the public methods keep taking
    // a positive amount where an operator types one, and negate it here.

    private record AdjustmentCommand(
            UUID tenantId,
            String moneyKind,
            @Nullable UUID grantId,
            long amountMinor,
            String currency,
            String reason) {}

    private record BonusGrantCommand(
            UUID tenantId, long amountMinor, Instant expiresAt, String currency, String reason) {}

    private record RefundCommand(
            UUID tenantId, long amountMinor, String payoutReference, String currency, String reason) {}

    private record DepositReversalCommand(
            UUID tenantId,
            UUID depositEntryId,
            UUID subscriptionId,
            long amountMinor,
            String currency,
            String reason) {}

    /** What a manual change did: applied, waiting for a second signature, or declined. */
    public record WalletChangeOutcome(
            String status, @Nullable UUID approvalRequestId) {
        public static final String CHANGED = "CHANGED";
        public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
        public static final String DECLINED = "DECLINED";
    }
}
