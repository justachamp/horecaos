package uz.horecaos.platform.ordering.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule;

/**
 * The authoring side of the rules, for the console's simulator only (ADR 0158): drafts and active rules
 * of one brand and the facts about the variants a hypothetical basket names.
 *
 * <p>A port, for the reason {@link CartAutoAddRules} is one: ordering reads what it needs from the catalog's
 * tables and takes no dependency on how they are authored. Unlike {@link CartAutoAddRules} it reads the
 * authoring rows and never a publication, because an author previews a rule before it is live.
 */
public interface AutoAddDraftRules {

    /** The brand's draft and active rules, archived ones left out. */
    List<DraftRule> rules(UUID tenantId, UUID brandId);

    /** What the catalog says about these variants; a variant of another brand or none is absent. */
    Map<UUID, VariantFacts> variantFacts(UUID tenantId, UUID brandId, Collection<UUID> variantIds);

    /** A rule as the evaluator reads it, with the authoring status it has. */
    record DraftRule(PublishedAutoAddRule rule, String status) {}

    /**
     * @param active whether the variant can be sold at all
     */
    record VariantFacts(
            @Nullable UUID productId,
            Set<UUID> categoryIds,
            @Nullable BigDecimal portionsPerUnit,
            boolean active) {

        public VariantFacts {
            categoryIds = Set.copyOf(categoryIds);
        }
    }
}
