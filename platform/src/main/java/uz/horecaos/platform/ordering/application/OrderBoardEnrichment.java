package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.CourierEtaPort;
import uz.horecaos.platform.fulfillment.api.DeliveryDistancePort;
import uz.horecaos.platform.ordering.api.OrderFiscalStatusPort;
import uz.horecaos.platform.ordering.api.OrderReprintPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderBoardFacts;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderBoardRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderItemSummary;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * The facts the order board draws beyond the order row, each read once per page from the module that owns
 * it (gap map row {@code 1.1}, ADR 0160 «Board columns», ADR 0102).
 *
 * <p><strong>One batch per fact per page, never a query per row.</strong> The facts are:
 *
 * <ul>
 *   <li>the payment method of the order's intent, for the payment icon, and the order's live positions
 *       (count and first name) for the «Позиции» column -- both belong to default-shown columns, so both
 *       are always read;</li>
 *   <li>the five optional columns' facts -- {@code courierEta}, {@code source}, {@code fiscal}, {@code
 *       distance}, {@code externalNumber} -- read only when the caller names the fact in {@code
 *       include}. The board polls every ten seconds; an operator who did not turn the column on pays for
 *       no statement on its behalf.</li>
 * </ul>
 *
 * <p>The courier ETA is asked of the page's {@code DELIVERY} orders only -- a pickup or a dine-in order
 * has no delivery plan to ask about -- and answers only for a partner-quoted plan (a snapshot of the
 * winning quote, not a live feed); an order carried by a house courier has none, and the column draws a
 * dash for it. The channel's presentation is resolved on the server (system type, the channel's own icon
 * and name) because the roles who work the board do not hold {@code CHANNEL_READ} and could not read the
 * registry themselves.
 */
@Service
public class OrderBoardEnrichment {

    /** The optional facts a board read may ask for, named as the console's column keys spell them. */
    public enum Fact {
        COURIER_ETA("courierEta"),
        SOURCE("source"),
        FISCAL("fiscal"),
        DISTANCE("distance"),
        EXTERNAL_NUMBER("externalNumber");

        private final String key;

        Fact(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Optional<Fact> ofKey(String key) {
            for (Fact fact : values()) {
                if (fact.key.equals(key)) {
                    return Optional.of(fact);
                }
            }
            return Optional.empty();
        }
    }

    private final JdbcOrderStore orders;
    private final PaymentIntentPort payments;
    private final CourierEtaPort courierEtas;
    private final SalesChannelLookup channels;
    private final OrderFiscalStatusPort fiscalStatuses;
    private final DeliveryDistancePort distances;
    private final OrderReprintPort reprints;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OrderBoardEnrichment(
            JdbcOrderStore orders,
            PaymentIntentPort payments,
            CourierEtaPort courierEtas,
            SalesChannelLookup channels,
            OrderFiscalStatusPort fiscalStatuses,
            DeliveryDistancePort distances,
            OrderReprintPort reprints) {
        this.orders = orders;
        this.payments = payments;
        this.courierEtas = courierEtas;
        this.channels = channels;
        this.fiscalStatuses = fiscalStatuses;
        this.distances = distances;
        this.reprints = reprints;
    }

    /** The facts a caller names by their column keys; a key that is not an optional fact is ignored. */
    public static Set<Fact> factsOf(Collection<String> keys) {
        Set<Fact> facts = EnumSet.noneOf(Fact.class);
        for (String key : keys) {
            Fact.ofKey(key.strip()).ifPresent(facts::add);
        }
        return facts;
    }

    /**
     * {@code rows} with their board facts read, in the order they came.
     *
     * @param include the optional facts to read on top of the always-read ones
     */
    @Transactional(readOnly = true)
    public List<OrderBoardRow> enrich(UUID tenantId, List<OrderBoardRow> rows, Set<Fact> include) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<UUID> allOrders = rows.stream().map(row -> row.order().orderId()).collect(Collectors.toSet());

        // Which branches of the page can print: one read per brand on the page (the brand board has one), so
        // REPRINT is in actions[] only where it can work.
        Set<UUID> printingBranches = printingBranchesOf(tenantId, rows);
        Map<UUID, String> methods = payments.paymentMethodsByOrders(tenantId, allOrders);
        Map<UUID, OrderItemSummary> items = orders.itemSummariesByOrders(tenantId, allOrders);

        Map<UUID, Instant> etas = Map.of();
        if (include.contains(Fact.COURIER_ETA)) {
            Set<UUID> deliveries = rows.stream()
                    .filter(row -> row.order().fulfillmentMode() == FulfillmentMode.DELIVERY)
                    .map(row -> row.order().orderId())
                    .collect(Collectors.toSet());
            if (!deliveries.isEmpty()) {
                etas = courierEtas.etaByOrders(tenantId, deliveries);
            }
        }
        Map<UUID, Integer> meters = Map.of();
        if (include.contains(Fact.DISTANCE)) {
            Set<UUID> deliveries = rows.stream()
                    .filter(row -> row.order().fulfillmentMode() == FulfillmentMode.DELIVERY)
                    .map(row -> row.order().orderId())
                    .collect(Collectors.toSet());
            if (!deliveries.isEmpty()) {
                meters = distances.distanceMetersByOrders(tenantId, deliveries);
            }
        }
        Map<UUID, String> fiscal =
                include.contains(Fact.FISCAL) ? fiscalStatuses.fiscalStatusByOrders(tenantId, allOrders) : Map.of();
        Map<UUID, String> externals =
                include.contains(Fact.EXTERNAL_NUMBER) ? orders.externalNumbersByOrders(tenantId, allOrders) : Map.of();
        Map<String, SalesChannel> channelsByCode =
                include.contains(Fact.SOURCE) ? channelsOf(tenantId, rows) : Map.of();

        List<OrderBoardRow> out = new ArrayList<>(rows.size());
        for (OrderBoardRow row : rows) {
            UUID orderId = row.order().orderId();
            OrderBoardFacts facts = row.facts()
                    .withPaymentMethod(methods.get(orderId))
                    .withReprintAvailable(printingBranches.contains(row.order().locationId()));
            OrderItemSummary summary = items.get(orderId);
            if (summary != null) {
                facts = facts.withItems(summary.itemCount(), summary.firstItemName());
            }
            if (include.contains(Fact.COURIER_ETA)) {
                facts = facts.withCourierEta(etas.get(orderId));
            }
            if (include.contains(Fact.DISTANCE)) {
                facts = facts.withDistance(meters.get(orderId));
            }
            if (include.contains(Fact.FISCAL)) {
                facts = facts.withFiscalStatus(fiscal.get(orderId));
            }
            if (include.contains(Fact.EXTERNAL_NUMBER)) {
                facts = facts.withExternalNumber(externals.get(orderId));
            }
            if (include.contains(Fact.SOURCE)) {
                SalesChannel channel = channelsByCode.get(row.order().channelCode());
                if (channel != null) {
                    facts = facts.withChannel(channel.systemType().name(), channel.icon(), channel.displayName());
                }
            }
            out.add(row.withFacts(facts));
        }
        return out;
    }

    /** Whether one branch can print its orders' documents; the detail screen's single-order counterpart. */
    public boolean reprintAvailable(UUID tenantId, UUID brandId, UUID locationId) {
        return reprints.branchesThatCanPrint(tenantId, brandId, Set.of(locationId))
                .contains(locationId);
    }

    private Set<UUID> printingBranchesOf(UUID tenantId, List<OrderBoardRow> rows) {
        Map<UUID, Set<UUID>> branchesByBrand = new java.util.LinkedHashMap<>();
        for (OrderBoardRow row : rows) {
            branchesByBrand
                    .computeIfAbsent(row.order().brandId(), brand -> new LinkedHashSet<>())
                    .add(row.order().locationId());
        }
        Set<UUID> printing = new java.util.HashSet<>();
        branchesByBrand.forEach(
                (brandId, branches) -> printing.addAll(reprints.branchesThatCanPrint(tenantId, brandId, branches)));
        return printing;
    }

    /** The channels of a page, resolved with one lookup over its distinct codes. */
    private Map<String, SalesChannel> channelsOf(UUID tenantId, List<OrderBoardRow> rows) {
        Set<String> codes = new LinkedHashSet<>();
        for (OrderBoardRow row : rows) {
            String code = row.order().channelCode();
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        return codes.isEmpty() ? Map.of() : channels.byCodes(tenantId, codes);
    }
}
