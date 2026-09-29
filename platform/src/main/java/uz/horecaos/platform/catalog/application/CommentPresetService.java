package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.PresetRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.ProductPresetRow;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
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

    /** {@code label_ru}/{@code label_uz}/{@code label_en} and a translation row all fit this. */
    static final int MAX_LABEL_LENGTH = 120;

    private final JdbcCommentPresetStore presets;
    private final JdbcCatalogStore catalog;
    private final AuditRecorder audit;
    private final Clock clock;
    private final BrandLocaleLookup brandLocales;

    @Autowired
    public CommentPresetService(
            JdbcCommentPresetStore presets,
            JdbcCatalogStore catalog,
            AuditRecorder audit,
            Clock clock,
            BrandLocaleLookup brandLocales) {
        this.presets = presets;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
        this.brandLocales = brandLocales;
    }

    /**
     * A service that treats every tenant as sitting on the platform locale
     * fallback (the {@code ru} default) -- for callers and tests that have no
     * brand-locale port and never name a locale outside the platform triple.
     */
    public CommentPresetService(
            JdbcCommentPresetStore presets, JdbcCatalogStore catalog, AuditRecorder audit, Clock clock) {
        this(presets, catalog, audit, clock, BrandLocaleLookup.platformFallback());
    }

    // ------------------------------------------------------------------ presets

    /**
     * Registers a preset. The wording it needs is the tenant's <em>default</em>
     * language (row 10.12, {@link TenantLocaleSet}); every other supported locale is
     * optional. The three platform columns are NOT NULL, so a triple locale the
     * caller did not supply is filled with the default wording -- the storefront and
     * the order snapshot already fall back to it, and the editor never shows that
     * column as a translation because the tenant's set does not include the locale.
     *
     * <p>Every supplied label is also written to the per-locale table
     * ({@code V0430}), the triple included, for the release that drops the columns.
     */
    @Transactional
    public PresetView createWithLabels(UUID tenantId, NewPreset command, String actorSubject) {
        Map<String, String> labels =
                suppliedLabels(command.labelRu(), command.labelUz(), command.labelEn(), command.labels());
        String defaultLocale = brandLocales.tenantLocaleSet(tenantId).defaultLocale();
        String defaultLabel = labels.get(defaultLocale);
        if (defaultLabel == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A preset needs its wording in the tenant's default language (%s)".formatted(defaultLocale));
        }
        PresetRow row = new PresetRow(
                Ids.newId(),
                tenantId,
                command.code(),
                labels.getOrDefault(LocalizedLabels.RU, defaultLabel),
                labels.getOrDefault(LocalizedLabels.UZ_LATN, defaultLabel),
                labels.getOrDefault(LocalizedLabels.EN, defaultLabel),
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
        presets.upsertTranslations(tenantId, row.id(), labels, row.createdAt());
        Map<String, Object> created = new java.util.LinkedHashMap<>();
        created.put("code", command.code());
        created.put("labelRu", row.labelRu());
        created.put("labelUz", row.labelUz());
        created.put("labelEn", row.labelEn());
        created.put("sortOrder", command.sortOrder());
        labels.forEach((locale, label) -> {
            if (!LocalizedLabels.PLATFORM_TRIPLE.contains(locale)) {
                created.put("label." + locale, label);
            }
        });
        audit.record(AuditFact.of("catalog.comment-preset.created", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("CommentPreset", row.id())
                .because("Registered a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                // Staff 9.3a: a brand-new preset, no prior state to diff against.
                .changed(ChangeDocuments.created(created))
                .correlatedBy(row.id().toString())
                .occurredAt(row.createdAt())
                .build());
        return new PresetView(row, mergedLabels(row, labels));
    }

    /** {@link #createWithLabels} for callers that read only the stored row. */
    @Transactional
    public PresetRow create(UUID tenantId, NewPreset command, String actorSubject) {
        return createWithLabels(tenantId, command, actorSubject).row();
    }

    public List<PresetRow> list(UUID tenantId) {
        return presets.list(tenantId);
    }

    /** Every preset with its wording merged from the platform columns and the per-locale table. */
    public List<PresetView> listWithLabels(UUID tenantId) {
        Map<UUID, Map<String, String>> translations = presets.translationsForTenant(tenantId);
        return presets.list(tenantId).stream()
                .map(row -> new PresetView(row, mergedLabels(row, translations.getOrDefault(row.id(), Map.of()))))
                .toList();
    }

    /** The locale set the tenant's preset editor offers -- see {@link TenantLocaleSet}. */
    public TenantLocaleSet localeSet(UUID tenantId) {
        return brandLocales.tenantLocaleSet(tenantId);
    }

    /**
     * Corrects a preset. Only the locales the request names are written: a locale
     * the editor did not show (one the tenant's brands no longer support) is not in
     * the request and keeps its wording -- an edit never deletes a translation.
     */
    @Transactional
    public PresetView updateWithLabels(UUID tenantId, UUID presetId, PresetEdit command, String actorSubject) {
        PresetRow existing = presets.find(tenantId, presetId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such preset"));
        if (existing.version() != command.expectedVersion()) {
            throw ApiException.staleVersion(command.expectedVersion(), existing.version());
        }
        Map<String, String> labels =
                suppliedLabels(command.labelRu(), command.labelUz(), command.labelEn(), command.labels());
        Map<String, String> existingTranslations = presets.translationsFor(tenantId, presetId);
        Instant now = clock.instant();
        int newVersion = presets.update(
                        tenantId,
                        presetId,
                        labels.get(LocalizedLabels.RU),
                        labels.get(LocalizedLabels.UZ_LATN),
                        labels.get(LocalizedLabels.EN),
                        command.posModifierCode(),
                        command.sortOrder(),
                        command.status(),
                        command.expectedVersion(),
                        now)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This preset was changed while this edit was being made"));
        presets.upsertTranslations(tenantId, presetId, labels, now);

        PresetRow updated = new PresetRow(
                existing.id(),
                existing.tenantId(),
                existing.code(),
                labels.getOrDefault(LocalizedLabels.RU, existing.labelRu()),
                labels.getOrDefault(LocalizedLabels.UZ_LATN, existing.labelUz()),
                labels.getOrDefault(LocalizedLabels.EN, existing.labelEn()),
                command.posModifierCode(),
                command.sortOrder(),
                command.status(),
                newVersion,
                existing.createdAt());

        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("labelRu", existing.labelRu());
        before.put("labelUz", existing.labelUz());
        before.put("labelEn", existing.labelEn());
        before.put("sortOrder", existing.sortOrder());
        before.put("status", existing.status());
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("labelRu", updated.labelRu());
        after.put("labelUz", updated.labelUz());
        after.put("labelEn", updated.labelEn());
        after.put("sortOrder", command.sortOrder());
        after.put("status", command.status());
        labels.forEach((locale, label) -> {
            if (!LocalizedLabels.PLATFORM_TRIPLE.contains(locale)) {
                before.put("label." + locale, existingTranslations.get(locale));
                after.put("label." + locale, label);
            }
        });
        audit.record(AuditFact.of("catalog.comment-preset.updated", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target("CommentPreset", presetId)
                .because("Edited a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                // Staff 9.3a: a per-field diff -- "existing" already holds every
                // field's value from before this write.
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(presetId.toString())
                .occurredAt(now)
                .build());
        Map<String, String> translationsAfter = new java.util.LinkedHashMap<>(existingTranslations);
        translationsAfter.putAll(labels);
        return new PresetView(updated, mergedLabels(updated, translationsAfter));
    }

    /** {@link #updateWithLabels} for callers that read only the stored row. */
    @Transactional
    public PresetRow update(UUID tenantId, UUID presetId, PresetEdit command, String actorSubject) {
        return updateWithLabels(tenantId, presetId, command, actorSubject).row();
    }

    private static Map<String, String> suppliedLabels(
            @Nullable String ru, @Nullable String uz, @Nullable String en, @Nullable Map<String, String> byLocale) {
        try {
            return LocalizedLabels.supplied(ru, uz, en, byLocale, MAX_LABEL_LENGTH);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    private static Map<String, String> mergedLabels(PresetRow row, Map<String, String> translationRows) {
        return LocalizedLabels.merge(row.labelRu(), row.labelUz(), row.labelEn(), translationRows);
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
        // Staff 9.3a: read the pair's current sortOrder before the upsert
        // below overwrites it -- empty distinguishes a fresh attach from a
        // re-sort.
        Optional<Integer> before = presets.listForProduct(tenantId, brandId, productId).stream()
                .filter(row -> row.presetId().equals(presetId))
                .map(ProductPresetRow::sortOrder)
                .findFirst();
        UUID id = presets.upsertProductPreset(tenantId, brandId, productId, presetId, sortOrder);
        Map<String, Object> beforeFields =
                before.isEmpty() ? Map.of() : Map.of("presetId", presetId.toString(), "sortOrder", before.get());
        Map<String, Object> afterFields = Map.of("presetId", presetId.toString(), "sortOrder", sortOrder);
        audit.record(AuditFact.of("catalog.comment-preset.attached", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Product", productId)
                .because("Attached a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                // Staff 9.3a: diff(Map.of(), after) is created(after) --
                // before.isEmpty() means this call attached a fresh pair.
                .changed(ChangeDocuments.diff(beforeFields, afterFields))
                .correlatedBy(productId.toString())
                .occurredAt(clock.instant())
                .build());
        return id;
    }

    /** Idempotent — detaching a pair that was never attached, or is already gone, still resolves. */
    @Transactional
    public void detachFromProduct(UUID tenantId, UUID brandId, UUID productId, UUID presetId, String actorSubject) {
        // Staff 9.3a: read the pair's sortOrder before deleteProductPreset
        // removes the row it lived on.
        Optional<Integer> before = presets.listForProduct(tenantId, brandId, productId).stream()
                .filter(row -> row.presetId().equals(presetId))
                .map(ProductPresetRow::sortOrder)
                .findFirst();
        boolean removed = presets.deleteProductPreset(tenantId, brandId, productId, presetId);
        if (!removed) {
            return;
        }
        Map<String, Object> beforeDoc = new LinkedHashMap<>();
        beforeDoc.put("presetId", presetId.toString());
        beforeDoc.put("sortOrder", before.orElse(null));
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("presetId", null);
        afterDoc.put("sortOrder", null);
        audit.record(AuditFact.of("catalog.comment-preset.detached", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Product", productId)
                .because("Detached a preset product comment")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(beforeDoc, afterDoc))
                .correlatedBy(productId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    public List<ProductPresetRow> listForProduct(UUID tenantId, UUID brandId, UUID productId) {
        return presets.listForProduct(tenantId, brandId, productId);
    }

    // --------------------------------------------------------------- commands

    /**
     * @param labelRu/labelUz/labelEn the platform triple's wording, kept for callers
     *                                that predate the per-locale map; each optional
     * @param labels                  wording by locale (row 10.12), overlaying the three
     *                                fields above -- the map wins where both name a locale
     */
    public record NewPreset(
            String code,
            @Nullable String labelRu,
            @Nullable String labelUz,
            @Nullable String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            Map<String, String> labels) {

        public NewPreset(
                String code,
                String labelRu,
                String labelUz,
                String labelEn,
                @Nullable String posModifierCode,
                int sortOrder) {
            this(code, labelRu, labelUz, labelEn, posModifierCode, sortOrder, Map.of());
        }
    }

    /** @see NewPreset for the label fields; a locale not named here is left as it was */
    public record PresetEdit(
            @Nullable String labelRu,
            @Nullable String labelUz,
            @Nullable String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status,
            int expectedVersion,
            Map<String, String> labels) {

        public PresetEdit(
                String labelRu,
                String labelUz,
                String labelEn,
                @Nullable String posModifierCode,
                int sortOrder,
                String status,
                int expectedVersion) {
            this(labelRu, labelUz, labelEn, posModifierCode, sortOrder, status, expectedVersion, Map.of());
        }
    }

    /**
     * A preset as the console reads it: the stored row plus its wording merged from
     * the platform columns and the per-locale table, each locale once.
     */
    public record PresetView(PresetRow row, Map<String, String> labels) {}

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
