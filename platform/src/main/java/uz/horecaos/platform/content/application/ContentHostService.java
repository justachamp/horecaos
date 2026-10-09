package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The brand's external-link allowlist (ADR 0157, Decision 3): the open-redirect control.
 *
 * <p>A brand may link to the tenant's own verified channel hostnames and nothing else until an
 * author with {@code content.manage} adds a host, one at a time -- no wildcards, {@code https}
 * only, no port, no credentials -- and each addition and removal is an audited <em>security</em>
 * fact. A host that stops being allowed stops being linked within one cache window, because the
 * read checks again. {@code t.me}, {@code instagram.com} and similar are not preloaded.
 */
@Service
public class ContentHostService {

    private static final Pattern HOST =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    private final JdbcContentStore store;
    private final ContentEnvironment environment;
    private final AuditRecorder audit;
    private final Clock clock;

    public ContentHostService(
            JdbcContentStore store, ContentEnvironment environment, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.environment = environment;
        this.audit = audit;
        this.clock = clock;
    }

    /** One host a brand may link to, and where it comes from. */
    public record AllowedHost(String host, String source, String addedBy, Instant addedAt) {}

    @Transactional(readOnly = true)
    public List<AllowedHost> list(UUID tenantId, UUID brandId) {
        List<AllowedHost> hosts = new ArrayList<>();
        store.allowedHosts(tenantId, brandId)
                .forEach(row -> hosts.add(new AllowedHost(row.host(), "ADDED", row.createdBy(), row.createdAt())));
        environment.brand().verifiedChannelHostnames(tenantId).stream()
                .sorted()
                .forEach(host -> hosts.add(new AllowedHost(host, "VERIFIED_CHANNEL_HOSTNAME", "", Instant.EPOCH)));
        return hosts;
    }

    /**
     * Adds a host, once.
     *
     * @return the host as stored (lower-case)
     */
    @Transactional
    public String add(UUID tenantId, UUID brandId, String actor, String rawHost, @Nullable String reason) {
        String host = normalise(rawHost);
        if (!environment.brand().brandExists(tenantId, brandId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand");
        }
        Instant now = clock.instant();
        if (!store.addAllowedHost(tenantId, brandId, host, actor, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That host is already on the allowlist");
        }
        audit.record(AuditFact.of("content.host.allowed", AuditClass.SECURITY)
                .by(ActorRef.user(actor, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target(
                        "content.allowed_host",
                        UUID.nameUUIDFromBytes(
                                (brandId + ":" + host).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .usingCapability(Capability.CONTENT_MANAGE.code())
                .because(ContentPublicationService.reasonOr(reason, "Host allowed"))
                .changed(ChangeDocuments.diff(Map.of(), Map.of("host", host)))
                .correlatedBy(brandId.toString())
                .occurredAt(now)
                .build());
        return host;
    }

    @Transactional
    public void remove(UUID tenantId, UUID brandId, String actor, String rawHost) {
        String host = normalise(rawHost);
        Instant now = clock.instant();
        if (!store.removeAllowedHost(tenantId, brandId, host)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That host is not on the allowlist");
        }
        audit.record(AuditFact.of("content.host.removed", AuditClass.SECURITY)
                .by(ActorRef.user(actor, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target(
                        "content.allowed_host",
                        UUID.nameUUIDFromBytes(
                                (brandId + ":" + host).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .usingCapability(Capability.CONTENT_MANAGE.code())
                .because("Host removed")
                .changed(ChangeDocuments.diff(Map.of("host", host), Map.of()))
                .correlatedBy(brandId.toString())
                .occurredAt(now)
                .build());
    }

    /** A bare lower-case DNS name: no scheme, port, path, wildcard or credentials. */
    static String normalise(String raw) {
        String host = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (host.length() > 253 || !HOST.matcher(host).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A host is a bare DNS name such as shop.example.uz: no scheme, port, path or wildcard",
                    Map.of(
                            "errors",
                            List.of(new uz.horecaos.platform.web.api.ApiProblem.FieldError(
                                    "host", "BAD_HOST", "A host is a bare DNS name"))));
        }
        return host;
    }
}
