package uz.horecaos.platform.pricing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A promotion as an operator authors it (ADR 0140), before and after it becomes
 * the engine's {@link Promotion}.
 *
 * <p>The value the authoring endpoints accept, the validator checks, the
 * simulator takes as a candidate and the definition history stores. It is data:
 * the closed condition and action vocabularies of {@link Promotion} with their
 * operands in plain maps, so the canonical form round-trips through a JSONB
 * column without a schema of its own.
 *
 * <p>{@link #canonical()} is the form stored in {@code
 * pricing.promotion_definition_versions}: every key present and ordered, so two
 * equal definitions serialise to the same document and an old quote is explainable
 * against exactly the rule that priced it.
 */
public record PromotionDefinition(
        String code,
        String name,
        Promotion.Kind kind,
        Promotion.Scope scope,
        String stackingGroup,
        boolean exclusive,
        int priority,
        boolean requiresCoupon,
        @Nullable Long maximumDiscountMinor,
        String currency,
        @Nullable Instant validFrom,
        @Nullable Instant validUntil,
        @Nullable Integer maximumRedemptions,
        @Nullable Integer maximumPerCustomer,
        Promotion.LoyaltyAccrual loyaltyAccrual,
        Promotion.LoyaltyRedemption loyaltyRedemption,
        List<ConditionDefinition> conditions,
        List<ActionDefinition> actions) {

    public PromotionDefinition {
        Objects.requireNonNull(kind, "A promotion kind is required");
        Objects.requireNonNull(scope, "A promotion scope is required");
        Objects.requireNonNull(loyaltyAccrual, "A loyalty accrual rule is required");
        Objects.requireNonNull(loyaltyRedemption, "A loyalty redemption rule is required");
        conditions = List.copyOf(conditions);
        actions = List.copyOf(actions);
    }

    /** One condition, with operands exactly as authored. */
    public record ConditionDefinition(int sequence, Promotion.Condition.Type type, Map<String, Object> operands) {

        public ConditionDefinition {
            operands = Map.copyOf(operands);
        }
    }

    /** One action, with operands exactly as authored. */
    public record ActionDefinition(int sequence, Promotion.Action.Type type, Map<String, Object> operands) {

        public ActionDefinition {
            operands = Map.copyOf(operands);
        }
    }

    /**
     * The engine's value for this definition.
     *
     * <p>A coupon-gated promotion's {@code code} is withheld from the engine value:
     * for a promo code the operator-facing handle and the customer-redeemable code
     * word are the same string (ADR 0072), and the word is a bearer secret that must
     * not reach an adjustment's description code.
     */
    public Promotion toPromotion(UUID promotionId, UUID tenantId, UUID brandId, int definitionVersion, Instant now) {
        return new Promotion(
                promotionId,
                tenantId,
                brandId,
                requiresCoupon ? "" : code,
                kind,
                scope,
                stackingGroup,
                exclusive,
                priority,
                requiresCoupon,
                maximumDiscountMinor,
                currency,
                validFrom == null ? now : validFrom,
                validUntil,
                definitionVersion,
                maximumRedemptions,
                maximumPerCustomer,
                loyaltyAccrual,
                loyaltyRedemption,
                conditions.stream()
                        .map(condition -> new Promotion.Condition(
                                condition.sequence(), condition.type(), new Promotion.Operands(condition.operands())))
                        .toList(),
                actions.stream()
                        .map(action -> new Promotion.Action(
                                action.sequence(), action.type(), new Promotion.Operands(action.operands())))
                        .toList());
    }

    /** The canonical document stored in the definition history. */
    public Map<String, Object> canonical() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("code", code);
        document.put("name", name);
        document.put("kind", kind.name());
        document.put("scope", scope.name());
        document.put("stackingGroup", stackingGroup);
        document.put("exclusive", exclusive);
        document.put("priority", priority);
        document.put("requiresCoupon", requiresCoupon);
        document.put("maximumDiscountMinor", maximumDiscountMinor);
        document.put("currency", currency);
        document.put("validFrom", validFrom == null ? null : validFrom.toString());
        document.put("validUntil", validUntil == null ? null : validUntil.toString());
        document.put("maximumRedemptions", maximumRedemptions);
        document.put("maximumPerCustomer", maximumPerCustomer);
        document.put("loyaltyAccrual", loyaltyAccrual.name());
        document.put("loyaltyRedemption", loyaltyRedemption.name());
        List<Object> conditionDocuments = new ArrayList<>();
        for (ConditionDefinition condition : conditions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", condition.sequence());
            entry.put("type", condition.type().name());
            entry.put("operands", new TreeMap<>(condition.operands()));
            conditionDocuments.add(entry);
        }
        document.put("conditions", conditionDocuments);
        List<Object> actionDocuments = new ArrayList<>();
        for (ActionDefinition action : actions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sequence", action.sequence());
            entry.put("type", action.type().name());
            entry.put("operands", new TreeMap<>(action.operands()));
            actionDocuments.add(entry);
        }
        document.put("actions", actionDocuments);
        return document;
    }

    private static String text(Map<String, Object> document, String key) {
        return Objects.requireNonNull((String) document.get(key), "A stored definition always carries " + key);
    }

    private static int intOf(Map<String, Object> document, String key) {
        return ((Number) Objects.requireNonNull(document.get(key), "A stored definition always carries " + key))
                .intValue();
    }

    /** Rebuilds a definition from its canonical document; the inverse of {@link #canonical()}. */
    @SuppressWarnings("unchecked")
    public static PromotionDefinition fromCanonical(Map<String, Object> document) {
        List<ConditionDefinition> conditions = new ArrayList<>();
        for (Object entry : (List<Object>) document.getOrDefault("conditions", List.of())) {
            Map<String, Object> condition = (Map<String, Object>) entry;
            conditions.add(new ConditionDefinition(
                    intOf(condition, "sequence"),
                    Promotion.Condition.Type.valueOf(text(condition, "type")),
                    (Map<String, Object>) condition.getOrDefault("operands", Map.of())));
        }
        List<ActionDefinition> actions = new ArrayList<>();
        for (Object entry : (List<Object>) document.getOrDefault("actions", List.of())) {
            Map<String, Object> action = (Map<String, Object>) entry;
            actions.add(new ActionDefinition(
                    intOf(action, "sequence"),
                    Promotion.Action.Type.valueOf(text(action, "type")),
                    (Map<String, Object>) action.getOrDefault("operands", Map.of())));
        }
        return new PromotionDefinition(
                text(document, "code"),
                text(document, "name"),
                Promotion.Kind.valueOf((String) document.getOrDefault("kind", "DISCOUNT")),
                Promotion.Scope.valueOf(text(document, "scope")),
                text(document, "stackingGroup"),
                Boolean.TRUE.equals(document.get("exclusive")),
                ((Number) document.getOrDefault("priority", 0)).intValue(),
                Boolean.TRUE.equals(document.get("requiresCoupon")),
                document.get("maximumDiscountMinor") == null
                        ? null
                        : ((Number) document.get("maximumDiscountMinor")).longValue(),
                text(document, "currency"),
                document.get("validFrom") == null ? null : Instant.parse((String) document.get("validFrom")),
                document.get("validUntil") == null ? null : Instant.parse((String) document.get("validUntil")),
                document.get("maximumRedemptions") == null
                        ? null
                        : ((Number) document.get("maximumRedemptions")).intValue(),
                document.get("maximumPerCustomer") == null
                        ? null
                        : ((Number) document.get("maximumPerCustomer")).intValue(),
                Promotion.LoyaltyAccrual.valueOf((String) document.getOrDefault("loyaltyAccrual", "ACCRUE")),
                Promotion.LoyaltyRedemption.valueOf((String) document.getOrDefault("loyaltyRedemption", "ALLOW")),
                conditions,
                actions);
    }
}
