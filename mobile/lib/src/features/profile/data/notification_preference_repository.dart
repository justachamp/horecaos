import '../../../api/api_client.dart';
import '../../../api/idempotency_key.dart';
import '../../../api/page.dart';
import '../customer_scope.dart';
import 'notification_preference.dart';

/// Which optional messages the customer receives (ADR 0020).
///
/// **Preferences, never consent.** Withdrawing consent is an ADR 0015 decision
/// recorded against a policy version with its evidence and its date, and it has
/// its own endpoint on the customers module. Nothing in this interface writes
/// one, and nothing in this feature calls that endpoint: a legal basis created
/// by a toggle nobody can date is not a legal basis, and the policy version a
/// consent write requires is a legal artefact this client has no business
/// inventing.
abstract interface class NotificationPreferenceRepository {
  Future<NotificationPreferences> list();

  /// Sets one preference.
  ///
  /// The platform refuses a class the customer cannot switch off, and so does
  /// the screen above this — this is the second line of defence rather than the
  /// first.
  Future<void> set({
    required NotificationClass notificationClass,
    required NotificationChannel channel,
    required bool enabled,
    required IdempotencyKey idempotencyKey,
    String? brandId,
  });
}

/// Against
/// `/api/v1/tenants/{tenantId}/customers/{accountId}/notification-preferences`.
///
/// The read is shaped against ADR 0031's collection envelope for the same
/// reason as the address read; see `HttpSavedAddressRepository` for the full
/// note. The write is a `PUT` returning 204 with no body.
final class HttpNotificationPreferenceRepository
    implements NotificationPreferenceRepository {
  const HttpNotificationPreferenceRepository({
    required this._api,
    required this._scope,
    required this._accountId,
  });

  final QoidaApiClient _api;
  final CustomerScope _scope;
  final String _accountId;

  String get _path =>
      '${_scope.accountPath(_accountId)}/notification-preferences';

  @override
  Future<NotificationPreferences> list() async {
    final ApiResponse<Page<NotificationPreference>> response = await _api
        .getPage<NotificationPreference>(
          _path,
          decodeItem: NotificationPreference.fromJson,
        );
    return NotificationPreferences(response.value.items);
  }

  @override
  Future<void> set({
    required NotificationClass notificationClass,
    required NotificationChannel channel,
    required bool enabled,
    required IdempotencyKey idempotencyKey,
    String? brandId,
  }) async {
    if (!notificationClass.isSwitchable) {
      throw ArgumentError(
        '${notificationClass.name} is not something a customer can switch off',
      );
    }
    if (!channel.isWired) {
      throw ArgumentError(
        'Nothing sends on ${channel.name}; a preference for it would mean '
        'nothing',
      );
    }
    await _api.put<void>(
      '$_path/${notificationClass.wire}/${channel.wire}',
      idempotencyKey: idempotencyKey,
      body: <String, Object?>{'brandId': brandId, 'enabled': enabled},
      // 204 with no body. The client hands back an empty map for one, so
      // there is nothing to read and nothing to invent.
      decode: (Map<String, Object?> json) {},
    );
  }
}
