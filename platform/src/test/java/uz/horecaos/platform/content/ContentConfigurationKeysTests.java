package uz.horecaos.platform.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.content.api.ContentConfigurationKeys;
import uz.horecaos.platform.content.api.ContentPolicies;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The content module's keys are declared twice -- in the module that consumes them and in the tenancy
 * registry the startup validator reads -- and must stay one key (ADR 0030), the arrangement
 * {@code AssistantConfigurationKeysTests} holds for the assistant.
 */
class ContentConfigurationKeysTests {

    private static void sameKey(ConfigurationKey<?> registered, ConfigurationKey<?> used) {
        assertThat(registered.valueType()).isEqualTo(used.valueType());
        assertThat(registered.defaultValue()).isEqualTo(used.defaultValue());
        assertThat(registered.settableScopes()).isEqualTo(used.settableScopes());
        assertThat(registered.owningModule()).isEqualTo(used.owningModule());
        assertThat(registered.tenantVisible()).isEqualTo(used.tenantVisible());
        assertThat(registered.explicitNullTerminates()).isEqualTo(used.explicitNullTerminates());
    }

    @Test
    @DisplayName("the registry and the content module declare the same two keys")
    void theRegistryAndTheModuleAgree() {
        sameKey(
                ConfigurationKeys.require(ContentConfigurationKeys.REVISION_MEDIA_RETENTION_DAYS_CODE),
                ContentConfigurationKeys.REVISION_MEDIA_RETENTION_DAYS);
        sameKey(
                ConfigurationKeys.require(ContentConfigurationKeys.VIDEO_MAX_LIVE_PER_BRAND_CODE),
                ContentConfigurationKeys.VIDEO_MAX_LIVE_PER_BRAND);
    }

    @Test
    @DisplayName(
            "the defaults are the ones the ADR names: media released 90 days after supersession, five live videos a brand")
    void theDefaults() {
        assertThat(ContentConfigurationKeys.REVISION_MEDIA_RETENTION_DAYS.defaultValue())
                .isEqualTo(90);
        assertThat(ContentConfigurationKeys.DEFAULT_REVISION_MEDIA_RETENTION_DAYS)
                .isEqualTo(90);
        assertThat(ContentConfigurationKeys.VIDEO_MAX_LIVE_PER_BRAND.defaultValue())
                .isEqualTo(5);
        assertThat(ContentConfigurationKeys.DEFAULT_VIDEO_MAX_LIVE_PER_BRAND).isEqualTo(5);
        assertThat(ContentConfigurationKeys.REVISION_MEDIA_RETENTION_DAYS.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(ContentConfigurationKeys.REVISION_MEDIA_RETENTION_DAYS.owningModule())
                .isEqualTo("content");
    }

    @Test
    @DisplayName("both policies are brand-scoped documents with every kind and flag off by default")
    void thePolicies() {
        assertThat(ContentPolicies.PUBLISHING.settableScopes()).containsExactly(ScopeType.BRAND);
        assertThat(ContentPolicies.EDITORIAL.settableScopes()).containsExactly(ScopeType.BRAND);
        assertThat(ContentPolicies.PUBLISHING.code()).isEqualTo("content.publishing");
        assertThat(ContentPolicies.EDITORIAL.code()).isEqualTo("content.editorial");
        assertThat(ContentPolicies.PublishingPolicy.platformDefault().allBrandLocalesRequired())
                .isFalse();
        var editorial = ContentPolicies.EditorialPolicy.platformDefault();
        assertThat(editorial.newsOn() || editorial.galleryOn() || editorial.recipesOn() || editorial.vacanciesOn())
                .as("all four editorial kinds are off until a tenant turns one on")
                .isFalse();
        // A missing flag in a stored document reads as off, never as an error.
        assertThat(new ContentPolicies.EditorialPolicy(null, null, null, null).newsOn())
                .isFalse();
        assertThat(new ContentPolicies.PublishingPolicy(null).allBrandLocalesRequired())
                .isFalse();
    }
}
