package uz.horecaos.platform.reviews.api;

import java.util.Set;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument;
import uz.horecaos.platform.reviews.domain.RecoveryPolicyDocument;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/**
 * The reviews module's ADR 0030 configuration keys and policy documents (ADR 0159).
 *
 * <p><strong>The scalar keys are declared twice.</strong> The registry ADR 0030's startup validator
 * consults lives in {@code tenancy.domain.configuration}, internal to tenancy, and this module cannot
 * import it; a reference the other way would make the two cyclic. The registry carries identical
 * declarations, and {@code ReviewsConfigurationKeysTests} fails the build if the two ever drift apart --
 * the arrangement {@code OrderingConfigurationKeys} and {@code AssistantConfigurationKeys} use.
 *
 * <p>Two flags (ADR 0082), both off, are the rollout and the rollback: {@link #FEATURE_SERVICE_RECOVERY}
 * gates case creation and the worklist, {@link #FEATURE_FEEDBACK_SETTINGS} gates tags in the submission,
 * the prompt policy and every public endpoint. With both off the platform behaves exactly as it did
 * before ADR 0159, including the prompt's immediate send.
 */
public final class ReviewsConfigurationKeys {

    public static final String FEATURE_SERVICE_RECOVERY_CODE = "feature.service_recovery";
    public static final String FEATURE_FEEDBACK_SETTINGS_CODE = "feature.feedback_settings";
    public static final String PUBLIC_VISIBILITY_CODE = "feedback.public_visibility";
    public static final String PUBLIC_MINIMUM_REVIEWS_CODE = "feedback.public_minimum_reviews";
    public static final String COMMENTS_REQUIRE_APPROVAL_CODE = "feedback.comments_require_approval";

    /** The wire value of {@link #PUBLIC_VISIBILITY} when nothing is public. */
    public static final String VISIBILITY_NONE = "NONE";

    public static final int PUBLIC_MINIMUM_REVIEWS_DEFAULT = 10;
    public static final int PUBLIC_MINIMUM_REVIEWS_MAXIMUM = 1000;

    /** ADR 0159, Decision 14: gates case creation and the worklist. Off until a pilot tenant is switched on. */
    public static final ConfigurationKey<Boolean> FEATURE_SERVICE_RECOVERY = ConfigurationKey.of(
                    FEATURE_SERVICE_RECOVERY_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("reviews")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Opens a service-recovery case from a low review and shows the worklist "
                    + "(ADR 0159). Off: no new case opens; existing cases and every review stay.")
            .build();

    /** ADR 0159, Decision 14: gates tags in the submission, the prompt policy and every public endpoint. */
    public static final ConfigurationKey<Boolean> FEATURE_FEEDBACK_SETTINGS = ConfigurationKey.of(
                    FEATURE_FEEDBACK_SETTINGS_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("reviews")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Offers feedback tags with a review, governs when the rating prompt is sent, and "
                    + "serves the public review endpoints (ADR 0159). Off: the prompt is sent at once, as "
                    + "before, and nothing is public.")
            .build();

    /** NONE, RATING_ONLY or RATING_AND_COMMENTS. Nothing is public until a tenant turns it on. */
    public static final ConfigurationKey<String> PUBLIC_VISIBILITY = ConfigurationKey.of(
                    PUBLIC_VISIBILITY_CODE, String.class)
            .defaultValue(VISIBILITY_NONE)
            .ownedBy("reviews")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("How much of the brand's reviews its public page shows: NONE, RATING_ONLY or "
                    + "RATING_AND_COMMENTS (ADR 0159). Off by default.")
            .build();

    /** A single bad review is never the rating: the summary reports nothing but a flag below this count. */
    public static final ConfigurationKey<Integer> PUBLIC_MINIMUM_REVIEWS = ConfigurationKey.of(
                    PUBLIC_MINIMUM_REVIEWS_CODE, Integer.class)
            .defaultValue(PUBLIC_MINIMUM_REVIEWS_DEFAULT)
            .ownedBy("reviews")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("How many reviews a brand must have before its public summary shows an average "
                    + "(0 to 1000, ADR 0159).")
            .build();

    /** On by default: no comment appears unread. A tenant that trusts its customers may switch it off. */
    public static final ConfigurationKey<Boolean> COMMENTS_REQUIRE_APPROVAL = ConfigurationKey.of(
                    COMMENTS_REQUIRE_APPROVAL_CODE, Boolean.class)
            .defaultValue(true)
            .ownedBy("reviews")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Whether a comment the customer agreed to show publicly waits for a moderator "
                    + "before it appears (ADR 0159).")
            .build();

    public static final String FEEDBACK_PROMPT_POLICY_CODE = "reviews.feedback_prompt";
    public static final String RECOVERY_POLICY_CODE = "reviews.recovery";

    /**
     * When a customer is asked to rate a finished order (ADR 0159, Decision 4). Settable at tenant and
     * brand, never platform: when to interrupt a restaurant's customers is that restaurant's decision,
     * and a platform-wide timing would be every tenant's.
     */
    public static final PolicyKey<FeedbackPromptDocument> FEEDBACK_PROMPT_POLICY = new PolicyKey<>(
            FEEDBACK_PROMPT_POLICY_CODE,
            FeedbackPromptDocument.class,
            Set.of(ScopeType.TENANT, ScopeType.BRAND),
            "reviews",
            false,
            "Rows by order type and channel: enabled, delay and give-up time for the rating prompt, with "
                    + "one send window in the location's time zone (ADR 0159).");

    /**
     * When a review opens a service-recovery case and how long staff have (ADR 0159, Decisions 8 and
     * 10). Settable at tenant and brand; a case stores the version it used.
     */
    public static final PolicyKey<RecoveryPolicyDocument> RECOVERY_POLICY = new PolicyKey<>(
            RECOVERY_POLICY_CODE,
            RecoveryPolicyDocument.class,
            Set.of(ScopeType.TENANT, ScopeType.BRAND),
            "reviews",
            false,
            "The rating at or under which a review opens a case, the first-response and resolution "
                    + "clocks, and whether staff chats are alerted (ADR 0159).");

    private ReviewsConfigurationKeys() {}
}
