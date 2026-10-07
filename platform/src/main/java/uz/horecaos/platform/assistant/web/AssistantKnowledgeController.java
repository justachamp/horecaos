package uz.horecaos.platform.assistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.assistant.application.KnowledgeService;
import uz.horecaos.platform.assistant.application.KnowledgeService.EntryView;
import uz.horecaos.platform.assistant.application.KnowledgeService.VersionView;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Where the operations team writes down what the assistant may tell customers
 * (ADR 0069: "tenant knowledge is authored by the operations team, versioned,
 * and scoped").
 *
 * <p>Two route families over one service: entries that apply to the whole
 * tenant, written at tenant scope, and entries for one brand or one of its
 * locations, written at brand scope. The path decides the scope, so the
 * capability check ({@code assistant.knowledge.manage}, ADR 0025) and the entry
 * can never disagree about it, and an entry is "not found" from any path that is
 * not its own.
 *
 * <p>An entry is never edited. {@code POST .../versions} appends the next
 * version, guarded by {@code If-Match} on the version the author last read (ADR
 * 0031); {@code POST .../retirements} appends the version that ends it. The
 * words a customer was once told stay readable in {@code GET .../versions}.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Assistant knowledge", description = "The tenant's own answers the grounded assistant may give")
public class AssistantKnowledgeController {

    private final KnowledgeService knowledge;
    private final CurrentActor currentActor;

    public AssistantKnowledgeController(KnowledgeService knowledge, CurrentActor currentActor) {
        this.knowledge = knowledge;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------ tenant scope

    @GetMapping("/assistant/knowledge")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant-wide knowledge entries",
            description = "Entries that apply to every brand and branch of the tenant, each at its current "
                    + "version, newest first. Retired entries are listed, marked RETIRED.")
    List<EntryResponse> listTenant(@PathVariable UUID tenantId) {
        return knowledge.list(tenantId, null).stream().map(EntryResponse::of).toList();
    }

    @PostMapping("/assistant/knowledge")
    @RequiresCapability(value = Capability.ASSISTANT_KNOWLEDGE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Create a tenant-wide knowledge entry",
            description = "Creates the entry and publishes its first version. The scope and the language are "
                    + "the entry's identity and never change; to change either, retire it and create another.")
    ResponseEntity<EntryResponse> createTenant(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateEntryRequest body) {
        EntryView created = knowledge.create(
                tenantId,
                null,
                null,
                body.locale(),
                body.questionForm(),
                body.answerBody(),
                currentActor.get().subject(),
                body.reason());
        return created(created);
    }

    @GetMapping("/assistant/knowledge/{entryId}")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.TENANT)
    @Operation(summary = "One tenant-wide entry at its current version")
    ResponseEntity<EntryResponse> getTenant(@PathVariable UUID tenantId, @PathVariable UUID entryId) {
        return entry(knowledge.get(tenantId, null, entryId));
    }

    @GetMapping("/assistant/knowledge/{entryId}/versions")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every version a tenant-wide entry has had",
            description = "Newest first, with who published each and why. The words an earlier version said "
                    + "are never altered.")
    List<VersionResponse> versionsTenant(@PathVariable UUID tenantId, @PathVariable UUID entryId) {
        return knowledge.versions(tenantId, null, entryId).stream()
                .map(VersionResponse::of)
                .toList();
    }

    @PostMapping("/assistant/knowledge/{entryId}/versions")
    @RequiresCapability(value = Capability.ASSISTANT_KNOWLEDGE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Publish the next version of a tenant-wide entry",
            description = "Never an edit: appends version n+1. Requires If-Match carrying the version last "
                    + "read; a stale version is refused with the current one.")
    ResponseEntity<EntryResponse> publishTenant(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            @Valid @RequestBody PublishVersionRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return entry(knowledge.publishNextVersion(
                tenantId,
                null,
                entryId,
                expected,
                false,
                body.questionForm(),
                body.answerBody(),
                currentActor.get().subject(),
                body.reason()));
    }

    @PostMapping("/assistant/knowledge/{entryId}/retirements")
    @RequiresCapability(value = Capability.ASSISTANT_KNOWLEDGE_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Retire a tenant-wide entry",
            description = "Appends a RETIRED version; the assistant stops retrieving the entry from this "
                    + "moment. Requires If-Match.")
    ResponseEntity<EntryResponse> retireTenant(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            @Valid @RequestBody RetireRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return entry(knowledge.retire(
                tenantId, null, entryId, expected, currentActor.get().subject(), body.reason()));
    }

    // ------------------------------------------------------------- brand scope

    @GetMapping("/brands/{brandId}/assistant/knowledge")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "A brand's knowledge entries",
            description = "The brand's own entries and those of its locations, each at its current version, "
                    + "newest first.")
    List<EntryResponse> listBrand(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return knowledge.list(tenantId, brandId).stream().map(EntryResponse::of).toList();
    }

    @PostMapping("/brands/{brandId}/assistant/knowledge")
    @RequiresCapability(value = Capability.ASSISTANT_KNOWLEDGE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Create a brand or location knowledge entry",
            description = "With no locationId the entry applies to the brand; with one, only to that branch. "
                    + "Creates the entry and publishes its first version.")
    ResponseEntity<EntryResponse> createBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateBrandEntryRequest body) {
        EntryView created = knowledge.create(
                tenantId,
                brandId,
                body.locationId(),
                body.locale(),
                body.questionForm(),
                body.answerBody(),
                currentActor.get().subject(),
                body.reason());
        return created(created);
    }

    @GetMapping("/brands/{brandId}/assistant/knowledge/{entryId}")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One brand or location entry at its current version")
    ResponseEntity<EntryResponse> getBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID entryId) {
        return entry(knowledge.get(tenantId, brandId, entryId));
    }

    @GetMapping("/brands/{brandId}/assistant/knowledge/{entryId}/versions")
    @RequiresCapability(value = Capability.ASSISTANT_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every version a brand or location entry has had")
    List<VersionResponse> versionsBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID entryId) {
        return knowledge.versions(tenantId, brandId, entryId).stream()
                .map(VersionResponse::of)
                .toList();
    }

    @PostMapping("/brands/{brandId}/assistant/knowledge/{entryId}/versions")
    @RequiresCapability(value = Capability.ASSISTANT_KNOWLEDGE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Publish the next version of a brand or location entry",
            description = "Never an edit: appends version n+1. Requires If-Match.")
    ResponseEntity<EntryResponse> publishBrand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID entryId,
            @Valid @RequestBody PublishVersionRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return entry(knowledge.publishNextVersion(
                tenantId,
                brandId,
                entryId,
                expected,
                false,
                body.questionForm(),
                body.answerBody(),
                currentActor.get().subject(),
                body.reason()));
    }

    @PostMapping("/brands/{brandId}/assistant/knowledge/{entryId}/retirements")
    @RequiresCapability(value = Capability.ASSISTANT_KNOWLEDGE_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Retire a brand or location entry",
            description = "Appends a RETIRED version. Requires If-Match.")
    ResponseEntity<EntryResponse> retireBrand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID entryId,
            @Valid @RequestBody RetireRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        return entry(knowledge.retire(
                tenantId, brandId, entryId, expected, currentActor.get().subject(), body.reason()));
    }

    // ----------------------------------------------------------------- shapes

    private static ResponseEntity<EntryResponse> entry(EntryView view) {
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(view.version())).body(EntryResponse.of(view));
    }

    private static ResponseEntity<EntryResponse> created(EntryView view) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(view.version()))
                .body(EntryResponse.of(view));
    }

    /**
     * @param locale {@code ru}, {@code uz} or {@code en}
     * @param questionForm how a customer would ask it: matched against the customer's words
     * @param answerBody what the assistant may say; the platform's own facts are never quoted from it
     * @param reason why this is being published (ADR 0027)
     */
    public record CreateEntryRequest(
            @NotBlank @Size(max = 8) String locale,
            @NotBlank @Size(min = 3, max = 300) String questionForm,
            @NotBlank @Size(max = 2000) String answerBody,
            @NotBlank @Size(max = 1000) String reason) {}

    /** {@code locationId} is optional: absent means the whole brand. */
    public record CreateBrandEntryRequest(
            @Nullable UUID locationId,
            @NotBlank @Size(max = 8) String locale,
            @NotBlank @Size(min = 3, max = 300) String questionForm,
            @NotBlank @Size(max = 2000) String answerBody,
            @NotBlank @Size(max = 1000) String reason) {}

    public record PublishVersionRequest(
            @NotBlank @Size(min = 3, max = 300) String questionForm,
            @NotBlank @Size(max = 2000) String answerBody,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RetireRequest(@NotBlank @Size(max = 1000) String reason) {}

    /** An entry at its current version; {@code version} is also the response's ETag. */
    public record EntryResponse(
            UUID id,
            String scope,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String locale,
            int version,
            String status,
            String questionForm,
            String answerBody,
            String authoredBy,
            Instant publishedAt) {

        static EntryResponse of(EntryView view) {
            return new EntryResponse(
                    view.id(),
                    view.scope(),
                    view.brandId(),
                    view.locationId(),
                    view.locale(),
                    view.version(),
                    view.status(),
                    view.questionForm(),
                    view.answerBody(),
                    view.authoredBy(),
                    view.publishedAt());
        }
    }

    public record VersionResponse(
            int version,
            String status,
            String questionForm,
            String answerBody,
            String authoredBy,
            String reason,
            Instant publishedAt) {

        static VersionResponse of(VersionView view) {
            return new VersionResponse(
                    view.version(),
                    view.status(),
                    view.questionForm(),
                    view.answerBody(),
                    view.authoredBy(),
                    view.reason(),
                    view.publishedAt());
        }
    }
}
