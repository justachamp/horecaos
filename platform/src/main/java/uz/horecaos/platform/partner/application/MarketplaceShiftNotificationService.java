package uz.horecaos.platform.partner.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.notifications.api.NotificationConfigurationKeys;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.domain.MarketplaceShiftEventType;
import uz.horecaos.platform.partner.domain.RejectionCode;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Gap map row {@code 10.9d}, second half: {@code
 * notifications.aggregator_shift_notifications_enabled} finally gets a
 * reader.
 *
 * <p>{@code NotificationConfigurationKeys}'s own Javadoc named the gap
 * precisely — the switch existed with nothing to gate, "there is no {@code
 * ShiftOpened}/{@code ShiftClosed} fact... for it to gate". This is that
 * fact, arriving the only way it credibly can: ADR 0040's aggregator's own
 * report of its shift, over the same partner surface
 * {@link MarketplaceIngestionService} already authenticates order pushes on.
 * HorecaOS has no view onto Yandex Eda's or Uzum Tezkor's own operating
 * shift beyond what the aggregator chooses to say.
 *
 * <p>Deliberately no persisted event row. Unlike an order, a shift ping is
 * evidence for exactly one thing — an operations alert — and {@code
 * notifications.notification_intents}' own unique constraint on {@code
 * (tenant_id, idempotency_key)} is already durable, at-least-once-safe
 * storage for "was this alert already sent"; a second table recording the
 * identical fact a second way is the kind of thing {@code
 * JdbcPartnerStore}'s own class doc warns against splitting state without
 * a reason. The switch itself is the record of what a tenant asked for;
 * this class is the trigger it never had.
 */
@Service
public class MarketplaceShiftNotificationService {

    private final JdbcPartnerStore store;
    private final ConfigurationResolver configuration;
    private final OperationsAlertPort operationsAlerts;
    private final Clock clock;
    private final Duration expiry;

    public MarketplaceShiftNotificationService(
            JdbcPartnerStore store,
            ConfigurationResolver configuration,
            OperationsAlertPort operationsAlerts,
            Clock clock,
            // A shift-opened alert nobody saw within the day is stale by the
            // time anyone would act on it; generous enough to survive a
            // quiet night shift without expiring before a manager's next
            // login.
            @Value("${horecaos.notifications.aggregator-shift-alert-expiry:P1D}") Duration expiry) {
        this.store = store;
        this.configuration = configuration;
        this.operationsAlerts = operationsAlerts;
        this.clock = clock;
        this.expiry = expiry;
    }

    public Outcome receive(PartnerPrincipal principal, ShiftEventPush push) {
        UUID tenantId = principal.tenantId();
        Instant now = clock.instant();

        // The identical two refusals MarketplaceIngestionService.receive
        // checks first, for the identical reason: a venue this credential
        // does not hold is the enumeration attempt the partner surface
        // exists to refuse.
        Optional<JdbcPartnerStore.Venue> venue = store.findVenue(tenantId, push.venueReference(), now);
        if (venue.isEmpty()) {
            return Outcome.rejected(RejectionCode.UNKNOWN_VENUE);
        }
        JdbcPartnerStore.Venue resolved = venue.get();
        if (!principal.covers(resolved.bindingId())) {
            return Outcome.rejected(RejectionCode.VENUE_NOT_PERMITTED);
        }

        Boolean enabled = configuration.value(
                NotificationConfigurationKeys.AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED,
                ResourceScope.brand(tenantId, resolved.brandId()));
        if (enabled == null || !enabled) {
            // Acknowledged, not silently dropped: the aggregator sent a real
            // event and HorecaOS understood it. The switch being off is a
            // tenant's own configured choice, not a partner-visible error.
            return Outcome.accepted(false);
        }

        Instant occurredAt = push.occurredAt() != null ? push.occurredAt() : now;
        String zoneId = store.businessTimezone(tenantId, resolved.locationId());
        ZoneId zone = zoneOrUtc(zoneId);
        String templateKey =
                push.event() == MarketplaceShiftEventType.OPENED ? MARKETPLACE_SHIFT_OPENED : MARKETPLACE_SHIFT_CLOSED;
        LocalDate businessDate = occurredAt.atZone(zone).toLocalDate();

        operationsAlerts.fanOut(
                tenantId,
                resolved.brandId(),
                resolved.locationId(),
                templateKey,
                templateKey,
                SUBJECT_TYPE,
                resolved.bindingId(),
                null,
                // One open alert and one close alert per binding per business
                // day: an aggregator that pings twice for the same shift (a
                // retried webhook, its own at-least-once delivery) collapses
                // to the one message an operator needed, in the binding's own
                // local day rather than UTC's.
                "%s:%s:%s:%s".formatted(templateKey, SUBJECT_TYPE, resolved.bindingId(), businessDate),
                shiftVariables(occurredAt, zone),
                expiry);

        return Outcome.accepted(true);
    }

    /** The semantic template key a tenant authors the shift-opened wording against. */
    public static final String MARKETPLACE_SHIFT_OPENED = "MARKETPLACE_SHIFT_OPENED";

    public static final String MARKETPLACE_SHIFT_CLOSED = "MARKETPLACE_SHIFT_CLOSED";

    static final String SUBJECT_TYPE = "MarketplaceBinding";

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * The entire variable set either shift message ever renders with — the
     * local wall-clock time and nothing about the aggregator or the
     * binding's own credentials. Package-visible so a classification test
     * can assert directly that this is the whole set, the same discipline
     * {@code OrderNotificationTrigger#reasonVariables} documents for its
     * own.
     */
    static Map<String, String> shiftVariables(Instant occurredAt, ZoneId zone) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("localTime", LOCAL_TIME.format(occurredAt.atZone(zone)));
        variables.put("timezone", zone.getId());
        return variables;
    }

    private static ZoneId zoneOrUtc(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException unknownZone) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * What a partner may hold about a shift ping (ADR 0031's contract).
     *
     * @param occurredAt null when the partner did not state one; the moment
     *                   this call was received stands in for it
     */
    public record ShiftEventPush(
            String venueReference,
            MarketplaceShiftEventType event,
            @Nullable Instant occurredAt) {}

    /**
     * @param notified false either for a rejected push (see {@link #rejectionCode()})
     *                 or for one the tenant's own switch declined to act on —
     *                 the two are distinguished by whether {@code accepted} is
     *                 true, never by this field alone
     */
    public record Outcome(
            boolean accepted, boolean notified, @Nullable RejectionCode rejectionCode) {

        static Outcome accepted(boolean notified) {
            return new Outcome(true, notified, null);
        }

        static Outcome rejected(RejectionCode code) {
            return new Outcome(false, false, code);
        }
    }
}
