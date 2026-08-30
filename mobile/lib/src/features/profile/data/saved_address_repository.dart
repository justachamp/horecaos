import '../../../api/api_client.dart';
import '../../../api/idempotency_key.dart';
import '../../../api/page.dart';
import '../customer_scope.dart';
import 'saved_address.dart';

/// The customer's saved addresses.
///
/// Reading one is a **reveal** on the platform: the address lines live inside an
/// encrypted document, and decrypting them requires a stated purpose that the
/// platform records as an audit fact (ADR 0029). That is why [list] sends
/// [revealPurpose] and why there is no way to call it without one.
abstract interface class SavedAddressRepository {
  /// Whether this implementation can change an existing address.
  ///
  /// False against today's platform. See [HttpSavedAddressRepository] for why,
  /// and note that the list screen reads this rather than assuming: an
  /// affordance that cannot complete is worse than an absent one.
  bool get supportsReplace;

  /// Whether this implementation can delete an address.
  bool get supportsRemove;

  Future<List<SavedAddress>> list();

  /// Returns the new address identifier.
  Future<String> add(
    AddressDraft draft, {
    required IdempotencyKey idempotencyKey,
  });

  Future<void> replace(
    String addressId,
    AddressDraft draft, {
    required IdempotencyKey idempotencyKey,
  });

  Future<void> remove(
    String addressId, {
    required IdempotencyKey idempotencyKey,
  });
}

/// Raised when the platform has no endpoint for an operation.
///
/// Distinct from an `ApiException` on purpose: a 404 from a request that was
/// actually sent and an operation that was never worth sending are different
/// facts, and collapsing them would leave a reader of a bug report unable to
/// tell whether the customer's address was deleted.
final class AddressOperationUnavailable implements Exception {
  const AddressOperationUnavailable(this.operation);

  final String operation;

  @override
  String toString() =>
      'AddressOperationUnavailable($operation: the platform exposes no '
      'endpoint for it. See CustomerController.)';
}

/// Against `/api/v1/tenants/{tenantId}/customers/{accountId}/addresses`.
///
/// **Two things about this endpoint family are worth stating plainly, because
/// both are contract facts rather than client bugs.**
///
/// *There is no update and no delete.* `CustomerController` exposes
/// `POST .../addresses` and `GET .../addresses` and nothing else. ADR 0015's own
/// API section lists `DELETE /api/v1/customer/me/addresses/{addressId}`, and
/// neither that path nor a tenant-scoped equivalent has been built. So
/// [supportsReplace] and [supportsRemove] are false, and [replace] and [remove]
/// refuse locally instead of sending a request to a URL nobody serves. Guessing
/// a path would produce a 404 that reads to a customer as "your address could
/// not be deleted" when the truth is that deletion was never wired.
///
/// *The reads are shaped against ADR 0031, not against today's controller.*
/// ADR 0031 fixes one collection representation — `{"items": [...],
/// "nextCursor": null}` — and [HorecaOSApiClient] implements exactly that.
/// `CustomerController` returns a bare JSON array, which disagrees with the
/// convention its own platform sets. The contract of record is ADR 0031 and the
/// published OpenAPI document (ADR 0035), so this client reads the envelope; a
/// bare array will fail to decode until the server wraps it. Building the other
/// way — decoding an array by hand around the shared client — would encode a
/// deviation into four consumers and make the convention unenforceable.
final class HttpSavedAddressRepository implements SavedAddressRepository {
  const HttpSavedAddressRepository({
    required this._api,
    required this._scope,
    required this._accountId,
  });

  final HorecaOSApiClient _api;
  final CustomerScope _scope;
  final String _accountId;

  /// Recorded by the platform against every decryption.
  ///
  /// It is deliberately not "support" or "operations": the difference between an
  /// agent reading fifty thousand addresses and a customer opening their own is
  /// exactly what this string exists to preserve, and a self-service read that
  /// borrowed a staff purpose would make that audit trail useless.
  static const String revealPurpose = 'customer_self_service';

  String get _path => '${_scope.accountPath(_accountId)}/addresses';

  @override
  bool get supportsReplace => false;

  @override
  bool get supportsRemove => false;

  @override
  Future<List<SavedAddress>> list() async {
    final ApiResponse<Page<SavedAddress>> response = await _api
        .getPage<SavedAddress>(
          _path,
          decodeItem: SavedAddress.fromJson,
          query: const <String, String>{'purpose': revealPurpose},
        );
    // No cursor is followed. A customer has a handful of addresses, the
    // endpoint accepts no cursor parameter, and paging a list this size would
    // be machinery with nothing to do. If a `nextCursor` ever arrives it is
    // ignored rather than silently dropping addresses without anyone noticing —
    // the screen shows what came back and the next page is a change to make
    // here, deliberately.
    return response.value.items;
  }

  @override
  Future<String> add(
    AddressDraft draft, {
    required IdempotencyKey idempotencyKey,
  }) async {
    final AddressDraftProblem? problem = draft.problem;
    if (problem != null) {
      // The platform refuses these too. Refusing here as well keeps a
      // malformed draft from becoming a 400 the customer reads as a failure of
      // the application rather than of the form.
      throw ArgumentError('Address draft is not sendable: ${problem.name}');
    }
    final ApiResponse<String> response = await _api.post<String>(
      _path,
      idempotencyKey: idempotencyKey,
      body: draft.toJson(),
      decode: (Map<String, Object?> json) {
        final Object? id = json['id'];
        if (id is! String || id.isEmpty) {
          throw const FormatException('Address creation returned no id');
        }
        return id;
      },
    );
    return response.value;
  }

  @override
  Future<void> replace(
    String addressId,
    AddressDraft draft, {
    required IdempotencyKey idempotencyKey,
  }) async => throw const AddressOperationUnavailable('replace');

  @override
  Future<void> remove(
    String addressId, {
    required IdempotencyKey idempotencyKey,
  }) async => throw const AddressOperationUnavailable('remove');
}
