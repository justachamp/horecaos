package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * HorecaOS's own card merchant account, in the shape of an ADR 0026 installation
 * (ADR 0095). Credentials are a secret reference, never a value; the endpoint is
 * an approved environment, never a typed URL.
 *
 * <p>{@code toString} omits the secret reference: it names where a credential
 * lives, and a log line has no business carrying that.
 */
public record PlatformCardInstallation(
        UUID id,
        String providerType,
        @Nullable String environmentCode,
        String displayName,
        String status,
        @Nullable String secretReference,
        @Nullable String externalAccountReference,
        Map<String, Object> nonSensitiveConfig,
        @Nullable Instant lastConnectionCheckAt,
        @Nullable String lastConnectionStatus,
        String createdBy,
        @Nullable String activatedBy,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String RETIRED = "RETIRED";

    public PlatformCardInstallation {
        nonSensitiveConfig = Map.copyOf(nonSensitiveConfig);
    }

    @Override
    public String toString() {
        return "PlatformCardInstallation[id=%s type=%s status=%s]".formatted(id, providerType, status);
    }
}
