package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads a TELEGRAM channel's own settings (ADR 0183 D27) for the modules that act on them: {@code integration} sets the
 * bot's menu button and answers {@code /start} with the welcome text, and {@code marketing} reads the Mini App short name
 * when it renders a link.
 *
 * <p>Every method takes the tenant id and every implementation puts it in the query, as every other tenancy port does: a
 * channel id is a UUID a caller may have received from anywhere.
 */
public interface TelegramChannelSettingsDirectory {

    /** The channel's settings; empty when the channel is not this tenant's or is not a TELEGRAM channel. */
    Optional<TelegramChannelSettings> forChannel(UUID tenantId, UUID channelId);

    /**
     * The settings of the one active TELEGRAM channel an ADR 0026 installation backs. Empty for none and for more than
     * one: two channels on one bot name no unambiguous settings, and a welcome text that guessed would greet a customer
     * in the wrong channel's voice.
     */
    Optional<TelegramChannelSettings> forInstallation(UUID tenantId, UUID installationId);
}
