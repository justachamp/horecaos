package uz.horecaos.platform.notifications.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcControlPlaneAlertStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcControlPlaneAlertStore.StoredAlert;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * ADR 0085: the platform's incidents — every control-plane alert kept until
 * someone resolves it.
 *
 * <p>Acknowledging says "someone has this"; resolving closes it with a note.
 * Both are audit facts, and a resolved incident that is raised again opens a
 * new one rather than reopening the old, so the record of what was done about
 * the first stays as it was.
 */
@RestController
@Validated
@Tag(name = "Incidents", description = "ADR 0085: control-plane alerts kept as incidents")
public class ControlPlaneIncidentController {

    private final JdbcControlPlaneAlertStore alerts;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public ControlPlaneIncidentController(
            JdbcControlPlaneAlertStore alerts, AuditRecorder audit, CurrentActor currentActor, Clock clock) {
        this.alerts = alerts;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping("/api/v1/control-plane/incidents")
    @RequiresCapability(value = Capability.CONTROL_PLANE_ALERT_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The platform's incidents, open ones first",
            description = "Each alert class and subject has at most one live incident; raising it "
                    + "again counts another occurrence.")
    List<IncidentView> list(
            @RequestParam(defaultValue = "false") boolean includeResolved,
            @RequestParam(defaultValue = "100") @Max(500) int limit) {
        return alerts.list(includeResolved, limit).stream()
                .map(IncidentView::of)
                .toList();
    }

    @PostMapping("/api/v1/control-plane/incidents/{incidentId}/acknowledgement")
    @RequiresCapability(value = Capability.CONTROL_PLANE_ALERT_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Say someone has this incident",
            description = "Only an open incident; a repeat changes nothing. Answers with no body: "
                    + "the note is free text and a stored idempotent response has no tenant to "
                    + "encrypt it under, so the caller reads the incident back instead.")
    @Transactional
    ResponseEntity<Void> acknowledge(
            @PathVariable UUID incidentId, @Valid @RequestBody(required = false) @Nullable NoteRequest body) {
        StoredAlert alert = require(incidentId);
        Instant now = clock.instant();
        if (alerts.acknowledge(incidentId, subject(), now)) {
            record("notifications.incident.acknowledged", alert, body == null ? "Acknowledged" : body.note(), now);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/control-plane/incidents/{incidentId}/resolution")
    @RequiresCapability(value = Capability.CONTROL_PLANE_ALERT_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Close an incident with what was done",
            description = "A resolved incident stays as it is. Answers with no body, for the same "
                    + "reason as acknowledging.")
    @Transactional
    ResponseEntity<Void> resolve(@PathVariable UUID incidentId, @Valid @RequestBody ResolveRequest body) {
        StoredAlert alert = require(incidentId);
        Instant now = clock.instant();
        if (alerts.resolve(incidentId, subject(), body.note(), now)) {
            record("notifications.incident.resolved", alert, body.note(), now);
        }
        return ResponseEntity.noContent().build();
    }

    private StoredAlert require(UUID incidentId) {
        return alerts.find(incidentId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such incident"));
    }

    private void record(String action, StoredAlert alert, String reason, Instant now) {
        audit.record(AuditFact.of(action, AuditClass.SECURITY)
                .by(ActorRef.user(subject(), null))
                .at(ResourceScope.platform())
                .target("ControlPlaneIncident", alert.id())
                .because(reason)
                .changed(Map.of("eventClass", alert.eventClass(), "subjectId", alert.subjectId()))
                .usingCapability(Capability.CONTROL_PLANE_ALERT_MANAGE.code())
                .correlatedBy(alert.id().toString())
                .occurredAt(now)
                .build());
    }

    private String subject() {
        return currentActor.get().subject();
    }

    record NoteRequest(@NotBlank @Size(max = 1000) String note) {}

    record ResolveRequest(@NotBlank @Size(max = 1000) String note) {}

    /** One incident as the console shows it. */
    public record IncidentView(
            UUID id,
            String eventClass,
            String subjectType,
            String subjectId,
            Map<String, String> variables,
            String firstRaisedAt,
            String lastRaisedAt,
            int occurrences,
            String status,
            @Nullable String acknowledgedBy,
            @Nullable String acknowledgedAt,
            @Nullable String resolvedBy,
            @Nullable String resolvedAt,
            @Nullable String resolutionNote) {

        static IncidentView of(StoredAlert alert) {
            return new IncidentView(
                    alert.id(),
                    alert.eventClass(),
                    alert.subjectType(),
                    alert.subjectId(),
                    alert.variables(),
                    alert.firstRaisedAt().toString(),
                    alert.lastRaisedAt().toString(),
                    alert.occurrences(),
                    alert.status(),
                    alert.acknowledgedBy(),
                    alert.acknowledgedAt() == null
                            ? null
                            : alert.acknowledgedAt().toString(),
                    alert.resolvedBy(),
                    alert.resolvedAt() == null ? null : alert.resolvedAt().toString(),
                    alert.resolutionNote());
        }
    }
}
