package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;
import uz.horecaos.platform.ordering.api.PaymentCaptured;
import uz.horecaos.platform.ordering.api.PaymentFailed;
import uz.horecaos.platform.ordering.api.PaymentRefunded;
import uz.horecaos.platform.ordering.api.PaymentVoided;
import uz.horecaos.platform.payments.api.PaymentAttemptFailed;
import uz.horecaos.platform.payments.api.PaymentAttemptNeedsOperator;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStateMachine;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentTransaction;
import uz.horecaos.platform.payments.domain.PaymentTransactionType;
import uz.horecaos.platform.payments.domain.PresentationFailure;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderEvidence;
import uz.horecaos.platform.payments.domain.ProviderInvoice;
import uz.horecaos.platform.payments.domain.ProviderOutcome;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.domain.UncertaintyResolver;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentTransactionStore;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The attempt lifecycle, and the place uncertainty is handled (ADR 0013).
 *
 * <p>Everything here is arranged around one rule: <strong>a lost response is never
 * retried.</strong> Click's MERCHANT API carries no idempotency key on any call,
 * and {@code card_token/payment} is the worst case — it moves money and offers
 * nothing to key on — so a retry after a timeout is a second charge on a
 * customer's card. Instead the attempt goes to {@code UNCERTAIN} with a named
 * resolver and a deadline, the intent is blocked from acquiring a second attempt
 * by a partial unique index, and the resolver is what settles it.
 *
 * <p>Payme's shape is the mirror image and needs no polling: Payme is the client,
 * it repeats every mutating call with identical parameters when a response is
 * lost, and the sandbox requires the second response to match the first. HorecaOS's
 * obligation there is idempotency, which is carried by the transaction store's
 * {@code ON CONFLICT DO NOTHING} plus deriving the replay answer from the
 * persisted attempt state rather than from a stored response body.
 */
@Service
public class PaymentAttemptService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAttemptService.class);

    /**
     * How long an uncertain outcome may stay automated before a human owns it.
     *
     * <p>Not a provider constant. Click publishes no retry schedule and Payme
     * publishes no response timeout, so this is HorecaOS's own patience, and its only
     * job is to make sure an uncertain attempt never sits unowned.
     */
    private static final Duration UNCERTAINTY_DEADLINE = Duration.ofHours(2);

    /**
     * Payme's transaction timeout: 12 hours, Payme's own 43,200,000 milliseconds.
     *
     * <p>Measured from Payme's {@code params.time} and never from HorecaOS's own
     * creation time. Click imposes no expiry at all, so on Click the equivalent is
     * HorecaOS's reservation timeout and the provider is never told.
     */
    public static final Duration PAYME_TRANSACTION_TIMEOUT = Duration.ofHours(12);

    /**
     * What a settlement records as having settled a tender on a capture.
     *
     * <p>A rule of the platform's, not a person: nobody pressed anything, a
     * provider reported money. ADR 0029 keeps the customer out of it.
     */
    private static final String CAPTURE_ACTOR = "payment-capture";

    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final JdbcPaymentTransactionStore transactions;
    private final PaymentBindingResolver bindings;
    private final Map<uz.horecaos.platform.payments.domain.PaymentProviderType, PaymentProviderPort> providers;
    private final CapturedMoneyPort captures;
    private final TransactionTemplate unitOfWork;
    private final TransactionTemplate independently;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // The template is for the two methods that call a provider. Their database
    // work has to be one transaction and the provider call has to be outside it,
    // and @Transactional cannot express that from inside a single bean: a method
    // calling its own annotated method skips the proxy entirely.
    public PaymentAttemptService(
            JdbcPaymentIntentStore intents,
            JdbcPaymentAttemptStore attempts,
            JdbcPaymentTransactionStore transactions,
            PaymentBindingResolver bindings,
            List<PaymentProviderPort> providerPorts,
            CapturedMoneyPort captures,
            TransactionTemplate unitOfWork,
            ApplicationEventPublisher events,
            Clock clock) {
        this.intents = intents;
        this.attempts = attempts;
        this.transactions = transactions;
        this.bindings = bindings;
        this.captures = captures;
        this.unitOfWork = unitOfWork;
        this.events = events;
        // A second template for the one write that has to survive the exception
        // it accompanies. present() records uncertainty and then throws; a caller
        // that happened to hold a transaction of its own would otherwise roll
        // that record back with the exception, leaving a possibly-created invoice
        // with nothing named to resolve it.
        this.independently = new TransactionTemplate(Objects.requireNonNull(
                unitOfWork.getTransactionManager(), "unitOfWork must already carry a transaction manager"));
        this.independently.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.providers =
                providerPorts.stream().collect(Collectors.toMap(PaymentProviderPort::providerType, port -> port));
        this.clock = clock;
    }

    /**
     * Opens an attempt and commits it, before anything is asked of a provider.
     *
     * <p>The order is the whole safety property. {@code merchantTransId} and
     * {@code businessDate} are what an uncertain outcome is resolved with, so an
     * attempt that has not been committed cannot be asked about — and a charge
     * nobody can ask about is a charge that can only be found in a settlement file
     * a day later.
     *
     * @throws IllegalStateException when another attempt against this intent
     *                               already holds money or a question about money.
     *                               Resolve that one; do not start a second
     */
    @Transactional
    public PaymentAttempt open(PaymentIntent intent, ProviderBinding binding, LocalDate businessDate) {
        // ADR 0024. An attempt is the row that says money is being asked for; the
        // presentation that follows is what the customer sees. Neither has a
        // meaning for an order that was paid and settled years ago.
        ImportSuppression.refuse(ExternalEffect.PAYMENT_COLLECTION, "open a payment attempt");

        // Every non-terminal attempt, not only the ones holding money. An abandoned
        // PRESENTED checkout is still a payable link in somebody's browser history,
        // and opening a second attempt beside it would put two payable links against
        // one intent — which is the outbound shape of the same double charge the
        // index prevents inbound. A customer coming back is re-presented, not
        // re-opened; see PaymentCheckoutService.
        Optional<PaymentAttempt> open = attempts.findOpenForIntent(intent.tenantId(), intent.id());
        if (open.isPresent()) {
            throw new IllegalStateException("Intent " + intent.id() + " already has an attempt in "
                    + open.get().status() + "; resolve or re-present it rather than "
                    + "charging again");
        }

        Instant now = clock.instant();
        PaymentAttempt attempt = new PaymentAttempt(
                UUID.randomUUID(),
                intent.tenantId(),
                intent.id(),
                binding.providerType(),
                binding.bindingId(),
                mintMerchantTransId(),
                businessDate,
                null,
                null,
                intent.amount(),
                PaymentAttemptStatus.INITIATED,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                now,
                null);

        try {
            attempts.insert(attempt);
        } catch (DuplicateKeyException raced) {
            // The index, not this method, is what settles a race. Two concurrent
            // CreateTransaction calls for one order is exactly the case the read
            // above cannot cover on its own.
            throw new IllegalStateException("Another attempt against intent " + intent.id() + " won the race", raced);
        }

        intents.transition(
                intent.tenantId(),
                intent.id(),
                intent.status(),
                PaymentIntentStatus.AUTHORIZING,
                intent.version(),
                now);

        return attempt;
    }

    /**
     * Presents the payment and records what the customer was shown.
     *
     * <p>No money moves. Both providers' checkout surfaces are unauthenticated — a
     * Click payment link takes an arbitrary amount from anyone, and Payme's base64
     * link is the same — so a presentation proves nothing and the authoritative
     * signal always arrives inbound.
     *
     * <p>The state moves only on the first presentation, and only from
     * {@code INITIATED}. A customer who abandons a checkout and returns is
     * re-presented from {@code PRESENTED}, and one who came back while the provider
     * holds a reservation is re-presented from {@code RESERVED}; neither is a
     * transition, and forcing one would be either an illegal move or a rewrite of
     * a reservation the provider is holding.
     *
     * <p>The one presentation that can fail in a way that matters is Click's
     * invoice push. It is a mutating MERCHANT API call with no idempotency key, so
     * a lost answer marks the attempt uncertain and is never sent again — and this
     * is the reason the attempt is committed, with its {@code merchant_trans_id}
     * and its business date, in a transaction that closed before this method was
     * called.
     *
     * @throws PresentationFailure.Refused   nothing happened and the attempt is
     *                                       untouched; it may be presented again
     * @throws PresentationFailure.Uncertain a mutating presentation's answer was
     *                                       lost. The attempt is now
     *                                       {@code UNCERTAIN} and belongs to the
     *                                       resolver
     */
    // Not @Transactional, and that replaces a noRollbackFor that used to say the
    // same thing more expensively. createInvoice below is an HTTP call to Click
    // or Payme; a transaction spanning it would hold one of ten pooled
    // connections for as long as the provider took to answer, so ten customers
    // reaching checkout during a Click outage would own every connection the
    // application has. Nothing before the call touches the database, and the two
    // writes after it open their own transaction below.
    public Optional<ProviderInvoice> present(
            PaymentAttempt attempt, ProviderBinding binding, PresentationRequest request) {
        // ADR 0024. createInvoice below is an outbound call to Click or Payme. The
        // empty Optional this method already returns means "no provider port", and
        // reusing it for an import would say the provider is unconfigured when it
        // is configured and was deliberately not called.
        ImportSuppression.refuse(ExternalEffect.PAYMENT_COLLECTION, "present a payment attempt");

        PaymentProviderPort provider = providers.get(binding.providerType());
        if (provider == null) {
            log.warn("No provider port for {}; attempt {} cannot be presented.", binding.providerType(), attempt.id());
            return Optional.empty();
        }

        ProviderInvoice invoice;
        try {
            invoice = provider.createInvoice(attempt, binding, request);
        } catch (PresentationFailure.Uncertain lost) {
            // Committed before the exception leaves, so the question about money
            // is as durable as the row that can be used to ask it. A caller that
            // saw the exception and then failed to record it would leave a
            // possible charge with no resolver.
            independently.executeWithoutResult(ignored -> markUncertain(attempt, lost.failureCode()));
            throw lost;
        }

        // One transaction for both writes. Split, a crash between them would
        // leave a presentation recorded against an attempt still reading
        // INITIATED — which re-presents by re-sending an invoice/create that
        // carries no idempotency key.
        unitOfWork.executeWithoutResult(ignored -> {
            Instant now = clock.instant();
            attempts.recordPresentation(
                    attempt.tenantId(),
                    attempt.id(),
                    invoice.presentationKind(),
                    invoice.externalInvoiceId(),
                    invoice.expiresAt(),
                    now);
            if (attempt.status() == PaymentAttemptStatus.INITIATED) {
                attempts.transition(
                        attempt.tenantId(),
                        attempt.id(),
                        PaymentAttemptStatus.INITIATED,
                        PaymentAttemptStatus.PRESENTED,
                        null,
                        null,
                        null,
                        null,
                        now);
            }
        });
        return Optional.of(invoice);
    }

    /**
     * Records a provider-reported event and moves the attempt with it.
     *
     * <p>One method for reserve, capture, cancel, expire and reverse, because they
     * differ only in which state they land on and which transaction type they
     * append. What they share is the part that matters: the append is idempotent
     * on {@code (attempt, type, provider reference)}, and the state change is
     * conditional on the state it expects — so a replayed Click Complete and a
     * repeated Payme {@code PerformTransaction} both write nothing and both leave
     * the caller able to answer from the persisted state.
     *
     * <p>The transaction and the state change are in one database transaction on
     * purpose. A crash between them is the one place Payme uncertainty genuinely
     * lives: Payme's retry would hit the {@code state == 2} branch and cheerfully
     * report success against an order the platform never credited.
     *
     * @return true when this call was the one that recorded the event
     */
    @Transactional
    public boolean recordProviderEvent(
            PaymentAttempt attempt,
            PaymentTransactionType type,
            PaymentAttemptStatus to,
            SomAmount amount,
            @Nullable String providerReference,
            @Nullable ProviderEvidence evidence,
            @Nullable String externalPaymentId,
            @Nullable String externalDocumentId,
            Instant occurredAt,
            @Nullable String protectedRequestReference,
            @Nullable String protectedResponseReference) {
        PaymentAttemptStateMachine.require(attempt.status(), to);

        Instant now = clock.instant();
        UUID transactionId = UUID.randomUUID();
        boolean appended = transactions.append(new PaymentTransaction(
                transactionId,
                attempt.tenantId(),
                attempt.intentId(),
                attempt.id(),
                type,
                amount,
                providerReference == null ? PaymentTransaction.localReference(transactionId) : providerReference,
                evidence,
                occurredAt,
                now,
                protectedRequestReference,
                protectedResponseReference));

        if (!appended) {
            // A replay. The attempt is already where this event would have put it,
            // and rewriting it would be how a re-delivered cancel overwrites a
            // cancel time or rewrites a Payme -2 back to a -1.
            return false;
        }

        attempts.transition(
                attempt.tenantId(),
                attempt.id(),
                attempt.status(),
                to,
                evidence,
                externalPaymentId,
                externalDocumentId,
                null,
                now);

        // null: this method's own transition call above never carries a
        // failure code either (see the literal null two lines up) — reserve,
        // capture, cancel, expire and reverse are this method's whole domain,
        // per its own Javadoc, and none of them is a decline.
        applyToIntent(attempt, to, null, now);
        return true;
    }

    /**
     * Records that a provider call's outcome is unknown.
     *
     * <p>Called for a timeout, a 500, a 502 or a transport failure on a
     * <em>mutating</em> call. Never for a 4xx: those are configuration or
     * programming errors and will fail identically, so they are terminal. And never
     * as a step towards a retry — the resolver is the only thing that may follow
     * this.
     */
    @Transactional
    public void markUncertain(PaymentAttempt attempt, @Nullable String failureCode) {
        Instant now = clock.instant();
        UncertaintyResolver resolver = UncertaintyResolver.forProvider(attempt.providerType());
        attempts.markUncertain(
                attempt.tenantId(),
                attempt.id(),
                attempt.status(),
                resolver,
                now,
                now.plus(UNCERTAINTY_DEADLINE),
                failureCode);
        log.warn(
                "Payment attempt {} on {} is uncertain; resolving through {} by {}.",
                attempt.id(),
                attempt.providerType(),
                resolver,
                now.plus(UNCERTAINTY_DEADLINE));

        // Telegram's reconciliation path is unspecified (UncertaintyResolver's
        // own Javadoc), so forProvider hands this attempt straight to
        // OPERATIONS_EXCEPTION on its very first uncertainty — there is no
        // automated leg to retry first. Every other provider starts on its own
        // polling resolver here and reaches this event later, from
        // resolveUncertainty, only once its deadline has actually passed.
        if (resolver == UncertaintyResolver.OPERATIONS_EXCEPTION) {
            publishNeedsOperator(attempt, PaymentAttemptNeedsOperator.REASON_UNSUPPORTED_PROVIDER, now);
        }
    }

    /**
     * Fans a {@link PaymentAttemptNeedsOperator} fact out alongside the
     * attempt-store write that decided a human is needed.
     *
     * <p>Called only from inside an active transaction (the {@code
     * @Transactional} method it publishes into or a caller's own {@code
     * unitOfWork}/{@code independently} template), because
     * {@code @TransactionalEventListener} silently drops an event published
     * with no transaction in flight — the same discipline every other
     * publish in this class already follows.
     *
     * <p>A second, best-effort lookup of the intent this attempt belongs to,
     * for {@code brandId}/{@code locationId} alone: neither is on {@code
     * PaymentAttempt} itself, and widening that record for three call sites
     * that each already have a natural home for one extra read is a smaller
     * change than it looks. A missing intent — reachable only if the intent
     * row was deleted underneath a live attempt, which nothing in this
     * codebase does — logs and skips rather than throws, because a human
     * already knows to look here: {@link #markUncertain} and {@link
     * #resolveUncertainty} both log at WARN before this is ever called.
     */
    private void publishNeedsOperator(PaymentAttempt attempt, String reasonCode, Instant now) {
        intents.find(attempt.tenantId(), attempt.intentId())
                .ifPresentOrElse(
                        intent -> events.publishEvent(new PaymentAttemptNeedsOperator(
                                UUID.randomUUID(),
                                attempt.tenantId(),
                                intent.brandId(),
                                intent.locationId(),
                                intent.orderId(),
                                attempt.id(),
                                reasonCode,
                                now)),
                        () -> log.warn(
                                "Payment attempt {} needs an operator but its intent {} no longer resolves; "
                                        + "no operations alert was raised.",
                                attempt.id(),
                                attempt.intentId()));
    }

    /**
     * Asks the provider what actually happened, and applies the answer.
     *
     * <p>Three answers and each has a different consequence. A definite outcome
     * moves the attempt. Still-in-flight — Click's {@code payment_status} of
     * {@code 0} or {@code 1}, which are created and in processing and neither of
     * which is money — leaves it uncertain and counts an attempt. And a not-found
     * <strong>also</strong> leaves it uncertain, which is the one that looks wrong
     * and is not: the business date the query is keyed on is undocumented, so on
     * Click absence of evidence is not evidence of absence, and treating a
     * not-found as "no charge happened" is exactly what would unblock the retry
     * this whole mechanism exists to prevent.
     *
     * @return the status the attempt now holds
     */
    // Not @Transactional, for the same reason as present(): queryOutcome below is
    // an HTTP call to Click or Payme, and the resolver runs it over a batch of
    // uncertain attempts. A transaction held across it would put the provider's
    // latency inside the connection pool once per attempt in the batch. The reads
    // before it are single statements, and the writes after it are one
    // transaction whose first move is a compare-and-set against UNCERTAIN — so a
    // second resolver racing this one settles the attempt once, not twice.
    public PaymentAttemptStatus resolveUncertainty(PaymentAttempt attempt) {
        if (attempt.status() != PaymentAttemptStatus.UNCERTAIN) {
            return attempt.status();
        }

        Optional<UUID> seller = sellerOf(attempt);
        Optional<ProviderBinding> binding = seller.isEmpty()
                ? Optional.empty()
                : bindings.resolve(attempt.tenantId(), seller.get(), attempt.providerType(), attempt.businessDate());
        PaymentProviderPort provider = providers.get(attempt.providerType());

        if (binding.isEmpty() || provider == null) {
            // A binding cannot be retired while an attempt against it is uncertain,
            // so reaching here means configuration has been changed underneath a
            // live question about money. A human, not a retry.
            //
            // independently rather than a bare write: recordResolutionAttempt and
            // the operations-alert publish belong in one transaction, for the same
            // reason every other publish in this class runs inside one — a
            // TransactionalEventListener silently drops an event published with
            // no transaction in flight.
            independently.executeWithoutResult(ignored -> {
                Instant now = clock.instant();
                attempts.recordResolutionAttempt(
                        attempt.tenantId(), attempt.id(), UncertaintyResolver.OPERATIONS_EXCEPTION, now);
                publishNeedsOperator(attempt, PaymentAttemptNeedsOperator.REASON_BINDING_UNAVAILABLE, now);
            });
            return PaymentAttemptStatus.UNCERTAIN;
        }

        ProviderOutcome outcome = provider.queryOutcome(attempt, binding.get());

        return unitOfWork.execute(ignored -> {
            Instant now = clock.instant();
            return switch (outcome.classification()) {
                case SUCCESS, REJECTED -> {
                    PaymentAttemptStatus settled =
                            outcome.observedStatus() == null ? PaymentAttemptStatus.FAILED : outcome.observedStatus();
                    attempts.transition(
                            attempt.tenantId(),
                            attempt.id(),
                            PaymentAttemptStatus.UNCERTAIN,
                            settled,
                            outcome.evidence(),
                            outcome.externalPaymentId(),
                            outcome.externalDocumentId(),
                            outcome.failureCode(),
                            now);
                    applyToIntent(attempt, settled, outcome.failureCode(), now);
                    yield settled;
                }
                case RETRYABLE, UNCERTAIN -> {
                    boolean pastDeadline = attempt.uncertain()
                            .filter(uncertainty -> uncertainty.pastDeadline(now))
                            .isPresent();
                    UncertaintyResolver next = pastDeadline
                            ? UncertaintyResolver.OPERATIONS_EXCEPTION
                            : UncertaintyResolver.forProvider(attempt.providerType());
                    attempts.recordResolutionAttempt(attempt.tenantId(), attempt.id(), next, now);
                    // Only the deadline transition is a human event. Every other
                    // pass through here is the automated resolver trying again,
                    // which ADR 0058 is explicit must not alert — "alert when a
                    // human is genuinely needed, not on every retry" — and the
                    // idempotency key on attemptId means a sweep that keeps
                    // finding this same attempt past its deadline republishes
                    // safely: the first one lands, the rest dedupe.
                    if (pastDeadline) {
                        publishNeedsOperator(attempt, PaymentAttemptNeedsOperator.REASON_DEADLINE_EXCEEDED, now);
                    }
                    yield PaymentAttemptStatus.UNCERTAIN;
                }
            };
        });
    }

    /**
     * Cancels reservations whose window has closed.
     *
     * <p>A background sweep the documentation never requires. Payme's own templates
     * expire lazily, on the next inbound call, and for a checkout the customer
     * abandoned that call never comes — so the reservation would hold stock
     * forever. The cost is that HorecaOS cancels some transactions Payme might have
     * left alone, which is the cheaper of the two errors.
     */
    @Transactional
    public int expireStaleReservations(int limit) {
        Instant now = clock.instant();
        List<PaymentAttempt> stale = attempts.listExpiredReservations(now, limit);
        int expired = 0;
        for (PaymentAttempt attempt : stale) {
            boolean recorded = recordProviderEvent(
                    attempt,
                    PaymentTransactionType.EXPIRE,
                    PaymentAttemptStatus.EXPIRED,
                    attempt.amount(),
                    null,
                    // Payme expresses this as state -1 with reason 4. Click has no
                    // expiry state at all and is never told.
                    attempt.providerType() == uz.horecaos.platform.payments.domain.PaymentProviderType.PAYME
                            ? new ProviderEvidence("-1", "4", now)
                            : null,
                    null,
                    null,
                    now,
                    null,
                    null);
            if (recorded) {
                expired++;
            }
        }
        return expired;
    }

    public List<PaymentAttempt> uncertainAttempts(UUID tenantId, int limit) {
        return attempts.listUncertain(tenantId, limit);
    }

    /**
     * The projection the order reads, derived from the attempt rather than decided
     * beside it.
     *
     * <p>A capture also tells the settlement, in this same transaction. Money
     * arriving is a fact about the tenant's bank balance and it is a fact whatever
     * has become of the order: an order cancelled or expired while its payment was
     * still live never confirms again, so {@code OrderConfirmedSettlementTrigger}
     * — the only thing that used to settle a provider tender — never fired for it,
     * and the capture landed against a settlement that had already been failed. A
     * refund then answered "a refund cannot exceed what the tenders settled" for
     * money the tenant was genuinely holding, which is the worst answer available:
     * the customer's money is gone and the platform will not admit it has it.
     *
     * <p>Local rows only, and inside the transaction that records the capture on
     * purpose. A settlement that closed in a separate transaction could be lost
     * while the capture survived, which is the same gap in a smaller window.
     *
     * <p>Four of the six statuses this method may be called with also carry a
     * second local fact: {@code ordering.orders.payment_status_projection}, the
     * V0022 rendering column that exists so an operations list can be drawn
     * without joining four modules. Every state this method draws a consequence
     * from is a state {@code PaymentProjectionTrigger} on the ordering side
     * mirrors onto it — {@link PaymentCaptured}, {@link PaymentFailed}, {@link
     * PaymentVoided} and {@link PaymentRefunded} — through the same plain
     * application event {@link PaymentCaptured} already used, and for the same
     * module-boundary reason given on that type's own Javadoc. {@code EXPIRED}
     * and {@code UNCERTAIN} publish nothing: the projection has no value for
     * either, and a reservation aging out is not, on its own, the fact an
     * operations list needs to see.
     *
     * @param freshFailureCode the failure code this exact transition is
     *                         carrying, for {@link PaymentAttemptFailed} —
     *                         never {@code attempt.failureCode()}, which is
     *                         the attempt as it stood before this call and
     *                         would read stale or null for the transition
     *                         that just decided FAILED
     */
    private void applyToIntent(
            PaymentAttempt attempt,
            PaymentAttemptStatus attemptStatus,
            @Nullable String freshFailureCode,
            Instant now) {
        intents.find(attempt.tenantId(), attempt.intentId()).ifPresent(intent -> {
            if (attemptStatus == PaymentAttemptStatus.CAPTURED) {
                captures.recordCapture(intent.tenantId(), intent.orderId(), CAPTURE_ACTOR);
            }

            // ADR 0019 step 8's missing half — and, for the three siblings below,
            // the projection this class exists to keep honest. A plain application
            // event and not a direct call, because this module already depends on
            // ordering.api for the reverse direction (OrderConfirmedSettlementTrigger,
            // TerminalOrderPaymentVoid) — calling into ordering.application
            // directly would reach past its module boundary into a package this
            // module may not see, and a dependency running the other way would
            // make the two modules mutually dependent. See PaymentCaptured's own
            // Javadoc.
            TenantId tenant = new TenantId(intent.tenantId());
            switch (attemptStatus) {
                case CAPTURED ->
                    events.publishEvent(new PaymentCaptured(UUID.randomUUID(), tenant, intent.orderId(), now));
                case FAILED -> {
                    events.publishEvent(new PaymentFailed(UUID.randomUUID(), tenant, intent.orderId(), now));
                    // ADR 0058's operations trigger: enough to route (brand,
                    // location, both on this same intent already in scope) and
                    // enough to say why (the attempt's own failureCode, never a
                    // provider payload) — see PaymentAttemptFailed's own Javadoc
                    // for why this is a sibling event and not a widened
                    // PaymentFailed.
                    events.publishEvent(new PaymentAttemptFailed(
                            UUID.randomUUID(),
                            intent.tenantId(),
                            intent.brandId(),
                            intent.locationId(),
                            intent.orderId(),
                            attempt.id(),
                            freshFailureCode,
                            now));
                }
                // CANCELLED here is always a release with no capture — the state
                // machine forbids CAPTURED -> CANCELLED — so it is honestly a void,
                // whether TerminalOrderPaymentVoid asked for it or a resolver found
                // the provider had rejected the reservation outright.
                case CANCELLED ->
                    events.publishEvent(new PaymentVoided(UUID.randomUUID(), tenant, intent.orderId(), now));
                // The mirror of CAPTURED: money that had landed has gone back,
                // reported by the provider itself (Payme's inbound CancelTransaction
                // after a cabinet refund, or Click's own reversal of a capture that
                // outlived its order).
                case REVERSED ->
                    events.publishEvent(new PaymentRefunded(UUID.randomUUID(), tenant, intent.orderId(), now));
                default -> {
                    // EXPIRED and UNCERTAIN. Neither is a fact the projection has a
                    // value for; UNCERTAIN in particular is not yet a fact at all.
                }
            }

            PaymentIntentStatus target =
                    switch (attemptStatus) {
                        case CAPTURED -> PaymentIntentStatus.PAID;
                        case CANCELLED -> PaymentIntentStatus.CANCELLED;
                        case EXPIRED -> PaymentIntentStatus.EXPIRED;
                        case FAILED -> PaymentIntentStatus.FAILED;
                        // A reversal does not un-pay the order. The order was paid, the
                        // money went back, and both facts are true: adjusting the intent to
                        // say otherwise would destroy the record that a capture happened,
                        // which is the record a settlement reconciliation is argued from.
                        case REVERSED -> PaymentIntentStatus.PAID;
                        default -> intent.status();
                    };
            if (target != intent.status()) {
                intents.transition(intent.tenantId(), intent.id(), intent.status(), target, intent.version(), now);
            }
        });
    }

    private Optional<UUID> sellerOf(PaymentAttempt attempt) {
        return intents.find(attempt.tenantId(), attempt.intentId()).flatMap(PaymentIntent::legalEntity);
    }

    /**
     * HorecaOS's own transaction id, opaque and non-sequential.
     *
     * <p>Non-sequential because on Payme this becomes {@code account.order_id} in an
     * unsigned checkout link, and {@code CheckPerformTransaction} is unauthenticated
     * from the customer's side: sequential integers would let anyone walk other
     * customers' orders. The Payme account schema is one field, frozen once, so the
     * shape of this value is effectively permanent from the first live transaction.
     */
    private static String mintMerchantTransId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
