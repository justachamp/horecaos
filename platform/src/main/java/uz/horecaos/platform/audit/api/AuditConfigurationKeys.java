package uz.horecaos.platform.audit.api;

/**
 * The two ADR 0030 key codes {@code audit} owns (ADR 0027).
 *
 * <p>String codes only, deliberately, and not the typed {@code ConfigurationKey}
 * declaration {@code TelemetryConfigurationKeys} and {@code CommercialConfigurationKeys}
 * each carry for the keys they read through {@code tenancy.api.ConfigurationResolver}.
 * That type lives in {@code tenancy.api}, and {@code tenancy} already depends on
 * {@code audit.api} — {@code JdbcConfigurationValueAuthor} records an ADR 0027 fact
 * on every configuration write — so a {@code ConfigurationKey}-typed field here
 * would close a cycle Spring Modulith refuses to build:
 * {@code audit -> tenancy -> audit}. {@code ModularArchitectureTests} caught exactly
 * this the first time {@link uz.horecaos.platform.audit.infrastructure.persistence.AuditPartitionArchiver}
 * took a {@code ConfigurationResolver} constructor parameter.
 *
 * <p>Both keys are platform-scope-only, so nothing here needs the resolver's scope
 * chain anyway. {@code AuditPartitionArchiver} reads the platform row directly with
 * plain SQL against {@code tenant.configuration_values} — the same escape hatch
 * {@code TrackRetentionSweeper.effectiveRetentionDays()} already uses for the
 * identical reason: "the resolver answers what applies at this scope, and a
 * partition has no scope — it holds every tenant's rows at once." The two codes
 * are still declared once here so the SQL and the tests share one literal rather
 * than three copies of a string that must match {@code
 * tenancy.domain.configuration.ConfigurationKeys}' own registration.
 */
public final class AuditConfigurationKeys {

    public static final String SECURITY_RETENTION_DAYS_CODE = "audit.security_retention_days";

    public static final String BUSINESS_RETENTION_DAYS_CODE = "audit.business_retention_days";

    private AuditConfigurationKeys() {}
}
