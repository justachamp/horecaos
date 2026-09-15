package uz.horecaos.platform.fulfillment.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How fulfilment reaches a courier partner without knowing one exists
 * (ADR 0014, ADR 0007).
 *
 * <p>ADR 0007's rule is that Camel performs calls and never decides whether a
 * delivery is acceptable, and ADR 0014's is that the selection service returns a
 * decision which Camel then executes. This interface is the line between those
 * two sentences. Fulfilment produces a {@link BookingCommand}; the adapter in
 * {@code integration.camel.delivery} turns it into a {@code DeliveryOperation}
 * and puts it on {@code direct:delivery.operation}.
 *
 * <p>Declared here rather than fulfilment depending on
 * {@code integration.api.provider} because integration already depends on this
 * package for the booking adapter, and the reverse edge would close the two
 * modules into a cycle. It is also why nothing on this interface is a
 * {@code BindingRef} or a {@code DeliveryCapability}: sourcing names an intent
 * and a binding id, and the mapping to a partner capability belongs to the side
 * that knows what the partners do.
 */
public interface ShipmentBookingPort {

    /**
     * Every delivery partner configured for this branch, narrowest binding
     * first, as ADR 0026 resolves them.
     *
     * <p>Empty is an ordinary answer, not a failure: a tenant running an
     * in-house fleet only has no delivery binding at all, and sourcing has to
     * render that as "no fallback exists" rather than as an error.
     */
    List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId);

    /**
     * Books, holds, or schedules with one named partner.
     *
     * <p>Never throws for a partner refusal. An out-of-zone address and a
     * partner with no couriers are both answers sourcing has to act on, and an
     * exception would turn a routine fallback into an incident.
     */
    BookingReceipt book(BookingCommand command);

    /**
     * A non-binding price and ETA from one partner, for scoring.
     *
     * <p>Side-effect-free by contract, which is what lets ADR 0014 quote every
     * eligible partner in parallel while forbidding it to create with more than
     * one. It takes a {@link BookingCommand} rather than a shape of its own
     * because a quote for a journey other than the one about to be booked scores
     * the wrong thing, and because the command is already the type whose
     * {@code toString} does not print a customer's address.
     *
     * <p>Defaulted to "no quote" rather than left abstract. Neither verified
     * partner returns a redeemable quote object, so a deployment whose adapter
     * has no quote path is an ordinary state, and scoring has to fall back to the
     * configured binding order rather than refusing to source.
     */
    default QuoteOutcome quote(BookingCommand command) {
        return QuoteOutcome.unavailable(QUOTE_NOT_WIRED);
    }

    /**
     * What one partner answered when asked what a journey would cost.
     *
     * @param priceMinor integer minor units — whole som for UZS — or null when
     *                   the partner refused or could not be asked. A price
     *                   without a currency is a number nobody can compare, which
     *                   is why {@code ck_quote_price_pair} refuses one
     * @param expiresAt  the partner's own guarantee, or null. A TTL HorecaOS imposes
     *                   is applied by the caller and recorded as
     *                   {@code HORECAOS_POLICY}, never as a partner promise
     */
    record QuoteOutcome(
            @Nullable Long priceMinor,
            @Nullable String currency,
            @Nullable Integer pickupEtaSeconds,
            @Nullable Integer deliveryEtaSeconds,
            @Nullable Integer distanceMeters,
            @Nullable Integer deadHeadMeters,
            @Nullable Instant expiresAt,
            boolean partnerSuppliedExpiry,
            @Nullable String failureCode,
            @Nullable String detail) {

        public QuoteOutcome {
            if ((priceMinor == null) != (currency == null)) {
                throw new IllegalArgumentException(
                        "A quoted price and its currency are present together or not at all");
            }
            if (priceMinor == null && failureCode == null) {
                // "No price and no reason" is the answer that makes a scoring
                // decision unexplainable six weeks later, which is the one thing
                // ADR 0014 requires a selection never to be.
                throw new IllegalArgumentException("A quote without a price must say why");
            }
        }

        public static QuoteOutcome priced(
                long priceMinor,
                String currency,
                @Nullable Integer pickupEtaSeconds,
                @Nullable Integer deliveryEtaSeconds,
                @Nullable Integer distanceMeters,
                @Nullable Integer deadHeadMeters) {
            return new QuoteOutcome(
                    priceMinor,
                    currency,
                    pickupEtaSeconds,
                    deliveryEtaSeconds,
                    distanceMeters,
                    deadHeadMeters,
                    null,
                    false,
                    null,
                    null);
        }

        public static QuoteOutcome unavailable(String failureCode) {
            return new QuoteOutcome(null, null, null, null, null, null, null, false, failureCode, null);
        }

        public boolean hasPrice() {
            return priceMinor != null;
        }
    }

    /**
     * What a partner can do, as much of it as sourcing needs to know.
     *
     * @param supportsHold whether a created booking is a hold rather than a live
     *                     one. Verified true for Yandex, whose unaccepted claim
     *                     is not a booking, and false for Noor, whose create
     *                     dispatches a courier. ADR 0014 hangs the single-winner
     *                     rule on exactly this flag: a hold may be taken while
     *                     another partner is still being evaluated and a live
     *                     create may never be
     * @param supportsScheduling whether the partner accepts a future pickup time.
     *                     Without it an advance booking has to wait until the
     *                     pickup window is near enough to book on demand
     */
    record PartnerOption(UUID bindingId, String providerType, boolean supportsHold, boolean supportsScheduling) {

        public PartnerOption {
            Objects.requireNonNull(bindingId, "A binding id is required");
            Objects.requireNonNull(providerType, "A provider type is required");
        }
    }

    /** What sourcing wants done, in terms no partner name appears in. */
    enum BookingIntent {

        /**
         * A hold that is not yet a live booking. Only ever sent to a partner
         * whose {@link PartnerOption#supportsHold()} is true; sending it to one
         * without holds would create a live booking under a name that says it
         * did not.
         */
        HOLD,

        /** A live booking, now. */
        BOOK_NOW,

        /** A live booking for {@link BookingCommand#requestedPickupAt()}. */
        BOOK_FOR_PICKUP_WINDOW
    }

    /**
     * One booking attempt against one partner.
     *
     * @param commandId  the idempotency key the partner sees. Stable across
     *                   every retry of this attempt, because a fresh id defeats
     *                   the provider-side deduplication the retry depends on —
     *                   which on a partner whose create is live means a second
     *                   courier
     * @param prepaid    whether HorecaOS already took the money. False here tells a
     *                   partner to collect from the recipient, so a wrong value
     *                   charges the customer twice
     * @param itemValueMinor integer minor units — whole som for UZS. The goods
     *                   value the courier is carrying, not the delivery fee
     * @param requestedPickupAt required for {@link BookingIntent#BOOK_FOR_PICKUP_WINDOW},
     *                   null for a booking made now
     */
    record BookingCommand(
            UUID commandId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID bindingId,
            BookingIntent intent,
            String horecaosReference,
            Waypoint pickup,
            Waypoint dropoff,
            @Nullable Instant requestedPickupAt,
            boolean prepaid,
            long itemValueMinor,
            String currency,
            String correlationId) {

        public BookingCommand {
            Objects.requireNonNull(commandId, "A command id is required");
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(bindingId, "A binding id is required");
            Objects.requireNonNull(intent, "A booking intent is required");
            Objects.requireNonNull(horecaosReference, "A HorecaOS reference is required");
            Objects.requireNonNull(pickup, "A pickup waypoint is required");
            Objects.requireNonNull(dropoff, "A dropoff waypoint is required");
            Objects.requireNonNull(currency, "A currency is required");
            if (intent == BookingIntent.BOOK_FOR_PICKUP_WINDOW && requestedPickupAt == null) {
                throw new IllegalArgumentException(
                        "A scheduled booking must name the pickup instant it is scheduled for");
            }
            if (itemValueMinor < 0) {
                throw new IllegalArgumentException("An item value cannot be negative, was " + itemValueMinor);
            }
        }

        /**
         * Names the order and nothing about the people on either end of it, for
         * the reason {@code DeliveryPartner.Pickup} gives: a record's generated
         * {@code toString} prints every component, and one interpolated log line
         * then puts a customer's address and phone number into the aggregator.
         */
        @Override
        public String toString() {
            return "BookingCommand[commandId=%s, intent=%s, reference=%s, pickupAt=%s]"
                    .formatted(commandId, intent, horecaosReference, requestedPickupAt);
        }
    }

    /** One end of the journey. Personal data throughout, so it prints as nothing. */
    record Waypoint(
            double latitude,
            double longitude,
            String address,
            @Nullable String contactName,
            @Nullable String contactPhone,
            @Nullable String comment,
            @Nullable String entrance,
            @Nullable String floor,
            @Nullable String apartment) {

        @Override
        public String toString() {
            return "Waypoint[REDACTED]";
        }
    }

    /**
     * What a booking attempt did, in the four outcomes ADR 0007 recognises.
     *
     * <p>{@link #UNCERTAIN} is the one that matters and it is why this is not a
     * boolean. A timeout after a create on a partner without holds may have
     * dispatched a courier, and treating it as a failure is how the fallback
     * books a second one.
     */
    enum BookingStatus {

        /** The partner accepted. {@code externalReference} names the booking. */
        BOOKED,

        /** A hold exists and is not a live booking. Must be confirmed or cancelled. */
        HELD,

        /** The partner refused on business grounds. Try the next partner. */
        REJECTED,

        /** Transport fault, nothing happened. Safe to send the same command again. */
        RETRYABLE,

        /**
         * The partner may or may not have acted. Nothing else may be attempted
         * for this plan until it is reconciled — in particular, no other partner.
         */
        UNCERTAIN
    }

    /**
     * What a booking attempt against one partner produced.
     *
     * @param providerType       the partner that answered, absent when no
     *                           binding could be resolved at all (there is no
     *                           partner to name)
     * @param externalReference the partner's own id, present on a booking or a
     *                          hold and absent otherwise. An {@code UNCERTAIN}
     *                          receipt with no reference is the case only a
     *                          human can resolve
     * @param errorCode          present on a refusal or fault, absent on a
     *                           successful booking or hold
     * @param detail             present alongside {@code errorCode}, absent
     *                           otherwise
     */
    record BookingReceipt(
            BookingStatus status,
            UUID commandId,
            UUID bindingId,
            @Nullable String providerType,
            @Nullable String externalReference,
            @Nullable String errorCode,
            @Nullable String detail) {

        public static BookingReceipt of(
                BookingStatus status,
                BookingCommand command,
                @Nullable String providerType,
                @Nullable String externalReference,
                @Nullable String errorCode,
                @Nullable String detail) {
            return new BookingReceipt(
                    status,
                    command.commandId(),
                    command.bindingId(),
                    providerType,
                    externalReference,
                    errorCode,
                    detail);
        }

        /** Whether this attempt produced something that must not be abandoned silently. */
        public boolean holdsProviderState() {
            return status == BookingStatus.BOOKED || status == BookingStatus.HELD || status == BookingStatus.UNCERTAIN;
        }
    }

    /**
     * Cancels a live booking or hold at the partner (ADR 0014's {@code
     * CancelShipment}, gap map row 1.2g).
     *
     * <p>Never throws for a partner refusal or fault, for the reason {@link
     * #book} never does. {@link CancellationStatus#UNCERTAIN} is the one that
     * matters: a timeout after the cancel reached the partner may or may not
     * have taken, and the caller must not assume either — it belongs in
     * {@code fulfillment.delivery_exceptions}, not in a shipment silently
     * marked cancelled.
     *
     * <p>Defaulted for the same reason {@link #quote} is: a test double built
     * before this method existed, and that never exercises cancellation, must
     * not fail to compile over a capability it does not use.
     */
    default CancellationReceipt cancel(CancelCommand command) {
        return CancellationReceipt.of(CancellationStatus.REJECTED, command, null, NOT_WIRED_REASON, null);
    }

    /**
     * One cancellation attempt against one partner.
     *
     * @param externalReference the partner's own id for the booking being
     *                          cancelled — {@code BookingReceipt.externalReference()}
     *                          from whichever call won the shipment
     * @param reasonCode        a short, non-PII code (ADR 0029) — never a free-text
     *                          note, which a partner's own log might retain
     */
    record CancelCommand(
            UUID commandId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID bindingId,
            String externalReference,
            String reasonCode,
            String correlationId) {

        public CancelCommand {
            Objects.requireNonNull(commandId, "A command id is required");
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(bindingId, "A binding id is required");
            Objects.requireNonNull(externalReference, "An external reference is required");
            Objects.requireNonNull(reasonCode, "A reason code is required");
        }

        /** Names the shipment and nothing about who it was headed to. */
        @Override
        public String toString() {
            return "CancelCommand[commandId=%s, reference=%s]".formatted(commandId, externalReference);
        }
    }

    /**
     * What a cancellation attempt produced.
     *
     * <p>Five rather than a boolean, mirroring {@link BookingStatus}: a partner
     * cancellation can succeed for free, succeed but cost something, be refused
     * (the delivery may already be under way), fault before reaching the
     * partner, or leave the outcome genuinely unknown.
     */
    enum CancellationStatus {

        /** The partner cancelled at no cost. */
        CANCELLED,

        /** The partner cancelled, but this cancellation is chargeable. */
        CANCELLED_WITH_COST,

        /** The partner refused on business grounds. The delivery may already be under way. */
        REJECTED,

        /** Transport fault, nothing happened at the partner. Safe to send again. */
        RETRYABLE,

        /** The partner may or may not have cancelled. Reconcile before anything else. */
        UNCERTAIN
    }

    /**
     * @param providerType present whenever a partner actually answered, absent
     *                     only when no binding could be resolved at all
     * @param errorCode    present on anything but a clean {@code CANCELLED}
     * @param detail       present alongside {@code errorCode}, absent otherwise
     */
    record CancellationReceipt(
            CancellationStatus status,
            UUID commandId,
            UUID bindingId,
            @Nullable String providerType,
            @Nullable String errorCode,
            @Nullable String detail) {

        public static CancellationReceipt of(
                CancellationStatus status,
                CancelCommand command,
                @Nullable String providerType,
                @Nullable String errorCode,
                @Nullable String detail) {
            return new CancellationReceipt(
                    status, command.commandId(), command.bindingId(), providerType, errorCode, detail);
        }
    }

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }

    /** The reason a sourcing decision carries when no partner path exists at all. */
    String NOT_WIRED_REASON = "SHIPMENT_BOOKING_NOT_WIRED";

    /** Recorded on a quote row when the adapter has no quote path at all. */
    String QUOTE_NOT_WIRED = "QUOTE_NOT_WIRED";
}
