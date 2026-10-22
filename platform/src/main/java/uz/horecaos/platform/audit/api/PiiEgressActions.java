package uz.horecaos.platform.audit.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The audit action codes that mean personal data left the platform, or was opened to a person (ADR 0027, ADR 0029).
 *
 * <p>Settings 10.11 promises the merchant "an export from any screen in the console is an audited PII egress event", and the
 * egress log in the console is only as honest as the list it filters the audit trail by. That list used to live in the
 * console, written by hand against the call sites that existed the day it was written, and it had already lost six real ones:
 * a lead's number, a booking guest, a promotion redemption's customer, a report export and two courier-app reveals were all
 * emitted and none appeared in the merchant's log. A list kept in the client cannot know about a new emitter; a list in
 * the module that records the facts can be held to them by a build.
 *
 * <p>This is that list. {@code PiiEgressRegistryTests} scans the main sources for every action code that names a reveal, an
 * export or a disclosure and requires it to be registered here or excused there with a reason, and requires every code
 * registered here to still be emitted by something. The audit search filters by it ({@code piiEgress=true}), so the log can
 * never be narrower than what the platform records.
 *
 * <p>A reveal is a person being shown a protected value; an export is a file or a list that carries them out. Both are
 * egress. The subject is whose data it is, so the log can say so and a reader can tell a customer's number from a
 * courier's passport.
 */
public final class PiiEgressActions {

    /** What happened to the data. */
    public enum Kind {
        REVEAL,
        EXPORT
    }

    /** Whose data it was. */
    public enum Subject {
        CUSTOMER,
        COURIER,
        OTHER
    }

    /**
     * One registered code.
     *
     * @param module the module that emits it, for the person who has to find the call site
     */
    public record Action(String code, Kind kind, Subject subject, String module) {}

    private static final List<Action> ALL = List.of(
            // customers
            new Action("customer.contact.revealed", Kind.REVEAL, Subject.CUSTOMER, "customers"),
            new Action("customer.address.revealed", Kind.REVEAL, Subject.CUSTOMER, "customers"),
            new Action("customer.dateOfBirth.revealed", Kind.REVEAL, Subject.CUSTOMER, "customers"),
            new Action("customer.blacklist.revealed", Kind.REVEAL, Subject.CUSTOMER, "customers"),
            new Action("customer.lead.revealed", Kind.REVEAL, Subject.CUSTOMER, "customers"),
            new Action("customer.list.exported", Kind.EXPORT, Subject.CUSTOMER, "customers"),
            // marketing
            new Action("MARKETING_AUDIENCE_EXPORTED", Kind.EXPORT, Subject.CUSTOMER, "marketing"),
            // orders and what hangs off them
            new Action("order.line_note.revealed", Kind.REVEAL, Subject.CUSTOMER, "ordering"),
            new Action("order.customer_phone.revealed", Kind.REVEAL, Subject.CUSTOMER, "ordering"),
            new Action("order.customer_address.revealed", Kind.REVEAL, Subject.CUSTOMER, "ordering"),
            new Action("order.map_points.revealed", Kind.REVEAL, Subject.CUSTOMER, "ordering"),
            new Action("pricing.promotion.redemption.customer_revealed", Kind.REVEAL, Subject.CUSTOMER, "pricing"),
            new Action("fulfillment.grouping.dropoff-revealed", Kind.REVEAL, Subject.CUSTOMER, "fulfillment"),
            new Action("courier.customer_location.revealed", Kind.REVEAL, Subject.CUSTOMER, "courier"),
            new Action("fiscal.evidence.revealed", Kind.REVEAL, Subject.CUSTOMER, "fiscal"),
            // bookings, calls
            new Action("dinein.reservation.guest_revealed", Kind.REVEAL, Subject.CUSTOMER, "dinein"),
            new Action("voice.screen_pop.caller_number_revealed", Kind.REVEAL, Subject.CUSTOMER, "voice"),
            // reports
            new Action("report.export.completed", Kind.EXPORT, Subject.CUSTOMER, "reporting"),
            // couriers and others
            new Action("telemetry.courier_track.revealed", Kind.REVEAL, Subject.COURIER, "telemetry"),
            new Action("courier.registration.revealed", Kind.REVEAL, Subject.COURIER, "courier"),
            new Action("courier.compliance.revealed", Kind.REVEAL, Subject.COURIER, "courier"),
            new Action("tenant.owner_invitation.recipient_revealed", Kind.REVEAL, Subject.OTHER, "tenancy"));

    private static final Map<String, Action> BY_CODE =
            ALL.stream().collect(Collectors.toMap(Action::code, action -> action, (a, b) -> a, LinkedHashMap::new));

    private PiiEgressActions() {}

    /** Every registered action, in registry order. */
    public static List<Action> all() {
        return ALL;
    }

    /** Every registered code. */
    public static Set<String> codes() {
        return BY_CODE.keySet();
    }

    public static boolean isEgress(String actionCode) {
        return BY_CODE.containsKey(actionCode);
    }

    public static Optional<Action> find(String actionCode) {
        return Optional.ofNullable(BY_CODE.get(actionCode));
    }
}
