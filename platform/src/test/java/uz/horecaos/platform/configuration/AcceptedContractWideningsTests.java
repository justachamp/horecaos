package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The OpenAPI gate's one allowance (ADR 0137) is only worth having if it stays narrow: a quantity
 * may become a number, and nothing else may.
 */
class AcceptedContractWideningsTests {

    private static final String LINE =
            "/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/carts get response 200.lines[].";

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "totalQuantity", "quantityTotal", "pickupQuantity", "deliveryQuantity"})
    @DisplayName("a quantity, and the report columns that sum one, may widen from integer to number")
    void aQuantityMayWiden(String property) {
        assertThat(AcceptedContractWidenings.permits(LINE + property, "integer", "number"))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unitAmountMinor", "totalMinor", "itemCount", "lineNumber", "quantityMinor", "Quantity"})
    @DisplayName("any other integer that becomes a number still fails the gate")
    void nothingElseMayWiden(String property) {
        assertThat(AcceptedContractWidenings.permits(LINE + property, "integer", "number"))
                .isFalse();
    }

    @Test
    @DisplayName("only the widening is accepted: narrowing back, or any other type change, is not")
    void onlyIntegerToNumberIsAccepted() {
        assertThat(AcceptedContractWidenings.permits(LINE + "quantity", "number", "integer"))
                .isFalse();
        assertThat(AcceptedContractWidenings.permits(LINE + "quantity", "integer", "string"))
                .isFalse();
        assertThat(AcceptedContractWidenings.permits(LINE + "quantity", "string", "number"))
                .isFalse();
        assertThat(AcceptedContractWidenings.permits(LINE + "quantity", "integer", "integer"))
                .isFalse();
    }

    @Test
    @DisplayName("a schema that is not a property of an object is never accepted, whatever the path ends in")
    void aBareSchemaIsNotAProperty() {
        assertThat(AcceptedContractWidenings.permits("/api/v1/quantity post response 200", "integer", "number"))
                .isFalse();
        assertThat(AcceptedContractWidenings.permits("quantity", "integer", "number"))
                .isFalse();
        assertThat(AcceptedContractWidenings.permits(LINE + "quantity[]", "integer", "number"))
                .isFalse();
    }
}
