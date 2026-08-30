import 'dart:ui' show Locale;

/// The locales this application ships (ADR 0035).
///
/// The script subtag on Uzbek is load-bearing. uz-Latn and uz-Cyrl are not the
/// same locale, they are not mutually legible at a glance, and the archived
/// application's bare `uz` was ambiguous about which one it meant. Carrying the
/// subtag means a future uz-Cyrl is an addition rather than a migration.
///
/// The subtag lives here and not on the ARB file, which is `app_uz.arb`.
/// Flutter's `gen-l10n` refuses to generate at all when a script-qualified ARB
/// has no base locale beside it, and `AppLocalizations.delegate` matches on
/// language code regardless — so a device asking for uz-Latn resolves to this
/// entry and reads `app_uz.arb`. Adding uz-Cyrl later means an `app_uz_Cyrl.arb`
/// alongside the base, which is the arrangement gen-l10n is asking for.
abstract final class SupportedLocales {
  static const Locale russian = Locale('ru');
  static const Locale uzbekLatin = Locale.fromSubtags(
    languageCode: 'uz',
    scriptCode: 'Latn',
  );
  static const Locale english = Locale('en');

  /// Order matters: this is also the fallback order, and the first entry is
  /// what a device with no matching locale gets.
  ///
  /// Russian first, because it is the majority reading language of this
  /// client's existing customers. English is present for completeness and is
  /// not the default for anybody.
  static const List<Locale> all = <Locale>[russian, uzbekLatin, english];

  /// The `ui_locales` value for the Keycloak login page.
  ///
  /// Keycloak takes a BCP 47 tag; `Locale.toLanguageTag()` produces `uz-Latn`,
  /// which Keycloak resolves to its `uz` theme. A realm without an `uz` theme
  /// falls back to its own default, which is a cosmetic mismatch and not a
  /// failure.
  static String tagFor(Locale locale) => locale.toLanguageTag();
}
