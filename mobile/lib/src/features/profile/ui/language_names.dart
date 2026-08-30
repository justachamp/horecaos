import 'dart:ui' show Locale;

import '../../../l10n/supported_locales.dart';

/// What each language is called, in that language.
///
/// **Deliberately not ARB messages.** Every other user-visible string in this
/// application is a message in all three locales; these are endonyms, and an
/// endonym is not translated — a Russian speaker looking for Uzbek looks for
/// "O'zbekcha", not for "узбекский". Every locale would therefore carry the same
/// three values, which `test/l10n/arb_parity_test.dart` correctly refuses: it
/// fails on a translation identical to the English, because that is what an
/// untranslated string looks like. The exception belongs to a proper noun, and
/// it is stated here rather than smuggled past that gate.
///
/// The same reasoning as `appTitle`, which is in the ARB files only because it
/// predates this list and is exempted there by name.
abstract final class LanguageNames {
  static const Map<String, String> _byLanguageCode = <String, String>{
    'ru': 'Русский',
    'uz': "O'zbekcha",
    'en': 'English',
  };

  /// The endonym for a locale the application ships.
  ///
  /// Falls back to the BCP 47 tag rather than to a language the caller did not
  /// ask for. A picker that silently renamed a locale would be worse than one
  /// showing `uz-Cyrl`.
  static String of(Locale locale) =>
      _byLanguageCode[locale.languageCode] ?? locale.toLanguageTag();

  /// The locales offered in the picker, in the order they are offered.
  static List<Locale> get offered => SupportedLocales.all;
}
