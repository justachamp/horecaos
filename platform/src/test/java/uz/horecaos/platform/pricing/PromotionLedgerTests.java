package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.pricing.PromotionDbFixture.BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;
import static uz.horecaos.platform.pricing.PromotionDbFixture.condition;
import static uz.horecaos.platform.pricing.PromotionDbFixture.definition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort.Result.Outcome;
import uz.horecaos.platform.pricing.api.PromotionRedemptionSource;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0140: the redemption ledger and the usage limits.
 *
 * <p>The claim is the part that has to be right under concurrency, so the two
 * races the record names are run for real, on two threads against a database: the
 * last slot of a limited promotion has exactly one winner, and two checkouts by the
 * same account of a {@code FIRST} promotion have exactly one winner. The ledger
 * rows are asserted against the quote's own adjustments, because "the row says what
 * the order was given" is only worth anything if the row agrees with the evidence.
 */
class PromotionLedgerTests {

    private static TestDatabase.Handle db;

    private PromotionDbFixture fixture;
    private TransactionTemplate transaction;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the promotion ledger");
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
        transaction = new TransactionTemplate(new DataSourceTransactionManager(fixture.dataSource));
    }

    private JdbcPromotionRow limited(String code, @Nullable Integer total, @Nullable Integer perCustomer) {
        var def = definition(
                code,
                Promotion.Scope.ORDER,
                "g-" + code,
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L)));
        var row = fixture.activate(new PromotionDefinition(
                def.code(),
                def.name(),
                def.kind(),
                def.scope(),
                def.stackingGroup(),
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                total,
                perCustomer,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                def.conditions(),
                def.actions()));
        return new JdbcPromotionRow(row.id());
    }

    private record JdbcPromotionRow(UUID id) {}

    private Quote quoteFor(@Nullable UUID account) {
        return fixture.quotes.quote(fixture.cartFor(account, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), null));
    }

    private PromotionRedemptionPort.Result claim(Quote quote, UUID orderId, @Nullable UUID account) {
        return transaction.execute(status -> fixture.ledger.claimForQuote(
                TENANT, BRAND, quote.quoteId(), orderId, account, fixture.clock.instant()));
    }

    // --------------------------------------------------------------- the claim

    @Test
    @DisplayName("a checkout writes one ledger row per promotion and claims the counters of a limited one")
    void aClaimWritesTheLedgerAndMovesTheCounters() {
        var promotion = limited("LIM", 10, 2);
        var unlimited = fixture.activate(definition(
                "FREE",
                Promotion.Scope.ORDER,
                "other",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_FIXED_DISCOUNT, "amountMinor", 1_000L))));
        Quote quote = quoteFor(CUSTOMER);
        UUID orderId = UUID.randomUUID();

        PromotionRedemptionPort.Result result = claim(quote, orderId, CUSTOMER);

        assertThat(result.outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(rows(orderId)).hasSize(2);
        assertThat(consumed(promotion.id()))
                .as("a limited promotion's total is claimed")
                .isEqualTo(1);
        assertThat(customerUsage(promotion.id(), CUSTOMER)).isEqualTo(1);
        assertThat(consumed(unlimited.id()))
                .as("an unlimited one touches no counter")
                .isZero();
        assertThat(fixture.jdbc
                        .sql("SELECT count(*) FROM pricing.promotion_customer_usage WHERE promotion_id = :id")
                        .param("id", unlimited.id())
                        .query(Long.class)
                        .single())
                .isZero();

        // Every row agrees with the quote's own evidence: a ledger that disagrees with the adjustments
        // is a report nobody can reconcile.
        for (Map<String, Object> row : rows(orderId)) {
            UUID promotionId = java.util.Objects.requireNonNull((UUID) row.get("promotion_id"));
            assertThat(row.get("discount_minor")).isEqualTo(-1 * sumOfAdjustments(quote.quoteId(), promotionId));
            assertThat(row.get("claimed_quote_id")).isEqualTo(quote.quoteId());
            assertThat(row.get("current_quote_id")).isEqualTo(quote.quoteId());
            assertThat(row.get("status")).isEqualTo("REDEEMED");
        }
    }

    @Test
    @DisplayName("a retried checkout finds its own rows and claims nothing twice")
    void aRetriedClaimIsIdempotent() {
        var promotion = limited("RETRY", 5, 5);
        Quote quote = quoteFor(CUSTOMER);
        UUID orderId = UUID.randomUUID();

        claim(quote, orderId, CUSTOMER);
        claim(quote, orderId, CUSTOMER);

        assertThat(rows(orderId)).hasSize(1);
        assertThat(consumed(promotion.id())).isEqualTo(1);
        assertThat(customerUsage(promotion.id(), CUSTOMER)).isEqualTo(1);
    }

    @Test
    @DisplayName("release gives back exactly what the claim took")
    void aReleaseGivesTheCountersBack() {
        var promotion = limited("GIVEBACK", 5, 5);
        Quote quote = quoteFor(CUSTOMER);
        UUID orderId = UUID.randomUUID();
        claim(quote, orderId, CUSTOMER);

        boolean released = transaction.execute(status -> fixture.ledger.releaseForQuote(TENANT, quote.quoteId()));

        assertThat(released).isTrue();
        assertThat(rows(orderId)).isEmpty();
        assertThat(consumed(promotion.id())).isZero();
        assertThat(customerUsage(promotion.id(), CUSTOMER)).isZero();
    }

    @Test
    @DisplayName("a per-customer limit refuses the customer's next order and returns the total it had taken")
    void thePerCustomerLimitRefusesAndCompensates() {
        var promotion = limited("ONEEACH", 10, 1);
        Quote first = quoteFor(CUSTOMER);
        Quote second = quoteFor(CUSTOMER);
        claim(first, UUID.randomUUID(), CUSTOMER);

        PromotionRedemptionPort.Result refused = claim(second, UUID.randomUUID(), CUSTOMER);

        assertThat(refused.outcome()).isEqualTo(Outcome.PER_CUSTOMER_LIMIT_REACHED);
        assertThat(consumed(promotion.id()))
                .as("the second claim took the total first and gave it back when the customer slot failed")
                .isEqualTo(1);
        assertThat(claim(quoteFor(OTHER_CUSTOMER), UUID.randomUUID(), OTHER_CUSTOMER)
                        .outcome())
                .as("another customer still has their own slot")
                .isEqualTo(Outcome.CLAIMED);
    }

    @Test
    @DisplayName(
            "a claim refused for a later promotion leaves no ledger row and no counter behind, and a retry is refused again")
    void aRefusedClaimLeavesNothingBehindAndTheRetryIsRefusedAgain() {
        // Promotions are claimed in id order, and ids are minted in order: the unlimited one is
        // claimed first, so its row is written before the limited one refuses.
        var open = limited("OPENFIRST", null, null);
        var lastSlot = limited("LASTSLOT2", 1, null);
        assertThat(open.id().compareTo(lastSlot.id())).isNegative();
        Quote mine = quoteFor(CUSTOMER);
        assertThat(fixture.promotionStore.promotionAmountsOnQuote(TENANT, mine.quoteId()))
                .as("the cart was priced with both promotions")
                .hasSize(2);
        // Someone else takes the last slot between this customer's pricing and checkout.
        assertThat(claim(quoteFor(OTHER_CUSTOMER), UUID.randomUUID(), OTHER_CUSTOMER)
                        .outcome())
                .isEqualTo(Outcome.CLAIMED);
        UUID orderId = UUID.randomUUID();

        PromotionRedemptionPort.Result refused = claim(mine, orderId, CUSTOMER);

        assertThat(refused.outcome()).isEqualTo(Outcome.LIMIT_REACHED);
        assertThat(refused.promotionId()).isEqualTo(lastSlot.id());
        assertThat(rows(orderId))
                .as("the refusal commits, so a row written for the earlier promotion would outlive it")
                .isEmpty();
        assertThat(claimedRows(mine.quoteId())).isZero();
        assertThat(consumed(lastSlot.id()))
                .as("the other customer's slot is still theirs")
                .isEqualTo(1);

        PromotionRedemptionPort.Result retried = claim(mine, UUID.randomUUID(), CUSTOMER);

        assertThat(retried.outcome())
                .as("with a leftover row the retry reports CLAIMED and spends no slot on the limited promotion")
                .isEqualTo(Outcome.LIMIT_REACHED);
        assertThat(consumed(lastSlot.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("a per-customer refusal on a later promotion gives back the earlier ones' rows and counters too")
    void aPerCustomerRefusalLeavesNothingBehind() {
        var open = limited("OPENCOUNTED", 5, null);
        var eachOnce = limited("EACHONCE", 10, 1);
        assertThat(open.id().compareTo(eachOnce.id())).isNegative();
        Quote first = quoteFor(CUSTOMER);
        Quote second = quoteFor(CUSTOMER);
        assertThat(claim(first, UUID.randomUUID(), CUSTOMER).outcome()).isEqualTo(Outcome.CLAIMED);
        UUID orderId = UUID.randomUUID();

        PromotionRedemptionPort.Result refused = claim(second, orderId, CUSTOMER);

        assertThat(refused.outcome()).isEqualTo(Outcome.PER_CUSTOMER_LIMIT_REACHED);
        assertThat(rows(orderId)).isEmpty();
        assertThat(claimedRows(second.quoteId())).isZero();
        assertThat(consumed(open.id()))
                .as("only the first order's slot is spent")
                .isEqualTo(1);
        assertThat(consumed(eachOnce.id())).isEqualTo(1);
        assertThat(customerUsage(eachOnce.id(), CUSTOMER)).isEqualTo(1);
    }

    @Test
    @DisplayName("a per-customer cap raised after the customer's first redemption is the cap the claim enforces")
    void aRaisedPerCustomerCapIsEnforcedAtCheckout() {
        var promotion = limited("RAISED", 10, 1);
        assertThat(claim(quoteFor(CUSTOMER), UUID.randomUUID(), CUSTOMER).outcome())
                .isEqualTo(Outcome.CLAIMED);
        assertThat(fixture.promotionStore.limitReached(TENANT, BRAND, CUSTOMER))
                .as("pricing already treats the customer as done with it")
                .contains(promotion.id());

        // The marketer suspends it, allows two each, and puts it back through validation.
        editLimits(promotion.id(), 10, 2);

        assertThat(fixture.promotionStore.limitReached(TENANT, BRAND, CUSTOMER))
                .as("pricing now offers it to the customer again")
                .doesNotContain(promotion.id());
        Quote second = quoteFor(CUSTOMER);
        assertThat(second.discount().minor())
                .as("the cart is priced with the promotion")
                .isPositive();

        assertThat(claim(second, UUID.randomUUID(), CUSTOMER).outcome())
                .as("the claim agrees with pricing; a cap frozen at the first claim refused this on every retry")
                .isEqualTo(Outcome.CLAIMED);
        assertThat(customerUsage(promotion.id(), CUSTOMER)).isEqualTo(2);
        assertThat(fixture.promotionStore.limitReached(TENANT, BRAND, CUSTOMER)).contains(promotion.id());
    }

    @Test
    @DisplayName("a per-customer cap lowered after the customer was priced is the cap the claim enforces")
    void aLoweredPerCustomerCapIsEnforcedAtCheckout() {
        var promotion = limited("LOWERED", 10, 3);
        assertThat(claim(quoteFor(CUSTOMER), UUID.randomUUID(), CUSTOMER).outcome())
                .isEqualTo(Outcome.CLAIMED);
        Quote priced = quoteFor(CUSTOMER);
        assertThat(priced.discount().minor()).isPositive();

        editLimits(promotion.id(), 10, 1);

        assertThat(claim(priced, UUID.randomUUID(), CUSTOMER).outcome())
                .as("one redemption already used, and the cap is one now")
                .isEqualTo(Outcome.PER_CUSTOMER_LIMIT_REACHED);
        assertThat(customerUsage(promotion.id(), CUSTOMER)).isEqualTo(1);
        assertThat(consumed(promotion.id()))
                .as("the total it took is given back")
                .isEqualTo(1);
    }

    /** Suspend, edit the two limits, validate and activate again, the way the console does. */
    private void editLimits(UUID id, @Nullable Integer total, @Nullable Integer perCustomer) {
        var authoring = fixture.authoring;
        var suspended = authoring.suspend(
                TENANT,
                BRAND,
                id,
                fixture.promotionStore.find(TENANT, BRAND, id).orElseThrow().version());
        var d = suspended.definition();
        var edited = authoring.update(
                TENANT,
                BRAND,
                id,
                suspended.version(),
                new PromotionDefinition(
                        d.code(),
                        d.name(),
                        d.kind(),
                        d.scope(),
                        d.stackingGroup(),
                        d.exclusive(),
                        d.priority(),
                        d.requiresCoupon(),
                        d.maximumDiscountMinor(),
                        d.currency(),
                        d.validFrom(),
                        d.validUntil(),
                        total,
                        perCustomer,
                        d.loyaltyAccrual(),
                        d.loyaltyRedemption(),
                        d.conditions(),
                        d.actions()));
        var validated = authoring.validate(TENANT, BRAND, id, edited.version());
        assertThat(validated.report().isValid()).isTrue();
        assertThat(authoring
                        .activate(TENANT, BRAND, id, validated.promotion().version(), "edit limits")
                        .isPending())
                .isFalse();
    }

    @Test
    @DisplayName("a guest is never refused for a per-customer cap, as decided for coupons")
    void aGuestHasNoPerCustomerCap() {
        var promotion = limited("GUESTS", 10, 1);

        assertThat(claim(quoteFor(null), UUID.randomUUID(), null).outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(claim(quoteFor(null), UUID.randomUUID(), null).outcome()).isEqualTo(Outcome.CLAIMED);
        assertThat(consumed(promotion.id())).isEqualTo(2);
    }

    // ---------------------------------------------------------------- the races

    @Test
    @DisplayName("two checkouts racing for the last slot: exactly one wins")
    void theLastSlotHasOneWinner() throws Exception {
        var promotion = limited("LASTSLOT", 1, null);
        Quote a = quoteFor(CUSTOMER);
        Quote b = quoteFor(OTHER_CUSTOMER);

        List<Outcome> outcomes = race(
                () -> claim(a, UUID.randomUUID(), CUSTOMER).outcome(),
                () -> claim(b, UUID.randomUUID(), OTHER_CUSTOMER).outcome());

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.CLAIMED, Outcome.LIMIT_REACHED);
        assertThat(consumed(promotion.id())).isEqualTo(1);
        assertThat(fixture.jdbc
                        .sql("SELECT count(*) FROM pricing.promotion_redemptions WHERE promotion_id = :id")
                        .param("id", promotion.id())
                        .query(Long.class)
                        .single())
                .as("one winner, one ledger row")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("two checkouts by one account of a FIRST promotion: exactly one is first")
    void twoQuickOrdersCannotBothBeFirst() throws Exception {
        var seq = definition(
                "FIRSTONLY",
                Promotion.Scope.ORDER,
                "first",
                List.of(condition(1, Promotion.Condition.Type.ORDER_SEQUENCE, "mode", "FIRST", "basis", "BRAND")),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L)));
        var promotion = fixture.activate(new PromotionDefinition(
                seq.code(),
                seq.name(),
                seq.kind(),
                seq.scope(),
                seq.stackingGroup(),
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                1,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                seq.conditions(),
                seq.actions()));
        fixture.brandOrders.set(0);
        // Both quotes are priced before either checks out, so both read "this will be the first order".
        Quote a = quoteFor(CUSTOMER);
        Quote b = quoteFor(CUSTOMER);
        assertThat(a.discount().minor()).isEqualTo(9_000L);
        assertThat(b.discount().minor()).isEqualTo(9_000L);

        List<Outcome> outcomes = race(
                () -> claim(a, UUID.randomUUID(), CUSTOMER).outcome(),
                () -> claim(b, UUID.randomUUID(), CUSTOMER).outcome());

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.CLAIMED, Outcome.PER_CUSTOMER_LIMIT_REACHED);
        assertThat(customerUsage(promotion.id(), CUSTOMER)).isEqualTo(1);
    }

    // -------------------------------------------------------------- amendments

    @Test
    @DisplayName(
            "an amended order moves its ledger row in place: one row, the checkout quote kept, amounts equal to the new quote's adjustments")
    void anAmendmentMovesTheRowInPlace() {
        var promotion = limited("AMENDED", 10, 2);
        Quote checkout = quoteFor(CUSTOMER);
        UUID orderId = UUID.randomUUID();
        claim(checkout, orderId, CUSTOMER);
        assertThat(rows(orderId).get(0).get("discount_minor")).isEqualTo(9_000L);

        // The amendment's quote: three pizzas, repriced under the order's recorded inputs.
        Quote amended = fixture.quotes.quote(new QuoteRequest(
                TENANT,
                BRAND,
                PromotionDbFixture.LOCATION,
                CUSTOMER,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariant, 3, List.of())),
                null,
                null,
                null,
                orderId,
                new QuoteRequest.Frame(null, null, "PICKUP", checkout.quoteId(), TASHKENT_LUNCH)));
        assertThat(amended.discount().minor()).as("10% of 135 000").isEqualTo(13_500L);

        transaction.executeWithoutResult(status -> fixture.ledger.restateForOrder(
                TENANT, BRAND, orderId, amended.quoteId(), 2, CUSTOMER, fixture.clock.instant()));

        List<Map<String, Object>> rows = rows(orderId);
        assertThat(rows).as("still one row for this (order, promotion)").hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("claimed_quote_id", checkout.quoteId())
                .containsEntry("current_quote_id", amended.quoteId())
                .containsEntry("last_revision", 2)
                .containsEntry("discount_minor", 13_500L)
                .containsEntry("status", "REDEEMED");
        assertThat(consumed(promotion.id())).as("an amendment claims no slot").isEqualTo(1);
        assertThat(customerUsage(promotion.id(), CUSTOMER)).isEqualTo(1);
        assertThat(rows.get(0).get("discount_minor"))
                .isEqualTo(-1 * sumOfAdjustments(amended.quoteId(), promotion.id()));
    }

    @Test
    @DisplayName(
            "a promotion that stops applying is RELEASED and its counter stays consumed; it comes back as the same row")
    void aPromotionThatStopsApplyingIsReleasedAndComesBack() {
        var promotion = fixture.activate(new PromotionDefinition(
                "MIN90",
                "Minimum 90 000",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "min",
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                10,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(condition(1, Promotion.Condition.Type.SUBTOTAL_AT_LEAST, "amountMinor", 90_000L)),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));
        Quote checkout = quoteFor(CUSTOMER);
        UUID orderId = UUID.randomUUID();
        claim(checkout, orderId, CUSTOMER);

        QuoteRequest.Frame frame = new QuoteRequest.Frame(null, null, "PICKUP", checkout.quoteId(), TASHKENT_LUNCH);
        Quote small = fixture.quotes.quote(new QuoteRequest(
                TENANT,
                BRAND,
                PromotionDbFixture.LOCATION,
                CUSTOMER,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.colaVariant, 1, List.of())),
                null,
                null,
                null,
                orderId,
                frame));
        assertThat(small.discount().minor())
                .as("12 000 does not meet the 90 000 minimum")
                .isZero();
        transaction.executeWithoutResult(status -> fixture.ledger.restateForOrder(
                TENANT, BRAND, orderId, small.quoteId(), 2, CUSTOMER, fixture.clock.instant()));

        assertThat(rows(orderId).get(0)).containsEntry("status", "RELEASED");
        assertThat(consumed(promotion.id()))
                .as("the slot is not returned, the rule a cancellation follows too")
                .isEqualTo(1);

        Quote big = fixture.quotes.quote(new QuoteRequest(
                TENANT,
                BRAND,
                PromotionDbFixture.LOCATION,
                CUSTOMER,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariant, 2, List.of())),
                null,
                null,
                null,
                orderId,
                frame));
        transaction.executeWithoutResult(status -> fixture.ledger.restateForOrder(
                TENANT, BRAND, orderId, big.quoteId(), 3, CUSTOMER, fixture.clock.instant()));

        assertThat(rows(orderId)).hasSize(1);
        assertThat(rows(orderId).get(0)).containsEntry("status", "REDEEMED").containsEntry("discount_minor", 9_000L);
        assertThat(consumed(promotion.id()))
                .as("it never claimed a second slot")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the day-close source returns redeemed rows in the window and never a coupon word")
    void theFactSourceReturnsTheLedgerInAWindow() {
        limited("SRC", 10, 10);
        Quote quote = quoteFor(CUSTOMER);
        UUID orderId = UUID.randomUUID();
        claim(quote, orderId, CUSTOMER);

        List<PromotionRedemptionSource.Redemption> inWindow = fixture.ledger.redeemedBetween(
                TENANT, TASHKENT_LUNCH.minus(Duration.ofHours(1)), TASHKENT_LUNCH.plus(Duration.ofHours(1)));
        List<PromotionRedemptionSource.Redemption> outside = fixture.ledger.redeemedBetween(
                TENANT, TASHKENT_LUNCH.plus(Duration.ofHours(2)), TASHKENT_LUNCH.plus(Duration.ofHours(3)));

        assertThat(inWindow).singleElement().satisfies(redemption -> {
            assertThat(redemption.orderId()).isEqualTo(orderId);
            assertThat(redemption.kind()).isEqualTo(PromotionRedemptionSource.Redemption.Kind.AUTOMATIC);
            assertThat(redemption.couponId()).isNull();
            assertThat(redemption.promotionCode()).isEqualTo("SRC");
            assertThat(redemption.discountMinor()).isEqualTo(9_000L);
        });
        assertThat(outside).isEmpty();
    }

    // ----------------------------------------------------------------- helpers

    private List<Outcome> race(
            java.util.concurrent.Callable<Outcome> first, java.util.concurrent.Callable<Outcome> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Outcome> one = pool.submit(() -> {
                ready.countDown();
                go.await();
                return first.call();
            });
            Future<Outcome> two = pool.submit(() -> {
                ready.countDown();
                go.await();
                return second.call();
            });
            ready.await();
            go.countDown();
            return List.of(one.get(), two.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private long claimedRows(UUID quoteId) {
        return fixture.jdbc
                .sql("SELECT count(*) FROM pricing.promotion_redemptions WHERE claimed_quote_id = :quote")
                .param("quote", quoteId)
                .query(Long.class)
                .single();
    }

    private List<Map<String, Object>> rows(UUID orderId) {
        return fixture.jdbc
                .sql("SELECT * FROM pricing.promotion_redemptions WHERE order_id = :id ORDER BY promotion_id")
                .param("id", orderId)
                .query()
                .listOfRows();
    }

    private int consumed(UUID promotionId) {
        return fixture.jdbc
                .sql("SELECT consumed_count FROM pricing.promotions WHERE id = :id")
                .param("id", promotionId)
                .query(Integer.class)
                .single();
    }

    private int customerUsage(UUID promotionId, UUID account) {
        return fixture.jdbc
                .sql("SELECT COALESCE(sum(consumed_count), 0) FROM pricing.promotion_customer_usage "
                        + "WHERE promotion_id = :id AND customer_account_id = :account")
                .param("id", promotionId)
                .param("account", account)
                .query(Integer.class)
                .single();
    }

    private long sumOfAdjustments(UUID quoteId, UUID promotionId) {
        return fixture.jdbc
                .sql("SELECT COALESCE(sum(amount_minor), 0) FROM pricing.quote_adjustments "
                        + "WHERE quote_id = :quote AND source_id = :promotion AND source_type = 'PROMOTION'")
                .param("quote", quoteId)
                .param("promotion", promotionId)
                .query(Long.class)
                .single();
    }
}
