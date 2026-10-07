package uz.horecaos.platform.integration.camel.einvoicing;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link EgressAllowlist} over {@code integration.provider_environments.egress_allowlist}. */
@Repository
public class JdbcEgressAllowlist implements EgressAllowlist {

    private final JdbcClient jdbc;

    public JdbcEgressAllowlist(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Set<String>> hostsOf(String environmentCode) {
        return jdbc.sql("SELECT egress_allowlist FROM integration.provider_environments WHERE code = :code")
                .param("code", environmentCode)
                .query(String.class)
                .optional()
                .map(list -> Arrays.stream(list.split(","))
                        .map(host -> host.strip().toLowerCase(Locale.ROOT))
                        .filter(host -> !host.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
