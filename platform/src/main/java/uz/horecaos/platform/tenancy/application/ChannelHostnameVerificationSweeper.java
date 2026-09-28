package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.tenancy.domain.channel.HostnameChallenge;
import uz.horecaos.platform.tenancy.infrastructure.dns.DnsTxtResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcChannelSetupStore;

/**
 * Row 10.5: periodically re-resolves every verified custom hostname's
 * DNS-TXT challenge and un-verifies it the moment the record no longer
 * matches.
 *
 * <p>Verification is not a one-time event. A tenant can delete the TXT
 * record the day after it passes (DNS housekeeping, a provider migration, a
 * lapsed domain) and nothing about the original {@code POST .../verify} call
 * would ever notice — the storefront hostname resolution
 * ({@code ChannelSetupService#resolveHostname}) would keep serving that
 * hostname to whoever the tenant no longer controls it, on the strength of a
 * check that is now stale. This sweep is what keeps {@code verified}
 * meaning "still true", not just "was true once".
 *
 * <p>Deliberately not gated on {@code expectedVersion}: nobody has read this
 * channel expecting to hear back from this sweep, the same reasoning {@code
 * JdbcChannelSetupStore#unverify}'s own doc gives. It still bumps the
 * channel's shared version, so a console that has this channel open finds
 * out on its own next write.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.tenancy.channel-hostname.recheck.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChannelHostnameVerificationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ChannelHostnameVerificationSweeper.class);

    private final JdbcChannelSetupStore store;
    private final DnsTxtResolver dnsResolver;
    private final Clock clock;

    public ChannelHostnameVerificationSweeper(JdbcChannelSetupStore store, DnsTxtResolver dnsResolver, Clock clock) {
        this.store = store;
        this.dnsResolver = dnsResolver;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${horecaos.tenancy.channel-hostname.recheck.initial-delay:PT5M}",
            fixedDelayString = "${horecaos.tenancy.channel-hostname.recheck.interval:PT30M}")
    public void recheck() {
        int unverified = runOnce();
        if (unverified > 0) {
            // A count only -- a hostname is not personal data, but it is
            // still tenant-identifying, so it stays out of a log line
            // nothing needs it in (ADR 0029).
            log.info(
                    "Channel hostname re-check: un-verified {} custom hostname(s) whose DNS-TXT "
                            + "challenge no longer matches",
                    unverified);
        }
    }

    /** Runs one pass synchronously and returns how many hostnames it un-verified — the shape a test drives directly, never through {@link #recheck}'s own timer. */
    public int runOnce() {
        List<HostnameChallenge> verified = store.verifiedCustomHostnames();
        int unverifiedCount = 0;
        for (HostnameChallenge challenge : verified) {
            boolean stillMatches = dnsResolver.resolveTxt(challenge.recordName()).stream()
                    .map(String::trim)
                    .anyMatch(challenge.token()::equals);
            if (!stillMatches) {
                store.unverify(challenge.tenantId(), challenge.channelId(), clock.instant());
                unverifiedCount++;
            }
        }
        return unverifiedCount;
    }
}
