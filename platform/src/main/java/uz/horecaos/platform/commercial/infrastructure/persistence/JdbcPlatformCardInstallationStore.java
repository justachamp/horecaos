package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.commercial.domain.PlatformCardInstallation;

/**
 * HorecaOS's own card merchant account (ADR 0095, ADR 0026, V0504).
 *
 * <p>The secret reference is selected, because the adapter resolves it at call
 * time, and it is the only place in the application that reads the column. It is
 * never returned by a controller.
 */
@Repository
public class JdbcPlatformCardInstallationStore {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, provider_type, environment_code, display_name, status, secret_reference,
            external_account_reference, non_sensitive_config::text AS non_sensitive_config,
            last_connection_check_at, last_connection_status, created_by, activated_by,
            version, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPlatformCardInstallationStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(PlatformCardInstallation installation) {
        jdbc.sql("""
                        INSERT INTO commercial.platform_card_installations (
                            id, provider_type, environment_code, display_name, status, secret_reference,
                            external_account_reference, non_sensitive_config, created_by, version,
                            created_at, updated_at)
                        VALUES (
                            :id, :providerType, :environmentCode, :displayName, :status, :secretReference,
                            :externalAccountReference, CAST(:config AS jsonb), :createdBy, 0, :now, :now)
                        """)
                .param("id", installation.id())
                .param("providerType", installation.providerType())
                .param("environmentCode", installation.environmentCode())
                .param("displayName", installation.displayName())
                .param("status", installation.status())
                .param("secretReference", installation.secretReference())
                .param("externalAccountReference", installation.externalAccountReference())
                .param("config", objectMapper.writeValueAsString(installation.nonSensitiveConfig()))
                .param("createdBy", installation.createdBy())
                .param("now", utc(installation.createdAt()))
                .update();
    }

    public Optional<PlatformCardInstallation> findActive() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM commercial.platform_card_installations WHERE status = 'ACTIVE'")
                .query(this::read)
                .optional();
    }

    public Optional<PlatformCardInstallation> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM commercial.platform_card_installations WHERE id = :id")
                .param("id", id)
                .query(this::read)
                .optional();
    }

    public List<PlatformCardInstallation> list() {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM commercial.platform_card_installations ORDER BY created_at DESC, id DESC")
                .query(this::read)
                .list();
    }

    /**
     * Moves one installation to a status, only from the version the caller read.
     *
     * @return false when the version moved on or another installation is already ACTIVE
     */
    public boolean transition(UUID id, long expectedVersion, String status, @Nullable String activatedBy, Instant now) {
        try {
            return jdbc.sql("""
                            UPDATE commercial.platform_card_installations
                               SET status = :status, activated_by = COALESCE(:activatedBy, activated_by),
                                   version = version + 1, updated_at = :now
                             WHERE id = :id AND version = :expectedVersion
                            """)
                            .param("id", id)
                            .param("expectedVersion", expectedVersion)
                            .param("status", status)
                            .param("activatedBy", activatedBy)
                            .param("now", utc(now))
                            .update()
                    == 1;
        } catch (org.springframework.dao.DuplicateKeyException anotherIsActive) {
            return false;
        }
    }

    /** Whether the ADR 0026 catalogue approves this environment for a payment provider of this type. */
    public boolean approvedEnvironmentExists(String environmentCode, String providerType) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM integration.provider_environments
                                        WHERE code = :code AND provider_category = 'PAYMENT'
                                          AND provider_type = :type)
                        """)
                .param("code", environmentCode)
                .param("type", providerType)
                .query(Boolean.class)
                .single();
    }

    private PlatformCardInstallation read(ResultSet row, int number) throws SQLException {
        return new PlatformCardInstallation(
                row.getObject("id", UUID.class),
                row.getString("provider_type"),
                row.getString("environment_code"),
                row.getString("display_name"),
                row.getString("status"),
                row.getString("secret_reference"),
                row.getString("external_account_reference"),
                objectMapper.readValue(row.getString("non_sensitive_config"), JSON_OBJECT),
                instant(row, "last_connection_check_at"),
                row.getString("last_connection_status"),
                row.getString("created_by"),
                row.getString("activated_by"),
                row.getLong("version"),
                java.util.Objects.requireNonNull(instant(row, "created_at")),
                java.util.Objects.requireNonNull(instant(row, "updated_at")));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
