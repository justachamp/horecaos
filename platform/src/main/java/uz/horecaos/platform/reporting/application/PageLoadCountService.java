package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.reporting.api.ReportingConfigurationKeys;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The page-load count (ADR 0183 D18, row {@code 7.6c}): the one thing the funnel's visits stage may be without client
 * telemetry, and only once counsel has answered.
 *
 * <p><strong>What it is.</strong> A storefront sends one anonymous ping per browser tab session; the platform adds one to the
 * counter of the day, brand and sales channel. It reads no cookie, mints no identifier, and stores no address, user agent,
 * session or path. The only thing that stands for the visitor is {@code visitor}, an opaque keyed hash that lives in the
 * ADR 0033 limiter for sixty seconds and in no table. A page load is not a person: one visitor opening the shop twice is two,
 * and there is no distinct-visitor or page-view figure to derive.
 *
 * <p><strong>Dark.</strong> {@link #enabled()} reads {@code reporting.page_load_count_enabled} at platform scope, default
 * false. While it is false {@link #record} does nothing at all, touches no limiter and no table, and answers as if it had counted:
 * a switch nobody has flipped must be indistinguishable from outside. No storefront sends the ping while it is false either
 * (it asks {@link #enabled()} first, once per tab session).
 *
 * <p><strong>What it will not count.</strong> A brand that is not the tenant's and a channel code the tenant never defined are
 * dropped, so the table cannot be grown by a caller inventing strings.
 */
@Service
public class PageLoadCountService {

    static final String OPERATION = "reporting.page_load";

    /**
     * Pings per visitor per minute. A tab session sends one, and a shared mobile address carries many visitors, so the
     * allowance is wide; it exists to stop a loop and not to ration people. Strict: a ping lost to an unavailable limiter is a
     * smaller number, and a counter that is too small is the safe way to be wrong.
     */
    static final RateLimiter.Policy LIMIT = RateLimiter.Policy.strictPerMinute(30);

    private final JdbcFunnelStore store;
    private final BusinessDayService businessDays;
    private final SalesChannelLookup channels;
    private final ConfigurationResolver configuration;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public PageLoadCountService(
            JdbcFunnelStore store,
            BusinessDayService businessDays,
            SalesChannelLookup channels,
            ConfigurationResolver configuration,
            RateLimiter rateLimiter,
            Clock clock) {
        this.store = store;
        this.businessDays = businessDays;
        this.channels = channels;
        this.configuration = configuration;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** Whether the platform counts page loads at all. Same answer for every tenant and every visitor. */
    public boolean enabled() {
        Boolean on = configuration.value(ReportingConfigurationKeys.PAGE_LOAD_COUNT_ENABLED, ResourceScope.platform());
        return Boolean.TRUE.equals(on);
    }

    /**
     * Counts one page load.
     *
     * @param visitor an opaque, never-stored handle for the limiter and nothing else
     * @return whether a load was counted; callers answer the same either way
     * @throws ApiException {@code RATE_LIMIT_EXCEEDED} when this visitor has used the minute's allowance
     */
    @Transactional
    public boolean record(UUID tenantId, UUID brandId, @Nullable String channelCode, String visitor) {
        if (!enabled()) {
            return false;
        }
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(OPERATION, tenantId.toString(), visitor), LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests. Try again shortly.");
        }
        if (channelCode == null
                || channelCode.isBlank()
                || !store.brandBelongsToTenant(tenantId, brandId)
                || channels.byCode(tenantId, channelCode).isEmpty()) {
            return false;
        }
        LocalDate today = businessDays.boundaryFor(tenantId).dateOf(clock.instant());
        store.addPageLoad(tenantId, brandId, today, channelCode);
        return true;
    }
}
