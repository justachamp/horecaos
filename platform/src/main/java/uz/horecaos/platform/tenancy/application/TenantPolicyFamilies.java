package uz.horecaos.platform.tenancy.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The versioned-policy families a tenant sets from its own console, listed by key code (ADR 0030,
 * settings.md §10.0: «Find a setting» searches "plus policy keys").
 *
 * <p>There is no registry of {@code PolicyKey}s: each module declares its own beside the code that reads it,
 * and {@code tenancy} cannot import them (the modules depend on it, not the other way round). So the list is
 * curated here, by code, and {@code TenantPolicyFamiliesTests} reads every {@code PolicyKey} constant the
 * build declares and fails the build when one is neither {@link #LISTED} nor {@link #NOT_FOR_TENANTS} -- a new
 * policy cannot be added without someone deciding whether the tenant can find it.
 *
 * <p>A family listed here is not a promise of a console screen; the console's own table names the screen of
 * each, and its own parity spec holds that table to this list.
 */
public final class TenantPolicyFamilies {

    /** The families a tenant-scope holder can set, in the order the console lists them. */
    public static final List<String> LISTED = List.of(
            "ordering.acceptance",
            "ordering.lateness",
            "ordering.payment_window",
            "fulfillment.sourcing",
            "fulfillment.delivery_subsidy",
            "fulfillment.dispatch_rules",
            "courier.compensation",
            "kitchen.paid_hold",
            "kitchen.station_productivity",
            "fiscal.evidence_retention_years",
            "fiscal.reporting_deadline",
            "content.publishing",
            "content.editorial",
            "reviews.feedback_prompt",
            "reviews.recovery");

    /** Policies that are declared and deliberately not offered to a tenant, with the reason. */
    public static final Map<String, String> NOT_FOR_TENANTS = Map.of(
            "fiscal.synthetic_lines_allowed",
            "Settable at PLATFORM only: whether the platform lets a receipt carry synthetic lines is not a tenant's call.",
            "loyalty.adjustment_reason_codes",
            "Settable at PLATFORM only: the reason-code vocabulary for balance adjustments is the platform's.");

    private static final Set<String> LISTED_SET = Set.copyOf(LISTED);

    private TenantPolicyFamilies() {}

    public static boolean isListed(String code) {
        return LISTED_SET.contains(code);
    }
}
