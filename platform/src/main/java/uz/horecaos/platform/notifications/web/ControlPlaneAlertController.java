package uz.horecaos.platform.notifications.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Where a non-Java platform signal reaches the ADR 0058 control-plane
 * audience (v1: {@link ControlPlaneAlertPort}'s log line and counter — see
 * that interface's own Javadoc for what "reaches" means today).
 *
 * <p>{@code ops/control_band_watch.py} is this endpoint's first and, as of
 * this build, only caller: it runs on the ops host's cron, outside the
 * Java process entirely, and ADR 0058's own checklist names "control-band
 * tier escalations" as a control-plane event class this build owes a
 * trigger for. Python cannot call {@code
 * uz.horecaos.platform.notifications.application.OperationsAlertFanoutService}
 * — there is no in-process listener to attach to a process that is not the
 * JVM — so the watcher becomes an HTTP caller instead, authenticated and
 * capability-gated exactly like any other client of this API. Provisioning
 * the ops host with a credential that holds {@link
 * Capability#CONTROL_PLANE_ALERT_RAISE} (a Keycloak service account, per
 * ADR 0028 — {@code tools/seed-payments}' own note that this repo has no
 * client-credentials flow yet applies here too) is an operations runbook
 * step this build names but does not perform.
 */
@RestController
@RequestMapping("/api/v1/control-plane/alerts")
@Tag(name = "SaaS control plane", description = "Control-plane alerts raised from outside the Java process")
public class ControlPlaneAlertController {

    static final String CONTROL_BAND_ESCALATED = "CONTROL_BAND_ESCALATED";

    static final String SUBJECT_TYPE = "ControlBandMetric";

    private final ControlPlaneAlertPort controlPlaneAlerts;
    private final Clock clock;

    public ControlPlaneAlertController(ControlPlaneAlertPort controlPlaneAlerts, Clock clock) {
        this.controlPlaneAlerts = controlPlaneAlerts;
        this.clock = clock;
    }

    /**
     * One {@code ops/bands.yaml} metric's tier escalation
     * ({@code ops/control_band_watch.py}'s own {@code respond()} call), enqueued
     * for the control-plane audience.
     *
     * <p>Fire-and-forget by design, matching {@link
     * ControlPlaneAlertPort#raise}'s own contract: this always answers 202 once
     * the alert is raised, because a control-band watcher blocking its next
     * sample on this call's success is a worse failure mode than a dropped
     * alert would be.
     */
    @PostMapping("/control-band-escalations")
    @RequiresCapability(value = Capability.CONTROL_PLANE_ALERT_RAISE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Raise a control-band tier escalation",
            description = "Requires platform-admin. Called by ops/control_band_watch.py, never by a tenant.")
    ResponseEntity<Void> raiseControlBandEscalation(@Valid @RequestBody ControlBandEscalationRequest request) {
        Instant now = clock.instant();
        controlPlaneAlerts.raise(new ControlPlaneAlert(
                CONTROL_BAND_ESCALATED, SUBJECT_TYPE, request.metricId(), variablesFor(request), now));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * The entire variable set this alert ever renders with — the metric's own
     * id, description and numbers, nothing that could name a person. Every
     * field here traces to {@code ops/bands.yaml}, a platform-owned
     * configuration file, never to request data a caller supplied about a
     * person. Package-visible so a classification test asserts that directly.
     */
    static Map<String, String> variablesFor(ControlBandEscalationRequest request) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("metricId", request.metricId());
        variables.put("description", request.description());
        variables.put("value", request.value());
        variables.put("unit", request.unit());
        variables.put("tier", String.valueOf(request.tier()));
        variables.put("reason", request.reason());
        return variables;
    }

    record ControlBandEscalationRequest(
            @NotBlank @Size(max = 128) String metricId,
            @NotBlank @Size(max = 500) String description,
            @NotBlank @Size(max = 64) String value,
            @NotBlank @Size(max = 32) String unit,
            @NotNull Integer tier,
            @NotBlank @Size(max = 500) String reason) {}
}
