package uz.qoida.platform.migration.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.migration.api.MigrationCapability;
import uz.qoida.platform.migration.application.reconciliation.CrossTenantAncestryRule;
import uz.qoida.platform.migration.application.reconciliation.LegacyQuery;
import uz.qoida.platform.migration.application.reconciliation.Measurement;
import uz.qoida.platform.migration.application.reconciliation.MoneyTotalsRule;
import uz.qoida.platform.migration.application.reconciliation.ReconciliationRule;
import uz.qoida.platform.migration.application.reconciliation.ReconciliationRuleStore;
import uz.qoida.platform.migration.application.reconciliation.TargetQuery;
import uz.qoida.platform.migration.domain.ReconciliationSeverity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The reconciliation rule library (ADR 0024's mandatory gates).
 *
 * <p>The rules' SQL is exercised against the real schema elsewhere; what is
 * asserted here is the comparison arithmetic, which is where a reconciliation
 * goes wrong quietly. A netted total, a rounded som, an intersected key set and a
 * checksum treated as a number are all findings that pass.
 */
class MigrationReconciliationRuleTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SCOPE = UUID.randomUUID();

    @Test
    @DisplayName("money is compared per currency and status, never netted into one total")
    void moneyIsSlicedRatherThanSummed() {
        // The failure a grand total hides: completed is 1 000 som short and
        // cancelled is 1 000 som over. Both figures are wrong and the sum is right.
        FakeLegacy legacy = new FakeLegacy();
        legacy.rows = List.of(
                Map.of("legacy_status", "completed", "total_minor", new BigDecimal("500000")),
                Map.of("legacy_status", "cancelled", "total_minor", new BigDecimal("120000")));

        FakeTarget target = new FakeTarget();
        target.rows = List.of(
                Map.of("target_status", "COMPLETED", "currency", "UZS",
                        "total_minor", new BigDecimal("499000")),
                Map.of("target_status", "CANCELLED", "currency", "UZS",
                        "total_minor", new BigDecimal("121000")));

        List<Measurement> measured =
                new MoneyTotalsRule(MigrationCapability.ORDERS).evaluate(context(legacy, target));

        assertThat(measured).hasSize(2);
        assertThat(measured).allSatisfy(measurement ->
                assertThat(measurement.agrees())
                        .as("both slices differ, and a single total would have agreed")
                        .isFalse());
        assertThat(measured).extracting(Measurement::dimensionKey)
                .containsExactly("UZS|CANCELLED", "UZS|COMPLETED");
        assertThat(measured.get(0).difference()).isEqualTo(BigInteger.valueOf(1_000));
        assertThat(measured.get(1).difference()).isEqualTo(BigInteger.valueOf(-1_000));
    }

    @Test
    @DisplayName("a status present on one side only is measured, not dropped")
    void theDimensionsAreTheUnionOfBothSides() {
        FakeLegacy legacy = new FakeLegacy();
        legacy.rows = List.of(
                Map.of("legacy_status", "delivering", "total_minor", new BigDecimal("75000")));

        FakeTarget target = new FakeTarget();
        target.rows = List.of();

        List<Measurement> measured =
                new MoneyTotalsRule(MigrationCapability.ORDERS).evaluate(context(legacy, target));

        assertThat(measured).hasSize(1);
        assertThat(measured.get(0).dimensionKey()).isEqualTo("UZS|FULFILLING");
        assertThat(measured.get(0).actual()).isEqualTo(BigInteger.ZERO);
        assertThat(measured.get(0).agrees())
                .as("every order of a status was lost, which intersecting the keys would hide")
                .isFalse();
    }

    @Test
    @DisplayName("a legacy status outside the enum gets its own dimension rather than a bucket")
    void anUnmappedStatusIsAFindingAndNotABucket() {
        FakeLegacy legacy = new FakeLegacy();
        legacy.rows = List.of(
                Map.of("legacy_status", "refunded", "total_minor", new BigDecimal("9000")));

        List<Measurement> measured = new MoneyTotalsRule(MigrationCapability.ORDERS)
                .evaluate(context(legacy, new FakeTarget()));

        assertThat(measured).extracting(Measurement::dimensionKey)
                .containsExactly("UZS|UNMAPPED_REFUNDED");
    }

    @Test
    @DisplayName("ancestry is three separate zero-tolerance counts")
    void ancestryIsMeasuredAtEveryLevel() {
        FakeTarget target = new FakeTarget();
        target.integers = List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO);

        List<Measurement> measured = new CrossTenantAncestryRule(MigrationCapability.ORDERS)
                .evaluate(context(new FakeLegacy(), target));

        assertThat(measured).extracting(Measurement::dimensionKey)
                .containsExactly("FOREIGN_TENANT", "FOREIGN_BRAND", "FOREIGN_LOCATION");
        assertThat(measured).allSatisfy(measurement ->
                assertThat(measurement.expected())
                        .as("no legacy figure excuses a row under the wrong parent")
                        .isEqualTo(BigInteger.ZERO));
        assertThat(measured.get(1).agrees()).isFalse();
        assertThat(measured.get(0).agrees()).isTrue();
        assertThat(measured.get(2).agrees()).isTrue();
    }

    @Test
    @DisplayName("a checksum agrees or it does not, and never by tolerance")
    void checksumsHaveNoArithmetic() {
        String digest = "a".repeat(64);
        Measurement same = Measurement.checksum("", digest, digest);
        Measurement different = Measurement.checksum("", digest, "b".repeat(64));

        assertThat(same.agrees()).isTrue();
        assertThat(different.agrees()).isFalse();
        assertThat(different.difference())
                .as("no arithmetic, so the results table's difference column stays null")
                .isNull();

        ReconciliationRuleStore.Declaration lenient = declaration(
                ReconciliationSeverity.WARNING, BigInteger.valueOf(1_000_000));
        assertThat(lenient.tolerates(different.difference()))
                .as("a tolerance against a digest would be a number nothing consults")
                .isFalse();
    }

    @Test
    @DisplayName("a measured comparison always has both sides")
    void oneSidedComparisonsAreRejected() {
        assertThat(catchThrowable(() -> new Measurement("", Measurement.MeasureKind.COUNT,
                BigInteger.TEN, null, null, null, null, null)))
                .as("a reconciliation that passes because half of it was not measured is worse "
                        + "than one that fails")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(catchThrowable(() -> new Measurement("", Measurement.MeasureKind.AMOUNT,
                BigInteger.TEN, BigInteger.TEN, null, null, null, null)))
                .as("an amount without a currency is a number nobody can compare")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a som is a minor unit, and the tolerance counts in som")
    void toleranceIsInMinorUnitsOfTheMeasure() {
        // For UZS a minor unit is a whole som. A tolerance of 500 is five hundred
        // som, not five som — a formatter that asked ISO 4217 for a decimal count
        // and divided by 100 would report a hundredth of the discrepancy.
        ReconciliationRuleStore.Declaration bounded = declaration(
                ReconciliationSeverity.WARNING, BigInteger.valueOf(500));

        assertThat(bounded.tolerates(BigInteger.valueOf(500))).isTrue();
        assertThat(bounded.tolerates(BigInteger.valueOf(-500)))
                .as("a shortfall and an excess of the same size are the same tolerance")
                .isTrue();
        assertThat(bounded.tolerates(BigInteger.valueOf(501))).isFalse();

        ReconciliationRuleStore.Declaration blocking =
                declaration(ReconciliationSeverity.CRITICAL, BigInteger.ZERO);
        assertThat(blocking.tolerates(BigInteger.ONE))
                .as("a rule that blocks cutover admits no difference; accepting one is a decision "
                        + "about a result, with a name on it")
                .isFalse();
    }

    private static ReconciliationRule.RuleContext context(LegacyQuery legacy, TargetQuery target) {
        return new ReconciliationRule.RuleContext(
                TENANT, SCOPE, null, null, "ORDER", legacy, target);
    }

    private static ReconciliationRuleStore.Declaration declaration(
            ReconciliationSeverity severity, BigInteger tolerance) {
        return new ReconciliationRuleStore.Declaration(
                UUID.randomUUID(), "RULE", 1, "ORDERS", "ORDER", severity, "AMOUNT",
                tolerance.signum() == 0 ? "ZERO" : "ABSOLUTE", tolerance, "because");
    }

    private static final class FakeLegacy implements LegacyQuery {
        private List<Map<String, Object>> rows = List.of();

        @Override
        public Optional<BigInteger> exactInteger(String sql, Map<String, Object> parameters) {
            return Optional.empty();
        }

        @Override
        public Optional<String> text(String sql, Map<String, Object> parameters) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> rows(String sql, Map<String, Object> parameters) {
            return rows;
        }
    }

    private static final class FakeTarget implements TargetQuery {
        private List<Map<String, Object>> rows = List.of();
        private List<BigInteger> integers = List.of();
        private final List<BigInteger> served = new ArrayList<>();

        @Override
        public Optional<BigInteger> exactInteger(String sql, Map<String, Object> parameters) {
            if (served.size() >= integers.size()) {
                return Optional.empty();
            }
            BigInteger next = integers.get(served.size());
            served.add(next);
            return Optional.of(next);
        }

        @Override
        public Optional<String> text(String sql, Map<String, Object> parameters) {
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> rows(String sql, Map<String, Object> parameters) {
            return rows;
        }
    }
}
