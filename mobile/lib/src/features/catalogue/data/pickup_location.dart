import 'catalogue_scope.dart';

/// A coordinate used to discover a nearby pickup branch.
///
/// The mobile app currently starts every customer at the configured Tashkent
/// point. It is deliberately a value handed to the storefront feature, rather
/// than a global position service: asking for device location is a separate
/// consent and product decision.
final class PickupSearchPoint {
  const PickupSearchPoint({required this.latitude, required this.longitude})
    : assert(latitude >= -90 && latitude <= 90),
      assert(longitude >= -180 && longitude <= 180);

  final double latitude;
  final double longitude;
}

/// One public branch returned by the pickup-location discovery endpoint.
///
/// The three opaque IDs are not guessed from a slug or name. The server chose
/// this branch because it has a published storefront menu, and [catalogueScope]
/// carries exactly that identity into the menu request.
final class PickupLocation {
  const PickupLocation({
    required this.tenantId,
    required this.brandId,
    required this.locationId,
    required this.brandName,
    required this.locationName,
    required this.addressLine,
    required this.district,
    required this.city,
    required this.distanceMeters,
    required this.available,
    required this.reason,
    required this.acceptsScheduledOrders,
    required this.preparationMinutes,
  });

  final String tenantId;
  final String brandId;
  final String locationId;
  final String brandName;
  final String locationName;
  final String? addressLine;
  final String? district;
  final String? city;
  final int distanceMeters;

  /// Whether pickup can start immediately. An unavailable branch remains
  /// browseable: the customer may still want to inspect its menu or return when
  /// it reopens, and the server will enforce availability again at checkout.
  final bool available;

  /// An operational code, intentionally never rendered as customer copy.
  final String? reason;
  final bool acceptsScheduledOrders;
  final int? preparationMinutes;

  CatalogueScope get catalogueScope => CatalogueScope(
    tenantId: tenantId,
    brandId: brandId,
    locationId: locationId,
  );

  static PickupLocation fromJson(Map<String, Object?> json) => PickupLocation(
    tenantId: _requireString(json, 'tenantId'),
    brandId: _requireString(json, 'brandId'),
    locationId: _requireString(json, 'locationId'),
    brandName: _requireString(json, 'brandName'),
    locationName: _requireString(json, 'locationName'),
    addressLine: _optionalString(json, 'addressLine'),
    district: _optionalString(json, 'district'),
    city: _optionalString(json, 'city'),
    distanceMeters: _requireInt(json, 'distanceMeters'),
    available: json['available'] == true,
    reason: _optionalString(json, 'reason'),
    acceptsScheduledOrders: json['acceptsScheduledOrders'] == true,
    preparationMinutes: _optionalInt(json, 'preparationMinutes'),
  );
}

/// The discovery endpoint envelope.
final class PickupLocations {
  const PickupLocations(this.locations);

  final List<PickupLocation> locations;

  static PickupLocations fromJson(Map<String, Object?> json) {
    final Object? value = json['locations'];
    if (value is! List) {
      throw FormatException(
        'Pickup locations field "locations" was not a list',
      );
    }
    return PickupLocations(
      value
          .map((Object? item) {
            if (item is! Map<String, Object?>) {
              throw FormatException('A pickup location was not an object');
            }
            return PickupLocation.fromJson(item);
          })
          .toList(growable: false),
    );
  }
}

String _requireString(Map<String, Object?> json, String key) {
  final Object? value = json[key];
  if (value is String && value.isNotEmpty) return value;
  throw FormatException(
    'Pickup location field "$key" was missing or not a string',
  );
}

String? _optionalString(Map<String, Object?> json, String key) {
  final Object? value = json[key];
  return value is String && value.isNotEmpty ? value : null;
}

int _requireInt(Map<String, Object?> json, String key) {
  final Object? value = json[key];
  if (value is int && value >= 0) return value;
  throw FormatException(
    'Pickup location field "$key" was missing or not an integer',
  );
}

int? _optionalInt(Map<String, Object?> json, String key) {
  final Object? value = json[key];
  return value is int ? value : null;
}
