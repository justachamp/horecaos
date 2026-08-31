package uz.horecaos.platform.fulfillment.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort.FleetCandidate;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingStatus;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.QuoteOutcome;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryExceptionReason;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.QuoteScoring;
import uz.horecaos.platform.fulfillment.domain.sourcing.QuoteScoring.ScoredPartner;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingPlanner;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingRequest;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * One tick of sourcing for one plan (ADR 0014).
 *
 * <p>Gathers the facts the decision turns on — who is on shift, which partners
 * this branch has, and what those partners say a journey would cost — runs
 * {@link SourcingPlanner}, records the decision, and only then executes the one
 * move that has an external effect. Everything with judgement in it is in the
 * planner and in {@link QuoteScoring} and is a pure function; everything here is
 * ordering and durability, which is the split that lets the fallback timing be
 * tested against a fixed clock without a Camel context or a courier.
 *
 * <h2>How a replayed tick avoids booking twice</h2>
 *
 * <p>Every effect here is externally visible: a courier's phone buzzes, a partner
 * is booked and charged. The scheduler is at-least-once — a lease expires, a pod
 * dies mid-call, a poll overlaps — so the safety of the whole feature rests on
 * three things and nothing else:
 *
 * <ol>
 *   <li><b>The attempt row is committed before the call.</b>
 *       {@link SourcingJournal#openPartnerAttempt} writes it, and its key is the
 *       key the partner sees.</li>
 *   <li><b>That key is derived, never random.</b> {@link #commandId} is a
 *       function of the plan, the binding and how many partners have already
 *       answered, so the same situation always produces the same key and the
 *       partner deduplicates a replay for us.</li>
 *   <li><b>The row is read before the call is made.</b> An attempt found rather
 *       than created is one a previous tick already sent; if it is no longer
 *       {@code REQUESTED} the partner has answered, and this tick asks nobody
 *       anything.</li>
 * </ol>
 *
 * <p>The single-winner rule is not here at all. It is three unique indexes in
 * V0054, and the journal reports whether a write passed them. ADR 0014 rejects a
 * service-side count by name: between the count and the insert is exactly the
 * window in which a second dispatcher books a second courier.
 */
@Service
public class DeliverySourcingService {

    private static final Logger log = LoggerFactory.getLogger(DeliverySourcingService.class);

    /**
     * How long a HorecaOS-imposed quote TTL lasts.
     *
     * <p>Neither verified partner returns an expiry, so this is policy and the
     * quote row records it as {@code HORECAOS_POLICY} rather than as a promise
     * anybody made. Two minutes because that is roughly one sourcing tick plus a
     * booking round-trip: longer and a price is used after the traffic it was
     * quoted in has changed, shorter and every quote is stale before it is scored.
     */
    private static final int QUOTE_TTL_SECONDS = 120;

    private final InternalFleetPort fleet;
    private final ShipmentBookingPort bookings;
    private final SourcingJournal journal;
    private final PolicyResolver policies;
    private final Clock clock;

    public DeliverySourcingService(
            InternalFleetPort fleet,
            ShipmentBookingPort bookings,
            SourcingJournal journal,
            PolicyResolver policies,
            Clock clock) {
        this.fleet = fleet;
        this.bookings = bookings;
        this.journal = journal;
        this.policies = policies;
        this.clock = clock;
    }

    /** The first tick for a plan nothing has been tried for yet. */
    public Outcome source(SourcingRequest request) {
        return source(request, SourcingProgress.starting(clock.instant()));
    }

    public Outcome source(SourcingRequest request, SourcingProgress progress) {
        Instant now = clock.instant();
        ResolvedPolicy<DeliverySourcingPolicy> policy = resolvePolicy(request);

        List<FleetCandidate> candidates = request.mode().usesFleet()
                ? fleet.candidates(
                        request.tenantId(), request.brandId(), request.locationId(), request.distanceMeters())
                : List.of();
        List<PartnerOption> partners = request.mode().usesPartners()
                ? bookings.partners(request.tenantId(), request.brandId(), request.locationId())
                : List.of();

        SourcingDecision decision = SourcingPlanner.decide(
                request.plan(), policy.document(), request.mode(), candidates, partners, progress, now);

        List<ScoredPartner> scored = List.of();
        if (decision instanceof SourcingDecision.BookPartner && partners.size() > 1) {
            // Quoted only once the fleet lane has been conceded, and only when
            // there is a choice to make. A quote is an API call and a partner's
            // patience; spending both on every tick of an order one of our own
            // couriers takes thirty seconds later buys nothing, and asking a
            // single configured partner what it costs cannot change who is booked.
            scored = quoteAndScore(request, partners, progress, now);
            decision = SourcingPlanner.decide(
                    request.plan(),
                    policy.document(),
                    request.mode(),
                    candidates,
                    QuoteScoring.ranked(scored),
                    ineligibleAsAttempted(progress, scored),
                    now);
        }

        return switch (decision) {
            case SourcingDecision.BookPartner book ->
                execute(request, book, progress, policy, quoteFor(scored, book), now);
            case SourcingDecision.OfferInternal offer -> offer(request, offer, progress, policy, now);
            case SourcingDecision.WaitForInternal wait ->
                new Outcome(wait, progress, null, policy.policyId(), policy.policyVersion(), null, false);
            case SourcingDecision.EscalateToOperations escalate -> escalate(request, escalate, progress, policy, now);
        };
    }

    /**
     * A courier taking the offer this plan made him.
     *
     * <p>The whole decision is one statement in the journal, and a false answer is
     * the ordinary outcome for the second of two taps and for an offer that lapsed
     * a second earlier. It is rendered to the courier as "somebody else took it",
     * never as an error, and never as forbidden — the caller acting on his own
     * offer is what authorises this.
     */
    public boolean acceptOffer(UUID tenantId, UUID attemptId, UUID courierId) {
        return journal.acceptOffer(tenantId, attemptId, courierId, clock.instant());
    }

    // ------------------------------------------------------------- the effects

    private Outcome execute(
            SourcingRequest request,
            SourcingDecision.BookPartner decision,
            SourcingProgress progress,
            ResolvedPolicy<DeliverySourcingPolicy> policy,
            @Nullable DeliveryQuote quote,
            Instant now) {

        UUID commandId = commandId(
                request.planId(),
                decision.partner().bindingId(),
                progress.attemptedPartners().size());

        SourcingJournal.OpenAttempt attempt = journal.openPartnerAttempt(new SourcingJournal.PartnerAttempt(
                request.tenantId(),
                request.planId(),
                decision.partner().bindingId(),
                commandId.toString(),
                quote == null ? null : quote.id(),
                decision.reason(),
                policy.policyId(),
                policy.policyVersion(),
                now));

        if (!attempt.needsCall()) {
            // A previous tick already sent this exact command and the partner has
            // answered it. Sending it again would be a second order on any partner
            // whose deduplication is unverified — which is Noor, today.
            log.info(
                    "Plan {} already has attempt {} in {}; no partner is called this tick",
                    request.planId(),
                    attempt.attemptId(),
                    attempt.status());
            return new Outcome(
                    decision, progress, null, policy.policyId(), policy.policyVersion(), attempt.attemptId(), false);
        }

        BookingCommand command = new BookingCommand(
                commandId,
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                decision.partner().bindingId(),
                decision.intent(),
                request.orderReference(),
                request.pickup(),
                request.dropoff(),
                decision.requestedPickupAt(),
                request.prepaid(),
                request.itemValueMinor(),
                request.currency(),
                request.correlationId());

        BookingReceipt receipt = bookings.book(command);
        boolean won = journal.settlePartnerAttempt(request.tenantId(), attempt.attemptId(), receipt, now);

        SourcingProgress next = progress.withPartnerAttempt(
                decision.partner().bindingId(), receipt.status() == BookingStatus.UNCERTAIN);

        log.info(
                "Plan {} booking with {} finished as {} ({})",
                request.planId(),
                decision.partner().providerType(),
                receipt.status(),
                decision.reason());

        if (receipt.status() == BookingStatus.UNCERTAIN || receipt.status() == BookingStatus.HELD) {
            // Nothing else may be attempted for this plan until a human or a query
            // settles what the partner actually did, so the exception exists from
            // the moment the doubt does rather than on the next tick.
            journal.raiseException(
                    request.tenantId(),
                    request.brandId(),
                    request.locationId(),
                    request.planId(),
                    DeliveryExceptionReason.AWAITING_RECONCILIATION,
                    "attempt=" + attempt.attemptId(),
                    now);
        }

        // A RETRYABLE receipt is the one case where the same partner is tried
        // again rather than the next one, and it is safe precisely because the
        // command id is derived rather than fresh: the attempt row is still
        // REQUESTED under the same key, so the partner sees the retry as a retry.
        return new Outcome(
                decision,
                receipt.status() == BookingStatus.RETRYABLE ? progress : next,
                receipt,
                policy.policyId(),
                policy.policyVersion(),
                attempt.attemptId(),
                won);
    }

    private Outcome offer(
            SourcingRequest request,
            SourcingDecision.OfferInternal decision,
            SourcingProgress progress,
            ResolvedPolicy<DeliverySourcingPolicy> policy,
            Instant now) {

        SourcingJournal.OpenAttempt attempt = journal.openInternalOffer(new SourcingJournal.InternalOffer(
                request.tenantId(),
                request.planId(),
                decision.courierId(),
                offerKey(request.planId(), decision.courierId()),
                decision.expiresAt(),
                decision.reason(),
                policy.policyId(),
                policy.policyVersion(),
                now));

        log.info(
                "Plan {} offered courier {} until {} ({})",
                request.planId(),
                decision.courierId(),
                decision.expiresAt(),
                decision.reason());

        return new Outcome(
                decision,
                progress.withOffer(decision.courierId(), decision.expiresAt()),
                null,
                policy.policyId(),
                policy.policyVersion(),
                attempt.attemptId(),
                false);
    }

    private Outcome escalate(
            SourcingRequest request,
            SourcingDecision.EscalateToOperations decision,
            SourcingProgress progress,
            ResolvedPolicy<DeliverySourcingPolicy> policy,
            Instant now) {

        journal.raiseException(
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                request.planId(),
                DeliveryExceptionReason.forDecision(decision.reason()),
                decision.reason(),
                now);

        log.warn("Plan {} needs manual action: {}", request.planId(), decision.reason());
        return new Outcome(decision, progress, null, policy.policyId(), policy.policyVersion(), null, false);
    }

    // -------------------------------------------------------------- the quotes

    /**
     * Asks every partner still in play what this journey would cost.
     *
     * <p>Side-effect-free by the port's contract, which is what lets ADR 0014
     * quote in parallel while forbidding it to create with more than one. An
     * adapter with no quote path answers {@code QUOTE_NOT_WIRED} and produces no
     * row at all: a refusal row would say the partner declined this plan, and
     * scoring would then step past a partner that was never asked.
     */
    private List<ScoredPartner> quoteAndScore(
            SourcingRequest request, List<PartnerOption> partners, SourcingProgress progress, Instant now) {

        List<DeliveryQuote> quotes = new ArrayList<>();
        for (PartnerOption partner : partners) {
            if (progress.attemptedPartners().contains(partner.bindingId())) {
                continue;
            }
            UUID requestId = quoteId(
                    request.planId(),
                    partner.bindingId(),
                    progress.attemptedPartners().size());
            QuoteOutcome outcome = bookings.quote(quoteCommand(request, partner, requestId));
            if (!outcome.hasPrice() && ShipmentBookingPort.QUOTE_NOT_WIRED.equals(outcome.failureCode())) {
                continue;
            }
            quotes.add(new DeliveryQuote(
                    UUID.randomUUID(),
                    partner.bindingId(),
                    partner.providerType(),
                    requestId,
                    outcome.priceMinor(),
                    outcome.currency(),
                    outcome.pickupEtaSeconds(),
                    outcome.deliveryEtaSeconds(),
                    outcome.distanceMeters(),
                    outcome.deadHeadMeters(),
                    outcome.expiresAt() != null ? outcome.expiresAt() : now.plusSeconds(QUOTE_TTL_SECONDS),
                    outcome.partnerSuppliedExpiry(),
                    outcome.failureCode(),
                    now));
        }

        if (!quotes.isEmpty()) {
            journal.recordQuotes(request.tenantId(), request.planId(), quotes);
        }
        return QuoteScoring.rank(partners, quotes, request.plan(), now);
    }

    private static BookingCommand quoteCommand(SourcingRequest request, PartnerOption partner, UUID requestId) {
        return new BookingCommand(
                requestId,
                request.tenantId(),
                request.brandId(),
                request.locationId(),
                partner.bindingId(),
                ShipmentBookingPort.BookingIntent.BOOK_NOW,
                request.orderReference(),
                request.pickup(),
                request.dropoff(),
                null,
                request.prepaid(),
                request.itemValueMinor(),
                request.currency(),
                request.correlationId());
    }

    /**
     * A partner that cannot serve this plan, told to the planner as one already
     * tried.
     *
     * <p>Local to this tick and never persisted. A partner out of couriers now may
     * have one in three minutes, so the next tick quotes it again — but the
     * decision being made <em>now</em> must walk past it, and the planner's own
     * vocabulary for "this one is not available" is the attempted set. It is also
     * what makes the escalation read {@code PARTNERS_EXHAUSTED} rather than
     * {@code NO_PARTNER_CONFIGURED} when a branch has partners that all refused.
     */
    private static SourcingProgress ineligibleAsAttempted(SourcingProgress progress, List<ScoredPartner> scored) {

        SourcingProgress augmented = progress;
        for (ScoredPartner candidate : scored) {
            if (!candidate.eligible()) {
                augmented = augmented.withPartnerAttempt(candidate.partner().bindingId(), false);
            }
        }
        return augmented;
    }

    /**
     * The quote the winning partner was chosen on, or null when it was not quoted.
     *
     * <p>A loop rather than a stream: the value being looked for is legitimately
     * null — an unquoted partner is still bookable — and {@code findFirst} on a
     * null element throws rather than answering "none".
     */
    private static @Nullable DeliveryQuote quoteFor(List<ScoredPartner> scored, SourcingDecision.BookPartner decision) {
        for (ScoredPartner candidate : scored) {
            if (candidate.partner().bindingId().equals(decision.partner().bindingId())) {
                return candidate.quote();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- keys

    /**
     * The partner idempotency key, derived rather than random.
     *
     * <p>{@code DeliveryOperation} says a retry must reuse the exact command
     * rather than build an equivalent one, and this is what makes that true across
     * a process restart: the same plan, the same binding and the same number of
     * partners already answered always produce the same key, so a partner that
     * deduplicates sees the retry as the retry it is instead of as a second order.
     * It is also the {@code assignment_attempts.idempotency_key} the row is
     * written under, which is what makes the replay find its own attempt.
     */
    static UUID commandId(UUID planId, UUID bindingId, int attempt) {
        return UUID.nameUUIDFromBytes("horecaos.delivery-attempt:%s:%s:%d"
                .formatted(planId, bindingId, attempt)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The quote's own request id, derived for the same reason and distinct from
     * the booking's: {@code uq_quote_request} is keyed on it, so a replayed tick
     * re-records the answer it already had instead of accumulating a row per
     * crash.
     */
    static UUID quoteId(UUID planId, UUID bindingId, int round) {
        return UUID.nameUUIDFromBytes("horecaos.delivery-quote:%s:%s:%d"
                .formatted(planId, bindingId, round)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The offer's key. Plan and courier alone, with no round in it: a courier who
     * declined is never asked again for the same plan, so a second row under the
     * same pair would be a bug rather than a retry.
     */
    static String offerKey(UUID planId, UUID courierId) {
        return "horecaos.delivery-offer:%s:%s".formatted(planId, courierId);
    }

    private ResolvedPolicy<DeliverySourcingPolicy> resolvePolicy(SourcingRequest request) {
        ResourceScope scope = ResourceScope.location(request.tenantId(), request.brandId(), request.locationId());
        Optional<ResolvedPolicy<DeliverySourcingPolicy>> resolved =
                policies.resolve(DeliverySourcingPolicies.SOURCING, scope);
        return resolved.orElseGet(() -> new ResolvedPolicy<>(
                DeliverySourcingPolicies.SOURCING.code(),
                DEFAULTS_ID,
                1,
                scope.type(),
                "defaults",
                DeliverySourcingPolicy.DEFAULTS));
    }

    /**
     * A stable identifier for "nothing was configured, ADR 0014's provisional
     * timings applied", matching {@code CourierPolicyResolver.DEFAULTS_ID}. A
     * random id per call would make two identical decisions look as though they
     * ran under two different policies.
     */
    public static final UUID DEFAULTS_ID = UUID.fromString("00000000-0000-0000-0000-000000000014");

    /**
     * What one sourcing tick decided and did.
     *
     * @param progress what the next tick would see if it did not read the attempt
     *                 rows. It does read them — this is here so a caller holding
     *                 one tick in its hand can assert on what changed
     * @param receipt  present only when a partner was actually called
     * @param attemptId the durable row this tick wrote, or null when it wrote none
     * @param won      whether this tick produced the plan's single active
     *                 shipment. False after a booking means the compare-and-set
     *                 was lost and somebody else is carrying this order
     */
    public record Outcome(
            SourcingDecision decision,
            SourcingProgress progress,
            @Nullable BookingReceipt receipt,
            UUID policyId,
            int policyVersion,
            @Nullable UUID attemptId,
            boolean won) {

        public boolean assigned() {
            return won;
        }
    }
}
