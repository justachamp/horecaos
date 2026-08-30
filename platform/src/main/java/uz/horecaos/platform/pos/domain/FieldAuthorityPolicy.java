package uz.horecaos.platform.pos.domain;

import java.util.Map;
import uz.horecaos.platform.pos.domain.SyncDifference.FieldAuthority;
import uz.horecaos.platform.pos.domain.SyncDifference.RecommendedAction;

/**
 * Who owns which field, at a stated version (ADR 0012).
 *
 * <p>Versioned and snapshotted onto every run. Without the version, a run resumed
 * next week would reinterpret last week's staged snapshot under this week's
 * ownership rules, and a field that changed hands in between would be applied
 * under an authority nobody granted at the time the data arrived.
 *
 * <p>The default below is ADR 0012's initial ownership table, and its shape is
 * the argument: everything a customer sees belongs to HorecaOS, everything that
 * identifies a row across systems belongs to reconciliation, and what is left for
 * the provider to own automatically is close to nothing. That is deliberate and
 * it is the whole reason the module exists. A provider bug must be able to
 * produce a bad import and not a bad menu.
 */
public record FieldAuthorityPolicy(int version, Map<String, FieldAuthority> byField) {

    /** ADR 0012's initial ownership, as the platform ships it. */
    public static final FieldAuthorityPolicy INITIAL = new FieldAuthorityPolicy(
            1,
            Map.ofEntries(
                    // Everything the customer reads is HorecaOS's. A restaurant renaming a
                    // dish in their till must not rename it on the storefront, because
                    // the storefront name has been translated, photographed and indexed.
                    Map.entry("product.name", FieldAuthority.HORECAOS),
                    Map.entry("product.description", FieldAuthority.HORECAOS),
                    Map.entry("product.media", FieldAuthority.HORECAOS),
                    Map.entry("variant.name", FieldAuthority.HORECAOS),
                    Map.entry("modifier.name", FieldAuthority.HORECAOS),
                    Map.entry("category.name", FieldAuthority.HORECAOS),

                    // Price is HorecaOS's, and this is the entry that survives the worst
                    // provider ambiguity. On the first real POS a price list is
                    // documented as applying to venues or channels and no field in any
                    // schema expresses that application, so an imported price cannot even
                    // be attributed to a venue. Importing it as evidence makes that a
                    // reporting nuisance; importing it as authority would make it a
                    // pricing incident.
                    Map.entry("product.price", FieldAuthority.HORECAOS),
                    Map.entry("variant.price", FieldAuthority.HORECAOS),
                    Map.entry("modifier.price", FieldAuthority.HORECAOS),

                    // What the customer can actually buy is HorecaOS's, resolved from
                    // ADR 0016 offerings and ADR 0017 inventory. The provider's stop list
                    // is a strong input to that, not a replacement for it.
                    Map.entry("product.available", FieldAuthority.HORECAOS),
                    Map.entry("availability.limit", FieldAuthority.REVIEWED_IMPORT),

                    // Identity across the two systems. Nobody else may decide it.
                    Map.entry("product.externalId", FieldAuthority.MAPPING),
                    Map.entry("variant.externalId", FieldAuthority.MAPPING),
                    Map.entry("category.externalId", FieldAuthority.MAPPING),
                    Map.entry("modifierGroup.externalId", FieldAuthority.MAPPING),
                    Map.entry("modifier.externalId", FieldAuthority.MAPPING),

                    // Operational metadata the till genuinely owns: which station cooks
                    // it, what kind of thing the provider thinks it is, its own tax code.
                    // Reviewed rather than automatic, because ADR 0038 would otherwise
                    // accept a classification code from a provider whose market is not
                    // ours and whose format nobody has characterised.
                    Map.entry("product.sourceKind", FieldAuthority.PROVIDER),
                    Map.entry("product.station", FieldAuthority.PROVIDER),
                    Map.entry("product.governmentCode", FieldAuthority.REVIEWED_IMPORT),
                    Map.entry("product.categoryMembership", FieldAuthority.REVIEWED_IMPORT),
                    Map.entry("modifierGroup.selectionRange", FieldAuthority.REVIEWED_IMPORT)));

    public FieldAuthorityPolicy {
        byField = Map.copyOf(byField);
    }

    /**
     * @return {@link FieldAuthority#HORECAOS} for a field nobody assigned. The safe
     *         default: an unassigned field auto-applying is how a provider gains
     *         authority over something by the platform forgetting to name it
     */
    public FieldAuthority authorityOf(String fieldPath) {
        return byField.getOrDefault(fieldPath, FieldAuthority.HORECAOS);
    }

    /** What the engine should recommend for a change to this field. */
    public RecommendedAction recommendationFor(String fieldPath) {
        return switch (authorityOf(fieldPath)) {
            case PROVIDER, MAPPING -> RecommendedAction.AUTO_APPLY;
            case REVIEWED_IMPORT -> RecommendedAction.REVIEW;
            // Not REVIEW. A reviewer cannot be offered the option of overwriting
            // a HorecaOS-authoritative field, because the point of the authority is
            // that the provider's value does not get to win.
            case HORECAOS -> RecommendedAction.IGNORE;
        };
    }
}
