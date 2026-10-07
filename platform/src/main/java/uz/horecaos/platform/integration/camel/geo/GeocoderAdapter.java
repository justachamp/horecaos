package uz.horecaos.platform.integration.camel.geo;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;

/**
 * One map provider's server-side half (ADR 0145 decision 1): everything vendor-specific about
 * geocoding, behind a contract that names no vendor.
 *
 * <p>An adapter does <em>not</em> resolve its own credential, refresh it, break its own circuit
 * or apply the region's box; the gateway does all four once for every adapter, so a second
 * provider cannot forget one of them. What is left for the adapter is the wire: build a
 * request, classify the answer into a {@link ProviderOutcome}, and normalize it. The payload of
 * a successful outcome is carried under {@link #PAYLOAD_KEY}: a {@code List<GeoSuggestion>} for
 * a suggest, a {@code List<GeocodeResult>} for a geocode and a {@code List<GeocodeResult>} of at
 * most one for a reverse.
 *
 * <p>Raw provider JSON never leaves an implementation of this interface.
 */
public interface GeocoderAdapter {

    /** The key a successful outcome's normalized map carries its payload under. */
    String PAYLOAD_KEY = "payload";

    /** {@code YANDEX}, {@code FAKE}: matches {@code horecaos.geo.provider}, case-insensitively. */
    String provider();

    /**
     * Whether this adapter can answer at all: a key is configured, a fixture is loaded. Asked
     * before any network round trip so an unconfigured environment spends neither a call nor a
     * log line finding out.
     */
    boolean configured();

    /** The ADR 0028 reference of the server key, or null for an adapter that needs none. */
    @Nullable
    SecretReference credentialReference();

    /** What the browser is told about this provider. */
    MapClientConfig clientConfig();

    /**
     * Performs the lookup.
     *
     * @param credential the server key, already read from the secret manager; null when
     *                   {@link #credentialReference()} is null
     * @param timeout    the deadline for the whole exchange
     */
    ProviderOutcome execute(GeoOperation operation, @Nullable String credential, Duration timeout);
}
