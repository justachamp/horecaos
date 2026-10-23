package uz.horecaos.platform.catalog.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The fiscal classification catalog holds for a priceable node, for the module that turns an order's
 * lines into a receipt (ADR 0038, ADR 0161).
 *
 * <p>The sibling of {@link FiscalNodeFacts} and {@link PackageCodeLookup}, implemented by catalog and
 * read by payments, for the same reason both are: the fact belongs here, and a receipt line built from a
 * second copy of it is how a receipt comes to disagree with the menu. Read-only and personal-data-free.
 */
public interface FiscalClassificationLookup {

    /**
     * The classification of each of these nodes, in one query rather than one per line.
     *
     * @param priceableIds variant, modifier option and fee identifiers, mixed freely
     * @return one entry per node that has a classification row; a node with none is absent. A present
     *         entry may still lack individual fields, which the caller decides how to treat
     */
    Map<UUID, FiscalClassificationFacts> classifications(UUID tenantId, UUID brandId, Set<UUID> priceableIds);

    /**
     * The classification of the brand's delivery fee node, which is a priceable node like a dish and the
     * line a receipt files the delivery charge under (ADR 0038, ADR 0166 Decision 9).
     *
     * @return empty when the brand has no delivery fee node, or the node has no classification row at all;
     *         a present entry may still lack individual fields, which the caller decides how to treat
     */
    default Optional<FiscalClassificationFacts> deliveryFee(UUID tenantId, UUID brandId) {
        return Optional.empty();
    }

    /**
     * @param fiscalUnitCode the tax authority's unit code
     * @param fiscalName     the receipt name, at most 63 characters, distinct from the display name
     * @param barcode        the EAN-8, UPC-A or EAN-13 as stored
     */
    record FiscalClassificationFacts(
            @Nullable String mxikCode,
            @Nullable String packageCode,
            @Nullable Integer fiscalUnitCode,
            @Nullable String fiscalName,
            @Nullable String barcode,
            boolean markingRequired) {}
}
