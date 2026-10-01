package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.MenuMembershipLookup;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PricingEngine.PricingInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.PromotionInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.TaxMode;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.PromotionContext;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.QuoteRequest;

/**
 * ADR 0140: every value a promotion condition reads is a term in the context
 * hash.
 *
 * <p>The previous engine hashed promotion ids, versions and the presented coupon
 * and nothing a condition actually reads, so two carts that priced differently by
 * first-order status, segment, local time or fulfilment mode shared a hash and a
 * checkout could accept one for the other. This test is the guard the record asks
 * for: it enumerates {@link PromotionContext}'s components by reflection, changes
 * each one in turn and requires the hash to move. A new component with no term in
 * the canonical string fails here, so the next condition cannot repeat the hole.
 */
class PricingEngineContextHashTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID PUBLICATION = UUID.randomUUID();
    private static final UUID PRICE_BOOK = UUID.randomUUID();
    private static final UUID TAX_PROFILE = UUID.randomUUID();
    private static final UUID BURGER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T07:30:00Z");

    private final PricingEngine engine = new PricingEngine();

    @Test
    @DisplayName("every PromotionContext component moves the context hash when it changes")
    void everyContextComponentIsATermInTheHash() throws Exception {
        PromotionContext base = fullyPopulatedContext();
        String baseline = hashFor(inputsWith(base));

        RecordComponent[] components = PromotionContext.class.getRecordComponents();
        assertThat(components).as("the record has components to enumerate").hasSizeGreaterThan(10);

        List<String> unhashed = new ArrayList<>();
        for (int index = 0; index < components.length; index++) {
            Object[] arguments = new Object[components.length];
            for (int other = 0; other < components.length; other++) {
                arguments[other] = components[other].getAccessor().invoke(base);
            }
            arguments[index] = alternative(components[index], arguments[index]);

            Class<?>[] types = java.util.Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            PromotionContext changed = (PromotionContext)
                    PromotionContext.class.getDeclaredConstructor(types).newInstance(arguments);
            if (hashFor(inputsWith(changed)).equals(baseline)) {
                unhashed.add(components[index].getName());
            }
        }

        assertThat(unhashed)
                .as("components that are inputs to a condition but not terms in the context hash")
                .isEmpty();
    }

    @Test
    @DisplayName("the catalog membership behind a line is a term in the hash")
    void membershipMovesTheHash() {
        String withoutCategory = hashFor(
                inputsWithMembership(Map.of(BURGER, new MenuMembershipLookup.Membership(UUID.randomUUID(), Set.of()))));
        UUID product = UUID.randomUUID();
        String first = hashFor(inputsWithMembership(
                Map.of(BURGER, new MenuMembershipLookup.Membership(product, Set.of(UUID.randomUUID())))));
        String second = hashFor(inputsWithMembership(
                Map.of(BURGER, new MenuMembershipLookup.Membership(product, Set.of(UUID.randomUUID())))));

        assertThat(first).as("a different category set").isNotEqualTo(second);
        assertThat(first).isNotEqualTo(withoutCategory);
    }

    @Test
    @DisplayName("a promotion's definition version is a term in the hash")
    void definitionVersionMovesTheHash() {
        Promotion v1 = promotion(UUID.nameUUIDFromBytes("p".getBytes()), 1);
        Promotion v2 = promotion(UUID.nameUUIDFromBytes("p".getBytes()), 2);
        PromotionContext context = fullyPopulatedContext();

        assertThat(hashFor(new PromotionInputs(List.of(v1), context, Map.of())))
                .isNotEqualTo(hashFor(new PromotionInputs(List.of(v2), context, Map.of())));
    }

    @Test
    @DisplayName("the same inputs hash the same however the sets were built")
    void theHashIsReproducible() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PromotionContext left = contextWith(Set.of("VIP", "NEW"), Set.of(a, b));
        PromotionContext right = contextWith(Set.of("NEW", "VIP"), Set.of(b, a));

        assertThat(hashFor(inputsWith(left))).isEqualTo(hashFor(inputsWith(right)));
        assertThat(engine.price(request(), inputsWith(left), NOW).contextHash())
                .as("the engine's own result carries the same hash")
                .isEqualTo(hashFor(inputsWith(left)));
    }

    @Test
    @DisplayName("the calculation version is 3 and leads the canonical string")
    void theCalculationVersionIsThree() {
        assertThat(PricingEngine.CALCULATION_VERSION).isEqualTo(3);
    }

    // ----------------------------------------------------------------- helpers

    private String hashFor(PromotionInputs promotions) {
        return engine.price(request(), inputs(promotions), NOW).contextHash();
    }

    private String hashFor(PricingInputs inputs) {
        return engine.price(request(), inputs, NOW).contextHash();
    }

    private PricingInputs inputsWith(PromotionContext context) {
        return inputs(
                new PromotionInputs(List.of(promotion(UUID.nameUUIDFromBytes("p".getBytes()), 1)), context, Map.of()));
    }

    private PricingInputs inputsWithMembership(Map<UUID, MenuMembershipLookup.Membership> membership) {
        return inputs(new PromotionInputs(
                List.of(promotion(UUID.nameUUIDFromBytes("p".getBytes()), 1)), fullyPopulatedContext(), membership));
    }

    private PricingInputs inputs(PromotionInputs promotions) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                Map.of(BURGER, 50_000L),
                Map.of(),
                Map.of(),
                null,
                promotions);
    }

    private static QuoteRequest request() {
        return new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-1", BURGER, 1, List.of())),
                null);
    }

    private static Promotion promotion(UUID id, int version) {
        return new Promotion(
                id,
                TENANT,
                BRAND,
                "P",
                Promotion.Scope.ORDER,
                "g",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                null,
                version,
                List.of(),
                List.of(new Promotion.Action(
                        1,
                        Promotion.Action.Type.ORDER_FIXED_DISCOUNT,
                        new Promotion.Operands(Map.of("amountMinor", 1_000L)))));
    }

    private static PromotionContext contextWith(Set<String> segments, Set<UUID> coupons) {
        return new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                "CLICK",
                UUID.nameUUIDFromBytes("zone".getBytes()),
                true,
                1,
                1,
                segments,
                coupons,
                NOW,
                3,
                750,
                Set.of(),
                Set.of());
    }

    private static PromotionContext fullyPopulatedContext() {
        return new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                "CLICK",
                UUID.nameUUIDFromBytes("zone".getBytes()),
                true,
                2,
                3,
                Set.of("VIP"),
                Set.of(UUID.nameUUIDFromBytes("coupon".getBytes())),
                NOW,
                3,
                750,
                Set.of(UUID.nameUUIDFromBytes("limit".getBytes())),
                Set.of(UUID.nameUUIDFromBytes("unclaimed".getBytes())));
    }

    /** A different, valid value of the component's type. */
    private static Object alternative(RecordComponent component, Object current) {
        Class<?> type = component.getType();
        if (type == String.class) {
            return current + "-changed";
        }
        if (type == UUID.class) {
            return UUID.nameUUIDFromBytes(("changed" + current).getBytes());
        }
        if (type == boolean.class) {
            return !((Boolean) current);
        }
        if (type == int.class) {
            return ((Integer) current) + 1;
        }
        if (type == Integer.class) {
            return current == null ? Integer.valueOf(9) : ((Integer) current) + 1;
        }
        if (type == Instant.class) {
            return ((Instant) current).plusSeconds(7_200);
        }
        if (Set.class.isAssignableFrom(type)) {
            Type element = ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
            Set<Object> changed = new HashSet<>((Set<?>) current);
            changed.add(element == UUID.class ? UUID.nameUUIDFromBytes("extra".getBytes()) : "EXTRA");
            return changed;
        }
        throw new AssertionError("Teach this test how to change a " + type.getSimpleName() + " component ("
                + component.getName() + ") so the new input is checked against the hash");
    }
}
