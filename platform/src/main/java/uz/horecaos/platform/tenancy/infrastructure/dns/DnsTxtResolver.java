package uz.horecaos.platform.tenancy.infrastructure.dns;

import java.util.List;

/**
 * Resolves a DNS name's TXT record set, for row 10.5's ownership challenge
 * ({@code ChannelSetupService#verifyCustomHostname},
 * {@code ChannelHostnameVerificationSweeper}).
 *
 * <p>A port, not an adapter: production wires {@link DnsjavaTxtResolver}
 * (real DNS, no shelling out, a small pure-Java dependency in place of the
 * JDK's own JNDI DNS provider -- this build's error-prone config refuses
 * JNDI outright), and every test wires a fake -- this platform's own
 * convention against ever letting a test reach the real network.
 */
public interface DnsTxtResolver {

    /**
     * The TXT record values published at {@code name}, one entry per string
     * DNS returned, with any enclosing quotes already stripped.
     *
     * <p>Returns an empty list for "no such record" and equally for any
     * resolution failure (NXDOMAIN, timeout, SERVFAIL, an unreachable
     * resolver) -- a caller proving ownership treats "could not confirm" and
     * "confirmed absent" identically: neither one flips {@code verified}.
     */
    List<String> resolveTxt(String name);
}
