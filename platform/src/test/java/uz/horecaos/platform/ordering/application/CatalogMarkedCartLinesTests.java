package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.api.FiscalNodeFacts;
import uz.horecaos.platform.ordering.application.CartService.NestedModifier;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;

/**
 * Whether a basket forces a method that can carry a marking code (ADR 0166 Decision 7): the one caller
 * {@code FiscalNodeFacts.requiresMarkingCapablePayment} has ever had.
 *
 * <p>What would still be true if the wiring were broken: the catalog would still answer correctly about a
 * node, and a basket would still be offered Payme. So each test reads the nodes the catalog was asked about.
 */
class CatalogMarkedCartLinesTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID SODA = UUID.randomUUID();
    private static final UUID CHEESE = UUID.randomUUID();
    private static final UUID CHILI = UUID.randomUUID();

    private final CartService carts = mock(CartService.class);
    private final List<Set<UUID>> asked = new ArrayList<>();

    @Test
    @DisplayName("the catalog is asked about the variants and every option the basket holds, nested ones included")
    void theCatalogIsAskedAboutEveryNodeOfTheBasket() {
        CartLineRow line = line(SODA);
        when(carts.modifierIdsOf(line)).thenReturn(List.of(CHEESE));
        when(carts.nestedModifiersOf(line)).thenReturn(List.of(new NestedModifier(CHEESE, CHILI)));
        CatalogMarkedCartLines marked = markedWhen(Set.of(CHILI), true);

        boolean required = marked.requiresMarkingCapablePayment(TENANT, BRAND, List.of(line));

        assertThat(required).as("a nested option can be the marked node").isTrue();
        assertThat(asked).singleElement().isEqualTo(Set.of(SODA, CHEESE, CHILI));
    }

    @Test
    @DisplayName("a basket with no marked node does not force a method")
    void anUnmarkedBasketForcesNothing() {
        CartLineRow line = line(SODA);
        when(carts.modifierIdsOf(line)).thenReturn(List.of());
        when(carts.nestedModifiersOf(line)).thenReturn(List.of());

        assertThat(markedWhen(Set.of(), true).requiresMarkingCapablePayment(TENANT, BRAND, List.of(line)))
                .isFalse();
    }

    @Test
    @DisplayName("while marked goods are off for the tenant nothing is asked of the catalog and nothing is forced")
    void aTenantWithMarkedGoodsOffKeepsEveryMethod() {
        CartLineRow line = line(SODA);

        boolean required = markedWhen(Set.of(SODA), false).requiresMarkingCapablePayment(TENANT, BRAND, List.of(line));

        assertThat(required).isFalse();
        assertThat(asked).as("the catalog was not consulted").isEmpty();
    }

    private CatalogMarkedCartLines markedWhen(Set<UUID> markedNodes, boolean flag) {
        FiscalNodeFacts facts = (tenantId, brandId, ids) -> {
            asked.add(Set.copyOf(ids));
            return markedNodes.stream().filter(ids::contains).collect(java.util.stream.Collectors.toSet());
        };
        return new CatalogMarkedCartLines(
                facts, carts, new FakeConfigurationResolver(Map.of(FeatureFlagKeys.MARKED_GOODS.code(), flag)));
    }

    private static CartLineRow line(UUID variant) {
        return new CartLineRow(
                UUID.randomUUID(),
                "k",
                variant,
                BigDecimal.ONE,
                "[]",
                List.of(),
                null,
                "[]",
                "[]",
                CartLineRow.ORIGIN_CUSTOMER,
                null,
                null,
                false);
    }
}
