package uz.horecaos.platform.iam.application.passwordresets;

/**
 * Which staff console a reset was asked from (ADR 0098), and therefore which
 * origin its emailed link points at.
 *
 * <p>Stored on the row rather than derived later: the operator asked from a
 * screen, and the link has to come back to that screen. A control-plane
 * operator sent to the operations console would arrive somewhere they may have
 * no access to at all.
 */
public enum StaffConsole {
    CONTROL_PLANE,
    OPERATIONS;

    /** The path segment this console is published under (ADR 0062, ADR 0057). */
    public String prefix() {
        return this == CONTROL_PLANE ? "control-plane" : "operations";
    }
}
