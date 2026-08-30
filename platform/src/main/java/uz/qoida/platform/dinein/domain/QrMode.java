package uz.qoida.platform.dinein.domain;

import java.util.Arrays;
import java.util.Locale;

import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * What a scanned table code is allowed to do at a branch (ADR 0047).
 *
 * <p>Configured per location and never per table. ADR 0047's physical sketch puts
 * the mode on each table row and its decision text says it is configured per
 * location; this module takes the decision text, because a room where one square
 * of card can order and the next can only read the menu is not a configuration
 * any restaurant means.
 */
public enum QrMode {

    /** The published dine-in menu behind a token. Qoida creates nothing. */
    VIEW_ONLY(true),

    /** A cart bound to the table, checkout, and the running bill. */
    ORDER_AND_PAY(true),

    /**
     * The waiter's open check, read from the POS and settled through Click or
     * Payme, with no Qoida order created.
     *
     * <p>Declared and refused. ADR 0047's rollout leaves it disabled until the
     * fiscal open input closes and one POS adapter passes contract tests for both
     * new ADR 0011 ports, and neither has happened. The value exists here rather
     * than being omitted so that an operator who asks for it is told why, and so
     * that the name means one thing when the adapter does arrive; the database
     * refuses it as well, in V0034's CHECK on the mode column.
     */
    SETTLE_OPEN_TICKET(false);

    private final boolean selectable;

    QrMode(boolean selectable) {
        this.selectable = selectable;
    }

    /** Whether a branch may currently be configured into this mode. */
    public boolean selectable() {
        return selectable;
    }

    public static QrMode require(String value) {
        QrMode mode = Arrays.stream(values())
                .filter(candidate -> candidate.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Unknown QR mode \"%s\"".formatted(value)));

        if (!mode.selectable()) {
            // ADR 0011's rule: an unsupported provider capability may never be the
            // sole business path. Failing here, at configuration, is the whole
            // point — the alternative failure is a guest holding a phone at a
            // table with a bill in front of them.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "QR mode %s needs a bound POS declaring both an open-ticket read and a "
                            .formatted(mode)
                            + "ticket settlement, and no adapter declares either. The mode is "
                            + "documented and disabled (ADR 0047).");
        }
        return mode;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
