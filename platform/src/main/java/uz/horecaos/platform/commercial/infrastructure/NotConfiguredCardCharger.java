package uz.horecaos.platform.commercial.infrastructure;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.application.CardCharger;

/**
 * The only {@link CardCharger} wired today (ADR 0095 item 8): HorecaOS has
 * no Click or Payme merchant account of its own yet, so every charge
 * attempt answers {@link CardCharger.Outcome.NotConfigured}. A tenant on
 * {@code CARD} is then collected exactly like {@code INVOICE} — the
 * remainder stays due until a real adapter replaces this one.
 */
@Component
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
