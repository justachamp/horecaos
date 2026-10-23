package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The OpenAPI gate's allowances (ADR 0137, ADR 0150, ADR 0182) are only worth having if they stay
 * narrow: a quantity may become a number, one fallback may be left blank, one request may swap free
 * text for an approved wording, and nothing else may.
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

    // ------------------------------------------ ADR 0182: a courier broadcast names an approved wording

    private static final String DRAFT_REQUEST =
            "/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/courier-broadcasts post request";

    @Test
    @DisplayName("the courier broadcast draft may require a wording's key and may drop the free text it replaced")
    void theCourierDraftMaySwapFreeTextForAWording() {
        assertThat(AcceptedContractWidenings.mayBecomeRequired(DRAFT_REQUEST)).containsExactly("templateKey");
        assertThat(AcceptedContractWidenings.mayBeRemoved(DRAFT_REQUEST)).containsExactly("message");
        assertThat(AcceptedContractWidenings.mayBecomeOptional(DRAFT_REQUEST)).containsExactly("message");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/courier-broadcasts post request.targetKind",
                "/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/courier-broadcasts post response 201",
                "/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/courier-broadcasts get response 200[]",
                "/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/courier-broadcasts/{broadcastId}/sends post request",
                "/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/campaigns post request",
                "/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/carts post request"
            })
    @DisplayName(
            "no other schema may gain a required field, lose a property or stop requiring one on the strength of it")
    void nothingElseMaySwapAField(String context) {
        assertThat(AcceptedContractWidenings.mayBecomeRequired(context)).isEmpty();
        assertThat(AcceptedContractWidenings.mayBeRemoved(context)).isEmpty();
        assertThat(AcceptedContractWidenings.mayBecomeOptional(context)).isEmpty();
    }
}
