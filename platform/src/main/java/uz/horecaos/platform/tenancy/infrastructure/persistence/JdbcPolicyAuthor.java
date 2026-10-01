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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

        // Serialise publications of one (key, scope) -- see lockPublication.
        lockPublication(key.code(), scope);
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
            // uq_policy_scope_version, where it can fire (see lockPublication for why it
            // cannot at TENANT and BRAND scope). Publications are serialised, so this is
            // defence in depth rather than the mechanism.
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
            // uq_policy_current_{tenant,brand,location,platform}. Publications of one scope are
            // serialised by lockPublication, so this is defence in depth: were two ever to
            // interleave, the loser's transaction rolls back with its policy row and must not
            // surface as a server error.
            throw concurrentPublication(expectedVersion, version);
        }

        evictResolutions(key.code(), scope);

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
     * Drops the cached resolutions this publication changes -- the scope's and every scope beneath it.
     *
     * <p>Twice. Right after the pointer moves, so a read later in this same transaction resolves the
     * version just written, and again once the transaction has completed: between the first eviction
     * and the commit a concurrent reader still sees the version being replaced, and its answer is
     * cached for the TTL. Nothing but an eviction after the commit (or after a rollback, when the
     * reader in this transaction cached a version that then ceased to exist) removes that. A
     * publication outside any transaction has only the first.
     */
    private void evictResolutions(String keyCode, ResourceScope scope) {
        policyCurrentCache.evict(keyCode, scope);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    policyCurrentCache.evict(keyCode, scope);
                }
            });
        }
    }

    /**
     * Makes two publications of the same key and scope run one after the other, for the length of
     * the surrounding transaction ({@code pg_advisory_xact_lock}: released at commit or rollback,
     * and a harmless statement-long lock when the writer is used outside a transaction).
     *
     * <p>Nothing else does. {@code uq_policy_scope_version} includes {@code brand_id} and
     * {@code location_id}, which are NULL for a TENANT scope (and {@code location_id} for a BRAND
     * one), and PostgreSQL treats NULLs as distinct, so it never refuses a duplicate there. The
     * pointer's partial unique indexes would, but only while the first writer has not yet
     * committed: the pointer is moved by a DELETE followed by an INSERT, and a second writer whose
     * DELETE runs after the first has committed deletes the first's pointer and inserts its own.
     * Two operators who both read version N and both published "N + 1" then both succeed, leaving
     * two policies rows with one version number and the pointer on whichever committed last --
     * the lost update the version check exists to prevent. Under this lock the second writer
     * re-reads the latest version only after the first has committed, so it sees N + 1 and is
     * refused (with an expectation) or takes N + 2 (without one).
     */
    private void lockPublication(String keyCode, ResourceScope scope) {
        String lockKey = "policy|%s|%s|%s|%s|%s"
                .formatted(keyCode, scope.type(), scope.tenantId(), scope.brandId(), scope.locationId());
        // The row mapper never reads the void column; consuming the one row is what waits for the lock.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .param("lockKey", lockKey)
                .query((row, number) -> Boolean.TRUE)
                .list();
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
