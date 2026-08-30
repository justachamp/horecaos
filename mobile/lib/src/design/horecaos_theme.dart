import 'package:flutter/material.dart';

import 'horecaos_tokens.dart';
import 'horecaos_typography.dart';

/// The design tokens, reachable from any widget.
///
/// ADR 0035 requires that widgets read tokens through
/// `Theme.of(context).extension<HorecaOSTokens>()` and never through `Colors.*` or
/// a literal `Color(0x…)`. This extension is what makes that possible, and it
/// is also the seam through which a tenant accent is injected: the accent is
/// per-brand data, so it cannot live in a `const` token file.
@immutable
class HorecaOSTokens extends ThemeExtension<HorecaOSTokens> {
  const HorecaOSTokens({
    required this.accent,
    required this.ink,
    required this.inkMuted,
    required this.inkSubtle,
    required this.canvas,
    required this.surface1,
    required this.surface2,
    required this.hairline,
    required this.success,
    required this.warningDot,
    required this.warningInk,
    required this.error,
    required this.errorInk,
    required this.radius,
    required this.minTarget,
  });

  /// The tenant's accent, defaulting to the platform blue.
  ///
  /// On CONSOLE a tenant accent is forbidden. On MOBILE the customer is in one
  /// brand's application and the accent is that brand's, injected once at
  /// bootstrap from brand configuration. It defaults to `--q-primary` so an
  /// unbranded build is correct rather than colourless.
  final Color accent;

  final Color ink;
  final Color inkMuted;
  final Color inkSubtle;
  final Color canvas;
  final Color surface1;
  final Color surface2;
  final Color hairline;

  final Color success;

  /// Yellow is a dot only. Its text pair is [warningInk], never this.
  final Color warningDot;
  final Color warningInk;

  final Color error;
  final Color errorInk;

  final double radius;
  final double minTarget;

  static const HorecaOSTokens light = HorecaOSTokens(
    accent: HorecaOSPalette.primary,
    ink: HorecaOSPalette.ink,
    inkMuted: HorecaOSPalette.inkMuted,
    inkSubtle: HorecaOSPalette.inkSubtle,
    canvas: HorecaOSPalette.canvas,
    surface1: HorecaOSPalette.surface1,
    surface2: HorecaOSPalette.surface2,
    hairline: HorecaOSPalette.hairline,
    success: HorecaOSPalette.successText,
    warningDot: HorecaOSPalette.warning,
    warningInk: HorecaOSPalette.warningText,
    error: HorecaOSPalette.error,
    errorInk: HorecaOSPalette.errorText,
    radius: HorecaOSGeometry.radius,
    minTarget: HorecaOSGeometry.minTarget,
  );

  @override
  HorecaOSTokens copyWith({Color? accent}) => HorecaOSTokens(
    accent: accent ?? this.accent,
    ink: ink,
    inkMuted: inkMuted,
    inkSubtle: inkSubtle,
    canvas: canvas,
    surface1: surface1,
    surface2: surface2,
    hairline: hairline,
    success: success,
    warningDot: warningDot,
    warningInk: warningInk,
    error: error,
    errorInk: errorInk,
    radius: radius,
    minTarget: minTarget,
  );

  /// Themes do not cross-fade in this system.
  ///
  /// Only `transform` and `opacity` animate, so a token lerp would produce
  /// motion the design system forbids. Returning the destination outright is
  /// the honest implementation of that rule rather than an unimplemented method.
  @override
  HorecaOSTokens lerp(ThemeExtension<HorecaOSTokens>? other, double t) {
    if (other is! HorecaOSTokens) {
      return this;
    }
    return t < 0.5 ? this : other;
  }
}

/// Builds the application theme from the tokens.
abstract final class HorecaOSTheme {
  /// The `--q-ease-productive` curve.
  static const Cubic easeProductive = Cubic(0.2, 0, 0.38, 0.9);

  static ThemeData light({Color? tenantAccent}) {
    final HorecaOSTokens tokens = HorecaOSTokens.light.copyWith(accent: tenantAccent);
    return _build(tokens);
  }

  static ThemeData _build(HorecaOSTokens tokens) {
    // A seeded scheme would generate its own palette and quietly overrule the
    // token sheet, so every role is stated. Roles the design system has no
    // opinion about still have to be filled: Material reads all of them, and an
    // unfilled role falls back to a Material default, which is exactly the
    // silent drift this file exists to prevent.
    final ColorScheme scheme = ColorScheme(
      brightness: Brightness.light,
      primary: tokens.accent,
      onPrimary: HorecaOSPalette.inverseInk,
      secondary: tokens.accent,
      onSecondary: HorecaOSPalette.inverseInk,
      error: tokens.error,
      onError: HorecaOSPalette.inverseInk,
      surface: tokens.canvas,
      onSurface: tokens.ink,
      surfaceContainerLowest: tokens.canvas,
      surfaceContainerLow: tokens.surface1,
      surfaceContainer: tokens.surface1,
      surfaceContainerHigh: tokens.surface2,
      surfaceContainerHighest: tokens.surface2,
      onSurfaceVariant: tokens.inkMuted,
      outline: tokens.hairline,
      outlineVariant: tokens.hairline,
      inverseSurface: HorecaOSPalette.inverse,
      onInverseSurface: HorecaOSPalette.inverseInk,
      // Material 3 tints surfaces by elevation. This design system expresses
      // depth with one shadow and a hairline, never by recolouring. A
      // transparent tint is how that is switched off scheme-wide.
      surfaceTint: const Color(0x00000000),
    );

    final RoundedRectangleBorder shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(tokens.radius),
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      extensions: <ThemeExtension<dynamic>>[tokens],
      scaffoldBackgroundColor: tokens.canvas,
      canvasColor: tokens.canvas,
      textTheme: _textTheme(tokens),
      fontFamily: HorecaOSTypography.fontFamily,

      // The ink ripple is decoration, and this system has none. A press state is
      // a token-controlled colour change, applied by the component that owns it.
      splashFactory: NoSplash.splashFactory,
      splashColor: const Color(0x00000000),
      highlightColor: const Color(0x00000000),

      // Belt and braces with the transparent surfaceTint above: this flag is
      // what Material consults for the legacy overlay path.
      applyElevationOverlayColor: false,

      shadowColor: HorecaOSElevation.shadowColor,
      dividerTheme: DividerThemeData(
        color: tokens.hairline,
        thickness: HorecaOSGeometry.hairline,
        space: HorecaOSGeometry.hairline,
      ),

      appBarTheme: AppBarThemeData(
        backgroundColor: tokens.canvas,
        foregroundColor: tokens.ink,
        surfaceTintColor: const Color(0x00000000),
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: HorecaOSTypography.subhead.copyWith(color: tokens.ink),
      ),

      cardTheme: CardThemeData(
        color: tokens.canvas,
        surfaceTintColor: const Color(0x00000000),
        elevation: 0,
        shape: shape.copyWith(
          side: BorderSide(color: tokens.hairline, width: HorecaOSGeometry.hairline),
        ),
        margin: EdgeInsets.zero,
      ),

      // Material's default sheet is a 28dp top radius. FIELD's radius is 8.
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: tokens.canvas,
        surfaceTintColor: const Color(0x00000000),
        elevation: 0,
        showDragHandle: true,
        dragHandleColor: tokens.surface2,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(tokens.radius),
          ),
        ),
      ),

      dialogTheme: DialogThemeData(
        backgroundColor: tokens.canvas,
        surfaceTintColor: const Color(0x00000000),
        elevation: 0,
        shape: shape,
        titleTextStyle: HorecaOSTypography.subhead.copyWith(color: tokens.ink),
        contentTextStyle: HorecaOSTypography.body.copyWith(color: tokens.ink),
      ),

      snackBarTheme: SnackBarThemeData(
        backgroundColor: HorecaOSPalette.inverse,
        contentTextStyle: HorecaOSTypography.bodySmall.copyWith(
          color: HorecaOSPalette.inverseInk,
        ),
        actionTextColor: HorecaOSPalette.inverseInk,
        behavior: SnackBarBehavior.floating,
        shape: shape,
        elevation: 0,
      ),

      filledButtonTheme: FilledButtonThemeData(
        style: _buttonStyle(tokens).copyWith(
          backgroundColor: WidgetStateProperty.resolveWith<Color?>((
            Set<WidgetState> states,
          ) {
            if (states.contains(WidgetState.disabled)) return tokens.surface2;
            if (states.contains(WidgetState.pressed)) {
              return HorecaOSPalette.primaryActive;
            }
            if (states.contains(WidgetState.hovered)) {
              return HorecaOSPalette.primaryHover;
            }
            return tokens.accent;
          }),
          foregroundColor: WidgetStateProperty.resolveWith<Color?>((
            Set<WidgetState> states,
          ) {
            if (states.contains(WidgetState.disabled)) return tokens.inkSubtle;
            return HorecaOSPalette.inverseInk;
          }),
        ),
      ),

      textButtonTheme: TextButtonThemeData(
        style: _buttonStyle(tokens).copyWith(
          foregroundColor: WidgetStateProperty.resolveWith<Color?>((
            Set<WidgetState> states,
          ) {
            if (states.contains(WidgetState.disabled)) return tokens.inkSubtle;
            if (states.contains(WidgetState.pressed)) {
              return HorecaOSPalette.primaryActive;
            }
            return tokens.accent;
          }),
        ),
      ),

      outlinedButtonTheme: OutlinedButtonThemeData(
        style: _buttonStyle(tokens).copyWith(
          foregroundColor: WidgetStatePropertyAll<Color>(tokens.ink),
          side: WidgetStatePropertyAll<BorderSide>(
            BorderSide(color: tokens.hairline, width: HorecaOSGeometry.hairline),
          ),
        ),
      ),

      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: tokens.canvas,
        surfaceTintColor: const Color(0x00000000),
        indicatorColor: const Color(0x00000000),
        elevation: 0,
        height: HorecaOSGeometry.minTarget + HorecaOSGeometry.spaceMd,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        labelTextStyle: WidgetStateProperty.resolveWith<TextStyle?>((
          Set<WidgetState> states,
        ) {
          final Color colour = states.contains(WidgetState.selected)
              ? tokens.accent
              : tokens.inkMuted;
          return HorecaOSTypography.caption.copyWith(color: colour);
        }),
        iconTheme: WidgetStateProperty.resolveWith<IconThemeData?>((
          Set<WidgetState> states,
        ) {
          final Color colour = states.contains(WidgetState.selected)
              ? tokens.accent
              : tokens.inkMuted;
          return IconThemeData(color: colour, size: 24);
        }),
      ),

      inputDecorationTheme: InputDecorationThemeData(
        filled: true,
        fillColor: tokens.surface1,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(tokens.radius),
          borderSide: BorderSide(color: tokens.hairline),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(tokens.radius),
          borderSide: BorderSide(color: tokens.hairline),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(tokens.radius),
          borderSide: BorderSide(color: tokens.accent, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(tokens.radius),
          borderSide: BorderSide(color: tokens.error),
        ),
        labelStyle: HorecaOSTypography.bodySmall.copyWith(color: tokens.inkMuted),
        hintStyle: HorecaOSTypography.body.copyWith(color: tokens.inkSubtle),
        errorStyle: HorecaOSTypography.caption.copyWith(color: tokens.errorInk),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: HorecaOSGeometry.spaceMd,
          vertical: HorecaOSGeometry.spaceMd,
        ),
      ),

      // One widget set on both platforms. `pageTransitionsTheme` is set for
      // every platform so an iOS build does not inherit the Cupertino slide and
      // an Android build the Material fade: "one codebase" stops being true the
      // moment the tree branches on platform.
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: <TargetPlatform, PageTransitionsBuilder>{
          TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: FadeForwardsPageTransitionsBuilder(),
          TargetPlatform.macOS: FadeForwardsPageTransitionsBuilder(),
          TargetPlatform.windows: FadeForwardsPageTransitionsBuilder(),
          TargetPlatform.linux: FadeForwardsPageTransitionsBuilder(),
          TargetPlatform.fuchsia: FadeForwardsPageTransitionsBuilder(),
        },
      ),
    );
  }

  static ButtonStyle _buttonStyle(HorecaOSTokens tokens) => ButtonStyle(
    minimumSize: WidgetStatePropertyAll<Size>(
      Size(tokens.minTarget, tokens.minTarget),
    ),
    shape: WidgetStatePropertyAll<OutlinedBorder>(
      RoundedRectangleBorder(borderRadius: BorderRadius.circular(tokens.radius)),
    ),
    textStyle: WidgetStatePropertyAll<TextStyle>(HorecaOSTypography.emphasis),
    elevation: const WidgetStatePropertyAll<double>(0),
    splashFactory: NoSplash.splashFactory,
    padding: const WidgetStatePropertyAll<EdgeInsetsGeometry>(
      EdgeInsets.symmetric(horizontal: HorecaOSGeometry.spaceMd),
    ),
  );

  /// The closed scale, mapped onto Material's slots.
  ///
  /// Every slot is filled from the scale. Leaving one unset would let a
  /// Material default through, and a Material default is a font size nobody in
  /// this design system chose.
  static TextTheme _textTheme(HorecaOSTokens tokens) {
    final Color ink = tokens.ink;
    final Color muted = tokens.inkMuted;
    return TextTheme(
      displayLarge: HorecaOSTypography.display.copyWith(color: ink),
      displayMedium: HorecaOSTypography.display.copyWith(color: ink),
      displaySmall: HorecaOSTypography.headline.copyWith(color: ink),
      headlineLarge: HorecaOSTypography.headline.copyWith(color: ink),
      // `.q-data-lg`. Material has no numeric slot, and this is the closest.
      headlineMedium: HorecaOSTypography.dataLarge.copyWith(color: ink),
      headlineSmall: HorecaOSTypography.title.copyWith(color: ink),
      titleLarge: HorecaOSTypography.title.copyWith(color: ink),
      titleMedium: HorecaOSTypography.subhead.copyWith(color: ink),
      titleSmall: HorecaOSTypography.emphasis.copyWith(color: ink),
      bodyLarge: HorecaOSTypography.body.copyWith(color: ink),
      bodyMedium: HorecaOSTypography.bodySmall.copyWith(color: ink),
      bodySmall: HorecaOSTypography.caption.copyWith(color: muted),
      labelLarge: HorecaOSTypography.emphasis.copyWith(color: ink),
      labelMedium: HorecaOSTypography.bodySmall.copyWith(color: muted),
      labelSmall: HorecaOSTypography.caption.copyWith(color: muted),
    );
  }
}

/// Reads the tokens, or fails loudly.
extension HorecaOSThemeContext on BuildContext {
  HorecaOSTokens get horecaos {
    final HorecaOSTokens? tokens = Theme.of(this).extension<HorecaOSTokens>();
    if (tokens == null) {
      // Falling back to a default here would let a widget render with
      // Material's palette and look almost right, which is worse than not
      // rendering: the drift would ship.
      throw FlutterError(
        'No HorecaOSTokens in this Theme. Wrap the subtree in HorecaOSTheme.light().',
      );
    }
    return tokens;
  }
}
