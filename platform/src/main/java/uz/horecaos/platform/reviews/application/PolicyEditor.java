package uz.horecaos.platform.reviews.application;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * A policy document as an editor needs it (ADR 0030): what is in force at a scope, where it came from,
 * and the version a save is checked against.
 *
 * @param isDefault      nothing was published anywhere in the chain, so the built-in default applies
 * @param versionAtScope the latest version authored at exactly the requested scope (0 when it
 *                       inherits): the {@code If-Match} to send back
 */
public record PolicyEditor<D>(
        D document,
        boolean isDefault,
        @Nullable ScopeType winningScope,
        @Nullable UUID policyId,
        int policyVersion,
        int versionAtScope,
        List<Level> levels) {

    /** One rung of the resolution ladder and whether something was authored on it. */
    public record Level(ScopeType scopeType, boolean authored) {}
}
