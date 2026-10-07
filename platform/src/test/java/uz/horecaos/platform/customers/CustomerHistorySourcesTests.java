package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry;
import uz.horecaos.platform.customers.api.CustomerHistoryEntry.Kind;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerEngagementHistory;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcCustomerCommunicationHistory;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort;
import uz.horecaos.platform.pricing.api.CustomerDiscountHistoryPort.Redemption;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcCustomerReviewHistory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The three modules' contributions to one guest's card (ADR 0111 §2, §8), each read from the
 * module that owns the data and answering for one guest in one tenant.
 *
 * <p>What would still pass if a source were broken is what these assertions are about: every one
 * puts a <em>second</em> guest's row beside the first guest's and asserts it is absent, because a
 * source that dropped its account predicate would return the first guest's rows too, and a test
 * that seeded only one guest could not tell. And each one seeds the field it must never return --
 * a review's comment ciphertext, a campaign recipient's notification -- and looks for it in what
 * comes back.
 */
class CustomerHistorySourcesTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-7000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-7000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000e1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-7000-7000-8000-0000000000e2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f10-7000-7000-8000-0000000000e3");

    private static final Instant BASE = Instant.parse("2026-10-01T10:00:00Z");

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    private OrderBoardFixtures fixtures;
    private UUID guest;
    private UUID stranger;
    private UUID order;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the history sources");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void seed() {
        fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        fixtures.tenant(TENANT, "history-sources", BRAND, LOCATION);
        fixtures.tenant(OTHER_TENANT, "history-sources-other", OTHER_BRAND, OTHER_LOCATION);
        guest = OrderBoardFixtures.customerOf(TENANT);
        stranger = UUID.randomUUID();
        jdbc.sql("INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, "
                        + "identity_policy_version, version) VALUES (:id, :t, 'ACTIVE', 'Stranger', 1, 1)")
                .param("id", stranger)
                .param("t", TENANT)
                .update();
        order = fixtures.insertOrder(OrderBoardFixtures.order("history").at(TENANT, BRAND, LOCATION));
    }

    // ------------------------------------------------------------- notifications

    @Test
    @DisplayName(
            "notifications: one guest's messages, newest first, by stable codes, with the reason a message was refused")
    void notificationsAnswerForOneGuestOnly() {
        UUID sms = notification(guest, "SMS", "DELIVERED", null, "order.confirmation", BASE.plusSeconds(60));
        UUID push = notification(
                guest, "PUSH", "SUPPRESSED", "NO_REACHABLE_ENDPOINT", "order.ready", BASE.plusSeconds(120));
        notification(stranger, "SMS", "DELIVERED", null, "order.confirmation", BASE.plusSeconds(90));

        List<CustomerHistoryEntry> entries =
                new JdbcCustomerCommunicationHistory(jdbc).history(TENANT, guest, null, 10);

        assertThat(entries).extracting(CustomerHistoryEntry::referenceId).containsExactly(push, sms);
        assertThat(entries.get(0)).satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(Kind.NOTIFICATION);
            assertThat(entry.channel()).isEqualTo("PUSH");
            assertThat(entry.statusCode()).isEqualTo("SUPPRESSED");
            assertThat(entry.detailCode())
                    .as("a refused message says why, in the one field a reader looks at")
                    .isEqualTo("NO_REACHABLE_ENDPOINT");
            assertThat(entry.orderId()).isEqualTo(order);
        });
        assertThat(entries.get(1).detailCode()).as("otherwise, which template").isEqualTo("order.confirmation");
    }

    @Test
    @DisplayName("notifications: `before` and `limit` page backwards, and nothing leaks across tenants")
    void notificationsPageBackwards() {
        for (int i = 0; i < 4; i++) {
            notification(guest, "SMS", "DELIVERED", null, "t" + i, BASE.plusSeconds(i * 60L));
        }

        JdbcCustomerCommunicationHistory source = new JdbcCustomerCommunicationHistory(jdbc);
        List<CustomerHistoryEntry> first = source.history(TENANT, guest, null, 2);
        List<CustomerHistoryEntry> second =
                source.history(TENANT, guest, first.getLast().occurredAt(), 2);

        assertThat(first).extracting(CustomerHistoryEntry::detailCode).containsExactly("t3", "t2");
        assertThat(second).extracting(CustomerHistoryEntry::detailCode).containsExactly("t1", "t0");
        assertThat(source.history(OTHER_TENANT, guest, null, 10))
                .as("the same account id asked about in another tenant is nobody")
                .isEmpty();
    }

    // ------------------------------------------------------------------- reviews

    @Test
    @DisplayName("reviews: the rating and the order, never the comment")
    void reviewsCarryTheRatingNotTheComment() {
        UUID mine = review(guest, 4, "ciphertext-of-the-guests-own-words", BASE.plusSeconds(30));
        review(
                stranger,
                1,
                null,
                BASE.plusSeconds(60),
                fixtures.insertOrder(
                        OrderBoardFixtures.order("history-stranger").at(TENANT, BRAND, LOCATION)));

        List<CustomerHistoryEntry> entries = new JdbcCustomerReviewHistory(jdbc).history(TENANT, guest, null, 10);

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().kind()).isEqualTo(Kind.REVIEW);
        assertThat(entries.getFirst().rating()).isEqualTo(4);
        assertThat(entries.getFirst().orderId()).isEqualTo(order);
        assertThat(entries.getFirst().referenceId()).isEqualTo(mine);
        assertThat(entries.getFirst().toString())
                .as("the comment's ciphertext is not on the entry in any form")
                .doesNotContain("ciphertext-of-the-guests-own-words");
        assertThat(new JdbcCustomerReviewHistory(jdbc).history(OTHER_TENANT, guest, null, 10))
                .isEmpty();
    }

    // ----------------------------------------------------------------- marketing

    @Test
    @DisplayName("marketing: the campaigns that reached or refused this guest, and the promotions she redeemed")
    void engagementCarriesReceiptsAndRedemptions() {
        UUID campaign = campaign("Autumn buffet");
        recipient(campaign, guest, "REFUSED", "CONSENT_WITHHELD", null, BASE.plusSeconds(100));
        recipient(campaign, stranger, "REFUSED", "SUPPRESSED", null, BASE.plusSeconds(100));
        UUID redemptionId = UUID.randomUUID();
        CustomerDiscountHistoryPort discounts = (tenantId, accountId) -> accountId.equals(guest)
                ? List.of(new Redemption(
                        redemptionId,
                        BRAND,
                        UUID.randomUUID(),
                        "AUTU***",
                        UUID.randomUUID(),
                        "Autumn 10%",
                        order,
                        Redemption.Status.REDEEMED,
                        15_000,
                        "UZS",
                        BASE.plusSeconds(40),
                        BASE.plusSeconds(50),
                        null))
                : List.of();

        List<CustomerHistoryEntry> entries =
                new JdbcCustomerEngagementHistory(jdbc, discounts).history(TENANT, guest, null, 10);

        assertThat(entries)
                .extracting(CustomerHistoryEntry::kind)
                .containsExactlyInAnyOrder(Kind.CAMPAIGN_RECEIPT, Kind.PROMO_REDEMPTION);
        CustomerHistoryEntry receipt = entries.stream()
                .filter(entry -> entry.kind() == Kind.CAMPAIGN_RECEIPT)
                .findFirst()
                .orElseThrow();
        assertThat(receipt.detailCode()).as("why this guest was refused").isEqualTo("CONSENT_WITHHELD");
        assertThat(receipt.label())
                .as("the campaign's own, tenant-authored name")
                .isEqualTo("Autumn buffet");
        assertThat(receipt.channel()).isEqualTo("SMS");
        CustomerHistoryEntry redemption = entries.stream()
                .filter(entry -> entry.kind() == Kind.PROMO_REDEMPTION)
                .findFirst()
                .orElseThrow();
        assertThat(redemption.statusCode()).isEqualTo("REDEEMED");
        assertThat(redemption.orderId()).isEqualTo(order);
        assertThat(redemption.occurredAt())
                .as("when it was redeemed, not when it was reserved")
                .isEqualTo(BASE.plusSeconds(50));
    }

    @Test
    @DisplayName("marketing: `before` applies to redemptions as well as receipts")
    void engagementHonoursBefore() {
        UUID campaign = campaign("Autumn buffet");
        recipient(campaign, guest, "REFUSED", "SUPPRESSED", null, BASE.plusSeconds(100));
        CustomerDiscountHistoryPort discounts = (tenantId, accountId) -> List.of(new Redemption(
                UUID.randomUUID(),
                BRAND,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                "Autumn 10%",
                null,
                Redemption.Status.RESERVED,
                0,
                "UZS",
                BASE.plusSeconds(10),
                null,
                null));

        List<CustomerHistoryEntry> older =
                new JdbcCustomerEngagementHistory(jdbc, discounts).history(TENANT, guest, BASE.plusSeconds(50), 10);

        assertThat(older).extracting(CustomerHistoryEntry::kind).containsExactly(Kind.PROMO_REDEMPTION);
    }

    // -------------------------------------------------------------------- seeding

    private UUID notification(
            UUID accountId, String channel, String status, @Nullable String suppression, String template, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications.notifications (
                    id, tenant_id, brand_id, notification_class, channel, template_key, subject_type, subject_id,
                    recipient_account_id, idempotency_key, status, suppression_reason, created_at)
                VALUES (:id, :t, :brand, 'TRANSACTIONAL_REQUIRED', :channel, :template, 'Order', :orderId,
                        :accountId, :key, :status, :suppression, :at)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("brand", BRAND)
                .param("channel", channel)
                .param("template", template)
                .param("orderId", order)
                .param("accountId", accountId)
                .param("key", UUID.randomUUID().toString())
                .param("status", status)
                .param("suppression", suppression)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID review(UUID accountId, int rating, @Nullable String comment, Instant at) {
        return review(accountId, rating, comment, at, order);
    }

    private UUID review(UUID accountId, int rating, @Nullable String comment, Instant at, UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reviews.order_reviews (
                    id, tenant_id, brand_id, location_id, order_id, customer_account_id, rating,
                    comment_protected, submitted_at)
                VALUES (:id, :t, :brand, :location, :orderId, :accountId, :rating, :comment, :at)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("brand", BRAND)
                .param("location", LOCATION)
                .param("orderId", orderId)
                .param("accountId", accountId)
                .param("rating", rating)
                .param("comment", comment)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID campaign(String name) {
        UUID audience = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.audiences (id, tenant_id, brand_id, name, created_by)
                VALUES (:id, :t, :brand, :name, :by)
                """)
                .param("id", audience)
                .param("t", TENANT)
                .param("brand", BRAND)
                .param("name", "Everyone " + audience)
                .param("by", UUID.randomUUID())
                .update();
        UUID campaign = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.campaigns (id, tenant_id, brand_id, name, channel, consent_purpose, audience_id,
                    template_key, recipient_cap, created_by)
                VALUES (:id, :t, :brand, :name, 'SMS', 'MARKETING_PROMOTIONS', :audience, 'promo.autumn', 100, :by)
                """)
                .param("id", campaign)
                .param("t", TENANT)
                .param("brand", BRAND)
                .param("name", name)
                .param("audience", audience)
                .param("by", UUID.randomUUID())
                .update();
        return campaign;
    }

    private void recipient(
            UUID campaign, UUID accountId, String status, String refusal, @Nullable UUID notification, Instant at) {
        jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status, refusal_reason, notification_id,
                    created_at)
                VALUES (:campaign, :t, :accountId, :sequence, :status, :refusal, :notification, :at)
                """)
                .param("campaign", campaign)
                .param("t", TENANT)
                .param("accountId", accountId)
                .param("sequence", accountId.equals(guest) ? 1 : 2)
                .param("status", status)
                .param("refusal", refusal)
                .param("notification", notification)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }
}
