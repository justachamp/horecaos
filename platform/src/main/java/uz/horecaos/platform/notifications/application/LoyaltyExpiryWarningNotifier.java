package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.loyalty.api.LoyaltyExpiryWarningPort;
import uz.horecaos.platform.notifications.api.CustomerAccountAlertPort;
import uz.horecaos.platform.notifications.api.CustomerAccountAlertPort.AccountAlert;
import uz.horecaos.platform.notifications.domain.MoneyText;

/**
 * The real adapter behind {@link LoyaltyExpiryWarningPort}: the pre-expiry warning of operations §6.3 reaches the
 * customer through ADR 0020 (ADR 0046's product default, "14 days before, at most one message per customer per
 * day"; ADR 0181).
 *
 * <p>Lives in {@code notifications} and implements a {@code loyalty} port, the direction the module graph allows:
 * {@code notifications} already reads {@code customers} and {@code marketing}, which read {@code loyalty}, so the
 * reverse edge would close a cycle.
 *
 * <p><strong>What the message says.</strong> One customer, however many lots are due: the amount is what the due
 * lots still hold, added together, and the date and the days remaining are those of the earliest of them. A
 * tenant's wording says "from" -- {@code {loyaltyPoints} points start to lapse on {loyaltyExpiresOn}} -- because
 * a customer with a lot in three days and another in thirteen has not been told that all of it lapses on the
 * third. The three variables are typed, never a name or a contact value (ADR 0029), and are the whole set the
 * message renders with.
 *
 * <p><strong>Built dark.</strong> The semantic template key {@value #TEMPLATE_KEY} has no wording until a tenant
 * authors and activates one. Until then nothing is sent <em>and nothing is created</em>: the adapter asks {@link
 * CustomerAccountAlertPort#canDeliver} first and answers {@code false} when there is no wording the customer could
 * be reached with, so the sweep does not mark the lots and each lot keeps its one warning for the day the wording
 * is activated. (An intent created anyway would be suppressed {@code NO_ACTIVE_TEMPLATE}, would hold the day's
 * idempotency key, and would have the sweep count the warning as spent.) The class is {@code
 * TRANSACTIONAL_REQUIRED} (see {@link CustomerAccountAlertPort}) until counsel rules on promotional balances; an SMS
 * wording additionally waits for its gateway's approval ({@code TEMPLATE_AWAITING_PROVIDER}), which counts as dark
 * for the same reason, while Telegram does not.
 *
 * <p>The idempotency key is the account and the day of the sweep, the second half of "at most one message per
 * customer per day": the loyalty worklist already leaves an account alone for 24 hours after a warning, and this
 * key makes a retry after a crash between the hand-over and the mark a no-op instead of a second message.
 */
@Component
public class LoyaltyExpiryWarningNotifier implements LoyaltyExpiryWarningPort {

    /** The semantic template key a tenant authors this message's wording against. */
    public static final String TEMPLATE_KEY = "LOYALTY_POINTS_EXPIRING";

    /** What the message is about: the loyalty account, not an order. */
    public static final String SUBJECT_TYPE = "LoyaltyAccount";

    static final String POINTS = "loyaltyPoints";
    static final String EXPIRES_ON = "loyaltyExpiresOn";
    static final String DAYS_REMAINING = "loyaltyDaysRemaining";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CustomerAccountAlertPort accountAlerts;
    private final ZoneId customerZone;
    private final Duration expiry;

    public LoyaltyExpiryWarningNotifier(
            CustomerAccountAlertPort accountAlerts,
            // The date a customer reads is a calendar date where they live. No tenant-level zone exists to read
            // (ADR 0058 makes the same remark about language), so one platform default until one does.
            @Value("${horecaos.notifications.customer-date-zone:Asia/Tashkent}") String customerZone,
            // A warning that cannot be sent within a day of its sweep is stale: the next sweep decides again.
            @Value("${horecaos.notifications.loyalty-expiry-warning-expiry:P1D}") Duration expiry) {
        this.accountAlerts = accountAlerts;
        this.customerZone = ZoneId.of(customerZone);
        this.expiry = expiry;
    }

    @Override
    public boolean lotsExpiring(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            UUID accountId,
            String currency,
            List<ExpiringLot> lots,
            Instant now) {
        if (lots.isEmpty()) {
            return false;
        }
        if (!accountAlerts.canDeliver(tenantId, brandId, customerAccountId, TEMPLATE_KEY)) {
            return false;
        }
        // The key already existing is the sweep retrying after a crash between this hand-over and its mark: the
        // message was handed over, so the answer is still true and the lots get marked.
        accountAlerts.notifyAccount(new AccountAlert(
                tenantId,
                brandId,
                customerAccountId,
                TEMPLATE_KEY,
                SUBJECT_TYPE,
                accountId,
                idempotencyKey(accountId, now),
                variables(currency, lots, now),
                now,
                expiry));
        return true;
    }

    /** One message per account per UTC day: the same sweep retried within the day produces the same key. */
    static String idempotencyKey(UUID accountId, Instant now) {
        return "%s:%s:%s:%s"
                .formatted(TEMPLATE_KEY, SUBJECT_TYPE, accountId, LocalDate.ofInstant(now, ZoneId.of("UTC")));
    }

    /**
     * The entire variable set this message ever renders with. Package-visible so a test can assert that this is
     * the whole set, the discipline {@code OrderNotificationTrigger#reasonVariables} documents for its own.
     */
    Map<String, String> variables(String currency, List<ExpiringLot> lots, Instant now) {
        long total = lots.stream().mapToLong(ExpiringLot::remainingMinor).sum();
        Instant earliest = lots.stream()
                .map(ExpiringLot::expiresAt)
                .min(Instant::compareTo)
                .orElseThrow();
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put(POINTS, MoneyText.format(total, currency));
        variables.put(EXPIRES_ON, DATE.format(earliest.atZone(customerZone)));
        variables.put(
                DAYS_REMAINING,
                Long.toString(Math.max(0, Duration.between(now, earliest).toDays())));
        return variables;
    }
}
