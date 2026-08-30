import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:qoida_mobile/src/api/api_client.dart';
import 'package:qoida_mobile/src/design/qoida_theme.dart';
import 'package:qoida_mobile/src/features/cart/cart_models.dart';
import 'package:qoida_mobile/src/l10n/generated/app_localizations.dart';
import 'package:qoida_mobile/src/l10n/supported_locales.dart';

/// The scope every test uses. Fixed identifiers so a path assertion can be
/// written out in full rather than interpolated from the thing under test.
const StorefrontScope testScope = StorefrontScope(
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  locationId: 'branch-1',
  channel: 'MOBILE_APP',
);

const String basePath =
    '/api/v1/storefront/tenants/tenant-1/brands/brand-1';

/// A session that never expires and never refreshes.
class StubTokens implements AccessTokens {
  @override
  Future<String?> current() async => 'access-token';

  @override
  Future<String?> refresh() async => 'access-token';
}

/// One recorded request, with its body already decoded.
class Recorded {
  Recorded(this.request, this.body);

  final http.Request request;
  final Map<String, Object?> body;
}

/// Builds a client whose transport answers from [handler] and records what it
/// was asked.
QoidaApiClient client(
  Future<http.Response> Function(http.Request request) handler, {
  List<Recorded>? log,
}) => QoidaApiClient(
  baseUri: Uri.parse('https://api.example.test'),
  httpClient: MockClient((http.Request request) async {
    log?.add(
      Recorded(
        request,
        request.body.isEmpty
            ? const <String, Object?>{}
            : jsonDecode(request.body) as Map<String, Object?>,
      ),
    );
    return handler(request);
  }),
  tokens: StubTokens(),
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

/// An RFC 9457 problem, in the shape the platform sends one.
http.Response problem(
  String code, {
  int status = 409,
  String? reason,
  Map<String, Object?> extensions = const <String, Object?>{},
}) => http.Response(
  jsonEncode(<String, Object?>{
    'status': status,
    'code': code,
    'title': code,
    'correlationId': 'cid-1',
    'reason': ?reason,
    ...extensions,
  }),
  status,
  headers: <String, String>{'content-type': 'application/problem+json'},
);

/// The instant every fixture is written against.
///
/// Tests pin the controllers' clock to this rather than letting them read
/// `DateTime.now`. The quote fixtures carry absolute expiry timestamps, so with
/// a real clock these tests pass until wall-clock time crosses one of them and
/// then fail forever after — which is exactly what happened: everything went
/// green in the morning and every checkout turned into `CheckoutPriceMoved`
/// after 12:15 UTC, with no code change between the two runs.
///
/// Fifteen minutes before [pricedJson]'s default expiry, so a freshly priced
/// quote is usable and a test that wants an expired one moves the fixture rather
/// than the clock.
final DateTime fixtureNow = DateTime.utc(2026, 8, 24, 12, 0, 0);

/// The clock to hand a controller under test.
DateTime Function() get fixtureClock => () => fixtureNow;

/// A `CartResponse` body.
Map<String, Object?> cartJson({
  String cartId = 'cart-1',
  String status = 'ACTIVE',
  int version = 1,
  String? quoteId,
  String? contextHash,
  List<Map<String, Object?>> lines = const <Map<String, Object?>>[],
  String? expiresAt,
}) => <String, Object?>{
  'cartId': cartId,
  'locationId': 'branch-1',
  'status': status,
  'currency': 'UZS',
  'version': version,
  'quoteId': quoteId,
  'contextHash': contextHash,
  'expiresAt': expiresAt ?? '2026-08-24T20:00:00Z',
  'lines': lines,
};

Map<String, Object?> lineJson({
  String lineKey = 'line-1',
  String variantId = 'variant-1',
  int quantity = 1,
  bool hasCustomerNote = false,
}) => <String, Object?>{
  'lineKey': lineKey,
  'variantId': variantId,
  'quantity': quantity,
  'hasCustomerNote': hasCustomerNote,
};

/// A `PricedCartResponse` body. Amounts are whole som (ADR 0018).
Map<String, Object?> pricedJson({
  int version = 2,
  String quoteId = 'quote-1',
  String contextHash = 'hash-1',
  int subtotal = 84000,
  int tax = 0,
  int total = 84000,
  String expiresAt = '2026-08-24T12:15:00Z',
}) => <String, Object?>{
  'cartId': 'cart-1',
  'cartVersion': version,
  'quoteId': quoteId,
  'contextHash': contextHash,
  'currency': 'UZS',
  'subtotalMinor': subtotal,
  'taxMinor': tax,
  'totalMinor': total,
  'expiresAt': expiresAt,
};

/// A rendered UZS amount, written with visible separators.
///
/// `uzs('84 000')` is `84\u00A0000\u00A0so'm`. The separator is a no-break
/// space so a price never wraps mid-number, which is invisible in a source file
/// and is exactly the sort of difference a hand-typed expectation gets wrong.
String uzs(String grouped) =>
    "${grouped.replaceAll(' ', '\u00A0')}\u00A0so'm";

/// Wraps a screen in exactly the theme and localisations the application uses.
///
/// The real theme, not a stub: a widget that reads `context.qoida` throws
/// without it, and a test that supplied a Material default would be testing a
/// tree the application never builds.
Future<void> pumpScreen(
  WidgetTester tester,
  Widget child, {
  Locale locale = const Locale('en'),
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: QoidaTheme.light(),
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: SupportedLocales.all,
      home: child,
    ),
  );
  await tester.pump();
}
