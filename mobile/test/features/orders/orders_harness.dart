import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/design/qoida_theme.dart';
import 'package:qoida_mobile/src/features/orders/data/orders_repository.dart';
import 'package:qoida_mobile/src/l10n/generated/app_localizations.dart';
import 'package:qoida_mobile/src/l10n/supported_locales.dart';

/// Shared scaffolding for the order tests.
///
/// The screens are exercised through a real [OrdersRepository] over a real
/// [QoidaApiClient] with a `MockClient` transport, rather than against a hand-
/// written fake repository. That is deliberate: the decoding, the path, the
/// cursor parameters and the `ETag` are the parts most likely to be wrong, and
/// a fake repository is exactly the thing that cannot catch them.
const String tenantId = '018f0000-0000-7000-8000-00000000t3n7';
const String brandId = '018f0000-0000-7000-8000-0000000br4nd';

/// The path both screens call, assembled once so a test asserting it and the
/// production code cannot drift apart silently.
const String ordersPath =
    '/api/v1/storefront/tenants/$tenantId/brands/$brandId/orders';

class StubTokens implements AccessTokens {
  @override
  Future<String?> current() async => 'at_1';

  @override
  Future<String?> refresh() async => null;
}

OrdersRepository repositoryOver(MockClient transport) => OrdersRepository(
  api: QoidaApiClient(
    baseUri: Uri.parse('https://api.example.test'),
    httpClient: transport,
    tokens: StubTokens(),
    correlationIds: () => 'cid-fixed',
  ),
  tenantId: tenantId,
  brandId: brandId,
);

http.Response jsonResponse(
  Object body, {
  int status = 200,
  Map<String, String>? headers,
}) => http.Response(
  jsonEncode(body),
  status,
  headers: <String, String>{'content-type': 'application/json', ...?headers},
);

/// An RFC 9457 Problem Details response, in the shape the platform's
/// `ApiProblem` renders.
http.Response problemResponse(int status, String code) => http.Response(
  jsonEncode(<String, Object?>{
    'status': status,
    'code': code,
    'title': code,
    'correlationId': 'cid-fixed',
  }),
  status,
  headers: <String, String>{'content-type': 'application/problem+json'},
);

/// One order as `StorefrontOrderingController` renders it, plus the members
/// these screens read that it does not send yet. Every added member is optional
/// in the decoder, so a test can leave it out to reproduce today's server.
Map<String, Object?> orderJson({
  String orderId = '018f0000-0000-7000-8000-00000000000a',
  String publicOrderNumber = 'A-1042',
  String status = 'PREPARING',
  String? fulfillmentMode = 'DELIVERY',
  int totalMinor = 84000,
  int subtotalMinor = 78000,
  int? taxMinor = 6000,
  int? discountMinor,
  int? feeMinor,
  String currency = 'UZS',
  String createdAt = '2026-08-24T09:00:00Z',
  String? confirmedAt = '2026-08-24T09:02:00Z',
  String? closedAt,
  String? promisedAt = '2026-08-24T09:45:00Z',
  String? paymentStatus = 'NOT_REQUIRED',
  String? paymentMethodName,
  String? courierFirstName,
  Map<String, Object?>? outcome,
  List<Map<String, Object?>>? lines,
  int version = 3,
}) => <String, Object?>{
  'orderId': orderId,
  'publicOrderNumber': publicOrderNumber,
  'status': status,
  'currency': currency,
  'subtotalMinor': subtotalMinor,
  'taxMinor': ?taxMinor,
  'discountMinor': ?discountMinor,
  'feeMinor': ?feeMinor,
  'totalMinor': totalMinor,
  'version': version,
  'createdAt': createdAt,
  'confirmedAt': ?confirmedAt,
  'closedAt': ?closedAt,
  'fulfillmentMode': ?fulfillmentMode,
  'promisedAt': ?promisedAt,
  'paymentStatus': ?paymentStatus,
  'paymentMethodName': ?paymentMethodName,
  'courierFirstName': ?courierFirstName,
  'outcome': ?outcome,
  'lines':
      lines ??
      <Map<String, Object?>>[
        <String, Object?>{
          'lineNumber': 1,
          'productName': 'Lagman',
          'variantName': 'Katta',
          'quantity': 2,
          'unitAmountMinor': 39000,
          'finalAmountMinor': 78000,
          'modifiers': <String>['Achchiq'],
        },
      ],
  'warnings': <String>[],
};

/// An ADR 0031 page envelope.
Map<String, Object?> pageJson(
  List<Map<String, Object?>> items, {
  String? nextCursor,
}) => <String, Object?>{'items': items, 'nextCursor': nextCursor};

/// A screen under the real theme and the real localisations.
Widget host(Widget child, {Locale locale = SupportedLocales.russian}) =>
    MaterialApp(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: SupportedLocales.all,
      theme: QoidaTheme.light(),
      home: child,
    );

Future<AppLocalizations> localisations([
  Locale locale = SupportedLocales.russian,
]) => AppLocalizations.delegate.load(locale);
