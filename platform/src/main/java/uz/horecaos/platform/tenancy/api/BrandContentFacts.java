package uz.horecaos.platform.tenancy.api;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The few facts about a brand and its tenant that storefront content needs and nothing else (ADR
 * 0157), so the {@code content} module reaches {@code tenancy} through a port and not through its
 * tables.
 *
 * <p>Every method takes the tenant id and every implementation puts it in the query: a brand id is
 * a UUID a caller may have received from anywhere, and a lookup that matched on the brand id alone
 * would answer another tenant's brand.
 */
public interface BrandContentFacts {

    /**
     * Whether this brand exists for this tenant and is {@code ACTIVE}: the public content read
     * answers 404 for everything else, the same shape for an unknown brand and a brand of another
     * tenant.
     */
    boolean brandIsActive(UUID tenantId, UUID brandId);

    /** Whether this brand exists for this tenant at all, in any status: what authoring asks. */
    boolean brandExists(UUID tenantId, UUID brandId);

    /**
     * The tenant's calendar, for the day an anonymous counter is filed under. Falls back to the
     * platform zone ({@code Asia/Tashkent}) for a tenant it cannot find, which cannot happen for a
     * brand that exists.
     */
    ZoneId tenantZone(UUID tenantId);

    /**
     * The hostnames of the tenant's channels that have been <em>verified</em> (ADR 0036, row 10.5),
     * lower-case. Read on demand and never copied, so a hostname that is unverified or released
     * stops being linkable on the next read.
     */
    Set<String> verifiedChannelHostnames(UUID tenantId);

    /**
     * The name a customer sees for this brand, for the brand switcher (ADR 0183 D17). Empty for a brand that is not this
     * tenant's. Defaulted so the test doubles that predate the switcher are untouched.
     */
    default java.util.Optional<String> brandDisplayName(UUID tenantId, UUID brandId) {
        return java.util.Optional.empty();
    }

    /**
     * The hostname of one WEB channel of this tenant, only while it is <em>verified</em> and the channel is active: what a
     * brand switcher entry links to, asked on every read so a released or un-verified hostname stops being linkable at once.
     * Defaulted for the same reason as {@link #brandDisplayName}.
     */
    default java.util.Optional<String> verifiedWebHostname(UUID tenantId, UUID channelId) {
        return java.util.Optional.empty();
    }

    /**
     * One of the tenant's other brands and one of its website channels: what a brand switcher entry can name (ADR 0183 D17).
     *
     * @param hostname the channel's verified hostname, or null while it has none (an entry can be added and is then not
     *                 shown until the hostname is verified)
     */
    record SiblingStorefront(
            UUID brandId,
            String brandName,
            UUID channelId,
            String channelCode,
            @Nullable String hostname) {}

    /**
     * The tenant's other active brands that sell through an active website channel at one of their own active branches, one
     * row per brand and channel, by brand name then channel code. Defaulted empty for the same reason as
     * {@link #brandDisplayName}.
     */
    default List<SiblingStorefront> siblingStorefronts(UUID tenantId, UUID excludingBrandId) {
        return List.of();
    }
}
