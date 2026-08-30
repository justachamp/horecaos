package uz.horecaos.platform.pos.infrastructure.clopos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.domain.SourceKind;

/**
 * Turns Clopos JSON into the canonical staging shape (ADR 0012).
 *
 * <p>This is where the vendor's vocabulary stops. Two Clopos words differ by
 * three characters and mean entirely different things:
 *
 * <ul>
 *   <li>a <b>modification</b> is a <em>variant</em> — a size or a colour. It has
 *       {@code type: "MODIFICATION"}, its {@code parent_id} points at the parent
 *       product, and it carries the full product schema including its own price,
 *       cost, barcode and stock;</li>
 *   <li>a <b>modificator</b>, inside {@code modificator_groups}, is a
 *       <em>modifier option</em> — "extra cheese" — and Clopos attaches them only
 *       to a {@code DISH}.</li>
 * </ul>
 *
 * <p>Clopos's own field reference concedes the confusion. Nothing downstream of
 * this class sees either word.
 *
 * <p>Three filtering rules, each of which prevents a specific wrong outcome.
 *
 * <ol>
 *   <li><b>Ingredients and preparations are staged and marked, not dropped.</b>
 *       Clopos returns them from {@code /products} beside real menu items — the
 *       documentation's own example response contains a tomato and an onion — so
 *       leaving them comparable would have the difference engine proposing
 *       vegetables as draft customer-facing products. Dropping them instead would
 *       lose the evidence that the provider sent them.</li>
 *   <li><b>A parent with variants is marked as not itself sellable.</b> Clopos is
 *       explicit that when a product has variants only the variants can be sold,
 *       the parent's price may be zero, and its values are not inherited at sale
 *       time. Publishing the parent as priceable publishes a free dish.</li>
 *   <li><b>Nested-set columns are ignored.</b> Categories carry {@code _lft} and
 *       {@code _rgt}, which renumber on every tree edit. Reading {@code parent_id}
 *       and {@code depth} is the whole of what is stable.</li>
 * </ol>
 */
public final class CloposCatalogNormalizer {

    private final String currency;

    /**
     * @param currency asserted from installation configuration. Clopos has no
     *                 currency field anywhere in its API, so this cannot be read
     *                 off the wire and must not be inferred from a price's shape
     */
    public CloposCatalogNormalizer(String currency) {
        this.currency = currency;
    }

    /**
     * @param walkStable whether the paged read could have skipped rows. Carried
     *                   through to the snapshot because it decides how much a
     *                   single run's absence is worth as evidence of a removal
     */
    public CatalogSnapshot normalize(List<Map<String, Object>> rawProducts,
            List<Map<String, Object>> rawCategories,
            List<Map<String, Object>> rawStopList,
            Instant readAt, boolean walkStable, int pageCount) {

        List<CatalogSnapshot.Category> categories = new ArrayList<>();
        for (Map<String, Object> raw : rawCategories) {
            categories.add(new CatalogSnapshot.Category(
                    id(raw, "id"),
                    id(raw, "parent_id"),
                    text(raw, "name"),
                    intOf(raw, "sort_order", 0),
                    CloposEnvelope.flag(raw, "status", true),
                    boxedInt(raw, "depth"),
                    raw));
        }

        List<CatalogSnapshot.Product> products = new ArrayList<>();
        List<CatalogSnapshot.Variant> variants = new ArrayList<>();
        List<CatalogSnapshot.ModifierGroup> groups = new ArrayList<>();
        List<CatalogSnapshot.Modifier> modifiers = new ArrayList<>();

        for (Map<String, Object> raw : rawProducts) {
            SourceKind kind = kindOf(text(raw, "type"));

            if (kind == SourceKind.VARIANT) {
                // A top-level MODIFICATION row. Clopos also nests these under
                // their parent when with[]=modifications is requested, so the
                // same variant can arrive twice; the run's staging key is the
                // external id, which collapses the duplicate rather than
                // reporting a conflict for something that is not one.
                variants.add(variant(raw, id(raw, "parent_id")));
                continue;
            }

            List<Map<String, Object>> nested = listOf(raw, "modifications");
            for (Map<String, Object> nestedVariant : nested) {
                variants.add(variant(nestedVariant, id(raw, "id")));
            }

            products.add(new CatalogSnapshot.Product(
                    id(raw, "id"),
                    text(raw, "name"),
                    id(raw, "category_id"),
                    kind,
                    kind.menuCandidate(),
                    !nested.isEmpty(),
                    minor(CloposEnvelope.decimal(raw, "price")),
                    currency,
                    CloposEnvelope.flag(raw, "status", true),
                    CloposEnvelope.flag(raw, "hidden", false),
                    text(raw, "gov_code"),
                    raw));

            for (Map<String, Object> rawGroup : listOf(raw, "modificator_groups")) {
                String groupId = id(rawGroup, "id");
                groups.add(new CatalogSnapshot.ModifierGroup(
                        groupId,
                        id(raw, "id"),
                        text(rawGroup, "name"),
                        intOf(rawGroup, "min", 0),
                        // A group with no stated maximum permits one choice. Zero
                        // would be a group nobody can choose from, which is not
                        // what an unstated maximum means.
                        Math.max(1, intOf(rawGroup, "max", 1)),
                        CloposEnvelope.flag(rawGroup, "required", false),
                        rawGroup));

                for (Map<String, Object> rawModifier : listOf(rawGroup, "modificators")) {
                    modifiers.add(new CatalogSnapshot.Modifier(
                            id(rawModifier, "id"),
                            groupId,
                            text(rawModifier, "name"),
                            minor(CloposEnvelope.decimal(rawModifier, "price")),
                            currency,
                            CloposEnvelope.flag(rawModifier, "status", true),
                            rawModifier));
                }
            }
        }

        List<CatalogSnapshot.Availability> availability = new ArrayList<>();
        for (Map<String, Object> raw : rawStopList) {
            availability.add(new CatalogSnapshot.Availability(
                    id(raw, "id"),
                    CloposEnvelope.decimal(raw, "limit"),
                    stopListTime(raw),
                    raw));
        }

        return new CatalogSnapshot(readAt, walkStable, pageCount,
                categories, products, variants, groups, modifiers, availability);
    }

    private CatalogSnapshot.Variant variant(Map<String, Object> raw, String parentId) {
        return new CatalogSnapshot.Variant(
                id(raw, "id"),
                parentId,
                text(raw, "name"),
                minor(CloposEnvelope.decimal(raw, "price")),
                currency,
                CloposEnvelope.flag(raw, "status", true),
                // Kept as the integer Clopos sent. There is no units endpoint in
                // the API, so this resolves to nothing and a unit code here would
                // be an invention.
                id(raw, "unit_id"),
                raw);
    }

    /**
     * The stop list's timestamp is Unix <em>milliseconds</em>, while the rest of
     * the API uses seconds or {@code YYYY-MM-DD HH:mm:ss} strings. Reading it as
     * seconds puts every availability observation somewhere in the year 57000.
     */
    private static Instant stopListTime(Map<String, Object> raw) {
        Object value = raw.get("timestamp");
        return value instanceof Number number ? Instant.ofEpochMilli(number.longValue()) : null;
    }

    static SourceKind kindOf(String cloposType) {
        if (cloposType == null) {
            return SourceKind.UNKNOWN;
        }
        return switch (cloposType.toUpperCase(java.util.Locale.ROOT)) {
            case "GOODS" -> SourceKind.GOODS;
            case "DISH" -> SourceKind.DISH;
            case "TIMER" -> SourceKind.TIMER;
            case "PREPARATION" -> SourceKind.PREPARATION;
            case "INGREDIENT" -> SourceKind.INGREDIENT;
            case "MODIFICATION" -> SourceKind.VARIANT;
            // MODIFIER appears in Clopos's prose field reference and not in its
            // OpenAPI enum. Unrecognised rather than guessed: a kind nobody can
            // confirm is worth an operator seeing, and mapping it to a guess would
            // hide the discrepancy that produced it.
            default -> SourceKind.UNKNOWN;
        };
    }

    /**
     * Whole minor units from a decimal price.
     *
     * <p>For UZS a minor unit is a whole som, so the scale is zero and this is a
     * rounding of a value Clopos should already have sent as an integer. HALF_UP
     * rather than truncation, because truncating a price that arrived as 8.5
     * loses money quietly in the restaurant's favour on every line.
     */
    static Long minor(BigDecimal amount) {
        return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String id(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        // Clopos ids are integers, and an integer that arrived as a JSON number
        // renders through Number#toString rather than through anything that could
        // introduce an exponent.
        return value instanceof Number number ? Long.toString(number.longValue()) : String.valueOf(value);
    }

    private static String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intOf(Map<String, Object> raw, String key, int fallback) {
        Object value = raw.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Integer boxedInt(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
