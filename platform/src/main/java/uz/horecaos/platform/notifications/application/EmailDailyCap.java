package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;

/**
 * A tenant's daily allowance on the platform's one email sender (ADR 0182, D13).
 *
 * <p>Every tenant's campaign email leaves from the same address through the same relay, so one
 * tenant that mails a hundred thousand people in an afternoon spends a reputation all the others
 * share. The cap is the blast radius of one tenant, and it replaces the money ceiling
 * ({@code MarketingChannel.EMAIL} carries no marginal cost to the tenant).
 *
 * <p><strong>Held, not dropped.</strong> A message over the cap waits for the next UTC day, and a
 * campaign's own expiry may still end it first: a promotion a day late is worth less than silence,
 * which is the rule ADR 0020 already states for every message. Counting is by attempts made since
 * 00:00 UTC, so a burst inside one dispatch pass can overshoot the cap by the size of the pass;
 * the cap bounds a tenant's day, it is not an exact meter.
 */
@Component
public class EmailDailyCap {

    private final JdbcNotificationStore notifications;
    private final Clock clock;
    private final int capPerTenantPerDay;

    public EmailDailyCap(
            JdbcNotificationStore notifications,
            Clock clock,
            // A pilot's first campaigns are small and a provider's free tier is small; the owner sets the
            // real figure against the provider's limits (ADR 0182, open input).
            @Value("${horecaos.notifications.email.daily-cap-per-tenant:1000}") int capPerTenantPerDay) {
        this.notifications = notifications;
        this.clock = clock;
        this.capPerTenantPerDay = capPerTenantPerDay;
    }

    /**
     * When this tenant may next send an email, if it may not send one now.
     *
     * @return empty when the tenant is under its cap; otherwise 00:00 UTC of the next day
     */
    public Optional<Instant> heldUntil(UUID tenantId, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (notifications.attemptsSince(tenantId, NotificationChannel.EMAIL.name(), startOfDay) < capPerTenantPerDay) {
            return Optional.empty();
        }
        return Optional.of(today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** The cap, for a screen or a log line that says what it is. */
    public int capPerTenantPerDay() {
        return capPerTenantPerDay;
    }

    /** The clock this cap counts days by, so a test advances the same one the service reads. */
    Clock clock() {
        return clock;
    }
}
