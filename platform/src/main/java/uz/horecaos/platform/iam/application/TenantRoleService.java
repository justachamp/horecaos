package uz.horecaos.platform.iam.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.RoleChanged;
import uz.horecaos.platform.iam.api.TenantJobRules;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant defines, edits and retires its own jobs (ADR 0186, gap-map row 9.1b).
 *
 * <p>A job is a name, a level and a set of capability codes taken from the code-owned
 * catalogue. Three rules keep that from becoming a way to mint authority:
 *
 * <ol>
 *   <li><strong>The author holds what she writes.</strong> Every capability in the resulting
 *       set must be one the author holds at {@code TENANT} scope at the moment of the write
 *       ({@link AuthorizationService#held}). An administrator who lacks a capability cannot
 *       put it in a job, and cannot keep it in one she edits.
 *   <li><strong>The deny-list.</strong> {@link TenantJobRules#isComposable}: nothing no human
 *       job carries, and nothing in {@code platform.*} or {@code iam.*}. A tenant job can
 *       never confer the power to define jobs or to give them away.
 *   <li><strong>The level is the author's word and the granter's limit.</strong> It is fixed at
 *       creation and read by {@code GrantManagementService.requireGrantable} (ADR 0103) when
 *       a branch manager gives the job away.
 * </ol>
 *
 * <p>An edit takes effect for every holder at the next request: the grant cache of each active
 * holder is evicted, now and again after commit. The job's code is generated, immutable and
 * never a code-owned role's, so a tenant job cannot shadow {@code tenant-owner}
 * ({@code GrantManagementService.resolveRole} consults the code-owned registry first
 * regardless).
 *
 * <p>Every method binds the tenant first (V0693: {@code iam.roles} has row-level security) and
 * is a transaction, so the binding lasts for the statements that follow.
 */
@Service
public class TenantRoleService {

    private static final Pattern CODE_SHAPE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private final JdbcClient jdbc;
    private final AuthorizationService authorization;
    private final JdbcAuthorizationService cacheOwner;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TenantRlsSession rls;

    public TenantRoleService(
            JdbcClient jdbc,
            AuthorizationService authorization,
            JdbcAuthorizationService cacheOwner,
            ApplicationEventPublisher events,
            Clock clock,
            TenantRlsSession rls) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.cacheOwner = cacheOwner;
        this.events = events;
        this.clock = clock;
        this.rls = rls;
    }

    /** What the author typed: a name and a capability set, plus the level and provenance on creation. */
    public record Draft(
            String name,
            @Nullable ScopeType scopeType,
            Collection<String> capabilities,
            @Nullable String copiedFrom) {}

    /** One row of the capability grid's vocabulary, as the author sees it. */
    public record CatalogueEntry(Capability capability, boolean held, boolean composable) {}

    // ------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<TenantJob> list(UUID tenantId, boolean includeRetired) {
        rls.bindTenant(tenantId);
        return TenantJobReads.list(jdbc, tenantId, includeRetired, clock.instant());
    }

    @Transactional(readOnly = true)
    public TenantJob get(UUID tenantId, String code) {
        rls.bindTenant(tenantId);
        return TenantJobReads.find(jdbc, tenantId, code, clock.instant()).orElseThrow(NoSuchJobException::new);
    }

    /**
     * Every capability a code-owned job carries, each marked with whether the reader holds it at
     * the company level and whether a tenant job may carry it at all -- the vocabulary of the
     * capability grid. The code-owned registry endpoint is platform-admin only; this is its
     * tenant-readable counterpart, and it lists only what some human job already carries.
     */
    public List<CatalogueEntry> catalogue(UUID tenantId, String readerSubject) {
        Set<Capability> held = authorization.held(readerSubject, ResourceScope.tenant(tenantId));
        return TenantJobRules.catalogue().stream()
                .sorted(Comparator.comparing(Capability::resourceType).thenComparing(Capability::action))
                .map(capability -> new CatalogueEntry(
                        capability, held.contains(capability), TenantJobRules.isComposable(capability)))
                .toList();
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public TenantJob create(UUID tenantId, Draft draft, String reason, String authorSubject) {
        rls.bindTenant(tenantId);
        ResourceScope tenant = ResourceScope.tenant(tenantId);
        authorization.require(authorSubject, Capability.IAM_ROLE_MANAGE, tenant);
        String why = requireReason(reason);

        String name = normalizeName(draft.name());
        ScopeType level = requireLevel(draft.scopeType());
        Set<Capability> capabilities = requireComposable(draft.capabilities());
        String copiedFrom = requireCopiedFrom(draft.copiedFrom());
        requireHeldByAuthor(authorSubject, tenant, capabilities);

        // One writer per tenant at a time, so the ceiling and the duplicate-name check below
        // cannot both be passed by two concurrent creates. Released at commit.
        lockTenantJobs(tenantId);
        List<TenantJob> active = TenantJobReads.list(jdbc, tenantId, false, clock.instant());
        if (active.size() >= TenantJobRules.MAX_ACTIVE_JOBS) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A company has at most %d active jobs of its own; close one before making another"
                            .formatted(TenantJobRules.MAX_ACTIVE_JOBS));
        }
        requireNameFree(active, name, null);

        Instant now = clock.instant();
        UUID id = Ids.newId();
        String code = freshCode(tenantId);
        jdbc.sql("""
                INSERT INTO iam.roles
                    (id, tenant_id, code, name, scope_type, status, is_platform_defined, version,
                     created_by, updated_by, copied_from, created_at, updated_at)
                VALUES (:id, :tenantId, :code, :name, :scopeType, 'ACTIVE', false, 1,
                        :actor, :actor, :copiedFrom, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("name", name)
                .param("scopeType", level.name())
                .param("actor", authorSubject)
                .param("copiedFrom", copiedFrom)
                .param("now", at(now))
                .update();
        insertCapabilities(id, capabilities);

        TenantJob created = TenantJobReads.find(jdbc, tenantId, code, now).orElseThrow();
        events.publishEvent(new RoleChanged(
                id,
                tenantId,
                code,
                RoleChanged.Change.CREATED,
                authorSubject,
                why,
                Map.of(),
                snapshot(created),
                correlationId(id),
                now));
        return created;
    }

    @Transactional
    public TenantJob update(
            UUID tenantId, String code, Draft draft, long expectedVersion, String reason, String authorSubject) {
        rls.bindTenant(tenantId);
        ResourceScope tenant = ResourceScope.tenant(tenantId);
        authorization.require(authorSubject, Capability.IAM_ROLE_MANAGE, tenant);
        String why = requireReason(reason);

        TenantJob before = lockedJob(tenantId, code);
        if (!before.active()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "A closed job is not edited");
        }
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }

        String name = normalizeName(draft.name());
        Set<Capability> capabilities = requireComposable(draft.capabilities());
        requireHeldByAuthor(authorSubject, tenant, capabilities);
        lockTenantJobs(tenantId);
        requireNameFree(TenantJobReads.list(jdbc, tenantId, false, clock.instant()), name, before.id());

        Set<String> wanted = codes(capabilities);
        Set<String> had = new TreeSet<>(before.capabilities());
        if (name.equals(before.name()) && wanted.equals(had)) {
            return before;
        }

        Instant now = clock.instant();
        int updated = jdbc.sql("""
                UPDATE iam.roles
                   SET name = :name, version = version + 1, updated_by = :actor, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId AND version = :expected AND status = 'ACTIVE'
                """)
                .param("name", name)
                .param("actor", authorSubject)
                .param("now", at(now))
                .param("id", before.id())
                .param("tenantId", tenantId)
                .param("expected", expectedVersion)
                .update();
        if (updated != 1) {
            throw ApiException.staleVersion(expectedVersion, get(tenantId, code).version());
        }
        replaceCapabilities(before.id(), had, wanted);

        TenantJob after = TenantJobReads.find(jdbc, tenantId, code, now).orElseThrow();
        evictEveryHolder(tenantId, before.id());
        events.publishEvent(new RoleChanged(
                before.id(),
                tenantId,
                code,
                RoleChanged.Change.EDITED,
                authorSubject,
                why,
                snapshot(before),
                snapshot(after),
                correlationId(before.id()),
                now));
        return after;
    }

    @Transactional
    public TenantJob retire(UUID tenantId, String code, long expectedVersion, String reason, String authorSubject) {
        rls.bindTenant(tenantId);
        authorization.require(authorSubject, Capability.IAM_ROLE_MANAGE, ResourceScope.tenant(tenantId));
        String why = requireReason(reason);

        TenantJob before = lockedJob(tenantId, code);
        if (!before.active()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This job is already closed");
        }
        if (before.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
        // Counted against the same rows the grant path reads, after the lock on the job, so a
        // grant made a moment ago is seen. A retire never silently takes access away.
        if (before.holders() > 0) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This job is still held by %d %s; take it away from them before closing it"
                            .formatted(before.holders(), before.holders() == 1 ? "person" : "people"));
        }

        Instant now = clock.instant();
        int updated = jdbc.sql("""
                UPDATE iam.roles
                   SET status = 'RETIRED', version = version + 1, updated_by = :actor, updated_at = :now,
                       retired_at = :now, retired_by = :actor, retired_reason = :reason
                 WHERE id = :id AND tenant_id = :tenantId AND version = :expected AND status = 'ACTIVE'
                """)
                .param("actor", authorSubject)
                .param("now", at(now))
                .param("reason", why)
                .param("id", before.id())
                .param("tenantId", tenantId)
                .param("expected", expectedVersion)
                .update();
        if (updated != 1) {
            throw ApiException.staleVersion(expectedVersion, get(tenantId, code).version());
        }

        TenantJob after = TenantJobReads.find(jdbc, tenantId, code, now).orElseThrow();
        events.publishEvent(new RoleChanged(
                before.id(),
                tenantId,
                code,
                RoleChanged.Change.RETIRED,
                authorSubject,
                why,
                snapshot(before),
                snapshot(after),
                correlationId(before.id()),
                now));
        return after;
    }

    // ------------------------------------------------------------------ rules

    /** One line, trimmed, whitespace collapsed, no control characters, at most the ceiling long. */
    static String normalizeName(@Nullable String raw) {
        String collapsed = raw == null ? "" : raw.strip().replaceAll("(?U)\\s+", " ");
        if (collapsed.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A job needs a name");
        }
        if (collapsed.codePointCount(0, collapsed.length()) > TenantJobRules.MAX_NAME_LENGTH) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A job's name is at most %d characters".formatted(TenantJobRules.MAX_NAME_LENGTH));
        }
        if (collapsed.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A job's name is plain text on one line");
        }
        return collapsed;
    }

    private static String requireReason(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A change to a job states its reason");
        }
        return reason.strip();
    }

    private static ScopeType requireLevel(@Nullable ScopeType level) {
        if (level == null || level == ScopeType.PLATFORM) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A job is given at the company, a brand or a branch");
        }
        return level;
    }

    private static @Nullable String requireCopiedFrom(@Nullable String copiedFrom) {
        if (copiedFrom == null || copiedFrom.isBlank()) {
            return null;
        }
        String trimmed = copiedFrom.strip();
        if (!CODE_SHAPE.matcher(trimmed).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A copied job is named by its code");
        }
        return trimmed;
    }

    /**
     * Parses and checks the deny-list. A code the build does not declare is refused rather than
     * ignored: silently dropping it would save a job that does less than the author ticked.
     */
    private static Set<Capability> requireComposable(@Nullable Collection<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A job carries at least one thing it may do");
        }
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        for (String code : requested) {
            Capability capability = Capability.find(code == null ? "" : code.strip())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown capability"));
            if (!TenantJobRules.isComposable(capability)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "%s is not something a job a company defines can carry".formatted(capability.code()));
            }
            capabilities.add(capability);
        }
        return capabilities;
    }

    private void requireHeldByAuthor(String authorSubject, ResourceScope tenant, Set<Capability> capabilities) {
        Set<Capability> held = authorization.held(authorSubject, tenant);
        for (Capability capability : capabilities) {
            if (!held.contains(capability)) {
                throw new AuthorizationService.AccessDeniedException(capability, tenant);
            }
        }
    }

    private static void requireNameFree(List<TenantJob> active, String name, @Nullable UUID exceptId) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (TenantJob job : active) {
            if (!job.id().equals(exceptId)
                    && job.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Another active job already has this name");
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private void lockTenantJobs(UUID tenantId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "iam.roles:" + tenantId)
                .query()
                .listOfRows();
    }

    /** The job row, locked for the rest of the transaction; the same answer for a code that is not this tenant's. */
    private TenantJob lockedJob(UUID tenantId, String code) {
        UUID id = jdbc.sql("""
                SELECT id FROM iam.roles
                 WHERE tenant_id = :tenantId AND code = :code AND NOT is_platform_defined
                   FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("code", code)
                .query(UUID.class)
                .optional()
                .orElseThrow(NoSuchJobException::new);
        return TenantJobReads.find(jdbc, tenantId, code, clock.instant()).orElseThrow(NoSuchJobException::new);
    }

    private String freshCode(UUID tenantId) {
        // The advisory lock is held, so nobody else is minting a code for this tenant.
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = TenantJobRules.CODE_PREFIX
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            Long taken = jdbc.sql("SELECT count(*) FROM iam.roles WHERE tenant_id = :tenantId AND code = :code")
                    .param("tenantId", tenantId)
                    .param("code", code)
                    .query(Long.class)
                    .single();
            if (taken == 0 && PlatformRole.find(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not mint a free job code");
    }

    private void insertCapabilities(UUID roleId, Collection<Capability> capabilities) {
        if (capabilities.isEmpty()) {
            return;
        }
        jdbc.sql("""
                INSERT INTO iam.role_capabilities (role_id, capability_code)
                SELECT :roleId, unnest(CAST(:codes AS varchar[]))
                """)
                .param("roleId", roleId)
                .param("codes", codes(capabilities).toArray(String[]::new))
                .update();
    }

    private void replaceCapabilities(UUID roleId, Set<String> had, Set<String> wanted) {
        Set<String> removed = new TreeSet<>(had);
        removed.removeAll(wanted);
        Set<String> added = new TreeSet<>(wanted);
        added.removeAll(had);
        if (!removed.isEmpty()) {
            jdbc.sql("DELETE FROM iam.role_capabilities WHERE role_id = :roleId AND capability_code = ANY(:codes)")
                    .param("roleId", roleId)
                    .param("codes", removed.toArray(String[]::new))
                    .update();
        }
        if (!added.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO iam.role_capabilities (role_id, capability_code)
                    SELECT :roleId, unnest(CAST(:codes AS varchar[]))
                    """)
                    .param("roleId", roleId)
                    .param("codes", added.toArray(String[]::new))
                    .update();
        }
    }

    /**
     * An edit is live for every holder at their next request: their cached grants are dropped
     * now, and once more after commit, because a reader that repopulates the cache between the
     * first eviction and the commit would otherwise keep the old capabilities until the entry
     * expires.
     */
    private void evictEveryHolder(UUID tenantId, UUID roleId) {
        List<String> holders = jdbc.sql("""
                SELECT DISTINCT principal_subject FROM iam.grants
                 WHERE role_id = :roleId AND tenant_id = :tenantId AND status = 'ACTIVE'
                """)
                .param("roleId", roleId)
                .param("tenantId", tenantId)
                .query(String.class)
                .list();
        holders.forEach(subject -> cacheOwner.evictGrants(subject, tenantId));
        if (!holders.isEmpty() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    holders.forEach(subject -> cacheOwner.evictGrants(subject, tenantId));
                }
            });
        }
    }

    private static Map<String, Object> snapshot(TenantJob job) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", job.name());
        fields.put("level", job.scopeType().name());
        fields.put("status", job.status());
        fields.put("capabilities", new ArrayList<>(job.capabilities()));
        if (job.copiedFrom() != null) {
            fields.put("copiedFrom", job.copiedFrom());
        }
        return fields;
    }

    private static Set<String> codes(Collection<Capability> capabilities) {
        Set<String> codes = new TreeSet<>();
        capabilities.forEach(capability -> codes.add(capability.code()));
        return codes;
    }

    /** The request's correlation id when there is one (set by CorrelationIdFilter), else the role's own id. */
    private static String correlationId(UUID roleId) {
        String fromRequest = MDC.get("correlationId");
        return fromRequest == null || fromRequest.isBlank() ? roleId.toString() : fromRequest;
    }

    private static OffsetDateTime at(Instant instant) {
        return Objects.requireNonNull(instant).atOffset(ZoneOffset.UTC);
    }

    /**
     * The code names no job this company defined: it does not exist, or it is a code-owned job,
     * or it is another company's. One answer for all three, for the reason {@code
     * GrantManagementService.NoSuchRoleException} gives.
     */
    public static final class NoSuchJobException extends ApiException {
        public NoSuchJobException() {
            super(ErrorCode.RESOURCE_NOT_FOUND, "No such job");
        }
    }
}
