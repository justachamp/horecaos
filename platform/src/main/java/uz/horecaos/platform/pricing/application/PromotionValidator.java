package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Promotion.Action;
import uz.horecaos.platform.pricing.domain.Promotion.Condition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ActionDefinition;
import uz.horecaos.platform.pricing.domain.PromotionDefinition.ConditionDefinition;

/**
 * The rule checker a promotion cannot leave {@code DRAFT} without passing
 * (ADR 0140, ADR 0018).
 *
 * <p>Pure apart from the reference lookups it is handed, so every refusal is a
 * unit test on a literal. Each refusal is a stable code: the authoring screen
 * renders the code, the sequence of the condition or action it concerns and a
 * message, and a marketer never sees a stack trace for a rule they wrote
 * wrongly. A <em>warning</em> is not a refusal -- an uncapped percentage is
 * legitimate and, as V0093 says, "a mistake waiting to happen" -- and does not
 * stop a promotion being validated.
 *
 * <p>An engine that only checks types at read time fails loudly on a bad operand
 * in the middle of a customer's checkout. This is where that failure belongs
 * instead: before the promotion can reach a customer by any route.
 */
public final class PromotionValidator {

    private static final long MAX_BASIS_POINTS = 10_000L;
    private static final int MINUTES_IN_DAY = 24 * 60;
    private static final Set<String> CHANNEL_TYPES =
            Set.of("WEB", "IOS", "ANDROID", "TELEGRAM", "KIOSK", "QR_TABLE", "CALL_CENTRE", "POS");
    private static final Set<String> FULFILMENT_MODES = Set.of("DELIVERY", "PICKUP", "DINE_IN");

    private static final Set<Action.Type> ITEM_ACTIONS = EnumSet.of(
            Action.Type.ITEM_PERCENTAGE_DISCOUNT,
            Action.Type.ITEM_FIXED_DISCOUNT,
            Action.Type.ITEM_FIXED_PRICE,
            Action.Type.FREE_ITEM);
    private static final Set<Action.Type> ORDER_ACTIONS =
            EnumSet.of(Action.Type.ORDER_PERCENTAGE_DISCOUNT, Action.Type.ORDER_FIXED_DISCOUNT);
    private static final Set<Action.Type> DELIVERY_ACTIONS =
            EnumSet.of(Action.Type.FREE_DELIVERY, Action.Type.REDUCED_DELIVERY);
    private static final Set<Action.Type> MARKUP_ACTIONS =
            EnumSet.of(Action.Type.ITEM_PERCENTAGE_MARKUP, Action.Type.ITEM_FIXED_MARKUP);

    /** The facts a definition is checked against: what exists in this tenant and brand. */
    public interface References {

        Set<UUID> knownProducts(Collection<UUID> ids);

        Set<UUID> knownCategories(Collection<UUID> ids);

        Set<UUID> knownVariants(Collection<UUID> ids);

        Set<UUID> knownZones(Collection<UUID> ids);

        Set<UUID> knownLocations(Collection<UUID> ids);

        Set<String> knownChannelCodes(Collection<String> codes);

        Set<String> knownPaymentMethods(Collection<String> codes);

        Set<String> knownAudiences(Collection<String> ids);

        /** The currency of the brand's price books, or empty when it has none yet. */
        Optional<String> brandCurrency();

        /** Of these variants, those with no active price in any of the brand's price books. */
        Set<UUID> unpricedVariants(Collection<UUID> ids);
    }

    /**
     * Another promotion of the brand sharing a stacking group, for the rule that an
     * item promotion may not share a group with an order or delivery one.
     */
    public record Peer(UUID promotionId, String stackingGroup, Promotion.Kind kind, Promotion.Scope scope) {}

    public record Issue(
            String code, String message, @Nullable Integer sequence) {}

    public record Report(List<Issue> refusals, List<Issue> warnings) {

        public boolean isValid() {
            return refusals.isEmpty();
        }
    }

    private PromotionValidator() {}

    public static Report validate(
            @Nullable UUID selfId, PromotionDefinition definition, List<Peer> peers, References references) {
        List<Issue> refusals = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();

        checkShape(definition, refusals);
        checkActions(definition, refusals, warnings, references);
        checkConditions(definition, refusals, warnings, references);
        checkWindow(definition, refusals);
        checkLimits(definition, refusals);
        checkGroupScopes(selfId, definition, peers, refusals);

        references.brandCurrency().ifPresent(currency -> {
            if (!currency.equals(definition.currency())) {
                refusals.add(new Issue(
                        "CURRENCY_MISMATCH",
                        "The promotion is in " + definition.currency() + " and the brand's price book is in "
                                + currency,
                        null));
            }
        });

        if (definition.maximumDiscountMinor() == null
                && definition.kind() == Promotion.Kind.DISCOUNT
                && definition.actions().stream()
                        .anyMatch(action -> action.type() == Action.Type.ITEM_PERCENTAGE_DISCOUNT
                                || action.type() == Action.Type.ORDER_PERCENTAGE_DISCOUNT)) {
            warnings.add(new Issue(
                    "UNCAPPED_PERCENTAGE",
                    "A percentage discount with no maximum discount takes a share of any basket, however large",
                    null));
        }
        return new Report(List.copyOf(refusals), List.copyOf(warnings));
    }

    // ---------------------------------------------------------------- shape

    private static void checkShape(PromotionDefinition definition, List<Issue> refusals) {
        if (definition.name() == null || definition.name().isBlank()) {
            refusals.add(new Issue("NAME_REQUIRED", "A promotion needs a name", null));
        }
        if (definition.code() == null || !definition.code().matches("^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$")) {
            refusals.add(
                    new Issue("CODE_INVALID", "The handle must be 2-64 letters, digits, dashes or underscores", null));
        }
        if (definition.stackingGroup() == null
                || definition.stackingGroup().isBlank()
                || definition.stackingGroup().length() > 64) {
            refusals.add(new Issue(
                    "STACKING_GROUP_REQUIRED", "A stacking group of at most 64 characters is required", null));
        }
        if (definition.currency() == null || !definition.currency().matches("^[A-Z]{3}$")) {
            refusals.add(new Issue("CURRENCY_INVALID", "The currency must be a 3-letter ISO code", null));
        }
        if (definition.maximumDiscountMinor() != null && definition.maximumDiscountMinor() <= 0) {
            refusals.add(
                    new Issue("MAXIMUM_INVALID", "A maximum discount must be positive, or omitted for uncapped", null));
        }
        if (definition.kind() == Promotion.Kind.MARKUP) {
            if (definition.exclusive()) {
                refusals.add(new Issue(
                        "EXCLUSIVE_WITH_MARKUP",
                        "A markup is never suppressed by exclusivity, so an exclusive markup cannot be authored",
                        null));
            }
            if (definition.scope() == Promotion.Scope.DELIVERY) {
                refusals.add(new Issue(
                        "MARKUP_SCOPE_INVALID",
                        "A markup lands on items; a delivery-scope markup does not exist",
                        null));
            }
            if (definition.scope() == Promotion.Scope.ORDER) {
                refusals.add(new Issue(
                        "ORDER_MARKUP_NOT_AVAILABLE",
                        "An order-level markup is a service line whose fiscal and tax treatment is still an open "
                                + "decision; only item markups can be authored",
                        null));
            }
            if (definition.requiresCoupon()) {
                refusals.add(new Issue("MARKUP_NOT_COUPON_GATED", "A markup cannot require a promo code", null));
            }
        }
    }

    // -------------------------------------------------------------- actions

    private static void checkActions(
            PromotionDefinition definition, List<Issue> refusals, List<Issue> warnings, References references) {
        if (definition.actions().isEmpty()) {
            refusals.add(new Issue("NO_ACTION", "A promotion with no action cannot change a total", null));
            return;
        }
        Set<Action.Type> allowed =
                switch (definition.kind()) {
                    case MARKUP -> MARKUP_ACTIONS;
                    case DISCOUNT ->
                        switch (definition.scope()) {
                            case ITEM -> ITEM_ACTIONS;
                            case ORDER -> ORDER_ACTIONS;
                            case DELIVERY -> DELIVERY_ACTIONS;
                        };
                };
        for (ActionDefinition action : definition.actions()) {
            if (!allowed.contains(action.type())) {
                refusals.add(new Issue(
                        "ACTION_SCOPE_MISMATCH",
                        action.type() + " cannot be used in a " + definition.kind() + " promotion of scope "
                                + definition.scope(),
                        action.sequence()));
                continue;
            }
            Map<String, Object> operands = action.operands();
            switch (action.type()) {
                case ITEM_PERCENTAGE_DISCOUNT, ORDER_PERCENTAGE_DISCOUNT, ITEM_PERCENTAGE_MARKUP -> {
                    Long basisPoints = longOperand(operands, "basisPoints");
                    if (basisPoints == null || basisPoints < 1) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID",
                                "basisPoints must be a whole number of at least 1",
                                action.sequence()));
                    } else if (basisPoints > MAX_BASIS_POINTS) {
                        refusals.add(new Issue(
                                "PERCENTAGE_OVER_100",
                                "A percentage cannot exceed 10000 basis points (100%)",
                                action.sequence()));
                    }
                }
                case ITEM_FIXED_DISCOUNT, ORDER_FIXED_DISCOUNT, ITEM_FIXED_MARKUP -> {
                    Long amount = longOperand(operands, "amountMinor");
                    if (amount == null || amount < 1) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID", "amountMinor must be a positive whole number", action.sequence()));
                    }
                }
                case ITEM_FIXED_PRICE -> {
                    Long amount = longOperand(operands, "amountMinor");
                    if (amount == null || amount < 0) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID",
                                "amountMinor must be a whole number, zero or more",
                                action.sequence()));
                    }
                }
                case REDUCED_DELIVERY -> {
                    Long amount = longOperand(operands, "amountMinor");
                    Long basisPoints = longOperand(operands, "basisPoints");
                    if ((amount == null) == (basisPoints == null)) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID",
                                "A reduced delivery needs exactly one of amountMinor or basisPoints",
                                action.sequence()));
                    } else if (basisPoints != null && (basisPoints < 1 || basisPoints > MAX_BASIS_POINTS)) {
                        refusals.add(new Issue(
                                "PERCENTAGE_OVER_100", "basisPoints must be between 1 and 10000", action.sequence()));
                    }
                }
                case FREE_DELIVERY -> {
                    /* No operands. */
                }
                case FREE_ITEM -> checkFreeItem(action, refusals, references);
            }
        }
        if (definition.kind() == Promotion.Kind.DISCOUNT
                && definition.actions().stream().anyMatch(action -> MARKUP_ACTIONS.contains(action.type()))) {
            // Already refused per action above; kept as one readable refusal for the screen.
            refusals.add(new Issue("ACTION_SCOPE_MISMATCH", "A discount cannot carry a markup action", null));
        }
    }

    /** The gift is bounded per promotion, and the bound is not optional: FREE_ITEM_UNBOUNDED. */
    private static void checkFreeItem(ActionDefinition action, List<Issue> refusals, References references) {
        Map<String, Object> operands = action.operands();
        Long quantity = longOperand(operands, "quantity");
        if (quantity == null || quantity < 1) {
            refusals.add(new Issue(
                    "FREE_ITEM_UNBOUNDED",
                    "A free item needs a quantity of at least 1: an unbounded gift gives away the whole cart",
                    action.sequence()));
        }
        Set<UUID> variants = idsOperand(operands, "variantIds");
        if (variants == null || variants.isEmpty()) {
            refusals.add(new Issue("OPERAND_INVALID", "variantIds must name at least one variant", action.sequence()));
        } else {
            Set<UUID> known = references.knownVariants(variants);
            if (!known.containsAll(variants)) {
                refusals.add(new Issue(
                        "UNKNOWN_REFERENCE", "A gift variant is not one of this brand's variants", action.sequence()));
            }
        }
        Object mode = operands.get("mode");
        if (mode != null && !"ONCE".equals(mode) && !"PER_MULTIPLE".equals(mode)) {
            refusals.add(new Issue("OPERAND_INVALID", "mode must be ONCE or PER_MULTIPLE", action.sequence()));
        }
        Long trigger = longOperand(operands, "triggerQuantity");
        if ("PER_MULTIPLE".equals(mode) && (trigger == null || trigger < 1)) {
            refusals.add(new Issue(
                    "OPERAND_INVALID", "PER_MULTIPLE needs a triggerQuantity of at least 1", action.sequence()));
        }
        if (trigger != null && trigger < 1) {
            refusals.add(new Issue("OPERAND_INVALID", "triggerQuantity must be at least 1", action.sequence()));
        }
    }

    // ----------------------------------------------------------- conditions

    private static void checkConditions(
            PromotionDefinition definition, List<Issue> refusals, List<Issue> warnings, References references) {
        for (ConditionDefinition condition : definition.conditions()) {
            Map<String, Object> operands = condition.operands();
            int sequence = condition.sequence();
            switch (condition.type()) {
                case PRODUCT -> checkIds(operands, "productIds", sequence, refusals, references::knownProducts);
                case CATEGORY -> checkIds(operands, "categoryIds", sequence, refusals, references::knownCategories);
                case VARIANT -> {
                    Set<UUID> variants =
                            checkIds(operands, "variantIds", sequence, refusals, references::knownVariants);
                    if (variants != null
                            && !variants.isEmpty()
                            && references.unpricedVariants(variants).containsAll(variants)) {
                        warnings.add(new Issue(
                                "NO_PRICED_VARIANT_MATCH",
                                "None of the named variants has an active price, so this promotion can never apply",
                                sequence));
                    }
                }
                case QUANTITY_AT_LEAST -> {
                    Long quantity = longOperand(operands, "quantity");
                    if (quantity == null || quantity < 1) {
                        refusals.add(new Issue("OPERAND_INVALID", "quantity must be at least 1", sequence));
                    }
                }
                case SUBTOTAL_AT_LEAST -> {
                    Long amount = longOperand(operands, "amountMinor");
                    if (amount == null || amount < 0) {
                        refusals.add(new Issue("OPERAND_INVALID", "amountMinor must be zero or more", sequence));
                    }
                }
                case CHANNEL ->
                    checkStrings(operands, "channels", sequence, refusals, references::knownChannelCodes, true);
                case LOCATION -> checkIds(operands, "locationIds", sequence, refusals, references::knownLocations);
                case FULFILLMENT_MODE -> {
                    Set<String> modes = stringsOperand(operands, "fulfillmentModes");
                    if (modes == null || modes.isEmpty() || !FULFILMENT_MODES.containsAll(modes)) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID",
                                "fulfillmentModes must be a non-empty subset of DELIVERY, PICKUP, DINE_IN",
                                sequence));
                    }
                }
                case DAY_OF_WEEK -> {
                    List<Integer> days = intsOperand(operands, "daysOfWeek");
                    if (days == null || days.isEmpty()) {
                        refusals.add(new Issue("WEEKDAYS_EMPTY", "At least one weekday must be selected", sequence));
                    } else if (days.stream().anyMatch(day -> day < 1 || day > 7)) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID", "daysOfWeek are ISO numbers, Monday 1 to Sunday 7", sequence));
                    }
                }
                case TIME_OF_DAY -> {
                    Long from = longOperand(operands, "fromMinuteOfDay");
                    Long to = longOperand(operands, "toMinuteOfDay");
                    if (from == null
                            || to == null
                            || from < 0
                            || to < 0
                            || from > MINUTES_IN_DAY
                            || to > MINUTES_IN_DAY) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID",
                                "fromMinuteOfDay and toMinuteOfDay must be minutes between 0 and 1440",
                                sequence));
                    } else if (from.equals(to)) {
                        // A window of zero length matches no minute. 24/7 is no time condition at all.
                        refusals.add(new Issue(
                                "WINDOW_INVERTED",
                                "A time window that starts and ends at the same minute never matches; omit the "
                                        + "condition for all day",
                                sequence));
                    }
                }
                case FIRST_ORDER -> {
                    /* No operands. */
                }
                case CUSTOMER_SEGMENT ->
                    checkStrings(operands, "segments", sequence, refusals, references::knownAudiences, false);
                case PAYMENT_METHOD ->
                    checkStrings(
                            operands, "paymentMethodCodes", sequence, refusals, references::knownPaymentMethods, true);
                case CHANNEL_TYPE -> {
                    Set<String> types = stringsOperand(operands, "channelTypes");
                    if (types == null || types.isEmpty() || !CHANNEL_TYPES.containsAll(types)) {
                        refusals.add(new Issue(
                                "OPERAND_INVALID",
                                "channelTypes must be a non-empty subset of " + new java.util.TreeSet<>(CHANNEL_TYPES)
                                        + "; aggregator orders are externally priced and never reach the engine",
                                sequence));
                    }
                }
                case ORDER_SEQUENCE -> checkSequence(operands, sequence, refusals);
                case DELIVERY_ZONE -> checkIds(operands, "zoneIds", sequence, refusals, references::knownZones);
            }
        }
        checkConditionCombinations(definition, refusals);
    }

    private static void checkSequence(Map<String, Object> operands, int sequence, List<Issue> refusals) {
        Object mode = operands.get("mode");
        Object basis = operands.get("basis");
        if (!"FIRST".equals(mode) && !"NTH".equals(mode) && !"EVERY_NTH".equals(mode)) {
            refusals.add(new Issue("OPERAND_INVALID", "mode must be FIRST, NTH or EVERY_NTH", sequence));
        }
        if (basis != null && !"BRAND".equals(basis) && !"CHANNEL".equals(basis)) {
            refusals.add(new Issue("OPERAND_INVALID", "basis must be BRAND or CHANNEL", sequence));
        }
        if ("NTH".equals(mode) || "EVERY_NTH".equals(mode)) {
            Long n = longOperand(operands, "n");
            if (n == null || n < 2) {
                refusals.add(new Issue("OPERAND_INVALID", "n must be a whole number of at least 2", sequence));
            }
        }
    }

    /** First/Nth sequence promotions must cap each customer at one, or the count race hands them out twice. */
    private static void checkConditionCombinations(PromotionDefinition definition, List<Issue> refusals) {
        for (ConditionDefinition condition : definition.conditions()) {
            if (condition.type() != Condition.Type.ORDER_SEQUENCE) {
                continue;
            }
            Object mode = condition.operands().get("mode");
            boolean exact = "FIRST".equals(mode) || "NTH".equals(mode);
            if (exact && !Integer.valueOf(1).equals(definition.maximumPerCustomer())) {
                refusals.add(new Issue(
                        "SEQUENCE_NEEDS_CUSTOMER_LIMIT",
                        "A first-order or Nth-order promotion must declare a per-customer limit of one: the claim "
                                + "is what stops two quick checkouts both being first",
                        condition.sequence()));
            }
        }
    }

    // --------------------------------------------------------------- window

    private static void checkWindow(PromotionDefinition definition, List<Issue> refusals) {
        Instant from = definition.validFrom();
        Instant until = definition.validUntil();
        if (from != null && until != null && !until.isAfter(from)) {
            refusals.add(new Issue("WINDOW_INVERTED", "validUntil must be after validFrom", null));
        }
    }

    private static void checkLimits(PromotionDefinition definition, List<Issue> refusals) {
        if (definition.maximumRedemptions() != null && definition.maximumRedemptions() < 1) {
            refusals.add(new Issue("OPERAND_INVALID", "maximumRedemptions must be at least 1, or omitted", null));
        }
        if (definition.maximumPerCustomer() != null && definition.maximumPerCustomer() < 1) {
            refusals.add(new Issue("OPERAND_INVALID", "maximumPerCustomer must be at least 1, or omitted", null));
        }
        if (definition.requiresCoupon()
                && (definition.maximumRedemptions() != null || definition.maximumPerCustomer() != null)) {
            refusals.add(new Issue(
                    "LIMIT_ON_COUPON_PROMOTION",
                    "A coupon-gated promotion carries its limits on the coupon, not on the promotion",
                    null));
        }
    }

    private static void checkGroupScopes(
            @Nullable UUID selfId, PromotionDefinition definition, List<Peer> peers, List<Issue> refusals) {
        if (definition.kind() != Promotion.Kind.DISCOUNT) {
            return;
        }
        boolean itemStage = definition.scope() == Promotion.Scope.ITEM;
        for (Peer peer : peers) {
            if (peer.promotionId().equals(selfId)
                    || peer.kind() != Promotion.Kind.DISCOUNT
                    || !peer.stackingGroup().equals(definition.stackingGroup())) {
                continue;
            }
            boolean peerItemStage = peer.scope() == Promotion.Scope.ITEM;
            if (peerItemStage != itemStage) {
                refusals.add(new Issue(
                        "STACKING_GROUP_MIXES_SCOPES",
                        "Group \"" + definition.stackingGroup() + "\" already holds a "
                                + (peerItemStage ? "product-scope" : "order or delivery-scope")
                                + " promotion; an item promotion may not share a group with an order or delivery one",
                        null));
                return;
            }
        }
    }

    // -------------------------------------------------------------- operands

    private static @Nullable Set<UUID> checkIds(
            Map<String, Object> operands,
            String key,
            int sequence,
            List<Issue> refusals,
            java.util.function.Function<Collection<UUID>, Set<UUID>> known) {
        Set<UUID> ids = idsOperand(operands, key);
        if (ids == null || ids.isEmpty()) {
            refusals.add(new Issue("OPERAND_INVALID", key + " must be a non-empty list of ids", sequence));
            return null;
        }
        if (!known.apply(ids).containsAll(ids)) {
            refusals.add(new Issue(
                    "UNKNOWN_REFERENCE", key + " names something that does not belong to this brand", sequence));
        }
        return ids;
    }

    private static void checkStrings(
            Map<String, Object> operands,
            String key,
            int sequence,
            List<Issue> refusals,
            java.util.function.Function<Collection<String>, Set<String>> known,
            boolean upperCaseCodes) {
        Set<String> values = stringsOperand(operands, key);
        if (values == null || values.isEmpty()) {
            refusals.add(new Issue("OPERAND_INVALID", key + " must be a non-empty list", sequence));
            return;
        }
        if (!known.apply(values).containsAll(values)) {
            refusals.add(new Issue(
                    "UNKNOWN_REFERENCE", key + " names something that does not belong to this brand", sequence));
        }
        if (upperCaseCodes && values.stream().anyMatch(value -> value.isBlank())) {
            refusals.add(new Issue("OPERAND_INVALID", key + " cannot contain a blank code", sequence));
        }
    }

    private static @Nullable Long longOperand(Map<String, Object> operands, String key) {
        Object value = operands.get(key);
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private static @Nullable Set<UUID> idsOperand(Map<String, Object> operands, String key) {
        Set<String> raw = stringsOperand(operands, key);
        if (raw == null) {
            return null;
        }
        Set<UUID> ids = new HashSet<>();
        for (String value : raw) {
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return ids;
    }

    private static @Nullable Set<String> stringsOperand(Map<String, Object> operands, String key) {
        Object value = operands.get(key);
        if (!(value instanceof List<?> list)) {
            return null;
        }
        if (!list.stream().allMatch(String.class::isInstance)) {
            return null;
        }
        return list.stream().map(String.class::cast).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static @Nullable List<Integer> intsOperand(Map<String, Object> operands, String key) {
        Object value = operands.get(key);
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Integer> ints = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Integer || entry instanceof Long)) {
                return null;
            }
            ints.add(((Number) entry).intValue());
        }
        return ints;
    }
}
