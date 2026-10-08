package uz.horecaos.platform.printing.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed, code-owned set of things the platform prints (ADR 0154, Decision 2).
 *
 * <p>A tenant cannot add a type, for the reason ADR 0036 closes its channel types: behaviour keys on
 * the type. A courier slip, a table bill and a refund or correction receipt are out of this record
 * (each needs a decision it cannot take: a printed delivery address is personal data, a table bill
 * needs the session bill to be final, a refund receipt needs ADR 0048).
 */
public enum PrintDocumentType {

    /** One station's items of one ADR 0041 ticket. Prints when the ticket is released. */
    KITCHEN_TICKET(true),

    /** An order's summary, and never a fiscal document. Prints when the order is confirmed. */
    ORDER_SLIP(true),

    /** One {@code ISSUED} {@code SALE} fiscal document, printed as stored. Prints when it is issued. */
    FISCAL_RECEIPT(true),

    /** Roll width, a ruler, every glyph of the locale catalogues, a QR and the printer's name. */
    TEST_PAGE(false);

    private final boolean routable;

    PrintDocumentType(boolean routable) {
        this.routable = routable;
    }

    /** Whether a route may name this type. A test page is asked for by a person and never routed. */
    public boolean routable() {
        return routable;
    }

    public static Optional<PrintDocumentType> parse(String value) {
        return Arrays.stream(values()).filter(type -> type.name().equals(value)).findFirst();
    }
}
