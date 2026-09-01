package uz.horecaos.platform.conversations.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A channel identity, as the adapter that authenticated the update already
 * knows it — never resolved inside this module from raw provider data. The
 * adapter (integration) is where a Telegram chat id comes from; this module
 * only ever receives one.
 *
 * @param tenantId the tenant the inbound update authenticated as
 * @param brandId which brand's bot this is — resolved by the adapter from an
 *                existing binding first, falling back to the installation's
 *                own configured brand (ADR 0058's bot-per-brand topology,
 *                still not a schema fact end to end)
 * @param installationId the provider installation this channel identity talks
 *                       through, carried so an outbound send can resolve its
 *                       own credentials without a second lookup
 * @param channel which channel this identity lives on
 * @param externalChatId the channel's own chat identifier (a Telegram chat
 *                       id); opaque to this module beyond identity and
 *                       equality
 * @param customerAccountId the linked customer account, when the adapter
 *                          already knows of one (ADR 0058's handshake); null
 *                          for a chat that has never linked
 */
public record ConversationChannelRef(
        UUID tenantId,
        UUID brandId,
        UUID installationId,
        ChannelKind channel,
        long externalChatId,
        @Nullable UUID customerAccountId) {}
