package uz.horecaos.platform.catalog.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.domain.CatalogLocales;
import uz.horecaos.platform.catalog.domain.Vocabularies;
import uz.horecaos.platform.catalog.domain.Vocabularies.Kind;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcVocabularyStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcVocabularyStore.EntryRow;

/**
 * Reads what the publication snapshot needs about tags and ingredients (ADR 0174): the active entries the
 * brand's products carry, in each product's order, with their wording keyed the way every other published name
 * is.
 *
 * <p>A seam of its own, like {@link AutoAddSnapshotSource}, so {@link CatalogSnapshotLoader} keeps the
 * constructors its callers and tests already use; a loader built without one publishes no tags and no
 * ingredients, which is what a catalog that never authored one wants.
 */
public interface VocabularySnapshotSource {

    CatalogValidator.VocabularyContext load(UUID tenantId, UUID brandId, Collection<UUID> productIds);

    /** For a loader with no vocabularies to read. */
    VocabularySnapshotSource NONE = (tenantId, brandId, productIds) -> CatalogValidator.VocabularyContext.empty();

    /** The production source: the registries and the product assignments. */
    @Component
    class Stored implements VocabularySnapshotSource {

        private final JdbcVocabularyStore store;

        public Stored(JdbcVocabularyStore store) {
            this.store = store;
        }

        @Override
        public CatalogValidator.VocabularyContext load(UUID tenantId, UUID brandId, Collection<UUID> productIds) {
            Map<UUID, List<UUID>> tagIds = store.activeAssignmentsForProducts(Kind.TAG, tenantId, brandId, productIds);
            Map<UUID, List<UUID>> ingredientIds =
                    store.activeAssignmentsForProducts(Kind.INGREDIENT, tenantId, brandId, productIds);
            return new CatalogValidator.VocabularyContext(
                    entries(Kind.TAG, tenantId, tagIds),
                    entries(Kind.INGREDIENT, tenantId, ingredientIds),
                    tagIds,
                    ingredientIds);
        }

        private Map<UUID, Vocabularies.Entry> entries(Kind kind, UUID tenantId, Map<UUID, List<UUID>> byProduct) {
            Collection<UUID> referenced = byProduct.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (referenced.isEmpty()) {
                return Map.of();
            }
            Map<UUID, Map<String, String>> names = store.namesForTenant(kind, tenantId);
            Map<UUID, Vocabularies.Entry> entries = new LinkedHashMap<>();
            for (EntryRow row : store.findAll(kind, tenantId, referenced).stream()
                    .sorted(java.util.Comparator.comparingInt(EntryRow::sortOrder)
                            .thenComparing(EntryRow::code))
                    .toList()) {
                Map<String, String> published = new LinkedHashMap<>();
                names.getOrDefault(row.id(), Map.of())
                        .forEach((locale, name) -> published.put(CatalogLocales.forBrandLocale(locale), name));
                entries.put(
                        row.id(),
                        new Vocabularies.Entry(
                                row.id(),
                                row.code(),
                                row.iconKey(),
                                row.showAsBadge(),
                                row.showAsFilter(),
                                row.sortOrder(),
                                row.version(),
                                Map.copyOf(published)));
            }
            return entries;
        }
    }
}
