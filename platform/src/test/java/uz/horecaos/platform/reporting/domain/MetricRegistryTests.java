package uz.horecaos.platform.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR 0043: every number on every surface resolves to a versioned metric id, and
 * a definition means one thing everywhere.
 */
class MetricRegistryTests {

    @Test
    void everyMetricTheOperationsConsoleNamesResolves() {
        // The ids the operations prototype's Statistics screen puts beside each
        // figure. A surface naming a metric the registry does not define is the
        // build failure this test exists to be.
        List<String> named = List.of(
                "revenue.gross.v1",
                "revenue.net.v1",
                "average_check.v1",
                "orders.count.v1",
                "orders.cancelled.v1",
                "orders.late.v1",
                "prep_time.median.v1",
                "delivery_time.median.v1",
                "pickup_time.median.v1",
                "sla_bucket_set.v1",
                "channel_mix.count.v1",
                "receipt_depth.v1",
                "payment_mix.amount.v1",
                "orders.promised.v1",
                "handover_time.median.v1",
                "customers.new.v1",
                "customers.distinct.v1",
                "customers.repeat_share.v1",
                "customers.order_frequency.v1",
                "customers.value.v1",
                "customers.basket_depth.v1",
                "customers.ltv.v1",
                "revenue.new_vs_returning.v1",
                "delivery_distance.average.v1");

        assertThat(named)
                .allSatisfy(
                        code -> assertThat(MetricRegistry.find(code)).as(code).isPresent());
    }

    /**
     * Wave 9 w4-reports-distance-crm (7.1): the overview's distance tile is
     * an average sourced from {@code fact_order} directly, on the same
     * footing as the elapsed-time tiles beside it — never the typed {@code
     * /queries} pipeline, which reads pre-aggregated {@code agg_branch_day}.
     */
    @Test
    void deliveryDistanceAverageIsItsOwnEndpointNotComposedFromDayAggregates() {
        MetricDefinition distance = MetricRegistry.require("delivery_distance.average.v1");

        assertThat(distance.aggregation()).isEqualTo(MetricDefinition.Aggregation.AVERAGE);
        assertThat(distance.unit()).isEqualTo(MetricDefinition.MetricUnit.METERS);
        assertThat(distance.currencyRule()).isEqualTo(MetricDefinition.CurrencyRule.NONE);
        assertThat(distance.sourceAvailable()).isTrue();
        assertThat(distance.sourceFact()).contains("fact_order.delivery_distance_meters");
        assertThat(distance.definition()).isNotBlank();
        assertThat(distance.inclusion()).isNotBlank();
        assertThat(distance.exclusion()).isNotBlank();
    }

    /**
     * T13 (7.6/7.6a): a drift test naming each customer-grain metric and
     * checking its formula is actually published — the credibility argument
     * against Delever's unstated LTV only holds if every one of these keeps
     * a non-blank definition, inclusion, exclusion and refund treatment
     * rather than one quietly regressing to an empty string a future edit
     * would not notice.
     */
    @Test
    void everyCustomerAnalyticsMetricPublishesANonBlankFormula() {
        List<String> customerGrainMetrics = List.of(
                "customers.new.v1",
                "customers.distinct.v1",
                "customers.repeat_share.v1",
                "customers.order_frequency.v1",
                "customers.value.v1",
                "customers.basket_depth.v1",
                "customers.ltv.v1",
                "revenue.new_vs_returning.v1");

        assertThat(customerGrainMetrics).allSatisfy(code -> {
            MetricDefinition metric = MetricRegistry.require(code);
            assertThat(metric.definition()).as(code + " definition").isNotBlank();
            assertThat(metric.inclusion()).as(code + " inclusion").isNotBlank();
            assertThat(metric.exclusion()).as(code + " exclusion").isNotBlank();
            assertThat(metric.refundTreatment()).as(code + " refundTreatment").isNotBlank();
            assertThat(metric.sourceFact()).as(code + " sourceFact").isNotBlank();
        });
    }

    @Test
    void customersLtvIsDeclaredUnbuiltRatherThanApproximatedFromThePeriodFigure() {
        // T13 (7.6): the row's own credibility argument against Delever's
        // unstated LTV — an explicit "not built" beats a number that quietly
        // stops at the query range and calls itself lifetime value.
        MetricDefinition ltv = MetricRegistry.require("customers.ltv.v1");

        assertThat(ltv.sourceAvailable()).isFalse();
        assertThat(ltv.effectiveFrom()).as("an unbuilt metric governs no dates").isNull();
        assertThat(ltv.openQuestion()).contains("dim_customer");
        assertThat(ltv.isMoney()).isTrue();
        assertThat(ltv.grain().namesLegalEntity()).isTrue();
    }

    @Test
    void revenueByCustomerTypeIsBuiltAtTheCustomerTypeGrainAndIsMoney() {
        // T13 (7.6a): unlike payment_mix.amount.v1 and receipt_depth.v1
        // above, this one IS answered by the typed /queries pipeline — see
        // ReportQueryService#run's customer-type branch.
        MetricDefinition revenueByType = MetricRegistry.require("revenue.new_vs_returning.v1");

        assertThat(revenueByType.sourceAvailable()).isTrue();
        assertThat(revenueByType.grain()).isEqualTo(Grain.DAY_LOCATION_LEGAL_ENTITY_CUSTOMER_TYPE);
        assertThat(revenueByType.grain().dimensions()).contains(Grain.Dimension.CUSTOMER_TYPE);
        assertThat(revenueByType.isMoney()).isTrue();
        assertThat(revenueByType.grain().namesLegalEntity()).isTrue();
        assertThat(revenueByType.aggregation()).isEqualTo(MetricDefinition.Aggregation.SUM);
    }

    @Test
    void everyCustomerKpiTileMetricIsAnsweredByTheDedicatedEndpointNotQueries() {
        // T13 (7.6): the six built KPI-tile metrics all point callers at
        // GET .../reporting/customer-kpis rather than /queries — a
        // distinct-customer count cannot be correctly summed across the
        // channel/fulfilment rows /queries rolls agg_branch_day up from.
        List<String> kpiTileMetrics = List.of(
                "customers.new.v1",
                "customers.distinct.v1",
                "customers.repeat_share.v1",
                "customers.order_frequency.v1",
                "customers.value.v1",
                "customers.basket_depth.v1");

        assertThat(kpiTileMetrics).allSatisfy(code -> {
            MetricDefinition metric = MetricRegistry.require(code);
            assertThat(metric.sourceAvailable()).as(code).isTrue();
            assertThat(metric.openQuestion()).as(code).contains("customer-kpis");
        });
    }

    @Test
    void anUnknownMetricIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> MetricRegistry.require("revenue.gross.v9"))
                .isInstanceOf(MetricRegistry.UnknownMetricException.class)
                .hasMessageContaining("revenue.gross.v9");
    }

    @Test
    void everyMoneyMetricNamesTheLegalEntity() {
        // ADR 0038. A money metric defined at a grain that does not reach the
        // legal entity sums two taxpayers by construction, and no query-time check
        // can recover from a definition that was wrong to begin with.
        assertThat(MetricRegistry.all())
                .filteredOn(MetricDefinition::isMoney)
                .allSatisfy(metric -> assertThat(metric.grain().namesLegalEntity())
                        .as(metric.id().code())
                        .isTrue());
    }

    @Test
    void anUnbuiltMetricIsDeclaredAndSaysWhy() {
        MetricDefinition variance = MetricRegistry.require("delivery_cost_variance.v1");

        assertThat(variance.sourceAvailable())
                .as("declared so a surface renders it unbuilt rather than zero")
                .isFalse();
        assertThat(variance.openQuestion()).contains("ADR 0042");
        assertThat(variance.effectiveFrom())
                .as("an unbuilt metric governs no dates, and saying otherwise implies " + "figures exist")
                .isNull();
    }

    @Test
    void receiptDepthIsBuiltAtTheOperatorGrainAndNotMoney() {
        // T12 (7.5a): the registry entry backing GET .../operator-leaderboard's
        // avgItemsPerOrder. Declared here for provenance even though it is
        // answered by that dedicated endpoint rather than /queries — the same
        // move prep_time.median.v1 already makes for a shape /queries cannot
        // express.
        MetricDefinition receiptDepth = MetricRegistry.require("receipt_depth.v1");

        assertThat(receiptDepth.sourceAvailable()).isTrue();
        assertThat(receiptDepth.grain()).isEqualTo(Grain.DAY_LOCATION_OPERATOR);
        assertThat(receiptDepth.grain().dimensions()).contains(Grain.Dimension.OPERATOR);
        assertThat(receiptDepth.isMoney())
                .as("an item count is never money, so it carries no legal-entity-grain obligation")
                .isFalse();
        assertThat(receiptDepth.effectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    void paymentMixIsBuiltAtThePaymentMethodGrainAndIsMoney() {
        // P39 (7.1c/7.3b): declared here for provenance even though it is
        // answered by GET .../reporting/payment-mix rather than /queries — a
        // share-per-method breakdown is several rows per slice, the same move
        // sla_bucket_set.v1 already makes for a shape /queries cannot express.
        MetricDefinition paymentMix = MetricRegistry.require("payment_mix.amount.v1");

        assertThat(paymentMix.sourceAvailable()).isTrue();
        assertThat(paymentMix.grain()).isEqualTo(Grain.DAY_LOCATION_LEGAL_ENTITY_PAYMENT_METHOD);
        assertThat(paymentMix.grain().dimensions()).contains(Grain.Dimension.PAYMENT_METHOD);
        assertThat(paymentMix.isMoney())
                .as("this is the cash-collection control figure — it has to be money")
                .isTrue();
        assertThat(paymentMix.grain().namesLegalEntity()).isTrue();
        assertThat(paymentMix.aggregation()).isEqualTo(MetricDefinition.Aggregation.DISTRIBUTION);
        assertThat(paymentMix.effectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    void ordersPromisedIsTheOnTimeDenominatorNotMoney() {
        // Wave T06 (7.3): the branch leaderboard's «В норме %» denominator.
        MetricDefinition promised = MetricRegistry.require("orders.promised.v1");

        assertThat(promised.sourceAvailable()).isTrue();
        assertThat(promised.grain()).isEqualTo(Grain.DAY_LOCATION);
        assertThat(promised.isMoney()).isFalse();
        assertThat(promised.aggregation()).isEqualTo(MetricDefinition.Aggregation.COUNT);
    }

    @Test
    void handoverTimeMedianReadsSecondsTotalNotSecondsToReady() {
        // Wave T06 (7.3a): the SLA table's «Медиана» column — the same
        // population sla_bucket_set.v1 buckets, distinct from
        // prep_time.median.v1's own seconds_to_ready.
        MetricDefinition handover = MetricRegistry.require("handover_time.median.v1");

        assertThat(handover.sourceAvailable()).isTrue();
        assertThat(handover.grain()).isEqualTo(Grain.DAY_LOCATION);
        assertThat(handover.aggregation()).isEqualTo(MetricDefinition.Aggregation.MEDIAN);
        assertThat(handover.sourceFact()).isEqualTo("reporting.fact_order.seconds_total");
    }

    @Test
    void aDigestChangesWithTheWordsItCovers() {
        MetricDefinition original = MetricRegistry.require("orders.count.v1");
        MetricDefinition reworded = new MetricDefinition(
                original.id(),
                original.grain(),
                original.sourceFact(),
                original.sourceAvailable(),
                original.aggregation(),
                original.inclusionRule(),
                original.currencyRule(),
                original.roundingRule(),
                original.unit(),
                original.definition() + " Slightly different.",
                original.inclusion(),
                original.exclusion(),
                original.refundTreatment(),
                original.openQuestion(),
                original.effectiveFrom());

        // This is what makes an edited-in-place definition a startup failure
        // rather than a signature standing over words finance never read.
        assertThat(reworded.digest()).isNotEqualTo(original.digest());
        assertThat(original.digest())
                .isEqualTo(MetricRegistry.require("orders.count.v1").digest());
    }

    @Test
    void aMetricIdRoundTripsThroughItsWireForm() {
        assertThat(MetricId.parse("revenue.gross.v1")).isEqualTo(new MetricId("revenue.gross", 1));
        assertThat(new MetricId("revenue.gross", 1).code()).isEqualTo("revenue.gross.v1");
        assertThatThrownBy(() -> MetricId.parse("revenue.gross")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSlaBucketsAreExhaustiveAndDoNotOverlap() {
        // The competitor's documented buckets («до 30, до 35, 30–40, 40–50,
        // 35–60, свыше 60») count the same order in two adjacent columns, and the
        // percentages cannot add up to anything. These do not.
        for (long minutes = 0; minutes <= 120; minutes++) {
            long seconds = minutes * 60;
            List<SlaBucketSet.Bucket> matching = SlaBucketSet.buckets().stream()
                    .filter(bucket -> bucket.fromMinutes() <= seconds / 60
                            && (bucket.toMinutesExclusive() == null || seconds / 60 < bucket.toMinutesExclusive()))
                    .toList();

            assertThat(matching).as("%d minutes", minutes).hasSize(1);
            assertThat(SlaBucketSet.bucketFor(seconds)).isEqualTo(matching.getFirst());
        }
    }

    @Test
    void anOrderThatClosedBeforeItOpenedIsRefusedRatherThanBucketed() {
        assertThatThrownBy(() -> SlaBucketSet.bucketFor(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
