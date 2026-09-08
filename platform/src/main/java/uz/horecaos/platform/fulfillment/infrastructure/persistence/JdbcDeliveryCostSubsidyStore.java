package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.instant;
import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.utc;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.fulfillment.application.SourcingJournal.CostSubsidy;

/**
 * {@code fulfillment.delivery_cost_subsidies} (ADR 0014, V0186).
 *
 * <p>{@code uq_subsidy_one_per_shipment} does the deduplication, not this class:
 * a plan is booked once, so a second write for the same shipment is a replay of
 * the same booking event and is absorbed by {@code ON CONFLICT DO NOTHING}
 * rather than recorded as a second overrun.
 */
@Repository
public class JdbcDeliveryCostSubsidyStore {

    private final JdbcClient jdbc;

    public JdbcDeliveryCostSubsidyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when this call is the one that recorded the subsidy. */
    public boolean record(CostSubsidy subsidy, String recordedBy) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("tenantId", subsidy.tenantId());
        params.put("brandId", subsidy.brandId());
        params.put("locationId", subsidy.locationId());
        params.put("planId", subsidy.planId());
        params.put("shipmentId", subsidy.shipmentId());
        params.put("bindingId", subsidy.providerBindingId());
        params.put("providerType", subsidy.providerType());
        params.put("feeMinor", subsidy.customerDeliveryFeeMinor());
        params.put("costMinor", subsidy.providerCostMinor());
        params.put("subsidyMinor", subsidy.subsidyAmountMinor());
        params.put("currency", subsidy.currency());
        params.put("bearer", subsidy.bearer().name());
        params.put("policyId", subsidy.policyId());
        params.put("policyVersion", subsidy.policyId() == null ? null : subsidy.policyVersion());
        params.put("recognisedAt", utc(subsidy.now()));
        params.put("recordedBy", recordedBy);

        return jdbc.sql("""
                INSERT INTO fulfillment.delivery_cost_subsidies (
                    id, tenant_id, brand_id, location_id, delivery_plan_id, shipment_id,
                    provider_binding_id, provider_type,
                    customer_delivery_fee_minor, provider_cost_minor, subsidy_amount_minor, currency,
                    bearer, policy_id, policy_version, recognised_at, recorded_by)
                VALUES (
                    :id, :tenantId, :brandId, :locationId, :planId, :shipmentId,
                    :bindingId, :providerType,
                    :feeMinor, :costMinor, :subsidyMinor, :currency,
                    :bearer, :policyId, :policyVersion, :recognisedAt, :recordedBy)
                ON CONFLICT (tenant_id, shipment_id) DO NOTHING
                """).params(params).update() == 1;
    }

    /** The subsidy recognised against one shipment, if any. There is at most one, by constraint. */
    public Optional<Row> findByShipment(UUID tenantId, UUID shipmentId) {
        return jdbc.sql("""
                SELECT customer_delivery_fee_minor, provider_cost_minor, subsidy_amount_minor,
                       currency, bearer, recognised_at
                FROM fulfillment.delivery_cost_subsidies
                WHERE tenant_id = :tenantId AND shipment_id = :shipmentId
                """)
                .param("tenantId", tenantId)
                .param("shipmentId", shipmentId)
                .query((row, number) -> new Row(
                        row.getLong("customer_delivery_fee_minor"),
                        row.getLong("provider_cost_minor"),
                        row.getLong("subsidy_amount_minor"),
                        row.getString("currency"),
                        row.getString("bearer"),
                        // recognised_at is NOT NULL: every subsidy is recognised at an instant.
                        Objects.requireNonNull(instant(row, "recognised_at"))))
                .optional();
    }

    public record Row(
            long customerDeliveryFeeMinor,
            long providerCostMinor,
            long subsidyAmountMinor,
            String currency,
            String bearer,
            Instant recognisedAt) {}
}
