package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.tenancy.application.port.PolicyCurrentCache;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * SQL adapter for ADR 0030 policy authoring (Gap D of the 2026-08-30 proving
 * run). {@link JdbcPolicyResolver} answers "what applies here"; this answers
 * "here is the next version, apply it" — the writer neither {@code
 * tenant.policies} nor {@code tenant.policy_current} had before this class.
 */
@Repository
public class JdbcPolicyAuthor implements PolicyAuthor {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final Clock clock;
    private final PolicyCurrentCache policyCurrentCache;

    public JdbcPolicyAuthor(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            AuditRecorder audit,
            Clock clock,
            PolicyCurrentCache policyCurrentCache) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.clock = clock;
        this.policyCurrentCache = policyCurrentCache;
    }

    @Override
    @Transactional
    public <P> ResolvedPolicy<P> author(
            PolicyKey<P> key, ResourceScope scope, P document, ActorRef authoredBy, String reason) {
        return publish(key, scope, document, null, authoredBy, reason);
    }

    @Override
    @Transactional
    public <P> ResolvedPolicy<P> author(
            PolicyKey<P> key,
            ResourceScope scope,
            P document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {
        return publish(key, scope, document, expectedVersion, authoredBy, reason);
    }

    @Override
    public int currentVersion(PolicyKey<?> key, ResourceScope scope) {
        Objects.requireNonNull(key, "A policy key is required");
        Objects.requireNonNull(scope, "A scope is required");
        return latestVersion(key.code(), scope);
    }

    private <P> ResolvedPolicy<P> publish(
            PolicyKey<P> key,
            ResourceScope scope,
            P document,
            @Nullable Integer expectedVersion,
            ActorRef authoredBy,
            String reason) {

        Objects.requireNonNull(key, "A policy key is required");
        Objects.requireNonNull(scope, "A scope is required");
        Objects.requireNonNull(document, "A policy document is required");
        Objects.requireNonNull(authoredBy, "An author is required");
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Authoring a policy requires a reason");
        }
        if (!key.settableScopes().contains(scope.type())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s cannot be set at %s scope; it is settable at %s"
                            .formatted(key.code(), scope.type(), key.settableScopes()));
        }
        if (!key.documentType().isInstance(document)) {
            // A programming error, not a caller mistake: every real call site
            // passes a document PolicyKey.documentType() already describes.
            throw new IllegalArgumentException("%s expects a %s document, got %s"
                    .formatted(key.code(), key.documentType().getSimpleName(), document.getClass()));
        }

        String json = objectMapper.writeValueAsString(document);
        String hash = sha256Hex(json);
        Instant now = clock.instant();
        int latest = latestVersion(key.code(), scope);
        if (expectedVersion != null && expectedVersion != latest) {
            // Read in this transaction, from the table: a cached or earlier answer
            // is exactly what a stale expectation is made of.
            throw ApiException.staleVersion(expectedVersion, latest);
        }
        int version = latest + 1;
        UUID policyId = UUID.randomUUID();

        try {
            jdbc.sql("""
                    INSERT INTO tenant.policies
                        (id, key_code, scope_type, tenant_id, brand_id, location_id, version, status,
                         document, document_hash, valid_from, created_by, approved_by)
                    VALUES (:id, :keyCode, :scopeType, :tenantId, :brandId, :locationId, :version, 'ACTIVE',
                            CAST(:document AS jsonb), :hash, :validFrom, :createdBy, :approvedBy)
                    """)
                    .param("id", policyId)
                    .param("keyCode", key.code())
                    .param("scopeType", scope.type().name())
                    .param("tenantId", scope.tenantId())
                    .param("brandId", scope.brandId())
                    .param("locationId", scope.locationId())
                    .param("version", version)
                    .param("document", json)
                    .param("hash", hash)
                    .param("validFrom", at(now))
                    .param("createdBy", authoredBy.subject())
                    .param("approvedBy", authoredBy.subject())
                    .update();
        } catch (DuplicateKeyException concurrentAuthor) {
            // uq_policy_scope_version, wherever it can fire (its nullable brand and
            // location columns make it inert at TENANT and BRAND scope -- see below).
            throw concurrentPublication(expectedVersion, version);
        } catch (DataIntegrityViolationException violation) {
            throw explain(violation);
        }

        // The version this replaces is never touched — only the pointer moves,
        // so JdbcPolicyResolver.pinned keeps answering with the old document
        // for every decision that already resolved it.
        jdbc.sql("""
                DELETE FROM tenant.policy_current
                 WHERE key_code = :keyCode AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                """)
                .param("keyCode", key.code())
                .param("scopeType", scope.type().name())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .update();

        try {
            jdbc.sql("""
                    INSERT INTO tenant.policy_current
                        (key_code, scope_type, tenant_id, brand_id, location_id,
                         policy_id, policy_version, activated_at, activated_by)
                    VALUES (:keyCode, :scopeType, :tenantId, :brandId, :locationId,
                            :policyId, :version, :now, :activatedBy)
                    """)
                    .param("keyCode", key.code())
                    .param("scopeType", scope.type().name())
                    .param("tenantId", scope.tenantId())
                    .param("brandId", scope.brandId())
                    .param("locationId", scope.locationId())
                    .param("policyId", policyId)
                    .param("version", version)
                    .param("now", at(now))
                    .param("activatedBy", authoredBy.subject())
                    .update();
        } catch (DuplicateKeyException concurrentAuthor) {
            // uq_policy_current_{tenant,brand,location,platform}: the index that really
            // arbitrates two publications of the same scope. uq_policy_scope_version cannot,
            // because its brand_id and location_id are NULL for a TENANT scope (and
            // location_id for a BRAND scope) and PostgreSQL treats NULLs as distinct, so two
            // writers that both read the same latest version each insert a "version N + 1"
            // without complaint and only meet here, on the pointer. The loser's transaction
            // rolls back with its policy row; it must not surface as a server error.
            throw concurrentPublication(expectedVersion, version);
        }

        // Right after the pointer moves, not before: an eviction that fires
        // and is then rolled back with its transaction is merely a wasted
        // cache miss, but one that fires before the write would let a
        // concurrent reader repopulate the cache with the version this call
        // is about to replace.
        policyCurrentCache.evict(key.code(), scope);

        audit.record(AuditFact.of("tenant.policy.authored", AuditClass.BUSINESS)
                .by(authoredBy)
                .at(scope)
                .target("Policy", policyId)
                .because(reason)
                // Staff 9.3a: always a new policy version (latestVersion + 1 never
                // reuses one), no prior state to diff against.
                .changed(ChangeDocuments.created(Map.of(
                        "keyCode",
                        key.code(),
                        "scopeType",
                        scope.type().name(),
                        "version",
                        version,
                        "documentHash",
                        hash)))
                .correlatedBy(policyId.toString())
                .occurredAt(now)
                .build());

        return new ResolvedPolicy<>(key.code(), policyId, version, scope.type(), hash, document);
    }

    /**
     * Two operators published the same scope at once. With an expectation the loser is exactly the
     * stale writer the expectation exists to refuse; without one it can only be told to retry.
     */
    private static ApiException concurrentPublication(@Nullable Integer expectedVersion, int attemptedVersion) {
        if (expectedVersion != null) {
            return ApiException.staleVersion(expectedVersion, attemptedVersion);
        }
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "Another version of this policy was published concurrently; re-read and retry");
    }

    /**
     * The highest version authored at exactly this scope, 0 when none. Matches
     * {@code uq_policy_scope_version} exactly: {@code (key_code, scope_type,
     * tenant_id, brand_id, location_id, version)}.
     */
    private int latestVersion(String keyCode, ResourceScope scope) {
        return jdbc.sql("""
                SELECT coalesce(max(version), 0) FROM tenant.policies
                 WHERE key_code = :keyCode AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                """)
                .param("keyCode", keyCode)
                .param("scopeType", scope.type().name())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .query(Integer.class)
                .single();
    }

    /**
     * A brand or location that is not the named tenant's is a caller mistake, not a
     * server fault: the same three refusals {@code JdbcConfigurationValueAuthor} gives
     * a configuration value.
     */
    private static ApiException explain(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("fk_policy_tenant")) {
            return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such tenant");
        }
        if (message.contains("fk_policy_brand")) {
            return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That brand does not belong to this tenant");
        }
        if (message.contains("fk_policy_location")) {
            return new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "That location does not belong to this tenant and brand");
        }
        return new ApiException(ErrorCode.RESOURCE_CONFLICT, "The policy conflicts with an existing resource");
    }

    private static String sha256Hex(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException required) {
            throw new IllegalStateException("SHA-256 is required by the platform", required);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
