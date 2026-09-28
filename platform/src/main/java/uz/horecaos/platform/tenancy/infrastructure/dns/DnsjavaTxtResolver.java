package uz.horecaos.platform.tenancy.infrastructure.dns;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

/**
 * Resolves TXT records over real DNS using dnsjava, a pure-Java client with
 * no native code and no libc resolver binding.
 *
 * <p>Not the JDK's own JNDI DNS service provider
 * ({@code com.sun.jndi.dns.DnsContextFactory}): this build's error-prone
 * configuration refuses any JNDI naming lookup ({@code BanJNDI}) as a
 * deserialization surface, the same reasoning that keeps JNDI out of the
 * rest of this codebase. dnsjava is the small dependency ADR 0135's own
 * sibling infrastructure decisions favour over hand-rolling a resolver (see
 * {@code java-uuid-generator}'s own doc in this module's {@code pom.xml} for
 * the same "maintained library over bespoke bit-twiddling" reasoning) — no
 * shelling out to {@code dig}/{@code nslookup}, and no transitive dependency
 * beyond {@code slf4j-api}, already on this application's classpath via
 * Spring Boot's own logging.
 *
 * <p>This is the only implementation this module ships; every test wires
 * {@code DnsTxtResolver} to a fake instead of this class, per its own doc.
 */
@Component
public class DnsjavaTxtResolver implements DnsTxtResolver {

    private static final Logger log = LoggerFactory.getLogger(DnsjavaTxtResolver.class);

    private final Duration timeout;
    private final String nameServer;

    public DnsjavaTxtResolver(
            @Value("${horecaos.tenancy.channel-hostname.dns.timeout-seconds:3}") int timeoutSeconds,
            @Value("${horecaos.tenancy.channel-hostname.dns.name-server:}") String nameServer) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.nameServer = nameServer;
    }

    @Override
    public List<String> resolveTxt(String name) {
        try {
            SimpleResolver resolver = nameServer.isBlank() ? new SimpleResolver() : new SimpleResolver(nameServer);
            resolver.setTimeout(timeout);
            Lookup lookup = new Lookup(name, Type.TXT);
            lookup.setResolver(resolver);
            Record[] records = lookup.run();
            if (records == null) {
                // NXDOMAIN, a timeout, SERVFAIL, or any other resolution
                // failure -- dnsjava reports these through Lookup#getResult
                // rather than an exception, and a caller proving ownership
                // treats "could not confirm" and "confirmed absent"
                // identically (see this interface's own doc).
                return List.of();
            }
            return List.copyOf(Arrays.stream(records)
                    .filter(TXTRecord.class::isInstance)
                    .map(TXTRecord.class::cast)
                    .flatMap(txt -> txt.getStrings().stream())
                    .toList());
        } catch (IOException | RuntimeException resolutionFailure) {
            // IOException covers both UnknownHostException (no usable
            // resolver address could be determined -- a malformed
            // horecaos.tenancy.channel-hostname.dns.name-server override, or
            // no system resolver configured at all) and TextParseException
            // (name is not a syntactically valid DNS name). Every other
            // resolver failure lands here too -- logged at debug because an
            // operator's DNS not yet propagating is the expected shape of a
            // retry loop, not an incident.
            log.debug("TXT lookup for {} did not resolve: {}", name, resolutionFailure.toString());
            return List.of();
        }
    }
}
