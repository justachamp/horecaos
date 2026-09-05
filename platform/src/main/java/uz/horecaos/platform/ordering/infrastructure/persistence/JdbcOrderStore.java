package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.ordering.api.OrderDirectory.ApprovalDeadlineWarning;
import uz.horecaos.platform.ordering.domain.OrderOutcome;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.PromiseBasis;
import uz.horecaos.platform.ordering.domain.TransitionTrigger;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * Order persistence (ADR 0019).
 *
 * <p>Two rules run through every statement here.
 *
 * <p>The tenant predicate is always inside the query. An order id is a UUID that
 * arrives from a client, and a lookup matching on it alone would serve another
 * tenant's commercial record.
 *
 * <p>Every state change is a conditional UPDATE whose {@code WHERE} clause names
 * the status and version it expects. Nothing here reads a row, decides, and then
 * writes: that pattern is how two operators approving simultaneously both
 * succeed, and how a timeout job rejects an order that was confirmed a
 * millisecond earlier.
 */
@Repository
public class JdbcOrderStore {

    private final JdbcClient jdbc;

    public JdbcOrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Allocates the next human-facing number for a location's business day.
     *
     * <p>One statement. Two checkouts at one branch in the same instant contend on
     * the primary key and receive consecutive numbers; there is no read-then-write
     * window in which both could see the same last value.
     */
    public int nextOrderNumber(UUID tenantId, UUID locationId, LocalDate businessDate) {
        return jdbc.sql("""
                INSERT INTO ordering.order_number_counters (
                    tenant_id, location_id, business_date, last_value)
                VALUES (:tenantId, :locationId, :businessDate, 1)
                ON CONFLICT (tenant_id, location_id, business_date) DO UPDATE
                SET last_value = ordering.order_number_counters.last_value + 1
                RETURNING last_value
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("businessDate", businessDate)
                .query(Integer.class)
                .single();
    }

    public void insertOrder(NewOrder order) {
        jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, customer_account_id, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, approval_deadline_at, status,
                    payment_status_projection, fulfillment_status_projection, currency,
                    subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, promised_at, promise_basis, promise_prep_minutes,
                    promise_travel_minutes, created_by_actor_type, created_by_actor_id,
                    version, created_at)
                VALUES (
                    :id, :number, :tenantId, :brandId, :locationId, :channelId,
                    :channelCode, :customerId, :guestHash,
                    :mode, :acceptanceMode, :policyId,
                    :policyVersion, :approvalChannel,
                    :timeoutAction, :approvalDeadline, :status,
                    :paymentProjection, :fulfillmentProjection, :currency,
                    :subtotal, :tax, :discount, :fee, :total,
                    :quoteId, :contextHash, :publicationId, :cartId,
                    :idempotencyKey, :promisedAt, :promiseBasis, :promisePrepMinutes,
                    :promiseTravelMinutes, :createdByActorType, :createdByActorId,
                    1, :now)
                """)
                .param("id", order.orderId())
                .param("number", order.publicOrderNumber())
                .param("tenantId", order.tenantId())
                .param("brandId", order.brandId())
                .param("locationId", order.locationId())
                .param("channelId", order.channelId())
                .param("channelCode", order.channelCode())
                .param("customerId", order.customerAccountId())
                .param("guestHash", order.guestReferenceHash())
                .param("mode", order.fulfillmentMode().name())
                .param("acceptanceMode", order.acceptanceMode())
                .param("policyId", order.acceptancePolicyId())
                .param("policyVersion", order.acceptancePolicyVersion())
                .param("approvalChannel", order.approvalChannel())
                .param("timeoutAction", order.approvalTimeoutAction())
                .param("approvalDeadline", order.approvalDeadlineAt() == null ? null : utc(order.approvalDeadlineAt()))
                .param("status", order.status().name())
                .param("paymentProjection", order.paymentStatusProjection())
                .param("fulfillmentProjection", order.fulfillmentStatusProjection())
                .param("currency", order.currency())
                .param("subtotal", order.subtotalMinor())
                .param("tax", order.taxMinor())
                .param("discount", order.discountMinor())
                .param("fee", order.feeMinor())
                .param("total", order.totalMinor())
                .param("quoteId", order.pricingQuoteId())
                .param("contextHash", order.pricingContextHash())
                .param("publicationId", order.catalogPublicationId())
                .param("cartId", order.cartId())
                .param("idempotencyKey", order.idempotencyKey())
                .param(
                        "promisedAt",
                        order.promise().promisedAt() == null
                                ? null
                                : utc(order.promise().promisedAt()))
                .param("promiseBasis", order.promise().basis().name())
                .param("promisePrepMinutes", order.promise().prepMinutes())
                .param("promiseTravelMinutes", order.promise().travelMinutes())
                // ADR 0039: who entered the order, written once. A storefront
                // checkout records the customer; an operator taking it on the
                // phone records themselves, and the two are never the same fact.
                .param("createdByActorType", order.createdByActorType())
                .param("createdByActorId", order.createdByActorId())
                .param("now", utc(order.createdAt()))
                .update();
    }

    /**
     * Appends one revision (ADR 0039).
     *
     * <p>Append-only, and the table holds no UPDATE grant. Revision 1 is the
     * checkout snapshot and a database CHECK states that equivalence in both
     * directions, so neither a second CHECKOUT revision nor an AMENDMENT numbered
     * 1 can be written.
     */
    public void insertRevision(NewRevision revision) {
        jdbc.sql("""
                INSERT INTO ordering.order_revisions (
                    order_id, revision, tenant_id, source, amendment_id, pricing_quote_id,
                    pricing_context_hash, currency, subtotal_minor, tax_minor, discount_minor,
                    fee_minor, total_minor, delta_total_minor, created_by_actor_type,
                    created_by_actor_id, created_at)
                VALUES (:orderId, :revision, :tenantId, :source, :amendmentId, :quoteId,
                    :contextHash, :currency, :subtotal, :tax, :discount,
                    :fee, :total, :delta, :actorType,
                    :actorId, :now)
                """)
                .param("orderId", revision.orderId())
                .param("revision", revision.revision())
                .param("tenantId", revision.tenantId())
                .param("source", revision.source())
                .param("amendmentId", revision.amendmentId())
                .param("quoteId", revision.pricingQuoteId())
                .param("contextHash", revision.pricingContextHash())
                .param("currency", revision.currency())
                .param("subtotal", revision.subtotalMinor())
                .param("tax", revision.taxMinor())
                .param("discount", revision.discountMinor())
                .param("fee", revision.feeMinor())
                .param("total", revision.totalMinor())
                .param("delta", revision.deltaTotalMinor())
                .param("actorType", revision.createdByActorType())
                .param("actorId", revision.createdByActorId())
                .param("now", utc(revision.createdAt()))
                .update();
    }

    public List<RevisionRow> revisions(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT order_id, revision, source, amendment_id, pricing_quote_id,
                       pricing_context_hash, currency, subtotal_minor, tax_minor, discount_minor,
                       fee_minor, total_minor, delta_total_minor, created_by_actor_type,
                       created_by_actor_id, created_at
                FROM ordering.order_revisions
                WHERE tenant_id = :tenantId AND order_id = :orderId
                ORDER BY revision
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new RevisionRow(
                        row.getObject("order_id", UUID.class),
                        row.getInt("revision"),
                        row.getString("source"),
                        row.getObject("amendment_id", UUID.class),
                        row.getObject("pricing_quote_id", UUID.class),
                        row.getString("pricing_context_hash"),
                        row.getString("currency"),
                        row.getLong("subtotal_minor"),
                        row.getLong("tax_minor"),
                        row.getLong("discount_minor"),
                        row.getLong("fee_minor"),
                        row.getLong("total_minor"),
                        row.getLong("delta_total_minor"),
                        row.getString("created_by_actor_type"),
                        row.getString("created_by_actor_id"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Moves the order to a new revision and applies an amendment's field changes.
     *
     * <p>One conditional UPDATE naming the version it expects, so two operators
     * applying at the same instant settle at one revision rather than two. A null
     * in the patch means "leave this alone"; the {@code CASE} is what keeps a
     * command that sets the kitchen note from also blanking the change-due figure
     * an earlier one recorded.
     *
     * @return the new order version when this caller won, or empty when it lost
     */
    public Optional<Integer> applyRevision(
            UUID tenantId,
            UUID orderId,
            int expectedVersion,
            int newRevision,
            OrderFieldPatch patch,
            String resolvedBy,
            Instant now) {

        // A HashMap rather than Map.of, because every value in the patch may
        // legitimately be null and Map.of refuses one.
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId);
        params.put("expectedVersion", expectedVersion);
        params.put("revision", newRevision);
        params.put("setKitchenNote", patch.kitchenNote() != null);
        params.put("kitchenNote", patch.kitchenNote());
        params.put("setCallback", patch.callbackRequested() != null);
        params.put("callbackRequested", patch.callbackRequested() != null && patch.callbackRequested());
        params.put("resolvedBy", resolvedBy);
        params.put("setCash", patch.cashTenderedExpectedMinor() != null);
        params.put("cashTendered", patch.cashTenderedExpectedMinor());
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE ordering.orders
                SET current_revision = :revision,
                    version = version + 1,
                    kitchen_note = CASE WHEN :setKitchenNote
                        THEN :kitchenNote::varchar ELSE kitchen_note END,
                    callback_requested = CASE WHEN :setCallback
                        THEN :callbackRequested ELSE callback_requested END,
                    -- A callback raised again is new work, so raising it clears
                    -- the previous resolution rather than leaving a row that
                    -- claims to be both open and settled.
                    callback_resolved_at = CASE WHEN :setCallback
                        THEN (CASE WHEN :callbackRequested THEN NULL ELSE :now END)
                        ELSE callback_resolved_at END,
                    callback_resolved_by = CASE WHEN :setCallback
                        THEN (CASE WHEN :callbackRequested THEN NULL ELSE :resolvedBy::varchar END)
                        ELSE callback_resolved_by END,
                    cash_tendered_expected_minor = CASE WHEN :setCash
                        THEN :cashTendered::bigint ELSE cash_tendered_expected_minor END
                WHERE tenant_id = :tenantId AND id = :orderId AND version = :expectedVersion
                RETURNING version
                """).params(params).query(Integer.class).optional();
    }

    /**
     * ADR 0064: attaching the voice call this order originated from.
     *
     * <p>Write-once by the {@code source_call_id IS NULL} predicate — a call id
     * is a fact about how the order began, not a field an order can be
     * reassigned to later. The caller (see {@code OrderCallProvenanceService})
     * treats zero rows updated as either "already this value" (a harmless
     * retry) or "already a different value" (a conflict), by reading {@link
     * #find} again rather than this method guessing which.
     */
    public boolean recordCallProvenance(UUID tenantId, UUID orderId, UUID callId) {
        return jdbc.sql("""
                UPDATE ordering.orders
                SET source_call_id = :callId, version = version + 1
                WHERE tenant_id = :tenantId AND id = :orderId AND source_call_id IS NULL
                """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .param("callId", callId)
                        .update()
                == 1;
    }

    /** Whether this order's call provenance is already exactly this value — the "harmless retry" case. */
    public boolean hasCallProvenance(UUID tenantId, UUID orderId, UUID callId) {
        Integer count = jdbc.sql("""
                SELECT count(*) FROM ordering.orders
                WHERE tenant_id = :tenantId AND id = :orderId AND source_call_id = :callId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("callId", callId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    // ---------------------------------------------------------- terminal outcome

    /**
     * Records the one terminal outcome (ADR 0039).
     *
     * <p>Written in the same transaction as the transition it explains. The table
     * is primary-keyed on the order, so a second attempt fails rather than
     * producing an order with two contradictory endings.
     */
    public void insertOutcome(
            UUID tenantId,
            UUID orderId,
            OrderOutcome outcome,
            String actorType,
            @Nullable String actorId,
            Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO ordering.order_outcomes (
                    order_id, tenant_id, kind, system_category, reason_id, reason_version,
                    reason_snapshot, actor_type, actor_id, stock_disposition, liability_party,
                    customer_refund, reservation_committed, note_encrypted, occurred_at)
                VALUES (:orderId, :tenantId, :kind, :category, :reasonId, :reasonVersion,
                    :snapshot::jsonb, :actorType, :actorId, :disposition, :liability,
                    :refund, :committed, :note, :occurredAt)
                """)
                .param("orderId", orderId)
                .param("tenantId", tenantId)
                .param("kind", outcome.kind().name())
                .param("category", outcome.systemCategory().name())
                .param("reasonId", outcome.reasonId())
                .param("reasonVersion", outcome.reasonVersion())
                .param("snapshot", outcome.reasonSnapshot())
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("disposition", outcome.disposition().name())
                .param(
                        "liability",
                        outcome.liabilityParty() == null
                                ? null
                                : outcome.liabilityParty().name())
                .param(
                        "refund",
                        outcome.customerRefund() == null
                                ? null
                                : outcome.customerRefund().name())
                .param("committed", outcome.reservationCommitted())
                .param("note", outcome.noteEncrypted())
                .param("occurredAt", utc(occurredAt))
                .update();
    }

    public Optional<OutcomeRow> findOutcome(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT kind, system_category, reason_id, reason_version, reason_snapshot::text
                           AS reason_snapshot,
                       actor_type, actor_id, stock_disposition, liability_party, customer_refund,
                       reservation_committed, inventory_movement_id, refund_id, occurred_at
                FROM ordering.order_outcomes
                WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new OutcomeRow(
                        row.getString("kind"),
                        row.getString("system_category"),
                        row.getObject("reason_id", UUID.class),
                        // getInt answers 0 for SQL NULL, and version 0 does not
                        // exist: a reason nobody cited would read as one cited at
                        // a version that was never published.
                        row.getObject("reason_version", Integer.class),
                        row.getString("reason_snapshot"),
                        row.getString("actor_type"),
                        row.getString("actor_id"),
                        row.getString("stock_disposition"),
                        row.getString("liability_party"),
                        row.getString("customer_refund"),
                        row.getBoolean("reservation_committed"),
                        row.getObject("inventory_movement_id", UUID.class),
                        row.getObject("refund_id", UUID.class),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Inserts one snapshotted line.
     *
     * <p>The line id is supplied rather than generated here, because the ADR 0029
     * associated data binds the note's ciphertext to the row it belongs to: the id
     * has to exist before the note can be encrypted for it.
     */
    public void insertLine(
            UUID lineId,
            UUID tenantId,
            UUID orderId,
            int lineNumber,
            @Nullable UUID sourceProductId,
            UUID sourceVariantId,
            String productName,
            @Nullable String variantName,
            @Nullable String sku,
            int quantity,
            long unitMinor,
            long baseMinor,
            long finalMinor,
            long taxMinor,
            @Nullable String noteEncrypted) {
        jdbc.sql("""
                INSERT INTO ordering.order_lines (
                    id, tenant_id, order_id, line_number, source_product_id, source_variant_id,
                    product_name_snapshot, variant_name_snapshot, sku_snapshot, quantity,
                    unit_amount_minor, base_amount_minor, final_amount_minor, tax_amount_minor,
                    note_encrypted)
                VALUES (:id, :tenantId, :orderId, :lineNumber, :productId, :variantId,
                    :productName, :variantName, :sku, :quantity,
                    :unit, :base, :finalAmount, :tax, :note)
                """)
                .param("id", lineId)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("lineNumber", lineNumber)
                .param("productId", sourceProductId)
                .param("variantId", sourceVariantId)
                .param("productName", productName)
                .param("variantName", variantName)
                .param("sku", sku)
                .param("quantity", quantity)
                .param("unit", unitMinor)
                .param("base", baseMinor)
                .param("finalAmount", finalMinor)
                .param("tax", taxMinor)
                .param("note", noteEncrypted)
                .update();
    }

    public void insertLineModifier(
            UUID tenantId,
            UUID orderLineId,
            @Nullable UUID sourceGroupId,
            UUID sourceOptionId,
            @Nullable String groupName,
            String optionName,
            int quantity,
            long unitMinor,
            long finalMinor) {
        jdbc.sql("""
                INSERT INTO ordering.order_line_modifiers (
                    id, tenant_id, order_line_id, source_group_id, source_option_id,
                    group_name_snapshot, option_name_snapshot, quantity,
                    unit_amount_minor, final_amount_minor)
                VALUES (:id, :tenantId, :lineId, :groupId, :optionId,
                    :groupName, :optionName, :quantity, :unit, :finalAmount)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("lineId", orderLineId)
                .param("groupId", sourceGroupId)
                .param("optionId", sourceOptionId)
                .param("groupName", groupName)
                .param("optionName", optionName)
                .param("quantity", quantity)
                .param("unit", unitMinor)
                .param("finalAmount", finalMinor)
                .update();
    }

    public void insertAdjustment(
            UUID tenantId,
            UUID orderId,
            int sequence,
            @Nullable UUID orderLineId,
            String adjustmentType,
            String sourceType,
            UUID sourceId,
            @Nullable Integer sourceVersion,
            String descriptionCode,
            long amountMinor) {
        jdbc.sql("""
                INSERT INTO ordering.order_adjustments (
                    order_id, sequence, tenant_id, order_line_id, adjustment_type,
                    source_type, source_id, source_version, description_code, amount_minor)
                VALUES (:orderId, :sequence, :tenantId, :lineId, :type,
                    :sourceType, :sourceId, :sourceVersion, :code, :amount)
                """)
                .param("orderId", orderId)
                .param("sequence", sequence)
                .param("tenantId", tenantId)
                .param("lineId", orderLineId)
                .param("type", adjustmentType)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("sourceVersion", sourceVersion)
                .param("code", descriptionCode)
                .param("amount", amountMinor)
                .update();
    }

    public void insertCustomerSnapshot(
            UUID tenantId,
            UUID orderId,
            @Nullable String displayName,
            @Nullable String contact,
            @Nullable String address,
            @Nullable String instructions,
            boolean transactionalAllowed) {
        jdbc.sql("""
                INSERT INTO ordering.order_customer_snapshots (
                    order_id, tenant_id, display_name_encrypted, contact_encrypted,
                    address_encrypted, delivery_instructions_encrypted,
                    transactional_contact_allowed)
                VALUES (:orderId, :tenantId, :displayName, :contact, :address,
                    :instructions, :allowed)
                """)
                .param("orderId", orderId)
                .param("tenantId", tenantId)
                .param("displayName", displayName)
                .param("contact", contact)
                .param("address", address)
                .param("instructions", instructions)
                .param("allowed", transactionalAllowed)
                .update();
    }

    /**
     * The order's customer snapshot, ciphertext and all (ADR 0029).
     *
     * <p>Every personal column comes back exactly as stored. Nothing here
     * decrypts: {@link uz.horecaos.platform.ordering.application.OrderQueryService}
     * masks the phone for an ordinary detail read and defers a full decrypt to
     * the capability-gated reveal calls, mirroring how {@link #lineNote} hands
     * back ciphertext for the one endpoint entitled to open it.
     */
    public Optional<CustomerSnapshotRow> customerSnapshot(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT order_id, display_name_encrypted, contact_encrypted, address_encrypted,
                       delivery_instructions_encrypted, transactional_contact_allowed, anonymized_at
                FROM ordering.order_customer_snapshots
                WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new CustomerSnapshotRow(
                        row.getObject("order_id", UUID.class),
                        row.getString("display_name_encrypted"),
                        row.getString("contact_encrypted"),
                        row.getString("address_encrypted"),
                        row.getString("delivery_instructions_encrypted"),
                        row.getBoolean("transactional_contact_allowed"),
                        instantOrNull(row, "anonymized_at")))
                .optional();
    }

    /**
     * The board's tab badges, one aggregate (orders.md §2.3).
     *
     * <p>Scoped identically to {@link #listForLocation} and computed in one pass
     * over the location's orders rather than one query per tab, so the board's
     * header never costs seven round trips. {@code Внимание}'s live severity
     * queue (late orders, stuck processes) is deliberately not among these
     * columns — orders.md §2.7 is explicit that lateness "needs no column, no
     * job and no event" because it is derived from the promise and the clock at
     * render time, and a count computed here would be wrong five seconds after
     * it was cached.
     *
     * @param locationId scoped to one location when given; every location in
     *                   the brand when null, for a flat operations group with
     *                   no single location to ask about (ADR 0058)
     */
    public OrderCountsRow counts(UUID tenantId, UUID brandId, @Nullable UUID locationId) {
        return jdbc.sql("""
                SELECT
                    count(*) FILTER (WHERE status IN ('RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL'))
                        AS new_orders,
                    count(*) FILTER (WHERE status = 'AWAITING_APPROVAL') AS awaiting_approval,
                    count(*) FILTER (WHERE status IN ('CONFIRMED', 'PREPARING')) AS in_kitchen,
                    count(*) FILTER (WHERE status = 'READY') AS ready,
                    count(*) FILTER (WHERE status = 'FULFILLING') AS fulfilling,
                    count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                    count(*) FILTER (WHERE status IN ('CANCELLED', 'REJECTED', 'EXPIRED')) AS cancelled,
                    count(*) FILTER (WHERE status NOT IN
                        ('PAYMENT_FAILED', 'REJECTED', 'EXPIRED', 'COMPLETED', 'CANCELLED')) AS total_non_terminal,
                    count(*) AS total
                FROM ordering.orders
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND (:locationId::uuid IS NULL OR location_id = :locationId)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query((row, number) -> new OrderCountsRow(
                        row.getLong("new_orders"),
                        row.getLong("awaiting_approval"),
                        row.getLong("in_kitchen"),
                        row.getLong("ready"),
                        row.getLong("fulfilling"),
                        row.getLong("completed"),
                        row.getLong("cancelled"),
                        row.getLong("total_non_terminal"),
                        row.getLong("total")))
                .single();
    }

    /**
     * Distinct customer accounts with at least one order inside {@code [from,
     * to)}, tenant-wide (not brand- or location-scoped: the customer grid this
     * serves is a tenant surface, the same scope {@code customer.customer_accounts}
     * itself lives at). Guest orders — {@code customer_account_id IS NULL} —
     * are excluded by the join predicate itself, never counted as a distinct
     * null.
     */
    public long countDistinctCustomersBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT count(DISTINCT customer_account_id) FROM ordering.orders
                WHERE tenant_id = :tenantId AND customer_account_id IS NOT NULL
                  AND created_at >= :from AND created_at < :to
                """)
                .param("tenantId", tenantId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Long.class)
                .single();
    }

    public Optional<OrderRow> find(UUID tenantId, UUID orderId) {
        return jdbc.sql(SELECT_ORDER + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", orderId)
                .query(JdbcOrderStore::mapOrder)
                .optional();
    }

    public Optional<OrderRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SELECT_ORDER + " WHERE tenant_id = :tenantId AND idempotency_key = :key")
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query(JdbcOrderStore::mapOrder)
                .optional();
    }

    /**
     * Mirrors a payment lifecycle fact onto {@code payment_status_projection}
     * (V0022): "always written from those aggregates' events rather than decided
     * here". This is that write — the one V0022's own comment promised and that
     * nothing, until now, ever performed.
     *
     * <p>A plain {@code SET}, not a conditional UPDATE naming an expected prior
     * value. Nothing about this column drives an order-state transition, so there
     * is no race to lose: whichever payment fact arrives last is the projection's
     * truth, exactly as it is the payment aggregate's, and applying the same fact
     * twice converges on the same value rather than erroring or double-applying.
     *
     * <p>{@code NOT_REQUIRED} is excluded from the {@code WHERE} clause on
     * purpose, which is the one guard this method does apply. It is not "no
     * payment fact has arrived yet" — it is checkout's own declaration that no
     * online payment will ever be tracked for this order (cash, or an unwired
     * payments port), made once, and a payment lifecycle signal must not
     * overwrite it. A cash order that is later refunded through {@code
     * OrderRemedyService} stays {@code NOT_REQUIRED}: the remedy is real and is
     * recorded in {@code payments.order_remedies}, but this column was never
     * tracking that order's money and does not start now.
     *
     * @param projection one of the CHECK constraint's values other than {@code
     *                    NOT_REQUIRED} — {@code CAPTURED}, {@code FAILED},
     *                    {@code VOIDED} or {@code REFUNDED} today
     * @return whether a row was actually changed — false for an unknown order, a
     *         cross-tenant id, or a {@code NOT_REQUIRED} order, all three of
     *         which are quiet no-ops rather than exceptions, matching every other
     *         inbound payment signal this store answers
     */
    public boolean updatePaymentProjection(UUID tenantId, UUID orderId, String projection) {
        int updated = jdbc.sql("""
                        UPDATE ordering.orders
                           SET payment_status_projection = :projection
                         WHERE tenant_id = :tenantId AND id = :id
                           AND payment_status_projection <> 'NOT_REQUIRED'
                        """)
                .param("projection", projection)
                .param("tenantId", tenantId)
                .param("id", orderId)
                .update();
        return updated > 0;
    }

    /** The operations list for one location, newest first. */
    public List<OrderRow> listForLocation(
            UUID tenantId, UUID brandId, UUID locationId, List<String> statuses, int limit) {
        return jdbc.sql(SELECT_ORDER + """
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND location_id = :locationId
                   AND (:statusFilterEmpty OR status = ANY(:statuses))
                 ORDER BY created_at DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("statusFilterEmpty", statuses.isEmpty())
                .param("statuses", statuses.toArray(String[]::new))
                .param("limit", limit)
                .query(JdbcOrderStore::mapOrder)
                .list();
    }

    /**
     * A page of one customer's own orders at one brand, newest first (ADR 0031).
     *
     * <p>Projects twelve columns rather than reading an {@link OrderRow}. An order
     * row carries the acceptance policy it was judged under, the approval channel
     * and deadline, the actor ids that entered and accepted it, the quote hash, the
     * catalog publication, the cash expected at the door and the kitchen note —
     * none of which a list of a customer's own orders needs, and several of which
     * describe how the restaurant works rather than what the customer bought. A
     * list endpoint that returns the whole aggregate is how a field nobody meant to
     * publish becomes part of the contract.
     *
     * <p>Keyset on {@code (created_at, id)} and never an offset: a customer paging
     * their history while placing an order would otherwise see one order twice and
     * miss another. The id breaks the tie, because two orders placed in the same
     * microsecond are ordinary at a busy branch and a non-unique sort key silently
     * skips rows.
     *
     * <p>{@code ix_orders_customer} — {@code (tenant_id, customer_account_id,
     * created_at DESC)} where the account is not null — already provides both the
     * predicate and the order. The brand is a filter over that, which matters only
     * under {@code TENANT_SHARED}, where one account spans a tenant's brands; the
     * rows it discards are bounded by one customer's own order count.
     *
     * @param beforeCreatedAt the previous page's last order's instant, or null to
     *                        start at the newest
     */
    public List<CustomerOrderRow> listForCustomer(
            UUID tenantId,
            UUID brandId,
            UUID accountId,
            @Nullable Instant beforeCreatedAt,
            @Nullable UUID beforeId,
            int limit) {
        return jdbc.sql("""
                SELECT id, public_order_number, location_id, fulfillment_mode, status,
                       payment_status_projection, fulfillment_status_projection, currency,
                       total_minor, promised_at, version, created_at
                FROM ordering.orders
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND customer_account_id = :accountId
                  AND (:unbounded
                       OR (created_at, id)
                          < (CAST(:beforeCreatedAt AS timestamptz), CAST(:beforeId AS uuid)))
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .param("unbounded", beforeCreatedAt == null)
                // Cast in the statement rather than typed here, so the null a first
                // page sends is a typed null the row comparison can be planned
                // against instead of an untyped one the driver has to guess at.
                .param(
                        "beforeCreatedAt",
                        beforeCreatedAt == null ? null : OffsetDateTime.ofInstant(beforeCreatedAt, ZoneOffset.UTC))
                .param("beforeId", beforeId == null ? null : beforeId.toString())
                .param("limit", limit)
                .query((row, number) -> new CustomerOrderRow(
                        row.getObject("id", UUID.class),
                        row.getString("public_order_number"),
                        row.getObject("location_id", UUID.class),
                        FulfillmentMode.valueOf(row.getString("fulfillment_mode")),
                        OrderStatus.valueOf(row.getString("status")),
                        row.getString("payment_status_projection"),
                        row.getString("fulfillment_status_projection"),
                        row.getString("currency"),
                        row.getLong("total_minor"),
                        instantOrNull(row, "promised_at"),
                        row.getInt("version"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Where a customer's cursor points, resolved inside their own scope.
     *
     * <p>The cursor is an order id the caller received in the page it is
     * continuing, and this read applies the same tenant, brand and account
     * predicates the page itself does. That is the whole of its safety: a cursor
     * naming a stranger's order resolves to nothing rather than moving the window
     * into their history, and the caller is told the cursor is unusable in exactly
     * the same words as one that was never an order at all.
     */
    public Optional<Instant> customerOrderCursor(UUID tenantId, UUID brandId, UUID accountId, UUID orderId) {
        return jdbc.sql("""
                SELECT created_at FROM ordering.orders
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND customer_account_id = :accountId AND id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .param("orderId", orderId)
                .query((row, number) ->
                        row.getObject("created_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    /**
     * Moves an order from one status to another, and only from that status.
     *
     * <p>This single statement is where every order race is settled. Two operators
     * deciding at once, a timeout firing against an order just confirmed, and a
     * duplicate command replayed from Kafka all reduce to "did my UPDATE affect a
     * row". The loser is told what actually happened rather than being allowed to
     * apply its own outcome on top.
     *
     * @return the new version when this caller won, or empty when it lost
     */
    public Optional<Integer> transition(UUID tenantId, UUID orderId, OrderStatus from, OrderStatus to, Instant now) {
        return transition(tenantId, orderId, from, to, now, null, null);
    }

    /**
     * The same transition, additionally recording who accepted the order.
     *
     * <p>ADR 0039: {@code accepted_by} is who moved it to {@code CONFIRMED}, which
     * is a different act from entering it and frequently a different person. It is
     * written in the transition itself rather than by a following UPDATE, because
     * a second statement could commit without the first or the other way round,
     * and an order confirmed by nobody is exactly the gap the column exists to
     * close. A trigger refuses any later rewrite; the {@code accepted_at IS NULL}
     * guard here means the write is also idempotent against a replay.
     */
    public Optional<Integer> transition(
            UUID tenantId,
            UUID orderId,
            OrderStatus from,
            OrderStatus to,
            Instant now,
            @Nullable String acceptedByActorType,
            @Nullable String acceptedByActorId) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("id", orderId);
        params.put("from", from.name());
        params.put("to", to.name());
        params.put("terminal", to.terminal());
        params.put("acceptedByType", acceptedByActorType);
        params.put("acceptedById", acceptedByActorId);
        params.put("now", utc(now));

        return jdbc.sql("""
                UPDATE ordering.orders
                SET status = :to,
                    version = version + 1,
                    confirmed_at = CASE WHEN :to = 'CONFIRMED' THEN :now ELSE confirmed_at END,
                    accepted_at = CASE
                        WHEN :to = 'CONFIRMED' AND accepted_at IS NULL
                             AND :acceptedByType::varchar IS NOT NULL
                        THEN :now ELSE accepted_at END,
                    accepted_by_actor_type = CASE
                        WHEN :to = 'CONFIRMED' AND accepted_at IS NULL
                        THEN :acceptedByType::varchar ELSE accepted_by_actor_type END,
                    accepted_by_actor_id = CASE
                        WHEN :to = 'CONFIRMED' AND accepted_at IS NULL
                        THEN :acceptedById::varchar ELSE accepted_by_actor_id END,
                    closed_at = CASE WHEN :terminal THEN :now ELSE closed_at END
                WHERE tenant_id = :tenantId AND id = :id AND status = :from
                RETURNING version
                """).params(params).query(Integer.class).optional();
    }

    /** Records a transition that already happened. Append-only; never updated. */
    public void recordTransition(
            UUID tenantId,
            UUID orderId,
            int sequenceNumber,
            @Nullable OrderStatus from,
            OrderStatus to,
            TransitionTrigger trigger,
            @Nullable String reasonCode,
            String actorType,
            @Nullable String actorId,
            @Nullable String correlationId,
            Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO ordering.order_state_history (
                    id, tenant_id, order_id, sequence_number, from_status, to_status,
                    trigger, reason_code, actor_type, actor_id, correlation_id, occurred_at)
                VALUES (:id, :tenantId, :orderId, :sequence, :from, :to,
                    :trigger, :reason, :actorType, :actorId, :correlationId, :occurredAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("sequence", sequenceNumber)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("trigger", trigger.name())
                .param("reason", reasonCode)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("correlationId", correlationId)
                .param("occurredAt", utc(occurredAt))
                .update();
    }

    public List<TransitionRow> history(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT sequence_number, from_status, to_status, trigger, reason_code,
                       actor_type, actor_id, correlation_id, occurred_at
                FROM ordering.order_state_history
                WHERE tenant_id = :tenantId AND order_id = :orderId
                ORDER BY sequence_number
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new TransitionRow(
                        row.getInt("sequence_number"),
                        row.getString("from_status"),
                        row.getString("to_status"),
                        row.getString("trigger"),
                        row.getString("reason_code"),
                        row.getString("actor_type"),
                        row.getString("actor_id"),
                        row.getString("correlation_id"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** The lines as they stand now: those no revision has closed. */
    public List<OrderLineRow> lines(UUID tenantId, UUID orderId) {
        return lines(tenantId, orderId, null);
    }

    /**
     * The lines as they were at one revision (ADR 0039).
     *
     * <p>ADR 0039's own negative consequence names this as the trap: a report
     * joining order lines without pinning a revision double-counts, and the
     * mistake stays invisible until somebody reconciles a total by hand. The
     * predicate is written here once so no caller has to remember it.
     *
     * @param revision the revision to read at, or null for the live set
     */
    public List<OrderLineRow> lines(UUID tenantId, UUID orderId, @Nullable Integer revision) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId);
        params.put("revision", revision);

        return jdbc.sql("""
                SELECT id, line_number, source_product_id, source_variant_id,
                       product_name_snapshot, variant_name_snapshot, sku_snapshot, quantity,
                       unit_amount_minor, base_amount_minor, final_amount_minor, tax_amount_minor,
                       note_encrypted
                FROM ordering.order_lines
                WHERE tenant_id = :tenantId AND order_id = :orderId
                  AND (:revision::integer IS NULL
                       OR (revision_from <= :revision::integer
                           AND (revision_to IS NULL OR revision_to > :revision::integer)))
                  AND (:revision::integer IS NOT NULL OR revision_to IS NULL)
                ORDER BY line_number
                """)
                .params(params)
                .query((row, number) -> new OrderLineRow(
                        row.getObject("id", UUID.class),
                        row.getInt("line_number"),
                        row.getObject("source_product_id", UUID.class),
                        row.getObject("source_variant_id", UUID.class),
                        row.getString("product_name_snapshot"),
                        row.getString("variant_name_snapshot"),
                        row.getString("sku_snapshot"),
                        row.getInt("quantity"),
                        row.getLong("unit_amount_minor"),
                        row.getLong("base_amount_minor"),
                        row.getLong("final_amount_minor"),
                        row.getLong("tax_amount_minor"),
                        row.getString("note_encrypted")))
                .list();
    }

    /** The stored ciphertext of one line's note, for the one endpoint that may reveal it. */
    public Optional<String> lineNote(UUID tenantId, UUID orderId, UUID lineId) {
        return jdbc.sql("""
                SELECT note_encrypted FROM ordering.order_lines
                WHERE tenant_id = :tenantId AND order_id = :orderId AND id = :lineId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("lineId", lineId)
                .query(String.class)
                .optional();
    }

    public List<OrderModifierRow> lineModifiers(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT m.order_line_id, m.source_group_id, m.source_option_id,
                       m.group_name_snapshot, m.option_name_snapshot, m.quantity,
                       m.unit_amount_minor, m.final_amount_minor
                FROM ordering.order_line_modifiers m
                JOIN ordering.order_lines l ON l.id = m.order_line_id AND l.tenant_id = m.tenant_id
                WHERE m.tenant_id = :tenantId AND l.order_id = :orderId
                ORDER BY l.line_number, m.option_name_snapshot
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new OrderModifierRow(
                        row.getObject("order_line_id", UUID.class),
                        row.getObject("source_group_id", UUID.class),
                        row.getObject("source_option_id", UUID.class),
                        row.getString("group_name_snapshot"),
                        row.getString("option_name_snapshot"),
                        row.getInt("quantity"),
                        row.getLong("unit_amount_minor"),
                        row.getLong("final_amount_minor")))
                .list();
    }

    // ------------------------------------------------------------ approvals

    public void insertApprovalDecision(
            UUID decisionRowId,
            UUID tenantId,
            UUID orderId,
            String decisionId,
            String action,
            String decisionChannel,
            String actorType,
            String actorId,
            String reasonCode,
            Instant issuedAt) {
        jdbc.sql("""
                INSERT INTO ordering.approval_decisions (
                    id, tenant_id, order_id, decision_id, action, decision_channel,
                    actor_type, actor_id, reason_code, effective, issued_at)
                VALUES (:id, :tenantId, :orderId, :decisionId, :action, :channel,
                    :actorType, :actorId, :reason, false, :issuedAt)
                """)
                .param("id", decisionRowId)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("decisionId", decisionId)
                .param("action", action)
                .param("channel", decisionChannel)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("reason", reasonCode)
                .param("issuedAt", utc(issuedAt))
                .update();
    }

    public Optional<ApprovalDecisionRow> findDecision(UUID tenantId, UUID orderId, String decisionId) {
        return jdbc.sql("""
                SELECT id, decision_id, action, decision_channel, actor_type, actor_id,
                       reason_code, effective, issued_at
                FROM ordering.approval_decisions
                WHERE tenant_id = :tenantId AND order_id = :orderId AND decision_id = :decisionId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("decisionId", decisionId)
                .query(JdbcOrderStore::mapDecision)
                .optional();
    }

    public Optional<ApprovalDecisionRow> findEffectiveDecision(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, decision_id, action, decision_channel, actor_type, actor_id,
                       reason_code, effective, issued_at
                FROM ordering.approval_decisions
                WHERE tenant_id = :tenantId AND order_id = :orderId AND effective
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcOrderStore::mapDecision)
                .optional();
    }

    /**
     * Marks the decision that won the compare-and-set as the effective one.
     *
     * <p>Guarded by a partial unique index on {@code effective}, so even if two
     * callers somehow both believed they had won the status update, only one row
     * can carry the flag and the second insert fails rather than producing an
     * order with two authoritative decisions.
     */
    public boolean markDecisionEffective(UUID tenantId, UUID decisionRowId) {
        return jdbc.sql("""
                UPDATE ordering.approval_decisions
                SET effective = true
                WHERE tenant_id = :tenantId AND id = :id AND NOT effective
                """)
                        .param("tenantId", tenantId)
                        .param("id", decisionRowId)
                        .update()
                == 1;
    }

    // ------------------------------------------------- kitchen progress proposals

    /**
     * Takes the idempotency key for this proposal, or reports that somebody
     * already holds it (ADR 0041, V0087).
     *
     * <p>Claimed <em>before</em> the transition is attempted rather than recorded
     * after it, so the key is held for the whole of the proposing transaction and
     * the row and the transition it describes commit or roll back together. The
     * alternative — decide, then insert — leaves a window in which two identical
     * proposals both decide and the second one's insert then fails on the unique
     * constraint, which would take a cook's station advance down with it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a select-then-insert, and it
     * does not block on an uncommitted conflicting row: an empty answer means
     * either a settled proposal this caller should read, or a twin still in
     * flight, and {@link #findProgressProposal} tells the two apart.
     *
     * @return the claimed row's id, or empty when the key was already taken
     */
    public Optional<UUID> claimProgressProposal(
            UUID tenantId,
            UUID orderId,
            String idempotencyKey,
            OrderStatus proposedStatus,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable String correlationId,
            Instant now) {

        return jdbc.sql("""
                INSERT INTO ordering.order_progress_proposals (
                    id, tenant_id, order_id, idempotency_key, proposed_status,
                    reason_code, actor_type, actor_id, correlation_id, proposed_at)
                VALUES (:id, :tenantId, :orderId, :key, :status,
                    :reason, :actorType, :actorId, :correlationId, :now)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING id
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("key", idempotencyKey)
                .param("status", proposedStatus.name())
                .param("reason", reasonCode)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("correlationId", correlationId)
                .param("now", utc(now))
                .query(UUID.class)
                .optional();
    }

    /** Writes what ordering did with a claimed proposal, in the same transaction. */
    public void settleProgressProposal(
            UUID tenantId, UUID proposalId, OrderStatus fromStatus, String outcome, Instant now) {
        jdbc.sql("""
                UPDATE ordering.order_progress_proposals
                SET outcome = :outcome, from_status = :from, settled_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND outcome IS NULL
                """)
                .param("tenantId", tenantId)
                .param("id", proposalId)
                .param("from", fromStatus == null ? null : fromStatus.name())
                .param("outcome", outcome)
                .param("now", utc(now))
                .update();
    }

    /**
     * What happened the first time this kitchen fact was proposed.
     *
     * <p>Keyed on the tenant as well as the string, because the key is supplied
     * by a caller and one tenant's ticket must never answer another's proposal.
     */
    public Optional<ProgressProposalRow> findProgressProposal(UUID tenantId, String idempotencyKey) {
        return jdbc.sql("""
                SELECT id, order_id, proposed_status, outcome
                FROM ordering.order_progress_proposals
                WHERE tenant_id = :tenantId AND idempotency_key = :key
                """)
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query((row, number) -> new ProgressProposalRow(
                        row.getObject("id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getString("proposed_status"),
                        row.getString("outcome")))
                .optional();
    }

    // -------------------------------------------------------------- timers

    public void insertTimer(UUID tenantId, UUID orderId, String timerType, Instant dueAt) {
        jdbc.sql("""
                INSERT INTO ordering.order_timers (id, tenant_id, order_id, timer_type, due_at)
                VALUES (:id, :tenantId, :orderId, :type, :dueAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("type", timerType)
                .param("dueAt", utc(dueAt))
                .update();
    }

    /**
     * Corrects the deadline an order itself reports, for the one path that arms
     * an approval timer later than checkout.
     *
     * <p>{@code approval_deadline_at} is written once, at {@link #insertOrder},
     * from "if this order needed restaurant approval right now" — which is
     * correct for a cash or already-payable order, where the timer is armed in
     * the same statement using the same instant, but wrong for a
     * {@code BEFORE_CONFIRMATION} order: {@code CheckoutService.awaitPayment}
     * arms no timer at all, and the real one is armed only once the payment
     * lands, against the later instant the money actually arrived. Left
     * uncorrected, this column would show a deadline no timer row backs, which
     * is precisely the gap {@code ordering.order_timers} exists to prevent
     * looking authoritative.
     */
    public void armApprovalDeadline(UUID tenantId, UUID orderId, Instant deadline) {
        jdbc.sql("""
                UPDATE ordering.orders
                SET approval_deadline_at = :deadline
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", orderId)
                .param("deadline", utc(deadline))
                .update();
    }

    /**
     * Claims due timers for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} so two workers never fire one timer twice,
     * and a slow worker does not stall every other pending deadline behind it.
     */
    public List<DueTimerRow> claimDueTimers(Instant now, int batchSize) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM ordering.order_timers
                    WHERE status = 'PENDING' AND due_at <= :now
                    ORDER BY due_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE ordering.order_timers AS timer
                SET status = 'FIRED', settled_at = :now
                FROM due
                WHERE timer.id = due.id
                RETURNING timer.id, timer.tenant_id, timer.order_id, timer.timer_type
                """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .query((row, number) -> new DueTimerRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getString("timer_type")))
                .list();
    }

    /**
     * Orders in {@code AWAITING_APPROVAL} whose deadline falls in
     * {@code (now, until]} — ADR 0058's approval-deadline-warning sweeper.
     *
     * <p>A plain SELECT against {@code ix_orders_awaiting_approval}, the same
     * partial index the board's own severity query relies on. No claim: unlike
     * {@link #claimDueTimers}, nothing here is fired exactly once by this table —
     * the caller's own notification idempotency key is what makes a re-scan
     * before the warning is sent safe to repeat.
     */
    public List<ApprovalDeadlineWarning> ordersNearingApprovalDeadline(Instant now, Instant until, int limit) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, public_order_number, approval_deadline_at
                FROM ordering.orders
                WHERE status = 'AWAITING_APPROVAL'
                  AND approval_deadline_at > :now AND approval_deadline_at <= :until
                ORDER BY approval_deadline_at
                LIMIT :limit
                """)
                .param("now", utc(now))
                .param("until", utc(until))
                .param("limit", limit)
                .query((row, number) -> new ApprovalDeadlineWarning(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("public_order_number"),
                        row.getObject("approval_deadline_at", OffsetDateTime.class)
                                .toInstant()))
                .list();
    }

    /** Cancels a pending timer once its order has settled some other way. */
    public boolean cancelTimer(UUID tenantId, UUID orderId, String timerType, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_timers
                SET status = 'CANCELLED', settled_at = :now
                WHERE tenant_id = :tenantId AND order_id = :orderId
                  AND timer_type = :type AND status = 'PENDING'
                """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .param("type", timerType)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private static final String SELECT_ORDER = """
            SELECT id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                   channel_code_snapshot, customer_account_id, guest_reference_hash,
                   fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                   acceptance_policy_version, approval_channel_snapshot,
                   approval_timeout_action_snapshot, approval_deadline_at, status,
                   payment_status_projection, fulfillment_status_projection, currency,
                   subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
                   pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                   idempotency_key, promised_at, promise_basis, promise_prep_minutes,
                   promise_travel_minutes, version, created_at, confirmed_at, closed_at,
                   current_revision, created_by_actor_type, created_by_actor_id,
                   accepted_by_actor_type, accepted_by_actor_id, accepted_at,
                   callback_requested, callback_resolved_at, callback_resolved_by,
                   cash_tendered_expected_minor, kitchen_note
            FROM ordering.orders""";

    /**
     * Rebuilds the promise from its four columns.
     *
     * <p>{@code getInt} answers 0 for a SQL NULL, which here would turn "this
     * basis carries no preparation component" into "we promised zero minutes in
     * the kitchen" — and {@link OrderPromise} would then reject the row it just
     * read back. The nullable read is what keeps the round trip lossless.
     */
    private static OrderPromise mapPromise(ResultSet row) throws SQLException {
        return new OrderPromise(
                instantOrNull(row, "promised_at"),
                PromiseBasis.valueOf(row.getString("promise_basis")),
                row.getObject("promise_prep_minutes", Integer.class),
                row.getObject("promise_travel_minutes", Integer.class));
    }

    private static OrderRow mapOrder(ResultSet row, int number) throws SQLException {
        return new OrderRow(
                row.getObject("id", UUID.class),
                row.getString("public_order_number"),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("channel_id", UUID.class),
                row.getString("channel_code_snapshot"),
                row.getObject("customer_account_id", UUID.class),
                row.getString("guest_reference_hash"),
                FulfillmentMode.valueOf(row.getString("fulfillment_mode")),
                row.getString("acceptance_mode_snapshot"),
                row.getObject("acceptance_policy_id", UUID.class),
                row.getInt("acceptance_policy_version"),
                row.getString("approval_channel_snapshot"),
                row.getString("approval_timeout_action_snapshot"),
                instantOrNull(row, "approval_deadline_at"),
                OrderStatus.valueOf(row.getString("status")),
                row.getString("payment_status_projection"),
                row.getString("fulfillment_status_projection"),
                row.getString("currency"),
                row.getLong("subtotal_minor"),
                row.getLong("tax_minor"),
                row.getLong("discount_minor"),
                row.getLong("fee_minor"),
                row.getLong("total_minor"),
                row.getObject("pricing_quote_id", UUID.class),
                row.getString("pricing_context_hash"),
                row.getObject("catalog_publication_id", UUID.class),
                row.getObject("cart_id", UUID.class),
                row.getString("idempotency_key"),
                mapPromise(row),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                instantOrNull(row, "confirmed_at"),
                instantOrNull(row, "closed_at"),
                row.getInt("current_revision"),
                row.getString("created_by_actor_type"),
                row.getString("created_by_actor_id"),
                row.getString("accepted_by_actor_type"),
                row.getString("accepted_by_actor_id"),
                instantOrNull(row, "accepted_at"),
                row.getBoolean("callback_requested"),
                instantOrNull(row, "callback_resolved_at"),
                row.getString("callback_resolved_by"),
                // getLong answers 0 for SQL NULL, and a change-due of zero is a
                // customer who said they would hand over nothing. Null is "they
                // did not say", and the operations board renders the two
                // differently.
                row.getObject("cash_tendered_expected_minor", Long.class),
                row.getString("kitchen_note"));
    }

    private static ApprovalDecisionRow mapDecision(ResultSet row, int number) throws SQLException {
        return new ApprovalDecisionRow(
                row.getObject("id", UUID.class),
                row.getString("decision_id"),
                row.getString("action"),
                row.getString("decision_channel"),
                row.getString("actor_type"),
                row.getString("actor_id"),
                row.getString("reason_code"),
                row.getBoolean("effective"),
                row.getObject("issued_at", OffsetDateTime.class).toInstant());
    }

    private static @Nullable Instant instantOrNull(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Everything an order needs at creation. Assembled once, inserted once.
     *
     * @param createdByActorType ADR 0039. Who entered the order — the storefront
     *                           acting for the customer, or an operator on the
     *                           phone. Written once and never overwritten, because
     *                           a leaderboard a later action can rewrite measures
     *                           nothing
     */
    public record NewOrder(
            UUID orderId,
            String publicOrderNumber,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            String channelCode,
            @Nullable UUID customerAccountId,
            @Nullable String guestReferenceHash,
            FulfillmentMode fulfillmentMode,
            String acceptanceMode,
            @Nullable UUID acceptancePolicyId,
            int acceptancePolicyVersion,
            String approvalChannel,
            @Nullable String approvalTimeoutAction,
            @Nullable Instant approvalDeadlineAt,
            OrderStatus status,
            String paymentStatusProjection,
            String fulfillmentStatusProjection,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long discountMinor,
            long feeMinor,
            long totalMinor,
            UUID pricingQuoteId,
            String pricingContextHash,
            UUID catalogPublicationId,
            UUID cartId,
            String idempotencyKey,
            OrderPromise promise,
            String createdByActorType,
            @Nullable String createdByActorId,
            Instant createdAt) {}

    /**
     * One appended revision.
     *
     * @param source        {@code CHECKOUT} for revision 1 and only revision 1;
     *                      {@code AMENDMENT} for every later one
     * @param amendmentId   the amendment this revision records, or null for the
     *                      {@code CHECKOUT} revision, which no amendment produced
     * @param deltaTotalMinor signed, against the predecessor. This is the figure
     *                      the operator reads to the customer, which is why it is
     *                      stored rather than recomputed by subtracting two rows
     *                      a report may have filtered differently
     */
    public record NewRevision(
            UUID orderId,
            int revision,
            UUID tenantId,
            String source,
            @Nullable UUID amendmentId,
            UUID pricingQuoteId,
            String pricingContextHash,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long discountMinor,
            long feeMinor,
            long totalMinor,
            long deltaTotalMinor,
            String createdByActorType,
            @Nullable String createdByActorId,
            Instant createdAt) {}

    public record RevisionRow(
            UUID orderId,
            int revision,
            String source,
            @Nullable UUID amendmentId,
            UUID pricingQuoteId,
            String pricingContextHash,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long discountMinor,
            long feeMinor,
            long totalMinor,
            long deltaTotalMinor,
            String createdByActorType,
            @Nullable String createdByActorId,
            Instant createdAt) {}

    /**
     * The order-level fields one amendment changes.
     *
     * <p>Null means "leave this alone" rather than "set this to nothing". A
     * command that sets the kitchen note must not also blank the change-due figure
     * an earlier one recorded, and a patch record with three optional fields is
     * how that stays true without three separate conditional statements.
     */
    public record OrderFieldPatch(
            @Nullable String kitchenNote,
            @Nullable Boolean callbackRequested,
            @Nullable Long cashTenderedExpectedMinor) {

        public static OrderFieldPatch none() {
            return new OrderFieldPatch(null, null, null);
        }
    }

    public record OutcomeRow(
            String kind,
            String systemCategory,
            @Nullable UUID reasonId,
            @Nullable Integer reasonVersion,
            @Nullable String reasonSnapshot,
            String actorType,
            String actorId,
            String stockDisposition,
            @Nullable String liabilityParty,
            @Nullable String customerRefund,
            boolean reservationCommitted,
            @Nullable UUID inventoryMovementId,
            @Nullable UUID refundId,
            Instant occurredAt) {}

    public record OrderRow(
            UUID orderId,
            String publicOrderNumber,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID channelId,
            String channelCode,
            UUID customerAccountId,
            String guestReferenceHash,
            FulfillmentMode fulfillmentMode,
            String acceptanceMode,
            @Nullable UUID acceptancePolicyId,
            int acceptancePolicyVersion,
            String approvalChannel,
            @Nullable String approvalTimeoutAction,
            @Nullable Instant approvalDeadlineAt,
            OrderStatus status,
            String paymentStatusProjection,
            String fulfillmentStatusProjection,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long discountMinor,
            long feeMinor,
            long totalMinor,
            UUID pricingQuoteId,
            String pricingContextHash,
            UUID catalogPublicationId,
            UUID cartId,
            String idempotencyKey,
            OrderPromise promise,
            int version,
            Instant createdAt,
            @Nullable Instant confirmedAt,
            @Nullable Instant closedAt,
            int currentRevision,
            String createdByActorType,
            @Nullable String createdByActorId,
            @Nullable String acceptedByActorType,
            @Nullable String acceptedByActorId,
            @Nullable Instant acceptedAt,
            boolean callbackRequested,
            @Nullable Instant callbackResolvedAt,
            @Nullable String callbackResolvedBy,
            Long cashTenderedExpectedMinor,
            String kitchenNote) {}

    /**
     * The order's customer snapshot, still encrypted (ADR 0029).
     *
     * <p>{@code addressEncrypted} is the JSON {@code DeliveryDestination}
     * document checkout wrote — structured fields and the coordinate together —
     * never a plain address line; {@code hasAddress}/{@code
     * hasDeliveryInstructions} are what an ordinary detail read may show, the
     * same way {@link OrderLineRow#hasNote()} lets a list show a marker without
     * rendering the words themselves.
     *
     * @param anonymizedAt non-null once the ADR 0029 retention job has blanked
     *                      the columns above; the caller renders "Данные удалены
     *                      по сроку хранения" rather than treating the row as
     *                      empty by accident
     */
    public record CustomerSnapshotRow(
            UUID orderId,
            @Nullable String displayNameEncrypted,
            @Nullable String contactEncrypted,
            @Nullable String addressEncrypted,
            @Nullable String deliveryInstructionsEncrypted,
            boolean transactionalContactAllowed,
            @Nullable Instant anonymizedAt) {

        public boolean hasAddress() {
            return addressEncrypted != null;
        }

        public boolean hasDeliveryInstructions() {
            return deliveryInstructionsEncrypted != null;
        }
    }

    /**
     * The board's tab badges (orders.md §2.3), all from one aggregate.
     *
     * <p>{@code Внимание} is deliberately absent — see {@link #counts}. {@code
     * totalNonTerminal} is every order not in {@link OrderStatus#terminal()},
     * across every status above.
     */
    public record OrderCountsRow(
            long newOrders,
            long awaitingApproval,
            long inKitchen,
            long ready,
            long fulfilling,
            long completed,
            long cancelled,
            long totalNonTerminal,
            long total) {}

    /**
     * The twelve columns a customer's own order list needs, and no others.
     *
     * <p>Deliberately not a subset view of {@link OrderRow}: a record that could be
     * widened to the full row is one that will be, and the fields left out here are
     * left out for a reason rather than for brevity.
     */
    public record CustomerOrderRow(
            UUID orderId,
            String publicOrderNumber,
            UUID locationId,
            FulfillmentMode fulfillmentMode,
            OrderStatus status,
            String paymentStatusProjection,
            String fulfillmentStatusProjection,
            String currency,
            long totalMinor,
            @Nullable Instant promisedAt,
            int version,
            Instant createdAt) {}

    /**
     * One order line as it stands at the revision it was read for.
     *
     * @param noteEncrypted the stored ciphertext, never rendered. Callers ask
     *                      {@link #hasNote()} to decide whether a kitchen ticket
     *                      should show a note marker, and reveal it separately
     *                      with a recorded purpose
     */
    public record OrderLineRow(
            UUID lineId,
            int lineNumber,
            UUID sourceProductId,
            UUID sourceVariantId,
            String productName,
            String variantName,
            String sku,
            int quantity,
            long unitAmountMinor,
            long baseAmountMinor,
            long finalAmountMinor,
            long taxAmountMinor,
            String noteEncrypted) {

        public boolean hasNote() {
            return noteEncrypted != null;
        }
    }

    public record OrderModifierRow(
            UUID orderLineId,
            UUID sourceGroupId,
            UUID sourceOptionId,
            String groupName,
            String optionName,
            int quantity,
            long unitAmountMinor,
            long finalAmountMinor) {}

    public record TransitionRow(
            int sequenceNumber,
            String fromStatus,
            String toStatus,
            String trigger,
            String reasonCode,
            String actorType,
            String actorId,
            String correlationId,
            Instant occurredAt) {}

    public record ApprovalDecisionRow(
            UUID id,
            String decisionId,
            String action,
            String decisionChannel,
            String actorType,
            String actorId,
            String reasonCode,
            boolean effective,
            Instant issuedAt) {}

    public record DueTimerRow(UUID timerId, UUID tenantId, UUID orderId, String timerType) {}

    /**
     * A status transition proposed inside one transaction, and its outcome.
     *
     * @param outcome null only for a proposal whose transaction has not finished,
     *                which is never visible to another one — a committed row
     *                always carries an outcome, and V0087 constrains it
     */
    public record ProgressProposalRow(
            UUID id,
            UUID orderId,
            String proposedStatus,
            @Nullable String outcome) {}
}
