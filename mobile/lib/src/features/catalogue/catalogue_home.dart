import 'package:flutter/material.dart';

import '../../api/api_client.dart';
import '../../app_scope.dart';
import 'data/catalogue_scope.dart';
import 'data/menu_index.dart';
import 'data/menu_repository.dart';
import 'domain/modifier_selection.dart';
import 'catalogue_controller.dart';
import 'ui/category_screen.dart';
import 'ui/menu_screen.dart';
import 'ui/product_detail_screen.dart';

/// The catalogue, mounted.
///
/// This is the one widget a host has to know about. It takes the branch, reads
/// the API client out of [AppScope], builds the controller, follows the
/// customer's locale, and owns navigation between the three screens.
///
/// **What it needs from outside and cannot get today.** [CatalogueScope] is a
/// constructor parameter because `AppConfig` carries no tenant, brand or
/// location, and the branch is a customer choice rather than a build constant.
/// Wiring it is the composition root's job:
///
/// ```dart
/// GoRoute(
///   path: Routes.menu,
///   builder: (BuildContext context, GoRouterState state) =>
///       CatalogueHome(scope: scopeForChosenBranch),
/// )
/// ```
///
/// Navigation between the screens is `Navigator.push` rather than a set of
/// declarative sub-routes. Product detail is a full-screen page above the
/// shell — which is where it belongs on a phone, over the bottom bar rather
/// than inside it — and this keeps the feature mountable, and testable, without
/// the router having to grow two paths for it.
class CatalogueHome extends StatefulWidget {
  const CatalogueHome({
    required this.scope,
    super.key,
    this.api,
    this.onAddToBasket,
  });

  final CatalogueScope scope;

  /// Supplying the composition-root client keeps this feature mountable
  /// without an inherited service. The fallback preserves its standalone host
  /// contract for screens that already provide [AppScope].
  final HorecaOSApiClient? api;

  /// Handed straight to product detail. The cart is a different feature and
  /// this one does not reach into it.
  final void Function(ProductConfiguration configuration)? onAddToBasket;

  @override
  State<CatalogueHome> createState() => _CatalogueHomeState();
}

class _CatalogueHomeState extends State<CatalogueHome> {
  CatalogueController? _controller;
  String? _locale;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();

    final String locale = Localizations.localeOf(context).languageCode;
    if (_controller == null) {
      _controller = CatalogueController(
        repository: MenuRepository(api: widget.api ?? AppScope.of(context).api),
        scope: widget.scope,
        locale: locale,
      );
      _locale = locale;
      // Names in a menu are resolved by the server per locale, so the first
      // load cannot start before the locale is known — which is why this is
      // here and not in initState.
      _controller!.load();
    } else if (locale != _locale) {
      _locale = locale;
      _controller!.setLocale(locale);
    }
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final CatalogueController controller = _controller!;
    return MenuScreen(
      controller: controller,
      onOpenProduct: (product) {
        final MenuState state = controller.state;
        if (state is! MenuReady) return;
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (BuildContext context) => ProductDetailScreen(
              index: state.index,
              product: product,
              onAddToBasket: widget.onAddToBasket,
            ),
          ),
        );
      },
      onOpenCategory: (category) {
        final MenuState state = controller.state;
        if (state is! MenuReady) return;
        final MenuIndex index = state.index;
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (BuildContext context) => CategoryScreen(
              index: index,
              category: category,
              onOpenProduct: (product) => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (BuildContext context) => ProductDetailScreen(
                    index: index,
                    product: product,
                    onAddToBasket: widget.onAddToBasket,
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
