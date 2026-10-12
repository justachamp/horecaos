package uz.horecaos.platform.tenancy.api;

import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

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
}
