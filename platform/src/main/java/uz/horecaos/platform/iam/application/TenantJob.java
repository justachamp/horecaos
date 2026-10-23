package uz.horecaos.platform.iam.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * A job a tenant defined (ADR 0186), as the management screens read it.
 *
 * @param capabilities  capability codes, sorted. May include a code the build no longer
 *                      declares until the next startup prunes it; such a code grants nothing
 * @param holders       distinct people holding an active, in-window grant of this job right now
 * @param createdBy     an opaque principal subject, never a name; null on a row a fixture wrote by hand
 * @param retiredAt     null while the job is active
 */
public record TenantJob(
        UUID id,
        String code,
        String name,
        ScopeType scopeType,
        List<String> capabilities,
        long version,
        String status,
        @Nullable String copiedFrom,
        @Nullable String createdBy,
        @Nullable String updatedBy,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt,
        @Nullable Instant retiredAt,
        @Nullable String retiredReason,
        int holders) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
