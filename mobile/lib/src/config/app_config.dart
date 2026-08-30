import '../auth/auth_config.dart';

/// Everything environment-specific, in one object.
///
/// Read from `--dart-define` rather than from a bundled `.env`: a compile-time
/// constant cannot be edited in a shipped binary, and there is nothing secret
/// here to protect anyway. A mobile client holds no Keycloak secret and no
/// provider credential (ADR 0028); if a value ever needs protecting, it does
/// not belong in this file.
final class AppConfig {
  const AppConfig({
    required this.apiBaseUri,
    required this.auth,
    required this.initialPickupLatitude,
    required this.initialPickupLongitude,
  });

  /// The platform root. Surface prefixes such as `/api/v1/storefront` are part
  /// of the paths callers pass, because one screen may touch two surfaces.
  final Uri apiBaseUri;

  final AuthConfig auth;

  /// The non-consented fallback used to discover a customer's first pickup
  /// branch. Device geolocation is deliberately not requested by this build.
  final double initialPickupLatitude;
  final double initialPickupLongitude;

  /// The defaults are the local development stack described in the platform
  /// repository, and they are wrong for every other environment on purpose: a
  /// build that forgets `--dart-define` should fail to reach anything rather
  /// than quietly point at production.
  factory AppConfig.fromEnvironment() {
    const String apiBase = String.fromEnvironment(
      'QOIDA_API_BASE_URI',
      defaultValue: 'http://localhost:8080',
    );
    const String issuer = String.fromEnvironment(
      'QOIDA_OIDC_ISSUER_URI',
      defaultValue: 'http://localhost:8081/realms/qoida',
    );
    const String clientId = String.fromEnvironment(
      'QOIDA_OIDC_CLIENT_ID',
      defaultValue: 'qoida-mobile',
    );
    const String redirectScheme = String.fromEnvironment(
      'QOIDA_OIDC_REDIRECT_SCHEME',
      defaultValue: 'uz.qoida.mobile',
    );
    const String pickupLatitude = String.fromEnvironment(
      'QOIDA_INITIAL_PICKUP_LATITUDE',
      defaultValue: '41.311341',
    );
    const String pickupLongitude = String.fromEnvironment(
      'QOIDA_INITIAL_PICKUP_LONGITUDE',
      defaultValue: '69.282722',
    );

    final double initialPickupLatitude = _coordinate(
      pickupLatitude,
      minimum: -90,
      maximum: 90,
      name: 'QOIDA_INITIAL_PICKUP_LATITUDE',
    );
    final double initialPickupLongitude = _coordinate(
      pickupLongitude,
      minimum: -180,
      maximum: 180,
      name: 'QOIDA_INITIAL_PICKUP_LONGITUDE',
    );

    return AppConfig(
      apiBaseUri: Uri.parse(apiBase),
      initialPickupLatitude: initialPickupLatitude,
      initialPickupLongitude: initialPickupLongitude,
      auth: AuthConfig(
        issuer: Uri.parse(issuer),
        clientId: clientId,
        // A reverse-DNS scheme rather than something short and guessable.
        // Any application can claim a custom scheme on both platforms, and a
        // collision is an authorization code delivered to somebody else. This
        // string must be registered on the Keycloak client as an exact
        // allowlisted redirect URI and declared in both platform manifests.
        redirectUri: Uri.parse('$redirectScheme://oauth/callback'),
        callbackUrlScheme: redirectScheme,
      ),
    );
  }

  static double _coordinate(
    String value, {
    required double minimum,
    required double maximum,
    required String name,
  }) {
    final double parsed = double.parse(value);
    if (!parsed.isFinite || parsed < minimum || parsed > maximum) {
      throw FormatException('$name must be between $minimum and $maximum');
    }
    return parsed;
  }
}
