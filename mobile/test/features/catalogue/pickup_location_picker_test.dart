import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/design/qoida_theme.dart';
import 'package:qoida_mobile/src/features/catalogue/data/pickup_location.dart';
import 'package:qoida_mobile/src/features/catalogue/data/pickup_location_repository.dart';
import 'package:qoida_mobile/src/features/catalogue/pickup_location_controller.dart';
import 'package:qoida_mobile/src/features/catalogue/ui/pickup_location_picker.dart';
import 'package:qoida_mobile/src/l10n/generated/app_localizations.dart';
import 'package:qoida_mobile/src/l10n/supported_locales.dart';

class _NoTokens implements AccessTokens {
  @override
  Future<String?> current() async => null;

  @override
  Future<String?> refresh() async => null;
}

const PickupSearchPoint _point = PickupSearchPoint(
  latitude: 41.311341,
  longitude: 69.282722,
);

const Map<String, Object?> _location = <String, Object?>{
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
};

PickupLocationRepository _repository(http.Client transport) =>
    PickupLocationRepository(
      api: QoidaApiClient(
        baseUri: Uri.parse('https://api.example.test'),
        httpClient: transport,
        tokens: _NoTokens(),
      ),
    );

Widget _host(Widget child) => MaterialApp(
  theme: QoidaTheme.light(),
  locale: SupportedLocales.english,
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: SupportedLocales.all,
  home: child,
);

void main() {
  test(
    'requests public pickup discovery around the configured coordinate',
    () async {
      late Uri seen;
      final PickupLocationRepository repository = _repository(
        MockClient((http.Request request) async {
          seen = request.url;
          return http.Response(
            jsonEncode(<String, Object?>{
              'locations': <Object?>[_location],
            }),
            200,
            headers: <String, String>{'content-type': 'application/json'},
          );
        }),
      );

      final List<PickupLocation> locations = await repository.nearby(
        point: _point,
      );

      expect(seen.path, '/api/v1/storefront/pickup-locations');
      expect(seen.queryParameters['lat'], '41.311341');
      expect(seen.queryParameters['lon'], '69.282722');
      expect(locations.single.catalogueScope.locationId, 'location-1');
    },
  );

  testWidgets('shows the nearest branch and opens it on tap', (
    WidgetTester tester,
  ) async {
    final PickupLocationsController controller = PickupLocationsController(
      repository: _repository(
        MockClient(
          (http.Request request) async => http.Response(
            jsonEncode(<String, Object?>{
              'locations': <Object?>[_location],
            }),
            200,
            headers: <String, String>{'content-type': 'application/json'},
          ),
        ),
      ),
      point: _point,
    );
    addTearDown(controller.dispose);
    PickupLocation? selected;

    await tester.pumpWidget(
      _host(
        PickupLocationPicker(
          controller: controller,
          onSelect: (PickupLocation location) => selected = location,
        ),
      ),
    );
    await controller.load();
    await tester.pumpAndSettle();

    expect(find.text('Qoida Cafe — Central kitchen'), findsOneWidget);
    expect(find.text('3600 m away'), findsOneWidget);
    expect(find.text('Available for pickup'), findsOneWidget);

    await tester.tap(find.text('Qoida Cafe — Central kitchen'));
    expect(selected?.catalogueScope.brandId, 'brand-1');
  });
}
