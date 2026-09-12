package uz.horecaos.platform.iam.infrastructure.keycloak;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffDisplayNames;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * {@link StaffDisplayNames} over {@link StaffAccounts#displayName}, cached
 * per {@link CacheRegistry#STAFF_DISPLAY_NAMES} (Staff 9.3b).
 *
 * <p>{@code unless = "#result == null"} rather than caching the miss too: an
 * account created moments ago and not yet named should not have "no name" —
 * this is the first ten minutes' worth of every new hire's audit rows — stuck
 * for the whole TTL. The cost is a repeated live call for a subject that
 * genuinely has no name (a service account, a migration actor), which is rare
 * enough that this interim mechanism accepts it.
 */
@Component
class CachedStaffDisplayNames implements StaffDisplayNames {

    private final StaffAccounts accounts;

    CachedStaffDisplayNames(StaffAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    @Cacheable(cacheNames = "staff.display_names", unless = "#result == null")
    public @Nullable String displayName(String subjectId) {
        return accounts.displayName(subjectId).orElse(null);
    }
}
