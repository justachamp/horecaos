package uz.horecaos.platform.tenancy.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.terminals.TerminalLockout;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.AttributionMode;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.RosterDisplay;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicySource;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant's five terminal policy values (ADR 0155, Decision 7): resolved for the terminals, and written, all five in one
 * transaction, by the Policy card of Staff → Терминалы.
 *
 * <p>The values are ordinary ADR 0030 configuration keys, but none is tenant-visible, so the generic configuration editor
 * neither lists nor writes them: this card is the only tenant write path, and {@code terminal.manage} at {@code TENANT}
 * scope is the control. The lockout numbers are not keys at all -- they are code constants, stated beside the PIN-length
 * choice so a tenant sees what it is choosing.
 */
@Service
public class TerminalPolicyService implements TerminalPolicySource {

    private final ConfigurationResolver resolver;
    private final ConfigurationValueAuthor author;
    private final AuditRecorder audit;

    public TerminalPolicyService(ConfigurationResolver resolver, ConfigurationValueAuthor author, AuditRecorder audit) {
        this.resolver = resolver;
        this.author = author;
        this.audit = audit;
    }

    @Override
    public TerminalPolicy of(UUID tenantId) {
        ResourceScope scope = ResourceScope.tenant(tenantId);
        return new TerminalPolicy(
                intOf(ConfigurationKeys.TERMINAL_PIN_LENGTH, scope, TerminalPolicy.DEFAULT_PIN_LENGTH),
                intOf(ConfigurationKeys.TERMINAL_AUTO_LOCK_SECONDS, scope, TerminalPolicy.DEFAULT_AUTO_LOCK_SECONDS),
                intOf(ConfigurationKeys.TERMINAL_SESSION_MAX_HOURS, scope, TerminalPolicy.DEFAULT_SESSION_MAX_HOURS),
                enumOf(ConfigurationKeys.TERMINAL_ROSTER_DISPLAY, scope, RosterDisplay.NAME_INITIAL),
                enumOf(ConfigurationKeys.TERMINAL_ATTRIBUTION_DEFAULT, scope, AttributionMode.DEVICE_ONLY));
    }

    /** A value and where the resolution chain found it. */
    public record Setting<T>(T value, String source) {}

    public record GuessOdds(int pinLength, long oneInNPerDay) {}

    public record Lockout(int consecutiveFailures, long lockMinutes, int failuresPerDay) {}

    public record PolicyView(
            Setting<Integer> pinLength,
            Setting<Integer> autoLockSeconds,
            Setting<Integer> sessionMaxHours,
            Setting<String> rosterDisplay,
            Setting<String> attributionDefault,
            List<GuessOdds> guessOdds,
            Lockout lockout) {}

    public PolicyView view(UUID tenantId) {
        ResourceScope scope = ResourceScope.tenant(tenantId);
        return new PolicyView(
                setting(ConfigurationKeys.TERMINAL_PIN_LENGTH, scope, TerminalPolicy.DEFAULT_PIN_LENGTH),
                setting(ConfigurationKeys.TERMINAL_AUTO_LOCK_SECONDS, scope, TerminalPolicy.DEFAULT_AUTO_LOCK_SECONDS),
                setting(ConfigurationKeys.TERMINAL_SESSION_MAX_HOURS, scope, TerminalPolicy.DEFAULT_SESSION_MAX_HOURS),
                setting(ConfigurationKeys.TERMINAL_ROSTER_DISPLAY, scope, RosterDisplay.NAME_INITIAL.name()),
                setting(ConfigurationKeys.TERMINAL_ATTRIBUTION_DEFAULT, scope, AttributionMode.DEVICE_ONLY.name()),
                List.of(
                        new GuessOdds(4, TerminalLockout.guessOneIn(4)),
                        new GuessOdds(6, TerminalLockout.guessOneIn(6))),
                new Lockout(
                        TerminalLockout.DEVICE_CONSECUTIVE_FAILURES,
                        TerminalLockout.DEVICE_LOCK.toMinutes(),
                        TerminalLockout.CREDENTIAL_FAILURES_PER_DAY));
    }

    /** What the card saves: every field, so a missing one is a client error and not a quiet reset. */
    public record PolicyChange(
            int pinLength,
            int autoLockSeconds,
            int sessionMaxHours,
            RosterDisplay rosterDisplay,
            AttributionMode attributionDefault) {}

    /**
     * Writes the five values at {@code TENANT} scope in one transaction, each refused by its value rule if it is out of range,
     * and records one {@code terminal.policy.changed} fact (through {@code ChangeDocuments.diff}) naming the person who saved.
     */
    @Transactional
    public PolicyView save(UUID tenantId, PolicyChange change, String actorSubject, String reason) {
        ResourceScope scope = ResourceScope.tenant(tenantId);
        TerminalPolicy before = of(tenantId);
        ActorRef actor = ActorRef.user(actorSubject, null);

        write(ConfigurationKeys.TERMINAL_PIN_LENGTH, scope, change.pinLength(), actor, reason);
        write(ConfigurationKeys.TERMINAL_AUTO_LOCK_SECONDS, scope, change.autoLockSeconds(), actor, reason);
        write(ConfigurationKeys.TERMINAL_SESSION_MAX_HOURS, scope, change.sessionMaxHours(), actor, reason);
        write(
                ConfigurationKeys.TERMINAL_ROSTER_DISPLAY,
                scope,
                change.rosterDisplay().name(),
                actor,
                reason);
        write(
                ConfigurationKeys.TERMINAL_ATTRIBUTION_DEFAULT,
                scope,
                change.attributionDefault().name(),
                actor,
                reason);

        TerminalPolicy after = of(tenantId);
        audit.record(AuditFact.of("terminal.policy.changed", AuditClass.SECURITY)
                .by(actor)
                .at(scope)
                .target("tenant.tenant", tenantId)
                .because(reason)
                .changed(ChangeDocuments.diff(snapshot(before), snapshot(after)))
                .usingCapability("terminal.manage")
                .correlatedBy(tenantId + ":terminal-policy")
                .occurredAt(java.time.Instant.now())
                .build());
        return view(tenantId);
    }

    private <T> void write(ConfigurationKey<T> key, ResourceScope scope, T value, ActorRef actor, String reason) {
        Resolved<T> current = resolver.resolve(key, scope);
        boolean scoped = current.trace().source() == ResolutionTrace.Source.SCOPED_VALUE
                && current.trace().winningScope() == ResourceScope.ScopeType.TENANT;
        if (scoped && value.equals(current.value())) {
            return;
        }
        Long expected = author.currentVersion(key, scope).orElse(null);
        try {
            author.set(key, scope, value, false, expected, actor, reason);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    private static Map<String, Object> snapshot(TerminalPolicy policy) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("pinLength", policy.pinLength());
        flat.put("autoLockSeconds", policy.autoLockSeconds());
        flat.put("sessionMaxHours", policy.sessionMaxHours());
        flat.put("rosterDisplay", policy.rosterDisplay().name());
        flat.put("attributionDefault", policy.attributionDefault().name());
        return flat;
    }

    private int intOf(ConfigurationKey<Integer> key, ResourceScope scope, int fallback) {
        Integer value = resolver.value(key, scope);
        return value == null ? fallback : value;
    }

    private <E extends Enum<E>> E enumOf(ConfigurationKey<String> key, ResourceScope scope, E fallback) {
        String value = resolver.value(key, scope);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    private <T> Setting<T> setting(ConfigurationKey<T> key, ResourceScope scope, T fallback) {
        Resolved<T> resolved = resolver.resolve(key, scope);
        T value = resolved.value() == null ? fallback : resolved.value();
        ResolutionTrace trace = resolved.trace();
        String source = trace.source() == ResolutionTrace.Source.CODE_DEFAULT
                ? "DEFAULT"
                : trace.winningScope() == null
                        ? "DEFAULT"
                        : trace.winningScope().name();
        return new Setting<>(value, source);
    }
}
