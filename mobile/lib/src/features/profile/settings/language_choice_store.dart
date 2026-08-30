import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Where the customer's chosen interface language is kept between launches.
///
/// **Deliberately narrow.** The interface is one value — a BCP 47 language tag —
/// and not a key-value store, because a general settings store in this feature
/// would be somewhere an address could end up. An address must not be written to
/// the device at all (ADR 0029), and the cheapest way to keep that true is to
/// have nowhere to put one.
abstract interface class LanguageChoiceStore {
  /// The stored tag, or null when the customer has never chosen.
  Future<String?> read();

  Future<void> write(String languageTag);

  /// Returns to following the device's language.
  Future<void> clear();
}

/// Keychain on iOS, the platform keystore on Android.
///
/// The same store the refresh token uses, which is heavier than a language
/// choice needs. It is used anyway because it is the only on-device store this
/// application already depends on, and adding `shared_preferences` for one
/// string would be a new dependency, a new plugin registration on two platforms,
/// and a second answer to "where do settings live". If a second unprotected
/// setting ever appears, that is the moment to reach for the lighter store —
/// not before.
final class SecureLanguageChoiceStore implements LanguageChoiceStore {
  const SecureLanguageChoiceStore({FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  static const String _key = 'qoida.language_choice';

  /// Not `first_unlock_this_device`, which is what the refresh token uses.
  ///
  /// The language choice is read during the first frame, and a stricter class
  /// than the token's would be pointless on a value that is not a credential.
  /// The default accessibility is taken deliberately rather than by omission.
  static const IOSOptions _iosOptions = IOSOptions();
  static const AndroidOptions _androidOptions = AndroidOptions();

  @override
  Future<String?> read() =>
      _storage.read(key: _key, iOptions: _iosOptions, aOptions: _androidOptions);

  @override
  Future<void> write(String languageTag) => _storage.write(
    key: _key,
    value: languageTag,
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
final class InMemoryLanguageChoiceStore implements LanguageChoiceStore {
  InMemoryLanguageChoiceStore([this._tag]);

  String? _tag;

  @override
  Future<String?> read() async => _tag;

  @override
  Future<void> write(String languageTag) async => _tag = languageTag;

  @override
  Future<void> clear() async => _tag = null;
}
