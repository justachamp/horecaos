/// A token response from Keycloak.
///
/// The access token lives here, in memory, for the lifetime of the object and
/// no longer. Only [refreshToken] is written to the device, and only by
/// `SecureRefreshTokenStore` (ADR 0035).
final class TokenSet {
  const TokenSet({
    required this.accessToken,
    required this.expiresAt,
    this.refreshToken,
    this.idToken,
  });

  final String accessToken;

  /// Absolute, in UTC. An absolute instant rather than the `expires_in` the
  /// server sent: a duration is only meaningful next to the moment it was
  /// received, and that moment is lost as soon as the response is handed on.
  final DateTime expiresAt;

  final String? refreshToken;

  /// Kept for the `id_token_hint` on logout.
  ///
  /// **Not validated.** Signature, issuer, audience and nonce checks against
  /// the realm's JWKS are not implemented here, and nothing in this application
  /// makes an authorization decision from its claims. The platform validates
  /// the access token on every request and is the enforcement point (ADR 0025).
  /// Implementing id_token validation needs a reachable realm to test against;
  /// see README, "What cannot be verified without a running Keycloak".
  final String? idToken;

  /// Whether the token should be refreshed now.
  ///
  /// The skew is generous on purpose: a token that expires while a request is
  /// in flight produces a 401 the customer sees as a failed order, and the cost
  /// of refreshing a minute early is one extra round trip.
  bool needsRefresh({
    DateTime? now,
    Duration skew = const Duration(seconds: 60),
  }) => (now ?? DateTime.now().toUtc()).add(skew).isAfter(expiresAt);

  static TokenSet fromResponse(
    Map<String, Object?> json, {
    required DateTime receivedAt,
  }) {
    final Object? accessToken = json['access_token'];
    if (accessToken is! String || accessToken.isEmpty) {
      throw const FormatException('Token response carried no access_token');
    }
    final Object? expiresIn = json['expires_in'];
    return TokenSet(
      accessToken: accessToken,
      // A response without expires_in is treated as already expired rather than
      // as long-lived. Guessing long is how a client sits on a dead token.
      expiresAt: receivedAt.add(
        Duration(seconds: expiresIn is int ? expiresIn : 0),
      ),
      refreshToken: json['refresh_token'] as String?,
      idToken: json['id_token'] as String?,
    );
  }

  /// Never includes the token values.
  ///
  /// A token in a crash report or a log line is a credential someone else can
  /// use, and `toString` is what a crash reporter calls (ADR 0029).
  @override
  String toString() =>
      'TokenSet(expiresAt: $expiresAt, refreshable: ${refreshToken != null})';
}

/// The OAuth error the token endpoint returned, or a transport failure.
final class AuthException implements Exception {
  const AuthException(this.code, {this.description});

  /// The OAuth 2.0 `error` code — `invalid_grant`, `access_denied` — or a local
  /// code such as `state_mismatch`.
  final String code;

  /// The server's `error_description`, which OAuth specifies as developer-facing
  /// ASCII and which is never shown to a customer.
  final String? description;

  /// A refresh token that Keycloak will not honour again: revoked, expired, or
  /// already used under rotation. The session is over and the customer signs in
  /// again.
  bool get isSessionOver => code == 'invalid_grant';

  @override
  String toString() => 'AuthException($code)';
}
