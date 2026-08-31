package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.failures.FailureCategory;
import uz.horecaos.platform.integration.failures.FailureOperationsService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Operations access to failed messages (ADR 0006).
 *
 * <p>These endpoints exist so that the answer to "a message is stuck" is never
 * an engineer with a database client. Direct SQL leaves no audit trail, no
 * idempotency guarantee, and no way to prove afterwards that a refund was not
 * sent twice.
 */
@RestController
@RequestMapping("/api/v1/control-plane/integration/failures")
@Tag(name = "Integration failures", description = "Inspect, retry, and resolve failed messages")
public class FailureOperationsController {

    private final FailureOperationsService operations;
    private final CurrentActor currentActor;

    public FailureOperationsController(FailureOperationsService operations, CurrentActor currentActor) {
        this.operations = operations;
        this.currentActor = currentActor;
    }

    @GetMapping("/outbox")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "List failed outbox events")
    Page<FailureOperationsService.FailureSummary> outboxFailures(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "DEAD_LETTER") String status,
            @RequestParam(required = false) Integer limit) {

        List<FailureOperationsService.FailureSummary> failures =
                operations.listOutboxFailures(tenantId, status, Page.limitOrDefault(limit));
        return Page.last(failures);
    }

    @GetMapping("/inbox/{consumerName}")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "List failed inbox messages for one consumer")
    Page<FailureOperationsService.FailureSummary> inboxFailures(
            @PathVariable String consumerName,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "DEAD_LETTER") String status,
            @RequestParam(required = false) Integer limit) {

        List<FailureOperationsService.FailureSummary> failures =
                operations.listInboxFailures(consumerName, tenantId, status, Page.limitOrDefault(limit));
        return Page.last(failures);
    }

    @GetMapping("/outbox/{eventId}")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Inspect one outbox event",
            description = "The routing, retry, and resolution facts of a single event. "
                    + "The payload is never returned: working the failure queue is not "
                    + "authority to read the customer record behind an item (ADR 0029). "
                    + "tenantId narrows the read exactly as it narrows the list; an event "
                    + "outside it is reported as not found, never as forbidden.")
    FailureOperationsService.OutboxFailureDetail outboxFailure(
            @PathVariable UUID eventId, @RequestParam(required = false) UUID tenantId) {

        return operations.findOutboxFailure(eventId, tenantId).orElseThrow(FailureOperationsController::notFound);
    }

    @GetMapping("/inbox/{consumerName}/{eventId}")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Inspect one consumer's copy of one event",
            description = "Keyed by consumer and event together, because one event reaches "
                    + "several consumers and each has its own attempt count and its own decision. "
                    + "The payload is never returned (ADR 0029); tenantId narrows the read and a "
                    + "message outside it is reported as not found, never as forbidden.")
    FailureOperationsService.InboxFailureDetail inboxFailure(
            @PathVariable String consumerName,
            @PathVariable UUID eventId,
            @RequestParam(required = false) UUID tenantId) {

        return operations
                .findInboxFailure(consumerName, eventId, tenantId)
                .orElseThrow(FailureOperationsController::notFound);
    }

    /**
     * Absent and not-yours are the same answer, on purpose.
     *
     * <p>The event id is a UUID the caller types. If a row that exists in
     * another tenant were refused with 403 while an id that exists nowhere gave
     * 404, the pair of statuses would confirm which identifiers are real — an
     * enumeration oracle of exactly the kind {@code ScopeNotFoundException} was
     * introduced to close for the tenant hierarchy. It answers the same way for
     * the same reason, and the two bodies are identical apart from RFC 9457's
     * {@code instance}, which is the caller's own request URI and so tells them
     * only what they already typed. The detail is deliberately generic: naming
     * the tenant, or saying "wrong tenant" rather than "no such message", would
     * hand back through the body exactly what the status stopped handing back.
     */
    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such failed message");
    }

    @PostMapping("/outbox/{eventId}/retry")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_RETRY, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Return a dead-lettered outbox event to pending",
            description = "Retries the same immutable event; it never mints a new event id.")
    ResponseEntity<Map<String, Object>> retryOutbox(
            @PathVariable UUID eventId, @Valid @RequestBody ReasonRequest request) {

        boolean retried = operations.retryOutboxEvent(eventId, actor(), request.reason());
        return outcome(retried, "retried");
    }

    @PostMapping("/inbox/{consumerName}/{eventId}/retry")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_RETRY, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Return a dead-lettered inbox message to pending for one consumer")
    ResponseEntity<Map<String, Object>> retryInbox(
            @PathVariable String consumerName, @PathVariable UUID eventId, @Valid @RequestBody ReasonRequest request) {

        boolean retried = operations.retryInboxMessage(consumerName, eventId, actor(), request.reason());
        return outcome(retried, "retried");
    }

    @PostMapping("/outbox/{eventId}/resolve")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_RESOLVE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Declare that an outbox event needs no further processing",
            description = "Irreversible. Uncertain provider outcomes require reconciliation evidence "
                    + "and a second approver.")
    ResponseEntity<Map<String, Object>> resolveOutbox(
            @PathVariable UUID eventId, @Valid @RequestBody ResolveRequest request) {

        boolean resolved = operations.resolveOutboxEvent(
                eventId, request.category(), actor(), request.reason(), request.evidenceReference());
        return outcome(resolved, "resolved");
    }

    @PostMapping("/inbox/{consumerName}/{eventId}/resolve")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_RESOLVE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Declare that an inbox message needs no further processing")
    ResponseEntity<Map<String, Object>> resolveInbox(
            @PathVariable String consumerName, @PathVariable UUID eventId, @Valid @RequestBody ResolveRequest request) {

        boolean resolved = operations.resolveInboxMessage(
                consumerName, eventId, request.category(), actor(), request.reason(), request.evidenceReference());
        return outcome(resolved, "resolved");
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /**
     * A no-op is reported rather than treated as an error: the item was not in a
     * terminal state, which during an incident usually means a colleague acted
     * first.
     */
    private static ResponseEntity<Map<String, Object>> outcome(boolean changed, String action) {
        return ResponseEntity.ok(Map.of("changed", changed, "outcome", changed ? action : "no_change"));
    }

    /** Retrying is safe and repeatable, so it needs only a reason. */
    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * A request to close a failure without further processing.
     *
     * @param evidenceReference reconciliation evidence, required when the
     *                          category means a provider may already have acted
     */
    public record ResolveRequest(
            @NotNull FailureCategory category,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 512) String evidenceReference) {}
}
