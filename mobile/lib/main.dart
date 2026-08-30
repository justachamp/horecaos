import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;

import 'src/api/api_client.dart';
import 'src/app.dart';
import 'src/app_scope.dart';
import 'src/auth/auth_session.dart';
import 'src/auth/token_endpoint.dart';
import 'src/auth/token_store.dart';
import 'src/config/app_config.dart';
import 'src/features/catalogue/data/pickup_location.dart';

/// Composition root.
///
/// Everything is constructed here and handed down through [AppScope]. There is
/// no service locator and no global: a widget that can reach the API client
/// without being given one is a widget that cannot be tested without a network.
void main() {
  WidgetsFlutterBinding.ensureInitialized();

  final AppConfig config = AppConfig.fromEnvironment();

  // One client for the application, so connections are pooled and a single
  // place decides the timeout. The token endpoint shares it deliberately: it
  // talks to Keycloak rather than to the platform, but it is the same transport.
  final http.Client httpClient = http.Client();

  final AuthSession session = AuthSession(
    config: config.auth,
    tokenEndpoint: TokenEndpoint(config: config.auth, httpClient: httpClient),
    refreshTokens: const SecureRefreshTokenStore(),
  );

  final HorecaOSApiClient api = HorecaOSApiClient(
    baseUri: config.apiBaseUri,
    httpClient: httpClient,
    tokens: session,
  );

  // Not awaited. The router's guard holds on the starting route until the
  // session resolves, so awaiting here would only delay the first frame while
  // showing the same thing.
  unawaited(session.restore());

  runApp(
    AppScope(
      session: session,
      api: api,
      child: HorecaOSApp(
        session: session,
        api: api,
        initialPickupPoint: PickupSearchPoint(
          latitude: config.initialPickupLatitude,
          longitude: config.initialPickupLongitude,
        ),
      ),
    ),
  );
}
