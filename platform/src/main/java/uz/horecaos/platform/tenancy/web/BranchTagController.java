package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.BranchTagService;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBranchTagStore.Assignment;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBranchTagStore.BranchTag;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * 10.10d: a chain's own branch tag registry and which branch carries which
 * tag — no table, no endpoint, no screen before this wave.
 *
 * <p>The registry (list, create, archive, and the tenant-wide assignment
 * read the settings screen's filter-and-group grid needs) is {@code TENANT}
 * scope; setting one branch's own tags is {@code LOCATION} scope, the same
 * split {@code LocationServiceOperationsController}'s own Javadoc draws — a
 * branch manager may re-tag their own branch without holding authority over
 * the tenant-wide list a tag is drawn from. Both directions reuse {@link
 * Capability#LOCATION_READ}/{@link Capability#LOCATION_WRITE} rather than a
 * new capability: a tag is exactly the "rearranging a branch" power {@link
 * Capability#LOCATION_WRITE}'s own doc already names, on the tenant-wide
 * object instead of the floor plan.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Branch tags", description = "A chain's own tags for its branches (24/7, has parking, airport)")
public class BranchTagController {

    private final BranchTagService tags;
    private final CurrentActor currentActor;

    public BranchTagController(BranchTagService tags, CurrentActor currentActor) {
        this.tags = tags;
        this.currentActor = currentActor;
    }

    @GetMapping("/branch-tags")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.TENANT)
    @Operation(summary = "The tenant's own branch tag registry")
    public ResponseEntity<List<TagResponse>> list(
            @PathVariable UUID tenantId, @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(
                tags.list(tenantId, activeOnly).stream().map(TagResponse::of).toList());
    }

    @GetMapping("/branch-tags/assignments")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every branch's tags, tenant-wide",
            description =
                    "The filter-and-group read: which branches carry a given tag, without " + "one request per branch.")
    public ResponseEntity<List<AssignmentResponse>> assignments(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                tags.assignments(tenantId).stream().map(AssignmentResponse::of).toList());
    }

    @PostMapping("/branch-tags")
    @RequiresCapability(value = Capability.LOCATION_WRITE, scope = ScopeType.TENANT, mutating = true)
    @Operation(summary = "Register a branch tag")
    public ResponseEntity<TagCreatedResponse> create(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateTagRequest body) {
        UUID id = tags.create(tenantId, body.code(), body.displayName(), actor(), body.reason());
        return ResponseEntity.ok(new TagCreatedResponse(id));
    }

    @PostMapping("/branch-tags/{tagId}/archive")
    @RequiresCapability(value = Capability.LOCATION_WRITE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Retire a branch tag",
            description = "Archived, never deleted: a branch already carrying this tag must keep "
                    + "resolving it, and the code is freed for reuse.")
    public ResponseEntity<Void> archive(
            @PathVariable UUID tenantId, @PathVariable UUID tagId, @Valid @RequestBody ArchiveTagRequest body) {
        tags.archive(tenantId, tagId, actor(), body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/brands/{brandId}/locations/{locationId}/branch-tags")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "One branch's own tags")
    public ResponseEntity<List<UUID>> tagsOfLocation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(tags.tagsOf(tenantId, locationId));
    }

    @PutMapping("/brands/{brandId}/locations/{locationId}/branch-tags")
    @RequiresCapability(value = Capability.LOCATION_WRITE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Replace one branch's tags",
            description = "Sets the whole tag set for this branch at once, rather than an "
                    + "assign/unassign pair — the screen's own checkbox grid already knows the "
                    + "full set it wants.")
    public ResponseEntity<Void> setTagsOfLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody SetLocationTagsRequest body) {
        tags.setTagsForLocation(tenantId, brandId, locationId, body.tagIds(), actor(), body.reason());
        return ResponseEntity.noContent().build();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    public record TagResponse(UUID tagId, String code, String displayName, String status) {
        static TagResponse of(BranchTag tag) {
            return new TagResponse(tag.id(), tag.code(), tag.displayName(), tag.status());
        }
    }

    public record AssignmentResponse(UUID locationId, UUID tagId, Instant assignedAt) {
        static AssignmentResponse of(Assignment assignment) {
            return new AssignmentResponse(assignment.locationId(), assignment.tagId(), assignment.assignedAt());
        }
    }

    public record TagCreatedResponse(UUID tagId) {}

    public record CreateTagRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,31}$")
            String code,

            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Size(max = 1000) String reason) {}

    public record ArchiveTagRequest(
            @NotBlank @Size(max = 1000) String reason) {}

    public record SetLocationTagsRequest(
            @NotNull List<UUID> tagIds,
            @NotBlank @Size(max = 1000) String reason) {}
}
