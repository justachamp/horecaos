import 'dart:convert';

import 'package:http/http.dart' as http;

import 'auth_config.dart';
import 'tokens.dart';

/// Talks to the realm's token endpoint. Nothing else in the application does.
///
/// Separated from the session so it can be tested against a mock transport:
/// this is the part with wire format in it, and it is the part most likely to
/// be wrong until someone runs it against a real Keycloak.
final class TokenEndpoint {
  const TokenEndpoint({required this.config, required this.httpClient});

  final AuthConfig config;
  final http.Client httpClient;

  /// Redeems an authorization code. The verifier goes here, never in the
  /// authorization request.
  Future<TokenSet> exchangeCode({
    required String code,
    required String codeVerifier,
  }) => _post(<String, String>{
    'grant_type': 'authorization_code',
    'client_id': config.clientId,
    'code': code,
    'redirect_uri': config.redirectUri.toString(),
    'code_verifier': codeVerifier,
  });

  Future<TokenSet> refresh(String refreshToken) => _post(<String, String>{
    'grant_type': 'refresh_token',
    'client_id': config.clientId,
    'refresh_token': refreshToken,
  });

  /// Best-effort revocation at the realm.
  ///
  /// Failure is swallowed: local state is cleared regardless, because a
  /// customer who taps sign out must end up signed out on this device whatever
  /// the network is doing. The token remains valid at the realm until it
  /// expires, and that is the accepted cost.
  Future<void> endSession(String refreshToken) async {
    try {
      await httpClient.post(
        config.endSessionEndpoint,
        headers: const <String, String>{
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: <String, String>{
          'client_id': config.clientId,
          'refresh_token': refreshToken,
        },
      );
    } on http.ClientException {
      return;
    }
  }

  Future<TokenSet> _post(Map<String, String> form) async {
    final DateTime sentAt = DateTime.now().toUtc();
    final http.Response response;
    try {
      response = await httpClient.post(
        config.tokenEndpoint,
        headers: const <String, String>{
          'Content-Type': 'application/x-www-form-urlencoded',
          'Accept': 'application/json',
        },
        body: form,
      );
    } on http.ClientException catch (failure) {
      throw AuthException('transport_failure', description: failure.message);
    }

    final Object? decoded = _tryDecode(response.body);
    if (response.statusCode >= 400) {
      final Map<String, Object?> problem = decoded is Map<String, Object?>
          ? decoded
          : const <String, Object?>{};
      throw AuthException(
        problem['error'] as String? ?? 'http_${response.statusCode}',
        description: problem['error_description'] as String?,
      );
    }
    if (decoded is! Map<String, Object?>) {
      throw const AuthException('malformed_token_response');
    }

    // `sentAt`, not the moment the response was parsed: expiry is measured from
    // when the server issued the token, and erring early is the safe direction.
    return TokenSet.fromResponse(decoded, receivedAt: sentAt);
  }

  static Object? _tryDecode(String body) {
    try {
      return jsonDecode(body);
    } on FormatException {
      return null;
    }
  }
}
