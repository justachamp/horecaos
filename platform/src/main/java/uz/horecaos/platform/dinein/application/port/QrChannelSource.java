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

    /**
     * The verified hostname a printed table card should send a phone to
     * (ADR 0047, ADR 0036, gap map row {@code 10.5}): the address the
     * storefront answers on, so a scan opens {@code
     * https://<hostname>/dine-in/<token>} and not a bare string a camera cannot
     * follow.
     *
     * <p>Two tiers, each requiring exactly one candidate, never a guess:
     * the tenant's own {@code QR_TABLE} channel's verified hostname first, then
     * the verified hostname of the tenant's one active {@code WEB} channel --
     * the console only lets a {@code WEB} channel claim a hostname, so for most
     * tenants the storefront the guest lands on is that one. Two candidates at a
     * tier are ambiguous (which brand's site?) and answer as if there were none.
     *
     * <p>Verified only: an unverified custom domain is one whose DNS the tenant
     * has not proven, and printing it on a card puts a guest's phone on a host
     * nobody has confirmed answers. A platform-issued subdomain is verified the
     * moment it is claimed and so qualifies.
     *
     * <p>Defaulted to empty so the hand-written {@code QrChannelSource} lambdas
     * that predate the printed card need no implementation of a read the QR
     * exchange never makes.
     *
     * @return empty when no hostname qualifies -- the caller then prints the bare
     *         token and says so, rather than inventing an address
     */
    default Optional<String> storefrontHostname(UUID tenantId) {
        return Optional.empty();
    }
}
