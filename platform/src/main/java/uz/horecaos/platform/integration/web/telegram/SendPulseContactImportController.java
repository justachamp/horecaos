package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
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
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.integration.provider.telegram.sendpulse.SendPulseContactImportService;
import uz.horecaos.platform.integration.provider.telegram.sendpulse.SendPulseImportFormat;
import uz.horecaos.platform.integration.provider.telegram.sendpulse.SendPulseImportReport;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The control-plane surface for the SendPulse contact-export import (ADR
 * 0059 stage 3, {@code docs/runbooks/sendpulse-cutover.md}).
 *
 * <p>{@code dryRun} defaults true — the ADR's own "REQUIRED dry-run mode": a
 * caller must deliberately pass {@code dryRun=false} to write anything, the
 * same default {@code PosSyncRunController#start} already uses for the same
 * reason. Both modes return the identical report shape; only what happened
 * behind {@code customerAccountId} differs.
 *
 * <p>The file body is a JSON field ({@code content}) rather than a
 * multipart upload — this codebase has no multipart endpoint anywhere to
 * follow as precedent, and a JSON body keeps this endpoint inside the same
 * ADR 0031 convention (Problem Details, {@code Idempotency-Key}) every other
 * control-plane write already uses rather than adding a second request
 * shape for one endpoint.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/sendpulse-imports")
@Tag(name = "SendPulse contact import", description = "ADR 0059 stage 3: the SendPulse exit's contact export")
public class SendPulseContactImportController {

    private final SendPulseContactImportService imports;
    private final CurrentActor currentActor;
    private final AuditRecorder audit;
    private final Clock clock;

    public SendPulseContactImportController(
            SendPulseContactImportService imports, CurrentActor currentActor, AuditRecorder audit, Clock clock) {
        this.imports = imports;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping
    @RequiresCapability(value = Capability.CUSTOMER_IMPORT, mutating = true)
    @Operation(
            summary = "Import a SendPulse contact export",
            description = "Parses the export and reports, per row, what it would do (or, when dryRun=false, "
                    + "what it did): a matched or newly created customer account, a bound Telegram chat, "
                    + "an imported consent decision — or a rejection with a reason. Idempotent: importing "
                    + "the same file twice writes nothing new for a row already imported.")
    public ResponseEntity<SendPulseImportReport> importContacts(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody ImportRequest request) {

        String subject = currentActor.get().subject();
        SendPulseImportReport report = imports.run(
                tenantId,
                request.installationId(),
                request.format(),
                request.content(),
                request.fileName(),
                dryRun,
                subject);

        // ADR 0027: "who imported, counts, source" — the import run's own
        // audit fact, whether or not this run wrote anything. A dry run is
        // still a decision an operator made and is still worth an answerable
        // trail of who ran it and what it reported.
        audit.record(AuditFact.of("integration.sendpulse_import_run", AuditClass.SECURITY)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("SendPulseImportRun", report.runId())
                .because("SendPulse contact export import (" + (dryRun ? "dry run" : "real run") + ")")
                .changed(Map.of(
                        "sourceFileName", request.fileName(),
                        "dryRun", Boolean.toString(dryRun),
                        "rowsTotal", Integer.toString(report.counts().total()),
                        "rowsCreatedCustomer", Integer.toString(report.counts().createdCustomer()),
                        "rowsMatchedCustomer", Integer.toString(report.counts().matchedCustomer()),
                        "rowsSkippedAlreadyLinked",
                                Integer.toString(report.counts().skippedAlreadyLinked()),
                        "rowsRejected", Integer.toString(report.counts().rejected())))
                .usingCapability(Capability.CUSTOMER_IMPORT.code())
                .correlatedBy(report.runId().toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(report);
    }

    @GetMapping("/{runId}")
    @RequiresCapability(Capability.CUSTOMER_IMPORT)
    @Operation(
            summary = "Re-read a past import run's report",
            description = "The same report the run's own POST response returned, re-readable afterward "
                    + "without re-parsing the file.")
    public ResponseEntity<SendPulseImportReport> report(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(imports.report(tenantId, runId, limit, offset));
    }

    public record ImportRequest(
            @NotNull UUID installationId,
            @NotNull SendPulseImportFormat format,
            @NotBlank String fileName,
            @NotBlank String content) {}
}
