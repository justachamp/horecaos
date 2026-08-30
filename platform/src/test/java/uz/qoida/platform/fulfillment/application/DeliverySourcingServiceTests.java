package uz.qoida.platform.fulfillment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.fulfillment.api.InternalFleetPort;
import uz.qoida.platform.fulfillment.api.ShipmentBookingPort;
import uz.qoida.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.qoida.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingProgress;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingRequest;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.tenancy.api.PolicyKey;
import uz.qoida.platform.tenancy.api.PolicyResolver;
import uz.qoida.platform.tenancy.api.ResolvedPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One tick of sourcing, from the two ports to a partner call (ADR 0014).
 *
 * <p>The planner's own tests own the timing judgement. These own the wiring
 * around it: that the fleet is asked before a partner is, that a partner is
 * called exactly once per attempt, and that the idempotency key a retry sends is
 * the one the first attempt sent.
 */
class DeliverySourcingServiceTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID COURIER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final ShipmentBookingPort.PartnerOption NOOR =
            new ShipmentBookingPort.PartnerOption(
                    UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"),
                    "noor-delivery", false, true);

    @Test
    @DisplayName("a free courier is offered the order and no partner is called at all")
    void anAvailableCourierKeepsThePartnerOut() {
        RecordingBookingPort bookings = new RecordingBookingPort(List.of(NOOR));
        DeliverySourcingService service = service(
                fleetWith(new InternalFleetPort.FleetCandidate(COURIER, 60, 0, 2, 400, 1)),
                bookings, sourceAt());

        DeliverySourcingService.Outcome outcome = service.source(request(SourcingMode.FLEET_FIRST));

        assertThat(outcome.decision()).isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(bookings.booked)
                .as("the commission is only paid when the fleet could not take it")
                .isEmpty();
        assertThat(outcome.progress().outstandingOffer()).isEqualTo(COURIER);
        // And the offer is durable before the courier's phone is told about it:
        // an offer nothing can hold a courier to is one two ticks make twice.
        assertThat(outcome.attemptId()).isNotNull();
    }

    @Test
    @DisplayName("an empty fleet falls through to the partner and books exactly once")
    void anEmptyFleetBooksThePartnerOnce() {
        RecordingBookingPort bookings = new RecordingBookingPort(List.of(NOOR));
        DeliverySourcingService service = service(emptyFleet(), bookings, sourceAt());

        DeliverySourcingService.Outcome outcome = service.source(request(SourcingMode.FLEET_FIRST));

        assertThat(bookings.booked).hasSize(1);
        assertThat(bookings.booked.getFirst().bindingId()).isEqualTo(NOOR.bindingId());
        assertThat(outcome.assigned()).isTrue();
        assertThat(outcome.decision().reason()).isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);
        assertThat(outcome.progress().attemptedPartners()).containsExactly(NOOR.bindingId());
    }

    @Test
    @DisplayName("the fleet is never asked about a partner-only plan")
    void partnerOnlyNeverTouchesTheFleet() {
        CountingFleetPort fleet = new CountingFleetPort();
        DeliverySourcingService service = service(fleet,
                new RecordingBookingPort(List.of(NOOR)), sourceAt());

        service.source(request(SourcingMode.PARTNER_ONLY));

        // ADR 0045 stops collecting a courier's position outside their shift;
        // asking who is on shift for a plan that will never offer them anything
        // is a purpose the privacy analysis does not cover.
        assertThat(fleet.calls).isZero();
    }

    @Test
    @DisplayName("a retried attempt sends the same idempotency key as the first")
    void aRetryReusesTheCommandId() {
        RecordingBookingPort bookings = new RecordingBookingPort(List.of(NOOR));
        bookings.status = ShipmentBookingPort.BookingStatus.RETRYABLE;
        DeliverySourcingService service = service(emptyFleet(), bookings, sourceAt());

        SourcingRequest request = request(SourcingMode.FLEET_FIRST);
        DeliverySourcingService.Outcome first = service.source(request);
        DeliverySourcingService.Outcome second = service.source(request, first.progress());

        assertThat(bookings.booked).hasSize(2);
        // A fresh id defeats the provider-side deduplication the retry depends
        // on, and on a partner whose create is live that is a second courier.
        assertThat(bookings.booked.get(1).commandId())
                .isEqualTo(bookings.booked.getFirst().commandId());
        // A transport fault is not the partner refusing, so the same partner is
        // still the one to try.
        assertThat(second.progress().attemptedPartners()).isEmpty();
    }

    @Test
    @DisplayName("an uncertain booking stops the run rather than trying the next partner")
    void anUncertainBookingBlocksTheNextPartner() {
        ShipmentBookingPort.PartnerOption second = new ShipmentBookingPort.PartnerOption(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003"),
                "yandex-delivery", true, true);
        RecordingBookingPort bookings = new RecordingBookingPort(List.of(NOOR, second));
        bookings.status = ShipmentBookingPort.BookingStatus.UNCERTAIN;
        DeliverySourcingService service = service(emptyFleet(), bookings, sourceAt());

        SourcingRequest request = request(SourcingMode.FLEET_FIRST);
        DeliverySourcingService.Outcome first = service.source(request);
        DeliverySourcingService.Outcome next = service.source(request, first.progress());

        assertThat(bookings.booked)
                .as("a partner that may have accepted must not be raced by a second one")
                .hasSize(1);
        assertThat(next.decision()).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(next.decision().reason()).isEqualTo(SourcingDecision.AWAITING_RECONCILIATION);
    }

    @Test
    @DisplayName("nothing configured resolves to ADR 0014's provisional timings under a stable id")
    void unconfiguredPolicyIsRecordedAsDefaults() {
        DeliverySourcingService service = service(emptyFleet(),
                new RecordingBookingPort(List.of(NOOR)), sourceAt());

        DeliverySourcingService.Outcome outcome = service.source(request(SourcingMode.FLEET_FIRST));

        // A random id per call would make two identical decisions look as though
        // they ran under two different policies.
        assertThat(outcome.policyId()).isEqualTo(DeliverySourcingService.DEFAULTS_ID);
        assertThat(outcome.policyVersion()).isEqualTo(1);
    }

    private static Instant sourceAt() {
        return plan().sourceAt();
    }

    private static PickupPlan plan() {
        return PickupPlan.forOrder(Instant.parse("2026-08-24T12:00:00Z"), Duration.ofHours(2),
                TASHKENT, DeliverySourcingPolicy.DEFAULTS);
    }

    private static SourcingRequest request(SourcingMode mode) {
        ShipmentBookingPort.Waypoint branch = new ShipmentBookingPort.Waypoint(
                41.311, 69.240, "branch", "Kitchen", "+998900000001", null, null, null, null);
        ShipmentBookingPort.Waypoint home = new ShipmentBookingPort.Waypoint(
                41.325, 69.281, "home", "Customer", "+998900000002", null, "2", "5", "17");

        return new SourcingRequest(TENANT, BRAND, LOCATION, UUID.randomUUID(),
                UUID.fromString("99999999-9999-9999-9999-999999999999"), "QO-3003", plan(),
                mode, 3_400, branch, home, true, 145_000L, "UZS", "corr-1");
    }

    private static DeliverySourcingService service(InternalFleetPort fleet,
            ShipmentBookingPort bookings, Instant now) {
        return service(fleet, bookings, now, new RecordingSourcingJournal());
    }

    private static DeliverySourcingService service(InternalFleetPort fleet,
            ShipmentBookingPort bookings, Instant now, RecordingSourcingJournal journal) {
        return new DeliverySourcingService(fleet, bookings, journal, unconfigured(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static InternalFleetPort emptyFleet() {
        return (tenantId, brandId, locationId, distanceMeters) -> List.of();
    }

    private static InternalFleetPort fleetWith(InternalFleetPort.FleetCandidate... candidates) {
        return (tenantId, brandId, locationId, distanceMeters) -> List.of(candidates);
    }

    /** Nothing configured at any scope, which is the state every tenant starts in. */
    private static PolicyResolver unconfigured() {
        return new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId,
                    int policyVersion) {
                return Optional.empty();
            }
        };
    }

    private static final class CountingFleetPort implements InternalFleetPort {

        private int calls;

        @Override
        public List<FleetCandidate> candidates(UUID tenantId, UUID brandId, UUID locationId,
                int distanceMeters) {
            calls++;
            return List.of();
        }
    }

    /** Records what sourcing asked a partner to do, without a Camel context. */
    private static final class RecordingBookingPort implements ShipmentBookingPort {

        private final List<PartnerOption> options;
        private final List<BookingCommand> booked = new ArrayList<>();
        private BookingStatus status = BookingStatus.BOOKED;

        RecordingBookingPort(List<PartnerOption> options) {
            this.options = options;
        }

        @Override
        public List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId) {
            return options;
        }

        @Override
        public BookingReceipt book(BookingCommand command) {
            booked.add(command);
            return BookingReceipt.of(status, command, "noor-delivery",
                    status == BookingStatus.BOOKED ? "ext-1" : null, null, null);
        }
    }
}
