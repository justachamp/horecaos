package uz.horecaos.platform.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * One immutable record of something that happened (ADR 0027).
 *
 * <p>Structured before and after values, not free text: a reason string alone
 * cannot answer "what changed" years later, and a change document alone cannot
 * answer "why".
 */
public record AuditFact(
        UUID id,
        AuditClass auditClass,
        String actionCode,
        ActorRef actor,
        ResourceScope scope,
        @Nullable String targetType,
        @Nullable UUID targetId,
        @Nullable Long targetVersion,
        Outcome outcome,
        @Nullable String reason,
        Map<String, Object> changeDocument,
        @Nullable String evidenceReference,
        @Nullable String capabilityUsed,
        @Nullable UUID approvalRequestId,
        String correlationId,
        @Nullable String causationId,
        Instant occurredAt) {

    public enum Outcome {
        SUCCEEDED,
        REJECTED,
        FAILED
    }

    public AuditFact {
        Objects.requireNonNull(id, "An audit event ID is required");
        Objects.requireNonNull(auditClass, "An audit class is required");
        Objects.requireNonNull(actionCode, "An action code is required");
        Objects.requireNonNull(actor, "An actor is required");
        Objects.requireNonNull(scope, "A scope is required");
        Objects.requireNonNull(outcome, "An outcome is required");
        Objects.requireNonNull(correlationId, "A correlation ID is required");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        changeDocument = changeDocument == null ? Map.of() : Map.copyOf(changeDocument);

        // An operator-initiated action without a reason produces an audit trail
        // that records what happened and never why, which is half an answer.
        if (actor.type() == ActorRef.Type.USER && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A user-initiated action requires a reason: " + actionCode);
        }
    }

    public static Builder of(String actionCode, AuditClass auditClass) {
        return new Builder(actionCode, auditClass);
    }

    /** Keeps call sites readable while every recorded field stays explicit. */
    public static final class Builder {
        private final String actionCode;
        private final AuditClass auditClass;
        private UUID id = UUID.randomUUID();
        private @Nullable ActorRef actor;
        private @Nullable ResourceScope scope;
        private @Nullable String targetType;
        private @Nullable UUID targetId;
        private @Nullable Long targetVersion;
        private Outcome outcome = Outcome.SUCCEEDED;
        private @Nullable String reason;
        private Map<String, Object> changeDocument = Map.of();
        private @Nullable String evidenceReference;
        private @Nullable String capabilityUsed;
        private @Nullable UUID approvalRequestId;
        private @Nullable String correlationId;
        private @Nullable String causationId;
        private @Nullable Instant occurredAt;

        private Builder(String actionCode, AuditClass auditClass) {
            this.actionCode = actionCode;
            this.auditClass = auditClass;
        }

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder by(ActorRef value) {
            this.actor = value;
            return this;
        }

        public Builder at(ResourceScope value) {
            this.scope = value;
            return this;
        }

        public Builder target(String type, UUID identifier) {
            this.targetType = type;
            this.targetId = identifier;
            return this;
        }

        public Builder targetVersion(Long value) {
            this.targetVersion = value;
            return this;
        }

        public Builder outcome(Outcome value) {
            this.outcome = value;
            return this;
        }

        public Builder because(String value) {
            this.reason = value;
            return this;
        }

        public Builder changed(Map<String, Object> value) {
            this.changeDocument = value;
            return this;
        }

        public Builder evidence(@Nullable String reference) {
            this.evidenceReference = reference;
            return this;
        }

        public Builder usingCapability(String capability) {
            this.capabilityUsed = capability;
            return this;
        }

        public Builder underApproval(@Nullable UUID requestId) {
            this.approvalRequestId = requestId;
            return this;
        }

        public Builder correlatedBy(String value) {
            this.correlationId = value;
            return this;
        }

        public Builder causedBy(String value) {
            this.causationId = value;
            return this;
        }

        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        public AuditFact build() {
            // Re-checked here, with the same messages the compact constructor
            // enforces, so the builder's fields can honestly stay @Nullable while
            // this method still hands AuditFact's non-null components non-null
            // values — NullAway cannot see across the constructor call otherwise.
            return new AuditFact(
                    id,
                    auditClass,
                    actionCode,
                    Objects.requireNonNull(actor, "An actor is required"),
                    Objects.requireNonNull(scope, "A scope is required"),
                    targetType,
                    targetId,
                    targetVersion,
                    outcome,
                    reason,
                    changeDocument,
                    evidenceReference,
                    capabilityUsed,
                    approvalRequestId,
                    Objects.requireNonNull(correlationId, "A correlation ID is required"),
                    causationId,
                    Objects.requireNonNull(occurredAt, "An occurrence time is required"));
        }
    }
}
