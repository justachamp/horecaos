package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import uz.horecaos.platform.commercial.domain.BonusGrantBalance;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;
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
 * <p><strong>Manual changes.</strong> An adjustment, a bonus grant and a
 * refund are proposed by one person and approved by a different one, through
 * the same ADR 0027 approval model {@code TenantProfileService.changeCountry}
 * uses for a change of country: the first call raises the request and
 * answers {@code AWAITING_APPROVAL}; the identical call again, after a
 * different person approves it, performs the change and spends the
 * signature. Recording a transfer or a deposit is not a correction — it is
 * one person's audited act, like {@code StatementService.issue}.
 */
@Service
public class WalletService {

    /** {@code recorded_by} on an entry no person wrote — the settlement pass at issue time or later. */
    private static final String SYSTEM_SETTLEMENT = "system:wallet-settlement";

    private static final String SYSTEM_CARD_CHARGER = "system:card-charger";
    private static final String SYSTEM_BONUS_EXPIRY = "system:bonus-expiry-sweep";

    private final JdbcWalletStore wallet;
    private final JdbcSubscriptionStore subscriptions;
    private final ApprovalService approvals;
    private final CardCharger cardCharger;
    private final AuditRecorder audit;
    private final Clock clock;

    public WalletService(
            JdbcWalletStore wallet,
            JdbcSubscriptionStore subscriptions,
            ApprovalService approvals,
            CardCharger cardCharger,
            AuditRecorder audit,
            Clock clock) {
        this.wallet = wallet;
        this.subscriptions = subscriptions;
        this.approvals = approvals;
        this.cardCharger = cardCharger;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------- reads

    public WalletBalances balances(UUID tenantId) {
        return wallet.balances(tenantId, wallet.currencyOf(tenantId));
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
        if (bankReference == null || bankReference.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A transfer carries the bank's reference");
        }
        wallet.lockBilling(tenantId, clock.instant());
        Instant now = clock.instant();
        UUID id = Ids.newId();
        wallet.append(new WalletEntry(
                id,
                tenantId,
                WalletEntry.PAID,
                WalletEntry.TOP_UP,
                amountMinor,
                wallet.currencyOf(tenantId),
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
     */
    @Transactional
    public UUID recordDeposit(
            UUID tenantId, String bankReference, ActorRef actor, String reason, String correlationId) {
        if (bankReference == null || bankReference.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A deposit carries the bank's reference");
        }
        wallet.lockBilling(tenantId, clock.instant());
        long due = subscriptions.liveDepositDue(tenantId);
        if (due <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The tenant has no activation deposit due");
        }
        Subscription live = subscriptions
                .findLive(tenantId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The tenant has no live subscription"));

        Instant now = clock.instant();
        UUID id = Ids.newId();
        wallet.append(new WalletEntry(
                id,
                tenantId,
                WalletEntry.PAID,
                WalletEntry.DEPOSIT,
                due,
                wallet.currencyOf(tenantId),
                null,
                null,
                null,
                bankReference,
                reason,
                subject(actor),
                null,
                null,
                now));
        subscriptions.clearDepositDue(tenantId, live.id());

        audit.record(AuditFact.of("commercial.wallet.deposit_recorded", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.wallet_entry", id)
                .because(reason)
                .changed(Map.of("amountMinor", due))
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
        AdjustmentCommand command = new AdjustmentCommand(tenantId, moneyKind, grantId, amountMinor, reason);
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.WALLET_ADJUSTMENT.code(),
                ApprovalParameters.of(command).excluding().hash(),
                ResourceScope.tenant(tenantId),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));

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
                reason,
                subject(actor),
                approvedBy(approval),
                requestId,
                now));

        audit.record(AuditFact.of("commercial.wallet.adjustment.applied", AuditClass.BUSINESS)
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
        BonusGrantCommand command = new BonusGrantCommand(tenantId, amountMinor, expiresAt, reason);
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.WALLET_BONUS_GRANT.code(),
                ApprovalParameters.of(command).excluding().hash(),
                ResourceScope.tenant(tenantId),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));

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
                expiresAt,
                null,
                reason,
                subject(actor),
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
        RefundCommand command = new RefundCommand(tenantId, amountMinor, payoutReference, reason);
        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.WALLET_REFUND.code(),
                ApprovalParameters.of(command).excluding().hash(),
                ResourceScope.tenant(tenantId),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));

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
                payoutReference,
                reason,
                subject(actor),
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

        audit.record(AuditFact.of("commercial.wallet.payment_method.changed", AuditClass.BUSINESS)
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
     * @return how much this pass paid across every statement, in the
     *         tenant's billing currency's minor units
     */
    @Transactional
    public long applyAvailableFunds(UUID tenantId) {
        Instant now = clock.instant();
        TenantBilling billing = wallet.lockBilling(tenantId, now);
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
                        "statement %s paid from the paid balance".formatted(statement.number()),
                        SYSTEM_SETTLEMENT,
                        null,
                        null,
                        now));
                paidBalance -= draw;
                remainingDue -= draw;
                totalPaid += draw;
            }
            if (remainingDue > 0) {
                totalPaid += attemptCardCharge(tenantId, billing, statement, remainingDue, currency, now);
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
     * Charges a CARD tenant's stored card for what a statement still owes
     * after bonus and paid money (ADR 0095, item 8). {@code NotConfigured} —
     * the only answer today, with no merchant account — leaves the
     * remainder due, exactly like {@code INVOICE}.
     */
    private long attemptCardCharge(
            UUID tenantId,
            TenantBilling billing,
            StatementPayment statement,
            long amountMinor,
            String currency,
            Instant now) {
        if (billing.paymentMethod() != PaymentMethod.CARD) {
            return 0;
        }
        CardCharger.Outcome outcome = cardCharger.charge(
                tenantId,
                billing.cardTokenReference(),
                amountMinor,
                currency,
                statement.statementId().toString());
        if (!(outcome instanceof CardCharger.Outcome.Succeeded succeeded)) {
            return 0;
        }
        wallet.append(new WalletEntry(
                Ids.newId(),
                tenantId,
                WalletEntry.PAID,
                WalletEntry.TOP_UP,
                amountMinor,
                currency,
                null,
                null,
                null,
                succeeded.providerReference(),
                "card charge for statement %s".formatted(statement.number()),
                SYSTEM_CARD_CHARGER,
                null,
                null,
                now));
        wallet.append(new WalletEntry(
                Ids.newId(),
                tenantId,
                WalletEntry.PAID,
                WalletEntry.STATEMENT_PAYMENT,
                -amountMinor,
                currency,
                statement.statementId(),
                null,
                null,
                null,
                "statement %s paid by card".formatted(statement.number()),
                SYSTEM_CARD_CHARGER,
                null,
                null,
                now));
        return amountMinor;
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
     * <p>{@code WALLET_ADJUSTMENT}, {@code WALLET_BONUS_GRANT} and {@code
     * WALLET_REFUND} are all fail-closed and seeded at platform scope (V0211),
     * so the only outcome that reaches here is {@code Approved}. The other one
     * {@code mayProceed()} admits is {@code NotRequired}, which would mean
     * somebody removed the policy — and an entry written on one signature is
     * exactly what ADR 0095 item 4 refuses, so this refuses it too rather than
     * writing the requester's own name into {@code approved_by}.
     */
    private static String approvedBy(ApprovalOutcome approval) {
        if (approval instanceof ApprovalOutcome.Approved approved) {
            return approved.approvedBy();
        }
        throw new ApiException(
                ErrorCode.APPROVAL_POLICY_REQUIRED, "A wallet change moves nothing without a second person's approval");
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

    private record AdjustmentCommand(
            UUID tenantId, String moneyKind, @Nullable UUID grantId, long amountMinor, String reason) {}

    private record BonusGrantCommand(UUID tenantId, long amountMinor, Instant expiresAt, String reason) {}

    private record RefundCommand(UUID tenantId, long amountMinor, String payoutReference, String reason) {}

    /** What a manual change did: applied, waiting for a second signature, or declined. */
    public record WalletChangeOutcome(
            String status, @Nullable UUID approvalRequestId) {
        public static final String CHANGED = "CHANGED";
        public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
        public static final String DECLINED = "DECLINED";
    }
}
