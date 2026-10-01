package uz.horecaos.platform.ordering.application;

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
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyService.AtRiskDefault;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyService.Authored;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument.ModeThresholds;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The editor behind the order-policy card's lateness boundaries (ADR 0030, orders.md §2.7, gap
 * map rows {@code X.39}/{@code 10.3b}): the {@code ordering.lateness} document's late-after
 * grace, no-promise fallback and per-fulfilment-mode at-risk window, authored at TENANT, BRAND
 * or LOCATION scope through the shared {@link PolicyAuthor}.
 *
 * <p>Before this, the document existed, every board read it, and nothing could write it -- a
 * tenant could move the at-risk edge and the colour (batch 15's two scalars) but not the line
 * where late itself begins. The scalar {@code ordering.late_order_threshold_minutes} (card 2's
 * "order is late after") is deliberately <em>not</em> read here or anywhere: what it means -- a
 * grace past the promise, or a limit from acceptance -- is an owner decision this row does not
 * make, so the document's own {@code lateAfterSeconds} is the only late line the boards draw.
 *
 * <p><strong>Versioned as one unit, checked as one unit.</strong> The document is replaced whole
 * (ADR 0030: a policy is a document, not a merge of fields), so a form built from a stale read
 * would silently discard what a second operator published. {@link #author} therefore requires the
 * version the caller last saw at exactly the scope it is writing, and refuses with {@code
 * STALE_VERSION} otherwise; it never edits a version in force, so {@code PolicyResolver.pinned}
 * still answers for anything that resolved an earlier one.
 *
 * <p>Every publication leaves a field-level {@code ordering.lateness-policy.authored} audit fact
 * (staff row {@code 9.3a}) beside the shared mechanism's hash-carrying {@code
 * tenant.policy.authored}: who moved delivery's grace from zero to a minute cannot be read off a
 * hash.
 */
@Service
public class OrderLatenessPolicyAuthoringService {

    private final OrderLatenessPolicyService reads;
    private final PolicyAuthor author;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrderLatenessPolicyAuthoringService(
            OrderLatenessPolicyService reads, PolicyAuthor author, AuditRecorder audit, Clock clock) {
        this.reads = reads;
        this.author = author;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The document in force at a scope, and everything the editor needs to draw it and to save it back.
     *
     * <p>Read from the table, never from the resolution cache: the document is shown beside {@link
     * Editor#versionAtScope()}, which is what the next save is compared with, and a cached document
     * paired with a current version is a form that passes the check on numbers the operator never saw.
     * The version is read <em>first</em>, so when a publication lands between the two reads the document
     * is at least as new as the version and the save is refused rather than waved through.
     */
    public Editor view(ResourceScope scope) {
        int versionAtScope = author.currentVersion(OrderingConfigurationKeys.LATENESS_POLICY, scope);
        Authored authored = reads.authoredUncachedAt(scope);
        return editorOf(
                scope,
                authored.document(),
                authored.policyId(),
                authored.policyVersion(),
                authored.winningScope(),
                versionAtScope);
    }

    /**
     * Publishes the next version of the document at exactly {@code scope}.
     *
     * @param expectedVersion the version {@link Editor#versionAtScope()} reported when the form was
     *                        opened; 0 or null when nothing was authored at this scope yet
     * @throws ApiException {@code VALIDATION_FAILED} for a number outside the bounds {@link
     *                      OrderLatenessDocument#violations()} names, {@code STALE_VERSION} when
     *                      the scope has moved on from {@code expectedVersion}
     */
    @Transactional
    public Editor author(
            ResourceScope scope,
            OrderLatenessDocument document,
            @Nullable Integer expectedVersion,
            ActorRef authoredBy,
            String reason) {

        List<String> violations = document.violations();
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", violations));
        }
        if (expectedVersion != null && expectedVersion < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "expectedVersion must not be negative");
        }

        // What this scope resolved to a moment ago -- its own version, an ancestor's, or the platform
        // default. That is the "before" an operator means by "who changed when an order counts as
        // late"; the shared mechanism's own fact records only that a new version exists, with a hash.
        Authored before = reads.authoredUncachedAt(scope);
        ResolvedPolicy<OrderLatenessDocument> published = author.author(
                OrderingConfigurationKeys.LATENESS_POLICY,
                scope,
                document,
                expectedVersion == null ? 0 : expectedVersion,
                authoredBy,
                reason);

        audit.record(AuditFact.of("ordering.lateness-policy.authored", AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("ordering.lateness-policy", published.policyId())
                .targetVersion((long) published.policyVersion())
                .because(reason)
                .changed(ChangeDocuments.diff(
                        snapshotOf(before.document(), before.policyVersion()),
                        snapshotOf(document, published.policyVersion())))
                .correlatedBy(published.policyId().toString())
                .occurredAt(clock.instant())
                .build());

        // Built from what was just published rather than re-resolved: the resolver caches by scope, and
        // a read inside this transaction that repopulated the cache before commit would leave a version
        // in it that a rollback then removes.
        return editorOf(
                scope,
                document,
                published.policyId(),
                published.policyVersion(),
                scope.type(),
                published.policyVersion());
    }

    private Editor editorOf(
            ResourceScope scope,
            OrderLatenessDocument document,
            @Nullable UUID policyId,
            int policyVersion,
            @Nullable ScopeType winningScope,
            int versionAtScope) {
        AtRiskDefault atRiskDefault = reads.atRiskDefaultAt(scope);
        List<Level> levels = new ArrayList<>();
        for (ResourceScope level : scope.chain()) {
            boolean authored = level.type() == scope.type()
                    ? versionAtScope > 0
                    : author.currentVersion(OrderingConfigurationKeys.LATENESS_POLICY, level) > 0;
            levels.add(new Level(level.type(), authored));
        }
        return new Editor(
                document,
                document.effective(atRiskDefault.seconds()),
                atRiskDefault,
                policyId == null,
                winningScope,
                policyId,
                policyVersion,
                versionAtScope,
                List.copyOf(levels));
    }

    /**
     * One flat map per side for {@link ChangeDocuments#diff}: keyed by mode so the activity log can
     * say "pickup lateAfterSeconds 0 to 120". An at-risk window a mode does not own is a null, kept
     * distinct from a window it owns and sets to zero.
     */
    private static Map<String, Object> snapshotOf(OrderLatenessDocument document, int policyVersion) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (FulfillmentMode mode : FulfillmentMode.values()) {
            ModeThresholds thresholds = document.forMode(mode);
            String prefix = modeKey(mode);
            snapshot.put(prefix + ".atRiskBeforeSeconds", thresholds.atRiskBeforeSeconds());
            snapshot.put(prefix + ".lateAfterSeconds", thresholds.lateAfterSeconds());
            snapshot.put(prefix + ".noPromiseFallbackSeconds", thresholds.noPromiseFallbackSeconds());
        }
        snapshot.put("policyVersion", policyVersion);
        return snapshot;
    }

    private static String modeKey(FulfillmentMode mode) {
        return switch (mode) {
            case DELIVERY -> "delivery";
            case PICKUP -> "pickup";
            case DINE_IN -> "dineIn";
        };
    }

    /**
     * Everything the editor shows and saves back.
     *
     * @param document       the document as authored, a mode's own at-risk window absent where the
     *                       author left it to the default
     * @param effective      what the boards actually evaluate, defaults filled in
     * @param atRiskDefault  the window a mode with none of its own gets, and whether that is the
     *                       tenant's scalar or the platform's five minutes
     * @param policyId       the document in force, null when the platform default applied
     * @param winningScope   the scope that supplied it, null for the platform default
     * @param versionAtScope the latest version authored at exactly the requested scope (0 when the
     *                       scope inherits): the {@code expectedVersion} to send back
     * @param levels         the resolution ladder, most specific first, for the "why is this value here" trace
     */
    public record Editor(
            OrderLatenessDocument document,
            OrderLatenessPolicy effective,
            AtRiskDefault atRiskDefault,
            boolean isPlatformDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<Level> levels) {}

    /** One rung of the resolution ladder: whether a document was authored at exactly that scope. */
    public record Level(ScopeType scopeType, boolean authored) {}
}
