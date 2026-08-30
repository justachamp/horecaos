package uz.horecaos.platform.audit.api;

import java.time.Duration;
import java.util.Objects;

import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * A request to perform an action that may need a second pair of eyes (ADR 0027).
 *
 * @param parametersHash a hash of the exact parameters being approved, so an
 *                       approval cannot be reused for a different action. Build
 *                       it with {@link ApprovalParameters}, which derives it from
 *                       the command record rather than from a hand-written field
 *                       list — every call site that wrote its own list had lost a
 *                       field that changes what happens
 */
public record ApprovalRequestCommand(
        String actionCode,
        String parametersHash,
        ResourceScope scope,
        ActorRef requester,
        String reason,
        Duration validity) {

    public static final Duration DEFAULT_VALIDITY = Duration.ofHours(24);

    public ApprovalRequestCommand {
        Objects.requireNonNull(actionCode, "An action code is required");
        ApprovalAction.require(actionCode);
        Objects.requireNonNull(scope, "A scope is required");
        Objects.requireNonNull(requester, "A requester is required");
        Objects.requireNonNull(validity, "A validity period is required");
        if (parametersHash == null || !parametersHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("A lower-case SHA-256 parameters hash is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An approval request requires a reason");
        }
    }
}
