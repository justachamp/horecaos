/// Which tenant and brand this installation of the application serves.
///
/// The customer module's endpoints are tenant-scoped and the notification
/// preference endpoints are too, so nothing in this feature can build a URL
/// without these two identifiers. They are passed in from the composition root
/// rather than read from an environment constant here: `AppConfig` owns
/// environment configuration, and a second place that reads `--dart-define`
/// would be a second configuration mechanism to keep in step.
///
/// **These paths are not under `/api/v1/storefront`.** The customer module was
/// built with one controller — `CustomerController`, at
/// `/api/v1/tenants/{tenantId}/customers` — and it is a staff-shaped surface
/// with staff-shaped capabilities. ADR 0015 documents a customer self-service
/// shape (`/api/v1/customer/me`, `/api/v1/customer/me/addresses`) that has not
/// been built. This feature calls what exists rather than what is documented
/// but absent; see the README note in `saved_address_repository.dart` for what
/// that costs today.
final class CustomerScope {
  const CustomerScope({required this.tenantId, required this.brandId});

  /// The tenant whose customers these are. A UUID string, and opaque here.
  final String tenantId;

  /// The brand the customer is shopping. Required for identity resolution: in
  /// `BRAND_ISOLATED` mode an account cannot be resolved without it, and in
  /// `TENANT_SHARED` mode it selects the brand profile.
  final String brandId;

  /// `/api/v1/tenants/{tenantId}/customers`.
  String get customersPath => '/api/v1/tenants/$tenantId/customers';

  /// `/api/v1/tenants/{tenantId}/customers/{accountId}`.
  String accountPath(String accountId) => '$customersPath/$accountId';

  @override
  bool operator ==(Object other) =>
      other is CustomerScope &&
      other.tenantId == tenantId &&
      other.brandId == brandId;

  @override
  int get hashCode => Object.hash(tenantId, brandId);

  @override
  String toString() => 'CustomerScope($tenantId/$brandId)';
}
