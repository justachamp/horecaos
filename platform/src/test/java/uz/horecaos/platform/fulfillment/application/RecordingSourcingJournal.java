package uz.horecaos.platform.fulfillment.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.AttemptStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;

/**
 * The journal without a database, for the tests that are about ordering rather
 * than about the indexes.
 *
 * <p>It deliberately does <em>not</em> reproduce the single-winner rule. That
 * rule is three unique indexes in V0054 and a fake that imitated them would let a
 * test pass while the real statement was wrong — which is the whole reason
 * {@code DeliverySourcingPersistenceTests} runs against a real PostgreSQL. What
 * this reproduces is the one property the service depends on: an attempt opened
 * twice under the same idempotency key is the same attempt.
 */
class RecordingSourcingJournal implements SourcingJournal {

    private final Map<String, Attempt> byKey = new LinkedHashMap<>();
    final List<PartnerAttempt> partnerAttempts = new ArrayList<>();
    final List<InternalOffer> offers = new ArrayList<>();
    final List<String> exceptions = new ArrayList<>();
    final List<DeliveryQuote> quotes = new ArrayList<>();

    @Override
    public SourcingProgress progress(UUID tenantId, UUID planId, Instant startedAt) {
        return SourcingProgress.starting(startedAt);
    }

    @Override
    public OpenAttempt openPartnerAttempt(PartnerAttempt attempt) {
        partnerAttempts.add(attempt);
        return open(attempt.idempotencyKey(), AttemptStatus.REQUESTED);
    }

    @Override
    public OpenAttempt openInternalOffer(InternalOffer offer) {
        offers.add(offer);
        return open(offer.idempotencyKey(), AttemptStatus.OFFERED);
    }

    @Override
    public boolean settlePartnerAttempt(UUID tenantId, UUID attemptId, BookingReceipt receipt, Instant now) {
        Attempt attempt = byId(attemptId);
        attempt.status = switch (receipt.status()) {
            case BOOKED -> AttemptStatus.ACCEPTED;
            case HELD, UNCERTAIN -> AttemptStatus.UNCERTAIN;
            case REJECTED -> AttemptStatus.FAILED;
            case RETRYABLE -> AttemptStatus.REQUESTED;
        };
        return receipt.status() == BookingStatus.BOOKED;
    }

    @Override
    public boolean acceptOffer(UUID tenantId, UUID attemptId, UUID courierId, Instant now) {
        Attempt attempt = byId(attemptId);
        if (attempt.status != AttemptStatus.OFFERED) {
            return false;
        }
        attempt.status = AttemptStatus.ACCEPTED;
        return true;
    }

    @Override
    public java.util.Optional<UUID> assignedShipment(UUID tenantId, UUID planId) {
        return java.util.Optional.empty();
    }

    @Override
    public int expireLapsedOffers(UUID tenantId, UUID planId, Instant now) {
        return 0;
    }

    @Override
    public void recordQuotes(UUID tenantId, UUID planId, List<DeliveryQuote> recorded) {
        quotes.addAll(recorded);
    }

    @Override
    public void raiseException(
            UUID tenantId, UUID brandId, UUID locationId, UUID planId, String reasonCode, String detail, Instant now) {
        exceptions.add(reasonCode);
    }

    private OpenAttempt open(String key, AttemptStatus initial) {
        Attempt existing = byKey.get(key);
        if (existing != null) {
            return new OpenAttempt(existing.id, existing.sequence, existing.status, false);
        }
        Attempt attempt = new Attempt(UUID.randomUUID(), byKey.size() + 1, initial);
        byKey.put(key, attempt);
        return new OpenAttempt(attempt.id, attempt.sequence, attempt.status, true);
    }

    private Attempt byId(UUID attemptId) {
        return byKey.values().stream()
                .filter(candidate -> candidate.id.equals(attemptId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No attempt " + attemptId));
    }

    private static final class Attempt {

        private final UUID id;
        private final int sequence;
        private AttemptStatus status;

        private Attempt(UUID id, int sequence, AttemptStatus status) {
            this.id = id;
            this.sequence = sequence;
            this.status = status;
        }
    }
}
