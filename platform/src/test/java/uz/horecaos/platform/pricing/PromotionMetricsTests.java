package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.pricing.PromotionDbFixture.BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;
import static uz.horecaos.platform.pricing.PromotionDbFixture.condition;
import static uz.horecaos.platform.pricing.PromotionDbFixture.definition;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort.Result.Outcome;
import uz.horecaos.platform.pricing.application.PromotionInputResolver;
import uz.horecaos.platform.pricing.application.PromotionMetrics;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0140, observability: the promotion engine's counters, read off the real quote path and the real
 * ledger rather than off a hand-fed trace.
 *
 * <p>What would still be true if the counters were wrong: a quote would still price, so these assert the
 * count of each kind of event against what the fixture made happen, that the simulator (which prices
 * through the same engine) counts nothing, and that no label carries an identifier.
 */
class PromotionMetricsTests {

    private static TestDatabase.Handle db;

    private PromotionDbFixture fixture;
    private MeterRegistry registry;
    private TransactionTemplate transaction;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the promotion metrics");
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
        fixture = new PromotionDbFixture(db, TASHKENT_LUNCH);
        registry = fixture.metrics.registry();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
    }

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private void activateLunch() {
        fixture.activate(definition(
                "LUNCH",
                Promotion.Scope.ORDER,
                "lunch",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.TIME_OF_DAY,
                        "fromMinuteOfDay",
                        12 * 60L,
                        "toMinuteOfDay",
                        15 * 60L)),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));
    }

    @Test
    @DisplayName("a real quote counts each rule it judged, those that fired, and those that did not with the reason")
    void aQuoteCountsEvaluatedFiredAndRefused() {
        activateLunch();

        fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));
        assertThat(count(PromotionMetrics.EVALUATED)).as("12:30 local: judged").isEqualTo(1);
        assertThat(count(PromotionMetrics.FIRED)).as("and applied").isEqualTo(1);
        assertThat(count(PromotionMetrics.REFUSED)).isZero();

        // 17:30 in Tashkent: the same rule is judged and does not apply.
        fixture.clock.set(Instant.parse("2026-10-01T12:30:00Z"));
        fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));
        assertThat(count(PromotionMetrics.EVALUATED)).isEqualTo(2);
        assertThat(count(PromotionMetrics.FIRED)).as("still one").isEqualTo(1);
        assertThat(count(PromotionMetrics.REFUSED, "reason", "condition_failed"))
                .as("refused, because the window had closed")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the simulator prices through the same engine and counts nothing")
    void theSimulatorIsNotCounted() {
        activateLunch();

        fixture.quotes.simulate(
                fixture.cart(Map.of(fixture.margheritaVariant, 2)),
                new PromotionInputResolver.Overrides(null, null, null, null, null, Set.of(), List.of(), Map.of()));

        assertThat(count(PromotionMetrics.EVALUATED)).isZero();
        assertThat(count(PromotionMetrics.FIRED)).isZero();
    }

    @Test
    @DisplayName("a checkout's claim counts a redemption, and the last slot running out counts a refusal")
    void theLedgerCountsClaimedAndRefusedRedemptions() {
        var limited = definition(
                "ONLY1",
                Promotion.Scope.ORDER,
                "g-only1",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L)));
        fixture.activate(new PromotionDefinition(
                limited.code(),
                limited.name(),
                limited.kind(),
                limited.scope(),
                limited.stackingGroup(),
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                1,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                limited.conditions(),
                limited.actions()));
        var first = fixture.quotes.quote(
                fixture.cartFor(CUSTOMER, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), null));
        var second = fixture.quotes.quote(
                fixture.cartFor(CUSTOMER, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), null));

        var won = transaction.execute(status -> fixture.ledger.claimForQuote(
                TENANT, BRAND, first.quoteId(), UUID.randomUUID(), CUSTOMER, fixture.clock.instant()));
        var lost = transaction.execute(status -> fixture.ledger.claimForQuote(
                TENANT, BRAND, second.quoteId(), UUID.randomUUID(), CUSTOMER, fixture.clock.instant()));

        assertThat(won.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(lost.outcome()).isEqualTo(Outcome.LIMIT_REACHED);
        assertThat(count(PromotionMetrics.REDEMPTIONS, "source", "automatic", "outcome", "claimed"))
                .isEqualTo(1);
        assertThat(count(PromotionMetrics.REDEMPTIONS, "source", "automatic", "outcome", "limit"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("no label on any promotion meter can carry a tenant, brand, promotion, order or account")
    void labelsAreBounded() {
        activateLunch();
        fixture.quotes.quote(fixture.cartFor(CUSTOMER, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), null));
        fixture.clock.set(Instant.parse("2026-10-01T12:30:00Z"));
        fixture.quotes.quote(fixture.cartFor(CUSTOMER, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), null));

        List<Meter> promoMeters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("horecaos.promo."))
                .toList();
        assertThat(promoMeters).isNotEmpty();
        assertThat(promoMeters).flatExtracting(meter -> meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("reason", "source", "outcome");
            assertThat(tag.getValue())
                    .as("a label value is a fixed word, never an id")
                    .matches("[a-z_]+");
        });
    }
}
