package uz.horecaos.platform.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring the maker-checker thresholds (ADR 0027).
 *
 * <p>Until this existed, {@code audit.approval_policies} had no writer anywhere
 * in the platform: no migration seeded a row, no service inserted one, and the
 * application database role held {@code SELECT} and nothing else. Every call to
 * {@code ApprovalService.requireApproval} therefore resolved no policy and
 * answered {@code NotRequired}, and each refund, payout, settlement close and
 * loyalty adjustment that ADR 0027 exists to gate went through on one signature
 * with the audit trail recording it as legitimately unapproved. This is the path
 * that makes the control configurable, so that a tenant that wants four eyes can
 * have them.
 *
 * <p><strong>A policy is versioned, never edited.</strong> The resolved version
 * is snapshotted onto every request it produces, so rewriting a threshold in
 * place would change what the recorded {@code policy_version} means and leave
 * the evidence claiming an approver read words that no longer exist. Publishing
 * a change is a new version row, and the previous open version is closed at the
 * moment the new one starts so the timeline never has two live answers. The one
 * mutation the schema permits — and the one the V0059 grant allows — is setting
 * {@code valid_until}, because resolution takes the highest version whose window
 * is open and a superseding row cannot retire an earlier one on its own.
 *
 * <p>ADR 0050 makes an absent policy explicit per action: the code-owned
 * {@link ApprovalAction} register either permits one signature or refuses the
 * action until an operator authors a valid policy. This service owns the
 * policies and their coverage view; {@code JdbcApprovalService} applies the
 * registered missing-policy mode at the decision point.
 */
@Service
public class ApprovalPolicyService {

    /**
     * Copied verbatim into {@code approval_requests.threshold_description}, which
     * is {@code varchar(500)}. Refused here rather than truncated there, so an
     * approver never reads half a threshold.
     */
    public static final int MAXIMUM_THRESHOLD_LENGTH = 500;

    private static final int MAXIMUM_ACTION_CODE_LENGTH = 128;

    /** Matches a registered operation code, e.g. {@code payments.remedy.record}. */
    private static final String ACTION_CODE_PATTERN = "^[a-z0-9]+(?:[.-][a-z0-9]+)+$";

    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final Clock clock;

    public ApprovalPolicyService(JdbcClient jdbc, AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The policies this tenant owns, newest version of each first.
     *
     * <p>Constrained to the tenant the caller was authorised against. Platform
     * policies, which carry no tenant, are deliberately not listed here: they are
     * HorecaOS's floor rather than the tenant's setting, and a tenant surface that
     * showed them would invite an end-date request this surface refuses anyway.
     */
    public List<PolicyView> list(UUID tenantId, @Nullable String actionCode, boolean includeEnded, int limit) {
        if (actionCode != null && !actionCode.isBlank()) {
            ApprovalAction.require(actionCode);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, brand_id, location_id, legacy_scope_wide, action_code, scope_type,
                       coalesce(threshold_json->>'description', threshold_json::text) AS threshold,
                       required_approver_capability, valid_from, valid_until, version,
                       approved_by, created_at
                  FROM audit.approval_policies
                 WHERE tenant_id = :tenantId
                """);
        if (actionCode != null && !actionCode.isBlank()) {
            sql.append(" AND action_code = :actionCode");
        }
        if (!includeEnded) {
            // A version superseded before it started closes at its own valid_from,
            // so its window is empty and no instant falls inside it. Comparing
            // valid_until against now alone would list such a row as still to come
            // when its valid_from is in the future, which is the one shape an
            // operator most needs not to be told is live.
            sql.append(
                    " AND (valid_until IS NULL" + " OR valid_until > GREATEST(valid_from, CAST(:now AS timestamptz)))");
        }
        sql.append(" ORDER BY action_code, scope_type, version DESC LIMIT :limit");

        var statement = jdbc.sql(sql.toString()).param("tenantId", tenantId).param("limit", limit);
        if (actionCode != null && !actionCode.isBlank()) {
            statement = statement.param("actionCode", actionCode);
        }
        if (!includeEnded) {
            statement = statement.param("now", clock.instant().atOffset(ZoneOffset.UTC));
        }
        return statement.query(ApprovalPolicyService::mapPolicy).list();
    }

    /**
     * The registered approval actions and the policy scopes currently configured
     * for this tenant.
     *
     * <p>This deliberately reports configured scopes rather than claiming every
     * brand or location is covered. A brand policy names one brand and a location
     * policy names one location; the operator can see that distinction and author
     * the exact missing scope instead of trusting an ambiguous green badge.
     */
    public List<PolicyCoverage> coverage(UUID tenantId) {
        Map<String, List<PolicyView>> byAction =
                list(tenantId, null, false, 1_000).stream().collect(Collectors.groupingBy(PolicyView::actionCode));
        return Arrays.stream(ApprovalAction.values())
                .map(action -> new PolicyCoverage(
                        action.code(), action.missingPolicyMode(), byAction.getOrDefault(action.code(), List.of())))
                .toList();
    }

    /**
     * Publishes the next version of a policy, closing the version it replaces.
     *
     * <p>The insert and the close share the caller's transaction with the audit
     * fact, so a policy that took effect always has a record of who made it take
     * effect.
     */
    @Transactional
    public PolicyView author(NewPolicyVersion command) {
        Instant now = clock.instant();
        String actionCode = requireActionCode(command.actionCode());
        String threshold = requireThreshold(command.thresholdDescription());
        Capability approver = requireApproverCapability(command.requiredApproverCapability());
        ResourceScope scope = requireTenantOwnedScope(command.scope());
        ScopeType scopeType = scope.type();
        Instant validFrom = command.validFrom() == null ? now : command.validFrom();

        if (validFrom.isBefore(now)) {
            // A backdated control change reads, later, as though the second
            // signature had been required all along for actions that never got
            // one. The start is now or in the future, never before.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A policy version cannot take effect before it was authored");
        }

        int version = nextVersion(scope, actionCode);
        List<SupersededVersion> superseded = closeOpenVersion(scope, actionCode, validFrom);

        UUID policyId = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO audit.approval_policies (
                        id, tenant_id, brand_id, location_id, action_code, scope_type, threshold_json,
                        required_approver_capability, valid_from, valid_until, version,
                        approved_by, created_at)
                    VALUES (
                        :id, :tenantId, :brandId, :locationId, :actionCode, :scopeType,
                        jsonb_build_object('description', CAST(:threshold AS text)),
                        :approverCapability, :validFrom, NULL, :version,
                        :authoredBy, :now)
                    """)
                    .param("id", policyId)
                    .param("tenantId", scope.tenantId())
                    .param("brandId", scope.brandId())
                    .param("locationId", scope.locationId())
                    .param("actionCode", actionCode)
                    .param("scopeType", scopeType.name())
                    .param("threshold", threshold)
                    .param("approverCapability", approver.code())
                    .param("validFrom", validFrom.atOffset(ZoneOffset.UTC))
                    .param("version", version)
                    .param("authoredBy", command.actor().subject())
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException concurrentAuthor) {
            // uq_approval_policy_version. Two operators publishing at once would
            // otherwise silently produce one version that supersedes nothing.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Another version of this policy was published concurrently; re-read and retry");
        }

        audit.record(AuditFact.of("approval.policy.authored", AuditClass.SECURITY)
                .by(command.actor())
                .at(scope)
                .target("ApprovalPolicy", policyId)
                .because(command.reason())
                .changed(changeDocument(actionCode, scope, version, threshold, approver, validFrom))
                .usingCapability(Capability.APPROVAL_POLICY_MANAGE.code())
                .correlatedBy(policyId.toString())
                .occurredAt(now)
                .build());

        recordSupersessions(command, policyId, actionCode, scope, superseded, now);

        return read(scope.tenantId(), policyId).orElseThrow();
    }

    /**
     * Says, in the audit trail, what publishing this version did to the ones
     * before it.
     *
     * <p>{@code approval.policy.authored} records only the row that was written.
     * The rows that were <em>changed</em> went unmentioned, and one of those
     * changes is not a shortening at all: a version scheduled for a date that has
     * not arrived is closed at its own {@code valid_from}, which is an empty
     * window, and it never governs a single instant. An operator who scheduled a
     * tightening for the first of the month and then published anything else in
     * the meantime lost the tightening silently — the row is still there, still
     * listed under {@code includeEnded}, still carrying the threshold they wrote,
     * and it will never apply. Nothing said so. These facts say so, one per
     * version affected, each targeting the version that changed rather than the
     * version that caused it, so a query on that policy id finds its own ending.
     *
     * <p>Recorded after the insert rather than inside {@link #closeOpenVersion},
     * because the fact names the version that superseded it and that identifier
     * does not exist until the row that carries it does. Same transaction either
     * way, so a publication that fails records neither.
     */
    private void recordSupersessions(
            NewPolicyVersion command,
            UUID policyId,
            String actionCode,
            ResourceScope scope,
            List<SupersededVersion> superseded,
            Instant now) {

        for (SupersededVersion previous : superseded) {
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("actionCode", actionCode);
            changes.put("scopeType", scope.type().name());
            addScopeIdentifiers(changes, scope);
            changes.put("version", previous.version());
            changes.put("supersededByPolicyId", policyId.toString());
            // Absent rather than null when the window was open: AuditFact copies
            // the document into an immutable map, which admits no null value.
            if (previous.validUntil() != null) {
                changes.put("previousValidUntil", previous.validUntil().toString());
            }
            changes.put("validUntil", previous.closesAt().toString());
            // The distinction the operator has to be able to find later. A
            // shortened window governed something; a voided one never will.
            changes.put("neverTookEffect", previous.voided());
            audit.record(AuditFact.of(
                            previous.voided() ? "approval.policy.voided" : "approval.policy.superseded",
                            AuditClass.SECURITY)
                    .by(command.actor())
                    .at(scope)
                    .target("ApprovalPolicy", previous.id())
                    .because(command.reason())
                    .changed(changes)
                    .usingCapability(Capability.APPROVAL_POLICY_MANAGE.code())
                    .correlatedBy(policyId.toString())
                    .occurredAt(now)
                    .build());
        }
    }

    /**
     * Closes a policy version's window, which is how a threshold is retired.
     *
     * <p>This is the operator saying <em>stop requiring approval</em>, and it is
     * the only operation on this surface allowed to leave an instant ungoverned.
     * Two rules keep it to that meaning.
     *
     * <p>Refused on a version that is already closed. Reopening one is not an
     * edit this surface offers: a threshold that should apply again is a new
     * version, which keeps the timeline readable and keeps the only permitted
     * mutation one-way.
     *
     * <p><strong>Refused on a version that has not taken effect yet and
     * superseded one that had</strong>, and that refusal is the interesting one.
     * Publishing a version scheduled for
     * later clamps the version it supersedes to the scheduled instant, so the
     * timeline reads {@code v1 [t0, t0+7d)}, {@code v2 [t0+7d, ∞)}. Cancelling
     * the scheduled v2 used to close it at its own {@code valid_from} — an empty
     * window, legal under {@code ck_approval_policy_validity} and correct for a
     * version <em>superseded</em> before it started, but catastrophic here:
     * v1 stays clamped, nothing succeeds it, and from day seven
     * {@code resolvePolicy} finds no row, {@code requireApproval} answers
     * {@code NotRequired}, and every refund of any size proceeds on one
     * signature. Invisibly — until day seven the live listing still shows one
     * governing version. An operator who asked to cancel a <em>change</em> got
     * "stop requiring approval, in a week".
     *
     * <p>The alternative was to restore the timeline by reopening v1, and it is
     * rejected because it cannot be done exactly. {@code valid_until} is set by
     * two different intentions — an operator retiring a threshold and a
     * supersession clamping one — and a clamp overwrites an earlier end date
     * without recording it, so "put it back" has to guess between {@code NULL}
     * and some forgotten instant. A guess that guesses wrong either re-arms a
     * control the operator retired or extends one they meant to end, silently, in
     * the one table whose readings are evidence. Refusing guesses nothing, and
     * the operator is not stuck: publishing the threshold they want as a new
     * version supersedes the scheduled one — {@link #closeOpenVersion} already
     * handles that shape — and takes effect immediately, so no instant on the
     * timeline is left ungoverned.
     *
     * <p><strong>A scheduled version that superseded nothing is a different case
     * and is cancelled, not refused.</strong> See {@link #cancelScheduled}: the
     * whole argument above rests on there being a clamped predecessor to strand,
     * and a first-ever version has none. Refusing it left an operator who
     * mis-scheduled a control with one escape — publish something that takes
     * effect now — so the only way out of arming the control later was arming it
     * immediately, and the refusal named a superseded version that did not exist.
     *
     * <p>Together with the fact that {@code author} closes every open version,
     * these refusals mean {@code endDate} can only ever target the newest
     * version: while it is in force, or before it starts when nothing stands
     * behind it. The timeline is therefore disarmable only from its tail, only by
     * an operator who said so, and never in a way that quietly ungoverns an
     * instant some other version was covering.
     */
    @Transactional
    public PolicyView endDate(
            UUID tenantId, UUID policyId, @Nullable Instant requestedEnd, ActorRef actor, String reason) {

        Instant now = clock.instant();
        PolicyView policy = read(tenantId, policyId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No approval policy %s in this tenant".formatted(policyId)));

        if (policy.validUntil() != null) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This policy version already ends at %s; publish a new version instead"
                            .formatted(policy.validUntil()));
        }
        if (policy.validFrom().isAfter(now)) {
            Optional<Integer> clamped = versionClampedBy(tenantId, policy);
            if (clamped.isPresent()) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        ("Version %d does not take effect until %s, and cancelling it here would "
                                        + "leave version %d, which it superseded, closed with nothing to "
                                        + "follow it — from that date no policy would govern this action "
                                        + "at all. Publish the threshold you want as a new version "
                                        + "instead; it supersedes this one and takes effect straight away.")
                                .formatted(policy.version(), policy.validFrom(), clamped.get()));
            }
            return cancelScheduled(tenantId, policy, requestedEnd, actor, reason, now);
        }

        Instant end = requestedEnd == null ? now : requestedEnd;
        if (end.isBefore(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A policy cannot be retired retroactively");
        }
        // No clamp to valid_from here any more, and none is reachable: a version
        // that has not started is refused above, so valid_from <= now <= end.

        int closed = jdbc.sql("""
                UPDATE audit.approval_policies
                   SET valid_until = :end
                 WHERE id = :id AND tenant_id = :tenantId AND valid_until IS NULL
                """)
                .param("id", policyId)
                .param("tenantId", tenantId)
                .param("end", end.atOffset(ZoneOffset.UTC))
                .update();

        if (closed != 1) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This policy version was ended concurrently");
        }

        audit.record(AuditFact.of("approval.policy.ended", AuditClass.SECURITY)
                .by(actor)
                .at(auditScope(policy))
                .target("ApprovalPolicy", policyId)
                .because(reason)
                .changed(policyChangeDocument(policy, end, false))
                .usingCapability(Capability.APPROVAL_POLICY_MANAGE.code())
                .correlatedBy(policyId.toString())
                .occurredAt(now)
                .build());

        return read(tenantId, policyId).orElseThrow();
    }

    /**
     * The version this scheduled one clamped, if it clamped one.
     *
     * <p>{@link #closeOpenVersion} closes every version it supersedes at exactly
     * the superseding version's {@code valid_from}, so a predecessor ending at
     * this version's start instant is precisely the row that would be left with
     * nothing to follow it. A version that ends earlier was retired by an
     * operator saying "stop requiring approval", and one whose window is empty
     * never governed anything; neither is resurrected or stranded by cancelling
     * this one.
     */
    private Optional<Integer> versionClampedBy(UUID tenantId, PolicyView policy) {
        return jdbc.sql("""
                SELECT version
                  FROM audit.approval_policies
                 WHERE action_code = :actionCode
                   AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                   AND NOT legacy_scope_wide
                   AND id <> :id
                   AND valid_until = :validFrom
                   AND valid_until > valid_from
                 ORDER BY version DESC
                 LIMIT 1
                """)
                .param("actionCode", policy.actionCode())
                .param("scopeType", policy.scopeType())
                .param("tenantId", tenantId)
                .param("brandId", policy.brandId())
                .param("locationId", policy.locationId())
                .param("id", policy.id())
                .param("validFrom", policy.validFrom().atOffset(ZoneOffset.UTC))
                .query(Integer.class)
                .optional();
    }

    /**
     * Calls off a version that has not taken effect and superseded nothing.
     *
     * <p>The refusal above exists because cancelling a scheduled version normally
     * strands the version it clamped: v1 stays closed at the scheduled instant,
     * nothing succeeds it, and from that date every action of any size proceeds
     * on one signature. That reasoning has a precondition, and a
     * <strong>first-ever</strong> scheduled version does not meet it. It clamped
     * nobody. Cancelling it restores the timeline to exactly what it was the
     * moment before it was published — ungoverned, which is where the operator
     * was standing when they scheduled it by mistake — and takes nothing away
     * that ever governed an instant.
     *
     * <p>Refusing it anyway is not the conservative choice, which is why this
     * exists. The only escape the refusal offered was to publish a version that
     * takes effect <em>immediately</em>, so an operator who mis-scheduled a
     * control was made to arm it now to get out of arming it later. The message
     * also asserted that a superseded version existed, which on this path was
     * simply untrue, and an operator reading it went looking for a row that was
     * never there.
     *
     * <p>The row is closed at its own {@code valid_from} rather than deleted: an
     * empty window governs no instant, {@code ck_approval_policy_validity} admits
     * it, and the threshold stays readable as evidence that somebody published it
     * and somebody called it off.
     */
    private PolicyView cancelScheduled(
            UUID tenantId,
            PolicyView policy,
            @Nullable Instant requestedEnd,
            ActorRef actor,
            String reason,
            Instant now) {

        if (requestedEnd != null && requestedEnd.isAfter(policy.validFrom())) {
            // Ending it after it starts is not a cancellation; it is scheduling a
            // window for a version that is not in force, which this surface does
            // not offer because nothing would follow it either.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    ("Version %d has not taken effect yet, so it can be cancelled outright but not "
                                    + "scheduled to end at %s. Omit the end date to call it off.")
                            .formatted(policy.version(), requestedEnd));
        }

        int cancelled = jdbc.sql("""
                UPDATE audit.approval_policies
                   SET valid_until = valid_from
                 WHERE id = :id AND tenant_id = :tenantId AND valid_until IS NULL
                """)
                .param("id", policy.id())
                .param("tenantId", tenantId)
                .update();

        if (cancelled != 1) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This policy version was ended concurrently");
        }

        audit.record(AuditFact.of("approval.policy.cancelled", AuditClass.SECURITY)
                .by(actor)
                .at(auditScope(policy))
                .target("ApprovalPolicy", policy.id())
                .because(reason)
                .changed(policyChangeDocument(policy, policy.validFrom(), true))
                .usingCapability(Capability.APPROVAL_POLICY_MANAGE.code())
                .correlatedBy(policy.id().toString())
                .occurredAt(now)
                .build());

        return read(tenantId, policy.id()).orElseThrow();
    }

    private Optional<PolicyView> read(UUID tenantId, UUID policyId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, legacy_scope_wide, action_code, scope_type,
                       coalesce(threshold_json->>'description', threshold_json::text) AS threshold,
                       required_approver_capability, valid_from, valid_until, version,
                       approved_by, created_at
                  FROM audit.approval_policies
                 WHERE id = :id AND tenant_id = :tenantId
                """)
                .param("id", policyId)
                .param("tenantId", tenantId)
                .query(ApprovalPolicyService::mapPolicy)
                .optional();
    }

    private int nextVersion(ResourceScope scope, String actionCode) {
        return jdbc.sql("""
                SELECT coalesce(max(version), 0) + 1
                  FROM audit.approval_policies
                 WHERE action_code = :actionCode
                   AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                   AND NOT legacy_scope_wide
                """)
                .param("actionCode", actionCode)
                .param("scopeType", scope.type().name())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .query(Integer.class)
                .single();
    }

    /**
     * Ends every version the new one replaces, at the instant the new one starts.
     *
     * <p>Without this, both stay open. Resolution takes the highest version, so
     * the new one would win — until somebody end-dated it, at which point the
     * superseded threshold would come back to life unannounced.
     *
     * <p>Three shapes have to be closed, and only the first is obvious. A version
     * already in force is cut short at {@code from}. A version whose window still
     * has life after {@code from} — one already end-dated to a later moment,
     * usually because it was itself closed at the start of a version that is now
     * being superseded in turn — is pulled back to {@code from}, which shortens a
     * window rather than reopening one. And a version scheduled to start after
     * {@code from} cannot be end-dated at {@code from} at all:
     * {@code ck_approval_policy_validity} refuses {@code valid_until <
     * valid_from}. It closes at its own {@code valid_from} instead, which is the
     * same shape {@link #endDate} produces for a version retired before it
     * starts: an empty window, a row still readable as evidence that the version
     * was published, and an instant nothing can resolve to. A scheduled version
     * that is superseded before it starts simply never takes effect.
     *
     * <p>The predicate the first version of this had — {@code valid_from <=
     * :from} — meant a version scheduled ahead of time was never closed by the
     * version that superseded it, and {@code author} accepts a future
     * {@code validFrom} by design. Both stayed open, and end-dating the newer one
     * revived the older threshold with nothing in the audit trail to say a policy
     * had taken effect.
     *
     * <p>Returns what it changed, so {@link #recordSupersessions} can say so. The
     * update used to be issued and its row count discarded, which is how voiding
     * a scheduled version became a change nothing recorded.
     */
    private List<SupersededVersion> closeOpenVersion(ResourceScope scope, String actionCode, Instant from) {

        OffsetDateTime at = from.atOffset(ZoneOffset.UTC);
        List<SupersededVersion> affected = jdbc.sql("""
                SELECT id, version, valid_from, valid_until,
                       GREATEST(valid_from, CAST(:from AS timestamptz)) AS closes_at,
                       valid_from >= CAST(:from AS timestamptz) AS voided
                  FROM audit.approval_policies
                 WHERE action_code = :actionCode
                   AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                   AND NOT legacy_scope_wide
                   AND (valid_until IS NULL OR valid_until > CAST(:from AS timestamptz))
                 ORDER BY version
                """)
                .param("actionCode", actionCode)
                .param("scopeType", scope.type().name())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .param("from", at)
                .query((rs, rowNumber) -> {
                    OffsetDateTime validUntil = rs.getObject("valid_until", OffsetDateTime.class);
                    return new SupersededVersion(
                            rs.getObject("id", UUID.class),
                            rs.getInt("version"),
                            validUntil == null ? null : validUntil.toInstant(),
                            rs.getObject("closes_at", OffsetDateTime.class).toInstant(),
                            rs.getBoolean("voided"));
                })
                .list();

        jdbc.sql("""
                UPDATE audit.approval_policies
                   SET valid_until = GREATEST(valid_from, CAST(:from AS timestamptz))
                 WHERE action_code = :actionCode
                   AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                   AND NOT legacy_scope_wide
                   AND (valid_until IS NULL OR valid_until > CAST(:from AS timestamptz))
                """)
                .param("actionCode", actionCode)
                .param("scopeType", scope.type().name())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .param("from", at)
                .update();

        return affected;
    }

    /**
     * One version the publication of another closed, and how.
     *
     * @param closesAt  the {@code valid_until} the close wrote
     * @param voided    true when the close leaves an empty window — the new
     *                  version starts at or before this one did — so this version
     *                  governs no instant at all, then or ever. Distinguished
     *                  from a shortening because the two are different facts: one
     *                  threshold applied and stopped, the other never applied
     */
    private record SupersededVersion(
            UUID id, int version, @Nullable Instant validUntil, Instant closesAt, boolean voided) {}

    private static Map<String, Object> changeDocument(
            String actionCode,
            ResourceScope scope,
            int version,
            String threshold,
            Capability approver,
            Instant validFrom) {

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("actionCode", actionCode);
        document.put("scopeType", scope.type().name());
        addScopeIdentifiers(document, scope);
        document.put("version", version);
        // The threshold is the substance of the change, and it is operator-authored
        // configuration rather than anybody's personal data. An audit entry for a
        // control change that omitted what the control now says would record only
        // that something moved.
        document.put("threshold", threshold);
        document.put("requiredApproverCapability", approver.code());
        document.put("validFrom", validFrom.toString());
        return document;
    }

    /**
     * The scope attached to an audit fact must be the policy's resource, not
     * merely its tenant. Pre-V0082 rows have no truthful resource identifier;
     * their deliberately visible legacy fallback remains a tenant-level fact.
     */
    private static ResourceScope auditScope(PolicyView policy) {
        if (policy.legacyScopeWide()) {
            return ResourceScope.tenant(policy.tenantId());
        }
        // brandId/locationId are @Nullable on PolicyView because a TENANT-scope
        // row genuinely carries neither, but a row whose own scope_type reads
        // BRAND or LOCATION always has the identifier that scope requires — the
        // same guarantee ResourceScope's own compact constructor enforces on the
        // scope it builds.
        return switch (ScopeType.valueOf(policy.scopeType())) {
            case TENANT -> ResourceScope.tenant(policy.tenantId());
            case BRAND ->
                ResourceScope.brand(
                        policy.tenantId(), Objects.requireNonNull(policy.brandId(), "A BRAND policy has a brand ID"));
            case LOCATION ->
                ResourceScope.location(
                        policy.tenantId(),
                        Objects.requireNonNull(policy.brandId(), "A LOCATION policy has a brand ID"),
                        Objects.requireNonNull(policy.locationId(), "A LOCATION policy has a location ID"));
            case PLATFORM -> ResourceScope.platform();
        };
    }

    private static Map<String, Object> policyChangeDocument(
            PolicyView policy, Instant validUntil, boolean neverTookEffect) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("actionCode", policy.actionCode());
        document.put("scopeType", policy.scopeType());
        if (policy.legacyScopeWide()) {
            document.put("legacyScopeWide", true);
        } else {
            addScopeIdentifiers(document, auditScope(policy));
        }
        document.put("version", policy.version());
        document.put("validUntil", validUntil.toString());
        if (neverTookEffect) {
            document.put("neverTookEffect", true);
        }
        return document;
    }

    private static String requireActionCode(String actionCode) {
        if (actionCode == null
                || !actionCode.matches(ACTION_CODE_PATTERN)
                || actionCode.length() > MAXIMUM_ACTION_CODE_LENGTH) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "An action code looks like payments.remedy.record, in lower case");
        }
        ApprovalAction.require(actionCode);
        return actionCode;
    }

    private static String requireThreshold(String threshold) {
        if (threshold == null || threshold.isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A threshold description is required: it is what the approver reads");
        }
        if (threshold.length() > MAXIMUM_THRESHOLD_LENGTH) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A threshold description is at most %d characters".formatted(MAXIMUM_THRESHOLD_LENGTH));
        }
        return threshold;
    }

    /**
     * The named approver capability has to be one the platform declares, or the
     * policy describes a second signature nobody in the system can give.
     */
    private static Capability requireApproverCapability(String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A policy must name the capability its approver has to hold");
        }
        return Capability.find(code)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "No such capability: %s. A policy naming one nobody can hold is unapprovable."
                                .formatted(code)));
    }

    /**
     * A tenant surface authors tenant-owned policies.
     *
     * <p>{@code PLATFORM} rows carry no tenant and are HorecaOS's own floor, so they
     * are not authorable from under {@code /tenants/{tenantId}}: a row written
     * here would carry this tenant's identifier and then never resolve, because
     * the platform link of the scope chain looks for a null tenant.
     */
    private static ResourceScope requireTenantOwnedScope(ResourceScope scope) {
        if (scope == null || scope.type() == ScopeType.PLATFORM) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A tenant authors TENANT, BRAND, or LOCATION policies");
        }
        return scope;
    }

    private static PolicyView mapPolicy(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        OffsetDateTime validUntil = rs.getObject("valid_until", OffsetDateTime.class);
        return new PolicyView(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getBoolean("legacy_scope_wide"),
                rs.getString("action_code"),
                rs.getString("scope_type"),
                rs.getString("threshold"),
                rs.getString("required_approver_capability"),
                rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                validUntil == null ? null : validUntil.toInstant(),
                rs.getInt("version"),
                rs.getString("approved_by"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /**
     * A request to publish the next version of a policy.
     *
     * @param thresholdDescription what the approver will read on the request. The
     *                             stored {@code threshold_json} carries this and
     *                             nothing else, because nothing in the platform
     *                             evaluates a structured threshold today: each
     *                             call site decides for itself whether an action
     *                             is large enough to ask, and then asks
     * @param validFrom            when the version takes effect, or null for now
     */
    public record NewPolicyVersion(
            ResourceScope scope,
            String actionCode,
            String thresholdDescription,
            String requiredApproverCapability,
            @Nullable Instant validFrom,
            ActorRef actor,
            String reason) {}

    /** One version of one policy, as an operator sees it. */
    public record PolicyView(
            UUID id,
            UUID tenantId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            boolean legacyScopeWide,
            String actionCode,
            String scopeType,
            String thresholdDescription,
            String requiredApproverCapability,
            Instant validFrom,
            @Nullable Instant validUntil,
            int version,
            String authoredBy,
            Instant createdAt) {

        /** Whether this version governs actions at the given instant. */
        public boolean isOpenAt(Instant instant) {
            return !validFrom.isAfter(instant) && (validUntil == null || validUntil.isAfter(instant));
        }
    }

    /** One registered action and the non-ended policies the tenant has authored for it. */
    public record PolicyCoverage(
            String actionCode, ApprovalAction.MissingPolicyMode missingPolicyMode, List<PolicyView> configuredScopes) {

        public PolicyCoverage {
            configuredScopes = List.copyOf(configuredScopes);
        }

        public boolean configuredAnywhere() {
            return !configuredScopes.isEmpty();
        }
    }

    private static void addScopeIdentifiers(Map<String, Object> document, ResourceScope scope) {
        if (scope.brandId() != null) {
            document.put("brandId", scope.brandId().toString());
        }
        if (scope.locationId() != null) {
            document.put("locationId", scope.locationId().toString());
        }
    }
}
