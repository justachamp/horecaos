package uz.qoida.platform.tenancy.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The closed, code-owned set of sales channel types (ADR 0036).
 *
 * <p>A sales channel row is tenant data — a tenant registers its third
 * aggregator without a deploy — but its <em>type</em> is code, because behaviour
 * keys on the type and a type an operator typed would be a behaviour nobody
 * implemented.
 *
 * <p><b>This list is owned here and nowhere else.</b> No other decision may
 * introduce a channel type name of its own: an ADR needing a type this list does
 * not carry amends this list, in ADR 0036, as part of being accepted. ADR 0047's
 * {@code DINE_IN_QR}, {@code DINE_IN_POS} and {@code ADMIN} are consequently not
 * here — those channels are {@link #QR_TABLE}, {@link #POS} and
 * {@link #CALL_CENTRE}.
 *
 * <p>The failure that rule prevents is the one ADR 0036 exists to end: two
 * vocabularies for one concept, where {@code POS} and some later
 * {@code DINE_IN_POS} name the same channel, behaviour keyed on the type matches
 * one and not the other, and every report grouping by type undercounts both
 * halves without anything failing.
 *
 * <p>Mirrored by {@code ck_sales_channel_system_type} in migration V0020, so a
 * row inserted outside this application cannot carry a type the code cannot
 * interpret.
 */
public enum SalesChannelSystemType {

    WEB,
    IOS,
    ANDROID,
    TELEGRAM,
    KIOSK,
    QR_TABLE,
    CALL_CENTRE,
    AGGREGATOR,
    POS;

    public static Optional<SalesChannelSystemType> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalised))
                .findFirst();
    }

    public static SalesChannelSystemType require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "Unknown sales channel system type \"%s\". The set is closed and owned by ADR 0036: %s"
                        .formatted(name, Arrays.toString(values()))));
    }
}
