package uz.horecaos.platform.integration.api.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * A courier partner, behind one provider-neutral contract (ADR 0014).
 *
 * <p>An adapter declares only what its partner actually does. Ordering asks for
 * a capability and gets a partner that has it, or none — it never asks "is this
 * Yandex?", which is the coupling that makes every new partner expensive.
 */
public interface DeliveryPartner {

    /** Matches the ADR 0026 installation's {@code provider_type}. */
    String providerType();

    Set<DeliveryCapability> capabilities();

    default boolean supports(DeliveryCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * Non-binding price and ETA. Safe to call against several partners at once,
     * because a quote creates nothing.
     */
    ProviderOutcome quote(DeliveryRequest request, ProviderCall call);

    /**
     * Creates a booking, or a hold where the partner distinguishes the two.
     *
     * <p>The distinction is load-bearing: on a partner without
     * {@link DeliveryCapability#RESERVE_SHIPMENT}, this call books a courier and
     * must never be made speculatively.
     */
    ProviderOutcome createShipment(DeliveryRequest request, ProviderCall call);

    /** Promotes a hold. Only meaningful where the partner supports holds. */
    ProviderOutcome confirmShipment(String externalReference, ProviderCall call);

    /**
     * Reports what cancelling would cost.
     *
     * <p>Where the partner cannot say, the outcome is {@code UNCERTAIN} rather
     * than an assumed zero: guessing "free" is how a cancellation fee arrives
     * unexplained.
     */
    ProviderOutcome cancellationCost(String externalReference, ProviderCall call);

    ProviderOutcome cancelShipment(String externalReference, String reason, ProviderCall call);

    /**
     * Reads current state. This is the reconciliation path after an uncertain
     * outcome, so it must be safe to call at any time and must never mutate.
     */
    ProviderOutcome queryShipment(String externalReference, ProviderCall call);

    /**
     * One provider-neutral delivery request, translated from the tenant's
     * booking intent.
     *
     * @param prepaid whether HorecaOS already took payment. Some partners will
     *                otherwise collect from the recipient, charging twice.
     * @param requestedPickupAt null for as-soon-as-possible
     */
    record DeliveryRequest(
            String horecaosReference,
            Pickup pickup,
            Dropoff dropoff,
            @Nullable Instant requestedPickupAt,
            boolean prepaid,
            long itemValueMinor,
            String currency,
            Map<String, Object> partnerOptions) {

        /**
         * Names the order and nothing about the people on either end of it.
         *
         * <p>{@code partnerOptions} is left out with them: it is free-form
         * per-partner configuration, and at least one partner takes a delivery
         * comment through it.
         */
        @Override
        public String toString() {
            return "DeliveryRequest[horecaosReference=%s, requestedPickupAt=%s, prepaid=%s, currency=%s]"
                    .formatted(horecaosReference, requestedPickupAt, prepaid, currency);
        }
    }

    record Pickup(
            double latitude,
            double longitude,
            String address,
            @Nullable String contactName,
            @Nullable String contactPhone,
            @Nullable String comment) {

        /**
         * Every component here is personal data — a precise location is as
         * identifying as the address beside it — and a record's generated
         * {@code toString} prints all of them, so one interpolated log line or
         * one exception message puts a branch address and a contact's phone
         * number into a log this platform's rules keep clear of both.
         */
        @Override
        public String toString() {
            return "Pickup[REDACTED]";
        }
    }

    record Dropoff(
            double latitude,
            double longitude,
            String address,
            @Nullable String contactName,
            @Nullable String contactPhone,
            @Nullable String comment,
            @Nullable String entrance,
            @Nullable String floor,
            @Nullable String apartment) {

        /** As {@link Pickup#toString()}, and this one is the customer's home. */
        @Override
        public String toString() {
            return "Dropoff[REDACTED]";
        }
    }

    /**
     * Everything the transport needs that is not part of the business request.
     *
     * @param idempotencyKey stable across retries of one logical operation, or null for a
     *                       call with no idempotency semantics (e.g. Telegram's getUpdates poll)
     */
    record ProviderCall(
            String baseUrl, String credential, @Nullable String idempotencyKey, Duration timeout) {

        /**
         * The credential is a live partner token. A generated {@code toString}
         * prints it, which is one interpolated log line away from a credential in
         * the log aggregator, and the idempotency key and base URL are what an
         * operator actually needs to trace the call.
         */
        @Override
        public String toString() {
            return "ProviderCall[baseUrl=%s, idempotencyKey=%s, timeout=%s, credential=REDACTED]"
                    .formatted(baseUrl, idempotencyKey, timeout);
        }
    }
}
