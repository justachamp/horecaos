package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The missing writer for {@code fulfillment.sourcing}, the timing numbers behind ADR 0014 (ADR 0142).
 *
 * <p>The document has had readers since ADR 0014 -- {@code DeliveryPlanningService} computes a plan's
 * start from it and {@code DeliverySourcingService} re-resolves it on every tick -- and no writer
 * anywhere, so the provisional {@link DeliverySourcingPolicy#DEFAULTS} were in force at every branch of
 * every tenant. This is the writer, and it changes no behaviour until someone publishes: it ships first
 * in ADR 0142's rollout for exactly that reason.
 *
 * <p>It stays a document of its own beside the dispatch rules, not folded into them. A lead time changed
 * without the buffer that goes with it is, in {@link DeliverySourcingPolicy}'s own words, "a mistake made
 * by editing one of a pair", and mixing the two documents would make every timing tweak a republish of
 * routing. The rules choose which lane, which partners and when to start; these numbers say how long
 * each step takes.
 */
@Service
public class SourcingPolicyAuthoringService {

    public static final String AUDIT_ACTION = "fulfillment.sourcing.published";

    /** A day: the ceiling for every duration in the document. Nobody times a courier lead in days. */
    static final int MAX_SECONDS = 86_400;

    static final int MAX_OFFER_ROUNDS = 10;
    static final int MAX_OFFER_SECONDS = 600;

    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final AuditRecorder audit;
    private final Clock clock;

    public SourcingPolicyAuthoringService(
            PolicyResolver policies, PolicyAuthor author, AuditRecorder audit, Clock clock) {
        this.policies = policies;
        this.author = author;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The timing document in force at a scope, read from the table and paired with the version a save is
     * checked against (version first, for the reason {@code DispatchRulesAuthoringService#view} gives).
     */
    public Editor view(ResourceScope scope) {
        requireSettable(scope);
        int versionAtScope = author.currentVersion(DeliverySourcingPolicies.SOURCING, scope);
        return editorOf(
                scope,
                policies.resolveUncached(DeliverySourcingPolicies.SOURCING, scope)
                        .orElse(null),
                versionAtScope);
    }

    /**
     * Publishes the next version of the timing document at exactly {@code scope}.
     *
     * @param expectedVersion the version {@link Editor#versionAtScope()} reported when the form was
     *                        opened; 0 when nothing was authored at this scope yet
     */
    @Transactional
    public Editor author(
            ResourceScope scope,
            DeliverySourcingPolicy document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {

        requireSettable(scope);
        List<String> violations = violations(document);
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", violations));
        }

        DeliverySourcingPolicy before = policies.resolveUncached(DeliverySourcingPolicies.SOURCING, scope)
                .map(ResolvedPolicy::document)
                .orElse(DeliverySourcingPolicy.DEFAULTS);
        ResolvedPolicy<DeliverySourcingPolicy> published =
                author.author(DeliverySourcingPolicies.SOURCING, scope, document, expectedVersion, authoredBy, reason);

        audit.record(AuditFact.of(AUDIT_ACTION, AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("fulfillment.sourcing-policy", published.policyId())
                .targetVersion((long) published.policyVersion())
                .because(reason)
                .changed(ChangeDocuments.diff(snapshotOf(before), snapshotOf(document)))
                .correlatedBy(published.policyId().toString())
                .occurredAt(clock.instant())
                .build());

        return editorOf(scope, published, published.policyVersion());
    }

    /**
     * The bounds the editor enforces before it sends, as sentences. The record's own invariants (a window of
     * zero width, no offer round, an offer that expires before a phone finishes ringing) are re-checked
     * here so a refusal names the field rather than surfacing as the constructor's exception.
     */
    public static List<String> violations(DeliverySourcingPolicy document) {
        List<String> found = new ArrayList<>();
        durationViolation("preparationLeadSeconds", document.preparationLeadSeconds(), 0, found);
        durationViolation("partnerLeadSeconds", document.partnerLeadSeconds(), 0, found);
        durationViolation("safetyBufferSeconds", document.safetyBufferSeconds(), 0, found);
        durationViolation("pickupToleranceSeconds", document.pickupToleranceSeconds(), 1, found);
        durationViolation("latestAssignmentSlackSeconds", document.latestAssignmentSlackSeconds(), 0, found);
        if (document.offerRounds() < 1 || document.offerRounds() > MAX_OFFER_ROUNDS) {
            found.add("offerRounds must be between 1 and " + MAX_OFFER_ROUNDS);
        }
        if (document.maxOfferSeconds() < 15 || document.maxOfferSeconds() > MAX_OFFER_SECONDS) {
            found.add("maxOfferSeconds must be between 15 and " + MAX_OFFER_SECONDS);
        }
        return List.copyOf(found);
    }

    private static void durationViolation(String field, int seconds, int minimum, List<String> found) {
        if (seconds < minimum || seconds > MAX_SECONDS) {
            found.add("%s must be between %d and %d seconds".formatted(field, minimum, MAX_SECONDS));
        }
    }

    private Editor editorOf(
            ResourceScope scope, @Nullable ResolvedPolicy<DeliverySourcingPolicy> resolved, int versionAtScope) {
        List<Level> levels = new ArrayList<>();
        for (ResourceScope level : scope.chain()) {
            if (level.type() == ScopeType.PLATFORM) {
                continue;
            }
            boolean authored = level.type() == scope.type()
                    ? versionAtScope > 0
                    : author.currentVersion(DeliverySourcingPolicies.SOURCING, level) > 0;
            levels.add(new Level(level.type(), authored));
        }
        return new Editor(
                resolved == null ? DeliverySourcingPolicy.DEFAULTS : resolved.document(),
                resolved == null,
                resolved == null ? null : resolved.winningScope(),
                resolved == null ? null : resolved.policyId(),
                resolved == null ? 0 : resolved.policyVersion(),
                versionAtScope,
                List.copyOf(levels));
    }

    private static void requireSettable(ResourceScope scope) {
        if (scope.type() == ScopeType.PLATFORM) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Sourcing timings are set at tenant, brand or location scope");
        }
    }

    private static Map<String, Object> snapshotOf(DeliverySourcingPolicy document) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("preparationLeadSeconds", document.preparationLeadSeconds());
        snapshot.put("partnerLeadSeconds", document.partnerLeadSeconds());
        snapshot.put("safetyBufferSeconds", document.safetyBufferSeconds());
        snapshot.put("pickupToleranceSeconds", document.pickupToleranceSeconds());
        snapshot.put("offerRounds", document.offerRounds());
        snapshot.put("maxOfferSeconds", document.maxOfferSeconds());
        snapshot.put("latestAssignmentSlackSeconds", document.latestAssignmentSlackSeconds());
        return snapshot;
    }

    /**
     * @param document       the numbers in force, the provisional defaults when nothing was published
     * @param isDefaults     nothing was published anywhere in the chain
     * @param versionAtScope the latest version authored at exactly the requested scope (0 when it
     *                       inherits): the {@code If-Match} to send back
     */
    public record Editor(
            DeliverySourcingPolicy document,
            boolean isDefaults,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<Level> levels) {}

    /** One rung of the resolution ladder. */
    public record Level(ScopeType scopeType, boolean authored) {}
}
