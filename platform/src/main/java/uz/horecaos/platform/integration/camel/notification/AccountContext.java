package uz.horecaos.platform.integration.camel.notification;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The non-secret half of the account a call is made as (ADR 0026, ADR 0146).
 *
 * <p>An adapter holds no configuration, so what a gateway needs beside its
 * credential arrives here for the length of one call. It is the installation's
 * {@code non_sensitive_config} with the binding's override laid over it (ADR
 * 0030, narrower wins), and it never holds a secret: the credential travels on
 * {@code ProviderCall} and is resolved at call time.
 */
public record AccountContext(Map<String, String> configuration) {

    /** No configuration at all, which is what a gateway needing none is given. */
    public static final AccountContext EMPTY = new AccountContext(Map.of());

    public AccountContext {
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }

    public @Nullable String value(String key) {
        String value = configuration.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    /** Keys only. A value is a login or a sender, which a log has no need of. */
    @Override
    public String toString() {
        return "AccountContext" + configuration.keySet();
    }
}
