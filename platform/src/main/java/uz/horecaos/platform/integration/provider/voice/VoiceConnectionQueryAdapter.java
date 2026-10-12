package uz.horecaos.platform.integration.provider.voice;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.voice.api.VoiceConnectionQueryPort;

/**
 * ADR 0175: whether a branch has a live telephony provider, over the ADR 0026 installation and
 * binding rows. An installation counts when it is {@code ACTIVE} and one of its bindings is {@code
 * ACTIVE} at the location; a draft installation, a suspended one, or one bound to another branch does
 * not.
 */
@Repository
public class VoiceConnectionQueryAdapter implements VoiceConnectionQueryPort {

    private final JdbcClient jdbc;

    public VoiceConnectionQueryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int activeInstallationCount(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT count(DISTINCT i.id)
                  FROM integration.installations i
                  JOIN integration.bindings b ON b.tenant_id = i.tenant_id AND b.installation_id = i.id
                 WHERE i.tenant_id = :tenantId
                   AND i.provider_category = 'VOICE'
                   AND i.status = 'ACTIVE'
                   AND b.status = 'ACTIVE'
                   AND b.location_id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(Integer.class)
                .single();
    }
}
