package uz.horecaos.platform.printing.api;

import java.util.List;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The printing module's ADR 0030 configuration keys and its ADR 0082 flag.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in
 * {@code tenancy.domain.configuration}, which is internal to the tenancy module; importing it here is
 * not possible and importing this from there would make the two modules cyclic. The registry therefore
 * carries an identical declaration, and {@code PrintingConfigurationKeyTests} fails the build if the two
 * ever drift apart, the same arrangement the kitchen's and every other module's keys use.
 */
public final class PrintingConfigurationKeys {

    private PrintingConfigurationKeys() {}

    /** The code both declarations of the flag share. */
    public static final String FEATURE_PRINTING_CODE = "feature.printing";

    public static final String DOCUMENT_LOCALE_CODE = "printing.document_locale";

    public static final String KITCHEN_TICKET_EXPIRE_MINUTES_CODE = "printing.kitchen_ticket.expire_minutes";

    public static final String LATE_MARKER_MINUTES_CODE = "printing.late_marker_minutes";

    /**
     * The languages a document can print in: the three the platform's content tier serves (ADR 0149).
     * A fourth is a catalogue and a glyph audit of the bundled face first (ADR 0154, Decision 3), so
     * the registry's own rule restates this list rather than reading the language registry.
     */
    public static final List<String> DOCUMENT_LOCALES = List.of("ru", "uz-Latn", "en");

    /**
     * ADR 0154, ADR 0082: whether a tenant's branches print. Off until turned on by hand, per tenant
     * (a module is a later price-list entry, ADR 0087). With it off nothing is created: no listener
     * makes a job, the sweeper makes none, and the endpoints answer for a tenant that has not switched
     * it on with an empty queue rather than an error, so the screens can say it is off.
     */
    public static final ConfigurationKey<Boolean> FEATURE_PRINTING = ConfigurationKey.of(
                    FEATURE_PRINTING_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("printing")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Lets a tenant's branches print kitchen tickets, order slips and fiscal receipts "
                    + "through a print agent or a browser.")
            .build();

    /**
     * The language one document prints in: the location's own, never the customer's (ADR 0154,
     * Open inputs). One language per document, so a slip is never half Russian and half Uzbek.
     */
    public static final ConfigurationKey<String> DOCUMENT_LOCALE = ConfigurationKey.of(
                    DOCUMENT_LOCALE_CODE, String.class)
            .defaultValue("ru")
            .ownedBy("printing")
            .tenantVisible()
            .describedAs("The language printed documents are written in at a branch: ru, uz-Latn or en.")
            .build();

    /**
     * Minutes after it was queued at which a kitchen ticket still waiting for its printer is expired
     * rather than printed stale (ADR 0154, Decision 7). The other document types do not expire for
     * twenty-four hours.
     */
    public static final ConfigurationKey<Integer> KITCHEN_TICKET_EXPIRE_MINUTES = ConfigurationKey.of(
                    KITCHEN_TICKET_EXPIRE_MINUTES_CODE, Integer.class)
            .defaultValue(30)
            .ownedBy("printing")
            .tenantVisible()
            .describedAs("Minutes after which a kitchen ticket still waiting for its printer is dropped "
                    + "instead of printing stale (5 to 240).")
            .build();

    /**
     * Minutes after it was queued beyond which a document's paper says «delayed» with the time it was
     * queued (ADR 0154, Decision 7): an offline printer stops being claimed from, so a job that only
     * waited prints as attempt 1 and needs this line to say so.
     */
    public static final ConfigurationKey<Integer> LATE_MARKER_MINUTES = ConfigurationKey.of(
                    LATE_MARKER_MINUTES_CODE, Integer.class)
            .defaultValue(5)
            .ownedBy("printing")
            .tenantVisible()
            .describedAs(
                    "Minutes after being queued beyond which a printed document is marked delayed " + "(1 to 1440).")
            .build();
}
