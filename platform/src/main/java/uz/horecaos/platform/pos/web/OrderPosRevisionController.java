package uz.horecaos.platform.pos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.TillRevisionNotices.Notice;
import uz.horecaos.platform.pos.application.PosOrderRevisionService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * An order the till holds whose total has moved since (ADR 0137, ADR 0011, row {@code 4.2c}): whether it
 * has, what the till holds against what the order is worth now, and the two ways to close it. A sibling of
 * {@link OrderPosExportController} for the same reason that one is a sibling of {@code
 * OperationsOrderController}: nothing else edits {@code OrderDetailResponse}, and the till is this module's
 * to describe.
 *
 * <p>The weighed total itself is not in doubt and the order is not failed; the till's copy is what is
 * stale. Every label this response feeds must say that, because the obvious wording («the order is wrong»)
 * sends an operator to cancel a paid order over a number on a till.
 */
@RestController
@RequestMapping(
        "/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/{orderId}/till-revision")
@Tag(
        name = "Order till revision",
        description = "An order the till already holds whose total changed after a weight was captured, and "
                + "telling the till or saying it was corrected")
public class OrderPosRevisionController {

    private final OrderDirectory orders;
    private final PosOrderRevisionService revisions;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public OrderPosRevisionController(
            OrderDirectory orders,
            PosOrderRevisionService revisions,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.orders = orders;
        this.revisions = revisions;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping
    @RequiresCapability(value = Capability.POS_EXPORT_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary =
                    "Whether the till holds a different total than this order has, and whether the platform can tell it",
            description = "`notice` is null when the till and the order agree (or the order was never "
                    + "exported before its weight was captured). An open notice carries what the till holds "
                    + "and what the order is worth; once closed, the latest notice is returned with its "
                    + "status so a screen can say what happened. `canTell` is true only while a notice is "
                    + "open and the binding the order was exported through has ORDER_REVISION enabled and "
                    + "usable: no real vendor has it today.")
    public ResponseEntity<TillRevisionResponse> tillRevisionForOrder(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {
        requireOrderAtLocation(tenantId, brandId, locationId, orderId);
        return ResponseEntity.ok(TillRevisionResponse.of(revisions.view(tenantId, orderId)));
    }

    @PostMapping("/send")
    @RequiresCapability(value = Capability.POS_EXPORT_RESOLVE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Tell the till the order's revised total",
            description = "Only where the binding can take one (ORDER_REVISION). Sent under a key built from "
                    + "the notice, so a repeat is the same send. The body names the revision of the notice the "
                    + "operator saw: 409 STALE_VERSION, with nothing sent and nothing closed, when the order has "
                    + "been weighed again since. Otherwise answers 200 with the outcome in the body: a "
                    + "refusal, an unreachable till, an unknown outcome and a notice that moved while the till "
                    + "was being asked (SUPERSEDED) are ordinary answers, and in each the notice stays open "
                    + "with a stable failure code.")
    public ResponseEntity<TillRevisionSendResponse> sendTillRevision(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody TillRevisionActionRequest request) {
        requireOrderAtLocation(tenantId, brandId, locationId, orderId);
        String subject = currentActor.get().subject();
        PosOrderRevisionService.Result result;
        try {
            result = revisions.tell(tenantId, orderId, subject, request.expectedRevision());
        } catch (PosOrderRevisionService.StaleNoticeException stale) {
            throw ApiException.staleVersion(stale.expectedRevision(), stale.actualRevision());
        }
        Notice notice = result.view().notice();
        if (notice != null) {
            audit(tenantId, notice, "pos.order_revision_send_requested", "TILL_REVISION_SENT", subject, result);
        }
        return ResponseEntity.ok(new TillRevisionSendResponse(
                result.outcome().name(), result.code(), messageFor(result), TillRevisionResponse.of(result.view())));
    }

    @PostMapping("/acknowledge")
    @RequiresCapability(value = Capability.POS_EXPORT_RESOLVE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Say the till now agrees with the order",
            description = "A person changed the till by hand. Closes the open notice as ACKNOWLEDGED, audited "
                    + "against the person. The body names the revision of the notice the person saw: 409 "
                    + "STALE_VERSION, with the notice left open, when the order has been weighed again since, "
                    + "because what they changed the till to is not the total the order is worth now. 409 "
                    + "NO_OPEN_NOTICE when none is open.")
    public ResponseEntity<TillRevisionResponse> acknowledgeTillRevision(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody TillRevisionActionRequest request) {
        requireOrderAtLocation(tenantId, brandId, locationId, orderId);
        String subject = currentActor.get().subject();
        Notice before = revisions.view(tenantId, orderId).notice();
        if (before == null || !before.open()) {
            throw noOpenNotice();
        }
        boolean closed;
        try {
            closed = revisions.acknowledge(tenantId, orderId, subject, request.expectedRevision());
        } catch (PosOrderRevisionService.StaleNoticeException stale) {
            throw ApiException.staleVersion(stale.expectedRevision(), stale.actualRevision());
        }
        if (!closed) {
            throw noOpenNotice();
        }
        PosOrderRevisionService.View after = revisions.view(tenantId, orderId);
        audit.record(AuditFact.of("pos.order_revision_acknowledged", AuditClass.BUSINESS)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.location(tenantId, before.brandId(), before.locationId()))
                .target("OrderTillRevisionNotice", before.id())
                .because("TILL_CORRECTED_BY_HAND")
                .changed(ChangeDocuments.created(Map.of(
                        "tillTotalMinor", before.tillTotalMinor(),
                        "revisedTotalMinor", before.revisedTotalMinor(),
                        "currency", before.currency())))
                .usingCapability(Capability.POS_EXPORT_RESOLVE.code())
                .correlatedBy(before.orderId().toString())
                .occurredAt(clock.instant())
                .build());
        return ResponseEntity.ok(TillRevisionResponse.of(after));
    }

    private static ApiException noOpenNotice() {
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "This order has no open till revision to acknowledge",
                Map.of("conflict", "NO_OPEN_NOTICE"));
    }

    private void audit(
            UUID tenantId,
            Notice notice,
            String action,
            String reason,
            String subject,
            PosOrderRevisionService.Result result) {
        audit.record(AuditFact.of(action, AuditClass.BUSINESS)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.location(tenantId, notice.brandId(), notice.locationId()))
                .target("OrderTillRevisionNotice", notice.id())
                .because(reason)
                .changed(ChangeDocuments.created(Map.of(
                        "outcome",
                        result.outcome().name(),
                        "tillTotalMinor",
                        notice.tillTotalMinor(),
                        "revisedTotalMinor",
                        notice.revisedTotalMinor())))
                .usingCapability(Capability.POS_EXPORT_RESOLVE.code())
                .correlatedBy(notice.orderId().toString())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * The order must be this branch's. The capability was checked against the branch in the path, and an order
     * id in that path is the client's: without this an operator of one branch could read, send or close the
     * notice of a sibling branch's order by naming it under their own (the same rule {@code
     * OperationsOrderController#requireOrderAtLocation} applies).
     */
    private void requireOrderAtLocation(UUID tenantId, UUID brandId, UUID locationId, UUID orderId) {
        boolean atLocation = orders.summary(tenantId, orderId)
                .filter(found -> brandId.equals(found.brandId()) && locationId.equals(found.locationId()))
                .isPresent();
        if (!atLocation) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }
    }

    /**
     * A platform-authored sentence for the outcome, never the vendor's own text (ADR 0029): a vendor error
     * body has been observed to echo request content back.
     */
    private static @Nullable String messageFor(PosOrderRevisionService.Result result) {
        return switch (result.outcome()) {
            case TOLD -> null;
            case NO_OPEN_NOTICE -> "There is nothing to tell the till: the totals already agree.";
            case NOT_AVAILABLE -> "This till cannot be told a new total from here. Change it by hand, then say so.";
            case REJECTED -> "The till refused the new total. Change it by hand, then say so.";
            case RETRYABLE -> "The till could not be reached. Try again, or change it by hand.";
            case UNCERTAIN ->
                "The till may or may not have the new total. Check it before sending again; sending again is the same send.";
            case SUPERSEDED ->
                "The till was sent the total as it stood, but the order was weighed again meanwhile. Check the new total, then tell the till again or change it by hand.";
        };
    }

    // ----------------------------------------------------------- payloads

    /**
     * The body of both actions.
     *
     * @param expectedRevision the {@code revision} of the notice the person looked at (ADR 0031's expected
     *     version, as a field because these are commands and not a resource). Required: an action that does not
     *     say which notice it is about would close whichever is open when it lands.
     */
    public record TillRevisionActionRequest(@NotNull Integer expectedRevision) {}

    /**
     * @param notice the order's open notice, or the latest closed one; null when there never was one
     * @param canTell whether the platform can tell the till itself right now
     */
    public record TillRevisionResponse(@Nullable TillRevisionNoticeView notice, boolean canTell) {

        static TillRevisionResponse of(PosOrderRevisionService.View view) {
            return new TillRevisionResponse(
                    view.notice() == null ? null : TillRevisionNoticeView.of(view.notice()), view.canTell());
        }
    }

    /**
     * @param revision the order revision whose total the till does not hold; it moves when the order is weighed
     *     again, and an action quotes it (see {@link TillRevisionActionRequest})
     * @param status {@code OPEN}, {@code TOLD} (the platform sent it) or {@code ACKNOWLEDGED} (a person changed
     *     the till)
     * @param tillTotalMinor what the till holds
     * @param revisedTotalMinor what the order is worth now
     * @param failureCode a stable code for the last unsuccessful try to tell the till, or null
     */
    public record TillRevisionNoticeView(
            UUID noticeId,
            int revision,
            String status,
            String source,
            String currency,
            long tillTotalMinor,
            long revisedTotalMinor,
            long deltaMinor,
            Instant openedAt,
            @Nullable Instant resolvedAt,
            int attemptCount,
            @Nullable String failureCode) {

        static TillRevisionNoticeView of(Notice notice) {
            return new TillRevisionNoticeView(
                    notice.id(),
                    notice.revision(),
                    notice.status(),
                    notice.source(),
                    notice.currency(),
                    notice.tillTotalMinor(),
                    notice.revisedTotalMinor(),
                    notice.deltaMinor(),
                    notice.openedAt(),
                    notice.resolvedAt(),
                    notice.attemptCount(),
                    notice.lastFailureCode());
        }
    }

    /**
     * @param outcome {@code TOLD}, {@code NO_OPEN_NOTICE}, {@code NOT_AVAILABLE}, {@code REJECTED},
     *     {@code RETRYABLE}, {@code UNCERTAIN} or {@code SUPERSEDED}
     * @param message a fixed sentence for the outcome, null when it was told
     */
    public record TillRevisionSendResponse(
            String outcome, @Nullable String code, @Nullable String message, TillRevisionResponse revision) {}
}
