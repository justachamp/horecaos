package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.instant;
import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.utc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;

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
        params.put(
                "capabilities",
                objectMapper.writeValueAsString(
                        Map.of("providerType", quote.providerType() == null ? "" : quote.providerType())));

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
                """).params(params).update();
    }

    /**
     * One quote by its own id, on the plan it was requested for — the manual
     * external-booking accept/abandon path's lookup (gap map row 1.2f), which
     * re-reads the persisted price rather than trust whatever figure a client
     * echoes back, so an operator can never accept a price the platform did
     * not itself just quote.
     *
     * <p>{@code providerType} comes back out of {@code capability_snapshot}
     * (a Postgres {@code ->>'providerType'} projection) rather than a Jackson
     * round trip: {@link #insert} wrote exactly one key into it, and reading
     * it back through the same path {@link #insertAll} wrote it through would
     * be a second JSON library on a value already sitting in the row.
     */
    public Optional<DeliveryQuote> find(UUID tenantId, UUID planId, UUID quoteId) {
        return jdbc.sql("""
                SELECT id, provider_binding_id, request_id, price_minor, currency,
                       pickup_eta_seconds, delivery_eta_seconds, distance_meters, dead_head_meters,
                       expires_at, quote_validity_source, failure_code, received_at,
                       capability_snapshot ->> 'providerType' AS provider_type
                FROM fulfillment.delivery_quotes
                WHERE tenant_id = :tenantId AND delivery_plan_id = :planId AND id = :quoteId
                """)
                .param("tenantId", tenantId)
                .param("planId", planId)
                .param("quoteId", quoteId)
                .query((row, number) -> new DeliveryQuote(
                        row.getObject("id", UUID.class),
                        row.getObject("provider_binding_id", UUID.class),
                        row.getString("provider_type"),
                        row.getObject("request_id", UUID.class),
                        row.getObject("price_minor", Long.class),
                        row.getString("currency"),
                        row.getObject("pickup_eta_seconds", Integer.class),
                        row.getObject("delivery_eta_seconds", Integer.class),
                        row.getObject("distance_meters", Integer.class),
                        row.getObject("dead_head_meters", Integer.class),
                        instant(row, "expires_at"),
                        "PARTNER".equals(row.getString("quote_validity_source")),
                        row.getString("failure_code"),
                        Objects.requireNonNull(
                                instant(row, "received_at"), "a quote always records when it was received")))
                .optional();
    }
}
