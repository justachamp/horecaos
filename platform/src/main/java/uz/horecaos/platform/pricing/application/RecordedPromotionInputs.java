package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The promotion inputs a quote was priced with, as the quote's own evidence
 * records them (ADR 0140, the {@code promotionInputs} key of {@code
 * calculation_document}).
 *
 * <p>An amendment's reprice starts from these and overrides exactly what the
 * amendment itself changes. The service instant is the one the order was placed
 * under and is never overridden by the clock, which is why an order placed inside a
 * "lunch 12:00 to 15:00" window keeps its promotion when a line is added at 15:05.
 * The order-sequence position and {@code firstOrder} are authoritative once
 * recorded: an amended first order stays first however many orders the customer
 * placed after it.
 *
 * <p>Holds no personal data: facts about the order's shape, and the ids and
 * definition versions of the promotions that applied.
 */
public record RecordedPromotionInputs(
        Instant serviceInstant,
        String fulfillmentMode,
        @Nullable String channelType,
        @Nullable String paymentMethodCode,
        @Nullable UUID deliveryZoneId,
        @Nullable Integer brandOrderPosition,
        @Nullable Integer channelOrderPosition,
        boolean firstOrder,
        Set<String> segments,
        String timeZone,
        List<AppliedRef> appliedPromotions) {

    public RecordedPromotionInputs {
        segments = segments == null ? Set.of() : Set.copyOf(segments);
        appliedPromotions = appliedPromotions == null ? List.of() : List.copyOf(appliedPromotions);
    }

    /** A promotion that applied, and the definition version it applied at. */
    public record AppliedRef(UUID promotionId, int definitionVersion) {}

    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("serviceInstant", serviceInstant.toString());
        document.put("fulfillmentMode", fulfillmentMode);
        document.put("channelType", channelType);
        document.put("paymentMethodCode", paymentMethodCode);
        document.put("deliveryZoneId", deliveryZoneId == null ? null : deliveryZoneId.toString());
        document.put("brandOrderPosition", brandOrderPosition);
        document.put("channelOrderPosition", channelOrderPosition);
        document.put("firstOrder", firstOrder);
        document.put("segments", new ArrayList<>(new TreeSet<>(segments)));
        document.put("timeZone", timeZone);
        List<Object> applied = new ArrayList<>();
        for (AppliedRef ref : appliedPromotions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("promotionId", ref.promotionId().toString());
            entry.put("definitionVersion", ref.definitionVersion());
            applied.add(entry);
        }
        document.put("appliedPromotions", applied);
        return document;
    }

    public RecordedPromotionInputs withApplied(List<AppliedRef> applied) {
        return new RecordedPromotionInputs(
                serviceInstant,
                fulfillmentMode,
                channelType,
                paymentMethodCode,
                deliveryZoneId,
                brandOrderPosition,
                channelOrderPosition,
                firstOrder,
                segments,
                timeZone,
                applied);
    }

    @SuppressWarnings("unchecked")
    public static RecordedPromotionInputs fromDocument(Map<String, Object> document) {
        List<AppliedRef> applied = new ArrayList<>();
        for (Object entry : (List<Object>) document.getOrDefault("appliedPromotions", List.of())) {
            Map<String, Object> ref = (Map<String, Object>) entry;
            applied.add(new AppliedRef(
                    UUID.fromString(Objects.requireNonNull((String) ref.get("promotionId"))),
                    ((Number) Objects.requireNonNull(ref.get("definitionVersion"))).intValue()));
        }
        return new RecordedPromotionInputs(
                Instant.parse(Objects.requireNonNull((String) document.get("serviceInstant"))),
                Objects.requireNonNull((String) document.get("fulfillmentMode")),
                (String) document.get("channelType"),
                (String) document.get("paymentMethodCode"),
                document.get("deliveryZoneId") == null
                        ? null
                        : UUID.fromString((String) document.get("deliveryZoneId")),
                document.get("brandOrderPosition") == null
                        ? null
                        : ((Number) document.get("brandOrderPosition")).intValue(),
                document.get("channelOrderPosition") == null
                        ? null
                        : ((Number) document.get("channelOrderPosition")).intValue(),
                Boolean.TRUE.equals(document.get("firstOrder")),
                Set.copyOf((List<String>) document.getOrDefault("segments", List.of())),
                (String) document.getOrDefault("timeZone", "UTC"),
                applied);
    }
}
