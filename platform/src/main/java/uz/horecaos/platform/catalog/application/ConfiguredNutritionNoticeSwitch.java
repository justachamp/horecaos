package uz.horecaos.platform.catalog.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.CatalogConfigurationKeys;
import uz.horecaos.platform.catalog.api.NutritionNoticeSwitch;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * {@link NutritionNoticeSwitch} read from the ADR 0030 key {@code catalog.nutrition_accuracy_line_enabled},
 * resolved at the brand (so a brand, its tenant or the platform can set it, the nearest winning). Off unless
 * something in that chain says on.
 */
@Component
public class ConfiguredNutritionNoticeSwitch implements NutritionNoticeSwitch {

    private final ConfigurationResolver configuration;

    public ConfiguredNutritionNoticeSwitch(ConfigurationResolver configuration) {
        this.configuration = configuration;
    }

    @Override
    public boolean enabled(UUID tenantId, UUID brandId) {
        return Boolean.TRUE.equals(configuration.value(
                CatalogConfigurationKeys.NUTRITION_ACCURACY_LINE_ENABLED, ResourceScope.brand(tenantId, brandId)));
    }
}
