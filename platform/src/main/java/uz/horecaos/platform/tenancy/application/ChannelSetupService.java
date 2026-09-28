package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
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
import uz.horecaos.platform.tenancy.domain.channel.HostnameChallenge;
import uz.horecaos.platform.tenancy.domain.channel.HostnameChallenges;
import uz.horecaos.platform.tenancy.domain.channel.ReservedSubdomains;
import uz.horecaos.platform.tenancy.infrastructure.dns.DnsTxtResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcChannelSetupStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

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
 * <p><strong>The DNS-TXT challenge.</strong> settings.md 10.5's WEB section:
 * "Domain verification is DNS TXT, not credential handover." A custom
 * hostname gets a fresh {@link HostnameChallenge} the moment it is claimed
 * ({@link #setCustomHostname}); {@link #verifyCustomHostname} resolves that
 * challenge's TXT record over real DNS ({@link DnsTxtResolver}) and only
 * flips {@code verified} on a match; {@link #rotateChallenge} issues a fresh
 * token on request, un-verifying the hostname in the same write since the
 * old proof no longer matches what the console now shows. The kiosk device
 * registry is the one thing left on row 10.5 needing an owner decision —
 * re-deciding it is out of scope here.
 */
@Service
public class ChannelSetupService {

    private static final int MAX_SEO_TITLE = 200;
    private static final int MAX_SEO_DESCRIPTION = 500;

    private final JdbcChannelSetupStore store;
    private final SalesChannelService channels;
    private final Clock clock;
    private final DnsTxtResolver dnsResolver;
    private final String baseDomain;

    public ChannelSetupService(
            JdbcChannelSetupStore store,
            SalesChannelService channels,
            Clock clock,
            DnsTxtResolver dnsResolver,
            @Value("${horecaos.storefront.base-domain:stores.horecaos.uz}") String baseDomain) {
        this.store = store;
        this.channels = channels;
        this.clock = clock;
        this.dnsResolver = dnsResolver;
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
     * Resolves the channel's active DNS-TXT challenge over real DNS and
     * flips {@code verified} true only on a match.
     *
     * <p>Refuses with {@link TenantResourceNotFoundException} when this
     * channel has no active challenge — no hostname claimed, or a
     * platform-issued subdomain, which is verified immediately at claim time
     * and never gets a challenge to resolve ({@link #setSubdomain}).
     * Refuses with {@link ErrorCode#UNPROCESSABLE_STATE} when the challenge
     * record does not (yet) carry the issued token: the request was
     * well-formed and named a real, pending challenge, but the DNS state it
     * depends on refuses it — exactly {@link ErrorCode#UNPROCESSABLE_STATE}'s
     * own contract, not a 400 telling the operator to fix a request that was
     * never wrong.
     */
    @Transactional
    public ChannelHostname verifyCustomHostname(UUID tenantId, UUID channelId, int expectedVersion) {
        channels.require(tenantId, channelId);
        HostnameChallenge challenge = store.challengeFor(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "This channel has no active DNS challenge to verify — claim a custom hostname first"));
        if (!recordCarriesToken(challenge)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    ("The DNS TXT record %s does not yet carry the issued challenge — publish it, allow "
                                    + "DNS to propagate, and try again")
                            .formatted(challenge.recordName()));
        }
        if (!store.markVerified(tenantId, channelId, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        return store.hostnameFor(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException("This channel has no hostname"));
    }

    /**
     * The channel's active DNS-TXT challenge, for the console to show with a
     * copy action. Empty for a channel with no custom hostname claimed —
     * including one that has claimed a platform-issued subdomain, which
     * never gets a challenge in the first place.
     */
    @Transactional(readOnly = true)
    public Optional<HostnameChallenge> challenge(UUID tenantId, UUID channelId) {
        channels.require(tenantId, channelId);
        return store.challengeFor(tenantId, channelId);
    }

    /**
     * Issues a fresh challenge token for the channel's current custom
     * hostname, replacing whatever token it had. Un-verifies the hostname in
     * the same write ({@link JdbcChannelSetupStore#rotateChallenge}): the
     * previous DNS-TXT record, if still published, carries the *old* token,
     * which is no longer the challenge the console is showing, so it must
     * not keep resolving traffic on a proof that no longer applies.
     */
    @Transactional
    public HostnameChallenge rotateChallenge(UUID tenantId, UUID channelId, int expectedVersion) {
        channels.require(tenantId, channelId);
        ChannelHostname current = store.hostnameFor(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException("This channel has no hostname"));
        if (isPlatformIssued(current.hostname())) {
            throw new IllegalArgumentException(
                    "\"%s\" is a platform-issued subdomain and needs no DNS challenge".formatted(current.hostname()));
        }
        String token = HostnameChallenges.generateToken();
        Instant now = clock.instant();
        if (!store.rotateChallenge(tenantId, channelId, token, expectedVersion, now)) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        return HostnameChallenge.of(tenantId, channelId, current.hostname(), token, now);
    }

    private boolean recordCarriesToken(HostnameChallenge challenge) {
        return dnsResolver.resolveTxt(challenge.recordName()).stream()
                .map(String::trim)
                .anyMatch(challenge.token()::equals);
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
        Instant now = clock.instant();
        // A platform-issued subdomain (verified=true) needs no challenge --
        // HorecaOS's own DNS already answers for it. A custom hostname
        // always gets a fresh one here, even if it is replacing an earlier
        // custom hostname's own token: the previous challenge named a
        // different hostname, so carrying its token forward would be
        // meaningless.
        String token = verified ? null : HostnameChallenges.generateToken();
        Instant issuedAt = verified ? null : now;
        try {
            if (!store.setHostname(tenantId, channelId, hostname, verified, token, issuedAt, expectedVersion, now)) {
                throw new TenantResourceConflictException("The channel changed since it was read");
            }
        } catch (DataIntegrityViolationException violation) {
            throw explainHostnameViolation(violation);
        }
        return new ChannelHostname(tenantId, channelId, hostname, verified, now);
    }

    /**
     * Whether {@code hostname} is (or is under) the platform's own base
     * domain — a subdomain claimed through {@link #setSubdomain}, verified
     * immediately and never given a DNS challenge — as opposed to a tenant's
     * own custom domain. Computed from the hostname string rather than
     * stored, matching {@link ChannelHostname}'s own doc on why that
     * distinction is deliberately not a column: it only ever matters at
     * write time, and now here, at challenge time.
     */
    private boolean isPlatformIssued(String hostname) {
        return hostname.equals(baseDomain) || hostname.endsWith("." + baseDomain);
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
        if (isPlatformIssued(normalized)) {
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
