import 'package:flutter/widgets.dart';

import 'api/api_client.dart';
import 'auth/auth_session.dart';

/// What the composition root built, made reachable from the widget tree.
///
/// An `InheritedWidget` and not a service locator. A locator lets any widget
/// reach the network from anywhere, which is convenient exactly once and then
/// makes every widget untestable without one. Reaching these requires being
/// inside the scope, and a test provides its own.
class AppScope extends InheritedWidget {
  const AppScope({
    required this.session,
    required this.api,
    required super.child,
    super.key,
  });

  final AuthSession session;
  final QoidaApiClient api;

  static AppScope of(BuildContext context) {
    final AppScope? scope = context
        .dependOnInheritedWidgetOfExactType<AppScope>();
    if (scope == null) {
      throw FlutterError('No AppScope above this widget.');
    }
    return scope;
  }

  /// The dependencies are built once at startup and never swapped, so no
  /// dependent ever needs rebuilding on account of this widget. Session changes
  /// are published by the session itself, which is a `Listenable`.
  @override
  bool updateShouldNotify(AppScope oldWidget) => false;
}
