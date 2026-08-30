/// Which brand's menu, at which branch.
///
/// Every storefront path is `/tenants/{tenantId}/brands/{brandId}/...`, and the
/// menu adds `/locations/{locationId}`. The platform's authorization and
/// idempotency interceptors both derive their scope from those path variables,
/// which is why a storefront call cannot be made without them.
///
/// **This is a constructor parameter and not a global.** `AppConfig` carries no
/// tenant, brand or location today, and adding them there is not this feature's
/// to do — the composition root has to supply them, and the branch in
/// particular is a customer choice rather than a build-time constant. Reading
/// them from `String.fromEnvironment` here would have produced a second
/// configuration object with no rule for which one wins.
final class CatalogueScope {
  const CatalogueScope({
    required this.tenantId,
    required this.brandId,
    required this.locationId,
  });

  final String tenantId;
  final String brandId;

  /// The branch. It is part of the menu's identity, not a filter on it: a
  /// location decides which variants appear and which of them are orderable,
  /// and ADR 0019 refuses to carry a cart across locations for the same reason.
  final String locationId;

  String get menuPath =>
      '/api/v1/storefront/tenants/$tenantId/brands/$brandId'
      '/locations/$locationId/menu';

  @override
  bool operator ==(Object other) =>
      other is CatalogueScope &&
      other.tenantId == tenantId &&
      other.brandId == brandId &&
      other.locationId == locationId;

  @override
  int get hashCode => Object.hash(tenantId, brandId, locationId);

  @override
  String toString() => 'CatalogueScope($tenantId/$brandId/$locationId)';
}
