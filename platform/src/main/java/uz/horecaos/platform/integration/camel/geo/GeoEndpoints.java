package uz.horecaos.platform.integration.camel.geo;

import java.util.Optional;

/**
 * Where a provider's approved endpoints are (ADR 0026): the base URL for an
 * {@code integration.provider_environments} code, or nothing.
 *
 * <p>An adapter names an environment code and never a URL, which is where the
 * server-side-request-forgery path is closed — at the model, not in a validator somebody can
 * forget. The consequence, recorded in V0061 for the SMS gateway and true here too, is that an
 * adapter without an approved row is an adapter nothing can be configured to reach.
 */
public interface GeoEndpoints {

    Optional<String> baseUrlOf(String environmentCode);
}
