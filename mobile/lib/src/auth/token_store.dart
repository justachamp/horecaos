import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Where the refresh token is kept between launches.
///
/// The refresh token, and nothing else. The access token never reaches this
/// interface: it lives in memory for the life of the process, exactly as it
/// does on the web surfaces.
abstract interface class RefreshTokenStore {
  Future<String?> read();
  Future<void> write(String token);
  Future<void> clear();
}

/// Keychain on iOS, the platform keystore on Android.
///
/// ADR 0035 makes this a considered difference from the web surfaces, which
/// persist nothing: a customer application that demands a fresh login every
/// session is a deleted application. The token is a bearer credential, so
/// "device storage" has to mean hardware-backed storage and not shared
/// preferences.
final class SecureRefreshTokenStore implements RefreshTokenStore {
  const SecureRefreshTokenStore({FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  /// Not namespaced by customer. There is one session on a device, and keying
  /// by subject would leave a previous customer's token behind after a sign-out
  /// on a shared phone.
  static const String _key = 'horecaos.refresh_token';

  static const IOSOptions _iosOptions = IOSOptions(
    // The default is `unlocked`, which permits a background read while the
    // device is locked. Nothing in this application refreshes in the
    // background, so the stricter class costs nothing and narrows the window in
    // which the token is readable at all.
    accessibility: KeychainAccessibility.first_unlock_this_device,
  );

  /// The package's own defaults, deliberately.
  ///
  /// flutter_secure_storage 11 replaced the Jetpack Security
  /// `encryptedSharedPreferences` path — which Google deprecated — with
  /// RSA-OAEP key wrapping over AES-GCM, and made it the default. Setting the
  /// old flag here would either fail to compile or opt back into the
  /// deprecated implementation, so the defaults are taken as they come.
  static const AndroidOptions _androidOptions = AndroidOptions();

  @override
  Future<String?> read() => _storage.read(
    key: _key,
    iOptions: _iosOptions,
    aOptions: _androidOptions,
  );

  @override
  Future<void> write(String token) => _storage.write(
    key: _key,
    value: token,
    iOptions: _iosOptions,
    aOptions: _androidOptions,
  );

  @override
  Future<void> clear() => _storage.delete(
    key: _key,
    iOptions: _iosOptions,
    aOptions: _androidOptions,
  );
}

/// For tests and for a build with no platform channels.
final class InMemoryRefreshTokenStore implements RefreshTokenStore {
  InMemoryRefreshTokenStore([this._token]);

  String? _token;

  @override
  Future<String?> read() async => _token;

  @override
  Future<void> write(String token) async => _token = token;

  @override
  Future<void> clear() async => _token = null;
}
