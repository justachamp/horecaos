package uz.horecaos.platform.ordering.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.FiscalNodeFacts;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;

/** {@link MarkedCartLines} over the catalog's classification of the nodes a basket holds. */
@Component
class CatalogMarkedCartLines implements MarkedCartLines {

    private final FiscalNodeFacts facts;
    private final CartService carts;
    private final ConfigurationResolver configuration;

    CatalogMarkedCartLines(FiscalNodeFacts facts, CartService carts, ConfigurationResolver configuration) {
        this.facts = facts;
        this.carts = carts;
        this.configuration = configuration;
    }

    @Override
    public boolean requiresMarkingCapablePayment(UUID tenantId, UUID brandId, List<CartLineRow> lines) {
        if (lines.isEmpty()
                || !Boolean.TRUE.equals(
                        configuration.value(FeatureFlagKeys.MARKED_GOODS, ResourceScope.tenant(tenantId)))) {
            return false;
        }
        Set<UUID> nodes = new HashSet<>();
        for (CartLineRow line : lines) {
            nodes.add(line.variantId());
            nodes.addAll(carts.modifierIdsOf(line));
            carts.nestedModifiersOf(line).forEach(nested -> nodes.add(nested.optionId()));
        }
        // The one call site of the catalog's question (ADR 0166 Decision 7): it had none.
        return facts.requiresMarkingCapablePayment(tenantId, brandId, nodes);
    }
}
