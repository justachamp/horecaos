package uz.horecaos.platform.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.CommentPresetLookup;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;

/**
 * The {@code catalog.api} face of row 2.1b's preset vocabulary, matching
 * {@code StopListPortAdapter}'s shape: a translation layer over {@link
 * CommentPresetService}'s own authoring model, not a reinvention of it.
 */
@Component
public class CommentPresetLookupAdapter implements CommentPresetLookup {

    private static final String ACTIVE = "ACTIVE";

    private final JdbcCatalogStore catalog;
    private final JdbcCommentPresetStore presets;

    public CommentPresetLookupAdapter(JdbcCatalogStore catalog, JdbcCommentPresetStore presets) {
        this.catalog = catalog;
        this.presets = presets;
    }

    @Override
    public List<String> offeredCodesForVariant(UUID tenantId, UUID brandId, UUID variantId) {
        return catalog.productIdForVariant(tenantId, variantId)
                .map(productId -> presets.listForProduct(tenantId, brandId, productId).stream()
                        .filter(row -> ACTIVE.equals(row.status()))
                        .map(JdbcCommentPresetStore.ProductPresetRow::code)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public Map<String, ResolvedPreset> resolve(UUID tenantId, Set<String> codes) {
        List<JdbcCommentPresetStore.PresetRow> active = presets.findByCodes(tenantId, codes).stream()
                .filter(row -> ACTIVE.equals(row.status()))
                .toList();
        // One bulk read of the per-locale wording, merged with the columns the way every other
        // read of a preset is: the column wins for the platform triple, the table is the only
        // home of a locale beyond it (row 10.12).
        Map<UUID, Map<String, String>> translations = presets.translationsForPresets(
                tenantId,
                active.stream().map(JdbcCommentPresetStore.PresetRow::id).collect(Collectors.toUnmodifiableSet()));
        return active.stream()
                .collect(Collectors.toMap(
                        JdbcCommentPresetStore.PresetRow::code,
                        row -> new ResolvedPreset(
                                row.id(),
                                row.code(),
                                row.labelRu(),
                                row.labelUz(),
                                row.labelEn(),
                                LocalizedLabels.merge(
                                        row.labelRu(),
                                        row.labelUz(),
                                        row.labelEn(),
                                        translations.getOrDefault(row.id(), Map.of())))));
    }
}
