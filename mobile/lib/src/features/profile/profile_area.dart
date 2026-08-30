import 'package:flutter/foundation.dart';

import '../../api/api_client.dart';
import '../../api/idempotency_key.dart';
import '../../auth/auth_session.dart';
import 'customer_scope.dart';
import 'data/customer_account.dart';
import 'data/notification_preference_repository.dart';
import 'data/saved_address_repository.dart';
import 'settings/language_choice_store.dart';
import 'settings/locale_preference.dart';

/// Everything the profile area needs, built once.
///
/// One object rather than a repository per screen because of the account
/// identifier: every address and preference path contains it, and getting it
/// costs a `POST .../resolve`, which is a **mutation** — it creates the account
/// on a first sign-in. Resolving per screen would send that mutation on every
/// navigation. It is resolved once here and shared.
///
/// Built at the composition root and passed to `ProfileRoutes.routes`, in the
/// same shape as `AuthSession`: nothing reaches it through a locator, and a test
/// supplies its own.
final class ProfileArea {
  ProfileArea({
    required this.customer,
    required this.locale,
    required this._accounts,
    required SavedAddressRepository Function(String accountId) addresses,
    required NotificationPreferenceRepository Function(String accountId)
    notifications,
    this._session,
  }) : _addressesFor = addresses,
       _notificationsFor = notifications {
    // A signed-out customer's account identifier must not survive into the next
    // customer's session. On a shared phone it would send one person's
    // addresses to another's screen, and the identifier is the only thing this
    // object holds that could do that.
    _session?.addListener(_onSessionChanged);
  }

  /// The ordinary wiring: repositories over the shared API client.
  factory ProfileArea.from({
    required HorecaOSApiClient api,
    required CustomerScope customer,
    AuthSession? session,
    LanguageChoiceStore? languageStore,
  }) {
    return ProfileArea(
      customer: customer,
      session: session,
      locale: LocalePreference(
        store: languageStore ?? const SecureLanguageChoiceStore(),
      ),
      accounts: HttpCustomerAccountRepository(api: api, scope: customer),
      addresses: (String accountId) => HttpSavedAddressRepository(
        api: api,
        scope: customer,
        accountId: accountId,
      ),
      notifications: (String accountId) =>
          HttpNotificationPreferenceRepository(
            api: api,
            scope: customer,
            accountId: accountId,
          ),
    );
  }

  final CustomerScope customer;
  final LocalePreference locale;

  final CustomerAccountRepository _accounts;
  final SavedAddressRepository Function(String accountId) _addressesFor;
  final NotificationPreferenceRepository Function(String accountId)
  _notificationsFor;
  final AuthSession? _session;

  Future<CustomerAccount>? _resolution;

  /// The resolved account, resolving it on first use.
  ///
  /// Memoised on the *future*, not on its result, so two screens opening at
  /// once share one request instead of racing to create the same account.
  ///
  /// A failed resolution is forgotten, so a retry actually retries. Memoising a
  /// failure would leave a customer looking at the same error until they killed
  /// the application.
  Future<CustomerAccount> account() => _resolution ??= _resolve();

  Future<CustomerAccount> _resolve() async {
    try {
      return await _accounts.resolve(
        // One key for one intent: resolving this session's account. The
        // client's own retry after a token refresh reuses it, so a refresh in
        // the middle of a first sign-in cannot create two accounts.
        idempotencyKey: IdempotencyKey.generate(),
      );
    } on Object catch (_) {
      _resolution = null;
      rethrow;
    }
  }

  /// The address repository for the resolved account.
  Future<SavedAddressRepository> addresses() async =>
      _addressesFor((await account()).accountId);

  /// The notification preference repository for the resolved account.
  Future<NotificationPreferenceRepository> notifications() async =>
      _notificationsFor((await account()).accountId);

  /// Forgets the resolved account.
  ///
  /// Called on sign-out and available to a test. Addresses are not forgotten
  /// here because they are never held here: they live in the state of the
  /// screen showing them and go when it does (ADR 0029).
  @visibleForTesting
  void forgetAccount() => _resolution = null;

  void _onSessionChanged() {
    if (_session?.status != AuthStatus.signedIn) {
      forgetAccount();
    }
  }

  void dispose() {
    _session?.removeListener(_onSessionChanged);
    locale.dispose();
  }
}
