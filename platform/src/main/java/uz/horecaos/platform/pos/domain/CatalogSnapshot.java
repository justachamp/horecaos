package uz.horecaos.platform.pos.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One provider catalog, normalized (ADR 0012).
 *
 * <p>The canonical shape every POS normalizer produces and the difference engine
 * consumes. No provider word reaches this far: the first real adapter meets a
 * vendor that calls a variant a "modification" and a modifier option a
 * "modificator" — three characters apart, entirely different things — and
 * carrying either word past the normalizer would guarantee somebody eventually
 * wires one into the other's table.
 *
 * <p>Whole snapshots rather than deltas, because the first real provider offers
 * no alternative: no updated-at filter, no date range on the product list, no
 * cursor, no ETag, no conditional request and no change feed. That is a
 * constraint the design already absorbed; what it must not absorb is the belief
 * that one page-through is an atomic picture of the menu. It is not — see
 * {@link #walkStable()}.
 *
 * @param walkStable whether the paging strategy could have skipped rows. An
 *                   offset walk over a catalog somebody is editing can miss a
 *                   product entirely, and a missed product is indistinguishable
 *                   here from a deleted one. {@link RemovalQuorum} reads this
 * @param pageCount  how many requests the walk took. Carried so an operator
 *                   reading a suspicious run can see whether the catalog was big
 *                   enough for a race to be plausible
 */
public record CatalogSnapshot(
        Instant readAt,
        boolean walkStable,
        int pageCount,
        List<Category> categories,
        List<Product> products,
        List<Variant> variants,
        List<ModifierGroup> modifierGroups,
        List<Modifier> modifiers,
        List<Availability> availability) {

    public CatalogSnapshot {
        categories = List.copyOf(categories == null ? List.of() : categories);
        products = List.copyOf(products == null ? List.of() : products);
        variants = List.copyOf(variants == null ? List.of() : variants);
        modifierGroups = List.copyOf(modifierGroups == null ? List.of() : modifierGroups);
        modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
        availability = List.copyOf(availability == null ? List.of() : availability);
    }

    /** Only the products that are candidates for a customer-facing menu. */
    public List<Product> comparableProducts() {
        return products.stream().filter(Product::comparable).toList();
    }

    /**
     * A staged provider category, unflattened from its nested-set representation.
     *
     * @param externalParentId null for a top-level category. Absent from the
     *                         provider's own tree rather than modeled with a
     *                         sentinel, matching {@code external_parent_id}
     * @param depth            the nested-set depth, when the provider states one.
     *                         Never recomputed from {@code _lft}/{@code _rgt},
     *                         which renumber on every tree edit
     */
    public record Category(
            String externalId,
            @Nullable String externalParentId,
            String name,
            int sortOrder,
            boolean active,
            @Nullable Integer depth,
            Map<String, Object> raw) {}

    /**
     * A staged provider product, its menu-comparability, and its optional price.
     *
     * @param comparable  false for kinds that are inventory rather than menu —
     *                    ingredients, preparations, time-billed rentals. They are
     *                    staged and marked rather than dropped so the evidence of
     *                    what the provider sent survives, and excluded from
     *                    comparison so the engine does not propose creating
     *                    tomatoes as draft customer-facing products
     * @param parentOnly  true when the provider sells only this product's
     *                    variants. The parent's own price is then not inherited at
     *                    sale time and may be zero, so treating it as priceable
     *                    publishes a free dish
     * @param priceMinor  whole minor units, or null when the provider stated none.
     *                    Parsed from the response's raw decimal text, never
     *                    through a double
     * @param governmentCode the provider's tax classification string, unparsed.
     *                    ADR 0038 needs an MXIK; whether this holds one is a
     *                    question about the provider's market and not about the
     *                    field's name
     */
    public record Product(
            String externalId,
            String name,
            @Nullable String externalCategoryId,
            SourceKind sourceKind,
            boolean comparable,
            boolean parentOnly,
            @Nullable Long priceMinor,
            String currency,
            boolean active,
            boolean hidden,
            @Nullable String governmentCode,
            Map<String, Object> raw) {}

    /**
     * A staged provider variant — a size, a colour — with its own price.
     *
     * @param externalUnitReference the provider's unit identifier, unresolved.
     *                              The first real provider publishes no endpoint
     *                              that maps it to a name or a measure, so this is
     *                              the whole of what is knowable and a unit code
     *                              here would be an invention
     */
    public record Variant(
            String externalId,
            String externalProductId,
            String name,
            @Nullable Long priceMinor,
            String currency,
            boolean active,
            @Nullable String externalUnitReference,
            Map<String, Object> raw) {}

    public record ModifierGroup(
            String externalId,
            String externalProductId,
            String name,
            int minimumSelections,
            int maximumSelections,
            boolean required,
            Map<String, Object> raw) {}

    public record Modifier(
            String externalId,
            String externalGroupId,
            String name,
            @Nullable Long priceMinor,
            String currency,
            boolean active,
            Map<String, Object> raw) {}

    /**
     * One entity's stop-list reading.
     *
     * @param stockLimit null when the provider named no limit for this entity.
     *                   Absence from a stop list means unconstrained, not
     *                   unavailable, and inverting that reading empties a menu
     * @param observedAt null when the provider's own stop-list timestamp did not
     *                   parse as a number. Recorded rather than defaulted, so a
     *                   reader can tell a genuinely unknown observation time from
     *                   one that happened to be the epoch
     */
    public record Availability(
            String externalId,
            java.math.@Nullable BigDecimal stockLimit,
            @Nullable Instant observedAt,
            Map<String, Object> raw) {}
}
