import '../../../api/api_client.dart';
import 'catalogue_scope.dart';
import 'menu.dart';

/// Reads the published menu.
///
/// One method, because the storefront catalog surface is one endpoint. There is
/// no per-category call and no per-product call: the server returns the whole
/// published menu for a location in one response, and it says so by giving that
/// response a 30 second `Cache-Control` and an `ETag` of the publication
/// identifier.
///
/// It goes through [HorecaOSApiClient] rather than `package:http` directly, which
/// is not a style preference: that client is where the correlation identifier,
/// the token refresh, Problem Details decoding and telemetry redaction live.
final class MenuRepository {
  // Not an initialising formal because a named parameter cannot be private,
  // and the client is nobody else's to reach.
  // ignore: prefer_initializing_formals
  const MenuRepository({required HorecaOSApiClient api}) : _api = api;

  final HorecaOSApiClient _api;

  /// The live menu for [scope], with names in [locale].
  ///
  /// [locale] is the customer's language code — `ru`, `uz` or `en`. The server
  /// resolves a name in it and falls back to any published name rather than
  /// failing, so the returned [StorefrontMenu.locale] may differ from what was
  /// asked for.
  ///
  /// Throws [ApiException] with a 404 when the brand has never published; that
  /// is a different answer from an empty menu and the screens tell them apart.
  Future<StorefrontMenu> menu({
    required CatalogueScope scope,
    required String locale,
  }) async {
    final ApiResponse<StorefrontMenu> response = await _api.get<StorefrontMenu>(
      scope.menuPath,
      query: <String, String>{'locale': locale},
      decode: StorefrontMenu.fromJson,
    );
    return response.value;
  }
}
