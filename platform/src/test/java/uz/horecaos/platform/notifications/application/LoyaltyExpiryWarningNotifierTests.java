package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.loyalty.api.LoyaltyExpiryWarningPort.ExpiringLot;
import uz.horecaos.platform.notifications.api.CustomerAccountAlertPort;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog;

/**
 * What the loyalty pre-expiry adapter hands to ADR 0020 (row 6.3, ADR 0181): the typed variables, the key that
 * makes a retry a no-op, and the catalogue that lets a tenant author the wording. No database: the pipeline half
 * is {@code LoyaltyExpiryWarningNotificationTests}.
 */
class LoyaltyExpiryWarningNotifierTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T07:00:00Z");

    private final List<CustomerAccountAlertPort.AccountAlert> sent = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private boolean wordingIsActive = true;
    private boolean intentAlreadyExists;
    private final CustomerAccountAlertPort port = new CustomerAccountAlertPort() {

        @Override
        public boolean notifyAccount(AccountAlert alert) {
            sent.add(alert);
            return !intentAlreadyExists;
        }

        @Override
        public boolean canDeliver(UUID tenantId, UUID brandId, UUID customerAccountId, String templateKey) {
            asked.add(templateKey);
            return wordingIsActive;
        }
    };
    private final LoyaltyExpiryWarningNotifier notifier =
            new LoyaltyExpiryWarningNotifier(port, "Asia/Tashkent", Duration.ofDays(1));

    private static ExpiringLot lot(Instant expiresAt, long remaining) {
        return new ExpiringLot(UUID.randomUUID(), expiresAt, remaining);
    }

    @Test
    @DisplayName("the amount is every due lot added together; the date and days are the earliest lot's")
    void sumsTheAmountAndNamesTheEarliestDate() {
        notifier.lotsExpiring(
                TENANT,
                BRAND,
                CUSTOMER,
                ACCOUNT,
                "UZS",
                List.of(
                        lot(NOW.plus(Duration.ofDays(13)), 2_000),
                        lot(NOW.plus(Duration.ofDays(3)).plusSeconds(60), 3_000),
                        lot(NOW.plus(Duration.ofDays(9)), 700)),
                NOW);

        assertThat(sent).hasSize(1);
        var alert = sent.getFirst();
        assertThat(alert.variables())
                .isEqualTo(Map.of(
                        "loyaltyPoints", "5700",
                        "loyaltyExpiresOn", "17.10.2026",
                        "loyaltyDaysRemaining", "3"));
        assertThat(alert.templateKey()).isEqualTo("LOYALTY_POINTS_EXPIRING");
        assertThat(alert.subjectType()).isEqualTo("LoyaltyAccount").hasSizeLessThanOrEqualTo(32);
        assertThat(alert.subjectId()).isEqualTo(ACCOUNT);
        assertThat(alert.customerAccountId()).isEqualTo(CUSTOMER);
        assertThat(alert.brandId()).isEqualTo(BRAND);
    }

    @Test
    @DisplayName("the date is the customer's calendar date, not UTC's: 20:00Z is already tomorrow in Tashkent")
    void writesTheCalendarDateWhereTheCustomerLives() {
        notifier.lotsExpiring(
                TENANT,
                BRAND,
                CUSTOMER,
                ACCOUNT,
                "UZS",
                List.of(lot(Instant.parse("2026-10-27T20:00:00Z"), 1_000)),
                NOW);

        assertThat(sent.getFirst().variables()).containsEntry("loyaltyExpiresOn", "28.10.2026");
    }

    @Test
    @DisplayName("the variables are exactly the three typed ones: nothing about a person or an order")
    void carriesNothingElse() {
        notifier.lotsExpiring(
                TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", List.of(lot(NOW.plus(Duration.ofDays(5)), 1_000)), NOW);

        assertThat(sent.getFirst().variables().keySet())
                .containsExactlyInAnyOrder("loyaltyPoints", "loyaltyExpiresOn", "loyaltyDaysRemaining");
    }

    @Test
    @DisplayName("the key is the account and the UTC day: a retry within the day repeats it, tomorrow does not")
    void theKeyIsPerAccountPerDay() {
        List<ExpiringLot> lots = List.of(lot(NOW.plus(Duration.ofDays(5)), 1_000));

        notifier.lotsExpiring(TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", lots, NOW);
        notifier.lotsExpiring(TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", lots, NOW.plusSeconds(3_600));
        notifier.lotsExpiring(TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", lots, NOW.plus(Duration.ofDays(1)));
        notifier.lotsExpiring(TENANT, BRAND, CUSTOMER, UUID.randomUUID(), "UZS", lots, NOW);

        assertThat(sent.get(0).idempotencyKey()).isEqualTo(sent.get(1).idempotencyKey());
        assertThat(sent.get(0).idempotencyKey()).isNotEqualTo(sent.get(2).idempotencyKey());
        assertThat(sent.get(0).idempotencyKey()).isNotEqualTo(sent.get(3).idempotencyKey());
    }

    @Test
    @DisplayName("an empty list says nothing")
    void nothingDueNothingSent() {
        notifier.lotsExpiring(TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", List.of(), NOW);

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("with no wording to send the adapter creates nothing and says so, so the lots keep their warning")
    void withNoDeliverableWordingItHandsNothingOver() {
        wordingIsActive = false;

        boolean handedOver = notifier.lotsExpiring(
                TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", List.of(lot(NOW.plus(Duration.ofDays(5)), 1_000)), NOW);

        assertThat(handedOver).isFalse();
        assertThat(sent).as("no intent is created to be suppressed later").isEmpty();
        assertThat(asked).containsExactly("LOYALTY_POINTS_EXPIRING");
    }

    @Test
    @DisplayName("with wording to send it hands the message over")
    void withDeliverableWordingItHandsOver() {
        boolean handedOver = notifier.lotsExpiring(
                TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", List.of(lot(NOW.plus(Duration.ofDays(5)), 1_000)), NOW);

        assertThat(handedOver).isTrue();
        assertThat(sent).hasSize(1);
    }

    /**
     * The sweep crashed between the hand-over and the mark, and the next pass retries: the intent for the day exists
     * already. That is a message that was handed over, so the lots are marked, not offered again for ever.
     */
    @Test
    @DisplayName("an intent the day already holds counts as handed over")
    void aRepeatedIntentIsStillHandedOver() {
        intentAlreadyExists = true;

        boolean handedOver = notifier.lotsExpiring(
                TENANT, BRAND, CUSTOMER, ACCOUNT, "UZS", List.of(lot(NOW.plus(Duration.ofDays(5)), 1_000)), NOW);

        assertThat(handedOver).isTrue();
    }

    @Test
    @DisplayName("a tenant can declare every variable the adapter sends, under the class the message is created in")
    void theCatalogueOffersWhatTheAdapterSends() {
        List<String> offered = NotificationVariableCatalog.forClass(NotificationClass.TRANSACTIONAL_REQUIRED).stream()
                .map(NotificationVariableCatalog.Variable::name)
                .toList();
        assertThat(offered).contains("loyaltyPoints", "loyaltyExpiresOn", "loyaltyDaysRemaining");

        // The class is a constant for counsel to change; if she moves it, its catalogue must keep the same offer.
        List<String> optional = NotificationVariableCatalog.forClass(NotificationClass.TRANSACTIONAL_OPTIONAL).stream()
                .map(NotificationVariableCatalog.Variable::name)
                .toList();
        assertThat(optional).contains("loyaltyPoints", "loyaltyExpiresOn", "loyaltyDaysRemaining");
        assertThat(CustomerAccountAlertFanoutService.NOTIFICATION_CLASS)
                .isEqualTo(NotificationClass.TRANSACTIONAL_REQUIRED);
    }
}
