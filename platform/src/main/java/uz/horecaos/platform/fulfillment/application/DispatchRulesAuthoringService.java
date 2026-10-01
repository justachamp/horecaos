package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Grouping;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.IntRange;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.TimeWindow;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesValidator;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.ChannelRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.InstallationRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.LocationRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.ZoneRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The writer and the reader behind the dispatch-rules screen (ADR 0142, ADR 0030).
 *
 * <p>Before this, dispatch was decided by what was compiled in: every plan was written {@code
 * FLEET_FIRST}, partners were tried in binding order whatever the order's zone or source, and the
 * start of sourcing was a formula. This publishes the {@code fulfillment.dispatch_rules} document
 * through the shared {@link PolicyAuthor}, so it is versioned, checked against the version the author
 * last saw, and resolvable forever by {@code PolicyResolver.pinned} -- which is what lets a plan say
 * which version of which document it ran under.
 *
 * <p><strong>Whole document, one unit.</strong> A policy is a document and not a merge of fields (ADR
 * 0030), and rule order is part of its meaning, so a publish replaces the document and requires the
 * version the caller saw at exactly this scope. Two administrators editing one scope race on that
 * version and the loser re-reads.
 *
 * <p><strong>Refused, not corrected.</strong> {@link DispatchRulesValidator} refuses a document that
 * names an installation that is not this tenant's, a rule no order can reach, a start that leaves a
 * partner no time, and the two options the record reserves. The author is told which rule and why; the
 * service never edits a document into validity behind their back.
 *
 * <p>Every publication leaves a field-level {@code fulfillment.dispatch_rules.published} audit fact
 * naming the rules added, removed, reordered and edited by id (staff row {@code 9.3a}), beside the
 * shared mechanism's hash-carrying {@code tenant.policy.authored}: who moved the far zone from one
 * partner to another cannot be read off a hash.
 */
@Service
public class DispatchRulesAuthoringService {

    public static final String AUDIT_ACTION = "fulfillment.dispatch_rules.published";

    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final JdbcDispatchRuleStore store;
    private final AuditRecorder audit;
    private final Clock clock;
    private final boolean groupingAllowed;

    public DispatchRulesAuthoringService(
            PolicyResolver policies,
            PolicyAuthor author,
            JdbcDispatchRuleStore store,
            AuditRecorder audit,
            Clock clock,
            // ADR 0142's open input, owned by finance: how a courier is paid for one run carrying
            // several orders, given ADR 0042 prices per delivery. "The pay treatment must be decided
            // before any tenant enables it" -- so this is off, and grouping is refused at publish, until
            // someone who may decide it flips it. The assignment bias is built; the switch is not ours.
            @Value("${horecaos.fulfillment.dispatch.grouping-enabled:false}") boolean groupingAllowed) {
        this.policies = policies;
        this.author = author;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.groupingAllowed = groupingAllowed;
    }

    /**
     * The document in force at a scope, and everything the editor needs to draw it and save it back.
     *
     * <p>Read from the table, never from the resolution cache: the document is shown beside {@link
     * Editor#versionAtScope()}, which the next save is compared with, and a cached document paired with
     * a current version is a form that passes the check on numbers the operator never saw. The version
     * is read first, so when a publication lands between the two reads the document is at least as new
     * as the version and the save is refused rather than waved through.
     */
    public Editor view(ResourceScope scope) {
        requireSettable(scope);
        int versionAtScope = author.currentVersion(DeliverySourcingPolicies.DISPATCH_RULES, scope);
        return editorOf(
                scope,
                policies.resolveUncached(DeliverySourcingPolicies.DISPATCH_RULES, scope)
                        .orElse(null),
                versionAtScope);
    }

    /**
     * Publishes the next version of the document at exactly {@code scope}.
     *
     * @param expectedVersion the version {@link Editor#versionAtScope()} reported when the form was
     *                        opened; 0 when nothing was authored at this scope yet
     * @throws ApiException {@code VALIDATION_FAILED} naming every reason the document may not be
     *                      published, {@code STALE_VERSION} when the scope has moved on
     */
    @Transactional
    public Editor author(
            ResourceScope scope,
            DispatchRulesDocument document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {

        requireSettable(scope);
        List<String> violations = violations(scope, document);
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", violations));
        }

        // What this scope resolved to a moment ago -- its own version, an ancestor's, or the built-in
        // default. That is the "before" an operator means by "who changed the far zone"; the shared
        // mechanism's own fact records only that a new version exists, with a hash.
        DispatchRulesDocument before = policies.resolveUncached(DeliverySourcingPolicies.DISPATCH_RULES, scope)
                .map(ResolvedPolicy::document)
                .orElseGet(DispatchRulesDocument::builtIn);

        ResolvedPolicy<DispatchRulesDocument> published = author.author(
                DeliverySourcingPolicies.DISPATCH_RULES, scope, document, expectedVersion, authoredBy, reason);

        Changes changes = changesBetween(before, document);
        audit.record(AuditFact.of(AUDIT_ACTION, AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("fulfillment.dispatch-rules", published.policyId())
                .targetVersion((long) published.policyVersion())
                .because(reason)
                .changed(ChangeDocuments.diff(changes.before(), changes.after()))
                .correlatedBy(published.policyId().toString())
                .occurredAt(clock.instant())
                .build());

        // Built from what was just published rather than re-resolved: the resolver caches by scope, and a
        // read inside this transaction that repopulated the cache before commit would leave a version in
        // it that a rollback then removes.
        return editorOf(scope, published, published.policyVersion());
    }

    /** Why {@code document} may not be published at {@code scope}; empty when it may. Also what the simulator reports for a draft. */
    public List<String> violations(ResourceScope scope, DispatchRulesDocument document) {
        return DispatchRulesValidator.violations(document, contextFor(scope));
    }

    /** What a document at this scope may name, and the numbers a rule's start is judged against. */
    public DispatchRulesValidator.Context contextFor(ResourceScope scope) {
        UUID tenantId = tenantOf(scope);
        DeliverySourcingPolicy timing = policies.resolve(DeliverySourcingPolicies.SOURCING, scope)
                .map(ResolvedPolicy::document)
                .orElse(DeliverySourcingPolicy.DEFAULTS);
        return new DispatchRulesValidator.Context(
                store.deliveryInstallations(tenantId).stream()
                        .map(InstallationRow::id)
                        .collect(Collectors.toSet()),
                store.deliveryZones(tenantId).stream().map(ZoneRow::id).collect(Collectors.toSet()),
                store.channels(tenantId).stream().map(ChannelRow::id).collect(Collectors.toSet()),
                store.locationsInScope(tenantId, scope.brandId(), scope.locationId()).stream()
                        .map(LocationRow::id)
                        .collect(Collectors.toSet()),
                timing,
                groupingAllowed);
    }

    /** The lists the editor draws its pickers from: everything a rule at this scope may name. */
    public Options options(ResourceScope scope) {
        UUID tenantId = tenantOf(scope);
        return new Options(
                store.deliveryInstallations(tenantId),
                store.deliveryZones(tenantId),
                store.channels(tenantId),
                store.locationsInScope(tenantId, scope.brandId(), scope.locationId()),
                groupingAllowed);
    }

    /** Whether this deployment lets a rule enable grouping. */
    public boolean groupingAllowed() {
        return groupingAllowed;
    }

    private Editor editorOf(
            ResourceScope scope, @Nullable ResolvedPolicy<DispatchRulesDocument> resolved, int versionAtScope) {
        List<Level> levels = new ArrayList<>();
        for (ResourceScope level : scope.chain()) {
            if (!DeliverySourcingPolicies.DISPATCH_RULES.settableScopes().contains(level.type())) {
                continue;
            }
            boolean authored = level.type() == scope.type()
                    ? versionAtScope > 0
                    : author.currentVersion(DeliverySourcingPolicies.DISPATCH_RULES, level) > 0;
            levels.add(new Level(level.type(), authored));
        }
        return new Editor(
                resolved == null ? DispatchRulesDocument.builtIn() : resolved.document(),
                resolved == null,
                resolved == null ? null : resolved.winningScope(),
                resolved == null ? null : resolved.policyId(),
                resolved == null ? 0 : resolved.policyVersion(),
                versionAtScope,
                List.copyOf(levels));
    }

    private static UUID tenantOf(ResourceScope scope) {
        return Objects.requireNonNull(scope.tenantId(), "A dispatch rules scope belongs to a tenant");
    }

    /** Dispatch rules are settable at tenant, brand and location -- a platform-wide routing rule would route every tenant's orders. */
    private static void requireSettable(ResourceScope scope) {
        if (!DeliverySourcingPolicies.DISPATCH_RULES.settableScopes().contains(scope.type())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Dispatch rules are set at tenant, brand or location scope, not " + scope.type());
        }
    }

    // ------------------------------------------------------------- the audit

    /**
     * The two documents as flat snapshots, narrowed to the fields that differ, ready for {@link
     * ChangeDocuments#diff} so the activity log can say "rule far-zone then.mode FLEET_FIRST to
     * PARTNER_FIRST". Only the fields that changed are listed -- a document of a hundred rules would
     * otherwise put eighteen hundred unchanged pairs in every audit fact -- and a rule that was added or
     * removed lists every one of its fields against null.
     *
     * <p>Rules are keyed by their id. An id is operator-typed text, and {@link ChangeDocuments} redacts a
     * field whose name merely <em>contains</em> a protected term ("tin", "pan", "note"), so an id that
     * happens to contain one falls back to the rule's position and the id travels in the order summary.
     */
    static Changes changesBetween(DispatchRulesDocument before, DispatchRulesDocument after) {
        Map<String, Object> was = snapshotOf(before);
        Map<String, Object> now = snapshotOf(after);

        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(was.keySet());
        keys.addAll(now.keySet());
        for (String key : keys) {
            if (!Objects.equals(was.get(key), now.get(key))) {
                changedBefore.put(key, was.get(key));
                changedAfter.put(key, now.get(key));
            }
        }
        return new Changes(changedBefore, changedAfter);
    }

    /** The fields that differ between two documents, as the before and after halves {@link ChangeDocuments#diff} takes. */
    record Changes(Map<String, Object> before, Map<String, Object> after) {}

    static Map<String, Object> snapshotOf(DispatchRulesDocument document) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("rules.order", document.rules().stream().map(Rule::id).collect(Collectors.joining(">")));
        snapshot.put("rules.count", document.rules().size());
        for (int index = 0; index < document.rules().size(); index++) {
            Rule rule = document.rules().get(index);
            String prefix = "rule." + rule.id() + ".";
            if (ChangeDocuments.isProtected(prefix)) {
                prefix = "rule#" + (index + 1) + ".";
            }
            snapshot.put(prefix + "present", true);
            snapshot.put(prefix + "name", rule.name());
            snapshot.put(prefix + "enabled", rule.enabled());
            putConditions(snapshot, prefix + "when.", rule.when());
            putAction(snapshot, prefix + "then.", rule.then());
        }
        putAction(snapshot, "default.", document.fallback());
        return snapshot;
    }

    private static void putConditions(Map<String, Object> snapshot, String prefix, Conditions when) {
        snapshot.put(prefix + "sources", String.join(",", when.sources()));
        snapshot.put(prefix + "channelIds", ids(when.channelIds()));
        snapshot.put(prefix + "zoneIds", ids(when.zoneIds()));
        snapshot.put(prefix + "locationIds", ids(when.locationIds()));
        snapshot.put(prefix + "prepMinutes", range(when.prepMinutes()));
        snapshot.put(prefix + "distanceMeters", range(when.distanceMeters()));
        snapshot.put(prefix + "localTime", window(when.localTime()));
        snapshot.put(prefix + "prepaid", when.prepaid() == null ? null : String.valueOf(when.prepaid()));
    }

    private static void putAction(Map<String, Object> snapshot, String prefix, Action action) {
        snapshot.put(prefix + "mode", action.mode().name());
        snapshot.put(prefix + "partners.order", ids(action.partners().order()));
        snapshot.put(prefix + "partners.exclude", ids(action.partners().exclude()));
        snapshot.put(
                prefix + "partners.selection", action.partners().selection().name());
        snapshot.put(
                prefix + "dispatchAt",
                action.dispatchAt().basis() + " " + action.dispatchAt().offsetSeconds() + "s");
        Grouping grouping = action.grouping();
        snapshot.put(
                prefix + "grouping",
                grouping == null
                        ? null
                        : "%dm/%d orders/%ds"
                                .formatted(
                                        grouping.mergeRadiusMeters(),
                                        grouping.maxOrdersPerRun(),
                                        grouping.maxWaitSeconds()));
    }

    private static String ids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    private static @Nullable String range(@Nullable IntRange range) {
        return range == null
                ? null
                : (range.min() == null ? "" : range.min()) + ".." + (range.max() == null ? "" : range.max());
    }

    private static @Nullable String window(@Nullable TimeWindow window) {
        return window == null
                ? null
                : window.days().stream().map(Enum::name).collect(Collectors.joining(","))
                        + " "
                        + window.from()
                        + "-"
                        + window.to();
    }

    // ------------------------------------------------------------ wire shapes

    /**
     * Everything the editor shows and saves back.
     *
     * @param document       the document in force at the scope, or the built-in default
     * @param isBuiltIn      nothing was published anywhere in the chain
     * @param winningScope   the scope whose document is in force, null for the built-in default
     * @param policyId       the document in force, null for the built-in default
     * @param policyVersion  its version
     * @param versionAtScope the latest version authored at exactly the requested scope, 0 when it
     *                       inherits: the {@code If-Match} to send back
     * @param levels         the resolution ladder, most specific first, for the "why is this here" trace
     */
    public record Editor(
            DispatchRulesDocument document,
            boolean isBuiltIn,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<Level> levels) {}

    /** One rung of the resolution ladder: whether a document was authored at exactly that scope. */
    public record Level(ScopeType scopeType, boolean authored) {}

    /** What a rule at a scope may name. */
    public record Options(
            List<InstallationRow> installations,
            List<ZoneRow> zones,
            List<ChannelRow> channels,
            List<LocationRow> locations,
            boolean groupingAllowed) {}
}
