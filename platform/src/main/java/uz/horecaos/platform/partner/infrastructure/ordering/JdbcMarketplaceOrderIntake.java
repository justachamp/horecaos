package uz.horecaos.platform.partner.infrastructure.ordering;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.partner.application.port.MarketplaceOrderIntake;
import uz.horecaos.platform.partner.domain.ExternalReference;
import uz.horecaos.platform.partner.domain.ExternalTotals;
import uz.horecaos.platform.partner.domain.HandoverChallengeType;

/**
 * Writes an accepted partner push into {@code ordering.orders} and the tables
 * that hang off it (ADR 0040).
 *
 * <p>The one place in this module that writes another module's schema, and it is
 * kept to one class for that reason. See {@code MarketplaceOrderIntake} for why
 * the write exists at all.
 *
 * <p>Two shapes here differ from an ADR 0019 checkout and both are deliberate.
 *
 * <p>The order carries no quote, no cart, and no context hash. V0038 relaxed
 * those from {@code NOT NULL} to "present exactly when HorecaOS priced it", so this
 * writes nulls rather than fabricating a quote nobody computed. A synthetic
 * quote would be worse than a null in the one way that matters: it would make an
 * aggregator's total look reconstructible in every report that joins to pricing.
 *
 * <p>Revision 1 is written with {@code source = 'CHECKOUT'} because ADR 0039's
 * {@code ck_order_revision_first} defines revision 1 as the original snapshot and
 * nothing else. There was no checkout here; there was a push, and the push is the
 * snapshot. Renaming the source value would be a change to ADR 0039's vocabulary
 * for no gain, so the meaning is recorded here instead.
 */
@Component
public class JdbcMarketplaceOrderIntake implements MarketplaceOrderIntake {

    private final JdbcClient jdbc;

    public JdbcMarketplaceOrderIntake(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Created create(NewMarketplaceOrder order) {
        UUID orderId = order.orderId();
        ExternalTotals totals = order.totals();

        // The counter is per location per business date, as ADR 0019 built it.
        // A marketplace order takes a number from the same sequence as every
        // other order at that branch, because the pass calls one list of numbers
        // out loud and a parallel sequence would produce two order fourteens.
        LocalDate businessDate = order.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
        int sequence = jdbc.sql("""
                INSERT INTO ordering.order_number_counters (
                    tenant_id, location_id, business_date, last_value)
                VALUES (:tenantId, :locationId, :businessDate, 1)
                ON CONFLICT (tenant_id, location_id, business_date) DO UPDATE
                SET last_value = ordering.order_number_counters.last_value + 1
                RETURNING last_value
                """)
                .param("tenantId", order.tenantId())
                .param("locationId", order.locationId())
                .param("businessDate", businessDate)
                .query(Integer.class)
                .single();
        String publicOrderNumber = "%03d".formatted(sequence);

        Map<String, Object> row = new HashMap<>();
        row.put("id", orderId);
        row.put("number", publicOrderNumber);
        row.put("tenantId", order.tenantId());
        row.put("brandId", order.brandId());
        row.put("locationId", order.locationId());
        row.put("channelId", order.channelId());
        row.put("channelCode", order.channelCode());
        row.put("guestReferenceHash", order.guestReferenceHash());
        row.put("fulfillmentMode", order.fulfillmentMode());
        row.put("currency", totals.currency());
        row.put("subtotal", totals.bookedSubtotalMinor());
        row.put("tax", totals.effectiveTaxMinor());
        row.put("discount", totals.discountMinor());
        row.put("fee", totals.feeMinor());
        row.put("total", totals.customerPaidTotalMinor());
        row.put("idempotencyKey", order.idempotencyKey());
        row.put("bindingId", order.bindingId());
        // ADR 0039's attribution columns. The actor is the partner's machine
        // principal, which is exactly what PROVIDER means there, and the id is
        // the binding rather than the client id: a rotated credential must not
        // make a nine-month-old order look as though somebody else entered it.
        row.put("createdByActorId", order.bindingId().toString());
        row.put("createdAt", OffsetDateTime.ofInstant(order.createdAt(), ZoneOffset.UTC));

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
                    created_by_actor_id, version, created_at)
                VALUES (
                    :id, :number, :tenantId, :brandId, :locationId, :channelId,
                    :channelCode, :guestReferenceHash, :fulfillmentMode,
                    'AUTO_CONFIRM', 0,
                    'NONE', 'RECEIVED', 'NOT_REQUIRED',
                    'PENDING', :currency, :subtotal, :tax,
                    :discount, :fee, :total, :idempotencyKey,
                    'NOT_PROMISED', 'MARKETPLACE', 'EXTERNAL', 'PARTNER',
                    'API', :bindingId, 'PROVIDER',
                    :createdByActorId, 1, :createdAt)
                """).params(row).update();

        // The promise is deliberately NOT_PROMISED. The partner made a promise
        // to the customer and HorecaOS did not; copying it into promise columns
        // that ADR 0036 defines as HorecaOS's own derivation would make an
        // aggregator's ETA indistinguishable from a promise this platform is
        // accountable for. The partner's expected pickup time is kept on the
        // staging row, where it reads as what it is.

        Map<String, Object> revision = new HashMap<>();
        revision.put("orderId", orderId);
        revision.put("tenantId", order.tenantId());
        revision.put("currency", totals.currency());
        revision.put("subtotal", totals.bookedSubtotalMinor());
        revision.put("tax", totals.effectiveTaxMinor());
        revision.put("discount", totals.discountMinor());
        revision.put("fee", totals.feeMinor());
        revision.put("total", totals.customerPaidTotalMinor());

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
                    'PROVIDER', 'marketplace')
                """).params(revision).update();

        int lineNumber = 1;
        for (IntakeLine line : order.lines()) {
            Map<String, Object> lineRow = new HashMap<>();
            lineRow.put("id", UUID.randomUUID());
            lineRow.put("tenantId", order.tenantId());
            lineRow.put("orderId", orderId);
            lineRow.put("lineNumber", lineNumber++);
            lineRow.put("productId", line.productId());
            lineRow.put("variantId", line.variantId());
            lineRow.put("name", line.nameSnapshot());
            lineRow.put("quantity", line.quantity());
            lineRow.put("unit", line.unitAmountMinor());
            lineRow.put("amount", line.lineAmountMinor());
            lineRow.put("tax", line.taxAmountMinor());
            lineRow.put("externalItemReference", line.externalItemReference());
            lineRow.put("mappingStatus", line.unmapped() ? "UNMAPPED" : "MAPPED");

            jdbc.sql("""
                    INSERT INTO ordering.order_lines (
                        id, tenant_id, order_id, line_number, source_product_id,
                        source_variant_id, product_name_snapshot, quantity,
                        unit_amount_minor, base_amount_minor, final_amount_minor,
                        tax_amount_minor, revision_from, external_mapping_status,
                        external_item_reference)
                    VALUES (
                        :id, :tenantId, :orderId, :lineNumber, :productId,
                        :variantId, :name, :quantity,
                        :unit, :amount, :amount,
                        :tax, 1, :mappingStatus,
                        :externalItemReference)
                    """).params(lineRow).update();
        }

        Map<String, Object> pricing = new HashMap<>();
        pricing.put("orderId", orderId);
        pricing.put("tenantId", order.tenantId());
        pricing.put("bindingId", order.bindingId());
        pricing.put("currency", totals.currency());
        pricing.put("total", totals.customerPaidTotalMinor());
        pricing.put("subtotal", totals.subtotalMinor());
        pricing.put("discount", totals.discountMinor());
        pricing.put("fee", totals.feeMinor());
        // Null and not zero: the partner stated no tax, which is a different
        // claim from a partner stating tax of zero.
        pricing.put("tax", totals.taxMinor());
        pricing.put("funding", order.discountFunding().name());
        pricing.put("rawTotals", order.rawTotalsJson() == null ? "{}" : order.rawTotalsJson());

        jdbc.sql("""
                INSERT INTO ordering.order_external_pricing (
                    order_id, tenant_id, binding_id, currency, customer_paid_total_minor,
                    external_subtotal_minor, external_discount_minor, external_fee_minor,
                    external_tax_minor, discount_funding, arithmetic_verified, raw_totals)
                VALUES (
                    :orderId, :tenantId, :bindingId, :currency, :total,
                    :subtotal, :discount, :fee,
                    :tax, :funding, true, CAST(:rawTotals AS jsonb))
                """).params(pricing).update();

        for (ExternalReference reference : order.references()) {
            Map<String, Object> referenceRow = new HashMap<>();
            referenceRow.put("id", UUID.randomUUID());
            referenceRow.put("tenantId", order.tenantId());
            referenceRow.put("orderId", orderId);
            referenceRow.put("bindingId", order.bindingId());
            referenceRow.put("type", reference.type().name());
            referenceRow.put("value", reference.value());
            referenceRow.put("normalised", reference.normalisedValue());
            referenceRow.put("issuedBy", reference.issuedBy());

            jdbc.sql("""
                    INSERT INTO ordering.order_external_references (
                        id, tenant_id, order_id, binding_id, reference_type,
                        reference_value, reference_value_normalised, issued_by)
                    VALUES (
                        :id, :tenantId, :orderId, :bindingId, :type,
                        :value, :normalised, :issuedBy)
                    """).params(referenceRow).update();
        }

        UUID challengeId = UUID.randomUUID();
        Map<String, Object> challenge = new HashMap<>();
        challenge.put("id", challengeId);
        challenge.put("tenantId", order.tenantId());
        challenge.put("orderId", orderId);
        challenge.put("bindingId", order.bindingId());
        challenge.put("type", order.challengeType().name());
        challenge.put("issuedBy", order.challengeIssuedBy());
        challenge.put("hash", order.challengeType() == HandoverChallengeType.NONE ? null : order.handoverCodeHash());

        jdbc.sql("""
                INSERT INTO ordering.order_handover_challenges (
                    id, tenant_id, order_id, binding_id, challenge_type, issued_by,
                    expected_value_hash, attempts, max_attempts, status)
                VALUES (
                    :id, :tenantId, :orderId, :bindingId, :type, :issuedBy,
                    :hash, 0, 5, 'PENDING')
                """).params(challenge).update();

        return new Created(orderId, publicOrderNumber, challengeId);
    }
}
