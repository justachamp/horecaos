package uz.horecaos.platform.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Row 2.1b: what {@code ordering} needs from the preset product comment
 * vocabulary to let a line carry one, and nothing of {@link
 * uz.horecaos.platform.catalog.application.CommentPresetService}'s own
 * authoring model — a port rather than a dependency on catalog, matching
 * {@link StopListPort} and {@link VariantPricingLookup}'s own shape.
 *
 * <p>Read live rather than from a publication: unlike a modifier group's
 * selection rules, a preset carries no price and no availability fact a
 * republish exists to freeze — it is exactly the coded vocabulary {@code
 * catalog.sales_channels} already is (V0378's own migration doc), and an
 * operator attaching a new preset to a product must be choosable on the next
 * order, not the next republish.
 */
public interface CommentPresetLookup {

    /**
     * The codes this variant's own product currently offers on a line, in
     * display order. Empty for a variant whose product offers none, or whose
     * id does not resolve at all — either way, "nothing to choose from" is
     * the honest answer and {@code CartService} refuses any code named
     * against it.
     */
    List<String> offeredCodesForVariant(UUID tenantId, UUID brandId, UUID variantId);

    /**
     * Resolves a set of codes to their current identity and label text, for
     * a checkout snapshot — every code in {@code codes} that still names an
     * {@code ACTIVE} preset for this tenant. A code renamed or archived since
     * the line was added is silently absent rather than refused: the line
     * was already validated when it was chosen, and checkout is the one
     * place the words are frozen onto the order, not re-validated against a
     * vocabulary that may have moved on.
     */
    Map<String, ResolvedPreset> resolve(UUID tenantId, Set<String> codes);

    record ResolvedPreset(UUID presetId, String code, String labelRu, String labelUz, String labelEn) {}
}
