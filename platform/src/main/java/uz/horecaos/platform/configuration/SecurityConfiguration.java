package uz.horecaos.platform.configuration;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import uz.horecaos.platform.customers.infrastructure.security.CustomerSessionAuthenticationFilter;
import uz.horecaos.platform.customers.infrastructure.security.CustomerSessionBearerTokenResolver;
import uz.horecaos.platform.observability.LocalMetricsScrapeMatcher;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * ADR 0051: a customer signs in with a platform-issued session, not a realm
     * token, and the two arrive in the same header.
     *
     * <p>{@code customerSessions} runs before the resource server's bearer filter
     * and only ever looks at a value carrying the customer prefix; everything else
     * falls through untouched. {@code bearerTokenResolver} is the other half —
     * without it the resource server would grab a customer token first, hand it to
     * the JWT decoder, and answer a signed-in customer with a complaint about a
     * malformed token.
     */
    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            LocalMetricsScrapeMatcher localMetricsScrape,
            CustomerSessionAuthenticationFilter customerSessions,
            CustomerSessionBearerTokenResolver bearerTokenResolver)
            throws Exception {
        return http.addFilterBefore(customerSessions, BearerTokenAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // The container's internal forward to /error. Without
                        // this, any anonymous request that errors — a missing
                        // query parameter, a 500 — has its error rendering
                        // denied at the forward, and masquerades as an empty
                        // 401 instead of its real Problem Details; a plain 400
                        // cost half an hour of security archaeology before
                        // this line. The dispatcher-type matcher permits only
                        // the forward the container itself makes: a caller
                        // requesting /error from outside is still anyRequest()
                        // like everything else.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                // The pre-account browse surface, listed one path
                                // at a time rather than as /api/v1/storefront/**.
                                //
                                // The wildcard opened the whole storefront GET
                                // surface, including reads that do declare a
                                // capability — a cart, an order, a points balance.
                                // For those, permitAll means the filter chain never
                                // authenticates, so the capability interceptor was
                                // the only check standing, and
                                // horecaos.authorization.enforce=false switches that
                                // interceptor off wholesale. That flag is an
                                // authorization opt-out for re-measuring divergence;
                                // it must not be able to remove authentication as a
                                // side effect. Guarding these reads here instead of
                                // narrowing the flag keeps the two layers doing
                                // their own jobs, and leaves the opt-out usable for
                                // what it is for.
                                //
                                // A wildcard also fails in the wrong direction:
                                // every storefront GET added afterwards is open by
                                // default, silently, and nothing about adding a
                                // controller method says so. A list has to be
                                // extended on purpose.
                                //
                                // ADR 0016: the published menu is what a customer
                                // browses before they have an account, so it is
                                // deliberately open. It serves only immutable
                                // publication rows, never authoring tables, so
                                // there is no path from here to a draft.
                                // The branch discovery route has the same
                                // pre-account purpose. It returns only active,
                                // published storefront branches and their
                                // current pickup serviceability — not a tenant's
                                // general location directory.
                                "/api/v1/storefront/pickup-locations",
                                "/api/v1/storefront/tenants/*/brands/*/locations/*/menu",
                                // ADR 0010, ADR 0016: the pictures on that menu.
                                // Anonymous for the same reason the menu is -- a
                                // customer browses before they have an account, and
                                // an image behind a bearer token is a menu of broken
                                // images for exactly the people being won over.
                                //
                                // Listed on its own rather than folded into a media
                                // wildcard: MediaController's own reads are staff
                                // reads behind MEDIA_READ, and /media/** here would
                                // open them. The controller additionally serves only
                                // a PUBLIC, displayable asset and answers everything
                                // else not-found, so this line does not by itself
                                // decide what is readable.
                                "/api/v1/storefront/tenants/*/media/*",
                                // A brand's own help content. Anonymous for the
                                // plainest reason: somebody looking up delivery
                                // hours or hunting for the Telegram channel very
                                // often has no account yet, and a token would
                                // answer the question only for people who have
                                // stopped needing to ask it. Nothing here is
                                // personal data or per-customer.
                                "/api/v1/storefront/tenants/*/brands/*/support/faq",
                                "/api/v1/storefront/tenants/*/brands/*/support/social-links",
                                // ADR 0036 and its delivery-fee companion: the same
                                // customer, the same moment, before an account
                                // exists. "Can I order from here" and "what does
                                // delivery cost here" are read from the same
                                // published state the menu is, and neither writes.
                                "/api/v1/storefront/tenants/*/brands/*/locations/*/serviceability",
                                "/api/v1/storefront/tenants/*/brands/*/locations/*/delivery-fee",
                                // ADR 0047: the guest's own running bill. Outside
                                // the resource server's principal model on purpose
                                // — see the POST pair below — and authorised by the
                                // X-Dine-In-Token the handler resolves to a row.
                                "/api/v1/storefront/dine-in/sessions/*")
                        .permitAll()
                        // ADR 0023: the on-box probe evaluates every alert
                        // threshold from this scrape, and it cannot hold a bearer
                        // token — minting one needs Keycloak, and an alert path
                        // that depends on the identity provider goes quiet in the
                        // outage it exists to report. The matcher permits only a
                        // request that arrived on the container's own loopback and
                        // carried no forwarding header, which is what
                        // `docker compose exec` produces and what a caller through
                        // the edge cannot forge. See LocalMetricsScrapeMatcher for
                        // why both halves of that are needed.
                        .requestMatchers(localMetricsScrape)
                        .permitAll()
                        // ADR 0047: a guest who has pointed a camera at a table has
                        // no Keycloak principal and never will — requiring one would
                        // mean asking somebody to create an account before they can
                        // read a menu. Authorization on these two is the bearer
                        // token itself: the first exchanges a printed table token
                        // for a short-lived guest token, and the second carries that
                        // guest token in a header. Both resolve it to a row before
                        // doing anything, both are rate-limited per token through
                        // ADR 0033, and neither accepts a table id or a tenant.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/storefront/dine-in/qr/token-exchanges",
                                "/api/v1/storefront/dine-in/sessions/*/bill-requests")
                        .permitAll()
                        // ADR 0015 and ADR 0051: the three steps by which somebody
                        // with no account gets one, and they are unavoidably
                        // unauthenticated — asking for a one-time code is what
                        // happens before there is a principal, so requiring one
                        // would mean requiring an account in order to create an
                        // account.
                        //
                        // They were missing from this list, and that alone made
                        // signing in impossible: every request for a code was
                        // answered 401 by the filter chain, long before the
                        // handler that was written to serve an anonymous caller
                        // could run. The controller's own javadoc says the first
                        // two are unauthenticated "and unavoidably so"; nothing
                        // here had ever agreed with it.
                        //
                        // What authorises them is possession — of the handset the
                        // code is sent to, of the unguessable challenge id it was
                        // sent against, and of the single-use grant that proves
                        // both. Each is rate-limited per caller through ADR 0033
                        // and per destination in PostgreSQL, and the grant is
                        // spent by a conditional UPDATE that exactly one caller
                        // wins.
                        //
                        // Listed one at a time rather than as
                        // .../identity/** for the reason the GET list above gives:
                        // POST /registrations sits under the same prefix, requires
                        // a token by design, and a wildcard would open it — along
                        // with whatever is added there next.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/storefront/tenants/*/brands/*/identity/verification-challenges",
                                "/api/v1/storefront/tenants/*/brands/*/identity/verification-challenges/*/attempts",
                                "/api/v1/storefront/tenants/*/brands/*/identity/sessions")
                        .permitAll()
                        // ADR 0013: Click's SHOP API carries no authentication
                        // header of any kind. The MD5 sign_string over a
                        // secret-prefixed concatenation is the whole of it, and it
                        // is verified in the endpoint before any database is
                        // touched. Authenticating here instead is not available:
                        // Click sends no credential, and a 401 would be read as a
                        // transport failure and retried until the payment went to
                        // manual investigation.
                        .requestMatchers(HttpMethod.POST, "/providers/click/*/prepare", "/providers/click/*/complete")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer ->
                        resourceServer.bearerTokenResolver(bearerTokenResolver).jwt(Customizer.withDefaults()))
                .build();
    }
}
