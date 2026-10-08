package uz.horecaos.platform.integration.camel.einvoicing.didox;

import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;

/**
 * Didox's numeric document status read as the state ADR 0096 shows (ADR 0096 decision 3).
 *
 * <p><strong>Provisional.</strong> The only published table of Didox's status codes is the one
 * in the partner SDK's {@code DocumentStatus} (npm {@code didox}); HorecaOS has no account to
 * check it against. So only the codes whose meaning is unambiguous in that table are mapped --
 * draft, sent, signed, refused, cancelled, and the two "waiting for the other signature" codes
 * -- and every other code reads as {@link EInvoiceOperatorState#UNKNOWN} with the raw code kept
 * beside it on the record, so a mapping that is wrong is visible and fixable here without
 * losing what Didox said.
 */
final class DidoxStatus {

    private DidoxStatus() {}

    static EInvoiceOperatorState of(@Nullable Integer code) {
        if (code == null) {
            return EInvoiceOperatorState.UNKNOWN;
        }
        return switch (code) {
            case 0 -> EInvoiceOperatorState.DRAFT;
            case 1, 6, 7 -> EInvoiceOperatorState.SENT;
            case 2 -> EInvoiceOperatorState.SIGNED;
            case 3 -> EInvoiceOperatorState.REFUSED;
            case 4 -> EInvoiceOperatorState.CANCELLED;
            default -> EInvoiceOperatorState.UNKNOWN;
        };
    }

    /** The code as the operator wrote it, for the record. */
    static String raw(@Nullable Integer code) {
        return code == null ? "none" : Integer.toString(code);
    }
}
