package uz.horecaos.platform.integration.camel.cardbilling;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A card provider's login read out of the one secret behind an account's reference (ADR 0028, ADR
 * 0095): a JSON object of the provider's own credential fields, which an operator of this platform puts
 * there with {@code bao kv put}.
 *
 * <p>Parsed inside the request function the gateway invokes with the live credential, so the text
 * exists only for one attempt. A missing field throws {@link IllegalStateException} naming the
 * <em>field</em> and never a value, and the gateway drops the message anyway.
 */
public final class CardBillingCredentials {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final Map<String, Object> fields;

    private CardBillingCredentials(Map<String, Object> fields) {
        this.fields = fields;
    }

    public static CardBillingCredentials parse(ObjectMapper mapper, String secret) {
        return new CardBillingCredentials(mapper.readValue(secret, MAP));
    }

    public String required(String field) {
        Object value = fields.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalStateException("The account's credential has no " + field);
    }

    public @Nullable String optional(String field) {
        return fields.get(field) instanceof String text && !text.isBlank() ? text : null;
    }

    /** Never prints a field. */
    @Override
    public String toString() {
        return "CardBillingCredentials[fields=" + fields.keySet() + "]";
    }
}
