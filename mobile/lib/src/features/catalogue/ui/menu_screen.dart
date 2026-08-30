import 'package:flutter/material.dart';

import '../../../design/q_empty_state.dart';
import '../../../design/q_icon.dart';
import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/menu.dart';
import '../data/menu_index.dart';
import '../catalogue_controller.dart';
import 'catalogue_failure_view.dart';
import 'catalogue_pressable.dart';
import 'product_tile.dart';

/// Home: the brand's live menu for the chosen branch.
///
/// The archived application drove this screen from server-sent UI element
/// blocks, and this one does not. ADR 0044 gives merchandising to the marketing
/// module, and the marketing module has no storefront-facing endpoint — its
/// controller is `/api/v1/tenants/{id}/brands/{id}/marketing`, audiences and
/// campaigns, staff only. So there are no blocks to render and no block engine
/// to reimplement. What the server offers a customer is the published menu, and
/// that is what this screen shows.
class MenuScreen extends StatefulWidget {
  const MenuScreen({
    required this.controller,
    super.key,
    this.onOpenProduct,
    this.onOpenCategory,
  });

  final CatalogueController controller;

  /// Null makes the rows inert, which is what a widget test wants and what a
  /// host that has not wired navigation gets — rather than a tap that throws.
  final void Function(MenuProduct product)? onOpenProduct;
  final void Function(MenuCategory category)? onOpenCategory;

  @override
  State<MenuScreen> createState() => _MenuScreenState();
}

class _MenuScreenState extends State<MenuScreen> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerChanged);
  }

  @override
  void didUpdateWidget(MenuScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.controller, widget.controller)) {
      oldWidget.controller.removeListener(_onControllerChanged);
      widget.controller.addListener(_onControllerChanged);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  void _onControllerChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.catalogueMenuTitle)),
      body: switch (widget.controller.state) {
        MenuLoading() => const Center(child: CircularProgressIndicator()),
        MenuFailed(:final MenuFailureKind kind) => CatalogueFailureView(
          kind: kind,
          onRetry: widget.controller.load,
        ),
        MenuReady(:final MenuIndex index) => _MenuBody(
          index: index,
          onRefresh: widget.controller.load,
          onOpenProduct: widget.onOpenProduct,
          onOpenCategory: widget.onOpenCategory,
        ),
      },
    );
  }
}

class _MenuBody extends StatelessWidget {
  const _MenuBody({
    required this.index,
    required this.onRefresh,
    this.onOpenProduct,
    this.onOpenCategory,
  });

  final MenuIndex index;
  final Future<void> Function() onRefresh;
  final void Function(MenuProduct product)? onOpenProduct;
  final void Function(MenuCategory category)? onOpenCategory;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final QoidaTokens tokens = context.qoida;

    if (index.products.isEmpty) {
      // Published, and offering nothing here. A different sentence from "this
      // brand has no menu", which is the 404 and never reaches this widget.
      return RefreshIndicator(
        onRefresh: onRefresh,
        child: ListView(
          children: <Widget>[
            SizedBox(
              height: MediaQuery.sizeOf(context).height / 2,
              child: QEmptyState(
                title: l10n.catalogueEmptyTitle,
                body: l10n.catalogueEmptyBody,
              ),
            ),
          ],
        ),
      );
    }

    // Categories are shown only when they lead somewhere. Today the published
    // menu carries no product-to-category membership, so this is false and the
    // screen is one list — see MenuIndex.of. Rendering the headings anyway
    // would be a navigation that dead-ends.
    final bool showCategories =
        index.categoriesAreNavigable && onOpenCategory != null;

    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView.separated(
        itemCount:
            index.products.length +
            (showCategories ? index.rootCategories.length + 1 : 0),
        separatorBuilder: (BuildContext context, int _) =>
            Divider(height: QoidaGeometry.hairline, color: tokens.hairline),
        itemBuilder: (BuildContext context, int position) {
          if (showCategories) {
            if (position == 0) {
              return _SectionHeading(l10n.catalogueCategoriesTitle);
            }
            if (position <= index.rootCategories.length) {
              final MenuCategory category = index.rootCategories[position - 1];
              return _CategoryRow(
                category: category,
                onTap: () => onOpenCategory!(category),
              );
            }
          }
          final int productAt =
              position - (showCategories ? index.rootCategories.length + 1 : 0);
          final MenuProduct product = index.products[productAt];
          return ProductTile(
            product: product,
            onTap: onOpenProduct == null ? null : () => onOpenProduct!(product),
          );
        },
      ),
    );
  }
}

class _SectionHeading extends StatelessWidget {
  const _SectionHeading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        QoidaGeometry.spaceMd,
        QoidaGeometry.spaceLg,
        QoidaGeometry.spaceMd,
        QoidaGeometry.spaceSm,
      ),
      child: Text(text, style: Theme.of(context).textTheme.titleMedium),
    );
  }
}

class _CategoryRow extends StatelessWidget {
  const _CategoryRow({required this.category, required this.onTap});

  final MenuCategory category;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    return CataloguePressable(
      onTap: onTap,
      child: Row(
        children: <Widget>[
          Expanded(
            child: Text(
              category.name,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
          ),
          QIcon(QIconName.chevronRight, color: tokens.inkSubtle),
        ],
      ),
    );
  }
}
