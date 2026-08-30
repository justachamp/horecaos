import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:horecaos_mobile/src/api/api_client.dart';
import 'package:horecaos_mobile/src/api/idempotency_key.dart';
import 'package:horecaos_mobile/src/app_scope.dart';
import 'package:horecaos_mobile/src/auth/auth_config.dart';
import 'package:horecaos_mobile/src/auth/auth_session.dart';
import 'package:horecaos_mobile/src/auth/token_endpoint.dart';
import 'package:horecaos_mobile/src/auth/token_store.dart';
import 'package:horecaos_mobile/src/design/horecaos_theme.dart';
import 'package:horecaos_mobile/src/features/profile/customer_scope.dart';
import 'package:horecaos_mobile/src/features/profile/data/customer_account.dart';
import 'package:horecaos_mobile/src/features/profile/data/notification_preference.dart';
import 'package:horecaos_mobile/src/features/profile/data/notification_preference_repository.dart';
import 'package:horecaos_mobile/src/features/profile/data/saved_address.dart';
import 'package:horecaos_mobile/src/features/profile/data/saved_address_repository.dart';
import 'package:horecaos_mobile/src/features/profile/profile_area.dart';
import 'package:horecaos_mobile/src/features/profile/profile_routes.dart';
import 'package:horecaos_mobile/src/features/profile/settings/language_choice_store.dart';
import 'package:horecaos_mobile/src/features/profile/settings/locale_preference.dart';
import 'package:horecaos_mobile/src/l10n/generated/app_localizations.dart';
import 'package:horecaos_mobile/src/l10n/supported_locales.dart';

/// The tenant and brand every test uses.
const CustomerScope testScope = CustomerScope(
  tenantId: '018f0000-0000-7000-8000-000000000001',
  brandId: '018f0000-0000-7000-8000-000000000002',
);

const CustomerAccount testAccount = CustomerAccount(
  accountId: '018f0000-0000-7000-8000-00000000000a',
  identityPolicy: 'TENANT_SHARED',
  createdOnThisResolve: false,
);

/// Resolves to a fixed account, or fails.
final class FakeAccounts implements CustomerAccountRepository {
  FakeAccounts({this.account = testAccount, this.failure});

  final CustomerAccount account;

  /// Thrown instead of resolving. Cleared by a test that wants the next
  /// attempt to succeed.
  Object? failure;

  int calls = 0;
  final List<IdempotencyKey> keys = <IdempotencyKey>[];

  @override
  Future<CustomerAccount> resolve({
    required IdempotencyKey idempotencyKey,
  }) async {
    calls++;
    keys.add(idempotencyKey);
    final Object? thrown = failure;
    if (thrown != null) {
      throw thrown;
    }
    return account;
  }
}

/// An address repository with no network behind it.
final class FakeAddresses implements SavedAddressRepository {
  FakeAddresses({
    List<SavedAddress>? addresses,
    this.supportsReplace = false,
    this.supportsRemove = false,
    this.failure,
  }) : addresses = addresses ?? <SavedAddress>[];

  List<SavedAddress> addresses;

  @override
  final bool supportsReplace;

  @override
  final bool supportsRemove;

  Object? failure;

  final List<AddressDraft> added = <AddressDraft>[];
  final List<String> removed = <String>[];
  final List<IdempotencyKey> keys = <IdempotencyKey>[];

  @override
  Future<List<SavedAddress>> list() async {
    final Object? thrown = failure;
    if (thrown != null) {
      throw thrown;
    }
    return addresses;
  }

  @override
  Future<String> add(
    AddressDraft draft, {
    required IdempotencyKey idempotencyKey,
  }) async {
    added.add(draft);
    keys.add(idempotencyKey);
    final Object? thrown = failure;
    if (thrown != null) {
      throw thrown;
    }
    return 'new-address';
  }

  @override
  Future<void> replace(
    String addressId,
    AddressDraft draft, {
    required IdempotencyKey idempotencyKey,
  }) async {
    added.add(draft);
    keys.add(idempotencyKey);
  }

  @override
  Future<void> remove(
    String addressId, {
    required IdempotencyKey idempotencyKey,
  }) async {
    final Object? thrown = failure;
    if (thrown != null) {
      throw thrown;
    }
    removed.add(addressId);
    addresses = addresses
        .where((SavedAddress address) => address.id != addressId)
        .toList();
  }
}

/// A record of one preference write.
final class PreferenceWrite {
  const PreferenceWrite(this.notificationClass, this.channel, this.enabled);

  final NotificationClass notificationClass;
  final NotificationChannel channel;
  final bool enabled;
}

final class FakeNotifications implements NotificationPreferenceRepository {
  FakeNotifications({NotificationPreferences? preferences, this.failure})
    : preferences = preferences ?? const NotificationPreferences.empty();

  NotificationPreferences preferences;
  Object? failure;

  final List<PreferenceWrite> writes = <PreferenceWrite>[];

  @override
  Future<NotificationPreferences> list() async {
    final Object? thrown = failure;
    if (thrown != null) {
      throw thrown;
    }
    return preferences;
  }

  @override
  Future<void> set({
    required NotificationClass notificationClass,
    required NotificationChannel channel,
    required bool enabled,
    required IdempotencyKey idempotencyKey,
    String? brandId,
  }) async {
    final Object? thrown = failure;
    if (thrown != null) {
      throw thrown;
    }
    writes.add(PreferenceWrite(notificationClass, channel, enabled));
  }
}

/// A profile area wired entirely to fakes.
ProfileArea fakeArea({
  FakeAccounts? accounts,
  FakeAddresses? addresses,
  FakeNotifications? notifications,
  LanguageChoiceStore? languageStore,
  AuthSession? session,
}) {
  final FakeAddresses addressRepository = addresses ?? FakeAddresses();
  final FakeNotifications notificationRepository =
      notifications ?? FakeNotifications();
  return ProfileArea(
    customer: testScope,
    session: session,
    locale: LocalePreference(
      store: languageStore ?? InMemoryLanguageChoiceStore(),
    ),
    accounts: accounts ?? FakeAccounts(),
    addresses: (String _) => addressRepository,
    notifications: (String _) => notificationRepository,
  );
}

/// A session that is signed in, over a token endpoint that answers.
AuthSession testSession() {
  final AuthConfig config = AuthConfig(
    issuer: Uri.parse('https://id.example.test/realms/horecaos'),
    clientId: 'horecaos-mobile',
    redirectUri: Uri.parse('uz.horecaos.mobile://oauth/callback'),
    callbackUrlScheme: 'uz.horecaos.mobile',
  );
  return AuthSession(
    config: config,
    tokenEndpoint: TokenEndpoint(
      config: config,
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
    refreshTokens: InMemoryRefreshTokenStore('rt_1'),
  );
}

/// Mounts the profile area's real routes at [location].
///
/// The routes are the production ones rather than a widget hoisted out of them,
/// so a test also proves that the paths resolve and that the back arrow has a
/// parent page to return to.
Future<AppLocalizations> pumpProfile(
  WidgetTester tester, {
  required ProfileArea area,
  String location = '/profile',
  Object? extra,
  Locale locale = SupportedLocales.english,
  AuthSession? session,
}) async {
  // A tall surface, so a settings list or a long form is entirely in the tree.
  // `ListView` builds only what the viewport shows, and a test that cannot see
  // a control cannot tap it; scrolling in every test would be noise around the
  // thing actually being asserted.
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(420, 2400);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final AuthSession activeSession = session ?? testSession();
  final GoRouter router = GoRouter(
    initialLocation: location,
    initialExtra: extra,
    routes: ProfileRoutes.routes(area),
  );
  addTearDown(router.dispose);

  await tester.pumpWidget(
    AppScope(
      session: activeSession,
      api: HorecaOSApiClient(
        baseUri: Uri.parse('https://api.example.test'),
        httpClient: MockClient(
          (http.Request request) async =>
              http.Response('{}', 200, headers: <String, String>{
                'content-type': 'application/json',
              }),
        ),
        tokens: activeSession,
      ),
      child: MaterialApp.router(
        routerConfig: router,
        theme: HorecaOSTheme.light(),
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: SupportedLocales.all,
      ),
    ),
  );
  await tester.pumpAndSettle();
  return AppLocalizations.delegate.load(locale);
}
