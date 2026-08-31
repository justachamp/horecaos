package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.application.SourcingJournal;
import uz.horecaos.platform.fulfillment.domain.sourcing.AttemptStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourceType;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;

/**
 * {@link SourcingJournal} over V0054 (ADR 0014).
 *
 * <p>Thin on purpose. Every decision this class could make is one the database
 * already makes better — which attempt won, whether a plan already has a
 * shipment, whether an exception is already open — so what is left here is the
 * translation from a booking receipt to the attempt state it implies, and that
 * translation is the only judgement in the file.
 */
@Component
public class JdbcSourcingJournal implements SourcingJournal {

    private static final Logger log = LoggerFactory.getLogger(JdbcSourcingJournal.class);

    /** {@code failure_code} is varchar(48) and a partner's code can be longer. */
    private static final int MAX_FAILURE_CODE = 48;

    /** Who the exception row says raised it. Never a person; this is a job. */
    private static final String RAISED_BY = "delivery-sourcing";

    /** A hold exists at the partner and the promotion to a live booking did not. */
    static final String HOLD_NOT_CONFIRMED = "PARTNER_HOLD_NOT_CONFIRMED";

    static final String PARTNER_REJECTED = "PARTNER_REJECTED";

    static final String PARTNER_UNCERTAIN = "PARTNER_UNCERTAIN";

    private final JdbcAssignmentStore assignments;
    private final JdbcDeliveryQuoteStore quotes;
    private final JdbcDeliveryExceptionStore exceptions;

    public JdbcSourcingJournal(
            JdbcAssignmentStore assignments, JdbcDeliveryQuoteStore quotes, JdbcDeliveryExceptionStore exceptions) {
        this.assignments = assignments;
        this.quotes = quotes;
        this.exceptions = exceptions;
    }

    @Override
    public SourcingProgress progress(UUID tenantId, UUID planId, Instant startedAt) {
        return assignments.progress(tenantId, planId, startedAt);
    }

    @Override
    public OpenAttempt openPartnerAttempt(PartnerAttempt attempt) {
        var opened = assignments.open(new JdbcAssignmentStore.NewAttempt(
                attempt.tenantId(),
                attempt.planId(),
                SourceType.PARTNER,
                AttemptStatus.REQUESTED,
                null,
                attempt.bindingId(),
                attempt.quoteId(),
                attempt.idempotencyKey(),
                attempt.decisionReason(),
                attempt.policyId(),
                version(attempt.policyId(), attempt.policyVersion()),
                null,
                attempt.now()));

        return new OpenAttempt(opened.attemptId(), opened.sequenceNumber(), opened.status(), opened.fresh());
    }

    @Override
    public OpenAttempt openInternalOffer(InternalOffer offer) {
        var opened = assignments.open(new JdbcAssignmentStore.NewAttempt(
                offer.tenantId(),
                offer.planId(),
                SourceType.INTERNAL,
                AttemptStatus.OFFERED,
                offer.courierId(),
                null,
                null,
                offer.idempotencyKey(),
                offer.decisionReason(),
                offer.policyId(),
                version(offer.policyId(), offer.policyVersion()),
                offer.expiresAt(),
                offer.now()));

        return new OpenAttempt(opened.attemptId(), opened.sequenceNumber(), opened.status(), opened.fresh());
    }

    @Override
    public boolean settlePartnerAttempt(UUID tenantId, UUID attemptId, BookingReceipt receipt, Instant now) {

        return switch (receipt.status()) {
            case BOOKED -> {
                Optional<UUID> shipment = assignments.win(new JdbcAssignmentStore.WinningAttempt(
                        tenantId,
                        attemptId,
                        SourceType.PARTNER,
                        AttemptStatus.REQUESTED,
                        receipt.providerType(),
                        receipt.externalReference(),
                        now));
                if (shipment.isEmpty()) {
                    // The plan already has a shipment. The booking that just
                    // succeeded is therefore a second courier, and it exists at the
                    // partner whatever this row says — so the attempt is closed as
                    // uncertain and an operator is told to cancel it, rather than
                    // being quietly dropped.
                    log.warn(
                            "Plan attempt {} booked a partner that lost the single-winner "
                                    + "compare-and-set; the booking must be cancelled",
                            attemptId);
                    assignments.close(
                            tenantId,
                            attemptId,
                            AttemptStatus.UNCERTAIN,
                            HOLD_NOT_CONFIRMED,
                            receipt.externalReference(),
                            true,
                            now);
                }
                yield shipment.isPresent();
            }
            case HELD -> {
                // A hold that nothing promoted. ADR 0014: an abandoned hold is an
                // operational exception, not a no-op, and nothing else may be
                // booked for this plan while it stands.
                assignments.close(
                        tenantId,
                        attemptId,
                        AttemptStatus.UNCERTAIN,
                        HOLD_NOT_CONFIRMED,
                        receipt.externalReference(),
                        true,
                        now);
                yield false;
            }
            case REJECTED -> {
                assignments.close(
                        tenantId,
                        attemptId,
                        AttemptStatus.FAILED,
                        code(receipt.errorCode(), PARTNER_REJECTED),
                        null,
                        false,
                        now);
                yield false;
            }
            case RETRYABLE ->
                // Left REQUESTED on purpose. The partner refused nothing, and the
                // next tick reuses this row's idempotency key so the partner sees
                // the retry as the retry it is rather than as a second order.
                false;
            case UNCERTAIN -> {
                assignments.close(
                        tenantId,
                        attemptId,
                        AttemptStatus.UNCERTAIN,
                        code(receipt.errorCode(), PARTNER_UNCERTAIN),
                        receipt.externalReference(),
                        true,
                        now);
                yield false;
            }
        };
    }

    @Override
    public boolean acceptOffer(UUID tenantId, UUID attemptId, UUID courierId, Instant now) {
        return assignments.acceptOffer(tenantId, attemptId, courierId, now).isPresent();
    }

    @Override
    public Optional<UUID> assignedShipment(UUID tenantId, UUID planId) {
        return assignments.findShipment(tenantId, planId).map(JdbcAssignmentStore.Shipment::id);
    }

    @Override
    public int expireLapsedOffers(UUID tenantId, UUID planId, Instant now) {
        return assignments.expireLapsedOffers(tenantId, planId, now);
    }

    @Override
    public void recordQuotes(UUID tenantId, UUID planId, List<DeliveryQuote> quotes) {
        this.quotes.insertAll(tenantId, planId, quotes);
    }

    @Override
    public void raiseException(
            UUID tenantId, UUID brandId, UUID locationId, UUID planId, String reasonCode, String detail, Instant now) {

        if (exceptions.raise(tenantId, brandId, locationId, planId, reasonCode, detail, RAISED_BY, now)) {
            log.warn("Delivery plan {} needs manual action: {}", planId, reasonCode);
        }
    }

    /**
     * A policy version only where there is a policy to version.
     *
     * <p>{@code ck_attempt_policy_pair} refuses one without the other, and a
     * version standing alone would claim a decision was pinned to something the
     * row cannot name.
     */
    private static @Nullable Integer version(@Nullable UUID policyId, int policyVersion) {
        return policyId == null ? null : policyVersion;
    }

    private static String code(@Nullable String provided, String fallback) {
        String value = provided == null || provided.isBlank() ? fallback : provided;
        return value.substring(0, Math.min(value.length(), MAX_FAILURE_CODE));
    }
}
