package uz.horecaos.platform.notifications.application;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.NotificationStatus;
import uz.horecaos.platform.notifications.domain.TemplateRenderer;
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

    private final JdbcNotificationStore notifications;
    private final JdbcTemplateStore templates;
    private final RecipientContactDirectory contacts;
    private final NotificationTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maximumAttempts;
    private final Duration retryBackoff;

    public NotificationDispatchService(
            JdbcNotificationStore notifications,
            JdbcTemplateStore templates,
            RecipientContactDirectory contacts,
            NotificationTransport transport,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.notifications.max-attempts:8}") int maximumAttempts,
            @Value("${horecaos.notifications.retry-backoff:PT30S}") Duration retryBackoff) {
        this.notifications = notifications;
        this.templates = templates;
        this.contacts = contacts;
        this.transport = transport;
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

        String recipientValue = contacts.resolveValue(row.tenantId(), endpointContactPoint(row), REVEAL_PURPOSE)
                .orElse(null);
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
                org.slf4j.MDC.get("correlationId")));

        record(row, attempt, outcome, clock.instant());
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

        DispatchOutcome outcome = transport.reconcile(
                row.tenantId(), row.brandId(), row.locationId(), row.channel(), attempt.providerIdempotencyKey());
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

    private void record(NotificationRow row, AttemptRow attempt, DispatchOutcome outcome, Instant now) {
        switch (outcome.status()) {
            case ACCEPTED -> {
                // The attempt records how strong the provider's answer actually
                // was. A gateway that says "queued" has not told us the handset saw
                // anything, and ADR 0020 forbids HorecaOS promising more than it was
                // given — so only a confirmed delivery gets an acknowledgement time.
                String normalized = normalize(outcome.providerStatus());
                boolean confirmed = "DELIVERED".equals(normalized) || "READ".equals(normalized);

                notifications.settleAttempt(
                        row.tenantId(),
                        attempt.id(),
                        confirmed ? "DELIVERED" : "ACCEPTED",
                        outcome.externalMessageId(),
                        null,
                        outcome.providerBindingId(),
                        outcome.providerType(),
                        confirmed ? now : null,
                        now);
                notifications.recordStatusEvent(
                        row.tenantId(),
                        attempt.id(),
                        providerEventId(attempt, outcome),
                        normalized,
                        outcome.providerStatus(),
                        now,
                        now);
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
        // Eligibility is what freezes these three onto the row (markReady), and
        // this method only ever runs on a row that reached READY; a row missing
        // any of them here is a data fault rather than an unresolved message.
        UUID templateId = Objects.requireNonNull(
                row.templateId(), () -> "Notification %s has no template frozen onto it".formatted(row.id()));
        int templateVersion = Objects.requireNonNull(
                row.templateVersion(),
                () -> "Notification %s has no template version frozen onto it".formatted(row.id()));
        String locale = Objects.requireNonNull(
                row.locale(), () -> "Notification %s has no locale frozen onto it".formatted(row.id()));

        VersionRow version = templates
                .version(row.tenantId(), templateId, templateVersion, locale)
                .orElseThrow(() -> new IllegalStateException(
                        "The template version frozen onto notification %s no longer exists".formatted(row.id())));

        Map<String, String> variables = objectMapper.readValue(row.variablesJson(), VARIABLES_TYPE);
        // The body template is never null (a template version cannot be saved
        // without one), so its rendering is asserted non-null even though
        // TemplateRenderer.render's return type is nullable for the subject case.
        String body = Objects.requireNonNull(TemplateRenderer.render(version.bodyTemplate(), variables));
        return new Rendered(TemplateRenderer.render(version.subjectTemplate(), variables), body);
    }

    private UUID endpointContactPoint(NotificationRow row) {
        // Frozen by eligibility alongside the template; see render()'s comment.
        UUID endpointId = Objects.requireNonNull(
                row.recipientEndpointId(),
                () -> "Notification %s has no recipient endpoint frozen onto it".formatted(row.id()));
        return notifications
                .endpoint(row.tenantId(), endpointId)
                .map(JdbcNotificationStore.EndpointRow::contactPointId)
                .orElseThrow(() ->
                        new IllegalStateException("Notification %s has no resolvable endpoint".formatted(row.id())));
    }

    /**
     * A stable id for a status the provider gave us no id for.
     *
     * <p>Derived from the attempt and the status rather than random, so a repeated
     * synchronous answer deduplicates on the unique index instead of appending a
     * second identical row every time a reconcile runs.
     */
    private static String providerEventId(AttemptRow attempt, DispatchOutcome outcome) {
        String provided = outcome.externalMessageId();
        return provided != null
                ? provided + ":" + normalize(outcome.providerStatus())
                : attempt.id() + ":" + normalize(outcome.providerStatus());
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
    private record Rendered(@Nullable String subject, String body) {}
}

