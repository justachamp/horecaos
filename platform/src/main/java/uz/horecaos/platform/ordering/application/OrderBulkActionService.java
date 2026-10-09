package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.api.CourierAssignmentPort;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.domain.BulkActionType;
import uz.horecaos.platform.ordering.domain.BulkItemStatus;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBulkOperationStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBulkOperationStore.BulkItemRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * Applying one command to many orders at once (ADR 0039).
 *
 * <p>Not one transaction. Per the ADR's own "Bulk actions" section and its
 * rejected all-or-nothing alternative, every order is applied and recorded
 * through its own call into the ordinary single-order services —
 * {@link OrderStateService#advance} or {@link OrderOutcomeService#cancel} —
 * each of which opens its own transaction because this class, deliberately,
 * opens none. A lock convoy during the peak that produced the bulk action, and
 * one already-settled order failing the other hundred and ninety-nine, is
 * exactly the failure the rejected alternative would reintroduce.
 *
 * <p>{@link BulkActionType#ADVANCE} and {@link BulkActionType#CANCEL} are the
 * whole of what may be bulk-applied; see {@link BulkActionType}'s own doc for
 * why {@code COMPLETE}, {@code REJECT} and {@code AMEND} are refused rather
 * than half-supported.
 *
 * <p>The per-item idempotency ADR 0039 derives as {@code {bulkKey}:{orderId}}
 * is the {@code (bulk_operation_id, order_id)} primary key on {@code
 * ordering.bulk_operation_items} — {@code bulk_operation_id} already names one
 * {@code Idempotency-Key} through {@code uq_bulk_operation_idempotency}, so the
 * pair is that derivation, not a reformatting of it. A resubmission under the
 * same key is answered from the row that already exists and touches nothing:
 * the exit criterion the ADR states in its own testing section ("a re-run
 * under the same bulk key changes nothing"), read literally rather than as
 * "retries only the failures".
 */
@Service
public class OrderBulkActionService {

    /** ADR 0039: routine, reversible-in-effect kitchen-path moves only. */
    private static final Set<OrderStatus> ADVANCE_TARGETS =
            EnumSet.of(OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.FULFILLING);

    /** ADR 0039's own cap. */
    public static final int MAX_ORDERS = 200;

    /** ADR 0172: the same shape as the single assignment's reason code. */
    private static final Pattern ASSIGN_REASON_CODE = Pattern.compile("[A-Z0-9_]{1,48}");

    private static final Logger log = LoggerFactory.getLogger(OrderBulkActionService.class);

    private final JdbcOrderStore orders;
    private final JdbcBulkOperationStore bulkStore;
    private final OrderStateService orderState;
    private final OrderOutcomeService outcomes;
    private final AuditRecorder audit;
    private final Clock clock;
    private final CourierAssignmentPort courierAssignment;
    private final boolean assignCourierEnabled;
    private final @Nullable MeterRegistry meters;

    /** A service that counts nothing: every test that predates the counters. */
    public OrderBulkActionService(
            JdbcOrderStore orders,
            JdbcBulkOperationStore bulkStore,
            OrderStateService orderState,
            OrderOutcomeService outcomes,
            AuditRecorder audit,
            Clock clock,
            CourierAssignmentPort courierAssignment,
            boolean assignCourierEnabled) {
        this(orders, bulkStore, orderState, outcomes, audit, clock, courierAssignment, assignCourierEnabled, null);
    }

    @Autowired
    public OrderBulkActionService(
            JdbcOrderStore orders,
            JdbcBulkOperationStore bulkStore,
            OrderStateService orderState,
            OrderOutcomeService outcomes,
            AuditRecorder audit,
            Clock clock,
            CourierAssignmentPort courierAssignment,
            @Value("${horecaos.ordering.bulk.assign-courier.enabled:false}") boolean assignCourierEnabled,
            @Nullable MeterRegistry meters) {
        this.orders = orders;
        this.bulkStore = bulkStore;
        this.orderState = orderState;
        this.outcomes = outcomes;
        this.audit = audit;
        this.clock = clock;
        this.courierAssignment = courierAssignment;
        this.assignCourierEnabled = assignCourierEnabled;
        this.meters = meters;
    }

    public BulkActionResult apply(UUID tenantId, UUID brandId, UUID locationId, BulkActionCommand command) {
        if (command.orders().isEmpty()) {
            throw new IllegalArgumentException("A bulk action names at least one order");
        }
        if (command.orders().size() > MAX_ORDERS) {
            throw new IllegalArgumentException("A bulk action names at most " + MAX_ORDERS + " orders");
        }
        if (command.actionType() == BulkActionType.ADVANCE
                && (command.targetStatus() == null || !ADVANCE_TARGETS.contains(command.targetStatus()))) {
            throw new IllegalArgumentException(
                    "ADVANCE targets one of " + ADVANCE_TARGETS + ", never a terminal status");
        }
        if (command.actionType() == BulkActionType.ADVANCE
                && (command.reasonCode() == null || command.reasonCode().isBlank())) {
            throw new IllegalArgumentException("ADVANCE names a reason code, exactly like a single state action");
        }
        if (command.actionType() == BulkActionType.CANCEL && command.cancelReasonId() == null) {
            throw new IllegalArgumentException(
                    "A bulk cancellation names a reason from the tenant's registry, exactly like a single one");
        }
        if (command.actionType() != BulkActionType.ASSIGN_COURIER && command.courierId() != null) {
            throw new IllegalArgumentException("Only ASSIGN_COURIER names a courier");
        }
        if (command.actionType() == BulkActionType.ASSIGN_COURIER) {
            // ADR 0172 rollout: refused by default, so the action is merged inert and a pilot
            // branch turns it on. The same VALIDATION_FAILED the unsupported members get.
            if (!assignCourierEnabled) {
                throw new IllegalArgumentException("ASSIGN_COURIER is not enabled for this deployment");
            }
            if (command.courierId() == null) {
                throw new IllegalArgumentException("A bulk assignment names the courier");
            }
            if (command.reasonCode() == null
                    || !ASSIGN_REASON_CODE.matcher(command.reasonCode()).matches()) {
                throw new IllegalArgumentException(
                        "A bulk assignment names a reason code of 1 to 48 upper-case letters, digits or underscores");
            }
            if (!courierAssignment.courierExists(tenantId, command.courierId())) {
                throw new IllegalArgumentException("The courier is not one of this tenant's");
            }
        }

        Instant now = clock.instant();

        Optional<JdbcBulkOperationStore.BulkOperationRow> existing =
                bulkStore.findByIdempotencyKey(tenantId, command.idempotencyKey());
        if (existing.isPresent()) {
            // A resubmission under the same key. Nothing is re-executed, on the
            // same argument OrderAmendmentService.propose replays rather than
            // re-proposing: the recorded outcome, not a fresh attempt, is what
            // makes a retry after a partial failure safe.
            JdbcBulkOperationStore.BulkOperationRow settled = existing.get();
            if (settled.completedAt() == null) {
                resumePending(tenantId, brandId, locationId, settled, command);
            }
            return new BulkActionResult(
                    settled.id(),
                    settled.actionType(),
                    settled.requestedCount(),
                    true,
                    outcomesOf(tenantId, settled.id()));
        }

        UUID bulkOperationId = Ids.newId();
        bulkStore.insert(new JdbcBulkOperationStore.NewBulkOperation(
                bulkOperationId,
                tenantId,
                brandId,
                locationId,
                command.actionType(),
                command.orders().size(),
                command.idempotencyKey(),
                command.actorType(),
                command.actorId(),
                now,
                command.courierId(),
                command.actionType() == BulkActionType.ASSIGN_COURIER ? command.reasonCode() : null));

        List<BulkItemOutcome> results = new ArrayList<>();
        int applied = 0;
        int failed = 0;
        for (BulkOrderRef ref : command.orders()) {
            BulkItemOutcome outcome =
                    counted(command, applyItem(tenantId, brandId, locationId, bulkOperationId, command, ref));
            results.add(outcome);
            if (outcome.itemStatus() == BulkItemStatus.APPLIED) {
                applied++;
            } else {
                failed++;
            }
        }

        bulkStore.markCompleted(tenantId, bulkOperationId, clock.instant());
        recordSummaryAudit(tenantId, brandId, locationId, bulkOperationId, command, applied, failed, now);
        recordRun(command, command.orders().size(), now);

        return new BulkActionResult(
                bulkOperationId, command.actionType(), command.orders().size(), false, results);
    }

    /**
     * A resubmission under the key of a bulk that never completed: the process died, or the request was cut off,
     * between an item's work and its recorded result (ADR 0172 Decision 8). A recorded outcome is replayed
     * untouched, as ever; an item still {@code PENDING} is driven again, and for an assignment it reaches the
     * attempt the first run opened under the same derived key rather than opening a second, so a shipment that
     * was committed before the crash is reported as the assignment it is. Only orders the first run staked a
     * claim on are driven: a resubmission cannot add orders to a bulk that already exists.
     */
    private void resumePending(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            JdbcBulkOperationStore.BulkOperationRow settled,
            BulkActionCommand command) {

        Instant started = clock.instant();
        List<BulkItemRow> recorded = bulkStore.items(tenantId, settled.id());
        Set<UUID> pending = new java.util.HashSet<>();
        for (BulkItemRow item : recorded) {
            if (item.itemStatus() == BulkItemStatus.PENDING) {
                pending.add(item.orderId());
            }
        }
        if (pending.isEmpty()) {
            // Every item was decided and only the completion mark was lost.
            bulkStore.markCompleted(tenantId, settled.id(), started);
            return;
        }
        for (BulkOrderRef ref : command.orders()) {
            if (pending.contains(ref.orderId())) {
                counted(command, applyItem(tenantId, brandId, locationId, settled.id(), command, ref));
            }
        }
        List<BulkItemRow> after = bulkStore.items(tenantId, settled.id());
        boolean stillPending = after.stream().anyMatch(item -> item.itemStatus() == BulkItemStatus.PENDING);
        if (!stillPending) {
            int applied = (int) after.stream()
                    .filter(item -> item.itemStatus() == BulkItemStatus.APPLIED)
                    .count();
            bulkStore.markCompleted(tenantId, settled.id(), clock.instant());
            recordSummaryAudit(
                    tenantId, brandId, locationId, settled.id(), command, applied, after.size() - applied, started);
            recordRun(command, command.orders().size(), started);
        }
    }

    /** The size and duration of one bulk run, labelled by action only (ADR 0172: no courier, no tenant). */
    private void recordRun(BulkActionCommand command, int size, Instant started) {
        if (meters == null) {
            return;
        }
        String action = command.actionType().name().toLowerCase(java.util.Locale.ROOT);
        meters.summary("ordering.bulk.size", "action", action).record(size);
        meters.timer("ordering.bulk.duration", "action", action)
                .record(java.time.Duration.between(started, clock.instant()));
    }

    /** One item's fate as a counter by action and outcome; the outcome is the problem code or {@code applied}. */
    private BulkItemOutcome counted(BulkActionCommand command, BulkItemOutcome outcome) {
        if (meters != null) {
            meters.counter(
                            "ordering.bulk.item",
                            "action",
                            command.actionType().name().toLowerCase(java.util.Locale.ROOT),
                            "outcome",
                            outcome.itemStatus() == BulkItemStatus.APPLIED
                                    ? "applied"
                                    : String.valueOf(outcome.itemProblemCode()).toLowerCase(java.util.Locale.ROOT))
                    .increment();
        }
        return outcome;
    }

    /**
     * One order, its own outcome. Nothing here shares a transaction with
     * another item: {@link OrderStateService#advance} and {@link
     * OrderOutcomeService#cancel} are called as ordinary bean methods with no
     * ambient transaction open, so each opens and settles its own, and a
     * failure here can never roll back a neighbour's success.
     */
    private BulkItemOutcome applyItem(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID bulkOperationId,
            BulkActionCommand command,
            BulkOrderRef ref) {

        Optional<BulkItemRow> existingItem = bulkStore.findItem(tenantId, bulkOperationId, ref.orderId());
        if (existingItem.isPresent() && existingItem.get().itemStatus() != BulkItemStatus.PENDING) {
            BulkItemRow settled = existingItem.get();
            return new BulkItemOutcome(
                    settled.orderId(),
                    settled.itemStatus(),
                    settled.itemProblemCode(),
                    settled.resultingOrderVersion(),
                    settled.planId(),
                    settled.shipmentId(),
                    settled.note(),
                    settled.ruleId());
        }
        if (existingItem.isEmpty()) {
            bulkStore.insertPendingItem(tenantId, bulkOperationId, ref.orderId());
        }

        JdbcOrderStore.OrderRow order = orders.find(tenantId, ref.orderId()).orElse(null);
        if (order == null || !order.locationId().equals(locationId)) {
            // The order named in the request cannot supply its own scope for the
            // audit fact — it may not exist at all — so the request's own
            // location stands in. The attempt is touched and recorded either way
            // (ADR 0027): a bulk action naming an order nobody can find is still
            // an act somebody performed against this location's queue.
            Instant now = clock.instant();
            bulkStore.markItemFailed(tenantId, bulkOperationId, ref.orderId(), "ORDER_NOT_FOUND_AT_LOCATION", now);
            recordItemAudit(
                    tenantId,
                    brandId,
                    locationId,
                    ref.orderId(),
                    bulkOperationId,
                    command,
                    AuditFact.Outcome.FAILED,
                    "ORDER_NOT_FOUND_AT_LOCATION",
                    null,
                    now);
            return new BulkItemOutcome(
                    ref.orderId(), BulkItemStatus.FAILED, "ORDER_NOT_FOUND_AT_LOCATION", null, null, null, null);
        }

        if (command.actionType() == BulkActionType.ASSIGN_COURIER) {
            return assignItem(tenantId, brandId, locationId, bulkOperationId, command, ref, order);
        }

        try {
            // apply() already refused the whole request unless the field this
            // branch needs is present; asserting it again here is what lets
            // NullAway see across that method boundary rather than a real
            // possibility of null reaching a single-order service.
            int resultingVersion =
                    switch (command.actionType()) {
                        case ADVANCE ->
                            orderState
                                    .advance(
                                            tenantId,
                                            ref.orderId(),
                                            Objects.requireNonNull(command.targetStatus()),
                                            ref.expectedVersion(),
                                            Objects.requireNonNull(command.reasonCode()),
                                            command.actorType(),
                                            command.actorId(),
                                            null)
                                    .orderVersion();
                        case CANCEL ->
                            outcomes.cancel(
                                            tenantId,
                                            ref.orderId(),
                                            ref.expectedVersion(),
                                            new OrderOutcomeService.CancelCommand(
                                                    Objects.requireNonNull(command.cancelReasonId()),
                                                    command.cancelNote(),
                                                    command.actorType(),
                                                    command.actorId(),
                                                    null))
                                    .orderVersion();
                        case ASSIGN_COURIER ->
                            throw new IllegalStateException("ASSIGN_COURIER is decided by assignItem");
                    };
            Instant now = clock.instant();
            bulkStore.markItemApplied(tenantId, bulkOperationId, ref.orderId(), resultingVersion, now);
            recordItemAudit(
                    tenantId,
                    order.brandId(),
                    order.locationId(),
                    order.orderId(),
                    bulkOperationId,
                    command,
                    AuditFact.Outcome.SUCCEEDED,
                    null,
                    resultingVersion,
                    now);
            return new BulkItemOutcome(ref.orderId(), BulkItemStatus.APPLIED, null, resultingVersion, null, null, null);
        } catch (OrderStateService.StaleOrderException stale) {
            return fail(tenantId, order, bulkOperationId, command, "STALE_VERSION");
        } catch (OrderStateMachine.IllegalTransitionException illegal) {
            return fail(tenantId, order, bulkOperationId, command, "ILLEGAL_TRANSITION");
        } catch (OrderStateService.CatchweightNotReconciledException unweighed) {
            return fail(tenantId, order, bulkOperationId, command, "CATCHWEIGHT_NOT_RECONCILED");
        } catch (OrderStateService.CancellationNotPermittedException refused) {
            return fail(tenantId, order, bulkOperationId, command, "CANCELLATION_NOT_PERMITTED");
        } catch (OrderOutcomeReasonService.ReasonNotFoundException missing) {
            return fail(tenantId, order, bulkOperationId, command, "REASON_NOT_FOUND");
        } catch (IllegalArgumentException invalid) {
            return fail(tenantId, order, bulkOperationId, command, "VALIDATION_FAILED");
        } catch (RuntimeException unexpected) {
            // One order's unexpected failure must never stop the other hundred
            // and ninety-nine, and must never be reported as a silent success
            // either. Logged with the order id, never with anything the order
            // carries about a person.
            log.error("Bulk item {} in operation {} failed unexpectedly", ref.orderId(), bulkOperationId, unexpected);
            return fail(tenantId, order, bulkOperationId, command, "UNEXPECTED_FAILURE");
        }
    }

    /**
     * One order, assigned to the request's courier (ADR 0172 Decision 3). The first refusal is
     * the answer: the order's own checks here, the plan's and the courier's in {@code fulfillment}.
     * The call is the whole act and opens its own transaction; a failure it can name comes back
     * as a stable code, and an exception becomes {@code UNEXPECTED_FAILURE} without its message.
     */
    private BulkItemOutcome assignItem(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID bulkOperationId,
            BulkActionCommand command,
            BulkOrderRef ref,
            JdbcOrderStore.OrderRow order) {
        if (order.version() != ref.expectedVersion()) {
            return fail(tenantId, order, bulkOperationId, command, "STALE_VERSION");
        }
        if (order.fulfillmentMode() != FulfillmentMode.DELIVERY) {
            return fail(tenantId, order, bulkOperationId, command, "NOT_A_DELIVERY_ORDER");
        }
        try {
            CourierAssignmentPort.Outcome outcome = courierAssignment.assignForOrder(
                    tenantId,
                    brandId,
                    locationId,
                    order.orderId(),
                    Objects.requireNonNull(command.courierId()),
                    Objects.requireNonNull(command.reasonCode()),
                    attemptKey(command.idempotencyKey(), order.orderId()),
                    bulkOperationId,
                    actorOf(command.actorType(), command.actorId()));
            String note = outcome.notes().isEmpty() ? null : String.join(",", outcome.notes());
            Instant now = clock.instant();
            if (!outcome.applied()) {
                String problem = Objects.requireNonNull(outcome.problemCode());
                bulkStore.markItemFailed(
                        tenantId,
                        bulkOperationId,
                        order.orderId(),
                        problem,
                        now,
                        outcome.planId(),
                        note,
                        outcome.ruleId());
                recordItemAudit(
                        tenantId,
                        order.brandId(),
                        order.locationId(),
                        order.orderId(),
                        bulkOperationId,
                        command,
                        AuditFact.Outcome.FAILED,
                        problem,
                        null,
                        now);
                return new BulkItemOutcome(
                        order.orderId(),
                        BulkItemStatus.FAILED,
                        problem,
                        null,
                        outcome.planId(),
                        null,
                        note,
                        outcome.ruleId());
            }
            // An assignment moves the plan and creates a shipment; it does not move the order's own
            // version, so an applied item records the version the order already had.
            bulkStore.markItemApplied(
                    tenantId,
                    bulkOperationId,
                    order.orderId(),
                    order.version(),
                    now,
                    outcome.planId(),
                    outcome.shipmentId(),
                    note,
                    outcome.ruleId());
            recordItemAudit(
                    tenantId,
                    order.brandId(),
                    order.locationId(),
                    order.orderId(),
                    bulkOperationId,
                    command,
                    AuditFact.Outcome.SUCCEEDED,
                    null,
                    order.version(),
                    now);
            return new BulkItemOutcome(
                    order.orderId(),
                    BulkItemStatus.APPLIED,
                    null,
                    order.version(),
                    outcome.planId(),
                    outcome.shipmentId(),
                    note,
                    outcome.ruleId());
        } catch (RuntimeException unexpected) {
            log.error(
                    "Bulk assignment of {} in operation {} failed unexpectedly",
                    order.orderId(),
                    bulkOperationId,
                    unexpected);
            return fail(tenantId, order, bulkOperationId, command, "UNEXPECTED_FAILURE");
        }
    }

    /**
     * ADR 0172 Decision 8: {@code bulk:{first 16 hex of sha256(bulkKey)}:{orderId}}. The attempt
     * table's idempotency column is {@code varchar(160)} and a client's {@code Idempotency-Key}
     * may be 255, so the key is hashed; 5 + 16 + 1 + 36 characters fit.
     */
    static String attemptKey(String bulkKey, UUID orderId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bulkKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "bulk:" + java.util.HexFormat.of().formatHex(digest, 0, 8) + ":" + orderId;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
        }
    }

    private BulkItemOutcome fail(
            UUID tenantId,
            JdbcOrderStore.OrderRow order,
            UUID bulkOperationId,
            BulkActionCommand command,
            String problemCode) {
        Instant now = clock.instant();
        bulkStore.markItemFailed(tenantId, bulkOperationId, order.orderId(), problemCode, now);
        recordItemAudit(
                tenantId,
                order.brandId(),
                order.locationId(),
                order.orderId(),
                bulkOperationId,
                command,
                AuditFact.Outcome.FAILED,
                problemCode,
                null,
                now);
        return new BulkItemOutcome(order.orderId(), BulkItemStatus.FAILED, problemCode, null, null, null, null);
    }

    private List<BulkItemOutcome> outcomesOf(UUID tenantId, UUID bulkOperationId) {
        return bulkStore.items(tenantId, bulkOperationId).stream()
                .map(row -> new BulkItemOutcome(
                        row.orderId(),
                        row.itemStatus(),
                        row.itemProblemCode(),
                        row.resultingOrderVersion(),
                        row.planId(),
                        row.shipmentId(),
                        row.note(),
                        row.ruleId()))
                .toList();
    }

    /**
     * One audit fact per order, in the same {@link AuditClass#BUSINESS} class
     * a single cancellation or state action would record — a bulk action must
     * not collapse into one fact that loses which orders were touched (ADR
     * 0027).
     */
    private void recordItemAudit(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID bulkOperationId,
            BulkActionCommand command,
            AuditFact.Outcome outcome,
            @Nullable String problemCode,
            @Nullable Integer resultingVersion,
            Instant now) {

        AuditFact.Builder fact = AuditFact.of(
                        "ordering.order.bulk-action-item."
                                + command.actionType().name().toLowerCase(java.util.Locale.ROOT),
                        AuditClass.BUSINESS)
                .by(actorOf(command.actorType(), command.actorId()))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("ordering.order", orderId)
                .outcome(outcome)
                .because(auditReasonFor(command))
                // Staff 9.3a: each bulk-item outcome is its own append-only
                // fact, with no prior item outcome to diff against.
                .changed(ChangeDocuments.created(Map.of(
                        "bulkOperationId",
                        bulkOperationId.toString(),
                        "problemCode",
                        problemCode == null ? "" : problemCode)))
                .correlatedBy(bulkOperationId.toString())
                .occurredAt(now);
        if (resultingVersion != null) {
            fact = fact.targetVersion(resultingVersion.longValue());
        }
        audit.record(fact.build());
    }

    /**
     * ADR 0027: a {@code USER}-initiated audit fact requires a non-blank reason
     * ({@code AuditFact}'s own compact constructor enforces it). {@code
     * ADVANCE} already carries one — {@code apply()} refuses the whole request
     * without it, exactly like a single {@code state-actions} call. {@code
     * CANCEL}'s reason lives structurally on the outcome the tenant's registry
     * produced, not as operator free text, so a stable marker stands in here —
     * the same role {@code cancelReasonId} already plays in {@code
     * bulk_operation_items}, without repeating it as prose.
     */
    private static String auditReasonFor(BulkActionCommand command) {
        return switch (command.actionType()) {
            case ADVANCE, ASSIGN_COURIER -> Objects.requireNonNull(command.reasonCode());
            case CANCEL -> "BULK_CANCELLATION";
        };
    }

    private void recordSummaryAudit(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID bulkOperationId,
            BulkActionCommand command,
            int applied,
            int failed,
            Instant now) {

        audit.record(AuditFact.of(
                        "ordering.order.bulk-action."
                                + command.actionType().name().toLowerCase(java.util.Locale.ROOT),
                        AuditClass.BUSINESS)
                .by(actorOf(command.actorType(), command.actorId()))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("ordering.bulk_operation", bulkOperationId)
                .outcome(failed == 0 ? AuditFact.Outcome.SUCCEEDED : AuditFact.Outcome.FAILED)
                .because(auditReasonFor(command))
                // Staff 9.3a: each bulk-operation run is its own append-only
                // summary fact, with no prior run's counts to diff against.
                .changed(ChangeDocuments.created(Map.of(
                        "requestedCount", command.orders().size(),
                        "appliedCount", applied,
                        "failedCount", failed)))
                .correlatedBy(bulkOperationId.toString())
                .occurredAt(now)
                .build());
    }

    private static ActorRef actorOf(String actorType, String actorId) {
        return switch (actorType) {
            case "USER" -> ActorRef.user(actorId, null);
            case "SYSTEM_JOB" -> ActorRef.systemJob(actorId);
            default -> ActorRef.service(actorId);
        };
    }

    public record BulkOrderRef(UUID orderId, int expectedVersion) {}

    /**
     * One command applied to every order named in {@code orders}.
     *
     * @param targetStatus  required for {@link BulkActionType#ADVANCE}, one of
     *                      {@link #ADVANCE_TARGETS}
     * @param reasonCode    required for {@link BulkActionType#ADVANCE}, exactly
     *                      like a single {@code state-actions} call's own
     * @param cancelReasonId required for {@link BulkActionType#CANCEL}, from
     *                      the tenant's outcome-reason registry
     */
    public record BulkActionCommand(
            BulkActionType actionType,
            List<BulkOrderRef> orders,
            @Nullable OrderStatus targetStatus,
            @Nullable String reasonCode,
            @Nullable UUID cancelReasonId,
            @Nullable String cancelNote,
            String idempotencyKey,
            String actorType,
            String actorId,
            @Nullable UUID courierId) {

        /** ADVANCE and CANCEL name no courier. */
        public BulkActionCommand(
                BulkActionType actionType,
                List<BulkOrderRef> orders,
                @Nullable OrderStatus targetStatus,
                @Nullable String reasonCode,
                @Nullable UUID cancelReasonId,
                @Nullable String cancelNote,
                String idempotencyKey,
                String actorType,
                String actorId) {
            this(
                    actionType,
                    orders,
                    targetStatus,
                    reasonCode,
                    cancelReasonId,
                    cancelNote,
                    idempotencyKey,
                    actorType,
                    actorId,
                    null);
        }
    }

    /**
     * @param planId     an assignment's plan (ADR 0172), applied or refused
     * @param shipmentId the shipment an applied assignment created
     * @param note       an applied item's non-fatal notes ({@code OVERRODE_DISPATCH_RULE},
     *                   {@code OUTSIDE_DISTANCE_BAND}) or a {@code COURIER_NOT_ELIGIBLE}
     *                   refusal's named reasons, comma separated
     * @param ruleId     the dispatch rule the plan's stored decision matched (ADR 0172 Decision 6), an id and
     *                   never the rule's content; null for ADVANCE and CANCEL
     */
    public record BulkItemOutcome(
            UUID orderId,
            BulkItemStatus itemStatus,
            @Nullable String itemProblemCode,
            @Nullable Integer resultingOrderVersion,
            @Nullable UUID planId,
            @Nullable UUID shipmentId,
            @Nullable String note,
            @Nullable String ruleId) {

        /** An item with no rule to name: ADVANCE, CANCEL, and an assignment refused before its plan was read. */
        public BulkItemOutcome(
                UUID orderId,
                BulkItemStatus itemStatus,
                @Nullable String itemProblemCode,
                @Nullable Integer resultingOrderVersion,
                @Nullable UUID planId,
                @Nullable UUID shipmentId,
                @Nullable String note) {
            this(orderId, itemStatus, itemProblemCode, resultingOrderVersion, planId, shipmentId, note, null);
        }
    }

    public record BulkActionResult(
            UUID bulkOperationId,
            BulkActionType actionType,
            int requestedCount,
            boolean replayed,
            List<BulkItemOutcome> items) {}
}
