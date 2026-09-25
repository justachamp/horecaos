package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
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
import tools.jackson.databind.ObjectMapper;

/**
 * {@code marketing.automation_rules} (gap-map row 6.5, V0414).
 *
 * <p>The tenant predicate is inside every query, including the ones that
 * already name a primary key — the same discipline {@link JdbcAudienceStore}'s
 * own doc states, for the same reason: an id that arrives from a client must
 * never resolve into another tenant's row.
 */
@Repository
public class JdbcAutomationRuleStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAutomationRuleStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(NewRule rule) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", rule.id());
        parameters.put("tenantId", rule.tenantId());
        parameters.put("brandId", rule.brandId());
        parameters.put("name", rule.name());
        parameters.put("triggerType", rule.triggerType());
        parameters.put("channel", rule.channel());
        parameters.put("consentPurpose", rule.consentPurpose());
        parameters.put("templateKey", rule.templateKey());
        parameters.put("triggerConfig", objectMapper.writeValueAsString(rule.triggerConfig()));
        parameters.put("cooldownDays", rule.cooldownDays());
        parameters.put("priority", rule.priority());
        parameters.put("createdBy", rule.createdBy());
        parameters.put("now", utc(rule.createdAt()));

        jdbc.sql("""
                INSERT INTO marketing.automation_rules (
                    id, tenant_id, brand_id, name, trigger_type, channel, consent_purpose,
                    template_key, trigger_config, cooldown_days, priority,
                    active, created_by, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :brandId, :name, :triggerType, :channel, :consentPurpose,
                    :templateKey, CAST(:triggerConfig AS jsonb), :cooldownDays, :priority,
                    false, :createdBy, :now, :now)
                """).params(parameters).update();
    }

    public Optional<AutomationRuleRow> find(UUID tenantId, UUID ruleId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", ruleId)
                .query(this::map)
                .optional();
    }

    public List<AutomationRuleRow> listByBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND brand_id = :brandId ORDER BY priority, name")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(this::map)
                .list();
    }

    /** Every active rule of one trigger kind, across every brand — a sweep's own candidate set. */
    public List<AutomationRuleRow> activeByTriggerType(String triggerType, int limit) {
        return jdbc.sql(SELECT
                        + " WHERE active = true AND trigger_type = :triggerType ORDER BY priority, name LIMIT :limit")
                .param("triggerType", triggerType)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /**
     * Arms the rule. {@code ck_automation_rule_human_armed} backstops this at the
     * schema level; this is where a human's identity and moment are actually
     * recorded.
     *
     * @return false when the version was stale — an optimistic-concurrency
     *         refusal, the same contract every other versioned write in this
     *         module gives
     */
    public boolean activate(UUID tenantId, UUID ruleId, int expectedVersion, UUID activatedBy, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.automation_rules
                   SET active = true, activated_by = :activatedBy, activated_at = :now,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("tenantId", tenantId)
                        .param("id", ruleId)
                        .param("expectedVersion", expectedVersion)
                        .param("activatedBy", activatedBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public boolean deactivate(UUID tenantId, UUID ruleId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.automation_rules
                   SET active = false, activated_by = NULL, activated_at = NULL,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("tenantId", tenantId)
                        .param("id", ruleId)
                        .param("expectedVersion", expectedVersion)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Rewrites a rule's authoring fields. Refused (by the caller, before this is
     * reached) while {@code active} — a marketer edits a rule by deactivating it
     * first, the same "stop, then change" posture {@code CampaignService} takes
     * with a running campaign.
     */
    public boolean update(
            UUID tenantId,
            UUID ruleId,
            int expectedVersion,
            String name,
            String channel,
            String consentPurpose,
            String templateKey,
            String triggerConfigJson,
            int cooldownDays,
            Instant now) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("id", ruleId);
        parameters.put("expectedVersion", expectedVersion);
        parameters.put("name", name);
        parameters.put("channel", channel);
        parameters.put("consentPurpose", consentPurpose);
        parameters.put("templateKey", templateKey);
        parameters.put("triggerConfig", triggerConfigJson);
        parameters.put("cooldownDays", cooldownDays);
        parameters.put("now", utc(now));

        return jdbc.sql("""
                UPDATE marketing.automation_rules
                   SET name = :name, channel = :channel, consent_purpose = :consentPurpose,
                       template_key = :templateKey,
                       trigger_config = CAST(:triggerConfig AS jsonb), cooldown_days = :cooldownDays,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """).params(parameters).update() == 1;
    }

    /**
     * Rewrites every named rule's priority in one statement (q-rule-list's own
     * whole-set contract, the same one {@code OrderOutcomeReasonController
     * .reorder} already gives its sibling screen). A rule id absent from
     * {@code orderedIds} keeps its existing priority.
     *
     * @return how many rows were touched, for the caller to compare against
     *         {@code orderedIds.size()}
     */
    public int reorder(UUID tenantId, UUID brandId, List<UUID> orderedIds, Instant now) {
        int touched = 0;
        for (int index = 0; index < orderedIds.size(); index++) {
            touched += jdbc.sql("""
                    UPDATE marketing.automation_rules
                       SET priority = :priority, version = version + 1, updated_at = :now
                     WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                    """)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("id", orderedIds.get(index))
                    .param("priority", index)
                    .param("now", utc(now))
                    .update();
        }
        return touched;
    }

    private static final String SELECT = """
            SELECT id, tenant_id, brand_id, name, trigger_type, channel, consent_purpose,
                   template_key, trigger_config, cooldown_days, priority, active,
                   created_by, activated_by, activated_at, version, created_at, updated_at
              FROM marketing.automation_rules
            """;

    private AutomationRuleRow map(ResultSet row, int number) throws java.sql.SQLException {
        return new AutomationRuleRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("name"),
                row.getString("trigger_type"),
                row.getString("channel"),
                row.getString("consent_purpose"),
                row.getString("template_key"),
                objectMapper.readValue(row.getString("trigger_config"), java.util.Map.class),
                row.getInt("cooldown_days"),
                row.getInt("priority"),
                row.getBoolean("active"),
                row.getObject("created_by", UUID.class),
                row.getObject("activated_by", UUID.class),
                instant(row.getObject("activated_at", OffsetDateTime.class)),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record NewRule(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String name,
            String triggerType,
            String channel,
            String consentPurpose,
            String templateKey,
            Map<String, Integer> triggerConfig,
            int cooldownDays,
            int priority,
            UUID createdBy,
            Instant createdAt) {}

    @SuppressWarnings("unchecked")
    public record AutomationRuleRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String name,
            String triggerType,
            String channel,
            String consentPurpose,
            String templateKey,
            Map<String, Object> triggerConfig,
            int cooldownDays,
            int priority,
            boolean active,
            UUID createdBy,
            @Nullable UUID activatedBy,
            @Nullable Instant activatedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        /** The one numeric threshold this rule's own {@link #triggerType()} requires. */
        public int configValue() {
            String key = uz.horecaos.platform.marketing.domain.AutomationTriggerType.valueOf(triggerType)
                    .configKey();
            Object value = triggerConfig.get(key);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("Automation rule's trigger_config has no numeric " + key);
            }
            return number.intValue();
        }
    }
}
