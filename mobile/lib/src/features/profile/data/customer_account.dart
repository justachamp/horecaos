import '../../../api/api_client.dart';
import '../../../api/idempotency_key.dart';
import '../customer_scope.dart';

/// The customer's durable account in this tenant (ADR 0015).
///
/// Not the Keycloak subject. ADR 0015 is explicit that a JWT is never a
/// customer record: the account is the commercial identity, it survives a
/// changed phone number, and it is what an order, an address and a consent
/// decision hang off.
///
/// Deliberately thin. `customer.customer_accounts` also holds `display_name`,
/// `preferred_locale` and `preferred_timezone`, and no endpoint returns them —
/// `POST /resolve` answers with the account identifier, whether it was just
/// created, and the identity policy, and there is no read of the account row.
/// Inventing fields the server does not send is how a client ships against a
/// contract that does not exist, so this record carries exactly what arrives.
final class CustomerAccount {
  const CustomerAccount({
    required this.accountId,
    required this.identityPolicy,
    required this.createdOnThisResolve,
  });

  /// A UUID. Pseudonymous — it names a row, not a person — so it is safe to
  /// show a customer for a support conversation and safe to put in telemetry,
  /// unlike everything else this feature touches (ADR 0029).
  final String accountId;

  /// `TENANT_SHARED` or `BRAND_ISOLATED`, from the tenant's versioned
  /// `CustomerIdentityPolicy`. Carried because the value decides whether one
  /// account spans a tenant's brands, and a future screen that says so needs
  /// it. Nothing branches on it today, and an unrecognised value is kept
  /// verbatim rather than rejected.
  final String identityPolicy;

  /// True when this call created the account — a first sign-in.
  final bool createdOnThisResolve;

  static CustomerAccount fromJson(Map<String, Object?> json) {
    final Object? accountId = json['accountId'];
    if (accountId is! String || accountId.isEmpty) {
      throw const FormatException('Resolve response carried no accountId');
    }
    return CustomerAccount(
      accountId: accountId,
      identityPolicy: json['identityPolicy'] as String? ?? '',
      createdOnThisResolve: json['created'] as bool? ?? false,
    );
  }

  @override
  String toString() => 'CustomerAccount($accountId)';
}

/// Resolving the signed-in principal to an account.
abstract interface class CustomerAccountRepository {
  /// Resolves, creating the account on a first sign-in.
  ///
  /// The identity is taken from the caller's own token server-side. There is
  /// no parameter for it here and there must not be: a client that could name
  /// an account could read somebody else's.
  Future<CustomerAccount> resolve({required IdempotencyKey idempotencyKey});
}

/// `POST /api/v1/tenants/{tenantId}/customers/resolve`.
final class HttpCustomerAccountRepository implements CustomerAccountRepository {
  const HttpCustomerAccountRepository({
    required this._api,
    required this._scope,
  });

  final QoidaApiClient _api;
  final CustomerScope _scope;

  @override
  Future<CustomerAccount> resolve({
    required IdempotencyKey idempotencyKey,
  }) async {
    // A mutation, because a first sign-in creates a row — so the platform's
    // idempotency interceptor requires the key, and the key is what stops a
    // retry over a dropped connection from racing the unique index on the
    // principal link.
    final ApiResponse<CustomerAccount> response = await _api
        .post<CustomerAccount>(
          '${_scope.customersPath}/resolve',
          idempotencyKey: idempotencyKey,
          body: <String, Object?>{'brandId': _scope.brandId},
          decode: CustomerAccount.fromJson,
        );
    return response.value;
  }
}
