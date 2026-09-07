package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.PreferenceRow;

/**
 * What a customer has asked for, per class and channel (ADR 0020).
 *
 * <p>Never a legal basis. A preference says whether the customer wants a message
 * they could lawfully be sent; consent says whether they may be sent one at all,
 * and it lives in {@code customer.consent_decisions} where it is append-only and
 * carries a policy version. Writing a preference must not create or destroy a
 * consent decision, so this service does not touch that table.
 *
 * <p>A class the customer cannot switch off is refused rather than silently
 * accepted and ignored. An interface that lets someone turn off their order
 * confirmations, and then sends them anyway, is worse than one that says no.
 */
@Service
public class NotificationPreferenceService {

    private final JdbcNotificationStore notifications;
    private final Clock clock;

    public NotificationPreferenceService(JdbcNotificationStore notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PreferenceRow> preferences(UUID tenantId, UUID accountId) {
        return notifications.preferences(tenantId, accountId);
    }

    /**
     * Sets one preference's {@code enabled} flag, leaving any quiet-hours
     * window on the row untouched.
     *
     * <p>The overload every caller had before quiet hours existed keeps this
     * exact shape — {@code CustomerProviderBindingSyncService} syncing a
     * TELEGRAM preference on and off as a binding links, is imported, or
     * retires has no opinion on a window the customer separately set through
     * {@link #set(UUID, UUID, UUID, NotificationClass, NotificationChannel,
     * boolean, LocalTime, LocalTime, String)}, and a binding event is not the
     * moment to silently clear one.
     *
     * @param brandId null for the customer's tenant-wide answer, set to override it
     *                for one brand
     */
    @Transactional
    public void set(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            NotificationClass notificationClass,
            NotificationChannel channel,
            boolean enabled) {

        if (!notificationClass.respectsPreference()) {
            throw new IllegalArgumentException(notificationClass + " is not something a customer can switch off");
        }
        notifications.upsertPreference(
                tenantId, accountId, brandId, notificationClass.name(), channel.name(), enabled, clock.instant());
    }

    /**
     * Sets one preference in full, quiet hours included — the customer-facing
     * endpoint's own call, matching its {@code PUT} semantics.
     *
     * <p>A full replace, not a patch: omitting {@code quietHoursStart}/{@code
     * quietHoursEnd} clears any window previously set, rather than leaving it
     * in force silently.
     *
     * @param brandId         null for the customer's tenant-wide answer, set to
     *                        override it for one brand
     * @param quietHoursStart the window's local start time, or null together
     *                        with {@code quietHoursEnd} for no window at all
     * @param quietHoursEnd   the window's local end time; must be null exactly
     *                        when {@code quietHoursStart} is and must differ
     *                        from it when both are set — a window closing the
     *                        instant it opens holds nothing, and deciding
     *                        whether that silently meant "always" or "never"
     *                        is not this method's call to make
     * @param timezone        the IANA zone the two times above are read in;
     *                        required whenever a window is set, because a local
     *                        time with no zone cannot be compared against an
     *                        instant
     */
    @Transactional
    public void set(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            NotificationClass notificationClass,
            NotificationChannel channel,
            boolean enabled,
            @Nullable LocalTime quietHoursStart,
            @Nullable LocalTime quietHoursEnd,
            @Nullable String timezone) {

        if (!notificationClass.respectsPreference()) {
            throw new IllegalArgumentException(notificationClass + " is not something a customer can switch off");
        }
        validateQuietHours(quietHoursStart, quietHoursEnd, timezone);
        notifications.upsertPreferenceWindow(
                tenantId,
                accountId,
                brandId,
                notificationClass.name(),
                channel.name(),
                enabled,
                quietHoursStart,
                quietHoursEnd,
                timezone,
                clock.instant());
    }

    /**
     * Fails fast on a malformed window rather than letting it reach the
     * database's own {@code ck_preference_quiet_hours_pair}/{@code
     * ck_preference_quiet_hours_zone} constraints, so a customer gets a clear
     * validation error instead of a constraint-violation stack trace.
     */
    private static void validateQuietHours(
            @Nullable LocalTime start, @Nullable LocalTime end, @Nullable String timezone) {
        if ((start == null) != (end == null)) {
            throw new IllegalArgumentException("Quiet hours need both a start and an end, or neither");
        }
        if (start == null) {
            return;
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException("Quiet hours start and end must differ");
        }
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("Quiet hours need a timezone");
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException invalidZone) {
            throw new IllegalArgumentException("%s is not a known timezone".formatted(timezone));
        }
    }
}
