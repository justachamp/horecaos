package uz.horecaos.platform.integration.provider.voice;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * ADR 0064's idempotent receipt of a VOICE provider event, mirroring {@code
 * TelegramUpdateDedupStore} exactly. Used by both adapter kinds: a hosted
 * PBX's webhook retry and an Asterisk AMI reconnect replay are the same
 * failure mode from this table's point of view.
 */
@Repository
public class VoiceProcessedEventStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public VoiceProcessedEventStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records this event as processed, if it is new.
     *
     * @return true the first time this {@code (installationId, providerEventId)}
     *         pair is seen — the caller should ingest it; false on a
     *         redelivery — the caller must not ingest it again
     */
    public boolean recordIfNew(UUID tenantId, UUID installationId, String providerEventId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                INSERT INTO integration.voice_processed_events (tenant_id, installation_id, provider_event_id, processed_at)
                VALUES (:tenantId, :installationId, :providerEventId, :now)
                ON CONFLICT (tenant_id, installation_id, provider_event_id) DO NOTHING
                """)
                        .param("tenantId", tenantId)
                        .param("installationId", installationId)
                        .param("providerEventId", providerEventId)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
