package uz.qoida.platform.tenancy.infrastructure.persistence;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.qoida.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * Fails startup when the database holds a configuration key that code does not
 * declare (ADR 0030).
 *
 * <p>Without this, a renamed or removed key would leave rows that silently stop
 * applying: the resolver would fall through to a default and nobody would learn
 * that a tenant's configured value had become inert. Failing at startup makes
 * that a deployment error instead of a support mystery.
 */
@Component
public class ConfigurationKeyStartupValidator implements ApplicationRunner {

    private final JdbcClient jdbc;

    public ConfigurationKeyStartupValidator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> unknown = jdbc.sql("""
                SELECT DISTINCT key_code FROM tenant.configuration_values
                """)
                .query(String.class)
                .list()
                .stream()
                .filter(code -> ConfigurationKeys.find(code).isEmpty())
                .sorted()
                .toList();

        if (!unknown.isEmpty()) {
            throw new ConfigurationKeys.UnknownConfigurationKeyException(
                    """
                    Stored configuration keys have no code-owned declaration: %s.
                    Either restore the declaration in ConfigurationKeys or migrate the rows away (ADR 0030)."""
                            .formatted(unknown));
        }
    }
}
