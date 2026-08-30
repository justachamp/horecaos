import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/design/horecaos_theme.dart';
import 'package:horecaos_mobile/src/design/horecaos_tokens.dart';
import 'package:horecaos_mobile/src/design/horecaos_typography.dart';

void main() {
  final ThemeData theme = HorecaOSTheme.light();

  group('tokens reach the theme', () {
    test('the extension is present', () {
      expect(theme.extension<HorecaOSTokens>(), isNotNull);
    });

    test('the accent is the platform blue when no tenant accent is given', () {
      expect(theme.colorScheme.primary, HorecaOSPalette.primary);
    });

    test('a tenant accent replaces the platform blue', () {
      // MOBILE is a customer inside one brand's application, so the accent is
      // that brand's. On CONSOLE a tenant accent is forbidden.
      const Color tenant = Color(0xFF7A1F5C);
      final ThemeData branded = HorecaOSTheme.light(tenantAccent: tenant);
      expect(branded.colorScheme.primary, tenant);
      expect(branded.extension<HorecaOSTokens>()!.accent, tenant);
    });
  });

  group('Material defaults this design system switches off', () {
    test('surfaces are not tinted by elevation', () {
      // Material 3 recolours a surface by depth. This system expresses depth
      // with one shadow and a hairline, never with colour.
      expect(theme.colorScheme.surfaceTint.a, 0);
      expect(theme.applyElevationOverlayColor, isFalse);
      expect(theme.cardTheme.surfaceTintColor?.a, 0);
      expect(theme.appBarTheme.surfaceTintColor?.a, 0);
      expect(theme.bottomSheetTheme.surfaceTintColor?.a, 0);
    });

    test('there is no ink ripple', () {
      // A ripple is decoration, and this system has none.
      expect(theme.splashFactory, NoSplash.splashFactory);
      expect(theme.splashColor.a, 0);
      expect(theme.highlightColor.a, 0);
    });

    test('nothing carries a Material elevation', () {
      expect(theme.appBarTheme.elevation, 0);
      expect(theme.cardTheme.elevation, 0);
      expect(theme.dialogTheme.elevation, 0);
      expect(theme.bottomSheetTheme.elevation, 0);
      expect(theme.snackBarTheme.elevation, 0);
    });
  });

  group('geometry is FIELD, not CONSOLE and not Material', () {
    test('corners are the 8dp FIELD radius', () {
      expect(HorecaOSGeometry.radius, 8);
      expect(_topLeftRadius(theme.cardTheme.shape), 8);
      expect(_topLeftRadius(theme.dialogTheme.shape), 8);
      // Material's default sheet corner is 28. A phone sheet in this system is
      // the same 8 as everything else.
      expect(_topLeftRadius(theme.bottomSheetTheme.shape), 8);
    });

    test('minimum targets are 48dp on both platforms', () {
      expect(HorecaOSGeometry.minTarget, 48);
      final Size? minimum = theme.filledButtonTheme.style?.minimumSize
          ?.resolve(<WidgetState>{});
      expect(minimum?.height, 48);
    });
  });

  group('the type scale is closed', () {
    test('every Material slot is filled from the scale', () {
      final TextTheme text = theme.textTheme;
      final List<double?> sizes = <double?>[
        text.displayLarge?.fontSize,
        text.displayMedium?.fontSize,
        text.displaySmall?.fontSize,
        text.headlineLarge?.fontSize,
        text.headlineMedium?.fontSize,
        text.headlineSmall?.fontSize,
        text.titleLarge?.fontSize,
        text.titleMedium?.fontSize,
        text.titleSmall?.fontSize,
        text.bodyLarge?.fontSize,
        text.bodyMedium?.fontSize,
        text.bodySmall?.fontSize,
        text.labelLarge?.fontSize,
        text.labelMedium?.fontSize,
        text.labelSmall?.fontSize,
      ];
      // An unfilled slot falls back to a Material default, which is a font size
      // nobody in this design system chose.
      expect(sizes, everyElement(isNotNull));

      // Not const: a constant set may not hold a type that overrides ==, and
      // double does. The set is built once per run either way.
      final Set<double> scale = <double>{42, 32, 28, 24, 20, 16, 14, 12};
      for (final double? size in sizes) {
        expect(scale, contains(size));
      }
    });

    test('the numeric style uses tabular figures', () {
      // Proportional digits make a column of totals ripple as it updates.
      expect(
        HorecaOSTypography.dataLarge.fontFeatures,
        contains(const FontFeature.tabularFigures()),
      );
    });
  });

  group('dynamic type', () {
    test('is honoured within bounds', () {
      expect(
        HorecaOSTypography.clampScaler(const TextScaler.linear(1.2)).scale(10),
        12,
      );
    });

    test('is clamped at both ends', () {
      // Unbounded, a 200 % system setting pushes a price off its row; refused,
      // the application is unusable for anyone who needs larger text.
      expect(
        HorecaOSTypography.clampScaler(const TextScaler.linear(3)).scale(10),
        10 * HorecaOSTypography.maxTextScale,
      );
      expect(
        HorecaOSTypography.clampScaler(const TextScaler.linear(0.5)).scale(10),
        10 * HorecaOSTypography.minTextScale,
      );
    });
  });

  group('status colour', () {
    test('yellow is a dot, and its text pair is the darker ink', () {
      // A design-system rule with an accessibility reason: the warning yellow
      // does not meet contrast as text on white, so it is never used as one.
      expect(HorecaOSTokens.light.warningDot, HorecaOSPalette.warning);
      expect(HorecaOSTokens.light.warningInk, HorecaOSPalette.warningText);
      expect(HorecaOSTokens.light.warningInk, isNot(HorecaOSTokens.light.warningDot));
    });
  });
}

double? _topLeftRadius(ShapeBorder? shape) {
  if (shape is RoundedRectangleBorder) {
    return shape.borderRadius.resolve(TextDirection.ltr).topLeft.x;
  }
  return null;
}
