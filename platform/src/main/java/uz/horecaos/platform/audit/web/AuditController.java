package uz.horecaos.platform.audit.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.application.AuditQueryService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Audit evidence, readable only with {@code audit.read} (ADR 0027).
 *
 * <p>The same search and detail shape is exposed twice: under {@code
 * control-plane} for HorecaOS staff, and under {@code operations} for a
 * tenant's own staff — Staff 9.3's activity log, which had no operations-surface
 * reader at all before this wave (the control-plane route is not reachable from
 * the operations frontend's OpenAPI group, ADR 0057). Both call the same {@link
 * AuditQueryService}; nothing about how audit is read differs between the two
 * audiences, only which app may reach it.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Audit", description = "Immutable security and business evidence")
public class AuditController {

    private final AuditQueryService audits;
    private final AuditRecorder recorder;
    private final CurrentActor currentActor;
    private final java.time.Clock clock;

    public AuditController(
            AuditQueryService audits, AuditRecorder recorder, CurrentActor currentActor, java.time.Clock clock) {
        this.audits = audits;
        this.recorder = recorder;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping("/control-plane/tenants/{tenantId}/audit-events")
    @RequiresCapability(Capability.AUDIT_READ)
    @Operation(
            summary = "Search audit evidence within a tenant",
            description = "Reading audit is itself audited. The change document is not returned in a "
                    + "list; retrieving it is a separate, individually audited read.")
    public Page<AuditQueryService.AuditEventView> search(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String actorSubject,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) String auditClass,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) UUID scopeId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit) {
        return searchAndRecord(
                tenantId,
                actorSubject,
                actionCode,
                targetId,
                auditClass,
                outcome,
                scopeType,
                scopeId,
                correlationId,
                from,
                to,
                limit);
    }

    @GetMapping("/control-plane/tenants/{tenantId}/audit-events/{eventId}")
    @RequiresCapability(Capability.AUDIT_READ)
    @Operation(
            summary = "One event's full record, including the change document",
            description = "The diff a settings or an order dispute is actually resolved with. "
                    + "Excluded from the list response on purpose (ADR 0027 §11.13): a change "
                    + "document can carry redacted structure that is still revealing in bulk, so "
                    + "retrieving it is a separate, individually audited read.")
    public ResponseEntity<AuditQueryService.AuditEventDetail> detail(
            @PathVariable UUID tenantId, @PathVariable UUID eventId) {
        return detailAndRecord(tenantId, eventId);
    }

    @GetMapping("/operations/tenants/{tenantId}/audit-events")
    @RequiresCapability(Capability.AUDIT_READ)
    @Operation(
            summary = "Search audit evidence within a tenant — Staff 9.3's activity log",
            description = "The same read as the control-plane route, reachable from the "
                    + "operations frontend. Reading audit is itself audited.")
    public Page<AuditQueryService.AuditEventView> operationsSearch(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String actorSubject,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) String auditClass,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) UUID scopeId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit) {
        return searchAndRecord(
                tenantId,
                actorSubject,
                actionCode,
                targetId,
                auditClass,
                outcome,
                scopeType,
                scopeId,
                correlationId,
                from,
                to,
                limit);
    }

    @GetMapping("/operations/tenants/{tenantId}/audit-events/{eventId}")
    @RequiresCapability(Capability.AUDIT_READ)
    @Operation(summary = "One event's full record, including the change document — Staff 9.3's detail drawer")
    public ResponseEntity<AuditQueryService.AuditEventDetail> operationsDetail(
            @PathVariable UUID tenantId, @PathVariable UUID eventId) {
        return detailAndRecord(tenantId, eventId);
    }

    private Page<AuditQueryService.AuditEventView> searchAndRecord(
            UUID tenantId,
            String actorSubject,
            String actionCode,
            UUID targetId,
            String auditClass,
            String outcome,
            String scopeType,
            UUID scopeId,
            String correlationId,
            Instant from,
            Instant to,
            Integer limit) {

        List<AuditQueryService.AuditEventView> events = audits.search(new AuditQueryService.AuditQuery(
                tenantId,
                actorSubject,
                actionCode,
                targetId,
                auditClass,
                outcome,
                scopeType,
                scopeId,
                correlationId,
                from,
                to,
                limit));

        recordTheRead(tenantId, events.size(), actorSubject, actionCode);
        return Page.last(events);
    }

    private ResponseEntity<AuditQueryService.AuditEventDetail> detailAndRecord(UUID tenantId, UUID eventId) {
        AuditQueryService.AuditEventDetail event = audits.findDetail(tenantId, eventId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No audit event %s for this tenant".formatted(eventId)));

        recorder.record(AuditFact.of("audit.read", AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("AuditEvent", eventId)
                .because("Audit event detail")
                .usingCapability(Capability.AUDIT_READ.code())
                .correlatedBy(UUID.randomUUID().toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(event);
    }

    /**
     * The most sensitive thing in the system is the record of who did what, so
     * an unlogged reader of it is a gap. The count is recorded because the
     * difference between reading one record and reading two hundred is the
     * difference this control exists to capture.
     */
    private void recordTheRead(UUID tenantId, int returned, String actorFilter, String actionFilter) {
        recorder.record(AuditFact.of("audit.read", AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .because("Audit search")
                .changed(Map.of(
                        "returned", returned,
                        "actorFilter", String.valueOf(actorFilter),
                        "actionFilter", String.valueOf(actionFilter)))
                .usingCapability(Capability.AUDIT_READ.code())
                .correlatedBy(UUID.randomUUID().toString())
                .occurredAt(clock.instant())
                .build());
    }
}
