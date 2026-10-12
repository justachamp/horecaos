package uz.horecaos.platform.voice.api;

import java.util.UUID;

/**
 * Whether a branch has a live telephony provider (ADR 0175), for the Telephony tab's three states.
 *
 * <p>Declared by the consumer ({@code reporting}'s call-stats read) and implemented by {@code
 * integration} over the same installation and binding tables {@code VoiceInstallationLookup} reads
 * (ADR 0026), because those tables belong to the integration schema and a reporting module that read
 * them directly would be reaching across a boundary this repository keeps shut.
 *
 * <p>Read-only and personal-data-free: it answers a count.
 */
public interface VoiceConnectionQueryPort {

    /**
     * The number of {@code VOICE} installations that are {@code ACTIVE} and hold an {@code ACTIVE}
     * binding at this location. Zero means no provider is connected to the branch.
     */
    int activeInstallationCount(UUID tenantId, UUID locationId);
}
