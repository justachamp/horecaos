package uz.qoida.platform.commercial.application;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uz.qoida.platform.commercial.api.CommercialConfigurationKeys;
import uz.qoida.platform.commercial.api.EnforcementMode;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.tenancy.api.ConfigurationResolver;

/**
 * How much enforcement a tenant is currently subject to (ADR 0021, ADR 0030).
 *
 * <p>Deliberately thin. All it does is read one ADR 0030 configuration value and
 * turn it into a mode, because the whole design intent is that enforcement is a
 * setting somebody turns up rather than a property of the code. A second
 * resolution mechanism here would have meant a second place to look when a
 * tenant is enforced differently from what anyone expects.
 *
 * <p>An unreadable or nonsense value resolves to {@link EnforcementMode#METER_ONLY}
 * rather than failing. That direction is not laziness: this is called on request
 * paths, and a typo in a configuration row must not be able to take a
 * restaurant's ordering down. The typo is logged and the platform measures.
 */
@Component
public class EnforcementCeiling {

    private static final Logger log = LoggerFactory.getLogger(EnforcementCeiling.class);

    private final ConfigurationResolver configuration;

    public EnforcementCeiling(ConfigurationResolver configuration) {
        this.configuration = configuration;
    }

    public EnforcementMode forTenant(UUID tenantId) {
        String configured = configuration.value(
                CommercialConfigurationKeys.ENFORCEMENT_CEILING, ResourceScope.tenant(tenantId));

        if (configured == null || configured.isBlank()) {
            return EnforcementMode.METER_ONLY;
        }
        try {
            return EnforcementMode.valueOf(configured.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // No tenant identifier in the message: this is a platform
            // misconfiguration and the value is the diagnostic (ADR 0029).
            log.warn("Unknown commercial.enforcement_ceiling value \"{}\"; measuring only", configured);
            return EnforcementMode.METER_ONLY;
        }
    }
}
