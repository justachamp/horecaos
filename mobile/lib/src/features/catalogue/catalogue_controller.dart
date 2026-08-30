import 'package:flutter/foundation.dart';

import '../../api/api_exception.dart';
import 'data/catalogue_scope.dart';
import 'data/menu.dart';
import 'data/menu_index.dart';
import 'data/menu_repository.dart';

/// Why the menu is not on screen.
///
/// A closed set rather than an exception passed to the view, because each of
/// these is a different sentence to a customer and the difference matters.
/// "This brand has not published a menu" and "your phone is offline" have
/// nothing in common except that the list is empty.
enum MenuFailureKind {
  /// 404 — the brand has never published. Distinct from an empty menu.
  notPublished,

  /// 403. Not reachable on the catalog endpoint, which is unauthenticated, and
  /// carried anyway because the ADR 0025 open item makes a 403 the expected
  /// answer on the storefront's ordering endpoints and a customer must not see
  /// a raw failure if the catalog surface ever joins them.
  forbidden,

  /// No answer at all: no route to host, TLS failure, timeout.
  offline,

  /// The platform answered with something else, or the answer did not decode.
  unavailable,
}

/// What the menu screens render.
sealed class MenuState {
  const MenuState();
}

final class MenuLoading extends MenuState {
  const MenuLoading();
}

final class MenuReady extends MenuState {
  const MenuReady(this.index);

  final MenuIndex index;

  StorefrontMenu get menu => index.menu;
}

final class MenuFailed extends MenuState {
  const MenuFailed(this.kind, {this.correlationId});

  final MenuFailureKind kind;

  /// For a support conversation, never for the customer to read. It is not
  /// rendered by any screen in this feature.
  final String? correlationId;
}

/// Loads one branch's menu and holds it.
///
/// A `ChangeNotifier` rather than a state-management package: the application
/// has none in its dependencies, and adding one for a single screen family
/// would be a platform decision made by a feature.
final class CatalogueController extends ChangeNotifier {
  // A named parameter cannot be private in Dart, so these three cannot be
  // initialising formals however much the lint would like them to be. The
  // fields stay private because nothing outside this controller has business
  // reading them.
  CatalogueController({
    required MenuRepository repository,
    required CatalogueScope scope,
    required String locale,
  }) : _repository = repository, // ignore: prefer_initializing_formals
       // ignore: prefer_initializing_formals
       _scope = scope,
       // ignore: prefer_initializing_formals
       _locale = locale;

  final MenuRepository _repository;
  final CatalogueScope _scope;
  String _locale;

  MenuState _state = const MenuLoading();
  MenuState get state => _state;

  bool _disposed = false;
  int _generation = 0;

  /// Reloads when the customer switches language.
  ///
  /// The names in a menu are the server's, resolved per locale, so a language
  /// change is a different response and not a re-render of the same one.
  Future<void> setLocale(String locale) async {
    if (locale == _locale) return;
    _locale = locale;
    await load();
  }

  Future<void> load() async {
    final int generation = ++_generation;
    _publish(const MenuLoading(), generation);

    try {
      final StorefrontMenu menu = await _repository.menu(
        scope: _scope,
        locale: _locale,
      );
      _publish(MenuReady(MenuIndex.of(menu)), generation);
    } on ApiException catch (failure) {
      _publish(
        MenuFailed(switch (failure.status) {
          404 => MenuFailureKind.notPublished,
          403 => MenuFailureKind.forbidden,
          _ => MenuFailureKind.unavailable,
        }, correlationId: failure.problem.correlationId),
        generation,
      );
    } on ApiTransportException catch (failure) {
      _publish(
        MenuFailed(
          MenuFailureKind.offline,
          correlationId: failure.correlationId,
        ),
        generation,
      );
    } on FormatException {
      // A menu that does not decode is a contract break, and showing a
      // half-decoded menu would hide it behind a screen that looks nearly
      // right.
      _publish(const MenuFailed(MenuFailureKind.unavailable), generation);
    }
  }

  /// Ignores a result from a load that has been superseded, and never notifies
  /// after disposal — both of which are how a fast language switch or a
  /// backgrounded screen produces a "setState after dispose".
  void _publish(MenuState next, int generation) {
    if (_disposed || generation != _generation) return;
    _state = next;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
