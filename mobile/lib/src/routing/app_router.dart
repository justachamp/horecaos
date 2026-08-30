import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../api/api_client.dart';
import '../auth/auth_session.dart';
import '../design/q_empty_state.dart';
import '../features/catalogue/data/pickup_location.dart';
import '../features/catalogue/storefront_home.dart';
import '../l10n/generated/app_localizations.dart';
import 'app_shell.dart';
import 'routes.dart';
import 'sign_in_page.dart';

/// The router and the authentication guard.
///
/// The menu is public browse; customer-specific surfaces remain guarded.
GoRouter buildRouter(
  AuthSession session, {
  required QoidaApiClient api,
  required PickupSearchPoint initialPickupPoint,
  String? initialLocation,
}) {
  return GoRouter(
    initialLocation: initialLocation ?? Routes.starting,

    // The guard re-evaluates whenever the session changes: a token that fails
    // to refresh mid-session moves the customer to sign-in without any screen
    // having to know that authentication exists.
    refreshListenable: session,

    redirect: (BuildContext context, GoRouterState state) =>
        guard(session.status, state.matchedLocation),

    routes: <RouteBase>[
      GoRoute(
        path: Routes.starting,
        // Nothing but the canvas colour. A spinner here flashes on every cold
        // start for the few milliseconds a keystore read takes, which reads as
        // a slow application rather than a fast one.
        builder: (BuildContext context, GoRouterState state) =>
            const Scaffold(body: SizedBox.expand()),
      ),
      GoRoute(
        path: Routes.signIn,
        builder: (BuildContext context, GoRouterState state) =>
            SignInPage(session: session),
      ),
      ShellRoute(
        builder: (BuildContext context, GoRouterState state, Widget child) =>
            AppShell(child: child),
        routes: <RouteBase>[
          GoRoute(
            path: Routes.menu,
            builder: (BuildContext context, GoRouterState state) =>
                StorefrontHome(
                  api: api,
                  initialPickupPoint: initialPickupPoint,
                ),
          ),
          GoRoute(
            path: Routes.orders,
            builder: (BuildContext context, GoRouterState state) =>
                const _UnbuiltRoute(),
          ),
        ],
      ),
    ],
  );
}

/// The whole guard, as a pure function.
///
/// Pure so it can be tested without a widget tree, a router, or a realm — which
/// matters, because this is the one piece of routing logic that can lock a
/// customer out of the application or let them past a sign-in they never did.
///
/// Returns the path to redirect to, or null to stay put.
String? guard(AuthStatus status, String location) {
  switch (status) {
    case AuthStatus.unknown:
      // Do not move while the session is being restored. Sending a returning
      // customer to sign-in for the half second a keystore read takes shows
      // them the one screen they should never see again.
      return location == Routes.starting ? null : Routes.starting;

    case AuthStatus.signedOut:
      // A menu is the pre-account browse surface. Orders remain personal to an
      // account, while the sign-in page stays reachable when a customer chooses
      // it in order to place an order later.
      if (location == Routes.starting) return Routes.menu;
      if (location == Routes.menu || location == Routes.signIn) return null;
      return Routes.signIn;

    case AuthStatus.signedIn:
      if (location == Routes.signIn || location == Routes.starting) {
        return Routes.menu;
      }
      return null;
  }
}

/// A route scaffolded ahead of its customer-specific screen.
class _UnbuiltRoute extends StatelessWidget {
  const _UnbuiltRoute();

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    return QEmptyState(title: l10n.notBuiltTitle, body: l10n.notBuiltBody);
  }
}
