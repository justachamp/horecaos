package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.ProtectionIntegrityException;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.BusinessDayWindows;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.DeliveryAddressCandidate;

/**
 * Where a branch's delivery orders of the day are going, as points (row {@code 7.10a}, ADR 0145
 * decision 8: "a dispatcher-scope read with an ADR 0027 audited purpose, never a reporting fact").
 *
 * <p><strong>A point is a doorstep, and a doorstep is a home.</strong> It exists only inside the
 * order's envelope-encrypted customer snapshot (ADR 0029; {@link DeliveryDestination}'s own
 * doc says why it is not a clear column), so a map of today's orders cannot be built from a
 * report and cannot be built without opening that ciphertext, once per order. That is exactly
 * what makes this a reveal and not a query, and it is treated as one:
 *
 * <ul>
 *   <li><strong>One audit fact for the whole call, written before any decryption.</strong> It
 *       names who, why (the stated purpose), which branch and how many orders -- and nothing about
 *       where any of them is. The order of the two steps is the same argument
 *       {@code OrderQueryService#revealLineNote} makes: a failure half-way cannot leave a reveal
 *       that happened with no record of it. A call that finds no orders still leaves its fact: a
 *       read that returned nothing is still a read.
 *   <li><strong>The answer holds no identity.</strong> An order number, a status, a time and a
 *       point. No name, no phone, no address text, no instructions -- the structured address is
 *       parsed only to take its coordinate and is dropped. "Which order is that pin" is answered
 *       by the order number, and "who lives there" stays behind the per-order reveal.
 *   <li><strong>Never logged.</strong> A refusal to open one ciphertext (a snapshot whose key was
 *       shredded, ADR 0029) counts the order as having no point; nothing about the value or the
 *       failure's detail is written anywhere.
 * </ul>
 *
 * <p>"Today" is the tenant's own business day (ADR 0043) containing this call's instant, through
 * the same {@link BusinessDayWindows} port the order board's counters use -- not the UTC calendar
 * day, which would cut a branch that closes at 02:00 in half.
 *
 * <p>Bounded: at most {@value #MAX_POINTS} points, newest first, with {@code truncated} saying so.
 * A pin map of five hundred orders is already past what a person can read, and an unbounded loop
 * of decryptions is a denial-of-service on the key service dressed up as a feature.
 */
@Service
public class OrderMapPointService {

    /** The most points one call opens. */
    public static final int MAX_POINTS = 500;

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";
    private static final String SNAPSHOT_ADDRESS_COLUMN = "address_encrypted";

    private final JdbcOrderStore orders;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final BusinessDayWindows businessDays;
    private final Clock clock;
    private final int maxPoints;

    @Autowired
    public OrderMapPointService(
            JdbcOrderStore orders,
            FieldProtection protection,
            ObjectMapper objectMapper,
            AuditRecorder audit,
            BusinessDayWindows businessDays,
            Clock clock) {
        this(orders, protection, objectMapper, audit, businessDays, clock, MAX_POINTS);
    }

    /** A smaller cap, so the truncation rule is testable without five hundred orders. */
    public OrderMapPointService(
            JdbcOrderStore orders,
            FieldProtection protection,
            ObjectMapper objectMapper,
            AuditRecorder audit,
            BusinessDayWindows businessDays,
            Clock clock,
            int maxPoints) {
        this.orders = orders;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.businessDays = businessDays;
        this.clock = clock;
        this.maxPoints = maxPoints;
    }

    /**
     * Opens the branch's delivery orders of the current business day as points.
     *
     * @param purpose recorded as the audit fact's reason (ADR 0027), and bound into each decrypt
     * @param actorSubject the caller's own identity, recorded as the audit actor -- never a value
     *     taken from the request body
     */
    @Transactional
    public MapPoints reveal(UUID tenantId, UUID brandId, UUID locationId, String purpose, String actorSubject) {
        if (purpose.isBlank()) {
            throw new IllegalArgumentException("A reveal states why: purpose must not be blank");
        }
        Instant now = clock.instant();
        BusinessDayWindows.Window window = businessDays.businessDayContaining(tenantId, now);

        List<DeliveryAddressCandidate> found = orders.deliveryAddressCandidates(
                tenantId, brandId, locationId, window.from(), window.to(), maxPoints + 1);
        boolean truncated = found.size() > maxPoints;
        List<DeliveryAddressCandidate> candidates = truncated ? found.subList(0, maxPoints) : found;

        // Before any decryption: the record that the reveal happened must outlive a failure in it.
        recordReveal(tenantId, brandId, locationId, purpose, actorSubject, window, candidates.size(), now);

        List<MapPoint> points = new ArrayList<>(candidates.size());
        int withoutPoint = 0;
        for (DeliveryAddressCandidate candidate : candidates) {
            DeliveryDestination destination = open(tenantId, candidate, purpose);
            if (destination == null) {
                withoutPoint++;
                continue;
            }
            points.add(new MapPoint(
                    candidate.orderId(),
                    candidate.publicOrderNumber(),
                    candidate.status(),
                    candidate.createdAt(),
                    destination.latitude(),
                    destination.longitude()));
        }
        return new MapPoints(window, List.copyOf(points), withoutPoint, truncated);
    }

    /** The destination's coordinate source, or null when this order has none to open. Nothing else leaves. */
    private @Nullable DeliveryDestination open(UUID tenantId, DeliveryAddressCandidate candidate, String purpose) {
        if (candidate.addressEncrypted() == null) {
            return null;
        }
        try {
            String document = protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(candidate.addressEncrypted()),
                    new FieldProtection.RecordRef(SNAPSHOT_TABLE, SNAPSHOT_ADDRESS_COLUMN, candidate.orderId()),
                    purpose);
            return objectMapper.readValue(document, DeliveryDestination.class);
        } catch (ProtectionIntegrityException | JacksonException | IllegalArgumentException unreadable) {
            // A shredded key, a document of an older shape, a coordinate out of range: this order has
            // no usable point. Deliberately not rethrown and not logged -- the exception text can
            // carry the value it failed on.
            return null;
        }
    }

    private void recordReveal(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String purpose,
            String actorSubject,
            BusinessDayWindows.Window window,
            int orderCount,
            Instant now) {
        Map<String, Object> revealed = new LinkedHashMap<>();
        revealed.put("windowFrom", window.from().toString());
        revealed.put("windowTo", window.to().toString());
        revealed.put("orders", orderCount);
        audit.record(AuditFact.of("order.map_points.revealed", AuditClass.SECURITY)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("tenant.location", locationId)
                .because(purpose)
                .usingCapability(Capability.ORDER_POINTS_REVEAL.code())
                // An append-only access-log fact with no prior state to diff against.
                .changed(ChangeDocuments.created(revealed))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    /**
     * One delivery order as a pin: which order, how it stands, when it came in, and where it is
     * going. No person.
     */
    public record MapPoint(
            UUID orderId,
            String publicOrderNumber,
            OrderStatus status,
            Instant createdAt,
            double latitude,
            double longitude) {

        /** Prints nothing: a record's generated {@code toString} would put a doorstep in a log line. */
        @Override
        public String toString() {
            return "MapPoint[" + orderId + "]";
        }
    }

    /**
     * @param withoutPoint delivery orders of the window with no point to show (an anonymized
     *     snapshot, an unreadable one): counted, so the map never silently under-reports
     * @param truncated the window held more than {@value #MAX_POINTS} orders; these are the newest
     */
    public record MapPoints(
            BusinessDayWindows.Window window, List<MapPoint> points, int withoutPoint, boolean truncated) {}
}
