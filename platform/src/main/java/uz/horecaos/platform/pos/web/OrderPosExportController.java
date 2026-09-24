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
            description = "`export` is read independently of `posCapable`: a location's binding can "
                    + "stop declaring ORDER_EXPORT (ADR 0011) after an order already has a real, "
                    + "unsettled export row, and the &sect;3.11 AMEND interlock has to keep seeing "
                    + "that row -- and its `permitsAmendment` signal -- regardless. `posCapable` gates "
                    + "only the push/retry affordance; `export` is null for an order that has not yet "
                    + "opened an export row at all (not yet confirmed, or confirmed too recently for "
                    + "the trigger to have run) -- not an error.")
    public ResponseEntity<OrderPosExportResponse> forOrder(@PathVariable UUID tenantId, @PathVariable UUID orderId) {
        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        boolean posCapable = posCapable(tenantId, order);
        // Read regardless of posCapable: a real, unsettled export row must
        // still surface -- and still disarm the AMEND interlock via
        // ExportView#permitsAmendment -- even after the binding stops
        // declaring ORDER_EXPORT. posCapable only gates the push/retry
        // action below, never this read.
        ExportView export = exportStore
                .findDetailByOrder(tenantId, orderId)
                .map(detail -> ExportView.of(detail, unmappedEntityFor(tenantId, orderId, detail.lastErrorCode())))
                .orElse(null);

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

        // ADR 0027: who asked the till to try again, and why -- an operator
        // asserting a push is a claim somebody may later have to defend against
        // a kitchen that says it never saw the ticket. Written by
        // PosOrderExportService itself, inside the same transaction as the
        // state change it describes, rather than as a separate write after
        // that change has already committed.
        ProviderOutcome outcome = exports.send(
                tenantId,
                exportId,
                (settledState, sendOutcome) ->
                        audit.record(AuditFact.of("pos.export_push_requested", AuditClass.BUSINESS)
                                .by(ActorRef.user(currentActor.get().subject(), null))
                                .at(ResourceScope.tenant(tenantId))
                                .target("PosOrderExport", exportId)
                                .because(request.reason())
                                .changed(Map.of(
                                        "outcome",
                                        sendOutcome.status().name(),
                                        "state",
                                        settledState == null ? "" : settledState.name()))
                                .usingCapability(Capability.POS_EXPORT_RESOLVE.code())
                                .correlatedBy(exportId.toString())
                                .occurredAt(clock.instant())
                                .build()));

        ExportState settledState = exportStore
                .findDetailByOrder(tenantId, orderId)
                .map(ExportDetail::state)
                .orElseThrow(() ->
                        new IllegalStateException("The export just opened or sent has vanished for order " + orderId));

        return ResponseEntity.ok(new PushResultResponse(
                outcome.status().name(),
                settledState.name(),
                outcome.errorCode(),
                safeErrorMessage(outcome.errorCode())));
    }

    /**
     * A platform-authored description of {@code errorCode}, never the
     * provider's own text (ADR 0029).
     *
     * <p>{@code CloposEnvelope#trim}'s own comment records why: a Clopos error
     * body has been observed to echo request content back, and a request body
     * here carries a customer's name, phone and address. Nothing downstream of
     * the adapter can tell a genuinely diagnostic sentence from an echoed one,
     * so this console -- and the audit fact and logs around it -- never carry
     * what the provider actually said, only a fixed sentence keyed off the
     * machine-readable code every outcome already carries beside its prose.
     * The raw text stays only in {@code integration.pos_order_exports.last_error}
     * and {@code pos_export_attempts.detail}, which are integration diagnostics
     * for engineering, not this tenant console.
     */
    private static @Nullable String safeErrorMessage(@Nullable String errorCode) {
        if (errorCode == null) {
            return null;
        }
        return switch (errorCode) {
            case "CLOPOS_INTEGRATOR_INVALID" ->
                "HorecaOS's own registration with the till provider was refused. This affects "
                        + "every branch, not just this one -- contact support.";
            case "CLOPOS_CLIENT_DISABLED" -> "The branch has disabled the Open API module in its own till back office.";
            case "CLOPOS_TOKEN_EXPIRED" -> "The till session expired; retrying will request a new one.";
            case "CLOPOS_REFUSED" -> "The till refused the order.";
            case "CIRCUIT_OPEN" -> "The till has been unreachable too many times recently and is temporarily paused.";
            case "LINE_UNMAPPED", "MODIFIER_UNMAPPED" -> "An item on this order has no till mapping yet.";
            case "ORDER_EMPTY" -> "The order has no lines to send.";
            case "ORDER_UNKNOWN" -> "The order behind this export could not be read.";
            case "BINDING_CHANGED" -> "The till this order was sent through no longer accepts exports.";
            case "NO_ADAPTER" -> "No till integration is registered for this branch's provider.";
            case "EXPORT_NOT_SENDABLE", "EXPORT_UNKNOWN", "EXPORT_CLAIMED_ELSEWHERE", "EXPORT_NOT_UNCERTAIN" ->
                "The export is not in a state this action can act on.";
            default -> "The till reported a problem exporting this order.";
        };
    }

    /**
     * Gap-map row 1.2i's fix path: only for the two codes it names, and never
     * for any other state, this order's lines are re-checked live for the
     * next unmapped entity a console deep link should pre-select on the ADR
     * 0012 mapping screen. Gated on the error code rather than run on every
     * read: {@link PosOrderExportService#findUnmappedEntity} reveals this
     * order's protected contact fields for the lookup (ADR 0029), and that
     * reveal belongs only to a screen that is actually showing a mapping
     * refusal, not to every ordinary POS-export status check.
     */
    private PosOrderExportService.@Nullable UnmappedEntity unmappedEntityFor(
            UUID tenantId, UUID orderId, @Nullable String lastErrorCode) {
        if (!"LINE_UNMAPPED".equals(lastErrorCode) && !"MODIFIER_UNMAPPED".equals(lastErrorCode)) {
            return null;
        }
        return exports.findUnmappedEntity(tenantId, orderId).orElse(null);
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
     * @param posCapable whether the location's own POS binding currently
     *                   declares {@code ORDER_EXPORT} at all (ADR 0011).
     *                   Gates only the push/retry affordance -- {@code export}
     *                   is read and returned regardless, so a real, unsettled
     *                   export never silently disappears (and the &sect;3.11
     *                   AMEND interlock never silently disarms) just because
     *                   the binding changed
     * @param export     null only when no export row has opened yet for this
     *                   order
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
     * @param lastError        a platform-authored sentence keyed off {@code
     *                         lastErrorCode} (ADR 0029) -- never the
     *                         provider's own text, which has been observed to
     *                         echo request content, including a customer's
     *                         address, back at the caller
     * @param unmappedEntityType gap-map row 1.2i's fix path: {@code "VARIANT"}
     *                         or {@code "MODIFIER"} when {@code lastErrorCode}
     *                         is {@code LINE_UNMAPPED}/{@code MODIFIER_UNMAPPED}
     *                         and a currently-unmapped line or modifier was
     *                         found live -- null otherwise, including when the
     *                         error code names a mapping gap but every line now
     *                         resolves (an operator may have already fixed it)
     * @param unmappedHorecaosEntityId the id to pre-select on the ADR 0012
     *                         mapping screen -- an internal id only, never a
     *                         provider code or free text, paired one-to-one
     *                         with {@code unmappedEntityType}
     * @param unmappedBindingId the {@code ORDER_EXPORT} binding {@code
     *                         unmappedHorecaosEntityId} was checked against
     *                         (see {@link PosOrderExportService.UnmappedEntity#bindingId}) --
     *                         without this a console deep link built from
     *                         only the two fields above lands the ADR 0012
     *                         mapping screen on whichever POS binding
     *                         happens to be first in the tenant's own list,
     *                         which is the wrong one for any tenant with more
     *                         than one
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
            @Nullable Instant resolvedAt,
            @Nullable String unmappedEntityType,
            @Nullable UUID unmappedHorecaosEntityId,
            @Nullable UUID unmappedBindingId) {

        static ExportView of(ExportDetail detail, PosOrderExportService.@Nullable UnmappedEntity unmapped) {
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
                    safeErrorMessage(detail.lastErrorCode()),
                    detail.resolutionKind(),
                    detail.resolutionReason(),
                    detail.resolvedAt(),
                    unmapped == null ? null : unmapped.entityType(),
                    unmapped == null ? null : unmapped.horecaosEntityId(),
                    unmapped == null ? null : unmapped.bindingId());
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
     * @param detail a platform-authored sentence keyed off {@code errorCode}
     *               (ADR 0029) -- never the provider's own text; see {@link
     *               ExportView#lastError} for why
     */
    public record PushResultResponse(
            String status,
            String state,
            @Nullable String errorCode,
            @Nullable String detail) {}
}
