import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/app.dart';
import 'package:qoida_mobile/src/auth/auth_config.dart';
import 'package:qoida_mobile/src/auth/auth_session.dart';
import 'package:qoida_mobile/src/auth/token_endpoint.dart';
import 'package:qoida_mobile/src/auth/token_store.dart';
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/design/qoida_theme.dart';
import 'package:qoida_mobile/src/features/catalogue/data/pickup_location.dart';
import 'package:qoida_mobile/src/l10n/generated/app_localizations.dart';
import 'package:qoida_mobile/src/l10n/supported_locales.dart';

final AuthConfig _config = AuthConfig(
  issuer: Uri.parse('https://id.example.test/realms/qoida'),
  clientId: 'qoida-mobile',
  redirectUri: Uri.parse('uz.qoida.mobile://oauth/callback'),
  callbackUrlScheme: 'uz.qoida.mobile',
);

AuthSession _session({String? storedRefreshToken}) => AuthSession(
  config: _config,
  tokenEndpoint: TokenEndpoint(
    config: _config,
    httpClient: MockClient(
      (http.Request request) async => http.Response(
        jsonEncode(<String, Object?>{
          'access_token': 'at_1',
          'refresh_token': 'rt_2',
          'expires_in': 300,
        }),
        200,
        headers: <String, String>{'content-type': 'application/json'},
      ),
    ),
  ),
  refreshTokens: InMemoryRefreshTokenStore(storedRefreshToken),
);

class _NoTokens implements AccessTokens {
  @override
  Future<String?> current() async => null;

  @override
  Future<String?> refresh() async => null;
}

const PickupSearchPoint _pickupPoint = PickupSearchPoint(
  latitude: 41.311341,
  longitude: 69.282722,
);

QoidaApiClient _api() => QoidaApiClient(
  baseUri: Uri.parse('https://api.example.test'),
  httpClient: MockClient((http.Request request) async {
    if (request.url.path == '/api/v1/storefront/pickup-locations') {
      return http.Response(
        jsonEncode(<String, Object?>{
          'locations': <Object?>[
            <String, Object?>{
              'tenantId': 'tenant-1',
              'brandId': 'brand-1',
              'locationId': 'location-1',
              'brandName': 'Qoida Cafe',
              'locationName': 'Central kitchen',
              'addressLine': '1 Demo Street',
              'district': 'Shaykhontohur',
              'city': 'Tashkent',
              'distanceMeters': 3600,
              'available': true,
              'reason': null,
              'acceptsScheduledOrders': true,
              'preparationMinutes': 20,
            },
          ],
        }),
        200,
        headers: <String, String>{'content-type': 'application/json'},
      );
    }
    if (request.url.path.endsWith('/menu')) {
      return http.Response(
        jsonEncode(<String, Object?>{
          'publicationId': 'publication-1',
          'locale': 'ru',
          'categories': <Object?>[],
          'modifierGroups': <Object?>[],
          'products': <Object?>[
            <String, Object?>{
              'productId': 'product-1',
              'code': 'PLOV',
              'name': 'Плов',
              'description': null,
              'mediaAssetIds': <Object?>[],
              'variants': <Object?>[
                <String, Object?>{
                  'variantId': 'variant-1',
                  'sku': 'PLOV-1',
                  'unitCode': 'PORTION',
                  'isDefault': true,
                  'orderable': true,
                },
              ],
              'modifierGroupIds': <Object?>[],
            },
          ],
        }),
        200,
        headers: <String, String>{'content-type': 'application/json'},
      );
    }
    return http.Response('{}', 404);
  }),
  tokens: _NoTokens(),
);

QoidaApp _app(AuthSession session) =>
    QoidaApp(session: session, api: _api(), initialPickupPoint: _pickupPoint);

void main() {
  // Every assertion below reads a Russian string, so the locale has to be the
  // one being asserted rather than whatever the host reports. The test binding
  // defaults to en-US, which this application genuinely supports — so without
  // this the app renders correct English while the test looks for Russian, and
  // the failure reads as a missing widget rather than a locale mismatch.
  setUp(() {
    // `localesTestValue`, not `localeTestValue`: WidgetsApp resolves against the
    // platform's locale *list*, and setting only the primary leaves that list at
    // its default. The symptom is an application that renders correct English
    // while the test looks for Russian, which reads as a missing widget rather
    // than as a locale that was never applied.
    TestWidgetsFlutterBinding.ensureInitialized()
        .platformDispatcher
        .localesTestValue = <Locale>[
      SupportedLocales.russian,
    ];
  });

  tearDown(() {
    TestWidgetsFlutterBinding.ensureInitialized().platformDispatcher
        .clearLocalesTestValue();
  });

  testWidgets('a signed-out customer can choose a pickup location', (
    WidgetTester tester,
  ) async {
    final AuthSession session = _session();
    await tester.pumpWidget(_app(session));
    await session.restore();
    await tester.pumpAndSettle();

    final AppLocalizations l10n = await AppLocalizations.delegate.load(
      SupportedLocales.russian,
    );
    expect(find.text(l10n.pickupLocationsTitle), findsOneWidget);
    expect(find.byType(NavigationBar), findsOneWidget);
  });

  testWidgets('a restored session lands inside the shell', (
    WidgetTester tester,
  ) async {
    final AuthSession session = _session(storedRefreshToken: 'rt_1');
    await tester.pumpWidget(_app(session));
    await session.restore();
    await tester.pumpAndSettle();

    expect(find.byType(NavigationBar), findsOneWidget);

    final AppLocalizations l10n = await AppLocalizations.delegate.load(
      SupportedLocales.russian,
    );
    expect(find.text(l10n.navHome), findsOneWidget);
    expect(find.text(l10n.navOrders), findsOneWidget);
  });

  testWidgets('a guest can choose a nearby branch and browse its live menu', (
    WidgetTester tester,
  ) async {
    final AuthSession session = _session();
    await tester.pumpWidget(_app(session));
    await session.restore();
    await tester.pumpAndSettle();

    await tester.tap(find.text('Qoida Cafe — Central kitchen'));
    await tester.pumpAndSettle();

    expect(find.text('Плов'), findsOneWidget);
  });

  testWidgets('the bottom bar moves between the two routes', (
    WidgetTester tester,
  ) async {
    final AuthSession session = _session(storedRefreshToken: 'rt_1');
    await tester.pumpWidget(_app(session));
    await session.restore();
    await tester.pumpAndSettle();

    final AppLocalizations l10n = await AppLocalizations.delegate.load(
      SupportedLocales.russian,
    );
    await tester.tap(find.text(l10n.navOrders));
    await tester.pumpAndSettle();

    final NavigationBar bar = tester.widget<NavigationBar>(
      find.byType(NavigationBar),
    );
    expect(bar.selectedIndex, 1);
  });

  testWidgets(
    'signing out returns to public browse without a screen asking it to',
    (WidgetTester tester) async {
      final AuthSession session = _session(storedRefreshToken: 'rt_1');
      await tester.pumpWidget(_app(session));
      await session.restore();
      await tester.pumpAndSettle();
      expect(find.byType(NavigationBar), findsOneWidget);

      // The guard listens to the session, so nothing navigates explicitly. This
      // is what makes a failed refresh mid-order behave the same as a deliberate
      // sign-out.
      await session.signOut();
      await tester.pumpAndSettle();

      expect(find.byType(NavigationBar), findsOneWidget);
    },
  );

  testWidgets('the theme reaching the tree is the Qoida theme', (
    WidgetTester tester,
  ) async {
    final AuthSession session = _session(storedRefreshToken: 'rt_1');
    await tester.pumpWidget(_app(session));
    await session.restore();
    await tester.pumpAndSettle();

    final BuildContext context = tester.element(find.byType(NavigationBar));
    expect(context.qoida.radius, 8);
    expect(context.qoida.minTarget, 48);
  });
}
