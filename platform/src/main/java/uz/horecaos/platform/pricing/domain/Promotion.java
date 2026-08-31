package uz.horecaos.platform.pricing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A promotion, as a value the engine can evaluate without touching a database
 * (ADR 0018).
 *
 * <p>A rule is <em>data</em>. The condition and action types below are closed
 * enumerations, and their operands travel in a map read through the accessors on
 * {@link Operands}. ADR 0018 rejects a scripting engine outright: arbitrary code
 * on the pricing path is unreviewable, non-deterministic across versions, and a
 * code-execution surface driven by control-plane input. Extending what a
 * promotion can express means adding a constant here and a branch in the
 * evaluator, both of which are reviewed and tested — not shipping an interpreter.
 *
 * <p>Every field is carried by value so {@code PricingEngine} stays a pure
 * function. Nothing here reads a clock, and {@code validFrom}/{@code validUntil}
 * are compared against the instant the engine is handed.
 */
public record Promotion(
        UUID promotionId,
        UUID tenantId,
        UUID brandId,
        String code,
        Scope scope,
        String stackingGroup,
        boolean exclusive,
        int priority,
        boolean requiresCoupon,
        /** Null is uncapped. Always positive when present. */
        @Nullable Long maximumDiscountMinor,
        String currency,
        Instant validFrom,
        /** Null is open-ended: the promotion never lapses on its own. */
        @Nullable Instant validUntil,
        int definitionVersion,
        List<Condition> conditions,
        List<Action> actions) {

    public Promotion {
        Objects.requireNonNull(promotionId, "A promotion id is required");
        Objects.requireNonNull(scope, "A promotion scope is required");
        Objects.requireNonNull(stackingGroup, "A stacking group is required");
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (actions.isEmpty()) {
            // A promotion with no action is one that cannot change a total. It is
            // not a no-op to tolerate: it is a rule somebody wrote and expected to
            // do something, and it will be reported as working.
            throw new IllegalArgumentException("A promotion needs at least one action");
        }
    }

    /** Whether this promotion is in force at {@code at}. */
    public boolean isInForceAt(Instant at) {
        return !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil));
    }

    /**
     * Which pipeline stage applies it.
     *
     * <p>{@code ITEM} is stage 3 and lands on matching lines; {@code ORDER} and
     * {@code DELIVERY} are stage 4 and land on the cart.
     */
    public enum Scope {
        ITEM,
        ORDER,
        DELIVERY
    }

    /**
     * One predicate. All of a promotion's conditions must hold for it to apply,
     * which is the only combinator there is — deliberately, because an
     * operator-authored boolean tree is the beginning of a scripting language.
     */
    public record Condition(int sequence, Type type, Operands operands) {

        public Condition {
            Objects.requireNonNull(type, "A condition type is required");
            operands = operands == null ? Operands.empty() : operands;
        }

        public enum Type {
            /** Any line whose product is named. Operand: {@code productIds}. */
            PRODUCT,
            /** Any line whose product is in a named category. Operand: {@code categoryIds}. */
            CATEGORY,
            /** Any line on a named variant. Operand: {@code variantIds}. */
            VARIANT,
            /** Matching lines total at least this many units. Operand: {@code quantity}. */
            QUANTITY_AT_LEAST,
            /** The goods subtotal is at least this. Operand: {@code amountMinor}. */
            SUBTOTAL_AT_LEAST,
            /** Operand: {@code channels}, tenant-defined channel codes. */
            CHANNEL,
            /** Operand: {@code locationIds}. */
            LOCATION,
            /** Operand: {@code fulfillmentModes}. */
            FULFILLMENT_MODE,
            /** Operand: {@code daysOfWeek}, ISO-8601 numbers, Monday is 1. */
            DAY_OF_WEEK,
            /** Operand: {@code fromMinuteOfDay} and {@code toMinuteOfDay}, local time. */
            TIME_OF_DAY,
            /** The customer has never ordered at this brand. No operands. */
            FIRST_ORDER,
            /** Operand: {@code segments}. */
            CUSTOMER_SEGMENT
        }
    }

    /**
     * What the promotion does when its conditions hold.
     *
     * <p>Percentages are basis points and amounts are minor units, both integers.
     * ADR 0018 forbids a rate stored as a float: two machines would round it
     * differently and the same cart would price twice.
     */
    public record Action(int sequence, Type type, Operands operands) {

        public Action {
            Objects.requireNonNull(type, "An action type is required");
            operands = operands == null ? Operands.empty() : operands;
        }

        public enum Type {
            /** Operand: {@code basisPoints}. Applies to matching lines. */
            ITEM_PERCENTAGE_DISCOUNT,
            /** Operand: {@code amountMinor}, taken off each matching unit. */
            ITEM_FIXED_DISCOUNT,
            /** Operand: {@code amountMinor}, the new unit price of a matching line. */
            ITEM_FIXED_PRICE,
            /** Operand: {@code basisPoints}, against the goods subtotal. */
            ORDER_PERCENTAGE_DISCOUNT,
            /** Operand: {@code amountMinor}, off the goods subtotal. */
            ORDER_FIXED_DISCOUNT,
            /** No operands. Waives whatever delivery fee survived the zone waiver. */
            FREE_DELIVERY,
            /** Operand: {@code amountMinor} or {@code basisPoints}, off the fee. */
            REDUCED_DELIVERY,
            /**
             * Operand: {@code variantIds} and {@code quantity}.
             *
             * <p>Bounded, and the bound is not optional: an unbounded free item is
             * a promotion that gives away the whole cart when somebody orders
             * enough of one thing.
             */
            FREE_ITEM
        }
    }

    /**
     * The operands of one condition or action.
     *
     * <p>A read-only view over the {@code attributes_json} column with accessors
     * that refuse rather than coerce. A missing or wrong-typed operand is an
     * authoring mistake, and the validator is what catches it before a promotion
     * may leave DRAFT; by the time the engine reads one, a throw here means a row
     * was written past the validator and is worth failing loudly over.
     */
    public record Operands(Map<String, Object> values) {

        public Operands {
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        public static Operands empty() {
            return new Operands(Map.of());
        }

        public long requireLong(String key) {
            Object value = values.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            throw new IllegalStateException("Operand '" + key + "' is missing or is not a number");
        }

        public int requireInt(String key) {
            return Math.toIntExact(requireLong(key));
        }

        public java.util.Optional<Long> optionalLong(String key) {
            Object value = values.get(key);
            return value instanceof Number number
                    ? java.util.Optional.of(number.longValue())
                    : java.util.Optional.empty();
        }

        /** Identifiers as a set, so membership is a hash lookup and not a scan. */
        public java.util.Set<UUID> requireIds(String key) {
            return requireStrings(key).stream()
                    .map(UUID::fromString)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        public java.util.Set<String> requireStrings(String key) {
            Object value = values.get(key);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalStateException("Operand '" + key + "' is missing or is not a non-empty list");
            }
            return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        public java.util.Set<Integer> requireInts(String key) {
            Object value = values.get(key);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalStateException("Operand '" + key + "' is missing or is not a non-empty list");
            }
            return list.stream()
                    .map(entry -> ((Number) entry).intValue())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
