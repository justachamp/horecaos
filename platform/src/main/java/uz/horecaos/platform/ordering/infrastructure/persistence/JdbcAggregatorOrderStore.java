package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes a manually keyed aggregator order into {@code ordering.orders}
 * (ADR 0040, wave P14's row {@code 1.3g}).
 *
 * <p>The write shape mirrors {@code JdbcMarketplaceOrderIntake} — the one
 * other writer of the V0038 authority columns — with the differences a phoned
 * order actually has from an automated partner push: {@code entry_mode =
 * 'MANUAL'}, attributed to the operator who typed it
 * ({@code created_by_actor_type = 'USER'}) rather than to the partner's own
 * machine principal, and {@code fulfillment_authority = 'HORECAOS'} — this
 * row's own title is "no live provider binding", so nothing on the
 * aggregator's side is dispatching a courier or projecting a status back;
 * the branch fulfils it exactly as it would an ordinary own-channel order.
 *
 * <p>Deliberately narrower than the automated intake in two ways, both
 * documented rather than silently absent. First, no {@code
 * ordering.order_external_pricing} row: that table's settlement columns
 * (commission, payout, a reconciliation statement) describe a partner
 * relationship this manual path does not have evidence for, and nothing
 * reads the table today (grep confirms {@code JdbcMarketplaceOrderIntake} is
 * still its only reader), so a future reconciliation feature can add the row
 * when it exists to reconcile against. Second, no {@code
 * order_handover_challenges} row: that table proves a *live* handover to a
 * partner courier or customer, which is exactly the live channel this row's
 * title says is absent.
 *
 * <p>One {@code order_external_references} row is written, because the
 * opposite choice would be a regression: the aggregator's own order number is
 * the identifier a customer or a courier actually quotes, and {@code
 * OperationsOrderController.board}'s {@code reference} filter (ADR 0102, row
 * {@code 1.1d}) already searches this exact table — an aggregator-entry order
 * with no reference row would be unfindable by the one number anybody outside
 * HorecaOS ever reads back.
 *
 * <p>Wave 11 w5-fulfillment-destination: {@code DELIVERY} is no longer
 * refused — see {@link Command#fulfillmentMode} for the one column this
 * class' own write shape needed to admit it, and {@code
 * AggregatorOrderIntakeService#planDelivery} for what happens after this
 * class returns (the customer snapshot and delivery plan a native order
 * gets at confirmation, which this order never passes through). The two
 * narrower-than-automated gaps above are unaffected by fulfilment mode.
 */
@Repository
public class JdbcAggregatorOrderStore {

    private final JdbcClient jdbc;

    public JdbcAggregatorOrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One line an operator typed in from the aggregator's own order screen. */
    public record Line(
            @Nullable UUID variantId,
            String nameSnapshot,
            int quantity,
            long unitAmountMinor,
            @Nullable String externalItemReference) {}

    /**
     * @param fulfillmentMode {@code PICKUP} or {@code DELIVERY} (row {@code
     *                        1.3g}). A {@code DELIVERY} entry is written
     *                        already {@code CONFIRMED} rather than {@code
     *                        RECEIVED} — unlike a native order, there is no
     *                        accept/reject step left to take: the aggregator
     *                        already has the customer's agreement, and
     *                        {@code CONFIRMED} is the one status {@code
     *                        DeliveryOrderPort#deliveryOrder} (and so {@code
     *                        DeliveryPlanner#planFor}) will source from. A
     *                        {@code PICKUP} entry is unaffected and keeps
     *                        {@code RECEIVED}, its behaviour since this
     *                        class was written
     */
    public record Command(
            UUID orderId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            String channelCode,
            UUID marketplaceBindingId,
            String externalOrderId,
            List<Line> lines,
            String currency,
            long subtotalMinor,
            long discountMinor,
            long feeMinor,
            long taxMinor,
            long totalMinor,
            String idempotencyKey,
            String operatorSubject,
            String fulfillmentMode,
            Instant now) {}

    public record Created(UUID orderId, String publicOrderNumber) {}

    /**
     * Writes the order, its first revision, its lines and one external
     * reference. Runs inside the caller's transaction; the V0038 trigger
     * {@code ordering.assert_marketplace_binding} is the backstop that
     * refuses a binding of the wrong category or the wrong branch, in case
     * the caller's own pre-check (against a stale list) missed it.
     */
    public Created create(Command command) {
        UUID orderId = command.orderId();

        LocalDate businessDate = command.now().atZone(ZoneOffset.UTC).toLocalDate();
        int sequence = jdbc.sql("""
                INSERT INTO ordering.order_number_counters (
                    tenant_id, location_id, business_date, last_value)
                VALUES (:tenantId, :locationId, :businessDate, 1)
                ON CONFLICT (tenant_id, location_id, business_date) DO UPDATE
                SET last_value = ordering.order_number_counters.last_value + 1
                RETURNING last_value
                """)
                .param("tenantId", command.tenantId())
                .param("locationId", command.locationId())
                .param("businessDate", businessDate)
                .query(Integer.class)
                .single();
        String publicOrderNumber = "%03d".formatted(sequence);

        Map<String, Object> row = new HashMap<>();
        row.put("id", orderId);
        row.put("number", publicOrderNumber);
        row.put("tenantId", command.tenantId());
        row.put("brandId", command.brandId());
        row.put("locationId", command.locationId());
        row.put("channelId", command.channelId());
        row.put("channelCode", command.channelCode());
        row.put("currency", command.currency());
        row.put("subtotal", command.subtotalMinor());
        row.put("tax", command.taxMinor());
        row.put("discount", command.discountMinor());
        row.put("fee", command.feeMinor());
        row.put("total", command.totalMinor());
        row.put("idempotencyKey", command.idempotencyKey());
        row.put("bindingId", command.marketplaceBindingId());
        // ADR 0039 attribution: the operator who typed this in, not the
        // aggregator's own machine principal — see the class doc for why
        // that differs from the automated intake's 'PROVIDER'.
        row.put("createdByActorId", command.operatorSubject());
        row.put("createdAt", OffsetDateTime.ofInstant(command.now(), ZoneOffset.UTC));
        // A guest_reference_hash is required whenever no customer_account_id is
        // named (ck_order_owner), and a marketplace order never matches one
        // (see MarketplaceOrderIntake's own doc). varchar(64), so the raw
        // "binding:external id" concatenation does not fit — sha256 hex is
        // exactly 64 characters, the identical shape (and the identical
        // reasoning: this is the aggregator's own order id, not personal
        // data) MarketplaceIngestionService#guestReferenceHash already uses.
        row.put("guestReferenceHash", sha256(command.marketplaceBindingId() + ":" + command.externalOrderId()));
        row.put("fulfillmentMode", command.fulfillmentMode());
        boolean delivery = "DELIVERY".equals(command.fulfillmentMode());
        // Row 1.3g: DELIVERY starts CONFIRMED — see this Command's own doc for
        // why. PICKUP keeps RECEIVED, unchanged. ck_order_confirmed_at requires
        // confirmed_at whenever status is CONFIRMED; this order's own creation
        // instant is that confirmation, there being no separate approval step.
        row.put("status", delivery ? "CONFIRMED" : "RECEIVED");
        row.put("confirmedAt", delivery ? OffsetDateTime.ofInstant(command.now(), ZoneOffset.UTC) : null);

        jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, payment_status_projection,
                    fulfillment_status_projection, currency, subtotal_minor, tax_minor,
                    discount_minor, fee_minor, total_minor, idempotency_key,
                    promise_basis, origin, pricing_authority, fulfillment_authority,
                    entry_mode, marketplace_binding_id, created_by_actor_type,
                    created_by_actor_id, version, created_at, confirmed_at)
                VALUES (
                    :id, :number, :tenantId, :brandId, :locationId, :channelId,
                    :channelCode, :guestReferenceHash, :fulfillmentMode,
                    'AUTO_CONFIRM', 0,
                    'NONE', :status, 'NOT_REQUIRED',
                    'PENDING', :currency, :subtotal, :tax,
                    :discount, :fee, :total, :idempotencyKey,
                    'NOT_PROMISED', 'MARKETPLACE', 'EXTERNAL', 'HORECAOS',
                    'MANUAL', :bindingId, 'USER',
                    :createdByActorId, 1, :createdAt, :confirmedAt)
                """).params(row).update();

        Map<String, Object> revision = new HashMap<>();
        revision.put("orderId", orderId);
        revision.put("tenantId", command.tenantId());
        revision.put("currency", command.currency());
        revision.put("subtotal", command.subtotalMinor());
        revision.put("tax", command.taxMinor());
        revision.put("discount", command.discountMinor());
        revision.put("fee", command.feeMinor());
        revision.put("total", command.totalMinor());
        revision.put("createdByActorId", command.operatorSubject());

        jdbc.sql("""
                INSERT INTO ordering.order_revisions (
                    order_id, revision, tenant_id, source, pricing_quote_id,
                    pricing_context_hash, currency, subtotal_minor, tax_minor,
                    discount_minor, fee_minor, total_minor, delta_total_minor,
                    created_by_actor_type, created_by_actor_id)
                VALUES (
                    :orderId, 1, :tenantId, 'CHECKOUT', NULL,
                    NULL, :currency, :subtotal, :tax,
                    :discount, :fee, :total, 0,
                    'USER', :createdByActorId)
                """).params(revision).update();

        int lineNumber = 1;
        for (Line line : command.lines()) {
            long lineAmount = line.unitAmountMinor() * line.quantity();
            Map<String, Object> lineRow = new HashMap<>();
            lineRow.put("id", UUID.randomUUID());
            lineRow.put("tenantId", command.tenantId());
            lineRow.put("orderId", orderId);
            lineRow.put("lineNumber", lineNumber++);
            lineRow.put("variantId", line.variantId());
            lineRow.put("name", line.nameSnapshot());
            lineRow.put("quantity", line.quantity());
            lineRow.put("unit", line.unitAmountMinor());
            lineRow.put("amount", lineAmount);
            lineRow.put("externalItemReference", line.externalItemReference());
            lineRow.put("mappingStatus", line.variantId() == null ? "UNMAPPED" : "MAPPED");

            jdbc.sql("""
                    INSERT INTO ordering.order_lines (
                        id, tenant_id, order_id, line_number, source_variant_id,
                        product_name_snapshot, quantity, unit_amount_minor,
                        base_amount_minor, final_amount_minor, tax_amount_minor,
                        external_mapping_status, external_item_reference)
                    VALUES (
                        :id, :tenantId, :orderId, :lineNumber, :variantId,
                        :name, :quantity, :unit,
                        :amount, :amount, 0,
                        :mappingStatus, :externalItemReference)
                    """).params(lineRow).update();
        }

        String normalisedReference = JdbcOrderStore.normalisedExternalReference(command.externalOrderId());
        if (normalisedReference != null) {
            Map<String, Object> referenceRow = new HashMap<>();
            referenceRow.put("id", UUID.randomUUID());
            referenceRow.put("tenantId", command.tenantId());
            referenceRow.put("orderId", orderId);
            referenceRow.put("bindingId", command.marketplaceBindingId());
            referenceRow.put("value", command.externalOrderId());
            referenceRow.put("normalised", normalisedReference);

            jdbc.sql("""
                    INSERT INTO ordering.order_external_references (
                        id, tenant_id, order_id, binding_id, reference_type,
                        reference_value, reference_value_normalised, issued_by)
                    VALUES (
                        :id, :tenantId, :orderId, :bindingId, 'PARTNER_ORDER_ID',
                        :value, :normalised, 'HORECAOS')
                    ON CONFLICT (tenant_id, binding_id, reference_type, reference_value_normalised) DO NOTHING
                    """).params(referenceRow).update();
        }

        return new Created(orderId, publicOrderNumber);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
