/// Where Keycloak is and how this application identifies itself (ADR 0003).
///
/// The realm is `qoida` — one realm, with tenants as Keycloak Organizations
/// inside it. ADR 0003 rejected realm-per-tenant: realms degrade past roughly a
/// hundred, and a multi-tenant customer would need identity brokering.
final class AuthConfig {
  const AuthConfig({
    required this.issuer,
    required this.clientId,
    required this.redirectUri,
    required this.callbackUrlScheme,
    this.scopes = const <String>['openid', 'profile', 'offline_access'],
    this.audience = 'qoida-api',
  });

  /// The realm's issuer, matching the platform's
  /// `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
  final Uri issuer;

  /// A public client. It has no secret, because a secret shipped in an
  /// application binary is not a secret.
  final String clientId;

  /// Must match an allowlisted redirect URI on the Keycloak client **exactly**.
  ///
  /// Exactly, not by wildcard: a wildcard redirect on a public client lets any
  /// application registering the same scheme receive the authorization code.
  final Uri redirectUri;

  /// The custom scheme the platform hands the redirect back on, and the value
  /// `flutter_web_auth_2` waits for. It must equal [redirectUri]'s scheme.
  final String callbackUrlScheme;

  final List<String> scopes;

  /// The platform validates this audience on every token.
  final String audience;

  /// Keycloak's endpoint layout under a realm issuer.
  ///
  /// Derived rather than discovered. OIDC discovery at
  /// `{issuer}/.well-known/openid-configuration` is the more correct answer and
  /// is what to move to once there is a reachable realm to try it against; the
  /// layout below has been fixed across Keycloak's major versions, and deriving
  /// it avoids a network round trip on cold start before the user has done
  /// anything. Recorded as a deliberate simplification, not an oversight.
  Uri get authorizationEndpoint =>
      issuer.replace(path: '${issuer.path}/protocol/openid-connect/auth');

  Uri get tokenEndpoint =>
      issuer.replace(path: '${issuer.path}/protocol/openid-connect/token');

  Uri get endSessionEndpoint =>
      issuer.replace(path: '${issuer.path}/protocol/openid-connect/logout');

  /// Builds the authorization request.
  ///
  /// `prompt` is not set: forcing `login` would defeat SSO, and forcing `none`
  /// would break a first sign-in.
  Uri authorizationUri({
    required String codeChallenge,
    required String state,
    required String nonce,
    String? uiLocale,
  }) {
    return authorizationEndpoint.replace(
      queryParameters: <String, String>{
        'response_type': 'code',
        'client_id': clientId,
        'redirect_uri': redirectUri.toString(),
        'scope': scopes.join(' '),
        'code_challenge': codeChallenge,
        'code_challenge_method': 'S256',
        'state': state,
        'nonce': nonce,
        // Keycloak renders its login pages in this locale. Without it a
        // customer who chose uz-Latn in the application meets a Russian login
        // screen, which reads as a different product.
        'ui_locales': ?uiLocale,
      },
    );
  }
}
