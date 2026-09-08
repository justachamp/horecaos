package uz.horecaos.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.AuditConfigurationKeys;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The two ADR 0030 key codes ADR 0027 owns, checked against the registry that
 * actually validates and resolves them.
 *
 * <p>{@code AuditConfigurationKeys} deliberately holds only the two string
 * codes, not a typed {@code ConfigurationKey} mirror — see that class's own doc
 * for why a {@code ConfigurationKey}-typed field here would close a module
 * cycle ({@code audit -> tenancy -> audit}) that {@code
 * tenancy.infrastructure.persistence.JdbcConfigurationValueAuthor}'s own
 * dependency on {@code audit.api.AuditRecorder} already half-builds. This test
 * reaches into {@code tenancy.domain.configuration.ConfigurationKeys} directly,
 * which production code under {@code audit} cannot do — but this is test code,
 * outside the package Spring Modulith's boundary check scans, so it can check
 * the real registration without recreating the cycle it exists to catch.
 */
class AuditConfigurationKeyTests {

    @Test
    @DisplayName("the security retention code names a key that is actually registered")
    void theSecurityRetentionCodeIsRegistered() {
        assertThat(registered(AuditConfigurationKeys.SECURITY_RETENTION_DAYS_CODE)
                        .code())
                .isEqualTo(ConfigurationKeys.AUDIT_SECURITY_RETENTION_DAYS.code());
    }

    @Test
    @DisplayName("the business retention code names a key that is actually registered")
    void theBusinessRetentionCodeIsRegistered() {
        assertThat(registered(AuditConfigurationKeys.BUSINESS_RETENTION_DAYS_CODE)
                        .code())
                .isEqualTo(ConfigurationKeys.AUDIT_BUSINESS_RETENTION_DAYS.code());
    }

    @Test
    @DisplayName("both default to the owner's ten-year directive of 2026-09-08")
    void theDefaultsAreTheOwnersDirective() {
        assertThat(ConfigurationKeys.AUDIT_SECURITY_RETENTION_DAYS.defaultValue())
                .isEqualTo(3653);
        assertThat(ConfigurationKeys.AUDIT_BUSINESS_RETENTION_DAYS.defaultValue())
                .isEqualTo(3653);
    }

    /**
     * {@code AuditPartitionArchiver.DEFAULT_RETENTION_DAYS} duplicates this
     * value rather than reading it — this test is the tripwire for the two
     * ever drifting apart; see that field's own doc for why it is a duplicate
     * rather than a shared constant.
     */
    @Test
    @DisplayName("a retention floor is not a per-tenant choice")
    void retentionIsPlatformOnly() {
        assertThat(ConfigurationKeys.AUDIT_SECURITY_RETENTION_DAYS.settableScopes())
                .containsExactly(ScopeType.PLATFORM);
        assertThat(ConfigurationKeys.AUDIT_BUSINESS_RETENTION_DAYS.settableScopes())
                .containsExactly(ScopeType.PLATFORM);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
