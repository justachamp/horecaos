package uz.horecaos.platform.catalog.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The package code catalog holds for a priceable node, for a consumer building
 * a provider order line rather than a fiscal receipt (ADR 0038).
 *
 * <p>Clopos confirmed 2026-09-08 that its order line's required {@code
 * product_hash} field is not a hash at all — it is the package code that pairs
 * with the line's ИКПУ/MXIK (docs/providers/clopos-api.md Q12). So a POS export
 * needs exactly the value {@code catalog.fiscal_classifications.package_code}
 * already carries, and this is that value, read across the module boundary
 * rather than reinvented as a second classification field: ADR 0038 owns
 * product classification and {@link FiscalNodeFacts} is the sibling port for
 * the same table's other consumer-facing question.
 *
 * <p>Implemented by catalog and read by other modules, the same direction
 * {@link ItemDisplayLookup} and {@link FiscalNodeFacts} already use: the fact
 * belongs here.
 */
public interface PackageCodeLookup {

    /**
     * The package code classified for each of these nodes, in one query rather
     * than one per line.
     *
     * @param priceableIds variant, modifier option, and fee identifiers, mixed
     *                     freely — the classification table is keyed on all
     *                     three
     * @return one entry per node that has a package code. A node with no
     *         classification, or a classification with no package code, is
     *         simply absent — never a null value standing in for "unclassified"
     */
    Map<UUID, String> packageCodes(UUID tenantId, UUID brandId, Set<UUID> priceableIds);
}
