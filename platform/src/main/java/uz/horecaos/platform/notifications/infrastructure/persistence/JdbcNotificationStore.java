package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Notification, endpoint, attempt, and preference persistence (ADR 0020).
 *
 * <p>Three rules run through every statement here.
 *
 * <p>The tenant predicate is always inside the query. A notification id is a UUID
 * that reaches Operations from a URL, and a lookup matching on it alone would show
 * one tenant another tenant's delivery record — including the hash that identifies
 * whose number it went to.
 *
 * <p>Every state change is a conditional UPDATE naming the claim it holds. Nothing
 * reads a row, decides, and writes: that pattern is how two workers both send one
 * confirmation, which is the exact failure this module exists to prevent.
 *
 * <p>Nothing here selects, inserts, or returns a contact value. The recipient is a
 * contact-point id and a keyed hash; the value is resolved through ADR 0015 for
 * the length of one provider call and never comes back.
 */
@Repository
public class JdbcNotificationStore {

    private final JdbcClient jdbc;

    public JdbcNotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------- notifications

    /**
     * Creates the intent, once.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on {@code (tenant_id, idempotency_key)} is
     * the whole defence against a customer receiving two confirmations for one
     * order. Both the outbox and the inbox deliver at least once, so a second
     * arrival is expected rather than exceptional, and it lands here.
     *
     * @return true when this call created the row, false when it already existed.
     *         The caller uses this to decide whether anything else needs doing,
     *         never to decide whether the message will be sent
     */
    public boolean createIntent(NewNotification intent) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", intent.notificationId());
        parameters.put("tenantId", intent.tenantId());
        parameters.put("brandId", intent.brandId());
        parameters.put("locationId", intent.locationId());
        parameters.put("class", intent.notificationClass());
        parameters.put("channel", intent.channel());
        parameters.put("templateKey", intent.templateKey());
        parameters.put("subjectType", intent.subjectType());
        parameters.put("subjectId", intent.subjectId());
        parameters.put("accountId", intent.recipientAccountId());
        parameters.put("triggerEventId", intent.triggerEventId());
        parameters.put("idempotencyKey", intent.idempotencyKey());
        parameters.put("variables", intent.triggerVariablesJson());
        parameters.put("scheduledAt", utc(intent.scheduledAt()));
        parameters.put("expiresAt", intent.expiresAt() == null ? null : utc(intent.expiresAt()));
        parameters.put("now", utc(intent.createdAt()));

        return jdbc.sql("""
                INSERT INTO notifications.notifications (
                    id, tenant_id, brand_id, location_id, notification_class, channel,
                    template_key, subject_type, subject_id, recipient_account_id,
                    trigger_event_id, idempotency_key, status, variables, scheduled_at,
                    expires_at, next_attempt_at, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :class, :channel,
                    :templateKey, :subjectType, :subjectId, :accountId,
                    :triggerEventId, :idempotencyKey, 'CREATED', CAST(:variables AS jsonb),
                    :scheduledAt, :expiresAt, :scheduledAt, :now, :now)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """).params(parameters).update() == 1;
    }

    public Optional<NotificationRow> find(UUID tenantId, UUID notificationId) {
        return jdbc.sql(SELECT_NOTIFICATION + """
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", notificationId)
                .query(JdbcNotificationStore::notificationRow)
                .optional();
    }

    public Optional<NotificationRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SELECT_NOTIFICATION + """
                WHERE tenant_id = :tenantId AND idempotency_key = :key
                """)
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query(JdbcNotificationStore::notificationRow)
                .optional();
    }

    /** Every message about one subject, newest first. What Operations reads. */
    public List<NotificationRow> forSubject(UUID tenantId, String subjectType, UUID subjectId) {
        return jdbc.sql(SELECT_NOTIFICATION + """
                WHERE tenant_id = :tenantId AND subject_type = :subjectType
                  AND subject_id = :subjectId
                ORDER BY created_at DESC
                """)
                .param("tenantId", tenantId)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .query(JdbcNotificationStore::notificationRow)
                .list();
    }

    /**
     * Claims due work for one worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} so several nodes share the queue instead of
     * contending on it. The claim pushes {@code next_attempt_at} out by the lease,
     * which is what makes a node dying mid-send recoverable: the row becomes
     * claimable again, and the claimer finds the open attempt and reconciles it
     * rather than sending a second message.
     *
     * <p>The attempt counter increments here rather than after a successful send,
     * so a message that crashes the worker every time still runs out of attempts
     * instead of looping forever.
     */
    public List<NotificationRow> claimDue(Instant now, Instant leaseUntil, int batchSize, UUID claimToken) {
        return jdbc.sql("""
                UPDATE notifications.notifications
                SET claim_token = :token, claimed_at = :now, attempt_count = attempt_count + 1,
                    next_attempt_at = :leaseUntil, updated_at = :now
                WHERE id IN (
                    SELECT id FROM notifications.notifications
                    WHERE status IN ('CREATED', 'READY', 'SENDING', 'RETRY_PENDING',
                                     'UNCERTAIN', 'RECONCILING')
                      AND next_attempt_at <= :now
                    ORDER BY next_attempt_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                RETURNING id, tenant_id, brand_id, location_id, notification_class, channel,
                          template_key, template_id, template_version, locale, subject_type,
                          subject_id, recipient_endpoint_id, recipient_account_id,
                          trigger_event_id, idempotency_key, status, suppression_reason,
                          variables::text AS variables, variables_hash, rendered_content_hash,
                          scheduled_at, expires_at, attempt_count, next_attempt_at,
                          claim_token, terminal_at, last_error, version, created_at
                """)
                .param("token", claimToken)
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("batchSize", batchSize)
                .query(JdbcNotificationStore::notificationRow)
                .list();
    }

    /**
     * Freezes what this message will say and who it goes to.
     *
     * <p>Written once, at eligibility, and never read from the template again. A
     * tenant activating new wording between the intent and the attempt must not
     * change what this message says: the version chosen is the version sent, and
     * is the version an auditor is shown.
     */
    public boolean markReady(
            UUID tenantId,
            UUID notificationId,
            UUID claimToken,
            UUID templateId,
            int templateVersion,
            String locale,
            UUID accountId,
            UUID endpointId,
            String variablesJson,
            String variablesHash,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", notificationId);
        parameters.put("token", claimToken);
        parameters.put("templateId", templateId);
        parameters.put("templateVersion", templateVersion);
        parameters.put("locale", locale);
        parameters.put("accountId", accountId);
        parameters.put("endpointId", endpointId);
        parameters.put("variables", variablesJson);
        parameters.put("variablesHash", variablesHash);
        parameters.put("now", utc(now));

        return jdbc.sql("""
                UPDATE notifications.notifications
                SET status = 'READY', template_id = :templateId,
                    template_version = :templateVersion, locale = :locale,
                    recipient_account_id = :accountId,
                    recipient_endpoint_id = :endpointId, variables = CAST(:variables AS jsonb),
                    variables_hash = :variablesHash, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND claim_token = :token
                  AND status = 'CREATED'
                """).params(parameters).update() == 1;
    }

    /**
     * Records that this message will never be sent, and why.
     *
     * <p>A refused message is a fact rather than an absence. Dropping it silently
     * is what makes "why did the customer not get their confirmation?"
     * unanswerable, which is the question support actually receives.
     */
    public boolean markSuppressed(
            UUID tenantId, UUID notificationId, @Nullable UUID claimToken, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.notifications
                SET status = 'SUPPRESSED', suppression_reason = :reason, claim_token = NULL,
                    claimed_at = NULL, terminal_at = :now, version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND claim_token = :token
                  AND status IN ('CREATED', 'READY')
                """)
                        .param("tenantId", tenantId)
                        .param("id", notificationId)
                        .param("token", claimToken)
                        .param("reason", reason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean markSending(
            UUID tenantId, UUID notificationId, @Nullable UUID claimToken, String renderedContentHash, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.notifications
                SET status = 'SENDING', rendered_content_hash = :hash, version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND claim_token = :token
                  AND status IN ('READY', 'SENDING', 'RETRY_PENDING', 'UNCERTAIN', 'RECONCILING')
                """)
                        .param("tenantId", tenantId)
                        .param("id", notificationId)
                        .param("token", claimToken)
                        .param("hash", renderedContentHash)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Marks a claimed message as being reconciled, keeping the claim.
     *
     * <p>The claim is held across the provider query on purpose. Releasing it would
     * let a second worker pick the row up and send the message whose fate this call
     * is in the middle of establishing.
     */
    public boolean markReconciling(UUID tenantId, UUID notificationId, @Nullable UUID claimToken, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.notifications
                SET status = 'RECONCILING', version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND claim_token = :token
                """)
                        .param("tenantId", tenantId)
                        .param("id", notificationId)
                        .param("token", claimToken)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Settles a claimed message.
     *
     * <p>One method for every outcome, so releasing the claim and recording the
     * result cannot be done separately — a settle that forgot to clear the token
     * would leave the row unclaimable by anything until the lease expired.
     *
     * @param nextAttemptAt when the row becomes claimable again. Meaningless for a
     *                      terminal status, which the claim query excludes by
     *                      status rather than by time
     */
    public boolean settle(
            UUID tenantId,
            UUID notificationId,
            @Nullable UUID claimToken,
            String status,
            Instant nextAttemptAt,
            @Nullable String lastError,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", notificationId);
        parameters.put("token", claimToken);
        parameters.put("status", status);
        parameters.put("nextAttemptAt", utc(nextAttemptAt));
        parameters.put("lastError", lastError);
        parameters.put("now", utc(now));

        return jdbc.sql("""
                UPDATE notifications.notifications
                SET status = :status, claim_token = NULL, claimed_at = NULL,
                    next_attempt_at = :nextAttemptAt, last_error = :lastError,
                    terminal_at = CASE
                        WHEN :status IN ('DELIVERED', 'FAILED_TERMINAL', 'EXPIRED', 'MANUAL_REVIEW')
                        THEN :now ELSE NULL END,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND claim_token = :token
                """).params(parameters).update() == 1;
    }

    /**
     * Puts a settled message back in the queue for an operator.
     *
     * <p>Back to {@code CREATED}, so the whole gate runs again. Reopening straight
     * to {@code READY} would let a retry send a message consent has since refused,
     * which ADR 0020 forbids an operator from being able to do.
     *
     * <p>The suppression reason is cleared with the status because the CHECK on this
     * table binds the two together, and the attempt counter is reset because the
     * operator is asking for a fresh set of attempts rather than the tail of an
     * exhausted one.
     *
     * <p>Conditional on the version read, so two operators pressing retry at once
     * produce one reopening rather than two.
     */
    public boolean reopenForRetry(UUID tenantId, UUID notificationId, int expectedVersion, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.notifications
                SET status = 'CREATED', suppression_reason = NULL, terminal_at = NULL,
                    attempt_count = 0, next_attempt_at = :now, claim_token = NULL,
                    claimed_at = NULL, last_error = :reason, version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                  AND status IN ('SUPPRESSED', 'FAILED_TERMINAL', 'EXPIRED', 'MANUAL_REVIEW')
                """)
                        .param("tenantId", tenantId)
                        .param("id", notificationId)
                        .param("expectedVersion", expectedVersion)
                        .param("reason", reason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    // -------------------------------------------------------------- endpoints

    /**
     * The endpoint standing for one ADR 0015 contact point, created on first use.
     *
     * <p>An upsert rather than a read-then-insert: two confirmations for the same
     * customer arriving together would both find nothing and both insert, and one
     * would fail on the unique index after doing its half of the work.
     */
    public UUID ensureEndpoint(
            UUID tenantId,
            UUID accountId,
            String endpointType,
            UUID contactPointId,
            String normalizedHash,
            String verificationStatus,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO notifications.recipient_endpoints (
                    id, tenant_id, customer_account_id, endpoint_type, contact_point_id,
                    normalized_hash, verification_status, status, created_at, updated_at)
                VALUES (:id, :tenantId, :accountId, :type, :contactPointId,
                    :hash, :verification, 'ACTIVE', :now, :now)
                ON CONFLICT (tenant_id, contact_point_id) WHERE contact_point_id IS NOT NULL
                DO UPDATE SET verification_status = excluded.verification_status,
                              normalized_hash = excluded.normalized_hash,
                              updated_at = excluded.updated_at
                RETURNING id
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("type", endpointType)
                .param("contactPointId", contactPointId)
                .param("hash", normalizedHash)
                .param("verification", verificationStatus)
                .param("now", utc(now))
                .query(UUID.class)
                .single();
    }

    public Optional<EndpointRow> endpoint(UUID tenantId, UUID endpointId) {
        return jdbc.sql("""
                SELECT id, customer_account_id, endpoint_type, contact_point_id,
                       operations_endpoint_reference, normalized_hash, verification_status, status
                FROM notifications.recipient_endpoints
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", endpointId)
                .query((row, number) -> new EndpointRow(
                        row.getObject("id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("endpoint_type"),
                        row.getObject("contact_point_id", UUID.class),
                        row.getString("operations_endpoint_reference"),
                        row.getString("normalized_hash"),
                        row.getString("verification_status"),
                        row.getString("status")))
                .optional();
    }

    // --------------------------------------------------------------- attempts

    /**
     * The provider request that is still in play for this message, if any.
     *
     * <p>Found before anything is sent. An attempt left {@code REQUESTED} by a node
     * that died, or left {@code UNCERTAIN} by a timeout, must be reconciled rather
     * than repeated: the provider may already have delivered it, and repeating is
     * how one confirmation becomes two.
     */
    public Optional<AttemptRow> openAttempt(UUID tenantId, UUID notificationId) {
        return jdbc.sql(SELECT_ATTEMPT + """
                WHERE tenant_id = :tenantId AND notification_id = :notificationId
                  AND status IN ('REQUESTED', 'RETRYABLE_FAILURE', 'UNCERTAIN')
                ORDER BY attempt_number DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("notificationId", notificationId)
                .query(JdbcNotificationStore::attemptRow)
                .optional();
    }

    public List<AttemptRow> attempts(UUID tenantId, UUID notificationId) {
        return jdbc.sql(SELECT_ATTEMPT + """
                WHERE tenant_id = :tenantId AND notification_id = :notificationId
                ORDER BY attempt_number
                """)
                .param("tenantId", tenantId)
                .param("notificationId", notificationId)
                .query(JdbcNotificationStore::attemptRow)
                .list();
    }

    public void insertAttempt(
            UUID id,
            UUID tenantId,
            UUID notificationId,
            String channel,
            @Nullable UUID providerBindingId,
            @Nullable String providerType,
            int attemptNumber,
            String providerIdempotencyKey,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", tenantId);
        parameters.put("notificationId", notificationId);
        parameters.put("channel", channel);
        parameters.put("bindingId", providerBindingId);
        parameters.put("providerType", providerType);
        parameters.put("attemptNumber", attemptNumber);
        parameters.put("key", providerIdempotencyKey);
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO notifications.delivery_attempts (
                    id, tenant_id, notification_id, channel, provider_binding_id, provider_type,
                    attempt_number, provider_idempotency_key, status, requested_at,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :notificationId, :channel, :bindingId, :providerType,
                    :attemptNumber, :key, 'REQUESTED', :now, :now, :now)
                """).params(parameters).update();
    }

    public int nextAttemptNumber(UUID tenantId, UUID notificationId) {
        return jdbc.sql("""
                SELECT coalesce(max(attempt_number), 0) + 1
                FROM notifications.delivery_attempts
                WHERE tenant_id = :tenantId AND notification_id = :notificationId
                """)
                .param("tenantId", tenantId)
                .param("notificationId", notificationId)
                .query(Integer.class)
                .single();
    }

    /**
     * Records what the provider said.
     *
     * <p>{@code acknowledged_at} is set only for a status the provider actually
     * confirmed. ADR 0020 forbids treating an accepted response as delivered:
     * overstating a guarantee the provider did not give is how it ends up quoted
     * in a support conversation and then in a dispute.
     */
    public void settleAttempt(
            UUID tenantId,
            UUID attemptId,
            String status,
            @Nullable String externalMessageId,
            @Nullable String failureCode,
            @Nullable UUID providerBindingId,
            @Nullable String providerType,
            @Nullable Instant acknowledgedAt,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", attemptId);
        parameters.put("status", status);
        parameters.put("externalMessageId", externalMessageId);
        parameters.put("failureCode", failureCode);
        parameters.put("bindingId", providerBindingId);
        parameters.put("providerType", providerType);
        parameters.put("acknowledgedAt", acknowledgedAt == null ? null : utc(acknowledgedAt));
        parameters.put("now", utc(now));

        // The binding is coalesced rather than assigned. It is learned when the
        // gateway answers, and a later settle that could not resolve one must not
        // erase the record of which account already handled this attempt.
        jdbc.sql("""
                UPDATE notifications.delivery_attempts
                SET status = :status, uncertain_outcome = (:status = 'UNCERTAIN'),
                    external_message_id = coalesce(:externalMessageId, external_message_id),
                    provider_binding_id = coalesce(:bindingId, provider_binding_id),
                    provider_type = coalesce(:providerType, provider_type),
                    failure_code = :failureCode, acknowledged_at = :acknowledgedAt,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                """).params(parameters).update();
    }

    /**
     * Appends one provider status fact.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on the provider's own event id, so a status
     * delivered twice or out of order is recorded once. ADR 0020 is explicit that a
     * duplicate must not regress a terminal status, and the cheapest way to honour
     * that is for the second copy never to be stored.
     */
    public void recordStatusEvent(
            UUID tenantId,
            UUID attemptId,
            String providerEventId,
            String normalizedStatus,
            @Nullable String providerStatus,
            Instant occurredAt,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("tenantId", tenantId);
        parameters.put("attemptId", attemptId);
        parameters.put("providerEventId", providerEventId);
        parameters.put("normalizedStatus", normalizedStatus);
        parameters.put("providerStatus", providerStatus);
        parameters.put("occurredAt", utc(occurredAt));
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO notifications.delivery_status_events (
                    id, tenant_id, attempt_id, provider_event_id, normalized_status,
                    provider_status, occurred_at, recorded_at)
                VALUES (:id, :tenantId, :attemptId, :providerEventId, :normalizedStatus,
                    :providerStatus, :occurredAt, :now)
                ON CONFLICT (tenant_id, attempt_id, provider_event_id) DO NOTHING
                """).params(parameters).update();
    }

    public List<StatusEventRow> statusEvents(UUID tenantId, UUID attemptId) {
        return jdbc.sql("""
                SELECT provider_event_id, normalized_status, provider_status, occurred_at
                FROM notifications.delivery_status_events
                WHERE tenant_id = :tenantId AND attempt_id = :attemptId
                ORDER BY occurred_at, recorded_at
                """)
                .param("tenantId", tenantId)
                .param("attemptId", attemptId)
                .query((row, number) -> new StatusEventRow(
                        row.getString("provider_event_id"),
                        row.getString("normalized_status"),
                        row.getString("provider_status"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    // ------------------------------------------------------------ preferences

    /**
     * The preference that applies, brand override first.
     *
     * <p>Same precedence as template resolution, and deliberately the same shape:
     * two different orderings for "brand beats tenant" in one module is how the two
     * end up disagreeing about which brand a customer opted out of.
     */
    public Optional<PreferenceRow> effectivePreference(
            UUID tenantId, UUID accountId, UUID brandId, String notificationClass, String channel) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("accountId", accountId);
        parameters.put("brandId", brandId);
        parameters.put("class", notificationClass);
        parameters.put("channel", channel);

        return jdbc.sql("""
                SELECT id, brand_id, notification_class, channel, enabled, quiet_hours_start,
                       quiet_hours_end, timezone, version
                FROM notifications.notification_preferences
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND notification_class = :class AND channel = :channel
                  AND (brand_id = :brandId OR brand_id IS NULL)
                ORDER BY brand_id NULLS LAST
                LIMIT 1
                """)
                .params(parameters)
                .query(JdbcNotificationStore::preferenceRow)
                .optional();
    }

    public List<PreferenceRow> preferences(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, brand_id, notification_class, channel, enabled, quiet_hours_start,
                       quiet_hours_end, timezone, version
                FROM notifications.notification_preferences
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                ORDER BY notification_class, channel, brand_id NULLS LAST
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcNotificationStore::preferenceRow)
                .list();
    }

    /**
     * Sets one preference.
     *
     * <p>The conflict targets are the two partial indexes, so the tenant-wide row
     * and a brand override are separate rows that never overwrite one another. A
     * single upsert over a nullable brand column would treat them as unrelated and
     * insert duplicates, because NULL does not compare equal to itself.
     */
    public void upsertPreference(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String notificationClass,
            String channel,
            boolean enabled,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", UUID.randomUUID());
        parameters.put("tenantId", tenantId);
        parameters.put("accountId", accountId);
        parameters.put("brandId", brandId);
        parameters.put("class", notificationClass);
        parameters.put("channel", channel);
        parameters.put("enabled", enabled);
        parameters.put("now", utc(now));

        String conflictTarget = brandId == null
                ? "(tenant_id, customer_account_id, notification_class, channel) WHERE brand_id IS NULL"
                : "(tenant_id, customer_account_id, brand_id, notification_class, channel) "
                        + "WHERE brand_id IS NOT NULL";

        jdbc.sql("""
                INSERT INTO notifications.notification_preferences (
                    id, tenant_id, customer_account_id, brand_id, notification_class, channel,
                    enabled, created_at, updated_at)
                VALUES (:id, :tenantId, :accountId, :brandId, :class, :channel,
                    :enabled, :now, :now)
                ON CONFLICT %s
                DO UPDATE SET enabled = excluded.enabled,
                              version = notifications.notification_preferences.version + 1,
                              updated_at = excluded.updated_at
                """.formatted(conflictTarget)).params(parameters).update();
    }

    // ------------------------------------------------------------------- rows

    private static final String SELECT_NOTIFICATION = """
            SELECT id, tenant_id, brand_id, location_id, notification_class, channel,
                   template_key, template_id, template_version, locale, subject_type,
                   subject_id, recipient_endpoint_id, recipient_account_id, trigger_event_id,
                   idempotency_key, status, suppression_reason, variables::text AS variables,
                   variables_hash, rendered_content_hash, scheduled_at, expires_at,
                   attempt_count, next_attempt_at, claim_token, terminal_at, last_error,
                   version, created_at
            FROM notifications.notifications
            """;

    private static final String SELECT_ATTEMPT = """
            SELECT id, notification_id, channel, provider_binding_id, provider_type,
                   attempt_number, provider_idempotency_key, status, external_message_id,
                   failure_code, uncertain_outcome, requested_at, acknowledged_at
            FROM notifications.delivery_attempts
            """;

    private static NotificationRow notificationRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new NotificationRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("notification_class"),
                row.getString("channel"),
                row.getString("template_key"),
                row.getObject("template_id", UUID.class),
                // getInt answers 0 for SQL NULL, and a message awaiting eligibility
                // has no template version at all. A silent zero here would resolve
                // to version 0 of a template and find nothing, presenting as a
                // missing translation rather than as an unresolved message.
                row.getObject("template_version", Integer.class),
                row.getString("locale"),
                row.getString("subject_type"),
                row.getObject("subject_id", UUID.class),
                row.getObject("recipient_endpoint_id", UUID.class),
                row.getObject("recipient_account_id", UUID.class),
                row.getObject("trigger_event_id", UUID.class),
                row.getString("idempotency_key"),
                row.getString("status"),
                row.getString("suppression_reason"),
                row.getString("variables"),
                row.getString("variables_hash"),
                row.getString("rendered_content_hash"),
                requireInstant(row.getObject("scheduled_at", OffsetDateTime.class)),
                instant(row.getObject("expires_at", OffsetDateTime.class)),
                row.getInt("attempt_count"),
                requireInstant(row.getObject("next_attempt_at", OffsetDateTime.class)),
                row.getObject("claim_token", UUID.class),
                instant(row.getObject("terminal_at", OffsetDateTime.class)),
                row.getString("last_error"),
                row.getInt("version"),
                requireInstant(row.getObject("created_at", OffsetDateTime.class)));
    }

    private static AttemptRow attemptRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new AttemptRow(
                row.getObject("id", UUID.class),
                row.getObject("notification_id", UUID.class),
                row.getString("channel"),
                row.getObject("provider_binding_id", UUID.class),
                row.getString("provider_type"),
                row.getInt("attempt_number"),
                row.getString("provider_idempotency_key"),
                row.getString("status"),
                row.getString("external_message_id"),
                row.getString("failure_code"),
                row.getBoolean("uncertain_outcome"),
                requireInstant(row.getObject("requested_at", OffsetDateTime.class)),
                instant(row.getObject("acknowledged_at", OffsetDateTime.class)));
    }

    private static PreferenceRow preferenceRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new PreferenceRow(
                row.getObject("id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("notification_class"),
                row.getString("channel"),
                row.getBoolean("enabled"),
                row.getObject("quiet_hours_start", java.time.LocalTime.class),
                row.getObject("quiet_hours_end", java.time.LocalTime.class),
                row.getString("timezone"),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Converts a column the schema declares {@code NOT NULL}. {@link
     * java.sql.ResultSet#getObject} is typed nullable regardless of the column
     * constraint, so this asserts the invariant instead of silently widening every
     * caller's return type to {@code @Nullable}.
     */
    private static Instant requireInstant(@Nullable OffsetDateTime value) {
        return java.util.Objects.requireNonNull(value, "NOT NULL column returned null").toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * The intent as it is created, before anything has been resolved about it.
     *
     * @param triggerVariablesJson what the triggering event carried and the order
     *                             row does not. A rejection reason code lives on
     *                             {@code OrderRejected} and nowhere else, so it is
     *                             captured here or it is gone by the time
     *                             eligibility runs
     */
    public record NewNotification(
            UUID notificationId,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String notificationClass,
            String channel,
            String templateKey,
            String subjectType,
            UUID subjectId,
            @Nullable UUID recipientAccountId,
            UUID triggerEventId,
            String idempotencyKey,
            String triggerVariablesJson,
            Instant scheduledAt,
            Instant expiresAt,
            Instant createdAt) {}

    /**
     * The notification row, as claimed and settled by the worker.
     *
     * @param templateVersion null until eligibility freezes one, which is a
     *                        different thing from version zero
     */
    public record NotificationRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String notificationClass,
            String channel,
            String templateKey,
            @Nullable UUID templateId,
            @Nullable Integer templateVersion,
            @Nullable String locale,
            String subjectType,
            UUID subjectId,
            @Nullable UUID recipientEndpointId,
            @Nullable UUID recipientAccountId,
            UUID triggerEventId,
            String idempotencyKey,
            String status,
            @Nullable String suppressionReason,
            String variablesJson,
            @Nullable String variablesHash,
            @Nullable String renderedContentHash,
            Instant scheduledAt,
            @Nullable Instant expiresAt,
            int attemptCount,
            Instant nextAttemptAt,
            @Nullable UUID claimToken,
            @Nullable Instant terminalAt,
            @Nullable String lastError,
            int version,
            Instant createdAt) {}

    public record EndpointRow(
            UUID id,
            @Nullable UUID customerAccountId,
            String endpointType,
            @Nullable UUID contactPointId,
            @Nullable String operationsEndpointReference,
            String normalizedHash,
            String verificationStatus,
            String status) {}

    public record AttemptRow(
            UUID id,
            UUID notificationId,
            String channel,
            @Nullable UUID providerBindingId,
            @Nullable String providerType,
            int attemptNumber,
            String providerIdempotencyKey,
            String status,
            @Nullable String externalMessageId,
            @Nullable String failureCode,
            boolean uncertainOutcome,
            Instant requestedAt,
            @Nullable Instant acknowledgedAt) {}

    public record StatusEventRow(
            String providerEventId, String normalizedStatus, String providerStatus, Instant occurredAt) {}

    public record PreferenceRow(
            UUID id,
            UUID brandId,
            String notificationClass,
            String channel,
            boolean enabled,
            java.time.LocalTime quietHoursStart,
            java.time.LocalTime quietHoursEnd,
            String timezone,
            int version) {}
}

