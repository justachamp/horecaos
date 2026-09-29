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
import java.util.Map;
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
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
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
        return ResponseEntity.ok(presets.listWithLabels(tenantId).stream()
                .map(PresetResponse::of)
                .toList());
    }

    @GetMapping("/locale-set")
    @RequiresCapability(Capability.CATALOG_READ)
    @Operation(
            summary = "The languages the preset editor offers",
            description = "Row 10.12. A preset is a tenant-level object shared by every brand, so "
                    + "it is edited in the union of the tenant's brands' supported languages, "
                    + "default first; the default is the tenant's first brand's. A brand that "
                    + "has chosen no set contributes the platform triple (ru, uz-Latn, en). "
                    + "The set is a menu, not a constraint: a language outside it that a "
                    + "preset already carries is kept, never deleted, by an edit.")
    public ResponseEntity<LocaleSetResponse> localeSet(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(LocaleSetResponse.of(presets.localeSet(tenantId)));
    }

    @PostMapping
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, mutating = true)
    @Operation(
            summary = "Register a preset product comment",
            description = "Refused (409) when the code is already registered for this tenant. "
                    + "pos_modifier_code is the coded value a POS export maps this preset to where "
                    + "it expects a modifier; left null, the preset still renders on the KDS and "
                    + "exports nowhere. Wording is given per locale in `labels` (row 10.12); the "
                    + "tenant's default language is required, every other locale optional. "
                    + "labelRu/labelUz/labelEn remain accepted for callers that predate `labels`, "
                    + "and `labels` wins where both name a locale.")
    public ResponseEntity<PresetResponse> create(
            @PathVariable UUID tenantId, @Valid @RequestBody NewPresetRequest body) {
        CommentPresetService.PresetView created = presets.createWithLabels(
                tenantId,
                new CommentPresetService.NewPreset(
                        body.code(),
                        body.labelRu(),
                        body.labelUz(),
                        body.labelEn(),
                        body.posModifierCode(),
                        body.sortOrder() == null ? 0 : body.sortOrder(),
                        body.labels() == null ? Map.of() : body.labels()),
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
                    + "order's line must stay resolvable. Wording is written only for the "
                    + "locales the request names, in `labels` (or the legacy labelRu/labelUz/"
                    + "labelEn); a locale left out keeps its wording, so an editor that shows "
                    + "only some languages never deletes the others.")
    public ResponseEntity<PresetResponse> update(
            @PathVariable UUID tenantId, @PathVariable UUID presetId, @Valid @RequestBody UpdatePresetRequest body) {
        CommentPresetService.PresetView updated = presets.updateWithLabels(
                tenantId,
                presetId,
                new CommentPresetService.PresetEdit(
                        body.labelRu(),
                        body.labelUz(),
                        body.labelEn(),
                        body.posModifierCode(),
                        body.sortOrder(),
                        body.status(),
                        body.expectedVersion(),
                        body.labels() == null ? Map.of() : body.labels()),
                currentActor.get().subject());
        return ResponseEntity.ok(PresetResponse.of(updated));
    }

    /**
     * @param labelRu/labelUz/labelEn the platform triple, optional since row 10.12: a
     *                                tenant whose brands do not offer a language need
     *                                not word the preset in it
     * @param labels                  wording by locale, {@code {"ru": "...", "en": "..."}}
     */
    record NewPresetRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,31}$")
            String code,

            @Size(max = 120) String labelRu,
            @Size(max = 120) String labelUz,
            @Size(max = 120) String labelEn,
            @Size(max = 64) String posModifierCode,
            Integer sortOrder,
            @Nullable @Size(max = 32) Map<String, @Size(max = 120) String> labels) {}

    record UpdatePresetRequest(
            @Size(max = 120) String labelRu,
            @Size(max = 120) String labelUz,
            @Size(max = 120) String labelEn,
            @Size(max = 64) String posModifierCode,
            @Min(0) int sortOrder,
            @NotBlank @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @NotNull Integer expectedVersion,
            @Nullable @Size(max = 32) Map<String, @Size(max = 120) String> labels) {}

    /**
     * @param labelRu/labelUz/labelEn the platform triple's columns, unchanged
     * @param labels                  every locale the preset has wording in, the triple
     *                                first then any other by code, each once
     */
    record PresetResponse(
            UUID presetId,
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status,
            int version,
            Map<String, String> labels) {

        static PresetResponse of(CommentPresetService.PresetView view) {
            PresetRow row = view.row();
            return new PresetResponse(
                    row.id(),
                    row.code(),
                    row.labelRu(),
                    row.labelUz(),
                    row.labelEn(),
                    row.posModifierCode(),
                    row.sortOrder(),
                    row.status(),
                    row.version(),
                    view.labels());
        }
    }

    /** @param locales default first; see {@link TenantLocaleSet} */
    record LocaleSetResponse(List<String> locales, String defaultLocale, boolean configured) {

        static LocaleSetResponse of(TenantLocaleSet set) {
            return new LocaleSetResponse(set.locales(), set.defaultLocale(), set.configured());
        }
    }
}
