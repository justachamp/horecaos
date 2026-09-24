package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.tenancy.domain.channel.ChannelHostname;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPresentation;
import uz.horecaos.platform.tenancy.domain.channel.ReservedSubdomains;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcChannelSetupStore;

/**
 * Row 10.5's channel setup hub: a channel's hostname and SEO presentation
 * (migrations V0403/V0404).
 *
 * <p><strong>What this deliberately leaves alone.</strong> The storefront
 * (frontend/storefront) still resolves its tenant/brand/channel entirely from
 * its own {@code /config.json} at bootstrap — see {@code load-config.ts}'s
 * own doc. Nothing here touches that path. What this adds is the record an
 * edge layer generating or choosing a deployment's {@code config.json} from
 * an incoming hostname would read — {@link #resolveHostname} is that read,
 * exposed publicly by {@code StorefrontChannelHostnameController} — and the
 * console screen an operator uses to set the mapping in the first place.
 *
 * <p><strong>What this does not build.</strong> The DNS-TXT
 * challenge-and-poll verification flow settings.md 10.5's WEB section
 * describes (issue a token, show it, poll DNS, flip the status) is real
 * infrastructure work this wave does not do — see this class's own {@link
 * #verifyCustomHostname} doc. That, and the kiosk device registry, are the
 * two things the operations gap map already names as needing an owner
 * decision on row 10.5, and re-deciding them is out of scope here.
 */
@Service
public class ChannelSetupService {

    private static final int MAX_SEO_TITLE = 200;
    private static final int MAX_SEO_DESCRIPTION = 500;

    private final JdbcChannelSetupStore store;
    private final SalesChannelService channels;
    private final Clock clock;
    private final String baseDomain;

    public ChannelSetupService(
            JdbcChannelSetupStore store,
            SalesChannelService channels,
            Clock clock,
            @Value("${horecaos.storefront.base-domain:stores.horecaos.uz}") String baseDomain) {
        this.store = store;
        this.channels = channels;
        this.clock = clock;
        this.baseDomain = baseDomain;
    }

    /** The base domain a platform-issued subdomain is composed under, for the console to show alongside the slug field. */
    public String baseDomain() {
        return baseDomain;
    }

    @Transactional(readOnly = true)
    public Optional<ChannelHostname> hostname(UUID tenantId, UUID channelId) {
        channels.require(tenantId, channelId);
        return store.hostnameFor(tenantId, channelId);
    }

    /**
     * The public read: what channel (if any) answers on this hostname, for an
     * edge caller that has nothing but a {@code Host} header — no tenant id,
     * because discovering the tenant is the whole point of the call.
     *
     * <p>Only a {@link ChannelHostname#verified()} row resolves. An
     * unverified custom domain is one the platform has not confirmed the
     * tenant controls, so serving traffic for it before that confirmation
     * would let anyone claim a hostname by typing it into a form.
     */
    @Transactional(readOnly = true)
    public Optional<ChannelHostname> resolveHostname(String hostname) {
        return store.byHostname(normalizedHostname(hostname)).filter(ChannelHostname::verified);
    }

    /**
     * Claims a platform-issued subdomain: {@code slug} becomes {@code
     * <slug>.<baseDomain>}. Verified immediately — the platform's own DNS
     * already answers for its own base domain, so there is nothing to prove.
     */
    @Transactional
    public ChannelHostname setSubdomain(UUID tenantId, UUID channelId, String slug, int expectedVersion) {
        channels.require(tenantId, channelId);
        String normalizedSlug = requireValidSlug(slug);
        String hostname = normalizedSlug + "." + baseDomain;
        return write(tenantId, channelId, hostname, true, expectedVersion);
    }

    /**
     * Claims a tenant's own custom domain. Stored unverified — see {@link
     * #verifyCustomHostname}.
     */
    @Transactional
    public ChannelHostname setCustomHostname(UUID tenantId, UUID channelId, String hostname, int expectedVersion) {
        channels.require(tenantId, channelId);
        String normalized = requireValidHostname(hostname);
        return write(tenantId, channelId, normalized, false, expectedVersion);
    }

    /**
     * Marks the channel's current hostname verified.
     *
     * <p>This is the flag alone, set by a capability-gated operator action
     * once they have confirmed ownership by whatever means — today, outside
     * this application. It is not the DNS-TXT challenge settings.md 10.5
     * describes: this wave stores where that flag lives and lets it be set,
     * it does not issue a token, poll a resolver, or prove anything on its
     * own. Building that checker is the gap map's own open item, not
     * re-decided here.
     */
    @Transactional
    public ChannelHostname verifyCustomHostname(UUID tenantId, UUID channelId, int expectedVersion) {
        channels.require(tenantId, channelId);
        if (!store.markVerified(tenantId, channelId, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        return store.hostnameFor(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException("This channel has no hostname"));
    }

    @Transactional
    public void clearHostname(UUID tenantId, UUID channelId, int expectedVersion) {
        channels.require(tenantId, channelId);
        if (!store.clearHostname(tenantId, channelId, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
    }

    private ChannelHostname write(
            UUID tenantId, UUID channelId, String hostname, boolean verified, int expectedVersion) {
        java.time.Instant now = clock.instant();
        try {
            if (!store.setHostname(tenantId, channelId, hostname, verified, expectedVersion, now)) {
                throw new TenantResourceConflictException("The channel changed since it was read");
            }
        } catch (DataIntegrityViolationException violation) {
            throw explainHostnameViolation(violation);
        }
        return new ChannelHostname(tenantId, channelId, hostname, verified, now);
    }

    // --------------------------------------------------------- presentation

    @Transactional(readOnly = true)
    public ChannelPresentation presentation(UUID tenantId, UUID channelId) {
        channels.require(tenantId, channelId);
        return store.presentationFor(tenantId, channelId);
    }

    @Transactional
    public ChannelPresentation setPresentation(
            UUID tenantId,
            UUID channelId,
            @Nullable String seoTitle,
            @Nullable String seoDescription,
            @Nullable UUID ogImageAssetId,
            int expectedVersion) {

        channels.require(tenantId, channelId);
        String normalizedTitle = trimmedOrNull(seoTitle);
        String normalizedDescription = trimmedOrNull(seoDescription);
        if (normalizedTitle != null && normalizedTitle.length() > MAX_SEO_TITLE) {
            throw new IllegalArgumentException("SEO title exceeds " + MAX_SEO_TITLE + " characters");
        }
        if (normalizedDescription != null && normalizedDescription.length() > MAX_SEO_DESCRIPTION) {
            throw new IllegalArgumentException("SEO description exceeds " + MAX_SEO_DESCRIPTION + " characters");
        }
        if (!store.setPresentation(
                tenantId,
                channelId,
                normalizedTitle,
                normalizedDescription,
                ogImageAssetId,
                expectedVersion,
                clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        return new ChannelPresentation(tenantId, channelId, normalizedTitle, normalizedDescription, ogImageAssetId);
    }

    // -------------------------------------------------------------- shared

    private static @Nullable String trimmedOrNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String requireValidSlug(String slug) {
        if (slug == null) {
            throw new IllegalArgumentException("A subdomain slug is required");
        }
        String normalized = slug.strip().toLowerCase(Locale.ROOT);
        if (!ReservedSubdomains.isWellFormed(normalized)) {
            throw new IllegalArgumentException(
                    "\"%s\" must be 1-63 lowercase letters, digits, or internal hyphens".formatted(slug));
        }
        if (ReservedSubdomains.isReserved(normalized)) {
            throw new IllegalArgumentException(
                    "\"%s\" is a reserved subdomain and cannot be claimed".formatted(normalized));
        }
        return normalized;
    }

    private static final Pattern HOSTNAME_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private String requireValidHostname(String hostname) {
        String normalized = normalizedHostname(hostname);
        if (normalized.isEmpty() || normalized.length() > 253) {
            throw new IllegalArgumentException("A hostname must be 1-253 characters");
        }
        String[] labels = normalized.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException(
                    "\"%s\" is not a full domain — use the subdomain field for a platform-issued address"
                            .formatted(hostname));
        }
        for (String label : labels) {
            if (!HOSTNAME_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("\"%s\" is not a valid hostname".formatted(hostname));
            }
        }
        if (normalized.equals(baseDomain) || normalized.endsWith("." + baseDomain)) {
            throw new IllegalArgumentException(
                    "\"%s\" is under the platform's own domain — use the subdomain field instead".formatted(hostname));
        }
        return normalized;
    }

    private static String normalizedHostname(String hostname) {
        if (hostname == null) {
            throw new IllegalArgumentException("A hostname is required");
        }
        return hostname.strip().toLowerCase(Locale.ROOT);
    }

    private static RuntimeException explainHostnameViolation(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("uq_channel_hostname")) {
            return new TenantResourceConflictException("That hostname is already claimed by another channel");
        }
        return violation;
    }
}
