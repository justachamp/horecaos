package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.CatalogImportService;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogImportStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The brand-scoped, asynchronous catalog CSV/Excel import and export (row
 * 4.5b) — modelled directly on {@code CustomerImportController}: {@link
 * #submit} only queues the run and returns immediately (202), the file is
 * parsed once, up front, to know {@code rowsTotal}, then handed to {@code
 * CatalogImportRunWorker} to work asynchronously. {@link #status} and {@link
 * #rows} are what the operations console's {@code q-import-wizard} instance
 * polls and reads.
 *
 * <p>Same {@code CATALOG_AUTHOR}/{@code CATALOG_READ} capabilities {@code
 * CatalogAuthoringController} already declares for this brand's draft catalog
 * — reused rather than a new {@code CATALOG_IMPORT} capability, per the
 * brief's own "capability-gated (CATALOG_WRITE-like — reuse existing)".
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog")
@Tag(
        name = "Catalog import",
        description = "Row 4.5b: a brand's catalog CSV/Excel import, dry run and apply, and export")
public class CatalogImportController {

    private final CatalogImportService imports;
    private final CurrentActor currentActor;

    public CatalogImportController(CatalogImportService imports, CurrentActor currentActor) {
        this.imports = imports;
        this.currentActor = currentActor;
    }

    @GetMapping(path = "/imports/template", produces = "text/csv")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The empty import template",
            description = "Header row only, UTF-8 -- the exact column order export fills and import reads.")
    public ResponseEntity<String> template(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return csvResponse("catalog-import-template.csv", imports.template());
    }

    @GetMapping(path = "/export", produces = "text/csv")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's catalog, filled into the import template",
            description = "Products, variants, categories and this import's own prices -- an operator's "
                    + "starting point for a correction, not a full price-book export. See "
                    + "CatalogImportService#export's own doc for the price scope limitation.")
    public ResponseEntity<String> export(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @RequestParam UUID catalogId) {
        String filename = "catalog-export-%s.csv".formatted(catalogId);
        return csvResponse(filename, imports.export(tenantId, brandId, catalogId));
    }

    @PostMapping("/imports")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Queue a catalog CSV/Excel import",
            description = "Parses the file to learn its row count and queues the run; returns immediately "
                    + "with a runId to poll. dryRun defaults true -- a caller must deliberately pass "
                    + "dryRun=false to write anything, the same default CustomerImportController and "
                    + "PosSyncRunController use for the same reason.")
    public ResponseEntity<CatalogImportSubmitResponse> submit(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody CatalogImportSubmitRequest request) {

        String subject = currentActor.get().subject();
        UUID runId = imports.submit(
                tenantId, brandId, request.catalogId(), dryRun, request.fileName(), request.content(), subject);
        JdbcCatalogImportStore.RunRow run = imports.status(tenantId, runId);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CatalogImportSubmitResponse(runId, run.status(), run.rowsTotal()));
    }

    @GetMapping("/imports")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "This brand's import run history, newest first")
    public ResponseEntity<List<CatalogImportStatusResponse>> history(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(imports.history(tenantId, brandId, limit).stream()
                .map(CatalogImportStatusResponse::of)
                .toList());
    }

    @GetMapping("/imports/{runId}")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One import run's status and progress",
            description = "status, rowsTotal, rowsProcessed and the running per-outcome counts.")
    public ResponseEntity<CatalogImportStatusResponse> status(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID runId) {
        return ResponseEntity.ok(CatalogImportStatusResponse.of(imports.status(tenantId, runId)));
    }

    @GetMapping("/imports/{runId}/rows")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One import run's per-row report",
            description = "The dry-run diff and the real result summary, in the identical shape either way.")
    public ResponseEntity<List<CatalogImportRowResponse>> rows(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(imports.rows(tenantId, runId, limit, offset).stream()
                .map(CatalogImportRowResponse::of)
                .toList());
    }

    private static ResponseEntity<String> csvResponse(String filename, String content) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(content);
    }

    public record CatalogImportSubmitRequest(
            @NotNull UUID catalogId,
            @NotBlank String fileName,
            @NotBlank String content) {}

    public record CatalogImportSubmitResponse(UUID runId, String status, int rowsTotal) {}

    public record CatalogImportStatusResponse(
            UUID runId,
            String status,
            boolean dryRun,
            UUID catalogId,
            String sourceFileName,
            int rowsTotal,
            int rowsProcessed,
            int rowsCreated,
            int rowsUpdated,
            int rowsSkipped,
            int rowsError,
            @Nullable String failureReason) {
        static CatalogImportStatusResponse of(JdbcCatalogImportStore.RunRow run) {
            return new CatalogImportStatusResponse(
                    run.id(),
                    run.status(),
                    run.dryRun(),
                    run.catalogId(),
                    run.sourceFileName(),
                    run.rowsTotal(),
                    run.rowsProcessed(),
                    run.rowsCreated(),
                    run.rowsUpdated(),
                    run.rowsSkipped(),
                    run.rowsError(),
                    run.failureReason());
        }
    }

    public record CatalogImportRowResponse(
            int rowNumber,
            String outcome,
            @Nullable UUID productId,
            @Nullable UUID variantId,
            @Nullable String errorReason) {
        static CatalogImportRowResponse of(JdbcCatalogImportStore.ImportRowView row) {
            return new CatalogImportRowResponse(
                    row.rowNumber(), row.outcome(), row.productId(), row.variantId(), row.errorReason());
        }
    }
}
