package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderAwaitingApproval;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderExpired;
import uz.horecaos.platform.ordering.api.OrderRejected;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.ApprovalTimeoutAction;
import uz.horecaos.platform.ordering.domain.CustomerRefund;
import uz.horecaos.platform.ordering.domain.LiabilityParty;
import uz.horecaos.platform.ordering.domain.OrderOutcome;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.ordering.domain.StockDisposition;
import uz.horecaos.platform.ordering.domain.TerminalOutcomeKind;
import uz.horecaos.platform.ordering.domain.TransitionTrigger;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.ApprovalDecisionRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.tenancy.api.LocationCapacityPort;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Everything that moves an order after checkout (ADR 0002, ADR 0019).
 *
 * <p>One rule governs the whole class: a status change is a conditional UPDATE
 * naming the status it expects, and the row count decides who won. Two operators
 * pressing approve and reject in the same second, a timeout firing against an
 * order that was confirmed a moment earlier, and a duplicate command replayed
 * from a channel all reduce to the same question, answered by PostgreSQL rather
 * than by whichever thread was scheduled first.
 *
 * <p>The loser is never an error. It is told what actually happened, because a
 * restaurant that pressed reject and got a 500 will press it again, and the
 * second attempt has to give the same answer as the first.
 */
@Service
public class OrderStateService {

    private static final Logger log = LoggerFactory.getLogger(OrderStateService.class);

    /** The actor a transition records when a payment capture drove it, nobody clicked. */
    private static final String PAYMENT_CAPTURE_ACTOR = "payment-capture";

    private final JdbcOrderStore orders;
    private final LocationCapacityPort capacity;
    private final OrderInventoryProcess inventoryProcess;
    private final OrderAcceptancePolicyService acceptancePolicies;
    private final OrderSettlementPort settlements;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderStateService(
            JdbcOrderStore orders,
            LocationCapacityPort capacity,
            OrderInventoryProcess inventoryProcess,
            OrderAcceptancePolicyService acceptancePolicies,
            OrderSettlementPort settlements,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.orders = orders;
        this.capacity = capacity;
        this.inventoryProcess = inventoryProcess;
        this.acceptancePolicies = acceptancePolicies;
        this.settlements = settlements;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /**
     * A restaurant approve or reject, from Operations or from a POS channel.
     *
     * <p>Every command is recorded, including the one that loses. Storing only the
     * winner would make an operator's rejected click invisible and leave "who
     * tried to reject this, and when" unanswerable — which is exactly the question
     * asked after a disputed order.
     */
    @Transactional
    public DecisionResult decide(UUID tenantId, UUID orderId, DecisionCommand command) {
        Instant now = clock.instant();

        OrderRow order = orders.find(tenantId, orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        // A repeat of one human decision. Returning the settled outcome rather
        // than applying it again is what makes a retried click harmless.
        Optional<ApprovalDecisionRow> alreadySeen = orders.findDecision(tenantId, orderId, command.decisionId());
        if (alreadySeen.isPresent()) {
            return settledOutcome(tenantId, orderId, alreadySeen.get(), order);
        }

        UUID decisionRowId = UUID.randomUUID();
        orders.insertApprovalDecision(
                decisionRowId,
                tenantId,
                orderId,
                command.decisionId(),
                command.action().name(),
                command.decisionChannel(),
                command.actorType(),
                command.actorId(),
                command.reasonCode(),
                command.issuedAt());

        if (order.status() != OrderStatus.AWAITING_APPROVAL) {
            // The order settled some other way — a timeout, a cancellation, or an
            // earlier decision. The command is on record and inert, and audited,
            // because a restaurant asking "I did reject that order" is asking about
            // exactly this case.
            recordAudit(
                    order,
                    "ordering.order.approval-decision",
                    command.actorType(),
                    command.actorId(),
                    command.reasonCode(),
                    order.version(),
                    Map.of(
                            "action",
                            command.action().name(),
                            "decisionId",
                            command.decisionId(),
                            "channel",
                            command.decisionChannel(),
                            "settledStatus",
                            order.status().name()),
                    AuditFact.Outcome.REJECTED,
                    command.correlationId(),
                    now);
            return new DecisionResult(
                    false,
                    order.status(),
                    order.version(),
                    orders.findEffectiveDecision(tenantId, orderId).orElse(null));
        }

        OrderStatus target = command.action() == DecisionAction.APPROVE ? OrderStatus.CONFIRMED : OrderStatus.REJECTED;
        OrderStateMachine.require(OrderStatus.AWAITING_APPROVAL, target);

        // ADR 0039: whoever approves is who accepted the order, and the fact is
        // written by the same statement that moves the status. Two statements
        // could commit apart, and an order confirmed by nobody is exactly the gap
        // the column exists to close.
        Optional<Integer> won = orders.transition(
                tenantId, orderId, OrderStatus.AWAITING_APPROVAL, target, now, command.actorType(), command.actorId());

        if (won.isEmpty()) {
            // Somebody else moved the order between the read above and this
            // statement. Their outcome stands; this decision remains on record as
            // not effective.
            OrderRow settled = orders.find(tenantId, orderId).orElseThrow();
            log.info(
                    "Decision {} on order {} lost the race; order is {}",
                    command.decisionId(),
                    orderId,
                    settled.status());
            // The losing command is audited too. "Who tried to reject this order,
            // and when" is asked after every dispute, and an audit trail that
            // records only the winner cannot answer it.
            recordAudit(
                    settled,
                    "ordering.order.approval-decision",
                    command.actorType(),
                    command.actorId(),
                    command.reasonCode(),
                    settled.version(),
                    Map.of(
                            "action",
                            command.action().name(),
                            "decisionId",
                            command.decisionId(),
                            "channel",
                            command.decisionChannel(),
                            "settledStatus",
                            settled.status().name()),
                    AuditFact.Outcome.REJECTED,
                    command.correlationId(),
                    now);
            return new DecisionResult(
                    false,
                    settled.status(),
                    settled.version(),
                    orders.findEffectiveDecision(tenantId, orderId).orElse(null));
        }

        int version = won.get();
        orders.markDecisionEffective(tenantId, decisionRowId);
        orders.recordTransition(
                tenantId,
                orderId,
                version,
                OrderStatus.AWAITING_APPROVAL,
                target,
                TransitionTrigger.APPROVAL_DECISION,
                command.reasonCode(),
                command.actorType(),
                command.actorId(),
                command.correlationId(),
                now);
        orders.cancelTimer(tenantId, orderId, CheckoutService.APPROVAL_TIMER, now);

        // ADR 0039: a rejection is its own commercial fact, not a cancellation
        // wearing the same status. The restaurant refused, before anything was
        // cooked and before the hold was committed, so the stock always goes back
        // and the tenant carries whatever the refusal cost.
        OrderOutcome outcome = target == OrderStatus.REJECTED
                ? new OrderOutcome(
                        TerminalOutcomeKind.REJECTED,
                        OutcomeSystemCategory.RESTAURANT_REFUSED,
                        null,
                        null,
                        null,
                        StockDisposition.RELEASE,
                        LiabilityParty.TENANT,
                        CustomerRefund.FULL,
                        reservationCommitted(order),
                        command.noteEncrypted())
                : null;

        applyConsequences(
                order,
                target,
                version,
                command.reasonCode(),
                command.decisionChannel(),
                command.actorType(),
                command.actorId(),
                outcome,
                now);

        recordAudit(
                order,
                "ordering.order.approval-decision",
                command.actorType(),
                command.actorId(),
                command.reasonCode(),
                version,
                Map.of(
                        "action",
                        command.action().name(),
                        "decisionId",
                        command.decisionId(),
                        "channel",
                        command.decisionChannel(),
                        "toStatus",
                        target.name()),
                AuditFact.Outcome.SUCCEEDED,
                command.correlationId(),
                now);

        return new DecisionResult(
                true,
                target,
                version,
                orders.findEffectiveDecision(tenantId, orderId).orElse(null));
    }

    /**
     * The order's status and effective decision, read without attempting one
     * (wave 24) — what {@code OrderDecisionPortAdapter.settledDecisionIfAny}
     * answers a bare Reject tap with, before deciding whether to present the
     * reason picker or the "already settled" answer {@link #decide} itself
     * gives a losing command.
     */
    public Optional<CurrentState> currentState(UUID tenantId, UUID orderId) {
        return orders.find(tenantId, orderId)
                .map(order -> new CurrentState(
                        order.status(),
                        order.version(),
                        orders.findEffectiveDecision(tenantId, orderId).orElse(null)));
    }

    /**
     * The provider's money has landed against an order still waiting on it
     * (ADR 0013, ADR 0019).
     *
     * <p>The missing half of ADR 0019 step 8: {@code CheckoutService.awaitPayment}
     * holds a {@code BEFORE_CONFIRMATION} order in {@code PAYMENT_AUTHORIZING} and
     * arms nothing, on the documented understanding that a payment capture is what
     * moves it on. Until this method existed, nothing did, and a fully paid order
     * sat there for ever.
     *
     * <p>The acceptance policy consulted is the one {@link #approvalDeadlineReached}
     * also uses: pinned to the order at checkout (ADR 0030), never re-resolved, so
     * a policy edited after checkout cannot change what an already-placed order is
     * permitted to do. A paid order lands exactly where an equivalent cash order
     * would — {@code AUTO_CONFIRM} confirms it immediately, {@code
     * RESTAURANT_APPROVAL} sends it to {@code AWAITING_APPROVAL} with the same
     * approval timer {@code CheckoutService.awaitApproval} would have armed had the
     * money been in hand at checkout — and the events a consumer expects on either
     * path, {@link OrderConfirmed} or {@link OrderAwaitingApproval}, fire exactly
     * as they would from there.
     *
     * <p>Idempotent the same way every other transition here is: a conditional
     * UPDATE naming {@code PAYMENT_AUTHORIZING} as the status it expects. A
     * duplicate delivery of the capture fact finds the order already moved and
     * applies nothing; an order that left {@code PAYMENT_AUTHORIZING} some other
     * way — cancelled while the customer was on the provider's page, or expired —
     * finds the same and is left exactly as it is. The money itself is never in
     * question here: {@code CapturedMoneyPort} records it against the settlement
     * regardless of what this method decides, in the same transaction, which is
     * what makes a late capture for an order that has already ended a recorded,
     * harmless no-op rather than a crash or a resurrected order.
     */
    @Transactional
    public DecisionResult paymentCaptured(UUID tenantId, UUID orderId, Instant capturedAt) {
        Instant now = capturedAt == null ? clock.instant() : capturedAt;

        OrderRow order = orders.find(tenantId, orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.status() != OrderStatus.PAYMENT_AUTHORIZING) {
            log.info(
                    "A payment was captured for order {}, which is already {} rather than "
                            + "PAYMENT_AUTHORIZING; the order is left as it is and the money stays "
                            + "recorded against the settlement",
                    orderId,
                    order.status());
            return new DecisionResult(false, order.status(), order.version(), null);
        }

        var policy = acceptancePolicies.pinned(order.acceptancePolicyId(), order.acceptancePolicyVersion());
        boolean approvalRequired = policy.mode() == AcceptanceMode.RESTAURANT_APPROVAL;
        OrderStatus target = approvalRequired ? OrderStatus.AWAITING_APPROVAL : OrderStatus.CONFIRMED;
        OrderStateMachine.require(OrderStatus.PAYMENT_AUTHORIZING, target);

        Optional<Integer> won = orders.transition(tenantId, orderId, OrderStatus.PAYMENT_AUTHORIZING, target, now);
        if (won.isEmpty()) {
            // Lost a race against a cancellation or a duplicate delivery arriving
            // concurrently. Whatever settled it stands; this call applies nothing.
            OrderRow settled = orders.find(tenantId, orderId).orElseThrow();
            log.info("A payment capture for order {} lost the race to move it; order is {}", orderId, settled.status());
            return new DecisionResult(false, settled.status(), settled.version(), null);
        }

        int version = won.get();
        orders.recordTransition(
                tenantId,
                orderId,
                version,
                OrderStatus.PAYMENT_AUTHORIZING,
                target,
                TransitionTrigger.PAYMENT_RESULT,
                "PAYMENT_CAPTURED",
                "SYSTEM_JOB",
                PAYMENT_CAPTURE_ACTOR,
                null,
                now);

        if (approvalRequired) {
            Instant deadline = now.plus(policy.approvalTimeout());
            orders.insertTimer(tenantId, orderId, CheckoutService.APPROVAL_TIMER, deadline);
            // Checkout wrote a hypothetical deadline onto the order itself and
            // armed no timer for a BEFORE_CONFIRMATION order; correct it to the
            // instant the real timer above actually uses.
            orders.armApprovalDeadline(tenantId, orderId, deadline);
            events.publishEvent(new OrderAwaitingApproval(
                    UUID.randomUUID(),
                    new TenantId(tenantId),
                    orderId,
                    now,
                    order.brandId(),
                    order.locationId(),
                    policy.approvalChannel().name(),
                    deadline,
                    policy.timeoutAction().name(),
                    OrderStatus.AWAITING_APPROVAL.name(),
                    version));
        } else {
            // Same consequence CheckoutService.confirmImmediately and
            // OrderStateService.applyConsequences both draw from CONFIRMED: the
            // reservation this order already holds becomes a committed sale.
            inventoryProcess.enqueueCommit(orderId, tenantId, order.pricingQuoteId(), now);
            events.publishEvent(new OrderConfirmed(
                    UUID.randomUUID(),
                    new TenantId(tenantId),
                    orderId,
                    now,
                    order.brandId(),
                    order.locationId(),
                    policy.mode().name(),
                    null,
                    now,
                    order.currency(),
                    order.totalMinor(),
                    OrderStatus.CONFIRMED.name(),
                    version));
        }

        recordAudit(
                order,
                "ordering.order.payment-captured",
                "SYSTEM_JOB",
                PAYMENT_CAPTURE_ACTOR,
                "PAYMENT_CAPTURED",
                version,
                Map.of("fromStatus", OrderStatus.PAYMENT_AUTHORIZING.name(), "toStatus", target.name()),
                AuditFact.Outcome.SUCCEEDED,
                null,
                now);

        return new DecisionResult(true, target, version, null);
    }

    /**
     * The approval deadline lapsed.
     *
     * <p>Goes through exactly the same conditional update as a human decision, so
     * a restaurant confirming at the last second and the timer firing at the same
     * instant settle at one outcome rather than both applying.
     *
     * <p>{@code AUTO_REJECT} produces {@link OrderStatus#EXPIRED} and not
     * {@code REJECTED}: "the restaurant declined" and "the restaurant never
     * looked" are different facts with different customer wording and different
     * branch metrics, and collapsing them hides the second inside the first.
     */
    @Transactional
    public DecisionResult approvalDeadlineReached(UUID tenantId, UUID orderId) {
        Instant now = clock.instant();
        OrderRow order = orders.find(tenantId, orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.status() != OrderStatus.AWAITING_APPROVAL) {
            return new DecisionResult(
                    false,
                    order.status(),
                    order.version(),
                    orders.findEffectiveDecision(tenantId, orderId).orElse(null));
        }

        // The policy the order was created under, not the one in force now. A
        // manager who switched the branch to auto-confirm this afternoon has not
        // thereby changed what this morning's order was permitted to do.
        var policy = acceptancePolicies.pinned(order.acceptancePolicyId(), order.acceptancePolicyVersion());

        OrderStatus target = policy.timeoutAction() == ApprovalTimeoutAction.AUTO_CONFIRM
                ? OrderStatus.CONFIRMED
                : OrderStatus.EXPIRED;

        // A machine principal appears as one, so an auto-confirmed order shows
        // "система" rather than an empty cell. The legacy dashboard rendered
        // "Оператор: (Не указан)" in red on every order nobody typed, which
        // trained staff to ignore the field.
        Optional<Integer> won = orders.transition(
                tenantId, orderId, OrderStatus.AWAITING_APPROVAL, target, now, "SYSTEM_JOB", "order-approval-timeout");
        if (won.isEmpty()) {
            OrderRow settled = orders.find(tenantId, orderId).orElseThrow();
            return new DecisionResult(
                    false,
                    settled.status(),
                    settled.version(),
                    orders.findEffectiveDecision(tenantId, orderId).orElse(null));
        }

        int version = won.get();
        orders.recordTransition(
                tenantId,
                orderId,
                version,
                OrderStatus.AWAITING_APPROVAL,
                target,
                TransitionTrigger.APPROVAL_TIMEOUT,
                "APPROVAL_DEADLINE_REACHED",
                "SYSTEM_JOB",
                "order-approval-timeout",
                null,
                now);

        if (target == OrderStatus.CONFIRMED) {
            // An auto-confirm on timeout is a decision in every sense that matters
            // to an audit, so it is recorded as one — with SYSTEM_TIMEOUT as its
            // channel, so nobody later mistakes it for a person's click.
            UUID decisionRowId = UUID.randomUUID();
            orders.insertApprovalDecision(
                    decisionRowId,
                    tenantId,
                    orderId,
                    "timeout:" + orderId,
                    "APPROVE",
                    "SYSTEM_TIMEOUT",
                    "SYSTEM_JOB",
                    "order-approval-timeout",
                    "APPROVAL_DEADLINE_REACHED",
                    now);
            orders.markDecisionEffective(tenantId, decisionRowId);
        }

        // ADR 0039: "the restaurant declined" and "the restaurant never looked"
        // are different facts with different branch metrics, and no operator picks
        // the second one — so it carries the platform's own category and no tenant
        // reason. Inventing one would put a tenant's wording on a fact they had no
        // part in.
        OrderOutcome outcome = target == OrderStatus.EXPIRED
                ? new OrderOutcome(
                        TerminalOutcomeKind.EXPIRED,
                        OutcomeSystemCategory.APPROVAL_DEADLINE_LAPSED,
                        null,
                        null,
                        null,
                        StockDisposition.RELEASE,
                        LiabilityParty.TENANT,
                        CustomerRefund.FULL,
                        reservationCommitted(order),
                        null)
                : null;

        applyConsequences(
                order,
                target,
                version,
                "APPROVAL_DEADLINE_REACHED",
                "SYSTEM_TIMEOUT",
                "SYSTEM_JOB",
                "order-approval-timeout",
                outcome,
                now);

        log.info("Order {} reached its approval deadline and is now {}", orderId, target);
        return new DecisionResult(
                true,
                target,
                version,
                orders.findEffectiveDecision(tenantId, orderId).orElse(null));
    }

    /**
     * An operations action along the kitchen path.
     *
     * <p>Guarded by the canonical machine, including the fulfilment-mode split at
     * {@code READY}: a pickup order must not be able to enter {@code FULFILLING},
     * where it would wait for a courier that does not exist.
     */
    @Transactional
    public DecisionResult advance(
            UUID tenantId,
            UUID orderId,
            OrderStatus target,
            int expectedVersion,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable String correlationId) {
        return advance(tenantId, orderId, target, expectedVersion, reasonCode, actorType, actorId, correlationId, null);
    }

    /**
     * The same action, with the completion reason an operator picked (ADR 0039).
     *
     * <p>A completion is not merely a status change. «Самовывоз выполнен» and
     * «Доставлен сторонней службой» are different facts, and both the courier SLA
     * report and the external-logistics settlement are built on the distinction;
     * an order ending {@code COMPLETED} with nothing else recorded cannot tell a
     * manager whether a courier was owed for it.
     *
     * @param completion the reason the operator chose, or null to record the one
     *                   the order's fulfilment mode implies — ADR 0039 keeps the
     *                   dialog away from an action performed three hundred times a
     *                   shift, because a dialog that is always confirmed teaches
     *                   people to click through dialogs
     */
    @Transactional
    public DecisionResult advance(
            UUID tenantId,
            UUID orderId,
            OrderStatus target,
            int expectedVersion,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable String correlationId,
            @Nullable OrderOutcome completion) {

        Instant now = clock.instant();
        OrderRow order = orders.find(tenantId, orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.version() != expectedVersion) {
            throw new StaleOrderException(expectedVersion, order.version());
        }
        if (!OrderStateMachine.permits(order.status(), target, order.fulfillmentMode())) {
            throw new OrderStateMachine.IllegalTransitionException(order.status(), target);
        }

        Optional<Integer> won = orders.transition(tenantId, orderId, order.status(), target, now);
        if (won.isEmpty()) {
            OrderRow settled = orders.find(tenantId, orderId).orElseThrow();
            throw new StaleOrderException(expectedVersion, settled.version());
        }

        int version = requireCallersVersion(expectedVersion, won.get());
        orders.recordTransition(
                tenantId,
                orderId,
                version,
                order.status(),
                target,
                TransitionTrigger.OPERATIONS_ACTION,
                reasonCode,
                actorType,
                actorId,
                correlationId,
                now);

        OrderOutcome outcome = null;
        if (target == OrderStatus.COMPLETED) {
            outcome = completion != null
                    ? completion
                    : new OrderOutcome(
                            TerminalOutcomeKind.COMPLETED,
                            OutcomeSystemCategory.defaultCompletionFor(order.fulfillmentMode()),
                            null,
                            null,
                            null,
                            StockDisposition.NO_EFFECT,
                            null,
                            null,
                            true,
                            null);
        }

        applyConsequences(order, target, version, reasonCode, null, actorType, actorId, outcome, now);
        recordAudit(
                order,
                "ordering.order.state-action",
                actorType,
                actorId,
                reasonCode,
                version,
                Map.of("fromStatus", order.status().name(), "toStatus", target.name()),
                AuditFact.Outcome.SUCCEEDED,
                correlationId,
                now);
        return new DecisionResult(true, target, version, null);
    }

    /**
     * A kitchen ticket telling the order what the food just did (ADR 0041).
     *
     * <p>A proposal, not a command, and the whole design is in that word. The
     * kitchen knows one thing — where the food is — and knows nothing about the
     * approval channel, the acceptance policy, the payment, or the amendment that
     * may have changed the order while a cook was at the grill. So it says what
     * happened and ordering decides whether that is a transition, and the answer
     * is a value rather than an exception: a cook cannot interpret an exception,
     * and failing the station advance because the order would not move would
     * leave the kitchen unable to record that the food is ready.
     *
     * <p>No expected version, which is the one visible difference from
     * {@link #advance}. An operator's advance presents a version because they are
     * asserting they have seen the order's current shape; a ticket asserts
     * nothing of the kind, and demanding a version would make every ADR 0039
     * amendment during service turn the next station advance into a spurious
     * conflict. The precondition it does present is the one it can honestly make:
     * the status it believes the order is in, named in the conditional UPDATE.
     *
     * <p>Idempotency is a ledger rather than a status comparison, because the two
     * differ exactly where it matters. An offline client replaying a queued
     * {@code PREPARING} against an order that has since reached {@code READY}
     * would be told {@code REFUSED} by a status comparison, and a refusal that is
     * really a replay is a false alarm on a board. V0087's key is claimed before
     * the transition is attempted and settled in the same transaction, so a
     * replay is answered with what happened the first time.
     */
    @Transactional
    public ProgressProposal proposeProgress(
            UUID tenantId,
            UUID orderId,
            OrderStatus target,
            String idempotencyKey,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable String correlationId) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // A programming error in the caller, not a refusal: without a key
            // there is nothing that makes a replay safe, and answering REFUSED
            // would hide that behind a plausible-looking outcome.
            throw new IllegalArgumentException("A progress proposal needs an idempotency key");
        }

        Instant now = clock.instant();
        // AuditFact refuses a USER actor with no reason, and a proposal that blew
        // up on the audit would take a cook's station advance with it.
        String reason = reasonCode == null || reasonCode.isBlank() ? "ORDER_PROGRESS_PROPOSAL" : reasonCode;

        Optional<JdbcOrderStore.ProgressProposalRow> seen = orders.findProgressProposal(tenantId, idempotencyKey);
        if (seen.isPresent()) {
            return replayOf(seen.get(), orderId, idempotencyKey);
        }

        Optional<OrderRow> found = orders.find(tenantId, orderId);
        if (found.isEmpty()) {
            // Not an exception. An order that does not exist for this tenant is
            // an order this kitchen cannot move, which is what REFUSED means, and
            // the ticket carries on holding the food it actually has.
            log.warn("A progress proposal named order {}, which does not belong to tenant {}", orderId, tenantId);
            return ProgressProposal.REFUSED;
        }
        OrderRow order = found.get();

        Optional<UUID> claimed = orders.claimProgressProposal(
                tenantId,
                orderId,
                idempotencyKey,
                target,
                reason,
                actorType == null ? "SERVICE" : actorType,
                actorId,
                correlationId,
                now);
        if (claimed.isEmpty()) {
            // The pre-check found nothing and the claim still conflicted, so an
            // identical proposal is in flight in another transaction and will
            // apply whatever is applicable. Producing a second effect is the one
            // thing this must not do.
            log.debug("A twin of proposal {} is in flight; this one applies nothing", idempotencyKey);
            return ProgressProposal.ALREADY_THERE;
        }

        ProgressProposal outcome;
        int version = order.version();

        if (order.status() == target) {
            // Two stations finishing in the same second, or an operator who
            // advanced by hand a moment ago. Correct, and not an error.
            outcome = ProgressProposal.ALREADY_THERE;
        } else if (!OrderStateMachine.permits(order.status(), target, order.fulfillmentMode())) {
            // ADR 0019 does not have this edge from where the order actually is.
            // The ticket is not rolled back: the food is where the food is.
            log.info("Order {} is {} and refuses a kitchen proposal of {}", orderId, order.status(), target);
            outcome = ProgressProposal.REFUSED;
        } else {
            Optional<Integer> won = orders.transition(tenantId, orderId, order.status(), target, now);
            if (won.isEmpty()) {
                OrderRow settled = orders.find(tenantId, orderId).orElseThrow();
                version = settled.version();
                outcome = settled.status() == target ? ProgressProposal.ALREADY_THERE : ProgressProposal.REFUSED;
            } else {
                version = won.get();
                orders.recordTransition(
                        tenantId,
                        orderId,
                        version,
                        order.status(),
                        target,
                        TransitionTrigger.KITCHEN_PROGRESS,
                        reason,
                        actorType,
                        actorId,
                        correlationId,
                        now);

                // A pickup handover is a completion in every sense ADR 0039
                // means, so it records the same outcome an operator's completion
                // records rather than a thinner one that no report can read.
                OrderOutcome completion = target == OrderStatus.COMPLETED
                        ? new OrderOutcome(
                                TerminalOutcomeKind.COMPLETED,
                                OutcomeSystemCategory.defaultCompletionFor(order.fulfillmentMode()),
                                null,
                                null,
                                null,
                                StockDisposition.NO_EFFECT,
                                null,
                                null,
                                true,
                                null)
                        : null;

                applyConsequences(order, target, version, reason, null, actorType, actorId, completion, now);
                outcome = ProgressProposal.APPLIED;
            }
        }

        orders.settleProgressProposal(tenantId, claimed.get(), order.status(), outcome.name(), now);

        // Every proposal is audited, including the refused one. "The kitchen said
        // the food was ready and the order would not move" is the question asked
        // after a customer is told their order is still being prepared while they
        // are holding it, and an audit trail that records only what worked cannot
        // answer it.
        recordAudit(
                order,
                "ordering.order.kitchen-progress",
                actorType,
                actorId,
                reason,
                version,
                Map.of(
                        "fromStatus",
                        order.status().name(),
                        "proposedStatus",
                        target.name(),
                        "outcome",
                        outcome.name(),
                        "idempotencyKey",
                        idempotencyKey),
                outcome == ProgressProposal.REFUSED ? AuditFact.Outcome.REJECTED : AuditFact.Outcome.SUCCEEDED,
                correlationId,
                now);

        return outcome;
    }

    /**
     * The answer a replayed proposal gets: whatever the first one got.
     *
     * <p>A key that was recorded against a different order is refused rather than
     * answered. The key is a string a caller composes, and letting one order's
     * settled outcome answer a proposal about another would turn a caller's
     * key-building bug into an order that moved for a reason nobody can find.
     */
    private ProgressProposal replayOf(JdbcOrderStore.ProgressProposalRow seen, UUID orderId, String idempotencyKey) {

        if (!seen.orderId().equals(orderId)) {
            log.warn(
                    "Progress proposal key {} is already recorded against order {} and cannot " + "answer for order {}",
                    idempotencyKey,
                    seen.orderId(),
                    orderId);
            return ProgressProposal.REFUSED;
        }
        // Only reachable if a row were committed without its outcome, which
        // V0087's constraint forbids. Asserted rather than assumed.
        if (seen.outcome() == null) {
            log.error("Progress proposal {} is committed with no outcome", idempotencyKey);
            return ProgressProposal.REFUSED;
        }
        return ProgressProposal.valueOf(seen.outcome());
    }

    /**
     * Cancels an order without a reason from the registry.
     *
     * <p>Refused once the order is confirmed, and the refusal is the point. After
     * confirmation the hold has been committed and the stock consequence, the
     * liable party and the refund posture are all real decisions — and none of the
     * three has a default that is safe to guess. A cancellation with a registry
     * reason is permitted there; this one is not.
     */
    @Transactional
    public DecisionResult cancel(
            UUID tenantId,
            UUID orderId,
            int expectedVersion,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable String correlationId) {
        return cancel(tenantId, orderId, expectedVersion, reasonCode, actorType, actorId, correlationId, null);
    }

    /**
     * Cancels an order, recording the terminal outcome it ended in (ADR 0039).
     *
     * <p>Where the caller supplies an outcome built from the tenant's reason
     * registry, cancellation after confirmation is permitted: the reason names the
     * ADR 0017 disposition and the liable party, which is exactly what ADR 0019
     * refused to guess at. Where it does not, the pre-confirmation refusal stands.
     *
     * <p>ADR 0017's rule survives either way. Before the reservation is committed
     * the hold is simply released and the disposition decides nothing; after it,
     * the disposition is recorded and the reservation is never reopened.
     *
     * @param prepared the outcome assembled from the registry, or null for the
     *                 pre-confirmation path with no reason
     */
    @Transactional
    public DecisionResult cancel(
            UUID tenantId,
            UUID orderId,
            int expectedVersion,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable String correlationId,
            @Nullable OrderOutcome prepared) {

        Instant now = clock.instant();
        OrderRow order = orders.find(tenantId, orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.version() != expectedVersion) {
            throw new StaleOrderException(expectedVersion, order.version());
        }
        // Extracted to OrderActionsPolicy so the mutating guard here and the
        // server-supplied actions[] read model (orders.md §4.2) read one
        // implementation of "may this order be cancelled with no registry
        // reason" rather than two copies that can silently disagree.
        if (prepared == null && !OrderActionsPolicy.canCancelWithoutReason(order.status())) {
            throw new CancellationNotPermittedException(order.status());
        }
        if (!OrderStateMachine.permits(order.status(), OrderStatus.CANCELLED)) {
            throw new OrderStateMachine.IllegalTransitionException(order.status(), OrderStatus.CANCELLED);
        }

        Optional<Integer> won = orders.transition(tenantId, orderId, order.status(), OrderStatus.CANCELLED, now);
        if (won.isEmpty()) {
            OrderRow settled = orders.find(tenantId, orderId).orElseThrow();
            throw new StaleOrderException(expectedVersion, settled.version());
        }

        int version = requireCallersVersion(expectedVersion, won.get());
        orders.recordTransition(
                tenantId,
                orderId,
                version,
                order.status(),
                OrderStatus.CANCELLED,
                "CUSTOMER".equals(actorType) ? TransitionTrigger.CUSTOMER_ACTION : TransitionTrigger.OPERATIONS_ACTION,
                reasonCode,
                actorType,
                actorId,
                correlationId,
                now);
        orders.cancelTimer(tenantId, orderId, CheckoutService.APPROVAL_TIMER, now);

        // With no registry reason the order was still unconfirmed, so the hold was
        // never committed: it goes back, and the tenant carries the cost of an
        // order nobody cooked.
        OrderOutcome outcome = prepared != null
                ? prepared
                : new OrderOutcome(
                        TerminalOutcomeKind.CANCELLED,
                        OutcomeSystemCategory.OTHER,
                        null,
                        null,
                        null,
                        StockDisposition.RELEASE,
                        LiabilityParty.TENANT,
                        CustomerRefund.FULL,
                        reservationCommitted(order),
                        null);

        applyConsequences(order, OrderStatus.CANCELLED, version, reasonCode, null, actorType, actorId, outcome, now);
        recordAudit(
                order,
                "ordering.order.cancel",
                actorType,
                actorId,
                reasonCode,
                version,
                Map.of(
                        "fromStatus",
                        order.status().name(),
                        "systemCategory",
                        outcome.systemCategory().name(),
                        "stockDisposition",
                        outcome.disposition().name(),
                        "reservationCommitted",
                        outcome.reservationCommitted()),
                AuditFact.Outcome.SUCCEEDED,
                correlationId,
                now);
        return new DecisionResult(true, OrderStatus.CANCELLED, version, null);
    }

    /**
     * Closes the gap between reading the version and settling on it.
     *
     * <p>The If-Match check above is a read, and the UPDATE that follows names the
     * status it expects and not the version. That leaves one interleaving open,
     * and it is not the obvious one. Two operators both advancing
     * {@code PREPARING → READY} do settle correctly: the loser's UPDATE finds the
     * status already moved and matches no row. The hole is anything that bumps the
     * version <em>without</em> changing the status — an ADR 0039 amendment is
     * exactly that. Operator A reads version 5 and presses ready; operator B's
     * amendment commits, changing the order's contents and taking it to version 6;
     * A's UPDATE then still sees {@code status = 'PREPARING'}, wins, and marks
     * ready an order A has never seen the current shape of. The precondition A
     * presented was supposed to prevent precisely that.
     *
     * <p>{@code RETURNING version} is what makes this decidable in one statement:
     * an UPDATE that took the row lock and found nothing had moved returns exactly
     * one more than the caller presented. Anything else means the row changed
     * between the read and the write, and throwing here rolls the transition back
     * with the rest of the transaction, so the loser applied nothing.
     *
     * <p>The tidier form is a {@code version = :expectedVersion} predicate in the
     * store's UPDATE, which would make the loser match no row rather than write
     * and roll back. That belongs in {@code JdbcOrderStore}.
     */
    private static int requireCallersVersion(int expectedVersion, int versionAfterTransition) {
        if (versionAfterTransition != expectedVersion + 1) {
            throw new StaleOrderException(expectedVersion, versionAfterTransition - 1);
        }
        return versionAfterTransition;
    }

    /**
     * Whether the ADR 0017 hold had been turned into a sale.
     *
     * <p>Derived from the confirmation, because confirmation is the only thing
     * that enqueues the commit: an order that never reached {@code CONFIRMED} has
     * a hold nobody was ever asked to commit, and one that did has a commit that
     * either succeeded or is sitting in the process manager waiting to be resolved
     * by hand. Both of the latter are "committed" for this purpose — a
     * cancellation never reopens the reservation either way, exactly as ADR 0017
     * says.
     */
    private boolean reservationCommitted(OrderRow order) {
        return order.confirmedAt() != null;
    }

    /**
     * Everything that follows from a status change, in the same transaction.
     *
     * <p>The consequence is durably recorded here and carried out afterwards by
     * the process manager. Doing the inventory work inline would mean a failure to
     * commit stock could roll back a confirmation the customer had already been
     * shown.
     */
    private void applyConsequences(
            OrderRow order,
            OrderStatus target,
            int version,
            @Nullable String reasonCode,
            @Nullable String decisionChannel,
            @Nullable String actorType,
            @Nullable String actorId,
            @Nullable OrderOutcome outcome,
            Instant now) {

        // ADR 0039: every order ends in exactly one recorded outcome, written in
        // the same transaction as the transition. The table is primary-keyed on
        // the order, so a second attempt fails rather than producing an order with
        // two contradictory endings.
        if (outcome != null) {
            orders.insertOutcome(
                    order.tenantId(),
                    order.orderId(),
                    outcome,
                    actorType == null ? "SERVICE" : actorType,
                    actorId,
                    now);
        }

        // ADR 0017, and the reason the disposition is recorded rather than acted
        // on here: a cancellation never reopens a committed reservation. The
        // release below only runs for a hold that was never committed, and the two
        // movements a committed disposition names — a return and a waste — need a
        // fourth verb on the ADR 0017 port that inventory owns and does not yet
        // expose. Until it does, the outcome row is the record and the movement is
        // an open item on ADR 0039's checklist rather than a silent no-op.
        if (target.releasesInventory()) {
            inventoryProcess.enqueueRelease(order.orderId(), order.tenantId(), order.pricingQuoteId(), now);
        } else if (target == OrderStatus.CONFIRMED) {
            inventoryProcess.enqueueCommit(order.orderId(), order.tenantId(), order.pricingQuoteId(), now);
        }

        // The ADR 0036 kitchen slot is freed the moment the order stops occupying
        // the kitchen, whether it was completed or refused. Leaving it held would
        // make a branch report itself full of orders that are over.
        if (!target.occupiesCapacity()) {
            capacity.releaseCapacity(order.tenantId(), order.orderId());
        }

        // ADR 0046. Handover is when a cash order's money arrives, and it is the
        // only moment the platform observes it: nothing captures a cash intent and
        // nothing ever will. Recorded here rather than on an event because ordering
        // publishes none for a completion, and in the same transaction as the
        // transition because a completion that committed without its tender
        // settling would be an order the tenant was paid for and cannot refund.
        //
        // A card order reaches here having settled on its confirmation; payments
        // decides which of the two moments a tender belongs to and leaves the other
        // alone, so this is safe to call for every completion.
        //
        // And the mirror of it. An order that ends without a handover has money
        // nobody will ever bring, and until now nothing said so: the settlement
        // stayed PLANNED and a points hold stayed RESERVED indefinitely, unwound
        // only by a loyalty sweep that could not tell a dead order from a live one
        // — and which was releasing live orders' holds in the process. Keyed on
        // terminal() rather than on a list of statuses, so a terminal status added
        // later cannot be the one somebody forgets.
        if (target == OrderStatus.COMPLETED) {
            settlements.recordHandover(order.tenantId(), order.orderId(), actorOf(actorType, actorId));
        } else if (target.terminal()) {
            settlements.recordTerminalOutcome(
                    order.tenantId(),
                    order.orderId(),
                    reasonCode == null ? target.name() : reasonCode,
                    actorOf(actorType, actorId));
        }

        TenantId tenant = new TenantId(order.tenantId());
        switch (target) {
            case CONFIRMED ->
                events.publishEvent(new OrderConfirmed(
                        UUID.randomUUID(),
                        tenant,
                        order.orderId(),
                        now,
                        order.brandId(),
                        order.locationId(),
                        order.acceptanceMode(),
                        decisionChannel,
                        now,
                        order.currency(),
                        order.totalMinor(),
                        target.name(),
                        version));
            case REJECTED ->
                events.publishEvent(new OrderRejected(
                        UUID.randomUUID(),
                        tenant,
                        order.orderId(),
                        now,
                        order.brandId(),
                        order.locationId(),
                        decisionChannel,
                        reasonCode,
                        target.name(),
                        version));
            case EXPIRED ->
                events.publishEvent(new OrderExpired(
                        UUID.randomUUID(),
                        tenant,
                        order.orderId(),
                        now,
                        order.brandId(),
                        order.locationId(),
                        order.approvalDeadlineAt(),
                        target.name(),
                        version));
            // ADR 0039 additive fields under ADR 0032. The category, the
            // disposition and the liable party are what a report needs and what a
            // reason code alone cannot give it — and none of the three says
            // anything about a person, which is why they may travel on an event
            // while the internal reason text may not.
            case CANCELLED ->
                events.publishEvent(new OrderCancelled(
                        UUID.randomUUID(),
                        tenant,
                        order.orderId(),
                        now,
                        order.brandId(),
                        order.locationId(),
                        actorType,
                        reasonCode,
                        order.status().name(),
                        target.name(),
                        version,
                        outcome == null ? null : outcome.systemCategory().name(),
                        outcome == null ? null : outcome.disposition().name(),
                        outcome == null || outcome.liabilityParty() == null
                                ? null
                                : outcome.liabilityParty().name()));
            default -> {
                // PREPARING, READY, FULFILLING and COMPLETED have no external
                // consumer in this slice. They are recorded in the state history
                // and will get their events with ADR 0014 and ADR 0020, rather
                // than being published now to a catalogue nobody reads.
            }
        }
    }

    /**
     * Records the ADR 0027 evidence, in the same transaction as the change.
     *
     * <p>An action that succeeded without a record is indistinguishable from one
     * that never happened, so an audit failure fails the transition rather than
     * being swallowed.
     */
    private void recordAudit(
            OrderRow order,
            String actionCode,
            @Nullable String actorType,
            @Nullable String actorId,
            @Nullable String reasonCode,
            int version,
            Map<String, Object> changed,
            AuditFact.Outcome outcome,
            @Nullable String correlationId,
            Instant now) {

        ActorRef actor =
                switch (actorType == null ? "SERVICE" : actorType) {
                    case "USER" -> ActorRef.user(actorId == null ? "unknown-user" : actorId, null);
                    case "SYSTEM_JOB" -> ActorRef.systemJob(actorId == null ? "ordering" : actorId);
                    // A customer is a person, but not a platform user: recording them as
                    // USER would put them in the same population as staff in every audit
                    // query, and "which operator cancelled this" would start returning
                    // customers.
                    default -> ActorRef.service(actorId == null ? "ordering" : actorId);
                };

        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(order.tenantId(), order.brandId(), order.locationId()))
                .target("ordering.order", order.orderId())
                .targetVersion((long) version)
                .outcome(outcome)
                // Every operator action carries a reason; AuditFact refuses a USER
                // actor without one, which is what stops "what happened" being
                // recorded with no "why".
                .because(reasonCode)
                .changed(changed)
                .correlatedBy(correlationId == null ? order.orderId().toString() : correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * The best identifier available for a settlement-facing actor field, which —
     * unlike {@link #recordAudit}'s {@link ActorRef} — is a single required
     * string with no structured "unknown" case of its own.
     */
    private static String actorOf(@Nullable String actorType, @Nullable String actorId) {
        if (actorId != null) {
            return actorId;
        }
        return actorType == null ? "ordering" : actorType;
    }

    private DecisionResult settledOutcome(UUID tenantId, UUID orderId, ApprovalDecisionRow seen, OrderRow order) {
        log.debug("Decision {} on order {} was already recorded", seen.decisionId(), orderId);
        return new DecisionResult(
                seen.effective(),
                order.status(),
                order.version(),
                orders.findEffectiveDecision(tenantId, orderId).orElse(seen));
    }

    public enum DecisionAction {
        APPROVE,
        REJECT
    }

    /**
     * What ordering did with a proposal (ADR 0041).
     *
     * <p>Deliberately the same three answers {@code OrderProgressPort} offers a
     * kitchen, minus {@code NOT_WIRED}, which is what the port says when nothing
     * implements it and therefore something this class can never be.
     */
    public enum ProgressProposal {
        APPLIED,
        ALREADY_THERE,
        REFUSED
    }

    /**
     * A restaurant's approve or reject click, on one order.
     *
     * @param decisionId stable across retries of one human decision, so the same
     *                   click arriving twice is one decision rather than two
     * @param issuedAt   when the operator decided, not when the command arrived;
     *                   the two differ by however long a POS channel was offline
     * @param noteEncrypted the operator's own words on a rejection, already
     *                   encrypted (wave 24) — ignored for an approve. Null unless
     *                   the reason picked required one; {@link OrderOutcomeService#reject}
     *                   is what encrypts it before this is ever called, exactly as
     *                   it already does for a cancellation's note
     */
    public record DecisionCommand(
            String decisionId,
            DecisionAction action,
            String decisionChannel,
            String actorType,
            String actorId,
            String reasonCode,
            Instant issuedAt,
            @Nullable String correlationId,
            @Nullable String noteEncrypted) {}

    /**
     * The result of one approve/reject command against an order.
     *
     * @param applied whether this caller's command is the one that moved the order
     * @param effectiveDecision the decision that actually settled it, which may be
     *                          somebody else's, or null when the order has no
     *                          approval decision at all
     */
    public record DecisionResult(
            boolean applied,
            OrderStatus status,
            int orderVersion,
            @Nullable ApprovalDecisionRow effectiveDecision) {}

    /** An order's status and effective decision, read rather than acted on (wave 24, {@link #currentState}). */
    public record CurrentState(
            OrderStatus status, int orderVersion, @Nullable ApprovalDecisionRow effectiveDecision) {}

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID orderId) {
            super("No order " + orderId + " for this tenant");
        }
    }

    /** The caller's expected version no longer matches the stored order. */
    public static class StaleOrderException extends RuntimeException {

        private final int expected;
        private final int actual;

        public StaleOrderException(int expected, int actual) {
            super("The order has changed since version %d was read".formatted(expected));
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }

    /** Cancelling after confirmation is ADR 0039's, and is refused rather than half-done. */
    public static class CancellationNotPermittedException extends RuntimeException {
        public CancellationNotPermittedException(OrderStatus status) {
            super(("An order that is %s cannot be cancelled in this release: payment, fiscal, POS "
                            + "and fulfilment consequences are owned by ADR 0039. Create a replacement "
                            + "order instead.")
                    .formatted(status));
        }
    }
}
