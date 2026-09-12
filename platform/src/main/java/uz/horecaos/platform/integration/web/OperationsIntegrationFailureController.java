package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import uz.horecaos.platform.integration.failures.FailureOperationsService;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A merchant's own view of ADR 0006's failure model (ADR 0106, gap-map row
 * 10.8c).
 *
 * <p>{@code FailureTaxonomyController} and {@code FailureOperationsController}
 * already answer these two questions — the operator-legible error taxonomy
 * and replaying a stuck inbound message — but only at {@code
 * /api/v1/control-plane/**} under {@code PLATFORM} scope, and their retry
 * method trusts an <em>optional</em> tenant filter a caller can simply omit.
 * A merchant cannot see that their own aggregator stopped pushing orders two
 * hours ago without phoning support, which is the row this class closes.
 *
 * <p>Both operations here are tenant-checked, not tenant-filtered:
 * {@code tenantId} comes from the authenticated path and {@link
 * FailureOperationsService#retryInboxMessageForTenant} refuses a message that
 * resolves to a different tenant rather than a caller-supplied predicate the
 * request could shape. This is a new, narrower surface beside the existing
 * platform one, not a replacement for it — the platform surface still serves
 * cross-tenant incident response.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/integrations/failures")
@Tag(name = "Integration failures (tenant)", description = "A merchant's own error taxonomy and inbox replay")
public class OperationsIntegrationFailureController {

    private final FailureOperationsService operations;
    private final CurrentActor currentActor;

    public OperationsIntegrationFailureController(FailureOperationsService operations, CurrentActor currentActor) {
        this.operations = operations;
        this.currentActor = currentActor;
    }

    @GetMapping("/taxonomy")
    @RequiresCapability(Capability.INTEGRATION_FAILURE_READ)
    @Operation(
            summary = "This tenant's failure categories, their rules, and how many messages are in each",
            description = "Waiting means still being retried; dead-lettered means waiting on a person. "
                    + "Every count is WHERE tenant_id = this path's tenantId — never an optional filter.")
    List<FailureOperationsService.TenantCategoryCount> taxonomy(@PathVariable UUID tenantId) {
        return operations.taxonomyForTenant(tenantId);
    }

    @GetMapping("/inbox")
    @RequiresCapability(Capability.INTEGRATION_FAILURE_READ)
    @Operation(
            summary = "This tenant's own failed inbound messages, across every consumer",
            description = "The payload is never returned (ADR 0029). One row per consumer that failed "
                    + "the event, the same shape the platform-wide surface uses, forced to this tenant.")
    Page<FailureOperationsService.InboxFailureSummary> inboxFailures(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "DEAD_LETTER") String status,
            @RequestParam(required = false) Integer limit) {

        return Page.last(operations.listInboxFailuresAcrossConsumers(tenantId, status, Page.limitOrDefault(limit)));
    }

    @PostMapping("/inbox/{consumerName}/{eventId}/replay")
    @RequiresCapability(value = Capability.INTEGRATION_FAILURE_RETRY, mutating = true)
    @Operation(
            summary = "Replay this tenant's own stuck inbound message",
            description = "Returns a dead-lettered inbox message to pending. A message belonging to "
                    + "another tenant is refused exactly like one that does not exist — ADR 0031's "
                    + "not-found-not-forbidden posture, so an event id is not an enumeration oracle.")
    ResponseEntity<Map<String, Object>> replay(
            @PathVariable UUID tenantId,
            @PathVariable String consumerName,
            @PathVariable UUID eventId,
            @Valid @RequestBody ReplayRequest request) {

        boolean replayed = operations.retryInboxMessageForTenant(
                consumerName,
                eventId,
                tenantId,
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return ResponseEntity.ok(Map.of("changed", replayed, "outcome", replayed ? "replayed" : "no_change"));
    }

    /** Replaying is safe and repeatable, so it needs only a reason. */
    public record ReplayRequest(@NotBlank @Size(max = 1000) String reason) {}
}
