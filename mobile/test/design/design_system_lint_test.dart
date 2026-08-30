import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// The design system's call-site rules, enforced by reading the sources.
///
/// ADR 0035 makes a `TextStyle` constructed at a call site a lint failure,
/// "exactly as an inline `font-size` is on web". Expressing that in the Dart
/// analyzer needs `custom_lint`, which is another dependency and another thing
/// that cannot be verified on this machine. A source scan is cruder, has no
/// dependencies, and fails the same build. If `custom_lint` is adopted later,
/// this test is what it replaces.
///
/// The scan is deliberately shallow: it looks for the constructor and the
/// palette by name. It will not catch a colour reached through an alias, and it
/// is not trying to.
void main() {
  // Where the design system itself is defined. These files are allowed to say
  // the things everything else is forbidden to say; that is what makes them the
  // design system.
  const Set<String> designSystem = <String>{
    'lib/src/design/horecaos_tokens.dart',
    'lib/src/design/horecaos_typography.dart',
    'lib/src/design/horecaos_theme.dart',
    'lib/src/design/q_icon.dart',
  };

  final List<File> callSites = Directory('lib')
      .listSync(recursive: true)
      .whereType<File>()
      .where((File file) => file.path.endsWith('.dart'))
      .where((File file) => !designSystem.contains(_normalise(file.path)))
      .where(
        (File file) =>
            !_normalise(file.path).startsWith('lib/src/l10n/generated/'),
      )
      .toList();

  test('there are call sites to check', () {
    // A scan over nothing passes silently, which is the failure mode of every
    // test like this one.
    expect(callSites, isNotEmpty);
  });

  group('no colour literals outside the design system', () {
    for (final File file in callSites) {
      test(_normalise(file.path), () {
        final List<String> offences = _offences(file, <RegExp, String>{
          RegExp(r'\bColors\.'): 'Material palette. Use context.horecaos.',
          RegExp(r'\bColor\(0x'):
              'colour literal. Add it to horecaos_tokens.dart if it is a token.',
          RegExp(r'\bHorecaOSPalette\.'):
              'palette constant at a call site. Read it from context.horecaos, '
              'so a tenant accent can override it.',
        });
        expect(offences, isEmpty, reason: offences.join('\n'));
      });
    }
  });

  group('no type at a call site', () {
    for (final File file in callSites) {
      test(_normalise(file.path), () {
        final List<String> offences = _offences(file, <RegExp, String>{
          RegExp(r'\bTextStyle\('):
              'TextStyle constructed at a call site. Take one from '
              'Theme.of(context).textTheme and copyWith a colour only.',
          RegExp(r'\bfontSize\s*:'): 'inline font size. The scale is closed.',
        });
        expect(offences, isEmpty, reason: offences.join('\n'));
      });
    }
  });

  group('icons go through the QIcon seam', () {
    for (final File file in callSites) {
      test(_normalise(file.path), () {
        final List<String> offences = _offences(file, <RegExp, String>{
          RegExp(r'\bIcons\.'):
              'Material icon at a call site. Add a QIconName and use QIcon, '
              'so the icon font stays replaceable.',
        });
        expect(offences, isEmpty, reason: offences.join('\n'));
      });
    }
  });

  group('no emoji anywhere in the sources', () {
    // "Sentence case, no emoji, no gradients, no illustration" is a rule of the
    // design system, and it is easier to keep than to remove later.
    final RegExp emoji = RegExp(
      r'[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]',
      unicode: true,
    );
    final List<File> everySource = <File>[
      ...callSites,
      ...designSystem.map(File.new),
    ];
    for (final File file in everySource) {
      test(_normalise(file.path), () {
        expect(emoji.hasMatch(file.readAsStringSync()), isFalse);
      });
    }
  });
}

/// Returns one message per offending line, skipping comments.
///
/// Comments are skipped because the rules are explained in comments, and a scan
/// that flagged its own documentation would be turned off within a week.
List<String> _offences(File file, Map<RegExp, String> rules) {
  final List<String> found = <String>[];
  final List<String> lines = file.readAsLinesSync();
  for (int i = 0; i < lines.length; i++) {
    final String line = lines[i];
    final String trimmed = line.trimLeft();
    if (trimmed.startsWith('//') || trimmed.startsWith('*')) {
      continue;
    }
    for (final MapEntry<RegExp, String> rule in rules.entries) {
      if (rule.key.hasMatch(line)) {
        found.add('${_normalise(file.path)}:${i + 1}: ${rule.value}');
      }
    }
  }
  return found;
}

String _normalise(String path) => path.replaceAll('\\', '/');
