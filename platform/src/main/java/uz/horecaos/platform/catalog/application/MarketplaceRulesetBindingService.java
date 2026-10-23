package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore.BindingVersion;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Names the marketplace ruleset a MARKETPLACE binding is checked against, or takes the name away (ADR 0138
 * "Capability placement" and exit criteria: a marketplace binding can carry a ruleset code).
 *
 * <p>The column, {@code integration.bindings.marketplace_ruleset_code}, was added with the registry and nothing
 * wrote it, so the first ruleset a partnership authored could not have been switched on without SQL. This is that
 * writer, and it is as narrow as the registry is: it accepts only {@link MarketplaceRulesets#knownCodes()}, which
 * is empty in production by ADR 0138's decision, so today the only thing it can honestly do is clear a code. A
 * code that names no registered ruleset is refused here, because a preview would report it as {@code
 * MARKETPLACE_RULESET_UNKNOWN} on every finding page of that channel for a typo.
 *
 * <p>Under the binding's own version (ADR 0031): two operators editing one binding see the second refused. The
 * audit fact names the code before and after, never anything the ruleset would say about a menu.
 */
@Service
public class MarketplaceRulesetBindingService {

    private final JdbcChannelProjectionStore projections;
    private final MarketplaceRulesets rulesets;
    private final AuditRecorder audit;
    private final Clock clock;

    public MarketplaceRulesetBindingService(
            JdbcChannelProjectionStore projections, MarketplaceRulesets rulesets, AuditRecorder audit, Clock clock) {
        this.projections = projections;
        this.rulesets = rulesets;
        this.audit = audit;
        this.clock = clock;
    }

    /** The codes a binding may name; empty until a ruleset is authored. */
    public List<String> knownCodes() {
        return rulesets.knownCodes().stream().sorted().toList();
    }

    /** What a binding names now, with the version to quote when changing it. */
    @Transactional(readOnly = true)
    public Applied read(UUID tenantId, UUID installationId, UUID bindingId) {
        BindingVersion binding = projections
                .bindingOfInstallation(tenantId, installationId, bindingId)
                .orElseThrow(() -> new UnknownBindingException(bindingId));
        return new Applied(binding.bindingId(), binding.rulesetCode(), binding.version());
    }

    /**
     * @param code the ruleset to name, or null to clear it so the universal rules alone apply
     * @param expectedVersion the binding's version the editor read
     * @throws UnknownBindingException the binding is not a MARKETPLACE binding of this installation of this tenant
     * @throws UnknownRulesetException the code names no registered ruleset
     * @throws StaleBindingException the binding changed since the editor read it
     */
    @Transactional
    public Applied set(
            UUID tenantId,
            UUID installationId,
            UUID bindingId,
            @Nullable String code,
            long expectedVersion,
            String actorSubject) {
        if (code != null && !rulesets.knownCodes().contains(code)) {
            throw new UnknownRulesetException(code, knownCodes());
        }
        BindingVersion binding = projections
                .bindingOfInstallation(tenantId, installationId, bindingId)
                .orElseThrow(() -> new UnknownBindingException(bindingId));
        if (binding.version() != expectedVersion) {
            throw new StaleBindingException(expectedVersion, binding.version());
        }
        if (Objects.equals(binding.rulesetCode(), code)) {
            // Saving what is stored changes nothing and keeps the version, so a double click is not an edit
            // another operator is then told about.
            return new Applied(bindingId, code, binding.version());
        }
        long next = projections
                .setRulesetCode(tenantId, installationId, bindingId, code, expectedVersion)
                .orElseThrow(() -> new StaleBindingException(
                        expectedVersion,
                        projections
                                .bindingOfInstallation(tenantId, installationId, bindingId)
                                .map(BindingVersion::version)
                                .orElse(expectedVersion)));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("rulesetCode", binding.rulesetCode());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rulesetCode", code);
        ResourceScope scope = binding.brandId() == null
                ? ResourceScope.tenant(tenantId)
                : binding.locationId() == null
                        ? ResourceScope.brand(tenantId, binding.brandId())
                        : ResourceScope.location(tenantId, binding.brandId(), binding.locationId());
        audit.record(AuditFact.of("integration.binding.marketplaceRulesetSet", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(scope)
                .target("integration.binding", bindingId)
                .targetVersion(next)
                .because(code == null ? "Cleared the marketplace ruleset" : "Named a marketplace ruleset")
                .usingCapability(Capability.INTEGRATION_INSTALLATION_MANAGE.code())
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(bindingId.toString())
                .occurredAt(clock.instant())
                .build());
        return new Applied(bindingId, code, next);
    }

    /** The ruleset a binding names and its version. */
    public record Applied(UUID bindingId, @Nullable String rulesetCode, long version) {}

    public static final class UnknownBindingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnknownBindingException(UUID bindingId) {
            super("No marketplace binding " + bindingId + " on this installation");
        }
    }

    public static final class UnknownRulesetException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient List<String> knownCodes;

        public UnknownRulesetException(String code, List<String> knownCodes) {
            super("No marketplace ruleset is registered under " + code);
            this.knownCodes = List.copyOf(knownCodes);
        }

        public List<String> knownCodes() {
            return knownCodes;
        }
    }

    public static final class StaleBindingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final long expected;
        private final long actual;

        public StaleBindingException(long expected, long actual) {
            super("The binding is at version " + actual + ", not " + expected);
            this.expected = expected;
            this.actual = actual;
        }

        public long expected() {
            return expected;
        }

        public long actual() {
            return actual;
        }
    }
}
