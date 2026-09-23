package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.PresetRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.ProductPresetRow;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring preset product comments (row 2.1b) — a tenant-wide coded
 * kitchen-instruction vocabulary, and which of a product's lines may carry
 * which preset.
 *
 * <p>{@code V0378__catalog_comment_presets.sql} explains why this is built
 * ahead of gap map row 4.7's neighbouring, still-blocked vocabularies
 * (attributes, tags, ingredients): those wait on an unanswered ADR 0016
 * question about whether an attribute is the variant axis or a spec-sheet
 * vocabulary, and a coded kitchen instruction is neither.
 */
@Service
public class CommentPresetService {

    private final JdbcCommentPresetStore presets;
    private final JdbcCatalogStore catalog;
    private final AuditRecorder audit;
    private final Clock clock;

    public CommentPresetService(
            JdbcCommentPresetStore presets, JdbcCatalogStore catalog, AuditRecorder audit, Clock clock) {
        this.presets = presets;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ presets

    @Transactional
    public PresetRow create(UUID tenantId, NewPreset command, String actorSubject) {
        PresetRow row = new PresetRow(
                Ids.newId(),
                tenantId,
                command.code(),
                command.labelRu(),
                command.labelUz(),
                command.labelEn(),
                command.posModifierCode(),
                command.sortOrder(),
                "ACTIVE",
                1,
                clock.instant());
        try {
            presets.insert(row);
        } catch (DuplicateKeyException clash) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A preset with code '%s' already exists for this tenant".formatted(command.code()));
        }
        audit.record(AuditFact.of("catalog.comment-preset.created", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("CommentPreset", row.id())
                .because("Registered a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("code", command.code()))
                .correlatedBy(row.id().toString())
                .occurredAt(row.createdAt())
                .build());
        return row;
    }

    public List<PresetRow> list(UUID tenantId) {
        return presets.list(tenantId);
    }

    @Transactional
    public PresetRow update(UUID tenantId, UUID presetId, PresetEdit command, String actorSubject) {
        PresetRow existing = presets.find(tenantId, presetId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such preset"));
        if (existing.version() != command.expectedVersion()) {
            throw ApiException.staleVersion(command.expectedVersion(), existing.version());
        }
        Instant now = clock.instant();
        int newVersion = presets.update(
                        tenantId,
                        presetId,
                        command.labelRu(),
                        command.labelUz(),
                        command.labelEn(),
                        command.posModifierCode(),
                        command.sortOrder(),
                        command.status(),
                        command.expectedVersion(),
                        now)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This preset was changed while this edit was being made"));
        audit.record(AuditFact.of("catalog.comment-preset.updated", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("CommentPreset", presetId)
                .because("Edited a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("status", command.status()))
                .correlatedBy(presetId.toString())
                .occurredAt(now)
                .build());
        return new PresetRow(
                existing.id(),
                existing.tenantId(),
                existing.code(),
                command.labelRu(),
                command.labelUz(),
                command.labelEn(),
                command.posModifierCode(),
                command.sortOrder(),
                command.status(),
                newVersion,
                existing.createdAt());
    }

    // ---------------------------------------------------- product attachment

    /**
     * Attaches a preset to a product, or re-sorts it if already attached.
     *
     * @throws UnknownProductException the product does not exist in this brand
     * @throws UnknownPresetException  the preset does not exist for this tenant
     */
    @Transactional
    public UUID attachToProduct(
            UUID tenantId, UUID brandId, UUID productId, UUID presetId, int sortOrder, String actorSubject) {
        if (!catalog.entityExistsInBrand(tenantId, brandId, EntityType.PRODUCT, productId)) {
            throw new UnknownProductException(productId);
        }
        if (presets.find(tenantId, presetId).isEmpty()) {
            throw new UnknownPresetException(presetId);
        }
        UUID id = presets.upsertProductPreset(tenantId, brandId, productId, presetId, sortOrder);
        audit.record(AuditFact.of("catalog.comment-preset.attached", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Product", productId)
                .because("Attached a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("presetId", presetId.toString(), "sortOrder", sortOrder))
                .correlatedBy(productId.toString())
                .occurredAt(clock.instant())
                .build());
        return id;
    }

    /** Idempotent — detaching a pair that was never attached, or is already gone, still resolves. */
    @Transactional
    public void detachFromProduct(UUID tenantId, UUID brandId, UUID productId, UUID presetId, String actorSubject) {
        boolean removed = presets.deleteProductPreset(tenantId, brandId, productId, presetId);
        if (!removed) {
            return;
        }
        audit.record(AuditFact.of("catalog.comment-preset.detached", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Product", productId)
                .because("Detached a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("presetId", presetId.toString()))
                .correlatedBy(productId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    public List<ProductPresetRow> listForProduct(UUID tenantId, UUID brandId, UUID productId) {
        return presets.listForProduct(tenantId, brandId, productId);
    }

    // --------------------------------------------------------------- commands

    public record NewPreset(
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            int sortOrder) {}

    public record PresetEdit(
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status,
            int expectedVersion) {}

    public static class UnknownProductException extends RuntimeException {
        public UnknownProductException(UUID productId) {
            super("No such product " + productId + " in this brand");
        }
    }

    public static class UnknownPresetException extends RuntimeException {
        public UnknownPresetException(UUID presetId) {
            super("No such preset " + presetId + " for this tenant");
        }
    }
}
