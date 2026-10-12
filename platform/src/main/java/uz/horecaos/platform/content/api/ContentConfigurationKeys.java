package uz.horecaos.platform.content.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The content module's ADR 0030 configuration keys (ADR 0157, Entitlements, policy and
 * configuration keys).
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in
 * {@code tenancy.domain.configuration}, internal to the tenancy module, and this module cannot
 * import it; a reference the other way would make the two cyclic. The registry carries an identical
 * declaration, and {@code ContentConfigurationKeysTests} fails the build if the two ever drift
 * apart -- the arrangement {@code AssistantConfigurationKeys} already uses.
 */
public final class ContentConfigurationKeys {

    public static final String REVISION_MEDIA_RETENTION_DAYS_CODE = "content.revision_media_retention_days";
    public static final String VIDEO_MAX_LIVE_PER_BRAND_CODE = "content.video.max_live_per_brand";

    /** The default of {@link #REVISION_MEDIA_RETENTION_DAYS}, as a primitive for a caller that must not unbox a null. */
    public static final int DEFAULT_REVISION_MEDIA_RETENTION_DAYS = 90;

    /** The default of {@link #VIDEO_MAX_LIVE_PER_BRAND}. */
    public static final int DEFAULT_VIDEO_MAX_LIVE_PER_BRAND = 5;

    /**
     * How long the media owned only by a superseded revision is kept before the sweeper asks for it
     * to be released. Revision rows themselves are kept indefinitely: they are small text and the
     * audit evidence of what customers were shown. Republishing an older revision whose media was
     * released is refused with a stated reason ({@code CONTENT_REVISION_MEDIA_RELEASED}).
     */
    public static final ConfigurationKey<Integer> REVISION_MEDIA_RETENTION_DAYS = ConfigurationKey.of(
                    REVISION_MEDIA_RETENTION_DAYS_CODE, Integer.class)
            .defaultValue(DEFAULT_REVISION_MEDIA_RETENTION_DAYS)
            .ownedBy("content")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Days after a content revision is superseded before the media only it uses is "
                    + "released (ADR 0157). Revisions themselves are kept indefinitely.")
            .build();

    /** The cap on videos in front of customers at once: five, because video is the expensive medium. */
    public static final ConfigurationKey<Integer> VIDEO_MAX_LIVE_PER_BRAND = ConfigurationKey.of(
                    VIDEO_MAX_LIVE_PER_BRAND_CODE, Integer.class)
            .defaultValue(DEFAULT_VIDEO_MAX_LIVE_PER_BRAND)
            .ownedBy("content")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Videos a brand may have live in its storefront content at once (ADR 0157). "
                    + "Publishing a sixth is refused with CONTENT_VIDEO_LIMIT.")
            .build();

    private ContentConfigurationKeys() {}
}
