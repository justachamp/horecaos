package uz.horecaos.platform.dinein.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The one fact a QR admission needs from the channel registry (ADR 0036): the
 * code of the tenant's own {@code QR_TABLE} channel.
 *
 * <p>The storefront's menu and cart reads take a channel by tenant-chosen
 * <em>code</em> ({@code SalesChannelLookup#byCode}), never a system type, and a
 * scanning guest has no session of their own to have picked one from — the
 * table token names a location, not a channel. This is the one read that lets
 * {@link uz.horecaos.platform.dinein.application.QrEntryService#exchange} hand
 * the code back so a guest's browser can fetch the right menu and price plane
 * without the storefront ever having to guess it.
 *
 * <p>A read, and only a read, in the same shape {@code SessionOrderSource}
 * already set for {@code ordering}: a module that needs one fact from another
 * module's table takes it through a port and keeps no copy, rather than
 * importing that module's services and making the two mutually aware.
 */
public interface QrChannelSource {

    /**
     * The tenant's single active {@code QR_TABLE} channel, by code.
     *
     * @return empty when the tenant has registered zero or more than one --
     *         mirroring {@code SalesChannelLookup#hallChannelId}'s own rule
     *         that neither state names an unambiguous channel, so a caller
     *         falls back to "no code" rather than guessing
     */
    Optional<String> qrTableChannelCode(UUID tenantId);
}
