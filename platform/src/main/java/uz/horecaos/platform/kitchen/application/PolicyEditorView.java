package uz.horecaos.platform.kitchen.application;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * A policy document as an operator screen reads it (ADR 0030): the document in force at a scope, where it
 * came from, and the version a save is checked against.
 *
 * @param isDefault      nothing was published anywhere in the chain, so the built-in document applies
 * @param winningScope   the level whose document is in force; null for the built-in
 * @param versionAtScope the latest version authored at exactly the requested scope (0 when it inherits):
 *                       the {@code If-Match} to send back
 * @param levels         one rung per level the policy can be set at, and whether that level authored one
 */
public record PolicyEditorView<P>(
        P document,
        boolean isDefault,
        @Nullable ScopeType winningScope,
        @Nullable UUID policyId,
        int policyVersion,
        int versionAtScope,
        List<Level> levels) {

    /** One rung of the resolution ladder. */
    public record Level(ScopeType scopeType, boolean authored) {}
}
