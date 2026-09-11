package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Template and template-version persistence (ADR 0020).
 *
 * <p>The reads are small and separate rather than one wide join, for the reason
 * {@code JdbcServiceabilityStore} gives: the caller has to be able to tell "this
 * tenant has no template for confirmations" from "it has one, but not in the
 * language this customer reads". A join returns the same empty result for both,
 * and the tenant would be shown a suppression reason that is not true.
 *
 * <p>Resolution prefers a brand's own wording over the tenant's default. ADR 0020
 * also names a platform default beneath those two; it does not exist here, because
 * a platform row would be a NULL-tenant row in a tenant-scoped table and the first
 * slice does not need one.
 */
@Repository
public class JdbcTemplateStore {

    private final JdbcClient jdbc;

    public JdbcTemplateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The template that applies here, brand override first.
     *
     * <p>{@code ORDER BY brand_id NULLS LAST} is the whole precedence rule. The
     * brand predicate is an OR rather than two queries so the choice is made by
     * one statement, and a brand id belonging to another tenant still matches
     * nothing because the tenant predicate is applied first.
     */
    public Optional<TemplateRow> activeTemplate(UUID tenantId, UUID brandId, String templateKey, String channel) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("key", templateKey);
        parameters.put("channel", channel);

        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, template_key, notification_class, channel,
                       consent_purpose, status, active_version, version
                FROM notifications.templates
                WHERE tenant_id = :tenantId AND template_key = :key AND channel = :channel
                  AND status = 'ACTIVE'
                  AND (brand_id = :brandId OR brand_id IS NULL)
                ORDER BY brand_id NULLS LAST
                LIMIT 1
                """)
                .params(parameters)
                .query(JdbcTemplateStore::templateRow)
                .optional();
    }

    public Optional<TemplateRow> template(UUID tenantId, UUID templateId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, template_key, notification_class, channel,
                       consent_purpose, status, active_version, version
                FROM notifications.templates
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", templateId)
                .query(JdbcTemplateStore::templateRow)
                .optional();
    }

    public List<TemplateRow> templatesForBrand(UUID tenantId, UUID brandId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);

        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, template_key, notification_class, channel,
                       consent_purpose, status, active_version, version
                FROM notifications.templates
                WHERE tenant_id = :tenantId AND (brand_id = :brandId OR brand_id IS NULL)
                ORDER BY template_key, channel, brand_id NULLS LAST
                """)
                .params(parameters)
                .query(JdbcTemplateStore::templateRow)
                .list();
    }

    /** One locale of one version. The row a message is actually rendered from. */
    public Optional<VersionRow> version(UUID tenantId, UUID templateId, int versionNumber, String locale) {
        return jdbc.sql("""
                SELECT id, tenant_id, template_id, version_number, locale, subject_template,
                       body_template, variables_schema::text AS variables_schema, content_hash,
                       status, approved_by, activated_at, provider_review
                FROM notifications.template_versions
                WHERE tenant_id = :tenantId AND template_id = :templateId
                  AND version_number = :versionNumber AND locale = :locale
                """)
                .param("tenantId", tenantId)
                .param("templateId", templateId)
                .param("versionNumber", versionNumber)
                .param("locale", locale)
                .query(JdbcTemplateStore::versionRow)
                .optional();
    }

    public List<VersionRow> versions(UUID tenantId, UUID templateId, int versionNumber) {
        return jdbc.sql("""
                SELECT id, tenant_id, template_id, version_number, locale, subject_template,
                       body_template, variables_schema::text AS variables_schema, content_hash,
                       status, approved_by, activated_at, provider_review
                FROM notifications.template_versions
                WHERE tenant_id = :tenantId AND template_id = :templateId
                  AND version_number = :versionNumber
                ORDER BY locale
                """)
                .param("tenantId", tenantId)
                .param("templateId", templateId)
                .param("versionNumber", versionNumber)
                .query(JdbcTemplateStore::versionRow)
                .list();
    }

    /**
     * The next draft number for a template.
     *
     * <p>One statement rather than a read followed by a write. Two authors saving
     * a draft at once would otherwise both see the same last value and collide on
     * the unique index, and the loser would lose their wording.
     */
    public int nextVersionNumber(UUID tenantId, UUID templateId) {
        return jdbc.sql("""
                SELECT coalesce(max(version_number), 0) + 1
                FROM notifications.template_versions
                WHERE tenant_id = :tenantId AND template_id = :templateId
                """)
                .param("tenantId", tenantId)
                .param("templateId", templateId)
                .query(Integer.class)
                .single();
    }

    // ----------------------------------------------------------------- writes

    public void insertTemplate(
            UUID id,
            UUID tenantId,
            @Nullable UUID brandId,
            String templateKey,
            String notificationClass,
            String channel,
            @Nullable String consentPurpose,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("key", templateKey);
        parameters.put("class", notificationClass);
        parameters.put("channel", channel);
        parameters.put("purpose", consentPurpose);
        parameters.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO notifications.templates (
                    id, tenant_id, brand_id, template_key, notification_class, channel,
                    consent_purpose, status, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :key, :class, :channel,
                    :purpose, 'DRAFT', :now, :now)
                """).params(parameters).update();
    }

    public void insertVersion(
            UUID id,
            UUID tenantId,
            UUID templateId,
            int versionNumber,
            String locale,
            @Nullable String subjectTemplate,
            String bodyTemplate,
            String variablesSchemaJson,
            String contentHash,
            Instant now) {
        insertVersion(
                id,
                tenantId,
                templateId,
                versionNumber,
                locale,
                subjectTemplate,
                bodyTemplate,
                variablesSchemaJson,
                contentHash,
                false,
                now);
    }

    /**
     * Inserts one draft locale of a version.
     *
     * @param awaitsGateway whether the version starts {@code PENDING} its SMS
     *        gateway's approval (ADR 0091) rather than {@code NOT_REQUIRED};
     *        attributed to the platform, because no person marked it
     */
    public void insertVersion(
            UUID id,
            UUID tenantId,
            UUID templateId,
            int versionNumber,
            String locale,
            @Nullable String subjectTemplate,
            String bodyTemplate,
            String variablesSchemaJson,
            String contentHash,
            boolean awaitsGateway,
            Instant now) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", tenantId);
        parameters.put("templateId", templateId);
        parameters.put("versionNumber", versionNumber);
        parameters.put("locale", locale);
        parameters.put("subject", subjectTemplate);
        parameters.put("body", bodyTemplate);
        parameters.put("schema", variablesSchemaJson);
        parameters.put("hash", contentHash);
        parameters.put("now", utc(now));
        parameters.put("review", awaitsGateway ? "PENDING" : "NOT_REQUIRED");
        parameters.put("reviewedBy", awaitsGateway ? GATEWAY_MODERATION : null);
        parameters.put("reviewedAt", awaitsGateway ? utc(now) : null);
        parameters.put(
                "reviewNote", awaitsGateway ? "The SMS gateway moderates wordings; awaiting its approval" : null);

        jdbc.sql("""
                INSERT INTO notifications.template_versions (
                    id, tenant_id, template_id, version_number, locale, subject_template,
                    body_template, variables_schema, content_hash, status, created_at, updated_at,
                    provider_review, provider_review_updated_by, provider_review_updated_at,
                    provider_review_note)
                VALUES (:id, :tenantId, :templateId, :versionNumber, :locale, :subject,
                    :body, CAST(:schema AS jsonb), :hash, 'DRAFT', :now, :now,
                    :review, :reviewedBy, :reviewedAt, :reviewNote)
                """).params(parameters).update();
    }

    /** Who a version marked as awaiting its gateway at creation was marked by: the platform's own rule. */
    public static final String GATEWAY_MODERATION = "platform:gateway-moderation";

    /**
     * Whether a new SMS wording of this tenant waits for its gateway's approval
     * (ADR 0091, decided 2026-09-11).
     *
     * <p>When the tenant has SMS bindings, it waits if any of them sits on an
     * endpoint that moderates wordings. When it has none yet, the gateway it
     * will use is unknown, so it waits if any approved notification endpoint
     * moderates. The installations and endpoints are the integration module's,
     * read by name for the reason this module reads other schemas by name:
     * the rule needs two columns, not that module's types.
     */
    public boolean smsWordingAwaitsGateway(UUID tenantId) {
        return Boolean.TRUE.equals(
                jdbc.sql("""
                        WITH sms_endpoints AS (
                            SELECT DISTINCT i.environment_code
                              FROM integration.installations i
                              JOIN integration.bindings b
                                ON b.tenant_id = i.tenant_id AND b.installation_id = i.id
                              JOIN integration.binding_capabilities c
                                ON c.tenant_id = b.tenant_id AND c.binding_id = b.id
                             WHERE i.tenant_id = :tenantId
                               AND c.capability_code = 'SEND_SMS'
                               AND c.enabled
                               AND b.status <> 'SUSPENDED'
                        )
                        SELECT CASE
                                 WHEN EXISTS (SELECT 1 FROM sms_endpoints) THEN EXISTS (
                                     SELECT 1
                                       FROM integration.provider_environments e
                                       JOIN sms_endpoints s ON s.environment_code = e.code
                                      WHERE e.moderates_wordings)
                                 ELSE EXISTS (
                                     SELECT 1
                                       FROM integration.provider_environments e
                                      WHERE e.provider_category = 'NOTIFICATION' AND e.moderates_wordings)
                               END
                        """).param("tenantId", tenantId).query(Boolean.class).single());
    }

    /**
     * Activates every locale of one version and points the template at it.
     *
     * <p>Two statements, one transaction, and both conditional. The version update
     * names the number it expects and the template update names the version it
     * read, so two operators activating different versions in the same instant
     * produce one winner rather than a template whose {@code active_version} points
     * at rows that were never activated.
     *
     * @return how many locale rows were activated. The caller refuses the whole
     *         activation unless this is the full locale set
     */
    public int activateVersion(UUID tenantId, UUID templateId, int versionNumber, String approvedBy, Instant now) {
        jdbc.sql("""
                UPDATE notifications.template_versions
                SET status = 'SUPERSEDED', updated_at = :now
                WHERE tenant_id = :tenantId AND template_id = :templateId
                  AND version_number <> :versionNumber AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("templateId", templateId)
                .param("versionNumber", versionNumber)
                .param("now", utc(now))
                .update();

        return jdbc.sql("""
                UPDATE notifications.template_versions
                SET status = 'ACTIVE', approved_by = :approvedBy, activated_at = :now,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND template_id = :templateId
                  AND version_number = :versionNumber AND status = 'DRAFT'
                """)
                .param("tenantId", tenantId)
                .param("templateId", templateId)
                .param("versionNumber", versionNumber)
                .param("approvedBy", approvedBy)
                .param("now", utc(now))
                .update();
    }

    /** Points the template at the activated version. Conditional on the version read. */
    public boolean markTemplateActive(
            UUID tenantId, UUID templateId, int versionNumber, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.templates
                SET status = 'ACTIVE', active_version = :versionNumber,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("tenantId", tenantId)
                        .param("id", templateId)
                        .param("versionNumber", versionNumber)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    // ------------------------------------------------------------------- rows

    private static TemplateRow templateRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new TemplateRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("template_key"),
                row.getString("notification_class"),
                row.getString("channel"),
                row.getString("consent_purpose"),
                row.getString("status"),
                // getInt answers 0 for SQL NULL, and 0 is a version number this
                // would then try to resolve. A template awaiting its first
                // activation is exactly this case.
                row.getObject("active_version", Integer.class),
                row.getInt("version"));
    }

    private static VersionRow versionRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        OffsetDateTime activatedAt = row.getObject("activated_at", OffsetDateTime.class);
        return new VersionRow(
                row.getObject("id", UUID.class),
                row.getObject("template_id", UUID.class),
                row.getInt("version_number"),
                row.getString("locale"),
                row.getString("subject_template"),
                row.getString("body_template"),
                row.getString("variables_schema"),
                row.getString("content_hash"),
                row.getString("status"),
                row.getString("approved_by"),
                activatedAt == null ? null : activatedAt.toInstant(),
                row.getString("provider_review"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * A template, and which version of it is live.
     *
     * @param activeVersion null until a version is activated
     */
    public record TemplateRow(
            UUID id,
            UUID tenantId,
            @Nullable UUID brandId,
            String templateKey,
            String notificationClass,
            String channel,
            @Nullable String consentPurpose,
            String status,
            @Nullable Integer activeVersion,
            int version) {

        /** Whether this row is the tenant's default rather than a brand's override. */
        public boolean isTenantWide() {
            return brandId == null;
        }
    }

    public record VersionRow(
            UUID id,
            UUID templateId,
            int versionNumber,
            String locale,
            @Nullable String subjectTemplate,
            String bodyTemplate,
            String variablesSchemaJson,
            String contentHash,
            String status,
            @Nullable String approvedBy,
            @Nullable Instant activatedAt,
            String providerReview) {}
}
