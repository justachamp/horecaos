package uz.horecaos.platform.printing.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.printing.api.PrintingConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * The ADR 0030 keys and the ADR 0082 flag printing reads, resolved at a location (ADR 0154).
 *
 * <p>A key that resolves to nothing, or to something out of range, falls back to its declared default
 * rather than to a value that would print nothing or never expire: the registry refuses a bad write, and
 * this refuses a bad read too.
 */
@Component
public class PrintingPolicy {

    private final ConfigurationResolver configuration;

    public PrintingPolicy(ConfigurationResolver configuration) {
        this.configuration = configuration;
    }

    /** Whether this tenant's branches print at all. Off until a person switches it on for the tenant. */
    public boolean enabled(UUID tenantId, UUID brandId, UUID locationId) {
        Boolean enabled = configuration.value(
                PrintingConfigurationKeys.FEATURE_PRINTING, ResourceScope.location(tenantId, brandId, locationId));
        return Boolean.TRUE.equals(enabled);
    }

    /** The same switch read at a brand, for a brand-level setting that has no branch in its path. */
    public boolean enabledForBrand(UUID tenantId, UUID brandId) {
        Boolean enabled =
                configuration.value(PrintingConfigurationKeys.FEATURE_PRINTING, ResourceScope.brand(tenantId, brandId));
        return Boolean.TRUE.equals(enabled);
    }

    /** The one language a document at this location is written in, defaulting to Russian. */
    public String documentLocale(UUID tenantId, UUID brandId, UUID locationId) {
        String locale = configuration.value(
                PrintingConfigurationKeys.DOCUMENT_LOCALE, ResourceScope.location(tenantId, brandId, locationId));
        return locale != null && PrintingConfigurationKeys.DOCUMENT_LOCALES.contains(locale) ? locale : "ru";
    }

    /** Minutes after queueing at which a waiting kitchen ticket is dropped, 5 to 240. */
    public int kitchenTicketExpireMinutes(UUID tenantId, UUID brandId, UUID locationId) {
        return bounded(
                PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES, tenantId, brandId, locationId, 5, 240, 30);
    }

    /** Minutes of waiting beyond which the paper says «delayed», 1 to 1440. */
    public int lateMarkerMinutes(UUID tenantId, UUID brandId, UUID locationId) {
        return bounded(PrintingConfigurationKeys.LATE_MARKER_MINUTES, tenantId, brandId, locationId, 1, 1_440, 5);
    }

    private int bounded(
            ConfigurationKey<Integer> key,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            int minimum,
            int maximum,
            int fallback) {
        Integer value = configuration.value(key, ResourceScope.location(tenantId, brandId, locationId));
        return value == null || value < minimum || value > maximum ? fallback : value;
    }
}
