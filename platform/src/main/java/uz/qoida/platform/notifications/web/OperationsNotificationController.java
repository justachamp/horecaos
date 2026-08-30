package uz.qoida.platform.notifications.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.notifications.application.NotificationQueryService;
import uz.qoida.platform.notifications.application.NotificationQueryService.NotificationDetail;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * Answering "why did the customer not get their confirmation?" (ADR 0020).
 *
 * <p>That question is the reason this module keeps a row for a message it refused
 * to send. The response below names the suppression reason, the template version
 * that was chosen, every attempt, and every status the gateway gave — which is the
 * whole trail, minus the person.
 *
 * <p>The recipient appears as an endpoint id and never as a phone number. Turning
 * one into the other needs {@code CUSTOMER_PII_REVEAL} and a stated purpose in the
 * customers module, which is where that decision belongs and where it is recorded.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/notifications")
@Tag(name = "Operations notifications",
        description = "Delivery evidence for one message, and the manual retry")
public class OperationsNotificationController {

    private final NotificationQueryService notifications;

    public OperationsNotificationController(NotificationQueryService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/{notificationId}")
    @RequiresCapability(Capability.NOTIFICATION_READ)
    @Operation(summary = "One message, with its attempts and provider statuses",
            description = "The provider's own wording is kept verbatim on each status, because "
                    + "'accepted' and 'delivered to handset' are different promises and a "
                    + "support conversation turns on which one was actually given.")
    public ResponseEntity<NotificationResponse> detail(@PathVariable UUID tenantId,
            @PathVariable UUID notificationId) {

        return notifications.detail(tenantId, notificationId)
                .map(OperationsNotificationController::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No notification " + notificationId + " belongs to this tenant"));
    }

    @GetMapping("/orders/{orderId}")
    @RequiresCapability(Capability.NOTIFICATION_READ)
    @Operation(summary = "Every message about one order, newest first",
            description = "Including the ones that were never sent. A suppressed confirmation is "
                    + "the answer to the question that brought the operator here.")
    public ResponseEntity<List<NotificationSummary>> forOrder(@PathVariable UUID tenantId,
            @PathVariable UUID orderId) {

        return ResponseEntity.ok(notifications.forOrder(tenantId, orderId).stream()
                .map(row -> new NotificationSummary(row.id(), row.templateKey(), row.channel(),
                        row.status(), row.suppressionReason(), row.locale(), row.createdAt()))
                .toList());
    }

    @PostMapping("/{notificationId}/retry")
    @RequiresCapability(value = Capability.NOTIFICATION_RETRY, mutating = true)
    @Operation(summary = "Put a settled message back in the queue",
            description = "Re-runs the eligibility gate from the start, so consent withdrawn "
                    + "since cannot be overridden by pressing retry. A delivered message is "
                    + "refused: resending it is how a customer gets two confirmations from a "
                    + "well-meaning support action.")
    public ResponseEntity<Void> retry(@PathVariable UUID tenantId,
            @PathVariable UUID notificationId, @Valid @RequestBody RetryRequest request) {

        if (!notifications.retry(tenantId, notificationId, request.reason())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This message is not in a state a retry applies to");
        }
        return ResponseEntity.accepted().build();
    }

    private static NotificationResponse toResponse(NotificationDetail detail) {
        var row = detail.notification();
        return new NotificationResponse(
                row.id(), row.brandId(), row.locationId(), row.notificationClass(), row.channel(),
                row.templateKey(), row.templateId(), row.templateVersion(), row.locale(),
                row.subjectType(), row.subjectId(), row.recipientEndpointId(), row.status(),
                row.suppressionReason(), row.variablesHash(), row.renderedContentHash(),
                row.attemptCount(), row.expiresAt(), row.terminalAt(), row.lastError(),
                row.version(),
                detail.attempts().stream()
                        .map(attempt -> new AttemptResponse(
                                attempt.attempt().attemptNumber(),
                                attempt.attempt().status(),
                                attempt.attempt().providerType(),
                                attempt.attempt().externalMessageId(),
                                attempt.attempt().failureCode(),
                                attempt.attempt().uncertainOutcome(),
                                attempt.attempt().requestedAt(),
                                attempt.attempt().acknowledgedAt(),
                                attempt.statusEvents().stream()
                                        .map(event -> new StatusEventResponse(
                                                event.normalizedStatus(), event.providerStatus(),
                                                event.occurredAt()))
                                        .toList()))
                        .toList());
    }

    /** @param reason recorded on the message, because a manual action needs one */
    public record RetryRequest(@NotBlank @Size(max = 500) String reason) { }

    public record NotificationSummary(UUID id, String templateKey, String channel, String status,
            String suppressionReason, String locale, Instant createdAt) { }

    /**
     * @param recipientEndpointId a reference. Resolving it to a contact value is a
     *                            separate, separately authorized act
     * @param renderedContentHash what was sent, as a hash. With the frozen template
     *                            version and the variables hash it reproduces the
     *                            message without this response carrying it
     */
    public record NotificationResponse(UUID id, UUID brandId, UUID locationId,
            String notificationClass, String channel, String templateKey, UUID templateId,
            Integer templateVersion, String locale, String subjectType, UUID subjectId,
            UUID recipientEndpointId, String status, String suppressionReason,
            String variablesHash, String renderedContentHash, int attemptCount, Instant expiresAt,
            Instant terminalAt, String lastError, int version, List<AttemptResponse> attempts) { }

    public record AttemptResponse(int attemptNumber, String status, String providerType,
            String externalMessageId, String failureCode, boolean uncertainOutcome,
            Instant requestedAt, Instant acknowledgedAt, List<StatusEventResponse> statusEvents) { }

    public record StatusEventResponse(String normalizedStatus, String providerStatus,
            Instant occurredAt) { }
}
