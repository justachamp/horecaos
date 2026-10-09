package uz.horecaos.platform.inventory.api;

/**
 * A disposal that cannot be carried out (ADR 0169). Thrown before anything is written; the caller's transaction
 * is left exactly as it was.
 *
 * <p>{@code DISPOSAL_EXCEEDS_ORDER_CLAIM}: the command asks to give back more units of a variant than the order
 * still holds on the shelf (held, or sold and not yet disposed of). A stable code, so the amendment that
 * called it can refuse by name.
 */
public class DisposalRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String EXCEEDS_ORDER_CLAIM = "DISPOSAL_EXCEEDS_ORDER_CLAIM";

    private final String code;

    public DisposalRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
