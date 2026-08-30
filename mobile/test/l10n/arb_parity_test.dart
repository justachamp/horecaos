import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Makes a missing translation fail the build.
///
/// `flutter gen-l10n` does not. Faced with a message that exists in the
/// template and not in a locale, it silently emits the template's string for
/// that locale — so a forgotten Uzbek translation ships as English and nobody
/// finds out until a customer does. `l10n.yaml` writes the list to
/// `l10n_untranslated.json`, which is a file nobody reads.
///
/// This is the gate. It runs in `flutter test`, so it runs in CI, and it fails
/// loudly with the missing keys named.
void main() {
  const String template = 'app_en.arb';
  const List<String> translations = <String>['app_ru.arb', 'app_uz.arb'];
  final Directory arbDir = Directory('lib/src/l10n');

  Map<String, Object?> read(String name) =>
      jsonDecode(File('${arbDir.path}/$name').readAsStringSync())
          as Map<String, Object?>;

  /// Message keys, excluding `@@locale` and the `@key` attribute entries.
  Set<String> messages(Map<String, Object?> arb) =>
      arb.keys.where((String key) => !key.startsWith('@')).toSet();

  final Map<String, Object?> en = read(template);

  test('the template has messages', () {
    expect(messages(en), isNotEmpty);
  });

  test('every locale the application ships has an ARB file', () {
    // ADR 0035 fixes the locales at ru, uz-Latn and en. A locale in
    // SupportedLocales with no ARB behind it renders as English.
    final Set<String> present = arbDir
        .listSync()
        .whereType<File>()
        .map((File file) => file.uri.pathSegments.last)
        .where((String name) => name.endsWith('.arb'))
        .toSet();
    expect(present, containsAll(<String>[template, ...translations]));
  });

  for (final String name in translations) {
    group(name, () {
      final Map<String, Object?> arb = read(name);

      test('declares its locale', () {
        expect(arb['@@locale'], isNotNull);
      });

      test('translates every message in the template', () {
        final Set<String> missing = messages(en).difference(messages(arb));
        expect(
          missing,
          isEmpty,
          reason:
              'Untranslated in $name: ${missing.join(', ')}. gen_l10n would '
              'have shipped the English string for each of these.',
        );
      });

      test('has no message the template does not have', () {
        // An extra key is a message that was renamed in the template and left
        // behind here — dead weight that looks like a translation.
        final Set<String> extra = messages(arb).difference(messages(en));
        expect(extra, isEmpty, reason: 'Not in the template: ${extra.join(', ')}');
      });

      test('has no untranslated value left identical to the English', () {
        const Set<String> sameEverywhere = <String>{
          // The brand name is the same in every locale by design.
          'appTitle',
          // An English-reading customer in Tashkent reads a price as
          // "84 000 so'm", the same as an Uzbek-reading one. Rendering it as
          // "UZS" for en would be a different price format, not a translation.
          'currencySymbolUzs',
        };
        final List<String> copied = <String>[
          for (final String key in messages(arb))
            if (!sameEverywhere.contains(key) && arb[key] == en[key]) key,
        ];
        expect(
          copied,
          isEmpty,
          reason:
              'Identical to the English in $name: ${copied.join(', ')}. Either '
              'translate them or add them to sameEverywhere with a reason.',
        );
      });
    });
  }

  test('every template message carries a description', () {
    // `required-resource-attributes` in l10n.yaml enforces this at generation
    // time. Asserting it here means the rule survives someone editing that file.
    final List<String> undescribed = <String>[
      for (final String key in messages(en))
        if (en['@$key'] is! Map<String, Object?> ||
            (en['@$key']! as Map<String, Object?>)['description'] == null)
          key,
    ];
    expect(undescribed, isEmpty, reason: undescribed.join(', '));
  });
}
