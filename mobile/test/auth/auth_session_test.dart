import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/auth/auth_config.dart';
import 'package:qoida_mobile/src/auth/auth_session.dart';
import 'package:qoida_mobile/src/auth/authorization_browser.dart';
import 'package:qoida_mobile/src/auth/token_endpoint.dart';
import 'package:qoida_mobile/src/auth/token_store.dart';
import 'package:qoida_mobile/src/auth/tokens.dart';

final AuthConfig _config = AuthConfig(
  issuer: Uri.parse('https://id.example.test/realms/qoida'),
  clientId: 'qoida-mobile',
  redirectUri: Uri.parse('uz.qoida.mobile://oauth/callback'),
  callbackUrlScheme: 'uz.qoida.mobile',
);

/// Returns whatever the test tells it to, and records what it was asked.
class _FakeBrowser implements AuthorizationBrowser {
  _FakeBrowser(this.respond);

  final Uri Function(Uri authorizationUri) respond;
  Uri? seen;

  @override
  Future<Uri> authorize({
    required Uri authorizationUri,
    required String callbackUrlScheme,
  }) async {
    seen = authorizationUri;
    return respond(authorizationUri);
  }
}

/// Echoes the `state` the application sent, as a well-behaved realm does.
Uri _redirectEchoingState(Uri authorizationUri) {
  final String state = authorizationUri.queryParameters['state']!;
  return Uri.parse('uz.qoida.mobile://oauth/callback?code=ac_1&state=$state');
}

String _tokenBody({
  String access = 'at_1',
  String? refresh = 'rt_2',
  int expiresIn = 300,
}) => jsonEncode(<String, Object?>{
  'access_token': access,
  'token_type': 'Bearer',
  'expires_in': expiresIn,
  'refresh_token': ?refresh,
});

void main() {
  group('restore', () {
    test('with nothing stored, ends signed out without a network call', () async {
      var calls = 0;
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            calls++;
            return http.Response('{}', 200);
          }),
        ),
        refreshTokens: InMemoryRefreshTokenStore(),
      );

      expect(session.status, AuthStatus.unknown);
      await session.restore();

      expect(session.status, AuthStatus.signedOut);
      expect(calls, 0);
    });

    test('redeems a stored refresh token and stores the rotated one', () async {
      final InMemoryRefreshTokenStore store = InMemoryRefreshTokenStore('rt_1');
      String? presented;

      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            presented = Uri.splitQueryString(request.body)['refresh_token'];
            return http.Response(
              _tokenBody(),
              200,
              headers: <String, String>{'content-type': 'application/json'},
            );
          }),
        ),
        refreshTokens: store,
      );

      await session.restore();

      expect(session.status, AuthStatus.signedIn);
      expect(presented, 'rt_1');
      // Keycloak rotates on every redemption. Keeping the old token would
      // present a retired credential on the next refresh.
      expect(await store.read(), 'rt_2');
      expect(await session.current(), 'at_1');
    });

    test('clears a refresh token the realm rejects', () async {
      final InMemoryRefreshTokenStore store = InMemoryRefreshTokenStore('rt_old');
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient(
            (http.Request request) async => http.Response(
              jsonEncode(<String, String>{'error': 'invalid_grant'}),
              400,
              headers: <String, String>{'content-type': 'application/json'},
            ),
          ),
        ),
        refreshTokens: store,
      );

      await session.restore();

      expect(session.status, AuthStatus.signedOut);
      expect(await store.read(), isNull);
    });
  });

  group('sign-in', () {
    test('sends S256 PKCE and the exact redirect URI', () async {
      final _FakeBrowser browser = _FakeBrowser(_redirectEchoingState);
      final Map<String, String> exchanged = <String, String>{};

      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            exchanged.addAll(Uri.splitQueryString(request.body));
            return http.Response(
              _tokenBody(),
              200,
              headers: <String, String>{'content-type': 'application/json'},
            );
          }),
        ),
        refreshTokens: InMemoryRefreshTokenStore(),
        browser: browser,
      );

      await session.signIn();

      final Map<String, String> authorize = browser.seen!.queryParameters;
      expect(authorize['response_type'], 'code');
      expect(authorize['code_challenge_method'], 'S256');
      expect(authorize['code_challenge'], isNotEmpty);
      // The verifier must never appear in the authorization request: that is
      // the value the challenge exists to keep out of it.
      expect(authorize.containsKey('code_verifier'), isFalse);
      expect(authorize['redirect_uri'], 'uz.qoida.mobile://oauth/callback');
      expect(authorize['client_id'], 'qoida-mobile');

      expect(exchanged['grant_type'], 'authorization_code');
      expect(exchanged['code'], 'ac_1');
      expect(exchanged['code_verifier'], isNotEmpty);
      expect(session.status, AuthStatus.signedIn);
    });

    test('refuses a redirect whose state it did not send', () async {
      var exchanges = 0;
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            exchanges++;
            return http.Response(_tokenBody(), 200);
          }),
        ),
        refreshTokens: InMemoryRefreshTokenStore(),
        browser: _FakeBrowser(
          (_) => Uri.parse(
            'uz.qoida.mobile://oauth/callback?code=attacker&state=not-ours',
          ),
        ),
      );

      // An unchecked state is a login-CSRF: the attacker's code is redeemed and
      // the customer is signed into the attacker's account.
      await expectLater(
        session.signIn(),
        throwsA(
          isA<AuthException>().having(
            (AuthException e) => e.code,
            'code',
            'state_mismatch',
          ),
        ),
      );
      expect(exchanges, 0);
      expect(session.status, AuthStatus.unknown);
    });

    test('surfaces an error the realm returned on the redirect', () async {
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient(
            (http.Request request) async => http.Response('{}', 500),
          ),
        ),
        refreshTokens: InMemoryRefreshTokenStore(),
        browser: _FakeBrowser(
          (_) => Uri.parse(
            'uz.qoida.mobile://oauth/callback?error=access_denied',
          ),
        ),
      );

      await expectLater(
        session.signIn(),
        throwsA(
          isA<AuthException>().having(
            (AuthException e) => e.code,
            'code',
            'access_denied',
          ),
        ),
      );
    });
  });

  group('refresh', () {
    test('collapses concurrent refreshes into one redemption', () async {
      var redemptions = 0;
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            redemptions++;
            await Future<void>.delayed(const Duration(milliseconds: 10));
            return http.Response(
              _tokenBody(access: 'at_$redemptions'),
              200,
              headers: <String, String>{'content-type': 'application/json'},
            );
          }),
        ),
        refreshTokens: InMemoryRefreshTokenStore('rt_1'),
      );
      await session.restore();
      expect(redemptions, 1);

      final List<String?> results = await Future.wait<String?>(<Future<String?>>[
        session.refresh(),
        session.refresh(),
        session.refresh(),
      ]);

      // Under refresh-token rotation, three parallel redemptions would
      // invalidate each other and sign the customer out mid-order.
      expect(redemptions, 2);
      expect(results, everyElement(results.first));
    });

    test('refreshes proactively before the token expires', () async {
      var redemptions = 0;
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            redemptions++;
            return http.Response(
              // Ten seconds is inside the sixty-second skew, so the very next
              // read should refresh rather than hand out a token that will
              // expire while a request is in flight.
              _tokenBody(access: 'at_$redemptions', expiresIn: 10),
              200,
              headers: <String, String>{'content-type': 'application/json'},
            );
          }),
        ),
        refreshTokens: InMemoryRefreshTokenStore('rt_1'),
      );

      await session.restore();
      expect(redemptions, 1);

      expect(await session.current(), 'at_2');
      expect(redemptions, 2);
    });
  });

  group('sign-out', () {
    test('clears local state even when the realm cannot be reached', () async {
      final InMemoryRefreshTokenStore store = InMemoryRefreshTokenStore('rt_1');
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            if (request.url == _config.endSessionEndpoint) {
              throw http.ClientException('no route to host');
            }
            return http.Response(
              _tokenBody(),
              200,
              headers: <String, String>{'content-type': 'application/json'},
            );
          }),
        ),
        refreshTokens: store,
      );
      await session.restore();
      expect(session.status, AuthStatus.signedIn);

      await session.signOut();

      expect(session.status, AuthStatus.signedOut);
      expect(await store.read(), isNull);
      expect(await session.current(), isNull);
    });

    test('is not undone by a refresh that was already in flight', () async {
      final InMemoryRefreshTokenStore store = InMemoryRefreshTokenStore('rt_1');
      final Completer<void> redemptionReached = Completer<void>();
      final Completer<void> releaseRedemption = Completer<void>();
      var redemptions = 0;

      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient((http.Request request) async {
            if (request.url == _config.endSessionEndpoint) {
              return http.Response('', 204);
            }
            redemptions++;
            if (redemptions == 2) {
              // Park the second redemption at the realm so sign-out lands
              // while its response is still on the wire.
              redemptionReached.complete();
              await releaseRedemption.future;
            }
            return http.Response(
              _tokenBody(
                access: 'at_$redemptions',
                refresh: 'rt_${redemptions + 1}',
              ),
              200,
              headers: <String, String>{'content-type': 'application/json'},
            );
          }),
        ),
        refreshTokens: store,
      );

      await session.restore();
      expect(session.status, AuthStatus.signedIn);

      final Future<String?> inFlight = session.refresh();
      await redemptionReached.future;

      await session.signOut();
      expect(session.status, AuthStatus.signedOut);
      expect(await store.read(), isNull);

      releaseRedemption.complete();

      // The caller that asked for the refresh is told there is no token,
      // rather than handed one that outlives the session it came from.
      expect(await inFlight, isNull);

      // The whole of the defect: adopting this response would put an access
      // token back in memory and a rotated refresh token back in the keystore,
      // so the next launch restores the session the customer just ended.
      expect(await session.current(), isNull);
      expect(await store.read(), isNull);
      expect(session.status, AuthStatus.signedOut);
    });

    test('notifies listeners so the router guard re-runs', () async {
      var notifications = 0;
      final AuthSession session = AuthSession(
        config: _config,
        tokenEndpoint: TokenEndpoint(
          config: _config,
          httpClient: MockClient(
            (http.Request request) async => http.Response(
              _tokenBody(),
              200,
              headers: <String, String>{'content-type': 'application/json'},
            ),
          ),
        ),
        refreshTokens: InMemoryRefreshTokenStore('rt_1'),
      )..addListener(() => notifications++);

      await session.restore();
      await session.signOut();

      expect(notifications, 2);
    });
  });

  group('the authorization sheet', () {
    test('asks for an ephemeral browser session', () {
      // `FlutterWebAuth2.authenticate` goes through a platform channel, so the
      // option it is handed cannot be observed from a unit test. Reading the
      // source is the same technique `test/design/design_system_lint_test.dart`
      // uses, and it fails the same build.
      //
      // The value matters on a shared phone, which is the normal case here:
      // `preferEphemeral: false` leaves the realm's SSO cookie in the device's
      // system browser, so the next person to tap "sign in" is silently
      // admitted to the previous customer's account.
      final String source = File(
        'lib/src/auth/authorization_browser.dart',
      ).readAsStringSync();
      expect(source, contains('preferEphemeral: true'));
    });
  });

  group('token hygiene', () {
    test('toString never carries a token value', () {
      // toString is what a crash reporter calls. A token in a crash report is a
      // credential somebody else can use (ADR 0029).
      final TokenSet tokens = TokenSet(
        accessToken: 'secret-access-token',
        refreshToken: 'secret-refresh-token',
        expiresAt: DateTime.utc(2026, 8, 22),
      );
      expect(tokens.toString(), isNot(contains('secret')));
    });
  });
}
