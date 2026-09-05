package uz.horecaos.platform.voice.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.voice.domain.OperatorPresenceState;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore.PresenceRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Operator presence (ADR 0064): a small, channel-neutral, audited state
 * machine with no states of its own to enforce — ONLINE, PAUSED, WRAP_UP, and
 * OFFLINE are all reachable from each other, because a dropped call, a
 * force-closed tab, or a supervisor stepping in for someone who forgot to
 * pause all look identical from here and none of them should be refused.
 *
 * <p>Self-service by construction: {@code operatorPrincipalId} always comes
 * from the authenticated actor, never from a request body, the same
 * discipline {@code CustomerController.resolve} uses for "you may only
 * resolve your own account."
 */
@Service
public class OperatorPresenceService {

    private final JdbcVoiceStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public OperatorPresenceService(JdbcVoiceStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void setPresence(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String operatorPrincipalId,
            OperatorPresenceState state,
            @Nullable String reason,
            ActorRef actor,
            String capabilityUsed,
            String correlationId) {

        if (state.requiresReason() && (reason == null || reason.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Pausing requires a reason");
        }

        var now = clock.instant();
        store.upsertPresence(
                UUID.randomUUID(), tenantId, brandId, locationId, operatorPrincipalId, state.name(), reason, now);

        audit.record(AuditFact.of("voice.presence.changed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("OperatorPresence", deterministicId(tenantId, locationId, operatorPrincipalId))
                .because(reason == null || reason.isBlank() ? "Operator changed their own presence" : reason)
                .usingCapability(capabilityUsed)
                .changed(Map.of("state", state.name(), "reason", String.valueOf(reason)))
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<PresenceRow> mine(UUID tenantId, UUID locationId, String operatorPrincipalId) {
        return store.presence(tenantId, locationId, operatorPrincipalId);
    }

    @Transactional(readOnly = true)
    public List<PresenceRow> roster(UUID tenantId, UUID locationId) {
        return store.presenceForLocation(tenantId, locationId);
    }

    /**
     * A stable id for the audit target, since presence has no aggregate id of
     * its own to name — a row is keyed on {@code (tenant, location, operator)},
     * not a minted UUID that would change every time the state changes.
     */
    private static UUID deterministicId(UUID tenantId, UUID locationId, String operatorPrincipalId) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + locationId + ":" + operatorPrincipalId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
