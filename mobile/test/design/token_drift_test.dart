import 'dart:io';
import 'dart:ui' show Color;

import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/design/horecaos_tokens.dart';
import 'package:horecaos_mobile/src/design/horecaos_typography.dart';

/// The drift check ADR 0035 asks for, in the form that is possible today.
///
/// The four applications vendor their own copies of the design system's tokens
/// because there is no shared package registry yet. That makes a hand edit
/// invisible unless something checks, so this reads the vendored CSS sheet at
/// `design-tokens/tokens.css` and asserts that every Dart constant still says
/// what the sheet says.
///
/// It is a weaker guarantee than the real thing: it catches a Dart file edited
/// away from the CSS beside it, and it does not catch both being edited
/// together and away from the design system. Only `sync-tokens` in the platform
/// repository can catch that, and it is not written yet.
void main() {
  final Map<String, String> css = _customProperties(
    File('design-tokens/tokens.css').readAsStringSync(),
  );

  group('colour tokens match the vendored sheet', () {
    final Map<String, Color> expected = <String, Color>{
      'q-primary': HorecaOSPalette.primary,
      'q-primary-hover': HorecaOSPalette.primaryHover,
      'q-primary-active': HorecaOSPalette.primaryActive,
      'q-ink': HorecaOSPalette.ink,
      'q-ink-muted': HorecaOSPalette.inkMuted,
      'q-ink-subtle': HorecaOSPalette.inkSubtle,
      'q-canvas': HorecaOSPalette.canvas,
      'q-surface-1': HorecaOSPalette.surface1,
      'q-surface-2': HorecaOSPalette.surface2,
      'q-hairline': HorecaOSPalette.hairline,
      'q-inverse': HorecaOSPalette.inverse,
      'q-inverse-ink': HorecaOSPalette.inverseInk,
      'q-inverse-ink-muted': HorecaOSPalette.inverseInkMuted,
      'q-success': HorecaOSPalette.success,
      'q-warning': HorecaOSPalette.warning,
      'q-error': HorecaOSPalette.error,
      'q-info-tint': HorecaOSPalette.infoTint,
      'q-info-text': HorecaOSPalette.infoText,
      'q-success-tint': HorecaOSPalette.successTint,
      'q-success-text': HorecaOSPalette.successText,
      'q-warning-tint': HorecaOSPalette.warningTint,
      'q-warning-text': HorecaOSPalette.warningText,
      'q-error-tint': HorecaOSPalette.errorTint,
      'q-error-text': HorecaOSPalette.errorText,
    };

    for (final MapEntry<String, Color> entry in expected.entries) {
      test('--${entry.key}', () {
        final String? value = css[entry.key];
        expect(value, isNotNull, reason: 'missing from the vendored sheet');
        expect(_parseHex(value!), entry.value);
      });
    }

    test('the sheet defines nothing this file has not vendored', () {
      // A token added to the design system and not carried across here is drift
      // in the direction that is easy to miss: nothing looks wrong until a
      // screen needs the new token and invents its own value.
      const Set<String> deliberatelyNotColour = <String>{
        'q-font-sans',
        'q-font-mono',
        'q-radius',
        'q-dur-fast',
        'q-dur-base',
        'q-ease-productive',
      };
      final Set<String> unaccounted = css.keys.toSet()
        ..removeAll(expected.keys)
        ..removeAll(deliberatelyNotColour);
      expect(unaccounted, isEmpty);
    });
  });

  group('motion tokens match the vendored sheet', () {
    test('durations', () {
      expect(css['q-dur-fast'], '110ms');
      expect(HorecaOSMotion.fast, const Duration(milliseconds: 110));
      expect(css['q-dur-base'], '150ms');
      expect(HorecaOSMotion.base, const Duration(milliseconds: 150));
    });

    test('the productive easing curve', () {
      expect(css['q-ease-productive'], 'cubic-bezier(0.2, 0, 0.38, 0.9)');
      expect(HorecaOSMotion.easeProductive, <double>[0.2, 0, 0.38, 0.9]);
    });
  });

  group('geometry deliberately does not match the sheet', () {
    test('the radius is FIELD, not the sheet CONSOLE value', () {
      // The vendored sheet is the CONSOLE surface, where 0px is the brand. ADR
      // 0035 puts MOBILE on FIELD geometry, so this is the one value that is
      // expected to differ — asserted so that a future sheet carrying a FIELD
      // radius does not silently make this a coincidence.
      expect(css['q-radius'], '0px');
      expect(HorecaOSGeometry.radius, 8);
    });
  });

  group('type', () {
    test('the font stack names IBM Plex Sans first', () {
      expect(css['q-font-sans'], startsWith("'IBM Plex Sans'"));
    });

    test('the family is withheld until the faces are bundled', () {
      // Naming a family Flutter cannot find logs an error on every frame and
      // renders the fallback anyway. See assets/fonts/README.md.
      expect(HorecaOSTypography.fontFamily, isNull);
      expect(
        Directory('assets/fonts').listSync().whereType<File>().where(
          (File file) => file.path.endsWith('.ttf'),
        ),
        isEmpty,
        reason:
            'Font faces are present: set HorecaOSTypography.fontFamily to '
            "'IBMPlexSans' and declare them in pubspec.yaml.",
      );
    });
  });
}

/// Pulls `--name: value;` declarations out of the sheet.
Map<String, String> _customProperties(String css) {
  final RegExp declaration = RegExp(r'--([a-z0-9-]+)\s*:\s*([^;]+);');
  return <String, String>{
    for (final RegExpMatch match in declaration.allMatches(css))
      match.group(1)!: match.group(2)!.trim(),
  };
}

Color _parseHex(String value) {
  final String hex = value.replaceAll('#', '').trim();
  return Color(int.parse('ff$hex', radix: 16));
}
