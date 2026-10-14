package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.application.TenantJob;
import uz.horecaos.platform.iam.application.TenantRoleService;
import uz.horecaos.platform.iam.application.TenantRoleService.CatalogueEntry;
import uz.horecaos.platform.iam.application.TenantRoleService.Draft;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A company defines, edits and closes its own jobs, and reads the vocabulary it builds them
 * from (ADR 0186, gap-map row 9.1b).
 *
 * <p>Sits beside {@link GrantController}'s {@code roles} list on the same path prefix, because
 * the console already reads its jobs from there. Reading the vocabulary needs {@code
 * iam.grant.manage} (whoever may give jobs may see what is in them); writing needs the new
 * {@code iam.role.manage}, held by the owner and the administrator. The service checks the
 * capability again and, for every capability a job is to carry, that the author holds it.
 *
 * <p>An edit and a close are aggregate writes under {@code If-Match} (ADR 0031), a create and
 * every other effect under {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}")
@Tag(name = "Tenant jobs", description = "ADR 0186: jobs a company defines from the capability catalogue")
public class TenantRoleController {

    private final TenantRoleService jobs;
    private final CurrentActor currentActor;

    public TenantRoleController(TenantRoleService jobs, CurrentActor currentActor) {
        this.jobs = jobs;
        this.currentActor = currentActor;
    }

    @GetMapping("/capabilities")
    @RequiresCapability(Capability.IAM_GRANT_MANAGE)
    @Operation(
            summary = "The capability vocabulary a company's jobs are built from",
            description = "Every capability a code-owned job carries, grouped by area through its "
                    + "resource type, each marked held (the caller has it company-wide) and "
                    + "composable (a job a company defines may carry it). The platform-admin-only "
                    + "registry at /control-plane/capabilities is the whole code-owned list; this "
                    + "is the part a restaurant may see and use.")
    public List<CapabilityEntryResponse> capabilities(@PathVariable UUID tenantId) {
        return jobs.catalogue(tenantId, subject()).stream()
                .map(CapabilityEntryResponse::of)
                .toList();
    }

    @PostMapping("/roles")
    @RequiresCapability(value = Capability.IAM_ROLE_MANAGE, mutating = true)
    @Operation(
            summary = "Define a job",
            description = "A name, the level it is normally given at, and capability codes the author "
                    + "holds company-wide and the deny-list allows. The code is generated. At most "
                    + "twenty active jobs per company; a name is unique among the active ones.")
    public ResponseEntity<TenantJobResponse> create(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateJobRequest request) {
        TenantJob created = jobs.create(
                tenantId,
                new Draft(request.name(), request.scopeType(), request.capabilities(), request.copiedFrom()),
                request.reason(),
                subject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.version()))
                .body(TenantJobResponse.of(created));
    }

    @PutMapping("/roles/{roleCode}")
    @RequiresCapability(value = Capability.IAM_ROLE_MANAGE, mutating = true)
    @Operation(
            summary = "Change a job",
            description = "Replaces the name and the capability set. The version the editor was opened "
                    + "at is If-Match. Takes effect for every holder at their next request. The level "
                    + "does not change; copy the job to move it.")
    public ResponseEntity<TenantJobResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable String roleCode,
            @Valid @RequestBody UpdateJobRequest request,
            HttpServletRequest http) {
        long expected = AggregateVersion.requireIfMatch(http);
        TenantJob updated = jobs.update(
                tenantId,
                roleCode,
                new Draft(request.name(), null, request.capabilities(), null),
                expected,
                request.reason(),
                subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.version()))
                .body(TenantJobResponse.of(updated));
    }

    @DeleteMapping("/roles/{roleCode}")
    @RequiresCapability(value = Capability.IAM_ROLE_MANAGE, mutating = true)
    @Operation(
            summary = "Close a job",
            description = "Refused 409 while anyone still holds it. A closed job is kept for the history "
                    + "and never deleted; its name is free again.")
    public ResponseEntity<TenantJobResponse> retire(
            @PathVariable UUID tenantId,
            @PathVariable String roleCode,
            @Valid @RequestBody RetireJobRequest request,
            HttpServletRequest http) {
        long expected = AggregateVersion.requireIfMatch(http);
        TenantJob retired = jobs.retire(tenantId, roleCode, expected, request.reason(), subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(retired.version()))
                .body(TenantJobResponse.of(retired));
    }

    private String subject() {
        return currentActor.get().subject();
    }

    /**
     * @param copiedFrom the code of the job the editor started from, for the audit trail only
     * @param capabilities capability codes; the service checks each against the deny-list and the author's holdings
     */
    public record CreateJobRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull ScopeType scopeType,
            @NotNull @Size(min = 1, max = 600) List<@NotBlank @Size(max = 128) String> capabilities,
            @Nullable @Size(max = 64) String copiedFrom,
            @NotBlank @Size(max = 1000) String reason) {}

    public record UpdateJobRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Size(min = 1, max = 600) List<@NotBlank @Size(max = 128) String> capabilities,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RetireJobRequest(
            @NotBlank @Size(max = 1000) String reason) {}

    /** One capability of the vocabulary. {@code area} is the resource type; the console names it in the reader's language. */
    public record CapabilityEntryResponse(String code, String area, String action, boolean held, boolean composable) {

        static CapabilityEntryResponse of(CatalogueEntry entry) {
            Capability capability = entry.capability();
            return new CapabilityEntryResponse(
                    capability.code(),
                    capability.resourceType(),
                    capability.action(),
                    entry.held(),
                    entry.composable());
        }
    }

    /** A job a company defined, as the editor and the library read it. */
    public record TenantJobResponse(
            String code,
            String name,
            ScopeType scopeType,
            List<String> capabilities,
            long version,
            String status,
            @Nullable String copiedFrom,
            int holders,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt,
            @Nullable Instant retiredAt) {

        static TenantJobResponse of(TenantJob job) {
            return new TenantJobResponse(
                    job.code(),
                    job.name(),
                    job.scopeType(),
                    job.capabilities(),
                    job.version(),
                    job.status(),
                    job.copiedFrom(),
                    job.holders(),
                    job.createdAt(),
                    job.updatedAt(),
                    job.retiredAt());
        }
    }
}
