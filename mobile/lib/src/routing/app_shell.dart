import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../design/q_icon.dart';
import '../design/horecaos_theme.dart';
import '../design/horecaos_tokens.dart';
import '../l10n/generated/app_localizations.dart';
import 'routes.dart';

/// The MOBILE layout shell: content, and a bottom bar over a hairline.
///
/// A bottom bar and not a drawer. A drawer beside a list has no phone
/// equivalent, and the destinations a customer moves between constantly should
/// not be behind a gesture.
class AppShell extends StatelessWidget {
  const AppShell({required this.child, super.key});

  final Widget child;

  static const List<String> _destinations = <String>[
    Routes.menu,
    Routes.orders,
  ];

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final int index = _indexOf(GoRouterState.of(context).uri.path);

    return Scaffold(
      body: SafeArea(child: child),
      bottomNavigationBar: DecoratedBox(
        // The hairline is the whole elevation. There is no shadow under a
        // navigation bar in this system, on any surface.
        decoration: BoxDecoration(
          border: Border(
            top: BorderSide(
              color: tokens.hairline,
              width: HorecaOSGeometry.hairline,
            ),
          ),
        ),
        child: NavigationBar(
          selectedIndex: index,
          onDestinationSelected: (int selected) {
            if (selected != index) {
              context.go(_destinations[selected]);
            }
          },
          destinations: <NavigationDestination>[
            NavigationDestination(
              icon: const QIcon(QIconName.home),
              label: l10n.navHome,
            ),
            NavigationDestination(
              icon: const QIcon(QIconName.orders),
              label: l10n.navOrders,
            ),
          ],
        ),
      ),
    );
  }

  /// Falls back to the first destination for a path that is inside the shell
  /// but is not itself a destination — a product detail under the menu, once
  /// those exist. A `NavigationBar` with an out-of-range index throws.
  static int _indexOf(String path) {
    final int found = _destinations.indexWhere(
      (String destination) => path.startsWith(destination),
    );
    return found == -1 ? 0 : found;
  }
}
