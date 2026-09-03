package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.domain.AudiencePredicate;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.MetricDefinitions;
import uz.horecaos.platform.marketing.domain.RefusalReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore.AudienceRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore.CandidateRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;

/**
 * Defining an audience, and evaluating it into a snapshot (ADR 0044).
 *
 * <p>An audience is a query over personal data, so this service is bounded rather
 * than general. The predicates it accepts come from a closed catalogue, they run
 * against {@code marketing.customer_metrics} in the tenant's own database, and the
 * result never leaves the platform. There is no upload to an advertising platform,
 * no hashed-contact match file, and no tag that receives a segment name — not
 * because none has been built yet, but because a segment leaving the platform is a
 * disclosure of personal data to a new controller, which needs a lawful basis, a
 * processor agreement, and a consent purpose ADR 0015 does not carry.
 *
 * <p>Building a snapshot evaluates the predicates and then subtracts, recording
 * the reason per exclusion. Both halves are written down. The excluded rows cost
 * storage and they are the evidence: somebody excluded here never becomes a
 * campaign recipient, so without them "why did this customer not get it" has no
 * answer anywhere for exactly the people who need it answered.
 */
@Service
public class AudienceService {

    private static final Logger log = LoggerFactory.getLogger(AudienceService.class);

    /** At most this many predicates on one audience. */
    private static final int MAX_PREDICATES = 12;

    private final JdbcAudienceStore audiences;
    private final JdbcCustomerMetricStore metrics;
    private final JdbcEngagementStore engagement;
    private final MarketingEligibility eligibility;
    private final AuditRecorder audit;
    private final Clock clock;

    public AudienceService(
            JdbcAudienceStore audiences,
            JdbcCustomerMetricStore metrics,
            JdbcEngagementStore engagement,
            MarketingEligibility eligibility,
            AuditRecorder audit,
            Clock clock) {
        this.audiences = audiences;
        this.metrics = metrics;
        this.engagement = engagement;
        this.eligibility = eligibility;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID define(
            UUID tenantId,
            UUID brandId,
            String name,
            @Nullable String description,
            List<AudiencePredicate> predicates,
            UUID authorId,
            String correlationId) {

        requireWorkablePredicates(predicates);

        UUID audienceId = UUID.randomUUID();
        Instant now = clock.instant();
        audiences.insertAudience(audienceId, tenantId, brandId, name, description, authorId, now);
        audiences.replacePredicates(tenantId, audienceId, predicates, now);
        return audienceId;
    }

    @Transactional
    public int redefine(UUID tenantId, UUID brandId, UUID audienceId, List<AudiencePredicate> predicates) {
        requireWorkablePredicates(predicates);
        AudienceRow audience = requireOwnedByBrand(tenantId, brandId, audienceId);
        return audiences.replacePredicates(tenantId, audience.id(), predicates, clock.instant());
    }

    /** Every audience the brand owns, newest first. */
    @Transactional(readOnly = true)
    public List<AudienceRow> list(UUID tenantId, UUID brandId) {
        return audiences.listByBrand(tenantId, brandId);
    }

    /** One audience with the predicate set its current definition version holds. */
    @Transactional(readOnly = true)
    public AudienceDetail get(UUID tenantId, UUID brandId, UUID audienceId) {
        AudienceRow audience = requireOwnedByBrand(tenantId, brandId, audienceId);
        List<AudiencePredicate> predicates =
                audiences.loadPredicates(tenantId, audienceId, audience.definitionVersion());
        return new AudienceDetail(audience, predicates);
    }

    private AudienceRow requireOwnedByBrand(UUID tenantId, UUID brandId, UUID audienceId) {
        AudienceRow audience = audiences
                .findAudience(tenantId, audienceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No audience %s belongs to this tenant".formatted(audienceId)));
        // Mirrors buildSnapshot's own check: the endpoint declares a BRAND-scoped
        // capability, so the caller was authorised for the brand in the URL, and
        // everything here then works from the audience's OWN brand read off the
        // row. Skipping this would let AUDIENCE_READ for one brand read, or
        // CAMPAIGN_AUTHOR for one brand redefine, a sibling brand's segment.
        if (!audience.brandId().equals(brandId)) {
            throw new IllegalArgumentException("No audience %s belongs to this brand".formatted(audienceId));
        }
        return audience;
    }

    /**
     * Evaluates an audience into an immutable snapshot.
     *
     * <p>Not annotated {@code @Transactional} at the whole-build level on purpose
     * for very large audiences: a snapshot of a six-figure base held in one
     * transaction is a long-running write on the same database that is taking
     * orders. It is transactional here because the first slice's audiences are
     * small enough that a partially-built snapshot left behind by a crash would be
     * worse — it would be a {@code BUILDING} row with a plausible-looking member
     * list that nothing ever completes. When volume forces the split, the
     * {@code BUILDING} status and {@code completed_at} are already the mechanism
     * that tells a half-built snapshot from a finished one.
     */
    @Transactional
    public SnapshotResult buildSnapshot(
            UUID tenantId,
            UUID brandId,
            UUID audienceId,
            MarketingChannel channel,
            String consentPurpose,
            ActorRef actor,
            String correlationId) {

        AudienceRow audience = audiences
                .findAudience(tenantId, audienceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No audience %s belongs to this tenant".formatted(audienceId)));

        // The endpoint declares a BRAND-scoped capability, so the caller was
        // authorised for the brand in the URL -- and everything below then works
        // from the audience's OWN brand, read off the row. Holding AUDIENCE_READ
        // for one brand would otherwise be enough to build, and later message, a
        // sibling brand's customer list.
        if (!audience.brandId().equals(brandId)) {
            throw new IllegalArgumentException("No audience %s belongs to this brand".formatted(audienceId));
        }

        Instant now = clock.instant();
        EngagementPolicy policy = engagement.resolvePolicy(tenantId, audience.brandId());
        LocalDate brandToday = ZonedDateTime.ofInstant(now, policy.timezone()).toLocalDate();

        List<AudiencePredicate> predicates =
                audiences.loadPredicates(tenantId, audienceId, audience.definitionVersion());

        UUID snapshotId = UUID.randomUUID();
        Instant watermark = metrics.watermark(tenantId, audience.brandId()).orElse(null);

        audiences.openSnapshot(
                snapshotId,
                tenantId,
                audience.brandId(),
                audienceId,
                audience.definitionVersion(),
                channel.name(),
                consentPurpose,
                watermark,
                MetricDefinitions.CURRENT_VERSION,
                actorId(actor),
                now);

        List<CandidateRow> candidates = audiences.candidates(tenantId, audience.brandId(), predicates, brandToday);

        int included = 0;
        for (CandidateRow candidate : candidates) {
            Optional<RefusalReason> refusal = eligibility.refusalFor(
                    tenantId,
                    audience.brandId(),
                    candidate.customerAccountId(),
                    channel,
                    consentPurpose,
                    policy,
                    candidate.isReachableAccount(),
                    now);

            audiences.recordMember(
                    snapshotId, tenantId, candidate.customerAccountId(), refusal.orElse(null), candidate);
            if (refusal.isEmpty()) {
                included++;
            }
        }

        audiences.completeSnapshot(tenantId, snapshotId, candidates.size(), included, now);

        // The reach an approver is shown. It is an upper bound, not a promise: the
        // same five checks run again per recipient at send, so the delivered count
        // is always lower. That reads as a bug to anyone who has not read ADR 0044,
        // and it is the point.
        audit.record(AuditFact.of("MARKETING_AUDIENCE_SNAPSHOT_BUILT", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, audience.brandId()))
                .target("MarketingAudienceSnapshot", snapshotId)
                .because("Evaluated audience %s for a %s send".formatted(audience.name(), channel))
                .changed(Map.of(
                        "audienceId", audienceId,
                        "definitionVersion", audience.definitionVersion(),
                        "channel", channel.name(),
                        "candidateCount", candidates.size(),
                        "memberCount", included))
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        log.info(
                "Audience {} snapshot {} evaluated {} candidates to {} members",
                audienceId,
                snapshotId,
                candidates.size(),
                included);

        return new SnapshotResult(snapshotId, candidates.size(), included, watermark);
    }

    /**
     * The metrics and pseudonymous ids of one snapshot's members, as an audited
     * export.
     *
     * <p>No contact value crosses this method, and none can: the store it reads
     * names no column of {@code customer.contact_points}. Turning an account id
     * into a phone number needs {@code customer.pii.reveal} in the customers
     * module, which is where that decision belongs and where it is recorded.
     *
     * <p>The export is itself an ADR 0027 fact carrying the requester, the audience
     * version, the row count, and a stated purpose, because an unrestricted
     * download of the customer base is how a tenant's list ends up on a
     * competitor's desk.
     */
    @Transactional
    public List<UUID> export(
            UUID tenantId, UUID snapshotId, ActorRef actor, String statedPurpose, String correlationId, int limit) {

        var snapshot = audiences
                .findSnapshot(tenantId, snapshotId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No snapshot %s belongs to this tenant".formatted(snapshotId)));

        List<UUID> ids = audiences.includedMembersAfter(tenantId, snapshotId, null, limit).stream()
                .map(JdbcAudienceStore.SnapshotMemberRow::customerAccountId)
                .toList();

        audit.record(AuditFact.of("MARKETING_AUDIENCE_EXPORTED", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.brand(tenantId, snapshot.brandId()))
                .target("MarketingAudienceSnapshot", snapshotId)
                .because(statedPurpose)
                .changed(Map.of(
                        "audienceId", snapshot.audienceId(),
                        "definitionVersion", snapshot.definitionVersion(),
                        "rowCount", ids.size()))
                .usingCapability("audience.export")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());

        return ids;
    }

    /** The locale mix of a snapshot, which is what a cost estimate is computed from. */
    public Map<String, Integer> memberLocales(UUID tenantId, UUID snapshotId) {
        return audiences.memberLocaleCounts(tenantId, snapshotId);
    }

    private static void requireWorkablePredicates(List<AudiencePredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            // An audience with no predicates is every customer of the brand. That
            // is occasionally what a marketer means and never what they should get
            // by leaving a form empty, so it has to be said out loud with a
            // predicate that says it.
            throw new IllegalArgumentException("An audience needs at least one predicate: an empty definition is the "
                    + "whole customer base, which must be asked for rather than defaulted to");
        }
        if (predicates.size() > MAX_PREDICATES) {
            throw new IllegalArgumentException("An audience takes at most %d predicates".formatted(MAX_PREDICATES));
        }
    }

    /**
     * The actor as a UUID for the {@code built_by} column, or a nil id for a job.
     *
     * <p>The audit fact carries the real actor with its type; this column exists so
     * a snapshot list can be filtered by who built it, and a job that builds
     * nightly is legitimately not a person.
     */
    private static UUID actorId(ActorRef actor) {
        if (actor.type() != ActorRef.Type.USER) {
            return new UUID(0L, 0L);
        }
        try {
            return UUID.fromString(actor.subject());
        } catch (IllegalArgumentException notAUuid) {
            return new UUID(0L, 0L);
        }
    }

    /** What a build produced, before any campaign is attached to it. */
    public record SnapshotResult(
            UUID snapshotId,
            int candidateCount,
            int memberCount,
            @Nullable Instant metricWatermarkAt) {}

    /** An audience together with the predicates its current definition version holds. */
    public record AudienceDetail(AudienceRow audience, List<AudiencePredicate> predicates) {}
}
