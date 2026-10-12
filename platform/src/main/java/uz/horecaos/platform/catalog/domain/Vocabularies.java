package uz.horecaos.platform.catalog.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The two reference vocabularies ADR 0174 builds -- tags and ingredients -- and the bounds and closed sets that
 * keep them vocabularies and not free text.
 *
 * <p>Both are tenant-wide registries with brand-scoped assignments through the product. A tag is a badge and a
 * storefront filter option; an ingredient is a composition label. Neither is priced, stocked, ordered or counted,
 * and an ingredient carries no allergen meaning: the schema has no column for one and none is to be added by
 * this record.
 */
public final class Vocabularies {

    private Vocabularies() {}

    /** Operator-chosen, stable, never shown to a customer; the column's check says the same. */
    public static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    /** The widest a name is, in any locale (the column is {@code varchar(80)}). */
    public static final int MAX_NAME_LENGTH = 80;

    /**
     * The badge pictograms, closed and code-owned (ADR 0174, open input on the icon set): neutral names in the
     * shared UI library. No uploaded image, because an upload is the media pipeline and an attack surface for a
     * decoration, and no name that makes a dietary claim.
     */
    public static final List<String> TAG_ICONS = List.of("FLAME", "LEAF", "STAR", "HEART", "FISH", "NEW");

    /** The two registries and what differs between them: their tables and their bounds. */
    public enum Kind {
        TAG("catalog.tags", "catalog.tag_translations", "catalog.product_tags", "tag_id", 12, 200),
        INGREDIENT(
                "catalog.ingredients",
                "catalog.ingredient_translations",
                "catalog.product_ingredients",
                "ingredient_id",
                40,
                1000);

        private final String registryTable;
        private final String translationTable;
        private final String assignmentTable;
        private final String entryColumn;
        private final int maximumPerProduct;
        private final int maximumPerTenant;

        Kind(
                String registryTable,
                String translationTable,
                String assignmentTable,
                String entryColumn,
                int maximumPerProduct,
                int maximumPerTenant) {
            this.registryTable = registryTable;
            this.translationTable = translationTable;
            this.assignmentTable = assignmentTable;
            this.entryColumn = entryColumn;
            this.maximumPerProduct = maximumPerProduct;
            this.maximumPerTenant = maximumPerTenant;
        }

        /** Table names are constants of this enum, never request input, so they may be concatenated into SQL. */
        public String registryTable() {
            return registryTable;
        }

        public String translationTable() {
            return translationTable;
        }

        public String assignmentTable() {
            return assignmentTable;
        }

        /** The column of the translation and assignment tables that names the registry entry. */
        public String entryColumn() {
            return entryColumn;
        }

        public int maximumPerProduct() {
            return maximumPerProduct;
        }

        public int maximumPerTenant() {
            return maximumPerTenant;
        }

        /** Whether the kind has an icon and the two switches; an ingredient has none of them. */
        public boolean hasDisplayOptions() {
            return this == TAG;
        }

        /** The audit action prefix and the publication item type. */
        public String auditName() {
            return this == TAG ? "tag" : "ingredient";
        }

        public CatalogEntities.EntityType entityType() {
            return this == TAG ? CatalogEntities.EntityType.TAG : CatalogEntities.EntityType.INGREDIENT;
        }
    }

    /**
     * A registry entry as the publication carries it. {@code names} are keyed by the catalog's own locale codes
     * (the keys of every other published {@code names} map, {@code uz} not {@code uz-Latn}) so one resolver
     * reads them all.
     */
    public record Entry(
            java.util.UUID id,
            String code,
            @org.jspecify.annotations.Nullable String iconKey,
            boolean showAsBadge,
            boolean showAsFilter,
            int sortOrder,
            int version,
            java.util.Map<String, String> names) {}

    /** A refusal code the console and the tests branch on; stable. */
    public static final class Refusals {
        public static final String CODE_INVALID = "VOCABULARY_CODE_INVALID";
        public static final String CODE_TAKEN = "VOCABULARY_CODE_TAKEN";
        public static final String ICON_UNKNOWN = "TAG_ICON_UNKNOWN";
        public static final String NOT_VISIBLE = "TAG_NOT_VISIBLE";
        public static final String NAME_MISSING = "VOCABULARY_DEFAULT_NAME_MISSING";
        public static final String TENANT_LIMIT = "VOCABULARY_TENANT_LIMIT_REACHED";
        public static final String PRODUCT_LIMIT = "VOCABULARY_PRODUCT_LIMIT_REACHED";
        public static final String ENTRY_UNKNOWN = "VOCABULARY_ENTRY_UNKNOWN";
        public static final String ENTRY_ARCHIVED = "VOCABULARY_ENTRY_ARCHIVED";
        public static final String ENTRY_DUPLICATED = "VOCABULARY_ENTRY_DUPLICATED";

        private Refusals() {}
    }
}
