package uz.horecaos.platform.workforce.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The workforce module's ADR 0030 configuration keys (ADR 0156, Specification: Policy keys).
 *
 * <p><strong>Declared twice</strong>, for the reason {@code CourierConfigurationKeys} gives: the
 * registry the startup validator consults lives in the tenancy module's internals and the registry
 * carries an identical declaration. {@code WorkforceConfigurationKeyTests} fails the build if the
 * two ever drift apart.
 *
 * <p>The defaults are the ADR's proposed defaults, which the owner accepted on 2026-10-07. None of
 * them is a legal working-time rule: the platform records hours and enforces none of those rules
 * (ADR 0156, first open input), and the sixteen-hour bound in the schema is a data-sanity check.
 */
public final class WorkforceConfigurationKeys {

    public static final String ATTENDANCE_ENABLED_CODE = "workforce.attendance.enabled";
    public static final String VARIANCE_THRESHOLD_MINUTES_CODE = "workforce.shift.variance_threshold_minutes";
    public static final String APPROVER_MUST_DIFFER_CODE = "workforce.shift.approver_must_differ";
    public static final String RETROACTIVE_EDIT_DAYS_CODE = "workforce.shift.retroactive_edit_days";
    public static final String MAX_OPEN_HOURS_CODE = "workforce.shift.max_open_hours";
    public static final String LATE_AFTER_MINUTES_CODE = "workforce.roster.late_after_minutes";
    public static final String MATCH_WINDOW_MINUTES_CODE = "workforce.roster.match_window_minutes";

    /**
     * Whether a tenant has opted in to recording staff hours. Off for every tenant until the tenant,
     * having read the notice legal supplies, turns it on: recording a person's hours may be workplace
     * monitoring that needs the tenant to notify its staff first (ADR 0156, third open input). While
     * it is false the workforce routes answer {@code RESOURCE_NOT_FOUND}, the screens are absent and
     * the export refuses. Tenant scope only: a branch cannot opt itself in under its tenant.
     */
    public static final ConfigurationKey<Boolean> ATTENDANCE_ENABLED = ConfigurationKey.of(
                    ATTENDANCE_ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("workforce")
            .tenantVisible()
            .settableAt(ScopeType.TENANT)
            .describedAs("Whether this company records its staff's working hours (rota, clock-in and "
                    + "timesheet). Off until switched on; switching it on is an audited change.")
            .build();

    /** Minutes of difference between paid and planned time over which a closed shift waits for approval. */
    public static final ConfigurationKey<Integer> VARIANCE_THRESHOLD_MINUTES = ConfigurationKey.of(
                    VARIANCE_THRESHOLD_MINUTES_CODE, Integer.class)
            .defaultValue(30)
            .ownedBy("workforce")
            .tenantVisible()
            .settableAt(ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION)
            .describedAs("Minutes a closed shift may differ from its plan before a manager must approve it. "
                    + "Between 5 and 240.")
            .build();

    /** Whether the person who edited a shift's hours may not also approve its variance. */
    public static final ConfigurationKey<Boolean> APPROVER_MUST_DIFFER = ConfigurationKey.of(
                    APPROVER_MUST_DIFFER_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("workforce")
            .tenantVisible()
            .settableAt(ScopeType.TENANT)
            .describedAs("Whether a different person must approve a shift's variance than edited it. Off, "
                    + "because a branch with one manager could otherwise approve nothing.")
            .build();

    /** Days after which only a holder of the approve capability may correct a shift. */
    public static final ConfigurationKey<Integer> RETROACTIVE_EDIT_DAYS = ConfigurationKey.of(
                    RETROACTIVE_EDIT_DAYS_CODE, Integer.class)
            .defaultValue(7)
            .ownedBy("workforce")
            .tenantVisible()
            .settableAt(ScopeType.TENANT)
            .describedAs("How many days back a branch manager may correct a shift; older ones need the "
                    + "approval capability. Between 0 and 60.")
            .build();

    /** Hours a shift may stay open before the manager's screen flags it. It is never closed by a job. */
    public static final ConfigurationKey<Integer> MAX_OPEN_HOURS = ConfigurationKey.of(
                    MAX_OPEN_HOURS_CODE, Integer.class)
            .defaultValue(14)
            .ownedBy("workforce")
            .tenantVisible()
            .settableAt(ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION)
            .describedAs("Hours after which a still-open shift is flagged as not closed. Between 4 and 16.")
            .build();

    /** Minutes after a published entry's start with no shift before it reads as late. */
    public static final ConfigurationKey<Integer> LATE_AFTER_MINUTES = ConfigurationKey.of(
                    LATE_AFTER_MINUTES_CODE, Integer.class)
            .defaultValue(10)
            .ownedBy("workforce")
            .tenantVisible()
            .settableAt(ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION)
            .describedAs("Minutes after the planned start with no shift before a person reads as late. "
                    + "Between 1 and 120.")
            .build();

    /** How near a roster entry's start a shift must begin to be matched to it. */
    public static final ConfigurationKey<Integer> MATCH_WINDOW_MINUTES = ConfigurationKey.of(
                    MATCH_WINDOW_MINUTES_CODE, Integer.class)
            .defaultValue(240)
            .ownedBy("workforce")
            .tenantVisible()
            .settableAt(ScopeType.TENANT)
            .describedAs("Minutes either side of a published entry's start within which a new shift is "
                    + "matched to it. Between 30 and 480.")
            .build();

    private WorkforceConfigurationKeys() {}
}
