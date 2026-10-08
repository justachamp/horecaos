package uz.horecaos.platform.storefrontapps.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Which storefront app a request came from, once the platform has checked it
 * (ADR 0070).
 *
 * <p>Set as a request attribute by the identity check so a later step -- an order
 * recording its provenance, a quota -- reads the answer instead of repeating the
 * lookup. It names the app and nothing about a customer.
 *
 * @param appId      the registered app's id
 * @param name       the app's registered name, for a log line or a response
 * @param clientType whether the app is a browser-only public client or a confidential one
 * @param authenticated true only when a confidential client's secret was verified; a
 *                   public client is attributed, never authenticated
 */
public record StorefrontAppIdentity(
        UUID appId, String name, StorefrontAppClientType clientType, boolean authenticated) {

    /** The request attribute under which the checked identity is stored. */
    public static final String REQUEST_ATTRIBUTE = StorefrontAppIdentity.class.getName();

    public StorefrontAppIdentity {
        Objects.requireNonNull(appId, "An app id is required");
        Objects.requireNonNull(name, "An app name is required");
        Objects.requireNonNull(clientType, "A client type is required");
    }
}
