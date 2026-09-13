package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
import uz.horecaos.platform.customers.application.CustomerImportService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerImportStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The tenant-scoped, asynchronous customer CSV import (row {@code X.13}/
 * {@code 5.1b}) -- {@code q-import-wizard}'s first backend, and the surface
 * a progress bar finally has something to poll against.
 *
 * <p>Unlike {@code SendPulseContactImportController}, which answers a dry
 * run or a real run synchronously in the same request, {@link #submit} only
 * queues the run and returns immediately (202): the file is parsed once, up
 * front, to know {@code rowsTotal}, then handed to {@code
 * CustomerImportRunWorker} to work asynchronously. {@link #status} and
 * {@link #rows} are what the wizard's JobProgress and ResultSummary poll and
 * read.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/customers/imports")
@Tag(name = "Customer CSV import", description = "Row X.13/5.1b: a generic, asynchronous customer CSV import")
public class CustomerImportController {

    private final CustomerImportService imports;
    private final CurrentActor currentActor;
    private final AuditRecorder audit;
    private final Clock clock;

    public CustomerImportController(
            CustomerImportService imports, CurrentActor currentActor, AuditRecorder audit, Clock clock) {
        this.imports = imports;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping
    @RequiresCapability(value = Capability.CUSTOMER_IMPORT, mutating = true)
    @Operation(
            summary = "Queue a customer CSV import",
            description = "Parses the file to learn its row count and queues the run; returns "
                    + "immediately with a runId to poll. dryRun defaults true -- a caller must "
                    + "deliberately pass dryRun=false to write anything, the same default "
                    + "SendPulseContactImportController and PosSyncRunController use for the "
                    + "same reason.")
    public ResponseEntity<CustomerImportSubmitResponse> submit(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody CustomerImportSubmitRequest request) {

        String subject = currentActor.get().subject();
        UUID runId =
                imports.submit(tenantId, request.brandId(), dryRun, request.fileName(), request.content(), subject);
        JdbcCustomerImportStore.RunRow run = imports.status(tenantId, runId);

        // ADR 0027: who queued the import and how many rows it will attempt,
        // written at submission time rather than at completion so a run that
        // never finishes still leaves an answerable trail.
        audit.record(AuditFact.of("customer.csv_import_queued", AuditClass.SECURITY)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("CustomerImportRun", runId)
                .because("Customer CSV import (" + (dryRun ? "dry run" : "real run") + ")")
                .changed(Map.of(
                        "sourceFileName", request.fileName(),
                        "dryRun", Boolean.toString(dryRun),
                        "rowsTotal", Integer.toString(run.rowsTotal())))
                .usingCapability(Capability.CUSTOMER_IMPORT.code())
                .correlatedBy(runId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CustomerImportSubmitResponse(runId, run.status(), run.rowsTotal()));
    }

    @GetMapping("/{runId}")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "One import run's status and progress",
            description = "What q-import-wizard's JobProgress polls: status, rowsTotal, "
                    + "rowsProcessed and the running per-outcome counts.")
    public ResponseEntity<CustomerImportStatusResponse> status(@PathVariable UUID tenantId, @PathVariable UUID runId) {
        return ResponseEntity.ok(CustomerImportStatusResponse.of(imports.status(tenantId, runId)));
    }

    @GetMapping("/{runId}/rows")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "One import run's per-row report",
            description = "The dry-run diff and the real result summary, in the identical shape "
                    + "either way -- what q-import-wizard's ResultSummary renders. No phone "
                    + "number and no display name here (ADR 0029): a row names the account it "
                    + "created or matched, never the source data that produced it.")
    public ResponseEntity<List<CustomerImportRowResponse>> rows(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(imports.rows(tenantId, runId, limit, offset).stream()
                .map(CustomerImportRowResponse::of)
                .toList());
    }

    public record CustomerImportSubmitRequest(
            @NotNull UUID brandId,
            @NotBlank String fileName,
            @NotBlank String content) {}

    public record CustomerImportSubmitResponse(UUID runId, String status, int rowsTotal) {}

    public record CustomerImportStatusResponse(
            UUID runId,
            String status,
            boolean dryRun,
            String sourceFileName,
            int rowsTotal,
            int rowsProcessed,
            int rowsCreatedCustomer,
            int rowsMatchedCustomer,
            int rowsRejected,
            @Nullable String failureReason) {
        static CustomerImportStatusResponse of(JdbcCustomerImportStore.RunRow run) {
            return new CustomerImportStatusResponse(
                    run.id(),
                    run.status(),
                    run.dryRun(),
                    run.sourceFileName(),
                    run.rowsTotal(),
                    run.rowsProcessed(),
                    run.rowsCreatedCustomer(),
                    run.rowsMatchedCustomer(),
                    run.rowsRejected(),
                    run.failureReason());
        }
    }

    public record CustomerImportRowResponse(
            int rowNumber,
            String outcome,
            @Nullable UUID customerAccountId,
            @Nullable String rejectReason) {
        static CustomerImportRowResponse of(JdbcCustomerImportStore.ImportRowView row) {
            return new CustomerImportRowResponse(
                    row.rowNumber(), row.outcome(), row.customerAccountId(), row.rejectReason());
        }
    }
}
