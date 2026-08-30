package uz.qoida.platform.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "revenue.gross.v1", "revenue.net.v1", "average_check.v1", "orders.count.v1",
                "orders.cancelled.v1", "orders.late.v1", "prep_time.median.v1",
                "sla_bucket_set.v1", "channel_mix.count.v1");

        assertThat(named).allSatisfy(code ->
                assertThat(MetricRegistry.find(code)).as(code).isPresent());
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
                .as("an unbuilt metric governs no dates, and saying otherwise implies "
                        + "figures exist")
                .isNull();
    }

    @Test
    void aDigestChangesWithTheWordsItCovers() {
        MetricDefinition original = MetricRegistry.require("orders.count.v1");
        MetricDefinition reworded = new MetricDefinition(original.id(), original.grain(),
                original.sourceFact(), original.sourceAvailable(), original.aggregation(),
                original.inclusionRule(), original.currencyRule(), original.roundingRule(),
                original.unit(), original.definition() + " Slightly different.",
                original.inclusion(), original.exclusion(), original.refundTreatment(),
                original.openQuestion(), original.effectiveFrom());

        // This is what makes an edited-in-place definition a startup failure
        // rather than a signature standing over words finance never read.
        assertThat(reworded.digest()).isNotEqualTo(original.digest());
        assertThat(original.digest()).isEqualTo(MetricRegistry.require("orders.count.v1").digest());
    }

    @Test
    void aMetricIdRoundTripsThroughItsWireForm() {
        assertThat(MetricId.parse("revenue.gross.v1"))
                .isEqualTo(new MetricId("revenue.gross", 1));
        assertThat(new MetricId("revenue.gross", 1).code()).isEqualTo("revenue.gross.v1");
        assertThatThrownBy(() -> MetricId.parse("revenue.gross"))
                .isInstanceOf(IllegalArgumentException.class);
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
                            && (bucket.toMinutesExclusive() == null
                                || seconds / 60 < bucket.toMinutesExclusive()))
                    .toList();

            assertThat(matching).as("%d minutes", minutes).hasSize(1);
            assertThat(SlaBucketSet.bucketFor(seconds)).isEqualTo(matching.getFirst());
        }
    }

    @Test
    void anOrderThatClosedBeforeItOpenedIsRefusedRatherThanBucketed() {
        assertThatThrownBy(() -> SlaBucketSet.bucketFor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
