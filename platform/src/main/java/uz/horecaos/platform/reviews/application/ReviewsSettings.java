package uz.horecaos.platform.reviews.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.reviews.api.ReviewsConfigurationKeys;
import uz.horecaos.platform.reviews.domain.PublicVisibility;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Reads the reviews module's ADR 0030 settings and ADR 0082 flags in one place (ADR 0159).
 *
 * <p>The flags resolve at the tenant (platform value, then the tenant's own); the public-display
 * settings resolve at the brand, which can override the tenant, which can override the platform. A flag
 * that cannot be resolved is off, and an unrecognised visibility is {@code NONE}: when in doubt, a
 * review stays inside operations, exactly as it did before ADR 0159.
 */
@Component
public class ReviewsSettings {

    private final ConfigurationResolver configuration;

    public ReviewsSettings(ConfigurationResolver configuration) {
        this.configuration = configuration;
    }

    /** Whether a low review opens a case and the worklist is shown ({@code feature.service_recovery}). */
    public boolean serviceRecoveryOn(UUID tenantId) {
        return Boolean.TRUE.equals(
                configuration.value(ReviewsConfigurationKeys.FEATURE_SERVICE_RECOVERY, ResourceScope.tenant(tenantId)));
    }

    /** Whether tags, the prompt policy and the public endpoints are on ({@code feature.feedback_settings}). */
    public boolean feedbackSettingsOn(UUID tenantId) {
        return Boolean.TRUE.equals(configuration.value(
                ReviewsConfigurationKeys.FEATURE_FEEDBACK_SETTINGS, ResourceScope.tenant(tenantId)));
    }

    /** How much of the brand's reviews its public page may show; {@code NONE} unless the flag and the setting say more. */
    public PublicVisibility publicVisibility(UUID tenantId, UUID brandId) {
        if (!feedbackSettingsOn(tenantId)) {
            return PublicVisibility.NONE;
        }
        String value =
                configuration.value(ReviewsConfigurationKeys.PUBLIC_VISIBILITY, ResourceScope.brand(tenantId, brandId));
        return value == null
                ? PublicVisibility.NONE
                : PublicVisibility.parse(value).orElse(PublicVisibility.NONE);
    }

    /** The count a brand needs before its public summary shows an average. */
    public int publicMinimumReviews(UUID tenantId, UUID brandId) {
        Integer value = configuration.value(
                ReviewsConfigurationKeys.PUBLIC_MINIMUM_REVIEWS, ResourceScope.brand(tenantId, brandId));
        return value == null ? ReviewsConfigurationKeys.PUBLIC_MINIMUM_REVIEWS_DEFAULT : value;
    }

    /** Whether a comment the customer agreed to share waits for a moderator. On unless the brand says otherwise. */
    public boolean commentsRequireApproval(UUID tenantId, UUID brandId) {
        Boolean value = configuration.value(
                ReviewsConfigurationKeys.COMMENTS_REQUIRE_APPROVAL, ResourceScope.brand(tenantId, brandId));
        return value == null || value;
    }
}
