package uz.horecaos.platform.marketing.domain;

import java.util.Optional;

/**
 * Where a scenario step reaches a guest (ADR 0112).
 *
 * <p>Six, not four. The four messaging channels are {@link MarketingChannel}'s and go
 * through ADR 0020 as a campaign's always have. {@link #IN_APP} is a banner a storefront
 * or the Telegram mini-app polls for, and {@link #CALL_CENTRE} is a task for a person to
 * phone the guest. Neither has a delivery attempt, which is exactly why the two are
 * not a {@code MarketingChannel}: that enum is what a broadcast's cost ceiling and
 * consent check are built on, and neither of these is a broadcast.
 */
public enum ScenarioChannel {
    SMS,
    EMAIL,
    PUSH,
    MESSAGING_APP,
    IN_APP,
    CALL_CENTRE;

    /** The ADR 0020 channel this is, or empty for a step that sends no message. */
    public Optional<MarketingChannel> messaging() {
        return switch (this) {
            case SMS -> Optional.of(MarketingChannel.SMS);
            case EMAIL -> Optional.of(MarketingChannel.EMAIL);
            case PUSH -> Optional.of(MarketingChannel.PUSH);
            case MESSAGING_APP -> Optional.of(MarketingChannel.MESSAGING_APP);
            case IN_APP, CALL_CENTRE -> Optional.empty();
        };
    }
}
