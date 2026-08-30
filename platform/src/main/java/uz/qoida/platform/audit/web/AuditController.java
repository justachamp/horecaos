package uz.qoida.platform.audit.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.audit.application.AuditQueryService;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.CurrentActor;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.web.api.Page;
import uz.qoida.platform.web.authorization.RequiresCapability;

/** Audit evidence, readable only with {@code audit.read} (ADR 0027). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Audit", description = "Immutable security and business evidence")
public class AuditController {

    private final AuditQueryService audits;
    private final AuditRecorder recorder;
    private final CurrentActor currentActor;
    private final java.time.Clock clock;

    public AuditController(
            AuditQueryService audits,
            AuditRecorder recorder,
            CurrentActor currentActor,
            java.time.Clock clock) {
        this.audits = audits;
        this.recorder = recorder;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping("/control-plane/tenants/{tenantId}/audit-events")
    @RequiresCapability(Capability.AUDIT_READ)
    @Operation(summary = "Search audit evidence within a tenant",
            description = "Reading audit is itself audited. The change document is not returned in a "
                    + "list; retrieving it is a separate, individually audited read.")
    Page<AuditQueryService.AuditEventView> search(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String actorSubject,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) String auditClass,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit) {

        List<AuditQueryService.AuditEventView> events = audits.search(new AuditQueryService.AuditQuery(
                tenantId, actorSubject, actionCode, targetId, auditClass, from, to, limit));

        recordTheRead(tenantId, events.size(), actorSubject, actionCode);
        return Page.last(events);
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
                .changed(java.util.Map.of(
                        "returned", returned,
                        "actorFilter", String.valueOf(actorFilter),
                        "actionFilter", String.valueOf(actionFilter)))
                .usingCapability(Capability.AUDIT_READ.code())
                .correlatedBy(UUID.randomUUID().toString())
                .occurredAt(clock.instant())
                .build());
    }
}
