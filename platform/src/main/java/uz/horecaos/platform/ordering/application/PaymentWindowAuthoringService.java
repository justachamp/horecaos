package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import uz.horecaos.platform.ordering.domain.PaymentWindowPolicy;
import uz.horecaos.platform.ordering.domain.PaymentWindowPolicy.Action;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The reader and writer of {@code ordering.payment_window} (ADR 0142 Decision 7, ADR 0030).
 *
 * <p>One place that answers "how long is an unpaid order waited for here, and what then" for the sweep
 * that acts on it ({@link #windowFor}), for the screen that shows it ({@link #view}) and for the writer
 * that changes it ({@link #author}), so the number an operator reads is the number the sweep uses.
 *
 * <p>The deploy property the sweep used to read is the <em>fallback</em>: when nothing is published
 * anywhere in the chain, {@link #windowFor} answers it, so a deployment that never opens the screen
 * behaves exactly as it did.
 */
@Service
public class PaymentWindowAuthoringService {

    public static final String AUDIT_ACTION = "ordering.payment-window.authored";

    private final PolicyResolver policies;
    private final PolicyAuthor author;
    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final Clock clock;

    public PaymentWindowAuthoringService(
            PolicyResolver policies, PolicyAuthor author, JdbcClient jdbc, AuditRecorder audit, Clock clock) {
        this.policies = policies;
        this.author = author;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * How long this order has been waited for and what is done then, for the sweep.
     *
     * <p>Resolved at the order's own location, through the cached resolver: the sweep runs every thirty
     * seconds over every unpaid order and a document edit takes effect within the cache's life.
     *
     * @param fallback the deploy property, used when no document is published
     * @return the window; {@link Window#authored()} is false when it is the fallback
     */
    public Window windowFor(UUID tenantId, UUID orderId, Duration fallback) {
        Optional<ResolvedPolicy<PaymentWindowPolicy>> resolved = jdbc.sql("""
                SELECT brand_id, location_id FROM ordering.orders
                WHERE tenant_id = :tenantId AND id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> ResourceScope.location(
                        tenantId, row.getObject("brand_id", UUID.class), row.getObject("location_id", UUID.class)))
                .optional()
                .flatMap(scope -> policies.resolve(OrderingConfigurationKeys.PAYMENT_WINDOW_POLICY, scope));
        return resolved.map(found ->
                        new Window(found.document().window(), found.document().action(), true))
                .orElseGet(() -> new Window(fallback, Action.FLAG_ONLY, false));
    }

    /** The document in force at a scope, read from the table and paired with the version a save is checked against. */
    public Editor view(ResourceScope scope, Duration fallback) {
        requireSettable(scope);
        int versionAtScope = author.currentVersion(OrderingConfigurationKeys.PAYMENT_WINDOW_POLICY, scope);
        return editorOf(
                scope,
                policies.resolveUncached(OrderingConfigurationKeys.PAYMENT_WINDOW_POLICY, scope)
                        .orElse(null),
                versionAtScope,
                fallback);
    }

    /**
     * Publishes the next version at exactly {@code scope}.
     *
     * @throws ApiException {@code VALIDATION_FAILED} for a number outside its bounds or for {@code CANCEL},
     *                      {@code STALE_VERSION} when the scope has moved on
     */
    @Transactional
    public Editor author(
            ResourceScope scope,
            PaymentWindowPolicy document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason,
            Duration fallback) {

        requireSettable(scope);
        List<String> violations = document.violations();
        if (!violations.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", violations));
        }

        PaymentWindowPolicy before = policies.resolveUncached(OrderingConfigurationKeys.PAYMENT_WINDOW_POLICY, scope)
                .map(ResolvedPolicy::document)
                .orElseGet(() -> new PaymentWindowPolicy((int) fallback.toMinutes(), Action.FLAG_ONLY));
        ResolvedPolicy<PaymentWindowPolicy> published = author.author(
                OrderingConfigurationKeys.PAYMENT_WINDOW_POLICY, scope, document, expectedVersion, authoredBy, reason);

        audit.record(AuditFact.of(AUDIT_ACTION, AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("ordering.payment-window", published.policyId())
                .targetVersion((long) published.policyVersion())
                .because(reason)
                .changed(ChangeDocuments.diff(snapshotOf(before), snapshotOf(document)))
                .correlatedBy(published.policyId().toString())
                .occurredAt(clock.instant())
                .build());

        return editorOf(scope, published, published.policyVersion(), fallback);
    }

    private Editor editorOf(
            ResourceScope scope,
            @Nullable ResolvedPolicy<PaymentWindowPolicy> resolved,
            int versionAtScope,
            Duration fallback) {
        List<Level> levels = new ArrayList<>();
        for (ResourceScope level : scope.chain()) {
            if (level.type() == ScopeType.PLATFORM) {
                continue;
            }
            boolean authored = level.type() == scope.type()
                    ? versionAtScope > 0
                    : author.currentVersion(OrderingConfigurationKeys.PAYMENT_WINDOW_POLICY, level) > 0;
            levels.add(new Level(level.type(), authored));
        }
        PaymentWindowPolicy document = resolved == null
                ? new PaymentWindowPolicy((int) fallback.toMinutes(), Action.FLAG_ONLY)
                : resolved.document();
        return new Editor(
                document,
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
                    ErrorCode.VALIDATION_FAILED, "The payment window is set at tenant, brand or location scope");
        }
    }

    private static Map<String, Object> snapshotOf(PaymentWindowPolicy document) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("windowMinutes", document.windowMinutes());
        snapshot.put("action", document.action().name());
        return snapshot;
    }

    /**
     * @param staleAfter how long an order may sit awaiting payment
     * @param authored   false when this is the deploy-property fallback
     */
    public record Window(Duration staleAfter, Action action, boolean authored) {}

    /**
     * @param isDefault      nothing was published anywhere in the chain, so the deploy default applies
     * @param versionAtScope the latest version authored at exactly the requested scope (0 when it
     *                       inherits): the {@code If-Match} to send back
     */
    public record Editor(
            PaymentWindowPolicy document,
            boolean isDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<Level> levels) {}

    /** One rung of the resolution ladder. */
    public record Level(ScopeType scopeType, boolean authored) {}
}
