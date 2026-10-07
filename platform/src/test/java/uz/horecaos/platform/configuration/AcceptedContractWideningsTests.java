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

    // ----------------------------------------------------- ADR 0150: an optional fallback

    private static final String EDITOR_REQUEST =
            "/api/v1/operations/tenants/{tenantId}/order-lateness-policy post request.";

    @ParameterizedTest
    @ValueSource(strings = {"delivery", "pickup", "dineIn"})
    @DisplayName("each mode of the lateness editor's request may leave the no-promise fallback blank")
    void theFallbackMayBecomeOptionalInEachMode(String mode) {
        assertThat(AcceptedContractWidenings.mayBecomeOptional(EDITOR_REQUEST + mode))
                .containsExactly("noPromiseFallbackSeconds");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/operations/tenants/{tenantId}/order-lateness-policy post request",
                "/api/v1/operations/tenants/{tenantId}/order-lateness-policy post request.reason",
                "/api/v1/operations/tenants/{tenantId}/order-lateness-policy post request.delivery.lateAfterSeconds",
                "/api/v1/operations/tenants/{tenantId}/order-lateness-policy get response 200.delivery",
                "/api/v1/operations/tenants/{tenantId}/order-acceptance post request.delivery",
                "/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/carts post request.delivery"
            })
    @DisplayName("nothing else may make a required field optional: not the grace, not the reply, not another route")
    void nothingElseMayBecomeOptional(String context) {
        assertThat(AcceptedContractWidenings.mayBecomeOptional(context)).isEmpty();
    }
}
