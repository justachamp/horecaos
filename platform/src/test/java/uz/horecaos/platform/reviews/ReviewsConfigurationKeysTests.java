package uz.horecaos.platform.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reviews.api.ReviewsConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.ConfigurationValueRules;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The reviews module's scalar keys are declared twice and must stay one key each (ADR 0030, ADR 0082,
 * ADR 0159).
 *
 * <p>ADR 0030's registry lives inside the tenancy module and {@code reviews} cannot import it; a
 * reference the other way would make the two modules cyclic. So each declaration exists in both places,
 * and this test is what stops that from becoming two different settings with one name -- the same
 * arrangement {@code CustomerConfigurationKeys} and {@code AssistantConfigurationKeys} use.
 */
class ReviewsConfigurationKeysTests {

    private static final List<ConfigurationKey<?>> DECLARED = List.of(
            ReviewsConfigurationKeys.FEATURE_SERVICE_RECOVERY,
            ReviewsConfigurationKeys.FEATURE_FEEDBACK_SETTINGS,
            ReviewsConfigurationKeys.PUBLIC_VISIBILITY,
            ReviewsConfigurationKeys.PUBLIC_MINIMUM_REVIEWS,
            ReviewsConfigurationKeys.COMMENTS_REQUIRE_APPROVAL);

    @Test
    @DisplayName("the registry and the module declare each of the five keys identically")
    void theRegistryAndTheModuleDeclareTheSameKeys() {
        for (ConfigurationKey<?> used : DECLARED) {
            ConfigurationKey<?> registered = ConfigurationKeys.require(used.code());

            assertThat(registered.valueType()).as(used.code()).isEqualTo(used.valueType());
            assertThat(registered.defaultValue()).as(used.code()).isEqualTo(used.defaultValue());
            assertThat(registered.settableScopes()).as(used.code()).isEqualTo(used.settableScopes());
            assertThat(registered.owningModule()).as(used.code()).isEqualTo(used.owningModule());
            assertThat(registered.tenantVisible()).as(used.code()).isEqualTo(used.tenantVisible());
            assertThat(registered.explicitNullTerminates()).as(used.code()).isEqualTo(used.explicitNullTerminates());
            assertThat(registered.description()).as(used.code()).isEqualTo(used.description());
        }
    }

    @Test
    @DisplayName("the registry holds exactly the keys the module declares: a sixth added to one side fails here")
    void theRegistryHoldsNoOtherReviewsKey() {
        assertThat(ConfigurationKeys.all())
                .filteredOn(key -> "reviews".equals(key.owningModule()))
                .extracting(ConfigurationKey::code)
                .containsExactlyInAnyOrderElementsOf(
                        DECLARED.stream().map(ConfigurationKey::code).toList());
    }

    @Test
    @DisplayName("nothing is on, nothing is public and no comment appears unread until a tenant says otherwise")
    void theDefaultsAreTheConservativeOnes() {
        assertThat(ReviewsConfigurationKeys.FEATURE_SERVICE_RECOVERY.defaultValue())
                .isEqualTo(false);
        assertThat(ReviewsConfigurationKeys.FEATURE_FEEDBACK_SETTINGS.defaultValue())
                .isEqualTo(false);
        assertThat(ReviewsConfigurationKeys.PUBLIC_VISIBILITY.defaultValue()).isEqualTo("NONE");
        assertThat(ReviewsConfigurationKeys.PUBLIC_MINIMUM_REVIEWS.defaultValue())
                .isEqualTo(10)
                .isEqualTo(ReviewsConfigurationKeys.PUBLIC_MINIMUM_REVIEWS_DEFAULT);
        assertThat(ReviewsConfigurationKeys.COMMENTS_REQUIRE_APPROVAL.defaultValue())
                .isEqualTo(true);
    }

    @Test
    @DisplayName("the flags are platform or tenant; the public settings reach down to a brand and no further")
    void theScopes() {
        assertThat(ReviewsConfigurationKeys.FEATURE_SERVICE_RECOVERY.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(ReviewsConfigurationKeys.FEATURE_FEEDBACK_SETTINGS.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        for (ConfigurationKey<?> key : List.of(
                ReviewsConfigurationKeys.PUBLIC_VISIBILITY,
                ReviewsConfigurationKeys.PUBLIC_MINIMUM_REVIEWS,
                ReviewsConfigurationKeys.COMMENTS_REQUIRE_APPROVAL)) {
            assertThat(key.settableScopes())
                    .as("%s: a brand's page is the brand's to decide, a location's is not a page", key.code())
                    .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
                    .doesNotContain(ScopeType.LOCATION);
        }
    }

    @Test
    @DisplayName("the writer refuses a visibility the page does not have and a minimum count out of range")
    void theWriterAppliesTheRules() {
        for (String ok : List.of("NONE", "RATING_ONLY", "RATING_AND_COMMENTS")) {
            assertThatCode(() -> validate("feedback.public_visibility", ok)).doesNotThrowAnyException();
        }
        for (String bad : List.of("ALL", "rating_only", "", "COMMENTS_ONLY")) {
            assertThatThrownBy(() -> validate("feedback.public_visibility", bad))
                    .as(bad)
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        for (int ok : new int[] {0, 1, 10, 1000}) {
            assertThatCode(() -> validate("feedback.public_minimum_reviews", ok))
                    .doesNotThrowAnyException();
        }
        for (int bad : new int[] {-1, 1001, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> validate("feedback.public_minimum_reviews", bad))
                    .as(Integer.toString(bad))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(ReviewsConfigurationKeys.PUBLIC_MINIMUM_REVIEWS_MAXIMUM).isEqualTo(1000);
    }

    private static void validate(String code, Object value) {
        ConfigurationValueRules.validate(ConfigurationKeys.require(code), value);
    }
}
