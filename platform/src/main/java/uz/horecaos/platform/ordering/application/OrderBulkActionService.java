package uz.horecaos.platform.ordering.application;

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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.domain.BulkActionType;
import uz.horecaos.platform.ordering.domain.BulkItemStatus;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBulkOperationStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBulkOperationStore.BulkItemRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;

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

    private static final Logger log = LoggerFactory.getLogger(OrderBulkActionService.class);

    private final JdbcOrderStore orders;
    private final JdbcBulkOperationStore bulkStore;
    private final OrderStateService orderState;
    private final OrderOutcomeService outcomes;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrderBulkActionService(
            JdbcOrderStore orders,
            JdbcBulkOperationStore bulkStore,
            OrderStateService orderState,
            OrderOutcomeService outcomes,
            AuditRecorder audit,
            Clock clock) {
        this.orders = orders;
        this.bulkStore = bulkStore;
        this.orderState = orderState;
        this.outcomes = outcomes;
        this.audit = audit;
        this.clock = clock;
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

        Instant now = clock.instant();

        Optional<JdbcBulkOperationStore.BulkOperationRow> existing =
                bulkStore.findByIdempotencyKey(tenantId, command.idempotencyKey());
        if (existing.isPresent()) {
            // A resubmission under the same key. Nothing is re-executed, on the
            // same argument OrderAmendmentService.propose replays rather than
            // re-proposing: the recorded outcome, not a fresh attempt, is what
            // makes a retry after a partial failure safe.
            JdbcBulkOperationStore.BulkOperationRow settled = existing.get();
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
                now));

        List<BulkItemOutcome> results = new ArrayList<>();
        int applied = 0;
        int failed = 0;
        for (BulkOrderRef ref : command.orders()) {
            BulkItemOutcome outcome = applyItem(tenantId, brandId, locationId, bulkOperationId, command, ref);
            results.add(outcome);
            if (outcome.itemStatus() == BulkItemStatus.APPLIED) {
                applied++;
            } else {
                failed++;
            }
        }

        bulkStore.markCompleted(tenantId, bulkOperationId, clock.instant());
        recordSummaryAudit(tenantId, brandId, locationId, bulkOperationId, command, applied, failed, now);

        return new BulkActionResult(
                bulkOperationId, command.actionType(), command.orders().size(), false, results);
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
                    settled.resultingOrderVersion());
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
            return new BulkItemOutcome(ref.orderId(), BulkItemStatus.FAILED, "ORDER_NOT_FOUND_AT_LOCATION", null);
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
            return new BulkItemOutcome(ref.orderId(), BulkItemStatus.APPLIED, null, resultingVersion);
        } catch (OrderStateService.StaleOrderException stale) {
            return fail(tenantId, order, bulkOperationId, command, "STALE_VERSION");
        } catch (OrderStateMachine.IllegalTransitionException illegal) {
            return fail(tenantId, order, bulkOperationId, command, "ILLEGAL_TRANSITION");
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
        return new BulkItemOutcome(order.orderId(), BulkItemStatus.FAILED, problemCode, null);
    }

    private List<BulkItemOutcome> outcomesOf(UUID tenantId, UUID bulkOperationId) {
        return bulkStore.items(tenantId, bulkOperationId).stream()
                .map(row -> new BulkItemOutcome(
                        row.orderId(), row.itemStatus(), row.itemProblemCode(), row.resultingOrderVersion()))
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
                .changed(Map.of(
                        "bulkOperationId",
                        bulkOperationId.toString(),
                        "problemCode",
                        problemCode == null ? "" : problemCode))
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
        return command.actionType() == BulkActionType.ADVANCE
                ? Objects.requireNonNull(command.reasonCode())
                : "BULK_CANCELLATION";
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
                .changed(Map.of(
                        "requestedCount", command.orders().size(),
                        "appliedCount", applied,
                        "failedCount", failed))
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
            String actorId) {}

    public record BulkItemOutcome(
            UUID orderId,
            BulkItemStatus itemStatus,
            @Nullable String itemProblemCode,
            @Nullable Integer resultingOrderVersion) {}

    public record BulkActionResult(
            UUID bulkOperationId,
            BulkActionType actionType,
            int requestedCount,
            boolean replayed,
            List<BulkItemOutcome> items) {}
}
