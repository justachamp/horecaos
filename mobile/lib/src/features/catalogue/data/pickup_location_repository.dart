import '../../../api/api_client.dart';
import 'pickup_location.dart';

/// Reads the public, nearest-first pickup branch projection.
final class PickupLocationRepository {
  // ignore: prefer_initializing_formals
  const PickupLocationRepository({required QoidaApiClient api}) : _api = api;

  final QoidaApiClient _api;

  /// The branch options around [point]. The endpoint accepts an unauthenticated
  /// request; going through [QoidaApiClient] still gives it the same timeout,
  /// correlation identifier and problem decoding as an authenticated request.
  Future<List<PickupLocation>> nearby({
    required PickupSearchPoint point,
    int limit = 10,
  }) async {
    final ApiResponse<PickupLocations> response = await _api
        .get<PickupLocations>(
          '/api/v1/storefront/pickup-locations',
          query: <String, String>{
            'lat': point.latitude.toString(),
            'lon': point.longitude.toString(),
            'limit': limit.toString(),
          },
          decode: PickupLocations.fromJson,
        );
    return response.value.locations;
  }
}
