package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's credentials that are due to be rotated (ADR 0094).
 *
 * <p>Provider credentials do not tell the platform when they expire, so the
 * rule is their age: a credential not rotated through the platform within the
 * rotation interval is due, counted from its last rotation or, if it never
 * was, from when it was set up. The values stay in the secrets manager; this
 * reads only the dates beside their references.
 */
@RestController
@Tag(name = "Platform integration administration", description = "Cross-tenant provider registry and installations")
public class CredentialRotationController {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration rotationInterval;

    public CredentialRotationController(
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.integration.credential-rotation-interval:P180D}") Duration rotationInterval) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rotationInterval = rotationInterval;
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/credentials-due")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's credentials due to be rotated",
            description = "Provider installations and payment merchant accounts whose credential has "
                    + "not been rotated through the platform within the rotation interval, oldest first.")
    CredentialsDue due(@PathVariable UUID tenantId) {
        Instant now = clock.instant();
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(now.minus(rotationInterval), ZoneOffset.UTC);
        List<DueCredential> due = jdbc.sql("""
                        SELECT 'INSTALLATION' AS kind, id, provider_type, display_name AS label,
                               last_secret_rotated_at, created_at
                          FROM integration.installations
                         WHERE tenant_id = :tenantId AND secret_reference IS NOT NULL
                           AND status <> 'RETIRED'
                           AND coalesce(last_secret_rotated_at, created_at) < :cutoff
                        UNION ALL
                        SELECT 'MERCHANT_ACCOUNT' AS kind, id, provider_type, merchant_account_reference AS label,
                               last_secret_rotated_at, created_at
                          FROM payments.merchant_bindings
                         WHERE tenant_id = :tenantId
                           AND coalesce(last_secret_rotated_at, created_at) < :cutoff
                         ORDER BY 5 NULLS FIRST, 6
                        """)
                .param("tenantId", tenantId)
                .param("cutoff", cutoff)
                .query((row, number) -> {
                    OffsetDateTime rotated = row.getObject("last_secret_rotated_at", OffsetDateTime.class);
                    Instant since = rotated != null
                            ? rotated.toInstant()
                            : row.getObject("created_at", OffsetDateTime.class).toInstant();
                    return new DueCredential(
                            row.getString("kind"),
                            row.getObject("id", UUID.class),
                            row.getString("provider_type"),
                            row.getString("label"),
                            rotated == null ? null : rotated.toInstant().toString(),
                            Math.max(0, Duration.between(since, now).toDays()));
                })
                .list();
        return new CredentialsDue(rotationInterval.toDays(), due);
    }

    /** The interval a credential is rotated within, and the ones past it. */
    public record CredentialsDue(long rotationIntervalDays, List<DueCredential> credentials) {}

    /**
     * One credential due.
     *
     * @param lastRotatedAt null when it was never rotated through the platform
     * @param daysOld       since its last rotation, or since it was set up
     */
    public record DueCredential(
            String kind,
            UUID id,
            String providerType,
            String label,
            @Nullable String lastRotatedAt,
            long daysOld) {}
}
