package uz.horecaos.platform.tenancy.application.onboarding;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Reads versioned ADR 0008 onboarding templates (Gap B of the 2026-08-30
 * proving run).
 *
 * <p>Read-only, deliberately: authoring and versioning a template is a
 * platform-release decision — every field it carries is either code-owned
 * reference data ({@code required_steps} mirrors the {@code OnboardingStep}
 * enum) or, for {@code default_configuration}, a document whose shape is
 * decided per key by the module that applies it ({@code
 * OnboardingStepHandlers.DefaultConfigurationApply} for {@code
 * acceptancePolicy}). An authoring surface is out of scope here and stays
 * out until a second template genuinely needs to exist; V0098 seeds the one
 * template a v1 deployment needs.
 */
@Service
public class OnboardingTemplateService {

    private static final String SELECT = """
            SELECT id, code, version, status, description, required_steps::text AS required_steps,
                   default_configuration::text AS default_configuration, created_by, created_at
              FROM tenant.onboarding_templates
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OnboardingTemplateService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Every template version, newest first within each code. */
    public List<TemplateView> list() {
        return jdbc.sql(SELECT + " ORDER BY code, version DESC")
                .query(this::map)
                .list();
    }

    public TemplateView get(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Unknown onboarding template"));
    }

    /**
     * The template a run uses when nothing is specified: the newest {@code
     * ACTIVE} version of the platform's {@code default} template — the row
     * V0098 seeds. Onboarding creation had no fallback before that migration
     * and refused every call with a foreign-key violation on a fresh
     * deployment; this is what a caller omitting {@code templateId} now
     * resolves to instead.
     */
    public TemplateView currentDefault() {
        return jdbc.sql(SELECT + " WHERE code = 'default' AND status = 'ACTIVE' ORDER BY version DESC LIMIT 1")
                .query(this::map)
                .optional()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "No default onboarding template is configured; this deployment's migrations are behind"));
    }

    @SuppressWarnings("unchecked")
    private TemplateView map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        List<String> requiredSteps = objectMapper.readValue(rs.getString("required_steps"), List.class);
        Map<String, Object> defaultConfiguration =
                objectMapper.readValue(rs.getString("default_configuration"), Map.class);
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new TemplateView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getString("description"),
                requiredSteps,
                defaultConfiguration,
                rs.getString("created_by"),
                createdAt == null ? null : createdAt.toInstant());
    }

    public record TemplateView(
            UUID id,
            String code,
            int version,
            String status,
            String description,
            List<String> requiredSteps,
            Map<String, Object> defaultConfiguration,
            String createdBy,
            @Nullable Instant createdAt) {}
}
