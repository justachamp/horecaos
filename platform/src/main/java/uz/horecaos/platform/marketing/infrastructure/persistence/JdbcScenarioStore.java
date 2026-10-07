package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Scenario steps, where each guest is, and every decision made about them (ADR 0112).
 *
 * <p>Three properties are enforced by the database and merely relied on here. A step
 * is sent at most once per guest ({@code ux_scenario_decision_sent_once}), so a tick
 * that runs twice cannot message a guest twice. A decision is never rewritten (the
 * grant is INSERT and SELECT, plus the one receipt column). And a guest is one row
 * per scenario, so entering twice is a no-op.
 */
@Repository
public class JdbcScenarioStore {

    private final JdbcClient jdbc;

    public JdbcScenarioStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ steps

    public void insertStep(NewStep step, Instant now) {
        jdbc.sql("""
                INSERT INTO marketing.scenario_steps (
                    id, tenant_id, brand_id, campaign_id, sequence, channel, offer_id, template_key,
                    template_version, wait_after_previous_seconds, continuation_condition, stop_condition,
                    created_at)
                VALUES (:id, :tenantId, :brandId, :campaignId, :sequence, :channel, :offerId, :templateKey,
                    :templateVersion, :wait, :continuation, :stop, :now)
                """)
                .param("id", step.id())
                .param("tenantId", step.tenantId())
                .param("brandId", step.brandId())
                .param("campaignId", step.campaignId())
                .param("sequence", step.sequence())
                .param("channel", step.channel())
                .param("offerId", step.offerId())
                .param("templateKey", step.templateKey())
                .param("templateVersion", step.templateVersion())
                .param("wait", step.waitAfterPreviousSeconds())
                .param("continuation", step.continuationCondition())
                .param("stop", step.stopCondition())
                .param("now", utc(now))
                .update();
    }

    /** Removes a scenario's steps. Only ever called while its campaign is a DRAFT; the service enforces that. */
    public int deleteSteps(UUID tenantId, UUID campaignId) {
        return jdbc.sql(
                        "DELETE FROM marketing.scenario_steps WHERE tenant_id = :tenantId AND campaign_id = :campaignId")
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .update();
    }

    public List<StepRow> steps(UUID tenantId, UUID campaignId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, campaign_id, sequence, channel, offer_id, template_key,
                       template_version, wait_after_previous_seconds, continuation_condition, stop_condition
                  FROM marketing.scenario_steps
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                 ORDER BY sequence
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query(JdbcScenarioStore::step)
                .list();
    }

    // ----------------------------------------------------------- participants

    /**
     * Puts a guest into the scenario. A guest already in it is left exactly as they are.
     *
     * @return true when this call entered them
     */
    public boolean enrol(
            UUID tenantId,
            UUID brandId,
            UUID campaignId,
            UUID customerAccountId,
            boolean inControlGroup,
            @Nullable Instant firstStepDueAt,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO marketing.scenario_participant_state (
                    tenant_id, brand_id, campaign_id, customer_account_id, current_step_sequence,
                    wait_until, in_control_group, entered_at, updated_at)
                VALUES (:tenantId, :brandId, :campaignId, :accountId, 1, :due, :control, :now, :now)
                ON CONFLICT (campaign_id, customer_account_id) DO NOTHING
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("campaignId", campaignId)
                        .param("accountId", customerAccountId)
                        .param("due", inControlGroup ? null : utc(firstStepDueAt))
                        .param("control", inControlGroup)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * The guests whose next step is due, locked so a second runner skips them.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} inside the caller's transaction: two replicas
     * sweeping the same scenario partition its due guests between them rather than
     * both deciding the same guest, and the once-per-step index is the backstop for
     * the case where they somehow still do.
     */
    public List<ParticipantRow> lockDue(UUID tenantId, UUID campaignId, Instant now, int limit) {
        return jdbc.sql("""
                SELECT tenant_id, brand_id, campaign_id, customer_account_id, current_step_sequence,
                       wait_until, in_control_group, outcome, entered_at
                  FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                   AND outcome IS NULL AND NOT in_control_group AND wait_until <= :now
                 ORDER BY wait_until
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .param("now", utc(now))
                .param("limit", limit)
                .query(JdbcScenarioStore::participant)
                .list();
    }

    /** Moves a guest to their next step, waiting until {@code dueAt}. */
    public void advance(UUID tenantId, UUID campaignId, UUID accountId, int nextStep, Instant dueAt, Instant now) {
        jdbc.sql("""
                UPDATE marketing.scenario_participant_state
                   SET current_step_sequence = :step, wait_until = :due, updated_at = :now
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                   AND outcome IS NULL
                """)
                .param("step", nextStep)
                .param("due", utc(dueAt))
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .update();
    }

    /** Holds a guest on the same step until {@code dueAt}: a deferral, never a drop. */
    public void defer(UUID tenantId, UUID campaignId, UUID accountId, Instant dueAt, Instant now) {
        jdbc.sql("""
                UPDATE marketing.scenario_participant_state
                   SET wait_until = :due, updated_at = :now
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                   AND outcome IS NULL
                """)
                .param("due", utc(dueAt))
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .update();
    }

    /** Ends a guest's run. Guarded on the guest still being in progress, so an outcome is written once. */
    public boolean finish(UUID tenantId, UUID campaignId, UUID accountId, String outcome, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.scenario_participant_state
                   SET outcome = :outcome, wait_until = NULL, updated_at = :now
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                   AND outcome IS NULL
                """)
                        .param("outcome", outcome)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("campaignId", campaignId)
                        .param("accountId", accountId)
                        .update()
                == 1;
    }

    /** The highest account id entered so far: the cursor the next enrolment batch resumes after. */
    public Optional<UUID> lastParticipantAccountId(UUID tenantId, UUID campaignId) {
        return jdbc.sql("""
                SELECT customer_account_id FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                 ORDER BY customer_account_id DESC
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query(UUID.class)
                .optional();
    }

    /** Guests still in progress (waiting or due), not counting the withheld control group. */
    public int activeParticipants(UUID tenantId, UUID campaignId) {
        Integer count = jdbc.sql("""
                SELECT count(*) FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                   AND outcome IS NULL AND NOT in_control_group
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public Optional<ParticipantRow> participant(UUID tenantId, UUID campaignId, UUID accountId) {
        return jdbc.sql("""
                SELECT tenant_id, brand_id, campaign_id, customer_account_id, current_step_sequence,
                       wait_until, in_control_group, outcome, entered_at
                  FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId AND customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .query(JdbcScenarioStore::participant)
                .optional();
    }

    /** How many guests are in each state: the outcomes, plus {@code IN_PROGRESS} and {@code CONTROL}. */
    public Map<String, Integer> participantCounts(UUID tenantId, UUID campaignId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT CASE WHEN in_control_group THEN 'CONTROL'
                            WHEN outcome IS NULL THEN 'IN_PROGRESS'
                            ELSE outcome END AS state,
                       count(*) AS total
                  FROM marketing.scenario_participant_state
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                 GROUP BY 1
                 ORDER BY 1
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query((row, number) -> Map.entry(row.getString("state"), row.getInt("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    // -------------------------------------------------------------- decisions

    /**
     * Appends one decision.
     *
     * @return false when it was a second {@code SENT} for the same guest and step,
     *         which the database refuses: the step was already sent, and this call
     *         must not send it again
     */
    public boolean insertDecision(NewDecision decision) {
        return jdbc.sql("""
                INSERT INTO marketing.scenario_step_decisions (
                    id, tenant_id, brand_id, campaign_id, customer_account_id, step_sequence, decision,
                    refusal_reason, reason_text, resolved_channel, attempt_id, decided_at)
                VALUES (:id, :tenantId, :brandId, :campaignId, :accountId, :step, :decision,
                    :reason, :text, :channel, :attemptId, :at)
                ON CONFLICT DO NOTHING
                """)
                        .param("id", decision.id())
                        .param("tenantId", decision.tenantId())
                        .param("brandId", decision.brandId())
                        .param("campaignId", decision.campaignId())
                        .param("accountId", decision.customerAccountId())
                        .param("step", decision.stepSequence())
                        .param("decision", decision.decision())
                        .param("reason", decision.refusalReason())
                        .param("text", decision.reasonText())
                        .param("channel", decision.resolvedChannel())
                        .param("attemptId", decision.attemptId())
                        .param("at", utc(decision.decidedAt()))
                        .update()
                == 1;
    }

    /** A scenario's decisions, newest first; optionally one guest's only. */
    public List<DecisionRow> decisions(UUID tenantId, UUID campaignId, @Nullable UUID accountId, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("campaignId", campaignId);
        parameters.put("accountId", accountId);
        parameters.put("limit", limit);
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, campaign_id, customer_account_id, step_sequence, decision,
                       refusal_reason, reason_text, resolved_channel, attempt_id, acknowledged_at, decided_at
                  FROM marketing.scenario_step_decisions
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                   AND (CAST(:accountId AS uuid) IS NULL OR customer_account_id = :accountId)
                 ORDER BY decided_at DESC, id DESC
                 LIMIT :limit
                """)
                .params(parameters)
                .query(JdbcScenarioStore::decision)
                .list();
    }

    /** A guest's scenario decisions across every scenario of the tenant: what the customer card reads (ADR 0111). */
    public List<DecisionRow> decisionsForGuest(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, campaign_id, customer_account_id, step_sequence, decision,
                       refusal_reason, reason_text, resolved_channel, attempt_id, acknowledged_at, decided_at
                  FROM marketing.scenario_step_decisions
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY decided_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query(JdbcScenarioStore::decision)
                .list();
    }

    public Map<String, Integer> decisionCounts(UUID tenantId, UUID campaignId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT coalesce(refusal_reason, decision) AS label, count(*) AS total
                  FROM marketing.scenario_step_decisions
                 WHERE tenant_id = :tenantId AND campaign_id = :campaignId
                 GROUP BY 1 ORDER BY 1
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .query((row, number) -> Map.entry(row.getString("label"), row.getInt("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    // ------------------------------------------------------- action selection

    /**
     * Whether another scenario has handed this guest an offer since {@code since}.
     *
     * <p>The conflict ADR 0112 names: a second offer-bearing step for a guest whose
     * last one is still fresh. It reads decisions, not the other scenario's plan, so
     * what it protects against is what actually reached the guest.
     */
    public boolean offerSentElsewhereSince(UUID tenantId, UUID accountId, UUID excludingCampaignId, Instant since) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM marketing.scenario_step_decisions d
                     WHERE d.tenant_id = :tenantId AND d.customer_account_id = :accountId
                       AND d.decision = 'SENT' AND d.campaign_id <> :campaignId AND d.decided_at >= :since
                       AND EXISTS (SELECT 1 FROM marketing.scenario_steps s
                                    WHERE s.campaign_id = d.campaign_id AND s.sequence = d.step_sequence
                                      AND s.offer_id IS NOT NULL))
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("campaignId", excludingCampaignId)
                .param("since", utc(since))
                .query(Boolean.class)
                .single();
    }

    /**
     * The consent purpose of a broadcast that is sending right now on this channel and
     * has this guest in its snapshot but has not reached them yet: the "simultaneously
     * due" send a scenario step has to be ranked against.
     */
    public Optional<String> pendingBroadcastPurpose(UUID tenantId, UUID brandId, UUID accountId, String channel) {
        return jdbc.sql("""
                SELECT c.consent_purpose
                  FROM marketing.campaigns c
                  JOIN marketing.audience_snapshot_members m
                    ON m.snapshot_id = c.audience_snapshot_id AND m.tenant_id = c.tenant_id
                       AND m.customer_account_id = :accountId AND m.inclusion_status = 'INCLUDED'
                  LEFT JOIN marketing.campaign_recipients r
                    ON r.campaign_id = c.id AND r.customer_account_id = m.customer_account_id
                 WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId AND c.kind = 'BROADCAST'
                   AND c.status = 'SENDING' AND c.channel = :channel AND r.customer_account_id IS NULL
                 ORDER BY c.started_at NULLS LAST
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .param("channel", channel)
                .query(String.class)
                .optional();
    }

    // ------------------------------------------------------------ measurement

    /**
     * One row per participant with the time of their first order after entering, if any
     * and inside the window: the raw material of the control-group comparison (ADR 0112).
     *
     * <p>An order counts unless it was cancelled, rejected or expired, the same line the
     * customer metric projection draws. Read straight from {@code ordering.orders}, as
     * that projection is, because it is the order fact itself.
     */
    public List<GoalRow> goalRows(UUID tenantId, UUID brandId, UUID campaignId, int windowDays) {
        return jdbc.sql("""
                SELECT p.customer_account_id, p.in_control_group, p.entered_at,
                       (SELECT min(o.created_at) FROM ordering.orders o
                         WHERE o.tenant_id = p.tenant_id AND o.brand_id = :brandId
                           AND o.customer_account_id = p.customer_account_id
                           AND o.created_at > p.entered_at
                           AND o.created_at <= p.entered_at + make_interval(days => :windowDays)
                           AND o.status NOT IN ('CANCELLED', 'REJECTED', 'EXPIRED')) AS first_order_at
                  FROM marketing.scenario_participant_state p
                 WHERE p.tenant_id = :tenantId AND p.campaign_id = :campaignId
                 ORDER BY p.entered_at, p.customer_account_id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("campaignId", campaignId)
                .param("windowDays", windowDays)
                .query((row, number) -> new GoalRow(
                        row.getObject("customer_account_id", UUID.class),
                        row.getBoolean("in_control_group"),
                        row.getObject("entered_at", OffsetDateTime.class).toInstant(),
                        instant(row.getObject("first_order_at", OffsetDateTime.class))))
                .list();
    }

    /**
     * Every contact a participant of this scenario received, from any scenario or
     * broadcast of the tenant, inside the measurement horizon: what an attribution model
     * chooses among.
     */
    public List<ContactRow> contactsOfParticipants(UUID tenantId, UUID campaignId, int windowDays) {
        return jdbc.sql("""
                SELECT c.customer_account_id, c.campaign_id, c.at
                  FROM (
                      SELECT d.customer_account_id, d.campaign_id, d.decided_at AS at
                        FROM marketing.scenario_step_decisions d
                       WHERE d.tenant_id = :tenantId AND d.decision = 'SENT'
                      UNION ALL
                      SELECT r.customer_account_id, r.campaign_id, r.created_at AS at
                        FROM marketing.campaign_recipients r
                        JOIN marketing.campaigns b ON b.id = r.campaign_id AND b.tenant_id = r.tenant_id
                       WHERE r.tenant_id = :tenantId AND b.kind = 'BROADCAST'
                         AND r.status IN ('QUEUED', 'DEFERRED')
                  ) c
                  JOIN marketing.scenario_participant_state p
                    ON p.tenant_id = :tenantId AND p.campaign_id = :campaignId
                       AND p.customer_account_id = c.customer_account_id
                 WHERE c.at >= p.entered_at - make_interval(days => :windowDays)
                   AND c.at <= p.entered_at + make_interval(days => :windowDays)
                 ORDER BY c.at
                """)
                .param("tenantId", tenantId)
                .param("campaignId", campaignId)
                .param("windowDays", windowDays)
                .query((row, number) -> new ContactRow(
                        row.getObject("customer_account_id", UUID.class),
                        row.getObject("campaign_id", UUID.class),
                        row.getObject("at", OffsetDateTime.class).toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ rows

    private static StepRow step(ResultSet row, int number) throws SQLException {
        return new StepRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("campaign_id", UUID.class),
                row.getInt("sequence"),
                row.getString("channel"),
                row.getObject("offer_id", UUID.class),
                row.getString("template_key"),
                row.getObject("template_version", Integer.class),
                row.getInt("wait_after_previous_seconds"),
                row.getString("continuation_condition"),
                row.getString("stop_condition"));
    }

    private static ParticipantRow participant(ResultSet row, int number) throws SQLException {
        return new ParticipantRow(
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("campaign_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getInt("current_step_sequence"),
                instant(row.getObject("wait_until", OffsetDateTime.class)),
                row.getBoolean("in_control_group"),
                row.getString("outcome"),
                row.getObject("entered_at", OffsetDateTime.class).toInstant());
    }

    private static DecisionRow decision(ResultSet row, int number) throws SQLException {
        return new DecisionRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("campaign_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getInt("step_sequence"),
                row.getString("decision"),
                row.getString("refusal_reason"),
                row.getString("reason_text"),
                row.getString("resolved_channel"),
                row.getObject("attempt_id", UUID.class),
                instant(row.getObject("acknowledged_at", OffsetDateTime.class)),
                row.getObject("decided_at", OffsetDateTime.class).toInstant());
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record NewStep(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID campaignId,
            int sequence,
            String channel,
            @Nullable UUID offerId,
            String templateKey,
            @Nullable Integer templateVersion,
            int waitAfterPreviousSeconds,
            String continuationCondition,
            String stopCondition) {}

    public record StepRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID campaignId,
            int sequence,
            String channel,
            @Nullable UUID offerId,
            String templateKey,
            @Nullable Integer templateVersion,
            int waitAfterPreviousSeconds,
            String continuationCondition,
            String stopCondition) {}

    public record ParticipantRow(
            UUID tenantId,
            UUID brandId,
            UUID campaignId,
            UUID customerAccountId,
            int currentStepSequence,
            @Nullable Instant waitUntil,
            boolean inControlGroup,
            @Nullable String outcome,
            Instant enteredAt) {}

    public record NewDecision(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID campaignId,
            UUID customerAccountId,
            int stepSequence,
            String decision,
            @Nullable String refusalReason,
            @Nullable String reasonText,
            @Nullable String resolvedChannel,
            @Nullable UUID attemptId,
            Instant decidedAt) {}

    public record DecisionRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID campaignId,
            UUID customerAccountId,
            int stepSequence,
            String decision,
            @Nullable String refusalReason,
            @Nullable String reasonText,
            @Nullable String resolvedChannel,
            @Nullable UUID attemptId,
            @Nullable Instant acknowledgedAt,
            Instant decidedAt) {}

    public record GoalRow(
            UUID customerAccountId,
            boolean inControlGroup,
            Instant enteredAt,
            @Nullable Instant firstOrderAt) {}

    public record ContactRow(UUID customerAccountId, UUID campaignId, Instant at) {}
}
