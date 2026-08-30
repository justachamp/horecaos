package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;

import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.utc;

/**
 * {@code fulfillment.delivery_quotes} (ADR 0014, V0054).
 *
 * <p>Write-once, and not by convention: V0054 grants this table SELECT and INSERT
 * and nothing else, with a note explaining why. A quote the application can
 * rewrite is not evidence, and evidence is the only reason to keep a partner's
 * price after the booking is made. The consequence is visible here — there is no
 * method that moves a quote to SELECTED, because "selected" is derivable from the
 * winning attempt's {@code quote_id} and does not need a second recording.
 */
@Repository
public class JdbcDeliveryQuoteStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDeliveryQuoteStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Every answer one round of quoting produced, refusals included.
     *
     * <p>Refusals especially. "Why did this order go to the expensive partner" is
     * answered by the row saying the cheap one was out of zone, and a store that
     * kept only the winners could never answer it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on {@code uq_quote_request}: a replayed
     * tick asks under the same request id, and the answer already recorded is the
     * one the selection was made on.
     */
    public void insertAll(UUID tenantId, UUID planId, List<DeliveryQuote> quotes) {
        for (DeliveryQuote quote : quotes) {
            insert(tenantId, planId, quote);
        }
    }

    private void insert(UUID tenantId, UUID planId, DeliveryQuote quote) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", quote.id());
        params.put("tenantId", tenantId);
        params.put("planId", planId);
        params.put("bindingId", quote.bindingId());
        params.put("requestId", quote.requestId());
        params.put("status", quote.status());
        params.put("price", quote.priceMinor());
        params.put("currency", quote.currency());
        params.put("pickupEta", quote.pickupEtaSeconds());
        params.put("deliveryEta", quote.deliveryEtaSeconds());
        params.put("distance", quote.distanceMeters());
        params.put("deadHead", quote.deadHeadMeters());
        params.put("expiresAt", utc(quote.expiresAt()));
        params.put("validitySource", quote.validitySource());
        params.put("failureCode", quote.failureCode());
        params.put("receivedAt", utc(quote.receivedAt()));
        // What the adapter declared at the moment this quote was scored. A
        // selection re-read against today's capability matrix is not the selection
        // that was made.
        params.put("capabilities", objectMapper.writeValueAsString(Map.of(
                "providerType", quote.providerType() == null ? "" : quote.providerType())));

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_quotes (
                    id, tenant_id, delivery_plan_id, provider_binding_id, request_id,
                    status, price_minor, currency, pickup_eta_seconds, delivery_eta_seconds,
                    distance_meters, dead_head_meters, expires_at, quote_validity_source,
                    capability_snapshot, failure_code, received_at)
                VALUES (
                    :id, :tenantId, :planId, :bindingId, :requestId,
                    :status, :price, :currency, :pickupEta, :deliveryEta,
                    :distance, :deadHead, :expiresAt, :validitySource,
                    CAST(:capabilities AS jsonb), :failureCode, :receivedAt)
                ON CONFLICT (provider_binding_id, request_id) DO NOTHING
                """)
                .params(params)
                .update();
    }
}
