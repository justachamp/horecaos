import 'dart:ui' show Locale;

import 'package:flutter/foundation.dart';

import '../../../l10n/supported_locales.dart';
import 'language_choice_store.dart';

/// The customer's chosen interface language, persisted.
///
/// A `ChangeNotifier` for the same reason `AuthSession` is one: the application
/// root has to rebuild when it changes, and Flutter already has a `Listenable`
/// contract for that. A state-management package would add a dependency without
/// adding a capability.
///
/// **Null means "follow the device".** That is a real third option and not an
/// unset field: a customer who has never chosen should get their phone's
/// language, and one who chose Russian on a Russian phone should keep Russian
/// after moving to an Uzbek phone. Storing the resolved locale instead of the
/// absence of a choice would lose that difference forever.
///
/// The interface language is the customer's. There is no English-only fallback
/// screen in this application, and there is no locale here that the application
/// does not ship translations for.
final class LocalePreference extends ChangeNotifier {
  LocalePreference({required this._store});

  final LanguageChoiceStore _store;

  Locale? _selected;
  bool _loaded = false;

  /// The chosen locale, or null to follow the device.
  Locale? get selected => _selected;

  /// Whether [load] has finished. The application root should not paint a
  /// language-dependent frame before this is true, or a returning customer sees
  /// one frame of the device's language and then a switch to theirs.
  bool get isLoaded => _loaded;

  /// Reads the stored choice. Call once at startup.
  Future<void> load() async {
    String? tag;
    try {
      tag = await _store.read();
    } on Object {
      // A keystore that cannot be read is not a reason to fail to start. The
      // customer gets the device language, which is the same outcome as never
      // having chosen, and choosing again repairs it.
      tag = null;
    }
    _selected = _match(tag);
    _loaded = true;
    notifyListeners();
  }

  /// Chooses a language, or passes null to follow the device again.
  ///
  /// Refuses a locale the application does not ship. The alternative — trusting
  /// the caller — ends with a customer stuck on a locale with no translations
  /// behind it, looking at the template language on every screen.
  Future<void> select(Locale? locale) async {
    if (locale != null && _match(locale.toLanguageTag()) == null) {
      throw ArgumentError('Not a supported locale: $locale');
    }
    if (locale == _selected) {
      return;
    }
    _selected = locale;
    notifyListeners();
    try {
      if (locale == null) {
        await _store.clear();
      } else {
        await _store.write(locale.toLanguageTag());
      }
    } on Object {
      // The choice has already been applied in memory and the customer can see
      // it. Losing it at the next launch is a worse outcome than a failed
      // write, but it is not one worth throwing an exception at a settings
      // screen over, and there is nothing useful the customer could do about
      // it.
    }
  }

  /// Matches a stored tag back to a locale this application actually ships.
  ///
  /// Never `Locale.fromSubtags` on stored text. A corrupted or stale value —
  /// `uz-Cyrl` from a future build, say — would otherwise produce a locale with
  /// no ARB file behind it, and gen_l10n's answer to that is the template
  /// language, silently.
  static Locale? _match(String? tag) {
    if (tag == null || tag.isEmpty) {
      return null;
    }
    for (final Locale candidate in SupportedLocales.all) {
      if (candidate.toLanguageTag() == tag) {
        return candidate;
      }
    }
    return null;
  }
}
