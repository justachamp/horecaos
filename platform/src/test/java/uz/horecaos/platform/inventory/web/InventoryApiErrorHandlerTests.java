package uz.horecaos.platform.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import uz.horecaos.platform.inventory.application.InventoryService.UnsupportedTrackingModeException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Gap map row {@code 4.4d} (wave P46): {@link UnsupportedTrackingModeException}
 * is a bare {@code RuntimeException} with no handler anywhere before this
 * class, which means
 * QUANTITY tracking's refusal reached a caller as an unmapped 500 rather than
 * the operator-legible Problem Details response ADR 0031 requires. Direct
 * unit tests of the handler method, the same style {@code
 * GlobalApiErrorHandlerTests} uses, rather than a full MockMvc round trip.
 */
class InventoryApiErrorHandlerTests {

    private final InventoryApiErrorHandler handler = new InventoryApiErrorHandler();

    @Test
    @DisplayName("maps to a 400 VALIDATION_FAILED problem naming the flag, never a 500")
    void mapsToAClientProblemNamingTheFlag() {
        ProblemDetail problem = handler.unsupportedTrackingMode(new UnsupportedTrackingModeException(false));

        assertThat(problem.getStatus())
                .isEqualTo(ErrorCode.VALIDATION_FAILED.status().value());
        assertThat(problem.getProperties()).containsEntry("code", ErrorCode.VALIDATION_FAILED.name());
        assertThat(problem.getDetail()).contains("catalog.use_stock_logic");
    }

    @Test
    @DisplayName("the flag-on message is mapped identically, just with different detail text")
    void mapsTheFlagOnMessageToo() {
        ProblemDetail problem = handler.unsupportedTrackingMode(new UnsupportedTrackingModeException(true));

        assertThat(problem.getStatus())
                .isEqualTo(ErrorCode.VALIDATION_FAILED.status().value());
        assertThat(problem.getDetail()).contains("turned on").contains("not available");
    }

    /** Guards the tests above against {@code ErrorCode} changing this mapping silently. */
    @Test
    @DisplayName("VALIDATION_FAILED is a 400, so this refusal is a client error, not a server one")
    void validationFailedIsA400() {
        assertThat(ErrorCode.VALIDATION_FAILED.status().value()).isEqualTo(400);
    }
}
