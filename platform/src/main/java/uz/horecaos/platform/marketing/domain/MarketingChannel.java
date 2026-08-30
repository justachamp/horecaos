package uz.horecaos.platform.marketing.domain;

/**
 * The transports a campaign may choose (ADR 0044, against ADR 0020's channels).
 *
 * <p>A separate enum from {@code notifications.domain.NotificationChannel} rather
 * than an import of it. That type is internal to the notifications module and the
 * boundary between the two is {@code CampaignMessagePort}, which carries the
 * channel as a string. The duplication is one closed list of four names; sharing
 * it would mean exposing a delivery-side vocabulary as a public interface, which
 * ADR 0020 deliberately does not do.
 *
 * <p>{@link #carriesMarginalCost} is the distinction that decides whether a cost
 * ceiling is optional. SMS costs money per segment and the mistake is
 * unrecoverable; push and messaging carry no marginal money, so their ceiling may
 * be null. The recipient cap never is: a runaway push campaign costs nothing in
 * cash and everything in uninstalls.
 */
public enum MarketingChannel {

    SMS(true),
    EMAIL(true),
    PUSH(false),

    /**
     * Telegram. Designed for and not activated in this slice: ADR 0044 is
     * explicit that turning it on is an ADR 0020 channel activation against this
     * model rather than a change to it. Declared here so a campaign row cannot
     * carry a channel name nobody recognises the day it is activated.
     */
    MESSAGING_APP(false);

    private final boolean marginalCost;

    MarketingChannel(boolean marginalCost) {
        this.marginalCost = marginalCost;
    }

    /** Whether one more recipient costs the tenant money. */
    public boolean carriesMarginalCost() {
        return marginalCost;
    }
}
