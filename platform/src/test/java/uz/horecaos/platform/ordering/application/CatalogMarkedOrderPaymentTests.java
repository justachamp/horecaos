package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.api.FiscalNodeFacts;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;

/**
 * Whether changing the payment method of an order reopens the door that checkout shut (ADR 0166 Decision 7):
 * a marked good paid through a method whose receipt cannot carry its code.
 *
 * <p>What would still be true if the rule were broken: the cart would still not be offered Payme, and the
 * receipt builder would still refuse the order, at the moment the receipt is built and not when the method
 * is chosen. So each test reads the answer the amendment is given.
 */
class CatalogMarkedOrderPaymentTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID SODA = UUID.randomUUID();
    private static final UUID PLOV = UUID.randomUUID();

    private final List<Set<UUID>> asked = new ArrayList<>();

    @Test
    @DisplayName("an order that already holds a marked line may not move to a method that cannot carry the code")
    void anOrderHoldingAMarkedLineMayNotMoveToAMethodThatCannotCarryTheCode() {
        CatalogMarkedOrderPayment rule = rule(true, true, Set.of(), "PAYME");

        assertThat(rule.refusalOf(TENANT, BRAND, ORDER, "PAYME", Set.of()))
                .hasValueSatisfying(message -> assertThat(message).contains("PAYME"));
        assertThat(asked)
                .as("the order's own marks answered; the catalog was not needed")
                .isEmpty();
    }

    @Test
    @DisplayName("a method that can carry the code is never refused, marked or not")
    void aMethodThatCarriesTheCodeIsNeverRefused() {
        CatalogMarkedOrderPayment rule = rule(true, true, Set.of(SODA), "PAYME");

        assertThat(rule.refusalOf(TENANT, BRAND, ORDER, "CLICK", Set.of(SODA))).isEmpty();
        assertThat(rule.refusalOf(TENANT, BRAND, ORDER, "CASH", Set.of(SODA))).isEmpty();
    }

    @Test
    @DisplayName("an order with nothing marked, and nothing marked added, keeps every method")
    void anUnmarkedOrderKeepsEveryMethod() {
        CatalogMarkedOrderPayment rule = rule(false, true, Set.of(SODA), "PAYME");

        assertThat(rule.refusalOf(TENANT, BRAND, ORDER, "PAYME", Set.of(PLOV))).isEmpty();
        assertThat(asked).singleElement().isEqualTo(Set.of(PLOV));
        assertThat(rule.refusalOf(TENANT, BRAND, ORDER, "PAYME", Set.of()))
                .as("an amendment adding nothing asks the catalog nothing")
                .isEmpty();
        assertThat(asked).hasSize(1);
    }

    @Test
    @DisplayName("a batch that adds a marked good and changes the method is judged as the order it would leave")
    void aMarkedGoodAddedInTheSameBatchCounts() {
        CatalogMarkedOrderPayment rule = rule(false, true, Set.of(SODA), "PAYME");

        assertThat(rule.refusalOf(TENANT, BRAND, ORDER, "PAYME", Set.of(PLOV, SODA)))
                .isPresent();
        assertThat(asked).singleElement().isEqualTo(Set.of(PLOV, SODA));
    }

    @Test
    @DisplayName("while marked goods are off for the tenant the catalog is not consulted and nothing is refused")
    void aTenantWithMarkedGoodsOffKeepsEveryMethod() {
        CatalogMarkedOrderPayment rule = rule(false, false, Set.of(SODA), "PAYME");

        assertThat(rule.refusalOf(TENANT, BRAND, ORDER, "PAYME", Set.of(SODA))).isEmpty();
        assertThat(asked).as("the catalog was not consulted").isEmpty();
    }

    private CatalogMarkedOrderPayment rule(
            boolean orderHoldsMarkedLine, boolean flag, Set<UUID> markedNodes, String methodThatCannotCarry) {
        OrderMarksPort marks = new OrderMarksPort() {
            @Override
            public List<LineMarkCount> markCounts(UUID tenantId, UUID orderId) {
                return orderHoldsMarkedLine ? List.of(new LineMarkCount(UUID.randomUUID(), 1, 0)) : List.of();
            }

            @Override
            public ReceiptSource receiptSource(UUID tenantId, UUID orderId) {
                throw new UnsupportedOperationException("not asked");
            }
        };
        FiscalNodeFacts facts = (tenantId, brandId, ids) -> {
            asked.add(Set.copyOf(ids));
            return markedNodes.stream().filter(ids::contains).collect(Collectors.toSet());
        };
        PaymentIntentPort payments = mock(PaymentIntentPort.class);
        when(payments.carriesMarkingCodes(any(), anyString())).thenReturn(true);
        when(payments.carriesMarkingCodes(any(), eq(methodThatCannotCarry))).thenReturn(false);
        return new CatalogMarkedOrderPayment(
                marks,
                facts,
                payments,
                new FakeConfigurationResolver(Map.of(FeatureFlagKeys.MARKED_GOODS.code(), flag)));
    }
}
