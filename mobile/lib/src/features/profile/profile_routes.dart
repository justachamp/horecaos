import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import 'data/saved_address.dart';
import 'profile_area.dart';
import 'ui/address_form_page.dart';
import 'ui/addresses_page.dart';
import 'ui/language_page.dart';
import 'ui/notification_preferences_page.dart';
import 'ui/profile_page.dart';
import 'ui/profile_scope.dart';

/// The profile area's paths, and the routes behind them.
///
/// Exposed as a list rather than registered here, because this feature does not
/// own the router. Three things wire it up, all of them outside this feature:
///
/// 1. **The composition root builds one [ProfileArea]** and loads the language
///    choice, beside the session and the API client:
///
///    ```dart
///    final ProfileArea profile = ProfileArea.from(
///      api: api,
///      customer: const CustomerScope(tenantId: …, brandId: …),
///      session: session,
///    );
///    unawaited(profile.locale.load());
///    ```
///
/// 2. **The router adds `...ProfileRoutes.routes(profile)`** to the shell
///    route's children, and `Routes.profile` to `AppShell._destinations` as a
///    third tab. Without the destination entry the shell's index falls back to
///    the first tab while the customer is inside the profile area, which is a
///    highlighted tab that is not the one they are on.
///
/// 3. **`MaterialApp.router` honours the chosen language**, by rebuilding on
///    `profile.locale` and passing `locale: profile.locale.selected`. Null
///    means "follow the device", which is exactly what `MaterialApp`'s own null
///    already means, so the two line up without a special case.
///
/// The paths are constants for the same reason `Routes` uses them: a renamed
/// route should be a compile error and not a dead link a customer finds.
abstract final class ProfileRoutes {
  /// Belongs inside the application shell, as a third destination beside the
  /// menu and orders tabs.
  static const String profile = '/profile';

  static const String addresses = '/profile/addresses';
  static const String newAddress = '/profile/addresses/new';
  static const String language = '/profile/language';
  static const String notifications = '/profile/notifications';

  /// The edit form for one address.
  ///
  /// The identifier is in the path so the route is addressable, and the address
  /// itself is passed as `extra` so the form does not have to re-reveal it —
  /// every read of an address is a decryption the platform records against a
  /// stated purpose (ADR 0029), and re-reading one the customer is already
  /// looking at would add an audit entry that means nothing.
  static String editAddressPath(String addressId) =>
      '/profile/addresses/$addressId';

  /// Every route in the area.
  ///
  /// Child paths are relative, so go_router builds the parent's page beneath
  /// them and the back arrow returns to the profile root rather than to
  /// whatever tab the customer came from.
  static List<RouteBase> routes(ProfileArea area) {
    Widget scoped(Widget child) => ProfileScope(area: area, child: child);

    return <RouteBase>[
      GoRoute(
        path: profile,
        builder: (BuildContext context, GoRouterState state) =>
            scoped(const ProfilePage()),
        routes: <RouteBase>[
          GoRoute(
            path: 'addresses',
            builder: (BuildContext context, GoRouterState state) =>
                scoped(const AddressesPage()),
            routes: <RouteBase>[
              GoRoute(
                // Before the `:addressId` route, so "new" is not read as an
                // identifier. go_router matches in declaration order.
                path: 'new',
                builder: (BuildContext context, GoRouterState state) =>
                    scoped(const AddressFormPage()),
              ),
              GoRoute(
                path: ':addressId',
                builder: (BuildContext context, GoRouterState state) {
                  final Object? extra = state.extra;
                  return scoped(
                    AddressFormPage(
                      existing: extra is SavedAddress ? extra : null,
                    ),
                  );
                },
              ),
            ],
          ),
          GoRoute(
            path: 'language',
            builder: (BuildContext context, GoRouterState state) =>
                scoped(const LanguagePage()),
          ),
          GoRoute(
            path: 'notifications',
            builder: (BuildContext context, GoRouterState state) =>
                scoped(const NotificationPreferencesPage()),
          ),
        ],
      ),
    ];
  }
}
