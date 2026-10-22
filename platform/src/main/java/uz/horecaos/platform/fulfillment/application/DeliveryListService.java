package uz.horecaos.platform.fulfillment.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.DeliveryMoneyPort;
import uz.horecaos.platform.fulfillment.api.DeliveryMoneyPort.DeliveryMoney;
import uz.horecaos.platform.fulfillment.api.DeliveryMoneyPort.ShipmentRef;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryListStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryListStore.Position;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryListStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.Cursor;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;

/**
 * The deliveries list a courier-service tenant works from (ADR 0164 slice two, gap map row 3.1b).
 *
 * <p>One row per delivery plan, with history, filterable by branch, courier, state and date and keyset-paged
 * (ADR 0031). The caller authorises the scope its filters name (ADR 0144); this class reads, joins what a
 * row needs from {@code courier} through {@link DeliveryMoneyPort}, and pages.
 *
 * <p><strong>A delivery is not a new aggregate</strong> (ADR 0164 Decision 7): there is no {@code
 * delivery_request} table behind this. It is the plan, its shipment and the order's own non-PII facts, which is
 * why it opens the existing order detail pane and why nothing here ever carries a recipient.
 */
@Service
public class DeliveryListService {

    private final JdbcDeliveryListStore store;
    private final DeliveryMoneyPort money;

    public DeliveryListService(JdbcDeliveryListStore store, DeliveryMoneyPort money) {
        this.store = store;
        this.money = money;
    }

    /** One page: rows and what each costs and is to collect. */
    public record DeliveryRow(Row row, @Nullable DeliveryMoney money) {}

    /**
     * @param statuses plan statuses; an unknown word is a validation failure rather than a filter that matches
     *     nothing and looks like an empty day
     * @param cursor   the {@code nextCursor} of the previous page; a cursor minted for other filters is refused
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Page<DeliveryRow> list(
            UUID tenantId,
            @Nullable UUID brandId,
            List<UUID> locationIds,
            List<String> statuses,
            @Nullable UUID courierId,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable String cursor,
            @Nullable Integer limit) {

        for (String status : statuses) {
            try {
                PlanStatus.valueOf(status);
            } catch (IllegalArgumentException unknown) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Unknown delivery state \"%s\"".formatted(status),
                        Map.of("field", "status"));
            }
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The range ends before it starts");
        }
        int wanted = Page.limitOrDefault(limit);
        String filterHash = filterHash(tenantId, brandId, locationIds, statuses, courierId, from, to);
        Position after = null;
        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = Cursor.decodeUnsigned(cursor, filterHash)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_REQUEST, "That cursor does not belong to this list or these filters"));
            after = parse(decoded.sortKey());
        }

        List<Row> rows = store.list(tenantId, brandId, locationIds, statuses, courierId, from, to, after, wanted + 1);
        boolean more = rows.size() > wanted;
        List<Row> page = more ? rows.subList(0, wanted) : rows;

        Map<UUID, DeliveryMoney> moneyByShipment = new HashMap<>(money.forShipments(
                tenantId,
                page.stream()
                        .filter(row -> row.shipmentId() != null)
                        .map(row -> new ShipmentRef(
                                java.util.Objects.requireNonNull(row.shipmentId()),
                                row.orderId(),
                                row.prepaid(),
                                row.orderTotalMinor()))
                        .toList()));
        List<DeliveryRow> items = page.stream()
                .map(row ->
                        new DeliveryRow(row, row.shipmentId() == null ? null : moneyByShipment.get(row.shipmentId())))
                .toList();
        String next = null;
        if (more) {
            Row last = page.getLast();
            next = new Cursor(key(last), filterHash).encodeUnsigned();
        }
        return new Page<>(items, next);
    }

    private static String key(Row row) {
        return row.confirmedAt().truncatedTo(ChronoUnit.MICROS) + "~" + row.planId();
    }

    private static Position parse(String sortKey) {
        int split = sortKey.indexOf('~');
        try {
            return new Position(
                    Instant.parse(sortKey.substring(0, split)), UUID.fromString(sortKey.substring(split + 1)));
        } catch (RuntimeException malformed) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "That cursor does not belong to this list");
        }
    }

    private static String filterHash(
            UUID tenantId,
            @Nullable UUID brandId,
            List<UUID> locationIds,
            List<String> statuses,
            @Nullable UUID courierId,
            @Nullable Instant from,
            @Nullable Instant to) {
        String canonical = String.join(
                "|",
                tenantId.toString(),
                String.valueOf(brandId),
                locationIds.stream().map(UUID::toString).sorted().toList().toString(),
                statuses.stream().sorted().toList().toString(),
                String.valueOf(courierId),
                String.valueOf(from),
                String.valueOf(to));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
