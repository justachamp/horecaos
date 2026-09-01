package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderCounts;
import uz.horecaos.platform.ordering.api.OrderCountsQuery;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.CustomerSnapshotRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderCountsRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.TransitionRow;

/**
 * Reading orders (ADR 0019).
 *
 * <p>Reads only the snapshot tables. Nothing here joins back to the catalog or
 * the price books, which is what makes "the order says what it said" a property
 * of the schema rather than a promise: there is no live row a republish could
 * change under a receipt.
 *
 * <p>Every read carries the platform warnings that apply, so an unwired port
 * shows up on an operations screen rather than only in a startup log.
 */
@Service
public class OrderQueryService implements OrderCountsQuery {

    private static final String ORDER_LINE_TABLE = "ordering.order_lines";
    private static final String NOTE_COLUMN = "note_encrypted";

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";
    private static final String SNAPSHOT_NAME_COLUMN = "display_name_encrypted";
    private static final String SNAPSHOT_CONTACT_COLUMN = "contact_encrypted";
    private static final String SNAPSHOT_ADDRESS_COLUMN = "address_encrypted";
    private static final String SNAPSHOT_INSTRUCTIONS_COLUMN = "delivery_instructions_encrypted";

    /**
     * The fixed purpose behind the name and masked-phone decrypt every ordinary
     * detail read performs. Not a reveal in the ADR 0029 sense — orders.md §1.5
     * puts the name in full and the phone masked on every detail screen with no
     * separate gate — so there is no caller-supplied purpose to thread through;
     * {@link #revealCustomerPhone} and {@link #revealCustomerAddress} take one
     * because the value they return is the whole point of the call.
     */
    private static final String DETAIL_DISPLAY_PURPOSE = "ORDER_DETAIL_DISPLAY";

    private final JdbcOrderStore orders;
    private final JdbcOrderProcessStore processes;
    private final PaymentIntentPort payments;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrderQueryService(
            JdbcOrderStore orders,
            JdbcOrderProcessStore processes,
            PaymentIntentPort payments,
            FieldProtection protection,
            ObjectMapper objectMapper,
            AuditRecorder audit,
            Clock clock) {
        this.orders = orders;
        this.processes = processes;
        this.payments = payments;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Reveals one line's customer note (ADR 0029, ADR 0027).
     *
     * <p>Separate from reading the order, and requiring a stated purpose, because
     * "no onions, ring the top bell" is the customer's own words about themselves.
     * A kitchen ticket needs it; an order list does not, and rendering it
     * everywhere would put personal data on every screen in the branch.
     *
     * <p>The audit fact is written before the decrypt, in the same transaction,
     * exactly as {@code CourierTrackRevealService} does it — so a decryption
     * failure cannot leave a reveal that happened with no record of it. Nothing
     * is written for a line that carries no note: there is no reveal to record a
     * purpose against.
     *
     * @param purpose      recorded as an audit fact (ADR 0027)
     * @param actorSubject the caller's own identity, recorded as the audit
     *                     actor — never a value taken from the request body
     */
    @Transactional
    public Optional<String> revealLineNote(
            UUID tenantId, UUID orderId, UUID lineId, String purpose, String actorSubject) {
        return orders.lineNote(tenantId, orderId, lineId)
                .flatMap(stored -> orders.find(tenantId, orderId).map(order -> {
                    recordReveal(
                            "order.line_note.revealed",
                            order,
                            purpose,
                            actorSubject,
                            Map.of("lineId", lineId.toString()));
                    return protection.reveal(
                            tenantId,
                            ProtectedValue.deserialize(stored),
                            new FieldProtection.RecordRef(ORDER_LINE_TABLE, NOTE_COLUMN, lineId),
                            purpose);
                }));
    }

    @Transactional(readOnly = true)
    public Optional<OrderDetail> detail(UUID tenantId, UUID orderId) {
        return detail(tenantId, orderId, null);
    }

    /**
     * The order as it stood at one revision (ADR 0039).
     *
     * <p>ADR 0039's own negative consequence names this as the trap: revisioned
     * orders make every read revision-aware, and a read that forgets to pin one
     * double-counts. The pin is a parameter here rather than a convention, so a
     * caller has to say which answer it wants.
     *
     * @param revision the revision to read at, or null for the current one
     */
    @Transactional(readOnly = true)
    public Optional<OrderDetail> detail(UUID tenantId, UUID orderId, @Nullable Integer revision) {
        return orders.find(tenantId, orderId).map(order -> {
            List<OrderLineRow> lines = orders.lines(tenantId, orderId, revision);
            Map<UUID, List<OrderModifierRow>> modifiers = orders.lineModifiers(tenantId, orderId).stream()
                    .collect(Collectors.groupingBy(OrderModifierRow::orderLineId));

            List<DetailLine> detailLines = new ArrayList<>(lines.size());
            lines.forEach(
                    line -> detailLines.add(new DetailLine(line, modifiers.getOrDefault(line.lineId(), List.of()))));

            return new OrderDetail(order, detailLines, warnings(), customerDetail(tenantId, order));
        });
    }

    /**
     * The order's customer, decrypted exactly as far as an ordinary detail read
     * may go (orders.md §1.5, §3.7-§3.8): the name in full, the phone decrypted
     * for the caller to mask, and a presence flag rather than the plaintext for
     * the address and the delivery instructions — both of those stay behind
     * {@link #revealCustomerAddress}.
     *
     * <p>{@code customerType} is derived from the order row itself and is
     * therefore never absent, even for the (untested-in-production) case of a
     * snapshot row that was never written.
     */
    private CustomerDetail customerDetail(UUID tenantId, OrderRow order) {
        @Nullable String customerType;
        if (order.customerAccountId() != null) {
            customerType = "ACCOUNT";
        } else if (order.guestReferenceHash() != null) {
            customerType = "GUEST";
        } else {
            customerType = null;
        }

        Optional<CustomerSnapshotRow> snapshot = orders.customerSnapshot(tenantId, order.orderId());
        if (snapshot.isEmpty()) {
            return new CustomerDetail(null, null, false, false, true, customerType, false);
        }

        CustomerSnapshotRow row = snapshot.get();
        return new CustomerDetail(
                decryptForDisplay(tenantId, order.orderId(), SNAPSHOT_NAME_COLUMN, row.displayNameEncrypted()),
                decryptForDisplay(tenantId, order.orderId(), SNAPSHOT_CONTACT_COLUMN, row.contactEncrypted()),
                row.hasAddress(),
                row.hasDeliveryInstructions(),
                row.transactionalContactAllowed(),
                customerType,
                row.anonymizedAt() != null);
    }

    private @Nullable String decryptForDisplay(
            UUID tenantId, UUID orderId, String column, @Nullable String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(ciphertext),
                new FieldProtection.RecordRef(SNAPSHOT_TABLE, column, orderId),
                DETAIL_DISPLAY_PURPOSE);
    }

    /**
     * Reveals the customer's phone in full (ADR 0029, ADR 0027, orders.md §1.5).
     *
     * <p>Separate from {@link #detail}, and requiring a stated purpose, for the
     * same reason {@link #revealLineNote} is separate from reading the order:
     * the masked form on the detail screen is what an ordinary read may show,
     * and going from masked to whole is a deliberate act with its own audit
     * trail. Copy-to-clipboard of the phone counts as a reveal (orders.md
     * §1.5) and calls this endpoint rather than copying an already-decrypted
     * value.
     *
     * <p>The audit fact is written before the decrypt, in the same
     * transaction — see {@link #revealLineNote}'s doc comment for why.
     *
     * @param purpose      recorded as an audit fact (ADR 0027)
     * @param actorSubject the caller's own identity, recorded as the audit actor
     */
    @Transactional
    public Optional<String> revealCustomerPhone(UUID tenantId, UUID orderId, String purpose, String actorSubject) {
        return orders.customerSnapshot(tenantId, orderId)
                .filter(row -> row.contactEncrypted() != null)
                .flatMap(row -> orders.find(tenantId, orderId).map(order -> {
                    recordReveal("order.customer_phone.revealed", order, purpose, actorSubject, Map.of());
                    return protection.reveal(
                            tenantId,
                            // The filter above already required this non-null; NullAway
                            // cannot see that guarantee across the two lambdas.
                            ProtectedValue.deserialize(
                                    Objects.requireNonNull(row.contactEncrypted(), "filtered for non-null above")),
                            new FieldProtection.RecordRef(SNAPSHOT_TABLE, SNAPSHOT_CONTACT_COLUMN, orderId),
                            purpose);
                }));
    }

    /**
     * Reveals the delivery address and instructions in full (ADR 0029, ADR 0027,
     * orders.md §1.5, §3.6, §3.8).
     *
     * <p>The stored document is the structured {@link DeliveryDestination}
     * checkout wrote — дом, квартира, подъезд, этаж, ориентир and the
     * coordinate together, never a single address line — decrypted and parsed
     * back into the same shape rather than handed back as a JSON blob the
     * caller has to know the schema of by convention.
     *
     * <p>One audit fact for the whole call, written before either the address or
     * the instructions is decrypted — see {@link #revealLineNote}'s doc comment
     * for why.
     *
     * @param purpose      recorded as an audit fact (ADR 0027)
     * @param actorSubject the caller's own identity, recorded as the audit actor
     */
    @Transactional
    public Optional<CustomerAddressReveal> revealCustomerAddress(
            UUID tenantId, UUID orderId, String purpose, String actorSubject) {
        return orders.customerSnapshot(tenantId, orderId).flatMap(row -> {
            if (row.addressEncrypted() == null) {
                return Optional.empty();
            }
            // Bound to a local now that the null check has passed; NullAway
            // cannot see the guarantee across the nested lambda below.
            String addressEncrypted = Objects.requireNonNull(row.addressEncrypted(), "checked non-null above");
            return orders.find(tenantId, orderId).map(order -> {
                recordReveal("order.customer_address.revealed", order, purpose, actorSubject, Map.of());

                String addressDocument = protection.reveal(
                        tenantId,
                        ProtectedValue.deserialize(addressEncrypted),
                        new FieldProtection.RecordRef(SNAPSHOT_TABLE, SNAPSHOT_ADDRESS_COLUMN, orderId),
                        purpose);
                DeliveryDestination address = objectMapper.readValue(addressDocument, DeliveryDestination.class);

                String instructions = row.deliveryInstructionsEncrypted() == null
                        ? null
                        : protection.reveal(
                                tenantId,
                                ProtectedValue.deserialize(row.deliveryInstructionsEncrypted()),
                                new FieldProtection.RecordRef(SNAPSHOT_TABLE, SNAPSHOT_INSTRUCTIONS_COLUMN, orderId),
                                purpose);

                return new CustomerAddressReveal(address, instructions);
            });
        });
    }

    /**
     * Records the ADR 0027 evidence for a customer-PII reveal, in the same
     * transaction as the decrypt that follows it.
     *
     * <p>{@code ordering.order} is the target for every one of these facts,
     * matching {@code OrderStateService} and {@code OrderAmendmentService}'s own
     * {@code recordAudit} helpers — an operator or an auditor searching this
     * order's history finds a reveal exactly where every other action on it
     * lives, with what was revealed (a line id, or nothing beyond the order
     * itself) in the change document rather than in a second target type.
     */
    private void recordReveal(
            String actionCode, OrderRow order, String purpose, String actorSubject, Map<String, Object> changed) {
        audit.record(AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(order.tenantId(), order.brandId(), order.locationId()))
                .target("ordering.order", order.orderId())
                .because(purpose)
                .usingCapability(Capability.CUSTOMER_PII_REVEAL.code())
                .changed(changed)
                .correlatedBy(order.orderId().toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** The board's tab badges for one location, one aggregate (orders.md §2.3). */
    @Transactional(readOnly = true)
    public OrderCountsRow counts(UUID tenantId, UUID brandId, UUID locationId) {
        return orders.counts(tenantId, brandId, locationId);
    }

    /** {@link OrderCountsQuery}: the same read, in the type another module is allowed to see. */
    @Override
    @Transactional(readOnly = true)
    public OrderCounts liveCounts(UUID tenantId, UUID brandId, @Nullable UUID locationId) {
        OrderCountsRow row = orders.counts(tenantId, brandId, locationId);
        return new OrderCounts(
                row.newOrders(),
                row.awaitingApproval(),
                row.inKitchen(),
                row.ready(),
                row.fulfilling(),
                row.completed(),
                row.cancelled(),
                row.totalNonTerminal(),
                row.total());
    }

    /**
     * An order scoped to the customer who placed it.
     *
     * <p>The ownership predicate is a parameter of the query, not a check after
     * loading: ADR 0019 requires storefront lookup to verify customer or guest
     * proof, and a post-load check is one forgotten branch away from serving
     * somebody else's order.
     */
    @Transactional(readOnly = true)
    public Optional<OrderDetail> detailForCustomer(
            UUID tenantId, UUID orderId, @Nullable UUID customerAccountId, @Nullable String guestReferenceHash) {
        return detail(tenantId, orderId).filter(found -> {
            OrderRow order = found.order();
            if (customerAccountId != null) {
                return customerAccountId.equals(order.customerAccountId());
            }
            return guestReferenceHash != null && guestReferenceHash.equals(order.guestReferenceHash());
        });
    }

    @Transactional(readOnly = true)
    public List<OrderRow> forLocation(UUID tenantId, UUID brandId, UUID locationId, List<String> statuses, int limit) {
        return orders.listForLocation(tenantId, brandId, locationId, statuses, limit);
    }

    /**
     * A page of one customer's own orders (ADR 0019, ADR 0031).
     *
     * <p>The account is a predicate of the query, exactly as it is in
     * {@link #detailForCustomer}. It is the difference between a customer's history
     * and an enumeration of the brand's: there is no unscoped form of this method,
     * and no parameter that widens it.
     *
     * <p>One row per order and nothing beneath it. Lines, modifiers and the
     * transition log are all read through {@code detail}, which is a second request
     * a client makes for the order it opened — because a list that eagerly loaded
     * them would be N+2 queries and would carry a customer's own line notes onto a
     * screen that only shows totals.
     *
     * @param cursorOrderId the last order of the previous page, or null for the
     *                      first. Resolved inside the caller's own scope
     * @throws UnknownCursorException when the cursor names no order of this
     *                                customer's at this brand — including one that
     *                                is somebody else's, which answers identically
     */
    @Transactional(readOnly = true)
    public List<JdbcOrderStore.CustomerOrderRow> forCustomer(
            UUID tenantId, UUID brandId, UUID accountId, @Nullable UUID cursorOrderId, int limit) {

        @Nullable Instant before = null;
        if (cursorOrderId != null) {
            before = orders.customerOrderCursor(tenantId, brandId, accountId, cursorOrderId)
                    .orElseThrow(UnknownCursorException::new);
        }
        return orders.listForCustomer(tenantId, brandId, accountId, before, cursorOrderId, limit);
    }

    /** The cursor names nothing this caller may continue from. */
    public static class UnknownCursorException extends RuntimeException {
        public UnknownCursorException() {
            super("This cursor does not name an order of yours");
        }
    }

    /** The transition log: the answer to "why is this order in this state". */
    @Transactional(readOnly = true)
    public List<TransitionRow> timeline(UUID tenantId, UUID orderId) {
        return orders.history(tenantId, orderId);
    }

    /**
     * Every revision of one order, with the delta each one carried (ADR 0039).
     *
     * <p>Revision 1 is the checkout snapshot and is byte-identical for ever, which
     * is what makes a report pinned to it reconcile to the original total however
     * many amendments followed.
     */
    @Transactional(readOnly = true)
    public List<JdbcOrderStore.RevisionRow> revisions(UUID tenantId, UUID orderId) {
        return orders.revisions(tenantId, orderId);
    }

    /** The one terminal outcome, once the order has ended. */
    @Transactional(readOnly = true)
    public Optional<JdbcOrderStore.OutcomeRow> outcome(UUID tenantId, UUID orderId) {
        return orders.findOutcome(tenantId, orderId);
    }

    /** Process managers that need an operator, per tenant. */
    @Transactional(readOnly = true)
    public List<JdbcOrderProcessStore.ProcessRow> stuckProcesses(UUID tenantId, int limit) {
        return processes.stuck(tenantId, limit);
    }

    private List<String> warnings() {
        return payments.isWired() ? List.of() : List.of(PaymentIntentPort.NOT_WIRED_WARNING);
    }

    public record OrderDetail(OrderRow order, List<DetailLine> lines, List<String> warnings, CustomerDetail customer) {}

    public record DetailLine(OrderLineRow line, List<OrderModifierRow> modifiers) {}

    /**
     * The customer block an ordinary detail read may show (orders.md
     * §3.7-§3.8).
     *
     * @param displayName          decrypted in full; never masked (orders.md
     *                             §1.5)
     * @param contactDecrypted     the phone, decrypted. Personal data held only
     *                             long enough for the web layer to mask it —
     *                             never serialize this field directly
     * @param hasAddress           whether a delivery address is on file, without
     *                             revealing it
     * @param hasDeliveryInstructions whether the customer left instructions,
     *                             without revealing them
     * @param customerType         {@code "ACCOUNT"} or {@code "GUEST"}, or null
     *                             for an order carrying neither
     * @param anonymized           true once the ADR 0029 retention job has
     *                             blanked the snapshot; the panel renders
     *                             "Данные удалены по сроку хранения" rather than
     *                             an empty customer
     */
    public record CustomerDetail(
            @Nullable String displayName,
            @Nullable String contactDecrypted,
            boolean hasAddress,
            boolean hasDeliveryInstructions,
            boolean transactionalContactAllowed,
            @Nullable String customerType,
            boolean anonymized) {

        /**
         * Never the phone. A generated record accessor is not called here, but a
         * future logging statement reaching for {@code String.valueOf(detail)}
         * is exactly the mistake {@link DeliveryDestination#toString()} is also
         * guarded against.
         */
        @Override
        public String toString() {
            return "CustomerDetail[REDACTED]";
        }
    }

    /**
     * The full delivery address and instructions, as {@link #revealCustomerAddress}
     * returns them.
     */
    public record CustomerAddressReveal(
            DeliveryDestination address, @Nullable String deliveryInstructions) {

        @Override
        public String toString() {
            return "CustomerAddressReveal[REDACTED]";
        }
    }
}
