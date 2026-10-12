package uz.horecaos.platform.payments.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fiscal.api.FiscalEvidencePolicies;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.payments.application.SyntheticLineGate;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * The placeholder is permitted where the binding's provider environment is not production and the
 * platform's {@code fiscal.synthetic_lines_allowed} says yes (ADR 0166, Decision 6).
 *
 * <p>Both, never either. The environment is ADR 0026's approved catalogue row
 * ({@code integration.provider_environments.is_production}) of the installation the binding runs
 * through, so a tenant cannot name one: the policy is settable at {@code PLATFORM} only, and a
 * production environment refuses whatever it says. An installation that cannot be found is not a
 * sandbox. A policy that cannot be read is not permission.
 */
@Component
public class JdbcSyntheticLineGate implements SyntheticLineGate {

    private static final Logger log = LoggerFactory.getLogger(JdbcSyntheticLineGate.class);

    private final JdbcClient jdbc;
    private final PolicyResolver policies;

    public JdbcSyntheticLineGate(JdbcClient jdbc, PolicyResolver policies) {
        this.jdbc = jdbc;
        this.policies = policies;
    }

    @Override
    public boolean permitted(ProviderBinding binding) {
        boolean sandbox = jdbc.sql("""
                        SELECT NOT e.is_production
                          FROM integration.installations i
                          JOIN integration.provider_environments e ON e.code = i.environment_code
                         WHERE i.tenant_id = :tenantId AND i.id = :installationId
                        """)
                .param("tenantId", binding.tenantId())
                .param("installationId", binding.installationId())
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!sandbox) {
            return false;
        }
        try {
            return policies.resolve(FiscalEvidencePolicies.SYNTHETIC_LINES_ALLOWED, ResourceScope.platform())
                    .map(ResolvedPolicy::document)
                    .orElseGet(FiscalEvidencePolicies.SyntheticLines::platformDefault)
                    .allowed();
        } catch (RuntimeException unreadable) {
            log.error(
                    "The {} policy is unreadable; the placeholder receipt is not permitted until it is fixed.",
                    FiscalEvidencePolicies.SYNTHETIC_LINES_ALLOWED.code(),
                    unreadable);
            return false;
        }
    }
}
