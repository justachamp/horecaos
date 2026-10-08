package uz.horecaos.platform.integration.camel.notification;

import java.util.List;

/**
 * Whether an installation carries what a gateway needs, found without calling
 * out (ADR 0146 Decision 2, {@code account}).
 *
 * <p>A missing sender is a configuration finding an operator reads in the
 * console, not a refusal a customer's sign-in screen discovers: this provider
 * answers {@code 15 sender required} only after the credential has been put on
 * the wire for nothing.
 */
public record AccountReadiness(boolean complete, List<String> missingFields) {

    private static final AccountReadiness COMPLETE = new AccountReadiness(true, List.of());

    public AccountReadiness {
        missingFields = List.copyOf(missingFields);
    }

    public static AccountReadiness satisfied() {
        return COMPLETE;
    }

    public static AccountReadiness missing(List<String> fields) {
        return fields.isEmpty() ? COMPLETE : new AccountReadiness(false, fields);
    }
}
