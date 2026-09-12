package uz.horecaos.platform.inventory.web;

import java.util.Objects;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.web.api.ApiProblem;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Maps inventory-specific failures onto the shared ADR 0031 error codes.
 *
 * <p>{@link InventoryService.UnsupportedTrackingModeException} is a bare
 * {@code RuntimeException} (it has to stay reachable from code that never
 * imports this web package), so any endpoint that lets it escape unhandled
 * answers a 500 — an implementation gap reported as a platform outage rather
 * than the operator-legible refusal gap map row {@code 4.4d} asks for.
 * {@code InventoryController.listVariant} used to catch it locally; every
 * other endpoint that can reach {@code InventoryService} at all (today,
 * {@code checkAvailability}, if a {@code QUANTITY} stock item ever exists)
 * did not. One handler for the whole controller closes both at once.
 */
@RestControllerAdvice(assignableTypes = {InventoryController.class})
public class InventoryApiErrorHandler {

    @ExceptionHandler(InventoryService.UnsupportedTrackingModeException.class)
    ProblemDetail unsupportedTrackingMode(InventoryService.UnsupportedTrackingModeException exception) {
        // Throwable#getMessage is nullable in the JDK's own contract, even
        // though this exception always constructs one; see
        // GlobalApiErrorHandler.detailOrTitle for the same guard.
        return ApiProblem.of(
                ErrorCode.VALIDATION_FAILED,
                Objects.requireNonNullElse(exception.getMessage(), ErrorCode.VALIDATION_FAILED.title()));
    }
}
