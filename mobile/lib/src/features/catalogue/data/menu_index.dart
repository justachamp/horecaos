import 'menu.dart';

/// A read model over one [StorefrontMenu].
///
/// Built once when a menu arrives, so a screen never walks the product list to
/// answer a question a map can answer. Nothing here is a rule about the world —
/// availability, price and membership all come from the server — it is only
/// indexing.
final class MenuIndex {
  MenuIndex._({
    required this.menu,
    required Map<String, List<MenuCategory>> childCategories,
    required List<MenuCategory> rootCategories,
    required Map<String, MenuProduct> productsById,
    required Map<String, MenuModifierGroup> modifierGroupsById,
    required Map<String, List<String>> productIdsByCategory,
    required Map<String, List<String>> modifierGroupIdsByProduct,
  }) : _childCategories = Map<String, List<MenuCategory>>.unmodifiable(
         childCategories,
       ),
       _rootCategories = List<MenuCategory>.unmodifiable(rootCategories),
       _productsById = Map<String, MenuProduct>.unmodifiable(productsById),
       _modifierGroupsById = Map<String, MenuModifierGroup>.unmodifiable(
         modifierGroupsById,
       ),
       _productIdsByCategory = Map<String, List<String>>.unmodifiable(
         productIdsByCategory,
       ),
       _modifierGroupIdsByProduct = Map<String, List<String>>.unmodifiable(
         modifierGroupIdsByProduct,
       );

  /// Indexes [menu].
  ///
  /// [productIdsByCategory] and [modifierGroupIdsByProduct] default to what the
  /// published menu itself carries, and are parameters only so a test can pass
  /// membership the server did not send.
  ///
  /// The server filters a category's products to what this location serves and
  /// drops a category left with none, so a membership entry here always
  /// resolves. A publication written before membership was carried has neither,
  /// and [categoriesAreNavigable] stays false for it rather than rendering
  /// headings that lead nowhere.
  ///
  /// The product-to-group link is product-level. `variant_modifier_groups`
  /// exists in the schema with nothing writing it, so a variant carries no
  /// groups of its own and a screen must not imply it does.
  factory MenuIndex.of(
    StorefrontMenu menu, {
    Map<String, List<String>>? productIdsByCategory,
    Map<String, List<String>>? modifierGroupIdsByProduct,
  }) {
    final List<MenuCategory> sorted = <MenuCategory>[...menu.categories]
      ..sort(_byCategoryOrder);

    final Map<String, List<MenuCategory>> children =
        <String, List<MenuCategory>>{};
    final List<MenuCategory> roots = <MenuCategory>[];
    final Set<String> knownIds = <String>{
      for (final MenuCategory category in sorted) category.categoryId,
    };

    for (final MenuCategory category in sorted) {
      final String? parent = category.parentCategoryId;
      // A parent the publication does not contain makes this a root. The
      // alternative — dropping the category — hides a whole section of a menu
      // because one identifier did not resolve.
      if (parent == null || !knownIds.contains(parent)) {
        roots.add(category);
      } else {
        children.putIfAbsent(parent, () => <MenuCategory>[]).add(category);
      }
    }

    return MenuIndex._(
      menu: menu,
      childCategories: children,
      rootCategories: roots,
      productsById: <String, MenuProduct>{
        for (final MenuProduct product in menu.products)
          product.productId: product,
      },
      modifierGroupsById: <String, MenuModifierGroup>{
        for (final MenuModifierGroup group in menu.modifierGroups)
          group.modifierGroupId: group,
      },
      productIdsByCategory:
          productIdsByCategory ??
          <String, List<String>>{
            for (final MenuCategory category in sorted)
              if (category.productIds.isNotEmpty)
                category.categoryId: category.productIds,
          },
      modifierGroupIdsByProduct:
          modifierGroupIdsByProduct ??
          <String, List<String>>{
            for (final MenuProduct product in menu.products)
              if (product.modifierGroupIds.isNotEmpty)
                product.productId: product.modifierGroupIds,
          },
    );
  }

  final StorefrontMenu menu;

  final Map<String, List<MenuCategory>> _childCategories;
  final List<MenuCategory> _rootCategories;
  final Map<String, MenuProduct> _productsById;
  final Map<String, MenuModifierGroup> _modifierGroupsById;
  final Map<String, List<String>> _productIdsByCategory;
  final Map<String, List<String>> _modifierGroupIdsByProduct;

  /// Every product the branch offers, in the order the server sent them.
  ///
  /// Not re-sorted. The publication's own order is a merchandising decision
  /// somebody made in the console, and an alphabetical sort applied here would
  /// silently overrule it.
  List<MenuProduct> get products => menu.products;

  List<MenuCategory> get rootCategories => _rootCategories;

  List<MenuCategory> childrenOf(String categoryId) =>
      _childCategories[categoryId] ?? const <MenuCategory>[];

  MenuProduct? productById(String productId) => _productsById[productId];

  MenuModifierGroup? modifierGroupById(String groupId) =>
      _modifierGroupsById[groupId];

  /// The products in one category, and its subcategories.
  ///
  /// Empty today for every category, for the reason given on [MenuIndex.of].
  List<MenuProduct> productsIn(String categoryId) {
    final List<MenuProduct> found = <MenuProduct>[];
    final Set<String> seen = <String>{};

    void collect(String id) {
      for (final String productId
          in _productIdsByCategory[id] ?? const <String>[]) {
        final MenuProduct? product = _productsById[productId];
        if (product != null && seen.add(productId)) {
          found.add(product);
        }
      }
      for (final MenuCategory child in childrenOf(id)) {
        collect(child.categoryId);
      }
    }

    collect(categoryId);
    return found;
  }

  /// Whether browsing by category leads anywhere.
  ///
  /// False when the menu carries no membership, which is the case against
  /// today's server. A home screen that showed category headings over an empty
  /// list would be describing a menu the customer cannot reach.
  bool get categoriesAreNavigable =>
      _rootCategories.isNotEmpty && _productIdsByCategory.isNotEmpty;

  /// The modifier groups that apply to one product.
  ///
  /// Empty for every product today, and not because products have none: the
  /// published menu carries no link from a product to a group. See
  /// [MenuIndex.of].
  ///
  /// A group named for a product but absent from the menu is skipped rather
  /// than faked — a picker built from an identifier with no bounds behind it is
  /// a choice with no rules.
  List<MenuModifierGroup> modifierGroupsFor(String productId) =>
      <MenuModifierGroup>[
        for (final String groupId
            in _modifierGroupIdsByProduct[productId] ?? const <String>[])
          if (_modifierGroupsById[groupId] case final MenuModifierGroup group)
            group,
      ];

  static int _byCategoryOrder(MenuCategory a, MenuCategory b) {
    final int order = a.sortOrder.compareTo(b.sortOrder);
    // Ties broken by name so the order is stable between two loads of the same
    // publication rather than whatever the map iteration produced.
    return order != 0 ? order : a.name.compareTo(b.name);
  }
}
