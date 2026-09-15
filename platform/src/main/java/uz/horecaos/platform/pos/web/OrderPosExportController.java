package uz.horecaos.platform.pos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.PosOrderExportService;
import uz.horecaos.platform.pos.domain.ExportState;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore.ExportDetail;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The operations-plane sibling of {@link PosOrderExportController} (ADR 0011,
 * gap map row {@code 1.2i}, wave P42).
 *
 * <p>{@code integration.pos_order_exports} has been written from the ordering
 * side by {@code PosOrderExportTrigger} since ADR 0011 shipped, and until this
 * wave its only HTTP surface was control-plane — a platform operator's own
 * {@code AWAITING_OPERATOR} queue, never a tenant's own console. A branch
 * manager looking at the order in front of her had no way to see that its
 * ticket never reached the till, and no way to push it again.
 *
 * <p><strong>A sibling, deliberately.</strong> {@code OperationsOrderController}
 * owns {@code OrderDetailResponse} and nothing else edits that record (hazard
 * #2 in the operations gap map, the same reason {@link
 * uz.horecaos.platform.fulfillment.web.OrderDeliveryController} took this
 * shape for wave P11). The order is read here through {@link OrderDirectory}
 * — identifiers, status and the brand/location pair the ADR 0011 capability
 * lookup needs — never through ordering's own lines, notes or encrypted
 * fields.
 *
 * <p><strong>A failed export is not an order failure.</strong> The order is
 * real; the kitchen's copy of it is what may be missing. Every label this
 * controller's response feeds must say that, because the obvious wording
 * («order failed») sends an operator to cancel a paid order over a printer
 * problem.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/orders/{orderId}/pos-export")
@Tag(
        name = "Order POS export",
        description =
                "One order's export to the branch's point of sale, and pushing or " + "retrying it from the console")
public class OrderPosExportController {

    private final OrderDirectory orders;
    private final ProviderInstallationLookup installations;
    private final PosOrderExportService exports;
    private final JdbcPosExportStore exportStore;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public OrderPosExportController(
            OrderDirectory orders,
            ProviderInstallationLookup installations,
            PosOrderExportService exports,
            JdbcPosExportStore exportStore,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.orders = orders;
        this.installations = installations;
        this.exports = exports;
        this.exportStore = exportStore;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping
    @RequiresCapability(Capability.POS_EXPORT_READ)
    @Operation(
            summary = "Whether this order reached the branch's till, and why not if it did not",
            description = "`posCapable` is false when the location's primary POS binding does not "
                    + "declare ORDER_EXPORT (ADR 0011) -- the console suppresses the whole affordance "
                    + "in that case, and `export` is always null alongside it. `export` is otherwise "
                    + "null for an order that has not yet opened an export row (not yet confirmed, or "
                    + "confirmed too recently for the trigger to have run) -- not an error.")
    public ResponseEntity<OrderPosExportResponse> forOrder(@PathVariable UUID tenantId, @PathVariable UUID orderId) {
        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        boolean posCapable = posCapable(tenantId, order);
        ExportView export = posCapable
                ? exportStore
                        .findDetailByOrder(tenantId, orderId)
                        .map(ExportView::of)
                        .orElse(null)
                : null;

        return ResponseEntity.ok(new OrderPosExportResponse(posCapable, export));
    }

    @PostMapping("/push")
    @RequiresCapability(value = Capability.POS_EXPORT_RESOLVE, mutating = true)
    @Operation(
            summary = "Push this order to the till, or retry a push that is safe to repeat",
            description = "The same `PosOrderExportService#send` the automatic trigger uses, called "
                    + "by a person instead of a scheduler. Refuses -- without touching the provider "
                    + "-- for an export the state machine does not permit sending again (already "
                    + "accepted, already sent, or waiting on the control-plane recovery read); the "
                    + "response says which state it is in rather than throwing, because that refusal "
                    + "is an ordinary answer, not a fault.")
    public ResponseEntity<PushResultResponse> push(
            @PathVariable UUID tenantId, @PathVariable UUID orderId, @Valid @RequestBody PushRequest request) {

        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        if (!posCapable(tenantId, order)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This branch's point of sale does not accept pushed orders",
                    Map.of("reason", "POS_NOT_CAPABLE"));
        }

        UUID exportId = exports.open(tenantId, orderId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "This order's POS export could not be opened",
                        Map.of("reason", "EXPORT_NOT_OPENABLE")));

        ProviderOutcome outcome = exports.send(tenantId, exportId);

        ExportState settledState = exportStore
                .findDetailByOrder(tenantId, orderId)
                .map(ExportDetail::state)
                .orElseThrow(() ->
                        new IllegalStateException("The export just opened or sent has vanished for order " + orderId));

        // ADR 0027: who asked the till to try again, and why -- an operator
        // asserting a push is a claim somebody may later have to defend against
        // a kitchen that says it never saw the ticket.
        audit.record(AuditFact.of("pos.export_push_requested", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("PosOrderExport", exportId)
                .because(request.reason())
                .changed(Map.of(
                        "outcome", outcome.status().name(),
                        "state", settledState.name()))
                .usingCapability(Capability.POS_EXPORT_RESOLVE.code())
                .correlatedBy(exportId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(new PushResultResponse(
                outcome.status().name(), settledState.name(), outcome.errorCode(), outcome.detail()));
    }

    private boolean posCapable(UUID tenantId, OrderDirectory.OrderSummary order) {
        return installations
                .primaryBinding(tenantId, order.brandId(), order.locationId(), PosCapability.ORDER_EXPORT.code())
                .isPresent();
    }

    // ----------------------------------------------------------- payloads

    /** A person's reason for asking the till to try again -- the same discipline every ADR 0027 mutation here carries. */
    public record PushRequest(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * One order's export picture, as the console sees it.
     *
     * @param posCapable whether the location's own POS binding declares
     *                   {@code ORDER_EXPORT} at all (ADR 0011). False
     *                   suppresses the whole affordance -- there is nothing
     *                   for the console to show or push
     * @param export     null when {@code posCapable} is false, or when no
     *                   export row has opened yet for this order
     */
    public record OrderPosExportResponse(
            boolean posCapable, @Nullable ExportView export) {}

    /**
     * One export, as an operator needs to see it -- never the control-plane's
     * protected recovery candidates (ADR 0011), and never a customer field.
     *
     * @param permitsAmendment mirrors {@link ExportState#permitsAmendment()}:
     *                         the &sect;3.11 amendment interlock's own signal,
     *                         so the console never re-derives it from the raw
     *                         {@code state} string
     * @param lastErrorCode    the adapter's own diagnostic code from the last
     *                         settled attempt, e.g. {@code LINE_UNMAPPED} (an
     *                         ADR 0012 mapping gap) -- never a reason to say
     *                         "order failed": the order is real, its kitchen
     *                         copy is what did not arrive
     */
    public record ExportView(
            UUID exportId,
            String state,
            boolean permitsAmendment,
            int attemptCount,
            @Nullable String externalOrderId,
            Instant requestedAt,
            @Nullable Instant firstSentAt,
            @Nullable Instant settledAt,
            @Nullable String lastErrorCode,
            @Nullable String lastError,
            @Nullable String resolutionKind,
            @Nullable String resolutionReason,
            @Nullable Instant resolvedAt) {

        static ExportView of(ExportDetail detail) {
            return new ExportView(
                    detail.exportId(),
                    detail.state().name(),
                    detail.state().permitsAmendment(),
                    detail.attemptCount(),
                    detail.externalOrderId(),
                    detail.requestedAt(),
                    detail.firstSentAt(),
                    detail.settledAt(),
                    detail.lastErrorCode(),
                    detail.lastError(),
                    detail.resolutionKind(),
                    detail.resolutionReason(),
                    detail.resolvedAt());
        }
    }

    /**
     * What one push attempt came to. Always {@code 200} -- a refusal the
     * state machine already understood (already sent, already landed, waiting
     * on a control-plane decision) is an ordinary answer, carried in {@code
     * status}/{@code errorCode}, never a thrown fault.
     *
     * @param status the {@code ProviderOutcome.Status} name: {@code SUCCESS},
     *               {@code REJECTED}, {@code RETRYABLE} or {@code UNCERTAIN}
     * @param state  the export's {@link ExportState} after this call
     */
    public record PushResultResponse(
            String status,
            String state,
            @Nullable String errorCode,
            @Nullable String detail) {}
}
