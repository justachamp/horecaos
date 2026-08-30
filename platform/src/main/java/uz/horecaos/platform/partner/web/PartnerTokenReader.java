package uz.horecaos.platform.partner.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Reads the client identity out of a client-credentials access token
 * (ADR 0040, ADR 0003).
 *
 * <p>Separate from {@code JwtCurrentActor}, which builds the actor a person's
 * token describes — a subject, organization memberships, and the roles inside
 * them. A client-credentials token has none of those. Its subject is the
 * service-account principal Keycloak invents, its organization claim is absent,
 * and the only identity worth anything on it is {@code azp}: the client the
 * token was issued to.
 *
 * <p>Reading {@code azp} in preference to {@code client_id} because {@code azp}
 * is the claim the OpenID Connect core specification defines for the authorized
 * party, and Keycloak emits both. Falling back keeps this working against an
 * issuer that emits only the other.
 *
 * <p>A token that carries no client identity at all is refused rather than
 * treated as anonymous. The partner surface has no anonymous mode: every path on
 * it names a tenant's order book.
 */
@Component
public class PartnerTokenReader {

    public String clientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            throw unauthenticated();
        }
        Jwt jwt = token.getToken();
        String authorizedParty = claim(jwt, "azp");
        if (authorizedParty != null) {
            return authorizedParty;
        }
        String clientId = claim(jwt, "client_id");
        if (clientId != null) {
            return clientId;
        }
        throw unauthenticated();
    }

    private static String claim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED,
                "A partner client credential is required");
    }
}
