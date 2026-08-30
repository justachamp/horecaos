package uz.horecaos.platform.iam.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a frontend needs to shape its interface (ADR 0025).
 *
 * <p>This exists to hide controls a user cannot use. It is not an authorization
 * decision: every mutation is authorized again on the server, and a test asserts
 * the view and server enforcement agree.
 */
public record CapabilityView(
        String subject,
        String activeTenantId,
        Set<Capability> capabilities,
        List<ScopeGrant> scopes,
        long contextVersion) {

    public CapabilityView {
        Objects.requireNonNull(subject, "A subject is required");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "Capabilities are required"));
        scopes = List.copyOf(Objects.requireNonNull(scopes, "Scopes are required"));
    }

    /** One scope a principal holds, with what it grants there. */
    public record ScopeGrant(ResourceScope scope, String roleCode, Set<Capability> capabilities) {

        public ScopeGrant {
            capabilities = Set.copyOf(capabilities);
        }
    }
}
