package uz.horecaos.platform.migration.application.reconciliation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;

/**
 * "Exact money totals by currency/provider/status" (ADR 0024's third mandatory
 * rule).
 *
 * <p>Sliced, never summed, and that is the entire design. A single grand total
 * that nets a shortfall in completed orders against an excess in cancelled ones
 * reconciles to zero while both figures are wrong — and the cancelled cohort is
 * exactly where legacy discounts and delivery subsidies behave differently, so it
 * is the slice most likely to be wrong and the one a total would hide.
 *
 * <p>The legacy total comes from the writer, not from a reconstruction.
 * {@code apps/customer/services/order/create_order.py} computes
 * {@code order_price = subtotal − subtotal_discount},
 * {@code delivery_price = delivery − delivery_discount} (zero on takeaway) and
 * {@code packaging_price = packaging}, so
 * {@code order_price + delivery_price + packaging_price} is settled by source and
 * equals {@code payments.amount}, computed by a different function — which gives
 * this rule an independent check that depends on no query of ours.
 *
 * <p>Deliberately <strong>not</strong> reconstructed from line items.
 * {@code calculate_subtotal} excludes packaging from the subtotal while
 * {@code create_order} appends package lines to {@code order_line_items} with
 * {@code is_package=True}, and line {@code price} is the undiscounted variant
 * price with {@code subtotal_discount} never distributed across lines. So
 * {@code Σ(price × quantity)} equals {@code order_price} only when the discount is
 * zero and no line is a package. A rule built on that identity passes on a
 * development cohort and fails on production for a reason that looks like a
 * migration defect.
 *
 * <p>Money is exact integers in minor units on both sides. For UZS a minor unit
 * is a whole som, so a difference of 1 in a result here is one som — nothing in
 * this class divides by anything, and a formatter that asked ISO 4217 for a
 * decimal count would report a hundredth of the discrepancy.
 */
public final class MoneyTotalsRule implements ReconciliationRule {

    /**
     * The legacy estate prices in som and stores no currency column at all.
     * Stated as a constant with a name rather than appearing as a literal in the
     * dimension key, because the day a second currency exists this is the line
     * that has to change and the rule version that has to move.
     */
    private static final String LEGACY_CURRENCY = "UZS";

    /**
     * Legacy {@code order_statuses} to target order status.
     *
     * <p>{@code enums.OrderStatus} is exactly these seven and each has a target
     * counterpart, which is what makes a per-status comparison possible at all. It
     * is part of the rule's version: changing a line here changes what the rule
     * measures, and a result recorded under version 1 must stay readable as
     * version 1's comparison.
     *
     * <p>{@code cancelled} maps to one target status here even though the target
     * distinguishes three terminal reasons, and that is correct for a money rule:
     * the reason resolution belongs to the transformation, which reads it from
     * {@code cancelled_by_type} and {@code cancel_reason}, and folding it in here
     * would make this rule fail whenever that mapping was revised.
     */
    private static final Map<String, String> STATUS_CROSSWALK = Map.of(
            "new", "RECEIVED",
            "accepted", "CONFIRMED",
            "cooking", "PREPARING",
            "ready", "READY",
            "delivering", "FULFILLING",
            "completed", "COMPLETED",
            "cancelled", "CANCELLED");

    private static final String LEGACY_TOTALS = """
            SELECT CAST(o.status_id AS text) AS legacy_status,
                   sum(o.order_price + o.delivery_price + o.packaging_price) AS total_minor
            FROM orders o
            GROUP BY o.status_id
            """;

    /**
     * The target side, joined through the crosswalk rather than read from the
     * orders table directly. An order created on the target during a canary is a
     * real order and is not this migration's to account for; including it would
     * report a surplus that grows with every genuine sale.
     */
    private static final String TARGET_TOTALS = """
            SELECT o.status AS target_status, o.currency AS currency,
                   sum(o.total_minor) AS total_minor
            FROM ordering.orders o
            JOIN migration.entity_mappings m
              ON m.target_id = o.id
             AND m.tenant_id = o.tenant_id
             AND m.scope_id = :scopeId
             AND m.entity_type = :entityType
             AND m.mapping_status = 'MAPPED'
            WHERE o.tenant_id = :tenantId
            GROUP BY o.status, o.currency
            """;

    private final MigrationCapability capability;

    public MoneyTotalsRule(MigrationCapability capability) {
        this.capability = capability;
    }

    @Override
    public String ruleCode() {
        return "MONEY_TOTAL_BY_CURRENCY_AND_STATUS";
    }

    @Override
    public int ruleVersion() {
        return 1;
    }

    @Override
    public MigrationCapability capability() {
        return capability;
    }

    @Override
    public String entityType() {
        return "ORDER";
    }

    @Override
    public ReconciliationSeverity severity() {
        return ReconciliationSeverity.CRITICAL;
    }

    @Override
    public Measurement.MeasureKind measureKind() {
        return Measurement.MeasureKind.AMOUNT;
    }

    @Override
    public List<Measurement> evaluate(RuleContext context) {
        Map<String, BigInteger> expected = new LinkedHashMap<>();
        for (Map<String, Object> row : context.legacy().rows(LEGACY_TOTALS, Map.of())) {
            String legacyStatus = String.valueOf(row.get("legacy_status"));
            String targetStatus = STATUS_CROSSWALK.get(legacyStatus);
            if (targetStatus == null) {
                // A legacy status outside enums.OrderStatus. Not silently bucketed:
                // an unmapped status carrying money is a finding, and giving it its
                // own dimension makes it one instead of hiding it inside another
                // slice's total.
                targetStatus = "UNMAPPED_" + legacyStatus.toUpperCase(java.util.Locale.ROOT);
            }
            expected.merge(dimension(LEGACY_CURRENCY, targetStatus), minorUnits(row.get("total_minor")),
                    BigInteger::add);
        }

        Map<String, BigInteger> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : context.target().rows(TARGET_TOTALS, Map.of(
                "tenantId", context.tenantId(),
                "scopeId", context.scopeId(),
                "entityType", entityType()))) {
            actual.merge(
                    dimension(String.valueOf(row.get("currency")), String.valueOf(row.get("target_status"))),
                    minorUnits(row.get("total_minor")),
                    BigInteger::add);
        }

        // The union, not the intersection. A status present on one side and absent
        // on the other is the most important difference this rule can find — every
        // order of that status was lost, or invented — and intersecting the keys
        // would drop exactly that case.
        Set<String> dimensions = new TreeSet<>(expected.keySet());
        dimensions.addAll(actual.keySet());

        List<Measurement> measurements = new ArrayList<>(dimensions.size());
        for (String dimension : dimensions) {
            measurements.add(Measurement.amount(dimension, dimension.substring(0, 3),
                    expected.getOrDefault(dimension, BigInteger.ZERO),
                    actual.getOrDefault(dimension, BigInteger.ZERO)));
        }
        return measurements;
    }

    /** {@code UZS|COMPLETED}: the currency first, so the results table sorts by it. */
    private static String dimension(String currency, String status) {
        return currency + "|" + status;
    }

    /**
     * The aggregate as an exact integer.
     *
     * <p>{@code sum()} over PostgreSQL {@code bigint} returns {@code numeric}, and
     * the driver hands it back as {@link BigDecimal}. {@code toBigIntegerExact}
     * rather than {@code longValue}: the column has no scale, so a value that
     * arrived with one is a mapping error and should say so rather than round a
     * customer's money away.
     */
    private static BigInteger minorUnits(Object value) {
        return switch (value) {
            case null -> BigInteger.ZERO;
            case BigInteger exact -> exact;
            case BigDecimal decimal -> decimal.toBigIntegerExact();
            case Number number -> BigInteger.valueOf(number.longValue());
            default -> throw new IllegalStateException(
                    "A money total came back as " + value.getClass() + ", which is not an integer");
        };
    }
}
