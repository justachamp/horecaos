package uz.horecaos.platform.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import uz.horecaos.platform.tenancy.infrastructure.dns.DnsTxtResolver;

/**
 * A {@link DnsTxtResolver} test double that answers only what a test has
 * explicitly published, so row 10.5's DNS-TXT verification is exercised
 * without ever reaching the real network -- this platform's own rule for
 * every test that would otherwise touch DNS.
 *
 * <p>Answers an empty list, the same as "no such record", for any name a
 * test has not called {@link #publish} for.
 */
public final class FakeDnsTxtResolver implements DnsTxtResolver {

    private final Map<String, List<String>> recordsByName = new ConcurrentHashMap<>();

    /** Publishes {@code values} as the TXT record set at {@code name}, replacing whatever was there. */
    public void publish(String name, String... values) {
        recordsByName.put(name, List.of(values));
    }

    /** Removes {@code name}'s record entirely -- the scheduled re-check's own fixture, simulating a withdrawn TXT record. */
    public void withdraw(String name) {
        recordsByName.remove(name);
    }

    /** Drops every published record -- for a shared Spring context's {@code @BeforeEach}, so one test's fixture cannot leak into the next. */
    public void reset() {
        recordsByName.clear();
    }

    @Override
    public List<String> resolveTxt(String name) {
        return recordsByName.getOrDefault(name, List.of());
    }
}
