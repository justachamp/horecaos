package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The edit-vs-send lifecycle (ADR 0058): while a tracked message is under
 * Telegram's ~48-hour edit window and still present, a further update to the
 * same concern edits it in place; otherwise a new message is sent and tracked.
 */
@Repository
public class TelegramMessageTracker {

    /** Telegram's own documented edit window for a text message. */
    public static final Duration EDIT_WINDOW = Duration.ofHours(48);

    private final JdbcClient jdbc;
    private final Clock clock;

    public TelegramMessageTracker(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The live tracked message for this exact concern, if the edit window has not closed. */
    public Optional<Tracked> current(
            UUID tenantId, UUID bindingId, String subjectType, UUID subjectId, String templateKey) {
        return jdbc.sql("""
                SELECT id, telegram_message_id, content_hash, edit_window_expires_at
                FROM integration.telegram_tracked_messages
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND subject_type = :subjectType
                  AND subject_id = :subjectId AND template_key = :templateKey AND superseded_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("templateKey", templateKey)
                .query((row, number) -> new Tracked(
                        row.getObject("id", UUID.class),
                        row.getLong("telegram_message_id"),
                        row.getString("content_hash"),
                        row.getObject("edit_window_expires_at", OffsetDateTime.class)
                                .toInstant()))
                .optional()
                .filter(tracked -> tracked.editWindowExpiresAt().isAfter(clock.instant()));
    }

    /** Records a freshly sent message as the new current one for this concern, superseding any prior row. */
    public void recordSent(
            UUID tenantId,
            UUID bindingId,
            String subjectType,
            UUID subjectId,
            String templateKey,
            long telegramMessageId,
            String contentHash) {
        Instant now = clock.instant();

        jdbc.sql("""
                UPDATE integration.telegram_tracked_messages
                SET superseded_at = :now
                WHERE tenant_id = :tenantId AND binding_id = :bindingId AND subject_type = :subjectType
                  AND subject_id = :subjectId AND template_key = :templateKey AND superseded_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("templateKey", templateKey)
                .param("now", utc(now))
                .update();

        jdbc.sql("""
                INSERT INTO integration.telegram_tracked_messages (
                    id, tenant_id, binding_id, subject_type, subject_id, template_key,
                    telegram_message_id, content_hash, sent_at, edit_window_expires_at, created_at, updated_at)
                VALUES (:id, :tenantId, :bindingId, :subjectType, :subjectId, :templateKey,
                    :messageId, :hash, :now, :editExpires, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("templateKey", templateKey)
                .param("messageId", telegramMessageId)
                .param("hash", contentHash)
                .param("now", utc(now))
                .param("editExpires", utc(now.plus(EDIT_WINDOW)))
                .update();
    }

    /** Records a successful in-place edit of the currently tracked message. */
    public void recordEdited(UUID tenantId, UUID trackedMessageId, String contentHash) {
        jdbc.sql("""
                UPDATE integration.telegram_tracked_messages
                SET content_hash = :hash, last_edited_at = :now, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", trackedMessageId)
                .param("hash", contentHash)
                .param("now", utc(clock.instant()))
                .update();
    }

    /** An edit failed for a reason that means the tracked message itself is gone; the next update sends fresh. */
    public void supersede(UUID tenantId, UUID trackedMessageId) {
        jdbc.sql("""
                UPDATE integration.telegram_tracked_messages
                SET superseded_at = :now, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND superseded_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("id", trackedMessageId)
                .param("now", utc(clock.instant()))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record Tracked(UUID id, long telegramMessageId, String contentHash, Instant editWindowExpiresAt) {}
}
