import 'package:flutter/widgets.dart';

import '../profile_area.dart';

/// Makes the profile area's dependencies reachable from its screens.
///
/// The same argument as `AppScope`: an `InheritedWidget` and not a locator, so a
/// screen can only reach the network by being placed inside a scope, and a test
/// provides its own.
class ProfileScope extends InheritedWidget {
  const ProfileScope({required this.area, required super.child, super.key});

  final ProfileArea area;

  static ProfileArea of(BuildContext context) {
    final ProfileScope? scope = context
        .dependOnInheritedWidgetOfExactType<ProfileScope>();
    if (scope == null) {
      throw FlutterError('No ProfileScope above this widget.');
    }
    return scope.area;
  }

  @override
  bool updateShouldNotify(ProfileScope oldWidget) => oldWidget.area != area;
}
