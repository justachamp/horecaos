package uz.horecaos.platform.commercial.infrastructure;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.commercial.application.CardCharger;

/**
 * What a charger says when no merchant account is connected (ADR 0095 item 8):
 * every charge attempt answers {@link CardCharger.Outcome.NotConfigured}. A
 * tenant on {@code CARD} is then collected exactly like {@code INVOICE} — the
 * remainder stays due.
 *
 * <p>No longer a Spring bean: {@code PlatformCardGateway} is the one charger the
 * application sees, and it answers exactly this when no platform card
 * installation is active. This class stays as the reference for that answer and
 * as the charger the wallet's own unit tests build by hand.
 */
public class NotConfiguredCardCharger implements CardCharger {

    @Override
    public Outcome charge(
            UUID tenantId,
            @Nullable String cardTokenReference,
            long amountMinor,
            String currency,
            String idempotencyKey) {
        return new Outcome.NotConfigured();
    }

    /** No merchant account means no way to ask, which is exactly {@link StatusOutcome.NotSucceeded}'s meaning. */
    @Override
    public StatusOutcome status(String idempotencyKey) {
        return new StatusOutcome.NotSucceeded();
    }
}
