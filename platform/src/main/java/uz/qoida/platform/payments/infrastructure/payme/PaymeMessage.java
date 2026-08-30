package uz.qoida.platform.payments.infrastructure.payme;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Payme's error {@code message}, which is an object and not a string (ADR 0013).
 *
 * <p>The docs make the {@code {ru, uz, en}} object mandatory only for the
 * account-error range {@code -31050…-31099}, and Payme's PHP template passes a
 * bare string everywhere else. This adapter emits the object for every error, on
 * the reasoning the provider notes give: it is valid everywhere, it removes the
 * question, and the alternative is a Russian-speaking payer reading an English
 * sentence — which is precisely what Payme's own Java template does, since it can
 * only carry a single {@code @JsonRpcError(message = "...")} string.
 *
 * <p>{@code ru} is the one that actually surfaces, because the Payme checkout
 * defaults to {@code lang=ru}. All three are filled anyway.
 */
public record PaymeMessage(String ru, String uz, String en) {

    public PaymeMessage {
        Objects.requireNonNull(ru, "A Russian message is required: it is the one the payer reads");
        Objects.requireNonNull(uz, "An Uzbek message is required");
        Objects.requireNonNull(en, "An English message is required");
    }

    /**
     * The wire form.
     *
     * <p>A {@link LinkedHashMap} so the key order is stable across responses.
     * Payme does not care, but a stable body makes the {@code request_body_hash}
     * style of diffing usable when an integration argument has to be settled from
     * captured traffic.
     */
    public Map<String, String> asMap() {
        Map<String, String> localised = new LinkedHashMap<>();
        localised.put("ru", ru);
        localised.put("uz", uz);
        localised.put("en", en);
        return localised;
    }
}
