package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.CommentPresetService;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.PresetRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Preset product comments (row 2.1b) — the tenant-wide coded
 * kitchen-instruction vocabulary itself. Which of a product's lines may
 * carry which preset is {@link ProductCommentPresetController}'s own,
 * brand-scoped concern.
 *
 * <p>{@code TENANT} scope (the default {@link RequiresCapability} applies):
 * a preset is a tenant-level object shared by every brand, the same reason
 * {@code SalesChannelController} sits at tenant scope rather than brand
 * scope. Mounted under {@code control-plane} alongside every other catalog
 * authoring controller — see {@code catalog-paths.ts}'s own doc comment in
 * the operations console for why that prefix, despite its name, is exactly
 * where the console's own catalog screens already call.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/comment-presets")
@Tag(name = "Preset product comments", description = "The tenant-wide coded kitchen-instruction vocabulary")
public class CommentPresetController {

    private final CommentPresetService presets;
    private final CurrentActor currentActor;

    public CommentPresetController(CommentPresetService presets, CurrentActor currentActor) {
        this.presets = presets;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(Capability.CATALOG_READ)
    @Operation(summary = "The tenant's preset product comments, every status")
    public ResponseEntity<List<PresetResponse>> list(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                presets.list(tenantId).stream().map(PresetResponse::of).toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, mutating = true)
    @Operation(
            summary = "Register a preset product comment",
            description = "Refused (409) when the code is already registered for this tenant. "
                    + "pos_modifier_code is the coded value a POS export maps this preset to where "
                    + "it expects a modifier; left null, the preset still renders on the KDS and "
                    + "exports nowhere.")
    public ResponseEntity<PresetResponse> create(
            @PathVariable UUID tenantId, @Valid @RequestBody NewPresetRequest body) {
        PresetRow created = presets.create(
                tenantId,
                new CommentPresetService.NewPreset(
                        body.code(),
                        body.labelRu(),
                        body.labelUz(),
                        body.labelEn(),
                        body.posModifierCode(),
                        body.sortOrder() == null ? 0 : body.sortOrder()),
                currentActor.get().subject());
        return ResponseEntity.ok(PresetResponse.of(created));
    }

    @PutMapping("/{presetId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, mutating = true)
    @Operation(
            summary = "Correct a preset's labels, POS mapping, order, or archive it",
            description = "Whole-record PUT with an expected version, the same discipline every "
                    + "other versioned reference table in this platform uses. Archiving is "
                    + "status=ARCHIVED here, not a DELETE: a preset already selected on an open "
                    + "order's line must stay resolvable.")
    public ResponseEntity<PresetResponse> update(
            @PathVariable UUID tenantId, @PathVariable UUID presetId, @Valid @RequestBody UpdatePresetRequest body) {
        PresetRow updated = presets.update(
                tenantId,
                presetId,
                new CommentPresetService.PresetEdit(
                        body.labelRu(),
                        body.labelUz(),
                        body.labelEn(),
                        body.posModifierCode(),
                        body.sortOrder(),
                        body.status(),
                        body.expectedVersion()),
                currentActor.get().subject());
        return ResponseEntity.ok(PresetResponse.of(updated));
    }

    record NewPresetRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
            String code,

            @NotBlank @Size(max = 120) String labelRu,
            @NotBlank @Size(max = 120) String labelUz,
            @NotBlank @Size(max = 120) String labelEn,
            @Size(max = 64) String posModifierCode,
            Integer sortOrder) {}

    record UpdatePresetRequest(
            @NotBlank @Size(max = 120) String labelRu,
            @NotBlank @Size(max = 120) String labelUz,
            @NotBlank @Size(max = 120) String labelEn,
            @Size(max = 64) String posModifierCode,
            @Min(0) int sortOrder,
            @NotBlank @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @NotNull Integer expectedVersion) {}

    record PresetResponse(
            UUID presetId,
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status,
            int version) {

        static PresetResponse of(PresetRow row) {
            return new PresetResponse(
                    row.id(),
                    row.code(),
                    row.labelRu(),
                    row.labelUz(),
                    row.labelEn(),
                    row.posModifierCode(),
                    row.sortOrder(),
                    row.status(),
                    row.version());
        }
    }
}
