package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.MarketingConfigurationKeys;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcPresentedOfferStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcPresentedOfferStore.PresentedRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * The in-app surface of a scenario: banners a storefront or the Telegram mini-app polls
 * for (ADR 0112 Decision 2).
 *
 * <p>A step on the {@code IN_APP} channel does not send a message; it makes a guest
 * eligible to be shown an offer. Showing is a separate act with its own cap, because a
 * banner has no delivery attempt to count against the messaging frequency cap and a guest
 * who opens the app six times in a day should not see the same banner six times.
 * {@code marketing.in_app.show_cap_per_day} (ADR 0030, brand scope, default three) is that
 * cap, counted per banner per guest per calendar day in the brand's own zone.
 *
 * <p>This is also what backs the storefront's existing {@code OfferItem}: one item is one
 * presentation joined to its offer's display name and banner reference, so a follow-on
 * controller serves the existing frontend type rather than a second representation.
 */
@Service
public class PresentedOfferService {

    /** The surfaces a banner can appear on. */
    public static final String STOREFRONT = "STOREFRONT";

    public static final String TELEGRAM_MINI_APP = "TELEGRAM_MINI_APP";

    private final JdbcPresentedOfferStore presented;
    private final JdbcEngagementStore engagement;
    private final ConfigurationResolver configuration;
    private final Clock clock;

    public PresentedOfferService(
            JdbcPresentedOfferStore presented,
            JdbcEngagementStore engagement,
            ConfigurationResolver configuration,
            Clock clock) {
        this.presented = presented;
        this.engagement = engagement;
        this.configuration = configuration;
        this.clock = clock;
    }

    /** Makes a guest eligible to be shown an offer on a surface. A second call for the same offer is a no-op. */
    @Transactional
    public boolean present(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID campaignId,
            UUID offerId,
            UUID accountId,
            String surface,
            Instant now) {
        return presented.present(Ids.newId(), tenantId, brandId, campaignId, offerId, accountId, surface, now);
    }

    /**
     * What this guest is shown right now. Each banner returned is counted against today's
     * cap in the same call, so asking is showing: a client that polls and renders nothing
     * has still used a showing, which is why a poll should be made when a screen opens and
     * not on a timer.
     */
    @Transactional
    public List<Banner> poll(UUID tenantId, UUID brandId, UUID accountId, String surface) {
        Instant now = clock.instant();
        int cap = capPerDay(tenantId, brandId);
        var day = now.atZone(engagement.resolvePolicy(tenantId, brandId).timezone())
                .toLocalDate();

        List<Banner> shown = new ArrayList<>();
        int priority = 0;
        for (PresentedRow row : presented.candidates(tenantId, brandId, accountId, surface, now)) {
            if (presented.markShown(tenantId, accountId, row.id(), day, cap, now)) {
                shown.add(
                        new Banner(row.id(), row.offerId(), row.displayName(), row.bannerImageReference(), priority++));
            }
        }
        return shown;
    }

    /** The guest closed a banner. It is not shown again. */
    @Transactional
    public boolean dismiss(UUID tenantId, UUID accountId, UUID presentedId) {
        return presented.dismiss(tenantId, accountId, presentedId, clock.instant());
    }

    int capPerDay(UUID tenantId, UUID brandId) {
        Integer cap = configuration
                .resolve(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY, ResourceScope.brand(tenantId, brandId))
                .value();
        return cap == null ? 3 : cap;
    }

    /** One banner, in the shape the storefront's {@code OfferItem} wants: an id, a name, an image, a priority. */
    public record Banner(
            UUID presentedOfferId,
            UUID offerId,
            String name,
            @Nullable String imageReference,
            int priority) {}
}
