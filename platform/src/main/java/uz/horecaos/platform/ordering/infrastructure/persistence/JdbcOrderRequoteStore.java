package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Scheduled orders' promotion re-quotes (ADR 0140, ADR 0019, V0474).
 *
 * <p>A re-quote is evidence about an order and never a change to it: this store holds the
 * findings and the question "which scheduled orders are due their checkpoint", and writes
 * nothing to {@code ordering.orders}, its lines, its revisions or its adjustments. Every
 * statement carries the tenant.
 */
@Repository
public class JdbcOrderRequoteStore {

    private static final TypeReference<List<PromotionChange>> CHANGES = new TypeReference<>() {};

    /** The statuses in which a scheduled order is still waiting for its kitchen: nothing is being made yet. */
    private static final String AWAITING_KITCHEN =
            "('RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL', 'CONFIRMED')";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOrderRequoteStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** One scheduled order due its checkpoint. */
    public record DueOrder(UUID tenantId, UUID orderId) {}

    /**
     * The scheduled orders whose checkpoint has arrived and which have no checkpoint finding yet.
     *
     * <p>Scheduled means the promise was chosen by the caller ({@code SCHEDULED_SLOT}); priced by
     * HorecaOS, since an aggregator's order is priced by the aggregator and never reaches the
     * engine (ADR 0040); and still waiting for its kitchen. An order whose promised time has
     * already passed is not swept: it is being served, and a re-quote then judges nothing the
     * customer can still be offered.
     *
     * @param leadSeconds how long before the promised time the checkpoint falls
     */
    public List<DueOrder> dueForCheckpoint(Instant now, long leadSeconds, int limit) {
        return jdbc.sql("""
                SELECT o.tenant_id, o.id
                FROM ordering.orders o
                WHERE o.promise_basis = 'SCHEDULED_SLOT'
                  AND o.pricing_authority = 'HORECAOS'
                  AND o.status IN %s
                  AND o.promised_at > :now
                  AND o.promised_at <= :checkpointHorizon
                  AND NOT EXISTS (
                      SELECT 1 FROM ordering.order_promotion_requotes r
                      WHERE r.tenant_id = o.tenant_id AND r.order_id = o.id AND r.trigger_kind = 'CHECKPOINT')
                ORDER BY o.promised_at, o.id
                LIMIT :limit
                """.formatted(AWAITING_KITCHEN))
                .param("now", utc(now))
                .param("checkpointHorizon", utc(now.plusSeconds(leadSeconds)))
                .param("limit", limit)
                .query((row, n) ->
                        new DueOrder(row.getObject("tenant_id", UUID.class), row.getObject("id", UUID.class)))
                .list();
    }

    /** Whether the order is one a checkpoint re-quote applies to at all (same test as the sweep, one order). */
    public boolean isAwaitingItsCheckpoint(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT count(*) FROM ordering.orders o
                WHERE o.tenant_id = :tenantId AND o.id = :orderId
                  AND o.promise_basis = 'SCHEDULED_SLOT'
                  AND o.pricing_authority = 'HORECAOS'
                  AND o.status IN %s
                """.formatted(AWAITING_KITCHEN))
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Records a finding. A second {@code CHECKPOINT} finding for the same order is not written
     * (the unique index), and the answer says so, so two sweeps racing leave one row.
     *
     * @return whether a row was written
     */
    public boolean insert(Finding finding) {
        return jdbc.sql("""
                INSERT INTO ordering.order_promotion_requotes (
                    id, tenant_id, order_id, trigger_kind, checkpoint_at, based_on_quote_id, requote_quote_id,
                    outcome, refusal_code, currency, held_total_minor, held_discount_minor,
                    requote_total_minor, requote_discount_minor, promotion_changes, created_at)
                VALUES (:id, :tenantId, :orderId, :trigger, :checkpointAt, :basedOn, :requote,
                    :outcome, :refusal, :currency, :heldTotal, :heldDiscount,
                    :requoteTotal, :requoteDiscount, CAST(:changes AS jsonb), :createdAt)
                ON CONFLICT DO NOTHING
                """)
                        .param("id", finding.id())
                        .param("tenantId", finding.tenantId())
                        .param("orderId", finding.orderId())
                        .param("trigger", finding.trigger())
                        .param("checkpointAt", utc(finding.checkpointAt()))
                        .param("basedOn", finding.basedOnQuoteId())
                        .param("requote", finding.requoteQuoteId())
                        .param("outcome", finding.outcome())
                        .param("refusal", finding.refusalCode())
                        .param("currency", finding.currency())
                        .param("heldTotal", finding.heldTotalMinor())
                        .param("heldDiscount", finding.heldDiscountMinor())
                        .param("requoteTotal", finding.requoteTotalMinor())
                        .param("requoteDiscount", finding.requoteDiscountMinor())
                        .param("changes", objectMapper.writeValueAsString(finding.promotionChanges()))
                        .param("createdAt", utc(finding.createdAt()))
                        .update()
                > 0;
    }

    /** The order's findings, newest first. */
    public List<Finding> list(UUID tenantId, UUID orderId, int limit) {
        return jdbc.sql("""
                SELECT id, tenant_id, order_id, trigger_kind, checkpoint_at, based_on_quote_id, requote_quote_id,
                       outcome, refusal_code, currency, held_total_minor, held_discount_minor,
                       requote_total_minor, requote_discount_minor, promotion_changes::text AS promotion_changes,
                       created_at
                FROM ordering.order_promotion_requotes
                WHERE tenant_id = :tenantId AND order_id = :orderId
                ORDER BY created_at DESC, id
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("limit", limit)
                .query((row, n) -> new Finding(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getString("trigger_kind"),
                        required(row.getObject("checkpoint_at", OffsetDateTime.class)),
                        row.getObject("based_on_quote_id", UUID.class),
                        row.getObject("requote_quote_id", UUID.class),
                        row.getString("outcome"),
                        row.getString("refusal_code"),
                        row.getString("currency"),
                        row.getLong("held_total_minor"),
                        row.getLong("held_discount_minor"),
                        (Long) row.getObject("requote_total_minor"),
                        (Long) row.getObject("requote_discount_minor"),
                        objectMapper.readValue(row.getString("promotion_changes"), CHANGES),
                        required(row.getObject("created_at", OffsetDateTime.class))))
                .list();
    }

    /**
     * One promotion whose effect on the order differs between the quote the customer holds and the
     * re-quote.
     *
     * @param change {@code DROPPED} (it gave something and now gives nothing), {@code GAINED} (the
     *     reverse) or {@code CHANGED} (it gives a different amount)
     * @param heldMinor the promotion's signed adjustments on the held quote: a discount is negative
     * @param requoteMinor the same on the re-quote
     */
    public record PromotionChange(UUID promotionId, String change, long heldMinor, long requoteMinor) {}

    /**
     * @param trigger {@code CHECKPOINT} or {@code OPERATOR}
     * @param outcome {@code UNCHANGED}, {@code CHANGED} or {@code NOT_PRICEABLE}
     * @param requoteQuoteId null exactly when {@code outcome} is {@code NOT_PRICEABLE}, as are the
     *     requote amounts
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public record Finding(
            UUID id,
            UUID tenantId,
            UUID orderId,
            String trigger,
            Instant checkpointAt,
            UUID basedOnQuoteId,
            @Nullable UUID requoteQuoteId,
            String outcome,
            @Nullable String refusalCode,
            String currency,
            long heldTotalMinor,
            long heldDiscountMinor,
            @Nullable Long requoteTotalMinor,
            @Nullable Long requoteDiscountMinor,
            List<PromotionChange> promotionChanges,
            Instant createdAt) {

        public Finding {
            promotionChanges = promotionChanges == null ? List.of() : List.copyOf(promotionChanges);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant required(@Nullable OffsetDateTime value) {
        if (value == null) {
            throw new IllegalStateException("A NOT NULL timestamp column read back as null");
        }
        return value.toInstant();
    }
}
