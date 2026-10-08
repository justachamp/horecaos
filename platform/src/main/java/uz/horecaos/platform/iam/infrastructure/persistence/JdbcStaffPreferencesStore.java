package uz.horecaos.platform.iam.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code iam.staff_preferences} (ADR 0160, V0531): one protected document per tenant, subject and
 * surface.
 *
 * <p>Every statement names the tenant <em>and</em> the subject, and there is no method that reads by
 * either alone: the row belongs to exactly one person of exactly one tenant, and a query that forgot the
 * subject would hand one colleague's layout to another. Nothing here decrypts or reads inside a document
 * -- the column is opaque ciphertext and the service opens it with the identity the ciphertext is bound
 * to.
 */
@Repository
public class JdbcStaffPreferencesStore {

    private static final String COLUMNS = """
            id, tenant_id, principal_subject, surface, protected_document,
            document_schema, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public JdbcStaffPreferencesStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PreferenceRow> find(UUID tenantId, String subject, String surface) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM iam.staff_preferences
                 WHERE tenant_id = :tenantId AND principal_subject = :subject AND surface = :surface
                """)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("surface", surface)
                .query(JdbcStaffPreferencesStore::mapRow)
                .optional();
    }

    public List<PreferenceRow> findAll(UUID tenantId, String subject) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM iam.staff_preferences
                 WHERE tenant_id = :tenantId AND principal_subject = :subject
                 ORDER BY surface
                """)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .query(JdbcStaffPreferencesStore::mapRow)
                .list();
    }

    public int count(UUID tenantId, String subject) {
        Integer count = jdbc.sql("""
                SELECT count(*) FROM iam.staff_preferences
                 WHERE tenant_id = :tenantId AND principal_subject = :subject
                """)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .query(Integer.class)
                .single();
        return count;
    }

    /**
     * Writes the person's first document for a surface. {@code false} when one already exists -- two devices
     * both saving their first choice -- which the caller answers as a stale version.
     */
    public boolean insertIfAbsent(PreferenceRow row) {
        return jdbc.sql("""
                        INSERT INTO iam.staff_preferences (
                            id, tenant_id, principal_subject, surface, protected_document,
                            document_schema, version, created_at, updated_at)
                        VALUES (:id, :tenantId, :subject, :surface, :document, :schema, :version, :now, :now)
                        ON CONFLICT (tenant_id, principal_subject, surface) DO NOTHING
                        """)
                        .param("id", row.id())
                        .param("tenantId", row.tenantId())
                        .param("subject", row.principalSubject())
                        .param("surface", row.surface())
                        .param("document", row.protectedDocument())
                        .param("schema", (short) row.documentSchema())
                        .param("version", row.version())
                        .param("now", at(row.createdAt()))
                        .update()
                == 1;
    }

    /** Replaces the document if the stored version is still {@code expectedVersion}; {@code false} otherwise. */
    public boolean update(
            UUID tenantId,
            String subject,
            String surface,
            String protectedDocument,
            int documentSchema,
            int expectedVersion,
            Instant now) {
        return jdbc.sql("""
                        UPDATE iam.staff_preferences
                           SET protected_document = :document, document_schema = :schema,
                               version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND principal_subject = :subject AND surface = :surface
                           AND version = :expectedVersion
                        """)
                        .param("document", protectedDocument)
                        .param("schema", (short) documentSchema)
                        .param("now", at(now))
                        .param("tenantId", tenantId)
                        .param("subject", subject)
                        .param("surface", surface)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /** Resets one surface to the default. {@code false} when the person had nothing stored for it. */
    public boolean delete(UUID tenantId, String subject, String surface) {
        return jdbc.sql("""
                        DELETE FROM iam.staff_preferences
                         WHERE tenant_id = :tenantId AND principal_subject = :subject AND surface = :surface
                        """)
                        .param("tenantId", tenantId)
                        .param("subject", subject)
                        .param("surface", surface)
                        .update()
                > 0;
    }

    /** Every document of one person, for the anonymisation of their member row. */
    public int deleteAllOf(UUID tenantId, String subject) {
        return jdbc.sql("""
                DELETE FROM iam.staff_preferences
                 WHERE tenant_id = :tenantId AND principal_subject = :subject
                """)
                .param("tenantId", tenantId)
                .param("subject", subject)
                .update();
    }

    /** The brand a branch of this tenant belongs to, or empty when the tenant has no such branch. */
    public Optional<UUID> brandOfLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql("SELECT brand_id FROM tenant.locations WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", locationId)
                .query(UUID.class)
                .optional();
    }

    private static PreferenceRow mapRow(ResultSet row, int number) throws SQLException {
        return new PreferenceRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("principal_subject"),
                row.getString("surface"),
                row.getString("protected_document"),
                row.getInt("document_schema"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** One stored document. The text is ciphertext; the row never prints it. */
    public record PreferenceRow(
            UUID id,
            UUID tenantId,
            String principalSubject,
            String surface,
            String protectedDocument,
            int documentSchema,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        @Override
        public String toString() {
            return "PreferenceRow[surface=" + surface + ", version=" + version + "]";
        }
    }
}
