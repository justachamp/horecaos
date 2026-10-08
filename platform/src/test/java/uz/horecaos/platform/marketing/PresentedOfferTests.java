package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.marketing.ScenarioHarness.BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.START;
import static uz.horecaos.platform.marketing.ScenarioHarness.TENANT;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.marketing.api.MarketingConfigurationKeys;
import uz.horecaos.platform.marketing.application.PresentedOfferService;
import uz.horecaos.platform.marketing.application.PresentedOfferService.Banner;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.DecisionRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The in-app half of a scenario (ADR 0112): a banner a storefront polls for, capped per day
 * by its own ADR 0030 key because it has no delivery attempt to count against the messaging
 * cap, and an in-app step that sends no message at all.
 */
class PresentedOfferTests {

    private static TestDatabase.Handle db;

    private ScenarioHarness h;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for presented offer tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        h = new ScenarioHarness(db);
    }

    @Test
    @DisplayName("an offer is presented to a guest once per surface, and a poll returns it in the storefront's shape")
    void presentingIsIdempotentAndPollingReturnsTheBanner() {
        UUID guest = h.reachableGuest("+998901300001");
        UUID offer = h.publishedOffer("Autumn ten");

        assertThat(present(guest, offer)).isTrue();
        assertThat(present(guest, offer)).isFalse();
        assertThat(rows(guest)).isEqualTo(1);

        List<Banner> banners = h.presented.poll(TENANT, BRAND, guest, PresentedOfferService.STOREFRONT);
        assertThat(banners).singleElement().satisfies(banner -> {
            assertThat(banner.offerId()).isEqualTo(offer);
            assertThat(banner.name()).isEqualTo("Autumn ten");
            assertThat(banner.priority()).isZero();
            assertThat(banner.presentedOfferId()).isNotNull();
        });
    }

    @Test
    @DisplayName("a banner is shown at most three times a day by default, and again the next local day")
    void theDefaultCapIsThreeAndResetsWithTheDay() {
        UUID guest = h.reachableGuest("+998901300002");
        present(guest, h.publishedOffer("Autumn ten"));

        for (int poll = 1; poll <= 3; poll++) {
            assertThat(poll(guest)).as("poll %d", poll).hasSize(1);
        }
        assertThat(poll(guest)).as("the fourth poll of the day").isEmpty();
        assertThat(shownCount(guest)).isEqualTo(3);

        // 00:30 in Tashkent, the next calendar day in the brand's zone.
        h.clock.set(Instant.parse("2026-08-22T19:30:00Z"));
        assertThat(poll(guest)).hasSize(1);
        assertThat(shownCount(guest)).isEqualTo(4);
    }

    @Test
    @DisplayName("the cap is the tenant's to set: one a day shows once")
    void theCapFollowsTheConfiguredKey() {
        UUID guest = h.reachableGuest("+998901300003");
        h.configuration.set(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY_CODE, 1);
        present(guest, h.publishedOffer("Autumn ten"));

        assertThat(poll(guest)).hasSize(1);
        assertThat(poll(guest)).isEmpty();
    }

    @Test
    @DisplayName("each banner has its own count against the cap")
    void eachBannerIsCountedOnItsOwn() {
        UUID guest = h.reachableGuest("+998901300004");
        h.configuration.set(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY_CODE, 1);
        present(guest, h.publishedOffer("Autumn ten"));
        present(guest, h.publishedOffer("Weekend twenty"));

        assertThat(poll(guest)).extracting(Banner::name).containsExactly("Autumn ten", "Weekend twenty");
        assertThat(poll(guest)).isEmpty();
    }

    @Test
    @DisplayName(
            "a dismissed banner is not shown again, only its own guest can dismiss it, and dismissing twice is harmless")
    void dismissal() {
        UUID guest = h.reachableGuest("+998901300005");
        UUID other = h.reachableGuest("+998901300006");
        UUID offer = h.publishedOffer("Autumn ten");
        present(guest, offer);
        present(other, offer);
        UUID presentedId = poll(guest).getFirst().presentedOfferId();

        assertThat(h.presented.dismiss(TENANT, other, presentedId)).isFalse();
        assertThat(h.presented.dismiss(OTHER_TENANT, guest, presentedId)).isFalse();
        assertThat(h.presented.dismiss(TENANT, guest, presentedId)).isTrue();
        assertThat(h.presented.dismiss(TENANT, guest, presentedId)).isFalse();

        assertThat(poll(guest)).isEmpty();
        assertThat(poll(other)).hasSize(1);
    }

    @Test
    @DisplayName("a retired offer, another surface, another brand and another tenant show nothing")
    void scope() {
        UUID guest = h.reachableGuest("+998901300007");
        UUID retired = h.publishedOffer("Gone");
        present(guest, retired);
        h.offerService.retire(
                TENANT,
                BRAND,
                retired,
                h.offerService.require(TENANT, BRAND, retired).rowVersion(),
                h.author,
                "Ended",
                "corr");
        assertThat(poll(guest)).isEmpty();

        UUID live = h.publishedOffer("Live");
        present(guest, live);
        assertThat(poll(guest)).extracting(Banner::name).containsExactly("Live");
        assertThat(h.presented.poll(TENANT, BRAND, guest, PresentedOfferService.TELEGRAM_MINI_APP))
                .isEmpty();
        assertThat(h.presented.poll(TENANT, OTHER_BRAND, guest, PresentedOfferService.STOREFRONT))
                .isEmpty();
        assertThat(h.presented.poll(OTHER_TENANT, BRAND, guest, PresentedOfferService.STOREFRONT))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "an in-app step shows a banner and sends no message: nothing is spent, nothing counts against the messaging cap")
    void anInAppStepSendsNothing() {
        UUID guest = h.reachableGuest("+998901300008");
        UUID offer = h.publishedOffer("Autumn ten");
        UUID scenario = h.launched(h.draftScenario(
                null,
                ScenarioHarness.smsStep("MARKETING_PROMOTION", 0),
                ScenarioHarness.offerStep("IN_APP", offer, 600)));
        h.enrolEverybody(scenario);
        h.decide(scenario);
        h.clock.advance(Duration.ofMinutes(11));

        h.decide(scenario);

        List<DecisionRow> rows = h.decisions(scenario, guest);
        assertThat(rows).hasSize(2);
        DecisionRow inApp =
                rows.stream().filter(row -> row.stepSequence() == 2).findFirst().orElseThrow();
        assertThat(inApp.decision()).isEqualTo("SENT");
        assertThat(inApp.resolvedChannel()).isEqualTo("IN_APP");
        assertThat(inApp.attemptId()).isNull();
        // One text for the SMS step; the banner is not a message.
        assertThat(h.port.sent()).hasSize(1);
        assertThat(h.jdbc.sql("SELECT count(*) FROM marketing.marketing_sends WHERE customer_account_id = :id")
                        .param("id", guest)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(h.outcomeOf(scenario, guest)).isEqualTo("COMPLETED");

        // And the guest is now eligible for the banner, once, on the storefront.
        assertThat(rows(guest)).isEqualTo(1);
        assertThat(poll(guest)).extracting(Banner::offerId).containsExactly(offer);
    }

    @Test
    @DisplayName("a guest's presentations are listed newest first for the customer card, with how often each was shown")
    void historyForTheCustomerCard() {
        UUID guest = h.reachableGuest("+998901300009");
        UUID offer = h.publishedOffer("Autumn ten");
        present(guest, offer);
        poll(guest);
        poll(guest);

        var history = h.presentedStore.historyForGuest(TENANT, guest, 10);

        assertThat(history).singleElement().satisfies(row -> {
            assertThat(row.offerId()).isEqualTo(offer);
            assertThat(row.shownCount()).isEqualTo(2);
            assertThat(row.surface()).isEqualTo("STOREFRONT");
            assertThat(row.dismissedAt()).isNull();
        });
        assertThat(h.presentedStore.historyForGuest(OTHER_TENANT, guest, 10)).isEmpty();
    }

    // ----------------------------------------------------------------- helpers

    private boolean present(UUID guest, UUID offer) {
        return h.presented.present(TENANT, BRAND, null, offer, guest, PresentedOfferService.STOREFRONT, START);
    }

    private List<Banner> poll(UUID guest) {
        return h.presented.poll(TENANT, BRAND, guest, PresentedOfferService.STOREFRONT);
    }

    private int rows(UUID guest) {
        return h.jdbc.sql("SELECT count(*) FROM marketing.presented_offers WHERE customer_account_id = :id")
                .param("id", guest)
                .query(Integer.class)
                .single();
    }

    private int shownCount(UUID guest) {
        return h.jdbc.sql("SELECT sum(shown_count) FROM marketing.presented_offers WHERE customer_account_id = :id")
                .param("id", guest)
                .query(Integer.class)
                .single();
    }
}
