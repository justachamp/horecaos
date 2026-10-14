package uz.horecaos.platform.marketing.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Counts one open of an attribution link on behalf of a surface other than the web storefront's own endpoint (ADR 0167
 * Decision 4 tier 1 and Decision 5; ADR 0183): today the Telegram bot's {@code /start <token>}.
 *
 * <p>Implemented by {@code marketing}, called by {@code integration}. One implementation serves every surface, so the
 * rules cannot drift between them: a click is a count and never an event, the limiter runs <em>before</em> the lookup, and
 * an unknown, archived, expired or other-brand token counts nothing.
 *
 * <p>The caller passes no address, no user agent and no account. {@code visitor} is an opaque handle (see
 * {@link AnonymousVisitorHandle}) that lives in the ADR 0033 limiter's memory for sixty seconds and is written to no
 * table, log, metric or event.
 */
public interface AttributionClickPort {

    /**
     * @param brandId the brand the surface serves; {@code null} for a surface that serves every brand of the tenant (a bot
     *                installed for the whole tenant), in which case the link's own brand decides
     */
    Outcome click(UUID tenantId, @Nullable UUID brandId, String token, String visitor);

    /** What happened to a click, as much as the caller may be told. */
    enum Outcome {
        /** A live link of the brand was opened and its count went up by one. */
        COUNTED,
        /**
         * This visitor has used the minute's allowance. Says nothing about whether the token names a link, because the
         * limiter runs before the lookup so that it cannot be used to probe.
         */
        RATE_LIMITED,
        /** Not a link of this brand that is live: ill-shaped, unknown, archived, outside its window, or another brand's. */
        NOT_A_LINK
    }
}
