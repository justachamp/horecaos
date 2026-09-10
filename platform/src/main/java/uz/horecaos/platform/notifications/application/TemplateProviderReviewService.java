package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Whether an SMS gateway has approved a template's wording (ADR 0091).
 *
 * <p>HorecaOS staff mark a version as awaiting the provider when its gateway
 * moderates texts, and record the provider's answer when it comes. A version
 * awaiting or refused is withheld from sending by {@link
 * NotificationEligibilityService}; one approved, or one that never needed it,
 * sends as before.
 */
@Service
public class TemplateProviderReviewService {

    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private static final Set<String> STATES = Set.of(NOT_REQUIRED, PENDING, APPROVED, REJECTED);

    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final Clock clock;

    public TemplateProviderReviewService(JdbcClient jdbc, AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Whether a version may be sent as far as its provider is concerned. */
    public static boolean withheld(String providerReview) {
        return PENDING.equals(providerReview) || REJECTED.equals(providerReview);
    }

    /**
     * The SMS versions in force or in draft across tenants, awaiting first and
     * then by most recently reviewed; only those in one state when it is named.
     */
    @Transactional(readOnly = true)
    public List<ReviewRow> list(@Nullable String state, int limit) {
        if (state != null && !STATES.contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown review state %s".formatted(state));
        }
        return jdbc.sql("""
                        SELECT v.id, v.tenant_id, t.display_name AS tenant_name, tpl.template_key,
                               v.version_number, v.locale, v.status, v.body_template, v.provider_review,
                               v.provider_review_reference, v.provider_review_note,
                               v.provider_review_updated_by, v.provider_review_updated_at
                          FROM notifications.template_versions v
                          JOIN notifications.templates tpl ON tpl.id = v.template_id AND tpl.tenant_id = v.tenant_id
                          JOIN tenant.tenants t ON t.id = v.tenant_id
                         WHERE tpl.channel = 'SMS' AND v.status IN ('ACTIVE', 'DRAFT')
                           AND (CAST(:state AS varchar) IS NULL OR v.provider_review = :state)
                         ORDER BY (v.provider_review = 'PENDING') DESC,
                                  v.provider_review_updated_at DESC NULLS LAST, tpl.template_key, v.locale
                         LIMIT :limit
                        """)
                .param("state", state)
                .param("limit", limit)
                .query((row, number) -> {
                    OffsetDateTime updatedAt = row.getObject("provider_review_updated_at", OffsetDateTime.class);
                    return new ReviewRow(
                            row.getObject("id", UUID.class),
                            row.getObject("tenant_id", UUID.class),
                            row.getString("tenant_name"),
                            row.getString("template_key"),
                            row.getInt("version_number"),
                            row.getString("locale"),
                            row.getString("status"),
                            row.getString("body_template"),
                            row.getString("provider_review"),
                            row.getString("provider_review_reference"),
                            row.getString("provider_review_note"),
                            row.getString("provider_review_updated_by"),
                            updatedAt == null ? null : updatedAt.toInstant());
                })
                .list();
    }

    /**
     * Records where a version stands with its provider.
     *
     * <p>Only an SMS version can wait on a provider. Refusing needs the
     * provider's reason, and approving its reference, so the next person can
     * find the decision on the provider's side.
     */
    @Transactional
    public void record(
            UUID tenantId,
            UUID versionId,
            String state,
            @Nullable String reference,
            @Nullable String note,
            ActorRef actor,
            String reason) {
        if (!STATES.contains(state)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown review state %s".formatted(state));
        }
        if (APPROVED.equals(state) && (reference == null || reference.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An approval names the provider's reference");
        }
        if (REJECTED.equals(state) && (note == null || note.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A refusal says what the provider objected to");
        }
        String channel = jdbc.sql("""
                        SELECT tpl.channel
                          FROM notifications.template_versions v
                          JOIN notifications.templates tpl ON tpl.id = v.template_id AND tpl.tenant_id = v.tenant_id
                         WHERE v.tenant_id = :tenantId AND v.id = :versionId
                        """)
                .param("tenantId", tenantId)
                .param("versionId", versionId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such template version"));
        if (!"SMS".equals(channel)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only an SMS wording waits on a gateway's approval");
        }

        Instant now = clock.instant();
        jdbc.sql("""
                        UPDATE notifications.template_versions
                           SET provider_review = :state, provider_review_reference = :reference,
                               provider_review_note = :note, provider_review_updated_by = :by,
                               provider_review_updated_at = :now, updated_at = :now
                         WHERE tenant_id = :tenantId AND id = :versionId
                        """)
                .param("state", state)
                .param("reference", reference)
                .param("note", note)
                .param("by", actor.subject() == null ? "" : actor.subject())
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("tenantId", tenantId)
                .param("versionId", versionId)
                .update();

        Map<String, Object> change = new HashMap<>();
        change.put("providerReview", state);
        if (reference != null) {
            change.put("reference", reference);
        }
        audit.record(AuditFact.of("notifications.template.provider_review_recorded", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("NotificationTemplateVersion", versionId)
                .because(reason)
                .changed(change)
                .usingCapability(Capability.NOTIFICATION_TEMPLATE_ACTIVATE.code())
                .correlatedBy(versionId.toString())
                .occurredAt(now)
                .build());
    }

    /** One SMS wording and where it stands with its provider. */
    public record ReviewRow(
            UUID versionId,
            UUID tenantId,
            String tenantName,
            String templateKey,
            int versionNumber,
            String locale,
            String status,
            String body,
            String providerReview,
            @Nullable String reference,
            @Nullable String providerNote,
            @Nullable String updatedBy,
            @Nullable Instant updatedAt) {}
}
