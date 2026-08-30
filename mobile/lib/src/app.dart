import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'api/api_client.dart';
import 'auth/auth_session.dart';
import 'design/qoida_theme.dart';
import 'design/qoida_typography.dart';
import 'features/catalogue/data/pickup_location.dart';
import 'l10n/generated/app_localizations.dart';
import 'l10n/supported_locales.dart';
import 'routing/app_router.dart';

/// The application root.
class QoidaApp extends StatefulWidget {
  const QoidaApp({
    required this.session,
    required this.api,
    required this.initialPickupPoint,
    super.key,
    this.initialLocation,
  });

  final AuthSession session;
  final QoidaApiClient api;
  final PickupSearchPoint initialPickupPoint;

  /// Only tests pass this. Production always starts at the guard's own
  /// starting route.
  final String? initialLocation;

  @override
  State<QoidaApp> createState() => _QoidaAppState();
}

class _QoidaAppState extends State<QoidaApp> {
  late final GoRouter _router = buildRouter(
    widget.session,
    api: widget.api,
    initialPickupPoint: widget.initialPickupPoint,
    initialLocation: widget.initialLocation,
  );

  @override
  void dispose() {
    _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      onGenerateTitle: (BuildContext context) =>
          AppLocalizations.of(context).appTitle,
      debugShowCheckedModeBanner: false,
      routerConfig: _router,

      theme: QoidaTheme.light(),
      // No dark theme, and this is a gap rather than a decision to ship light
      // only. The design system's token sheet has no dark palette: there are no
      // dark values for `--q-canvas`, `--q-ink` or the status tints to vendor,
      // and inventing them here would put a palette in this repository that the
      // design system never approved and the three Angular applications would
      // not match. `themeMode` is pinned to light so a device in dark mode gets
      // the approved palette rather than Material's inversion of it.
      themeMode: ThemeMode.light,

      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: SupportedLocales.all,
      localeResolutionCallback: _resolveLocale,

      builder: (BuildContext context, Widget? child) {
        // Dynamic type is honoured and clamped. Refusing the system setting is
        // an accessibility failure; taking it unbounded breaks a closed type
        // scale, and at 2.0 a price no longer fits on its row.
        final MediaQueryData media = MediaQuery.of(context);
        return MediaQuery(
          data: media.copyWith(
            textScaler: QoidaTypography.clampScaler(media.textScaler),
          ),
          child: child ?? const SizedBox.shrink(),
        );
      },
    );
  }

  /// Matches on language and script, ignoring country.
  ///
  /// Flutter's default resolution would give a device set to `uz_UZ` — with no
  /// script subtag, which is what Android reports — no match against
  /// `uz_Latn` and fall through to the first supported locale. Since the
  /// application ships only the Latin script today, a bare `uz` is uz-Latn.
  static Locale _resolveLocale(Locale? device, Iterable<Locale> supported) {
    if (device == null) {
      return SupportedLocales.all.first;
    }
    for (final Locale candidate in supported) {
      if (candidate.languageCode == device.languageCode) {
        return candidate;
      }
    }
    return SupportedLocales.all.first;
  }
}
