import 'package:flutter/material.dart';

import '../../../design/q_empty_state.dart';
import '../../../design/horecaos_theme.dart';
import '../../../design/horecaos_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/menu.dart';
import '../data/menu_index.dart';
import 'product_tile.dart';

/// One category, and the products in it.
///
/// **This screen is built and is currently unreachable, and that is deliberate
/// rather than an oversight.** The published menu returns categories — with
/// names, parents and sort order — and no statement of which products belong to
/// them. The membership rows exist in `catalog.category_products`; the
/// publication snapshot does not carry them, so `MenuIndex.productsIn` has
/// nothing to answer with and `MenuIndex.categoriesAreNavigable` is false,
/// which is what keeps [MenuScreen] from offering a route here.
///
/// Building it anyway is the cheaper half of the work: when the snapshot
/// carries membership, one decoder change turns the whole browse on, and this
/// screen is already tested against a menu that has it.
class CategoryScreen extends StatelessWidget {
  const CategoryScreen({
    required this.index,
    required this.category,
    super.key,
    this.onOpenProduct,
  });

  final MenuIndex index;
  final MenuCategory category;
  final void Function(MenuProduct product)? onOpenProduct;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final HorecaOSTokens tokens = context.horecaos;
    final List<MenuProduct> products = index.productsIn(category.categoryId);

    return Scaffold(
      appBar: AppBar(title: Text(category.name)),
      body: products.isEmpty
          ? QEmptyState(
              title: l10n.catalogueEmptyTitle,
              body: l10n.catalogueEmptyBody,
            )
          : ListView.separated(
              itemCount: products.length,
              separatorBuilder: (BuildContext context, int _) => Divider(
                height: HorecaOSGeometry.hairline,
                color: tokens.hairline,
              ),
              itemBuilder: (BuildContext context, int position) {
                final MenuProduct product = products[position];
                return ProductTile(
                  product: product,
                  onTap: onOpenProduct == null
                      ? null
                      : () => onOpenProduct!(product),
                );
              },
            ),
    );
  }
}
