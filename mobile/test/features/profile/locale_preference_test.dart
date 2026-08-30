import 'dart:ui' show Locale;

import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/features/profile/settings/language_choice_store.dart';
import 'package:horecaos_mobile/src/features/profile/settings/locale_preference.dart';
import 'package:horecaos_mobile/src/l10n/supported_locales.dart';

/// A store that fails everything, for the paths that must survive it.
final class _BrokenStore implements LanguageChoiceStore {
  @override
  Future<String?> read() async => throw StateError('keystore unavailable');

  @override
  Future<void> write(String languageTag) async =>
      throw StateError('keystore unavailable');

  @override
  Future<void> clear() async => throw StateError('keystore unavailable');
}

void main() {
  test('with nothing stored, the device language is followed', () async {
    final LocalePreference preference = LocalePreference(
      store: InMemoryLanguageChoiceStore(),
    );

    await preference.load();

    expect(preference.selected, isNull);
    expect(preference.isLoaded, isTrue);
  });

  test('a chosen language survives a restart', () async {
    final InMemoryLanguageChoiceStore store = InMemoryLanguageChoiceStore();
    final LocalePreference first = LocalePreference(store: store);
    await first.load();

    await first.select(SupportedLocales.russian);

    final LocalePreference afterRestart = LocalePreference(store: store);
    await afterRestart.load();
    expect(afterRestart.selected, SupportedLocales.russian);
  });

  test('the script subtag survives the round trip', () async {
    // uz-Latn and uz-Cyrl are not the same locale. Storing a bare `uz` would
    // make a future uz-Cyrl a migration rather than an addition.
    final InMemoryLanguageChoiceStore store = InMemoryLanguageChoiceStore();
    final LocalePreference preference = LocalePreference(store: store);
    await preference.load();

    await preference.select(SupportedLocales.uzbekLatin);

    expect(await store.read(), 'uz-Latn');
    final LocalePreference afterRestart = LocalePreference(store: store);
    await afterRestart.load();
    expect(afterRestart.selected, SupportedLocales.uzbekLatin);
  });

  test('going back to the device language clears the stored choice', () async {
    final InMemoryLanguageChoiceStore store = InMemoryLanguageChoiceStore();
    final LocalePreference preference = LocalePreference(store: store);
    await preference.load();
    await preference.select(SupportedLocales.english);

    await preference.select(null);

    expect(preference.selected, isNull);
    expect(await store.read(), isNull);
  });

  test('choosing notifies, so the application root can rebuild', () async {
    final LocalePreference preference = LocalePreference(
      store: InMemoryLanguageChoiceStore(),
    );
    await preference.load();
    int notifications = 0;
    preference.addListener(() => notifications++);

    await preference.select(SupportedLocales.russian);
    // The same choice again is not a change and must not rebuild the tree.
    await preference.select(SupportedLocales.russian);

    expect(notifications, 1);
  });

  test('a locale the application does not ship is refused', () async {
    final LocalePreference preference = LocalePreference(
      store: InMemoryLanguageChoiceStore(),
    );
    await preference.load();

    // Trusting the caller ends with a customer stuck on a locale with no
    // translations behind it, reading the template language on every screen.
    expect(
      () => preference.select(const Locale('fr')),
      throwsArgumentError,
    );
  });

  test('a stored value the application no longer ships is ignored', () async {
    // A tag written by a future build, or a corrupted one. Reconstructing a
    // Locale from it would produce one with no ARB file behind it, and
    // gen_l10n answers that with the template language, silently.
    final LocalePreference preference = LocalePreference(
      store: InMemoryLanguageChoiceStore('uz-Cyrl'),
    );

    await preference.load();

    expect(preference.selected, isNull);
  });

  test('a keystore that cannot be read does not stop the application', () async {
    final LocalePreference preference = LocalePreference(store: _BrokenStore());

    await preference.load();

    expect(preference.isLoaded, isTrue);
    expect(preference.selected, isNull);
  });

  test('a failed write still applies the choice for this session', () async {
    final LocalePreference preference = LocalePreference(store: _BrokenStore());
    await preference.load();

    await preference.select(SupportedLocales.russian);

    // Losing it at the next launch is worse than nothing, and it is still not
    // something to throw at a settings screen: the customer can see the
    // language they picked, and there is nothing they could do about the
    // keystore.
    expect(preference.selected, SupportedLocales.russian);
  });
}
