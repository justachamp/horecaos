package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.api.CourierSelfAuthorized;
import uz.horecaos.platform.courier.application.CourierDeliveryService;
import uz.horecaos.platform.courier.application.CourierDeliveryService.AcceptOutcome;
import uz.horecaos.platform.courier.application.CourierDeliveryService.DeliveryView;
import uz.horecaos.platform.courier.application.CourierDeliveryService.OfferView;
import uz.horecaos.platform.courier.application.CourierDeliveryService.Position;
import uz.horecaos.platform.courier.application.CourierDeliveryService.Where;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Job;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Offer;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Pickup;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Step;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.CustomerLocation;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The courier app's offers and deliveries (ADR 0014, ADR 0042, gap map row 3.9).
 *
 * <p>Every endpoint resolves the courier from the caller's own token subject and never from a
 * path variable, exactly as {@link CourierShiftController} does, so there is no parameter
 * through which a courier could name somebody else. The offer or delivery id in a path is an
 * id the caller supplies and proves nothing: every statement beneath names the courier, so an
 * id belonging to anybody else answers as one that does not exist.
 *
 * <p>The courier policy (couriers.md §16) is held here, at the one place a courier acts:
 * {@code POST .../offers/{id}/accept} and {@code POST .../deliveries/{id}/advance} carry the
 * courier's own position when the tenant has switched the GPS gate on, and are refused with
 * {@code 422 UNPROCESSABLE_STATE} and a stable {@code reason} (see
 * {@link uz.horecaos.platform.courier.domain.GateRefusal}) when the courier is too far away,
 * the kitchen is not done, or the cash has not been confirmed. A position is measured and
 * discarded; no response here contains one, and none is stored.
 *
 * <p>The customer's name and telephone number are in no response here. The customer's door is
 * reachable only through the two {@code customer-location-reveals} endpoints, which are
 * {@code POST} for the reason the dispatcher's track reveal is: the call leaves an audit
 * record behind it, so it must be covered by an idempotency key, and nothing about it belongs in
 * a URL.
 */
@RestController
@RequestMapping("/api/v1/courier/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(name = "Courier deliveries", description = "A courier's own offers and deliveries, held to the courier policy")
public class CourierDeliveryController {

    private final CourierDeliveryService deliveries;
    private final JdbcCourierStore couriers;
    private final CurrentActor currentActor;

    public CourierDeliveryController(
            CourierDeliveryService deliveries, JdbcCourierStore couriers, CurrentActor currentActor) {
        this.deliveries = deliveries;
        this.couriers = couriers;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------------ offers

    @GetMapping("/offers")
    @CourierSelfAuthorized(Capability.COURIER_DELIVERY_READ)
    @Operation(
            operationId = "courierListOffers",
            summary = "The offers I can still take at this branch",
            description = "Soonest to lapse first. With the tenant's kitchen-ready-only switch on, an "
                    + "order the kitchen has not finished is not listed. Carries no name, telephone "
                    + "number or address of the customer; the address is a separate, audited reveal.")
    public ResponseEntity<List<CourierOfferResponse>> offers(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        Where where = new Where(tenantId, brandId, locationId);
        return ResponseEntity.ok(deliveries.offers(where, me(tenantId)).stream()
                .map(CourierOfferResponse::of)
                .toList());
    }

    @PostMapping("/offers/{offerId}/accept")
    @CourierSelfAuthorized(Capability.COURIER_OFFER_ACCEPT)
    @Idempotent
    @Operation(
            operationId = "courierAcceptOffer",
            summary = "Take an offer",
            description = "Requires If-Match with the offer's version. When the tenant's GPS gate is "
                    + "on, the body carries the courier's own position and acceptance is refused "
                    + "(422, reason TOO_FAR_FROM_PICKUP) beyond the accept radius of the branch. "
                    + "Refused with KITCHEN_NOT_READY when the tenant takes only finished orders. "
                    + "An offer that lapsed, was taken by another courier, or was never this "
                    + "courier's answers 200 with outcome NO_LONGER_AVAILABLE, never an error.")
    public ResponseEntity<CourierAcceptOfferResponse> accept(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID offerId,
            @Valid @RequestBody(required = false) @Nullable CourierAcceptOfferRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        AcceptOutcome outcome = deliveries.accept(
                new Where(tenantId, brandId, locationId),
                me(tenantId),
                offerId,
                expected,
                position(body == null ? null : body.position()),
                actor());
        return ResponseEntity.ok(CourierAcceptOfferResponse.of(outcome));
    }

    @PostMapping("/offers/{offerId}/decline")
    @CourierSelfAuthorized(Capability.COURIER_OFFER_DECLINE)
    @Idempotent
    @Operation(
            operationId = "courierDeclineOffer",
            summary = "Turn an offer down",
            description = "Requires If-Match with the offer's version. Sourcing asks the next courier "
                    + "at once rather than when this offer would have lapsed.")
    public ResponseEntity<CourierDeclineOfferResponse> decline(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID offerId,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        boolean declined =
                deliveries.decline(new Where(tenantId, brandId, locationId), me(tenantId), offerId, expected, actor());
        return ResponseEntity.ok(new CourierDeclineOfferResponse(declined ? "DECLINED" : "NO_LONGER_AVAILABLE"));
    }

    @PostMapping("/offers/{offerId}/customer-location-reveals")
    @CourierSelfAuthorized(Capability.COURIER_DELIVERY_LOCATION_REVEAL)
    @Idempotent
    @Operation(
            operationId = "courierRevealOfferCustomerLocation",
            summary = "Open the customer's address while I only hold the offer",
            description = "Refused (422, reason LOCATION_NOT_YET_REVEALED) unless the tenant reveals "
                    + "the customer's location before acceptance. Always an audit fact naming the "
                    + "courier, the order and the offer, and never the address. A courier who has "
                    + "accepted uses the delivery's own reveal.")
    public ResponseEntity<CourierCustomerLocationResponse> revealForOffer(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID offerId) {

        return ResponseEntity.ok(CourierCustomerLocationResponse.of(
                deliveries.revealForOffer(new Where(tenantId, brandId, locationId), me(tenantId), offerId, actor())));
    }

    // -------------------------------------------------------------- deliveries

    @GetMapping("/deliveries")
    @CourierSelfAuthorized(Capability.COURIER_DELIVERY_READ)
    @Operation(
            operationId = "courierListDeliveries",
            summary = "What I am carrying at this branch",
            description = "Assigned, waiting at the pickup, or on the road. Only this courier's own "
                    + "deliveries are ever returned.")
    public ResponseEntity<List<CourierDeliveryResponse>> deliveries(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        Where where = new Where(tenantId, brandId, locationId);
        return ResponseEntity.ok(deliveries.deliveries(where, me(tenantId)).stream()
                .map(CourierDeliveryResponse::of)
                .toList());
    }

    @GetMapping("/deliveries/{shipmentId}")
    @CourierSelfAuthorized(Capability.COURIER_DELIVERY_READ)
    @Operation(
            operationId = "courierGetDelivery",
            summary = "One of my deliveries",
            description = "Any status. A delivery that is not this courier's answers 404, exactly as "
                    + "one that does not exist. Returns an ETag to send back as If-Match.")
    public ResponseEntity<CourierDeliveryResponse> delivery(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shipmentId) {

        return respond(deliveries.delivery(new Where(tenantId, brandId, locationId), me(tenantId), shipmentId));
    }

    @PostMapping("/deliveries/{shipmentId}/advance")
    @CourierSelfAuthorized(Capability.COURIER_DELIVERY_ADVANCE)
    @Idempotent
    @Operation(
            operationId = "courierAdvanceDelivery",
            summary = "Move my delivery to the next step",
            description = "Requires If-Match with the delivery's version. step is PICKUP_PENDING "
                    + "(arrived), PICKED_UP or DELIVERED. When the tenant's GPS gate is on, the body "
                    + "carries the courier's own position and the step is refused (422) when it is "
                    + "further than the status-change radius from the branch (arrival, pickup) or "
                    + "the customer's door (handover): reasons GPS_POSITION_REQUIRED, "
                    + "GPS_ACCURACY_INSUFFICIENT, TOO_FAR_FROM_PICKUP, TOO_FAR_FROM_DROPOFF. "
                    + "DELIVERED is also refused (PAYMENT_CONFIRMATION_REQUIRED) while the tenant "
                    + "requires the cash to be confirmed and cash is due and unconfirmed. The "
                    + "position is measured and discarded.")
    public ResponseEntity<CourierDeliveryResponse> advance(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shipmentId,
            @Valid @RequestBody CourierAdvanceDeliveryRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        return respond(deliveries.advance(
                new Where(tenantId, brandId, locationId),
                me(tenantId),
                shipmentId,
                expected,
                body.step(),
                position(body.position()),
                actor()));
    }

    @PostMapping("/deliveries/{shipmentId}/payment-confirmation")
    @CourierSelfAuthorized(Capability.COURIER_DELIVERY_PAYMENT_CONFIRM)
    @Idempotent
    @Operation(
            operationId = "courierConfirmDeliveryPayment",
            summary = "Confirm the cash I collected at the door",
            description = "Requires If-Match with the delivery's version. Only on a delivery that has "
                    + "been picked up. The amount must equal what the settlement says is due (zero "
                    + "for an order the platform already holds the money for); anything else is "
                    + "refused (422, PAYMENT_AMOUNT_MISMATCH) and nothing is stored.")
    public ResponseEntity<CourierDeliveryResponse> confirmPayment(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shipmentId,
            @Valid @RequestBody CourierPaymentConfirmationRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        return respond(deliveries.confirmPayment(
                new Where(tenantId, brandId, locationId),
                me(tenantId),
                shipmentId,
                expected,
                body.collectedMinor(),
                actor()));
    }

    @PostMapping("/deliveries/{shipmentId}/customer-location-reveals")
    @CourierSelfAuthorized(Capability.COURIER_DELIVERY_LOCATION_REVEAL)
    @Idempotent
    @Operation(
            operationId = "courierRevealDeliveryCustomerLocation",
            summary = "Open the customer's address for a delivery I carry",
            description = "Always an audit fact naming the courier, the order and the shipment, and "
                    + "never the address. Refused once the delivery is finished or cancelled.")
    public ResponseEntity<CourierCustomerLocationResponse> revealForDelivery(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shipmentId) {

        return ResponseEntity.ok(CourierCustomerLocationResponse.of(deliveries.revealForDelivery(
                new Where(tenantId, brandId, locationId), me(tenantId), shipmentId, actor())));
    }

    // ----------------------------------------------------------------- helpers

    private static ResponseEntity<CourierDeliveryResponse> respond(DeliveryView view) {
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(view.job().version()))
                .body(CourierDeliveryResponse.of(view));
    }

    /** The courier a request is about is always the caller's own, resolved from their token. */
    private UUID me(UUID tenantId) {
        return couriers.findCourierBySubject(tenantId, currentActor.get().subject())
                .map(JdbcCourierStore.CourierRow::id)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The caller is not a courier of this tenant"));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static @Nullable Position position(@Nullable CourierPositionBody body) {
        if (body == null) {
            return null;
        }
        try {
            return new Position(new GeoPoint(body.latitude(), body.longitude()), body.accuracyMeters());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The position is not a real place on earth");
        }
    }

    // ---------------------------------------------------------------- requests

    /**
     * The courier's own position, sent only so a gate can measure it.
     *
     * <p>Boxed: Jackson 3 refuses a missing primitive, and a position missing its accuracy is a
     * position the gate cannot trust.
     */
    record CourierPositionBody(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("0") Double accuracyMeters) {

        @Override
        public String toString() {
            return "CourierPositionBody[REDACTED]";
        }
    }

    record CourierAcceptOfferRequest(@Valid @Nullable CourierPositionBody position) {}

    record CourierAdvanceDeliveryRequest(
            @NotNull Step step, @Valid @Nullable CourierPositionBody position) {}

    record CourierPaymentConfirmationRequest(
            @NotNull @Min(0) Long collectedMinor) {}

    // --------------------------------------------------------------- responses

    record CourierPickupResponse(
            String name,
            String addressLine,
            @Nullable String landmark,
            @Nullable Double latitude,
            @Nullable Double longitude) {

        static CourierPickupResponse of(Pickup pickup) {
            return new CourierPickupResponse(
                    pickup.name(), pickup.addressLine(), pickup.landmark(), pickup.latitude(), pickup.longitude());
        }
    }

    /**
     * @param customerLocationRevealable the tenant reveals the customer's address before acceptance,
     *                                   so this offer's {@code customer-location-reveals} will answer
     */
    record CourierOfferResponse(
            UUID offerId,
            long version,
            String orderReference,
            Instant offeredAt,
            Instant expiresAt,
            String currency,
            long orderTotalMinor,
            boolean prepaid,
            boolean kitchenReady,
            @Nullable String destinationLabel,
            @Nullable Integer distanceMeters,
            Instant pickupWindowStart,
            Instant pickupWindowEnd,
            @Nullable Instant promisedDeliveryEnd,
            CourierPickupResponse pickup,
            boolean customerLocationRevealable) {

        static CourierOfferResponse of(OfferView view) {
            Offer offer = view.offer();
            return new CourierOfferResponse(
                    offer.offerId(),
                    offer.version(),
                    offer.orderReference(),
                    offer.offeredAt(),
                    offer.expiresAt(),
                    offer.currency(),
                    offer.orderTotalMinor(),
                    offer.prepaid(),
                    offer.kitchenReady(),
                    offer.destinationLabel(),
                    offer.distanceMeters(),
                    offer.pickupWindowStart(),
                    offer.pickupWindowEnd(),
                    offer.promisedDeliveryEnd(),
                    CourierPickupResponse.of(offer.pickup()),
                    view.customerLocationRevealable());
        }
    }

    /**
     * @param outcome  {@code ACCEPTED}, or {@code NO_LONGER_AVAILABLE} for an offer that lapsed, was
     *                 taken by another, or was never this courier's
     * @param delivery the delivery now carried, absent unless accepted
     */
    record CourierAcceptOfferResponse(
            String outcome, @Nullable CourierDeliveryResponse delivery) {

        static CourierAcceptOfferResponse of(AcceptOutcome outcome) {
            return outcome.accepted() && outcome.delivery() != null
                    ? new CourierAcceptOfferResponse("ACCEPTED", CourierDeliveryResponse.of(outcome.delivery()))
                    : new CourierAcceptOfferResponse("NO_LONGER_AVAILABLE", null);
        }
    }

    record CourierDeclineOfferResponse(String outcome) {}

    /**
     * @param cashDueMinor                what the courier is to collect at the door; zero for an order
     *                                    the platform already holds the money for
     * @param paymentConfirmationRequired the tenant requires the cash to be confirmed before this
     *                                    delivery can be marked delivered
     */
    record CourierDeliveryResponse(
            UUID shipmentId,
            long version,
            String status,
            String orderReference,
            Instant assignedAt,
            @Nullable Instant pickedUpAt,
            @Nullable Instant deliveredAt,
            @Nullable Instant paymentConfirmedAt,
            @Nullable Long paymentConfirmedMinor,
            String currency,
            long orderTotalMinor,
            long cashDueMinor,
            boolean prepaid,
            boolean kitchenReady,
            @Nullable String destinationLabel,
            @Nullable Integer distanceMeters,
            Instant pickupWindowStart,
            Instant pickupWindowEnd,
            @Nullable Instant promisedDeliveryEnd,
            CourierPickupResponse pickup,
            boolean paymentConfirmationRequired) {

        static CourierDeliveryResponse of(DeliveryView view) {
            Job job = view.job();
            return new CourierDeliveryResponse(
                    job.shipmentId(),
                    job.version(),
                    job.status().name(),
                    job.orderReference(),
                    job.assignedAt(),
                    job.pickedUpAt(),
                    job.deliveredAt(),
                    job.paymentConfirmedAt(),
                    job.paymentConfirmedMinor(),
                    job.currency(),
                    job.orderTotalMinor(),
                    view.cashDueMinor(),
                    job.prepaid(),
                    job.kitchenReady(),
                    job.destinationLabel(),
                    job.distanceMeters(),
                    job.pickupWindowStart(),
                    job.pickupWindowEnd(),
                    job.promisedDeliveryEnd(),
                    CourierPickupResponse.of(job.pickup()),
                    view.paymentConfirmationRequired());
        }
    }

    /**
     * The customer's door and the way in. Personal data throughout, so it prints as nothing.
     */
    record CourierCustomerLocationResponse(
            double latitude,
            double longitude,
            String addressLine,
            @Nullable String entrance,
            @Nullable String floor,
            @Nullable String apartment,
            @Nullable String instructions) {

        static CourierCustomerLocationResponse of(CustomerLocation location) {
            return new CourierCustomerLocationResponse(
                    location.latitude(),
                    location.longitude(),
                    location.addressLine(),
                    location.entrance(),
                    location.floor(),
                    location.apartment(),
                    location.instructions());
        }

        @Override
        public String toString() {
            return "CourierCustomerLocationResponse[REDACTED]";
        }
    }
}
