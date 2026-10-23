package uz.horecaos.platform.notifications.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.marketing.api.CourierContactSource;
import uz.horecaos.platform.marketing.api.DeliverabilityFeedbackPort;
import uz.horecaos.platform.marketing.api.SmsSegments;
import uz.horecaos.platform.notifications.api.CourierFirstNameDirectory;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.api.NotificationTransport.ReconcileRequest;
import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.NotificationStatus;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog;
import uz.horecaos.platform.notifications.domain.OrderMessageValues;
import uz.horecaos.platform.notifications.domain.PlatformDefaultTemplates;
import uz.horecaos.platform.notifications.domain.TemplateRenderer;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.AttemptRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.VersionRow;

/**
 * Sending a ready message, and settling what came back (ADR 0020, steps 4 to 7).
 *
 * <p>The order of operations is the whole design. An attempt row is written
 * <em>before</em> the provider is called, so a worker that dies mid-call leaves
 * evidence that a request may already be out there. The next claim finds that open
 * attempt and reconciles it instead of sending again — which is the difference
 * between a customer getting one confirmation and two.
 *
 * <p>A retry re-transmits the <em>same</em> attempt under the same provider
 * idempotency key, rather than starting a new one. ADR 0007 is explicit about why:
 * a fresh key defeats the provider-side deduplication the retry depends on. A new
 * attempt number is reserved for a deliberately different request — another channel
 * or another provider — which this slice does not do.
 *
 * <p>Nothing rendered or resolved here is written down. The recipient value is
 * fetched from ADR 0015 for the length of one call and the rendered body exists
 * only inside {@link NotificationDispatch}; what survives on the row is the frozen
 * template version, the variables, and two hashes, which together reproduce the
 * message without this table holding it.
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    private static final TypeReference<Map<String, String>> VARIABLES_TYPE = new TypeReference<>() {};

    /** Recorded on every reveal, so a delivery sweep is distinguishable from an export. */
    private static final String REVEAL_PURPOSE = "NOTIFICATION_DELIVERY";

    /** Recorded on every read of a courier's number from the identity provider (ADR 0182, D15). */
    static final String COURIER_REVEAL_PURPOSE = "COURIER_BROADCAST_DELIVERY";

    private final JdbcNotificationStore notifications;
    private final JdbcTemplateStore templates;
    private final RecipientContactDirectory contacts;
    private final CourierFirstNameDirectory courierNames;
    private final NotificationTransport transport;
    private final CampaignBlockRateMonitor campaignBlockRate;
    private final DeliverabilityFeedbackPort deliverability;
    private final MeterRegistry meters;
    private final EmailEnvelopes emailEnvelopes;
    private final CourierContactSource couriers;
    private final PushDispatches pushes;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maximumAttempts;
    private final Duration retryBackoff;

    /**
     * The shape every caller used before ADR 0146: no deliverability feedback and a
     * private meter registry, so a suite that does not exercise either still reads
     * the same.
     */
    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            NotificationTransport transport,
            CampaignBlockRateMonitor campaignBlockRate,
            ObjectMapper objectMapper,
            Clock clock,
            int maximumAttempts,
            Duration retryBackoff) {
        this(
                notifications,
                templates,
                contacts,
                (tenantId, orderId) -> Optional.empty(),
                transport,
                campaignBlockRate,
                (tenantId, brandId, accountId, channel, correlationId) -> false,
                new SimpleMeterRegistry(),
                objectMapper,
                clock,
                maximumAttempts,
                retryBackoff);
    }

    /**
     * The shape that predates ADR 0184: no courier directory, so a message that names {@code
     * courierFirstName} reads the neutral phrase. For the callers and tests that build a dispatcher by hand
     * and have no courier module to ask.
     */
    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            NotificationTransport transport,
            CampaignBlockRateMonitor campaignBlockRate,
            DeliverabilityFeedbackPort deliverability,
            MeterRegistry meters,
            ObjectMapper objectMapper,
            Clock clock,
            int maximumAttempts,
            Duration retryBackoff) {
        this(
                notifications,
                templates,
                contacts,
                (tenantId, orderId) -> Optional.empty(),
                transport,
                campaignBlockRate,
                deliverability,
                meters,
                objectMapper,
                clock,
                maximumAttempts,
                retryBackoff);
    }

    /**
     * The shape every caller used before ADR 0182: a courier directory, and no email envelope, because
     * nothing sends email.
     */
    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            CourierFirstNameDirectory courierNames,
            NotificationTransport transport,
            CampaignBlockRateMonitor campaignBlockRate,
            DeliverabilityFeedbackPort deliverability,
            MeterRegistry meters,
            ObjectMapper objectMapper,
            Clock clock,
            int maximumAttempts,
            Duration retryBackoff) {
        this(
                notifications,
                templates,
                contacts,
                courierNames,
                transport,
                campaignBlockRate,
                deliverability,
                meters,
                EmailEnvelopes.NONE,
                () -> false,
                PushDispatches.NONE,
                objectMapper,
                clock,
                maximumAttempts,
                retryBackoff);
    }

    /**
     * Email, no courier directory, no courier broadcast and no push: the shape a caller that sends campaign
     * email and nothing else uses (ADR 0182).
     */
    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            NotificationTransport transport,
            CampaignBlockRateMonitor campaignBlockRate,
            DeliverabilityFeedbackPort deliverability,
            MeterRegistry meters,
            EmailEnvelopes emailEnvelopes,
            ObjectMapper objectMapper,
            Clock clock,
            int maximumAttempts,
            Duration retryBackoff) {
        this(
                notifications,
                templates,
                contacts,
                (tenantId, orderId) -> Optional.empty(),
                transport,
                campaignBlockRate,
                deliverability,
                meters,
                emailEnvelopes,
                () -> false,
                PushDispatches.NONE,
                objectMapper,
                clock,
                maximumAttempts,
                retryBackoff);
    }

    /**
     * Email and couriers, and no push: the shape a caller that predates the Web Push channel uses
     * (ADR 0182).
     */
    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            NotificationTransport transport,
            CampaignBlockRateMonitor campaignBlockRate,
            DeliverabilityFeedbackPort deliverability,
            MeterRegistry meters,
            EmailEnvelopes emailEnvelopes,
            CourierContactSource couriers,
            ObjectMapper objectMapper,
            Clock clock,
            int maximumAttempts,
            Duration retryBackoff) {
        this(
                notifications,
                templates,
                contacts,
                (tenantId, orderId) -> Optional.empty(),
                transport,
                campaignBlockRate,
                deliverability,
                meters,
                emailEnvelopes,
                couriers,
                PushDispatches.NONE,
                objectMapper,
                clock,
                maximumAttempts,
                retryBackoff);
    }

    /**
     * Email, couriers and push, and no courier directory: the shape a caller that predates ADR 0184 uses.
     */
    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            NotificationTransport transport,
            CampaignBlockRateMonitor campaignBlockRate,
            DeliverabilityFeedbackPort deliverability,
            MeterRegistry meters,
            EmailEnvelopes emailEnvelopes,
            CourierContactSource couriers,
            PushDispatches pushes,
            ObjectMapper objectMapper,
            Clock clock,
            int maximumAttempts,
            Duration retryBackoff) {
        this(
                notifications,
                templates,
                contacts,
                (tenantId, orderId) -> Optional.empty(),
                transport,
                campaignBlockRate,
                deliverability,
                meters,
                emailEnvelopes,
                couriers,
                pushes,
                objectMapper,
                clock,
                maximumAttempts,
                retryBackoff);
    }

    @Autowired
    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            CourierFirstNameDirectory courierNames,
            NotificationTransport transport,
            CampaignBlockRateMonitor campaignBlockRate,
            DeliverabilityFeedbackPort deliverability,
            MeterRegistry meters,
            EmailEnvelopes emailEnvelopes,
            CourierContactSource couriers,
            PushDispatches pushes,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.notifications.max-attempts:8}") int maximumAttempts,
            @Value("${horecaos.notifications.retry-backoff:PT30S}") Duration retryBackoff) {
        this.pushes = pushes;
        this.notifications = notifications;
        this.templates = templates;
        this.contacts = contacts;
        this.courierNames = courierNames;
        this.transport = transport;
        this.campaignBlockRate = campaignBlockRate;
        this.deliverability = deliverability;
        this.meters = meters;
        this.emailEnvelopes = emailEnvelopes;
        this.couriers = couriers;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maximumAttempts = maximumAttempts;
        this.retryBackoff = retryBackoff;
    }

    /**
     * Carries out one claimed, ready message.
     *
     * <p>Not annotated {@code @Transactional}, and that is deliberate. A provider
     * call inside a database transaction holds the transaction open across the
     * network, and a rollback afterwards would erase the evidence that the call was
     * made while doing nothing at all about the message the provider already sent.
     * Each write below is its own short transaction.
     */
    public void dispatch(NotificationRow row) {
        Instant now = clock.instant();

        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            // Past this, sending is worse than not sending. A confirmation an hour
            // after the customer collected their food is noise, and a rejection the
            // next morning is an incident.
            notifications.settle(
                    row.tenantId(),
                    row.id(),
                    row.claimToken(),
                    NotificationStatus.EXPIRED.name(),
                    now,
                    "The message expired before it was sent",
                    now);
            return;
        }

        if (NotificationChannel.valueOf(row.channel()).requiresPerEndpointOrdering()
                && row.recipientEndpointId() != null
                && notifications.hasOlderPendingForEndpoint(
                        row.tenantId(), row.channel(), row.recipientEndpointId(), row.createdAt(), row.id())) {
            // ADR 0058's ordering boundary: an older message to the same chat is
            // still outstanding, so this one waits rather than risking a race that
            // sends it first. A short requeue rather than escalateOrRetry's
            // backoff — this is a scheduling deferral, not a provider failure, and
            // must not read as one in an operator's eyes.
            notifications.settle(
                    row.tenantId(),
                    row.id(),
                    row.claimToken(),
                    NotificationStatus.RETRY_PENDING.name(),
                    now.plusSeconds(2),
                    "Waiting for an older message to the same chat to send first",
                    now);
            return;
        }

        Optional<AttemptRow> open = notifications.openAttempt(row.tenantId(), row.id());
        if (open.isPresent() && "UNCERTAIN".equals(open.get().status())) {
            reconcile(row, open.get(), now);
            return;
        }
        if (open.isPresent() && "REQUESTED".equals(open.get().status())) {
            // A worker died between writing this row and recording an answer, so
            // the provider may have acted. Reconciled rather than repeated, for the
            // same reason an explicit timeout is.
            reconcile(row, open.get(), now);
            return;
        }

        AttemptRow attempt = open.orElseGet(() -> openNewAttempt(row, now));
        Rendered rendered = render(row);

        String recipientValue;
        NotificationDispatch.PushEnvelope pushEnvelope = null;
        try {
            if (NotificationChannel.PUSH.name().equals(row.channel())) {
                // ADR 0182, D14: the recipient is the browser's push service URL, revealed with the keys
                // and the click link in one step, for the length of this call and kept nowhere.
                Optional<PushDispatches.Resolved> resolved =
                        pushes.resolve(row, rendered.coverAssetId(), rendered.targetPath());
                recipientValue = resolved.map(PushDispatches.Resolved::endpoint).orElse(null);
                pushEnvelope = resolved.map(PushDispatches.Resolved::envelope).orElse(null);
            } else {
                recipientValue = resolveRecipientValue(row);
            }
        } catch (CourierContactSource.UnavailableException unavailable) {
            // The identity provider could not be asked for a courier's number (ADR 0182). Nothing was
            // sent and nothing is known about the courier, so this is a fault to retry later, never
            // a reason to conclude the courier has no number.
            notifications.settleAttempt(
                    row.tenantId(),
                    attempt.id(),
                    "RETRYABLE_FAILURE",
                    null,
                    "IDENTITY_PROVIDER_UNAVAILABLE",
                    null,
                    null,
                    null,
                    now);
            escalateOrRetry(row, attempt, "IDENTITY_PROVIDER_UNAVAILABLE", now.plus(retryBackoff));
            return;
        }
        if (recipientValue == null) {
            // The customer removed the number between eligibility and now. Not a
            // suppression — the message was eligible when it was decided — and not
            // retryable either, because nothing will bring the contact back.
            notifications.settleAttempt(
                    row.tenantId(), attempt.id(), "REJECTED", null, "RECIPIENT_UNRESOLVABLE", null, null, null, now);
            notifications.settle(
                    row.tenantId(),
                    row.id(),
                    row.claimToken(),
                    NotificationStatus.FAILED_TERMINAL.name(),
                    now,
                    "The recipient contact no longer exists",
                    now);
            return;
        }

        notifications.markSending(row.tenantId(), row.id(), row.claimToken(), ContentHashes.of(rendered.body()), now);

        // ADR 0182: an email is sent in the brand's name and carries a way out. Minted here, after
        // the attempt is written and before the call, so a message that is never sent leaves a token
        // for nobody and a message that is sent always has one.
        NotificationDispatch.EmailEnvelope email =
                emailEnvelopes.forMessage(row).orElse(null);

        DispatchOutcome outcome = transport.dispatch(new NotificationDispatch(
                row.id(),
                attempt.id(),
                row.tenantId(),
                row.brandId(),
                row.locationId(),
                row.channel(),
                recipientValue,
                rendered.subject(),
                rendered.body(),
                attempt.providerIdempotencyKey(),
                org.slf4j.MDC.get("correlationId"),
                row.subjectType(),
                row.subjectId(),
                row.templateKey(),
                purposeOf(row),
                email,
                pushEnvelope));

        record(row, attempt, outcome, clock.instant());
        // After the outcome is recorded, so a push service's «gone» is on the attempt before the
        // subscription it names is ended.
        pushes.settled(row, outcome);
        compareSegments(row, outcome, rendered.body());
    }

    /**
     * Discovers the truth after an outcome that did not say.
     *
     * <p>The single most important behaviour here. The command is not repeated: on
     * a gateway whose accept is immediately live, repeating it texts the customer a
     * second time and bills the tenant twice.
     */
    private void reconcile(NotificationRow row, AttemptRow attempt, Instant now) {
        // The claim is held throughout. Releasing it around the query would let a
        // second worker pick the row up and send the message this call is in the
        // middle of establishing the fate of.
        notifications.markReconciling(row.tenantId(), row.id(), row.claimToken(), now);

        // ADR 0146 Decision 2: ask by the provider's own id when the send's answer
        // gave one, and otherwise by destination and the hash of what was sent. The
        // destination is resolved for this call only and its reveal is recorded
        // (ADR 0029), which is why it is not looked up when an id already names the
        // message, and not at all for a channel whose recipient is not a number.
        String destination;
        try {
            destination = attempt.externalMessageId() == null && "SMS".equals(row.channel())
                    ? resolveRecipientValue(row)
                    : null;
        } catch (CourierContactSource.UnavailableException unavailable) {
            escalateOrRetry(row, attempt, "IDENTITY_PROVIDER_UNAVAILABLE", now.plus(retryBackoff));
            return;
        }
        DispatchOutcome outcome = transport.reconcile(new ReconcileRequest(
                row.tenantId(),
                row.brandId(),
                row.locationId(),
                row.channel(),
                attempt.providerIdempotencyKey(),
                attempt.externalMessageId(),
                destination,
                row.renderedContentHash(),
                attempt.requestedAt()));
        Instant answeredAt = clock.instant();

        switch (outcome.status()) {
            case UNCERTAIN ->
                // Still cannot tell. Backed off rather than escalated at once;
                // attempt_count is what eventually hands it to a person.
                escalateOrRetry(
                        row, attempt, "The provider could not confirm the outcome", answeredAt.plus(retryBackoff));
            case RETRYABLE -> {
                // The provider has no record of this key, so it never acted. Only
                // now is a second send safe, and it is a genuinely new request
                // rather than a re-transmission: this attempt is closed so the next
                // claim opens a fresh one under a fresh key.
                notifications.settleAttempt(
                        row.tenantId(),
                        attempt.id(),
                        "RECONCILED_NOT_SENT",
                        null,
                        outcome.errorCode(),
                        outcome.providerBindingId(),
                        outcome.providerType(),
                        null,
                        answeredAt);
                escalateOrRetry(row, attempt, "The provider never received the message", answeredAt);
            }
            case ACCEPTED, REJECTED -> record(row, attempt, outcome, answeredAt);
        }
    }

    /**
     * ADR 0146 Decision 5's one pull: asks the gateway what it holds for an attempt
     * it reported as {@code UNKNOWN}, and answers with what it said.
     *
     * <p>Every pull decrypts a number (ADR 0029, a recorded purpose), which is why
     * this runs only for attempts the provider itself called unresolved and never as
     * a standing "chase every delivery" workload. It sends nothing and changes
     * nothing here: the caller applies the answer through the receipt rules, so a
     * pulled state can only advance an attempt exactly as a callback can.
     */
    public Optional<DispatchOutcome> pullState(JdbcDeliveryReceiptStore.UnknownStateAttempt attempt) {
        Optional<NotificationRow> row = notifications.find(attempt.tenantId(), attempt.notificationId());
        if (row.isEmpty() || attempt.brandId() == null) {
            return Optional.empty();
        }
        // A gateway that can only be searched by number needs it even when an id
        // already names the message, so it is resolved here for this call only.
        String destination;
        try {
            destination = resolveRecipientValue(row.get());
        } catch (CourierContactSource.UnavailableException unavailable) {
            // The identity provider is down: a pull is best effort, and asking again later is free.
            return Optional.empty();
        }
        DispatchOutcome outcome = transport.reconcile(new ReconcileRequest(
                attempt.tenantId(),
                attempt.brandId(),
                attempt.locationId(),
                attempt.channel(),
                attempt.providerIdempotencyKey(),
                attempt.externalMessageId(),
                destination,
                attempt.renderedContentHash(),
                attempt.requestedAt()));
        return Optional.of(outcome);
    }

    private void record(NotificationRow row, AttemptRow attempt, DispatchOutcome outcome, Instant now) {
        switch (outcome.status()) {
            case ACCEPTED -> {
                // The attempt records how strong the provider's answer actually
                // was. A gateway that says "queued" has not told us the handset saw
                // anything, and ADR 0020 forbids HorecaOS promising more than it was
                // given — so only a confirmed delivery gets an acknowledgement time.
                //
                // The adapter's own reading of its provider's word wins when it has
                // one (ADR 0146 Decision 2, receipt): a gateway that says "Fail" or
                // "InBlackList" has said the message will not arrive, which is a
                // fact about that message and not a licence to send another.
                String normalized = outcome.normalizedStatus() != null
                        ? outcome.normalizedStatus()
                        : normalize(outcome.providerStatus());
                boolean confirmed = "DELIVERED".equals(normalized) || "READ".equals(normalized);
                boolean failed = "FAILED".equals(normalized);

                notifications.settleAttempt(
                        row.tenantId(),
                        attempt.id(),
                        confirmed ? "DELIVERED" : failed ? "FAILED" : "ACCEPTED",
                        outcome.externalMessageId(),
                        failed
                                ? (outcome.hardBounce()
                                        ? DeliveryReceiptService.FAILURE_UNREACHABLE
                                        : DeliveryReceiptService.FAILURE_REPORTED)
                                : null,
                        outcome.providerBindingId(),
                        outcome.providerType(),
                        confirmed ? now : null,
                        outcome.providerSegments(),
                        now);
                notifications.recordStatusEvent(
                        row.tenantId(),
                        attempt.id(),
                        providerEventId(attempt, outcome, normalized),
                        normalized,
                        outcome.providerStatus(),
                        now,
                        now);
                if (failed) {
                    // Terminal and not retried: the provider took the message and
                    // says it will not arrive. A blacklisted receiver is also a
                    // deliverability fact (ADR 0146 Decision 6), written through
                    // the one narrow path that raises it.
                    notifications.settle(
                            row.tenantId(),
                            row.id(),
                            row.claimToken(),
                            NotificationStatus.FAILED_TERMINAL.name(),
                            now,
                            DeliveryReceiptService.FAILURE_REPORTED,
                            now);
                    if (outcome.hardBounce() && row.recipientAccountId() != null && row.brandId() != null) {
                        deliverability.recordHardBounce(
                                row.tenantId(),
                                row.brandId(),
                                row.recipientAccountId(),
                                row.channel(),
                                attempt.id().toString());
                    }
                } else {
                    // The message is terminal either way. Which promise was actually
                    // made is on the status event, verbatim, because that is the
                    // distinction a support conversation turns on.
                    notifications.settle(
                            row.tenantId(),
                            row.id(),
                            row.claimToken(),
                            NotificationStatus.DELIVERED.name(),
                            now,
                            null,
                            now);
                }
            }
            case REJECTED -> {
                notifications.settleAttempt(
                        row.tenantId(),
                        attempt.id(),
                        "REJECTED",
                        null,
                        outcome.errorCode(),
                        outcome.providerBindingId(),
                        outcome.providerType(),
                        null,
                        now);
                // Retrying a business rejection produces the same rejection forever
                // while looking like an outage.
                notifications.settle(
                        row.tenantId(),
                        row.id(),
                        row.claimToken(),
                        NotificationStatus.FAILED_TERMINAL.name(),
                        now,
                        outcome.errorCode(),
                        now);
                if (outcome.hardBounce() && row.recipientAccountId() != null && row.brandId() != null) {
                    // A relay that refuses an address for good (ADR 0182): the same deliverability
                    // fact a gateway's blacklist receipt is (ADR 0146 Decision 6), raised by a send
                    // of ours and written through the same narrow path.
                    deliverability.recordHardBounce(
                            row.tenantId(),
                            row.brandId(),
                            row.recipientAccountId(),
                            row.channel(),
                            attempt.id().toString());
                }
                // ADR 0059 stage 4: a no-op for anything that is not a campaign
                // message whose Telegram binding just retired. Called after the
                // row is settled, not before, so the guard's own read of "how
                // many recipients has this campaign attempted" already counts
                // this one as queued.
                campaignBlockRate.onRejected(row, outcome, now);
            }
            case RETRYABLE -> {
                notifications.settleAttempt(
                        row.tenantId(),
                        attempt.id(),
                        "RETRYABLE_FAILURE",
                        null,
                        outcome.errorCode(),
                        outcome.providerBindingId(),
                        outcome.providerType(),
                        null,
                        now);
                escalateOrRetry(
                        row,
                        attempt,
                        outcome.errorCode(),
                        now.plus(outcome.retryDelay().orElse(retryBackoff)));
            }
            case UNCERTAIN -> {
                notifications.settleAttempt(
                        row.tenantId(),
                        attempt.id(),
                        "UNCERTAIN",
                        null,
                        outcome.errorCode(),
                        outcome.providerBindingId(),
                        outcome.providerType(),
                        null,
                        now);
                notifications.settle(
                        row.tenantId(),
                        row.id(),
                        row.claimToken(),
                        NotificationStatus.UNCERTAIN.name(),
                        now.plus(retryBackoff),
                        outcome.errorCode(),
                        now);
                log.warn("Notification {} has an uncertain provider outcome; it will be reconciled", row.id());
            }
        }
    }

    /**
     * Either back off, or stop and ask for a person.
     *
     * <p>The escalation is {@code MANUAL_REVIEW} rather than {@code FAILED_TERMINAL}
     * whenever the last thing we knew was uncertain: "we could not send it" and "we
     * do not know whether we sent it" need different handling from support, and
     * collapsing them would let somebody resend a message the customer already has.
     */
    private void escalateOrRetry(
            NotificationRow row, AttemptRow attempt, @Nullable String reason, Instant nextAttemptAt) {
        Instant now = clock.instant();
        if (row.attemptCount() < maximumAttempts) {
            notifications.settle(
                    row.tenantId(),
                    row.id(),
                    row.claimToken(),
                    NotificationStatus.RETRY_PENDING.name(),
                    nextAttemptAt,
                    reason,
                    now);
            return;
        }
        boolean uncertain = attempt.uncertainOutcome() || "UNCERTAIN".equals(attempt.status());
        notifications.settle(
                row.tenantId(),
                row.id(),
                row.claimToken(),
                uncertain ? NotificationStatus.MANUAL_REVIEW.name() : NotificationStatus.FAILED_TERMINAL.name(),
                now,
                "Gave up after %d attempts: %s".formatted(row.attemptCount(), reason),
                now);
        log.error("Notification {} exhausted {} attempts", row.id(), row.attemptCount());
    }

    private AttemptRow openNewAttempt(NotificationRow row, Instant now) {
        UUID attemptId = UUID.randomUUID();
        int attemptNumber = notifications.nextAttemptNumber(row.tenantId(), row.id());
        // A fresh key per attempt, stable for every re-transmission of that attempt.
        // The provider deduplicates on it, which is why a retry after a timeout
        // does not produce a second message and a reconciled-not-sent attempt gets
        // a new one.
        String providerKey = attemptId.toString();

        notifications.insertAttempt(
                attemptId, row.tenantId(), row.id(), row.channel(), null, null, attemptNumber, providerKey, now);

        return new AttemptRow(
                attemptId,
                row.id(),
                row.channel(),
                null,
                null,
                attemptNumber,
                providerKey,
                "REQUESTED",
                null,
                null,
                false,
                now,
                null);
    }

    private Rendered render(NotificationRow row) {
        // Eligibility is what freezes the source of the wording onto the row (markReady), and this
        // method only ever runs on a row that reached READY; a row missing it here is a data fault
        // rather than an unresolved message.
        String locale = Objects.requireNonNull(
                row.locale(), () -> "Notification %s has no locale frozen onto it".formatted(row.id()));

        String subjectTemplate;
        String bodyTemplate;
        // A push carries a cover picture and a landing path with its wording (ADR 0182, D14); the platform's
        // bundled wording has neither.
        UUID coverAssetId = null;
        String targetPath = null;
        if (row.templateId() == null) {
            // ADR 0184: sent from the platform's own bundled wording. The version frozen onto the row is
            // the one handle on what the customer was sent, so a row from a version this release no longer
            // carries is a fault and not a sentence to guess at.
            int platformVersion = Objects.requireNonNull(
                    row.platformDefaultVersion(),
                    () -> "Notification %s has neither a template nor a platform default frozen onto it"
                            .formatted(row.id()));
            if (platformVersion != PlatformDefaultTemplates.VERSION) {
                throw new IllegalStateException(
                        "Notification %s was sent from platform default version %d, which this release does not carry"
                                .formatted(row.id(), platformVersion));
            }
            bodyTemplate = PlatformDefaultTemplates.forKey(row.templateKey())
                    .flatMap(entry -> entry.body(locale))
                    .orElseThrow(() -> new IllegalStateException(
                            "The platform default %s has no wording in %s".formatted(row.templateKey(), locale)));
            subjectTemplate = null;
        } else {
            int templateVersion = Objects.requireNonNull(
                    row.templateVersion(),
                    () -> "Notification %s has no template version frozen onto it".formatted(row.id()));
            VersionRow version = templates
                    .version(row.tenantId(), row.templateId(), templateVersion, locale)
                    .orElseThrow(() -> new IllegalStateException(
                            "The template version frozen onto notification %s no longer exists".formatted(row.id())));
            subjectTemplate = version.subjectTemplate();
            bodyTemplate = version.bodyTemplate();
            coverAssetId = version.coverAssetId();
            targetPath = version.targetPath();
        }

        Map<String, String> variables =
                new java.util.LinkedHashMap<>(objectMapper.readValue(row.variablesJson(), VARIABLES_TYPE));
        resolvePersonalValues(row, locale, bodyTemplate, subjectTemplate, variables);
        // The body template is never null (a template version cannot be saved
        // without one), so its rendering is asserted non-null even though
        // TemplateRenderer.render's return type is nullable for the subject case.
        String body = Objects.requireNonNull(TemplateRenderer.render(bodyTemplate, variables));
        return new Rendered(TemplateRenderer.render(subjectTemplate, variables), body, coverAssetId, targetPath);
    }

    /**
     * The two values that are about a person, put in the map for this render and nowhere else (ADR 0184,
     * D21): the customer's name, and the first name of the courier carrying the order.
     *
     * <p>Asked for only when the wording names them, so a message that does not say a name never reads
     * one. Each is allowed only where the decision allows it: the customer's name in a message addressed
     * to the customer, the courier's first name in the on-the-way message. Anything else is left out of
     * the map and the render refuses the missing value, which is the right outcome for a wording that
     * got past authoring with a personal variable in the wrong place. Neither value is logged, hashed
     * into a row, or kept: the map is this method's local.
     */
    private void resolvePersonalValues(
            NotificationRow row,
            String localeTag,
            String bodyTemplate,
            @Nullable String subjectTemplate,
            Map<String, String> variables) {
        java.util.Set<String> used = new java.util.LinkedHashSet<>(TemplateRenderer.variablesUsedIn(bodyTemplate));
        used.addAll(TemplateRenderer.variablesUsedIn(subjectTemplate));
        NotificationClass notificationClass = NotificationClass.valueOf(row.notificationClass());

        if (used.contains("customerName") && NotificationVariableCatalog.isCustomerAddressed(notificationClass)) {
            String name = row.recipientAccountId() == null
                    ? null
                    : contacts.displayName(row.tenantId(), row.recipientAccountId())
                            .orElse(null);
            variables.put("customerName", name != null ? name : OrderMessageValues.customerNameFallback(localeTag));
        }
        if (used.contains("courierFirstName")
                && NotificationVariableCatalog.COURIER_NAME_TEMPLATE_KEY.equals(row.templateKey())
                && (notificationClass == NotificationClass.TRANSACTIONAL_OPTIONAL
                        || notificationClass == NotificationClass.TRANSACTIONAL_REQUIRED)) {
            String firstName = courierNames
                    .firstNameCarrying(row.tenantId(), row.subjectId())
                    .orElse(null);
            variables.put(
                    "courierFirstName",
                    firstName != null ? firstName : OrderMessageValues.courierFirstNameFallback(localeTag));
        }
    }

    /**
     * The value to send to, resolved by what shape the endpoint actually is.
     *
     * <p>A contact-point endpoint resolves through ADR 0015 for the length of
     * this call, exactly as before. A binding-shaped endpoint (ADR 0058:
     * operations groups today) needs no PII resolution at all — the binding id
     * itself is already a non-sensitive ADR 0026 reference, so it travels as the
     * recipient value verbatim and the Telegram adapter resolves the chat from it
     * directly. An operations config string does the same. Both are configuration
     * references, never personal data, which is why neither goes near
     * {@link RecipientContactDirectory}.
     */
    private @Nullable String resolveRecipientValue(NotificationRow row) {
        // Frozen by eligibility alongside the template; see render()'s comment.
        UUID endpointId = Objects.requireNonNull(
                row.recipientEndpointId(),
                () -> "Notification %s has no recipient endpoint frozen onto it".formatted(row.id()));
        JdbcNotificationStore.EndpointRow endpoint = notifications
                .endpoint(row.tenantId(), endpointId)
                .orElseThrow(() ->
                        new IllegalStateException("Notification %s has no resolvable endpoint".formatted(row.id())));

        if (endpoint.providerBindingId() != null) {
            return endpoint.providerBindingId().toString();
        }
        if (endpoint.operationsEndpointReference() != null) {
            return endpoint.operationsEndpointReference();
        }
        if (endpoint.courierId() != null) {
            // ADR 0182, D15: a courier is a destination by id, and the number is read from the
            // identity provider for the length of this call and kept nowhere. The read is audited by
            // the source, with its purpose.
            return couriers.number(row.tenantId(), endpoint.courierId(), COURIER_REVEAL_PURPOSE)
                    .orElse(null);
        }
        if (endpoint.pushSubscriptionId() != null) {
            // ADR 0182, D14: a push address is a capability that names a device, and is revealed with
            // its keys by PushDispatches for the length of a send. No other caller of this method
            // (a reconciliation, a status pull) has any use for one.
            return null;
        }
        // ck_endpoint_destination requires exactly one destination column to be set; none of the
        // others is, so this one is.
        UUID contactPointId = Objects.requireNonNull(endpoint.contactPointId());
        return contacts.resolveValue(row.tenantId(), contactPointId, REVEAL_PURPOSE)
                .orElse(null);
    }

    /**
     * A stable id for a status the provider gave us no id for.
     *
     * <p>Derived from the attempt and the status rather than random, so a repeated
     * synchronous answer deduplicates on the unique index instead of appending a
     * second identical row every time a reconcile runs.
     */
    private static String providerEventId(AttemptRow attempt, DispatchOutcome outcome, String normalized) {
        String provided = outcome.externalMessageId();
        return provided != null ? provided + ":" + normalized : attempt.id() + ":" + normalized;
    }

    /** ADR 0146 Decision 1: what a message is for decides whether the gateway account may carry it. */
    private static String purposeOf(NotificationRow row) {
        if ("COURIER_DISPATCH".equals(row.notificationClass())) {
            // ADR 0182: a dispatcher's message to a courier is cleared per gateway account, apart from
            // both marketing and transactional traffic.
            return NotificationDispatch.PURPOSE_COURIER;
        }
        return "MARKETING".equals(row.notificationClass())
                ? NotificationDispatch.PURPOSE_MARKETING
                : NotificationDispatch.PURPOSE_TRANSACTIONAL;
    }

    /**
     * ADR 0146 Decision 7: the segments the provider says it billed, against the
     * platform's estimate of the same text.
     *
     * <p>A mismatch is a metric and nothing else. The estimator is what a tenant's
     * campaign ceiling is enforced against, so a gateway that bills differently from
     * it is a finding worth seeing, and not a reason to refuse or to resend. Bounded
     * tags: the provider type and which way the estimate was wrong, never a count.
     */
    private void compareSegments(NotificationRow row, DispatchOutcome outcome, String body) {
        Integer billed = outcome.providerSegments();
        if (billed == null || !"SMS".equals(row.channel())) {
            return;
        }
        int estimated = SmsSegments.segmentsFor(body);
        if (estimated == billed) {
            return;
        }
        meters.counter(
                        "horecaos.sms.segments.mismatch",
                        "provider",
                        outcome.providerType() == null ? "unknown" : outcome.providerType(),
                        "estimate",
                        estimated < billed ? "under" : "over")
                .increment();
    }

    /**
     * The provider's word mapped onto ADR 0020's ladder.
     *
     * <p>Unknown rather than delivered when a provider says something we do not
     * recognise. Guessing upward is how HorecaOS ends up claiming a delivery the
     * provider never confirmed.
     */
    private static String normalize(@Nullable String providerStatus) {
        if (providerStatus == null) {
            return "UNKNOWN";
        }
        return switch (providerStatus.toUpperCase(java.util.Locale.ROOT)) {
            case "ACCEPTED", "QUEUED", "PENDING" -> "ACCEPTED";
            case "SENT", "DISPATCHED" -> "DISPATCHED";
            case "DELIVERED" -> "DELIVERED";
            case "READ", "SEEN" -> "READ";
            case "FAILED", "UNDELIVERED", "REJECTED" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    /** Exists for the length of one call and is never persisted or logged. */
    private record Rendered(
            @Nullable String subject,
            String body,
            @Nullable UUID coverAssetId,
            @Nullable String targetPath) {}
}
