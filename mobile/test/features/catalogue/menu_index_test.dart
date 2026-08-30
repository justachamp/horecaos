import 'package:flutter_test/flutter_test.dart';
import 'package:qoida_mobile/src/features/catalogue/data/menu.dart';
import 'package:qoida_mobile/src/features/catalogue/data/menu_index.dart';

import 'menu_fixture.dart';

void main() {
  group('the category tree', () {
    test('is rooted and ordered by the server\'s sort order', () {
      final MenuIndex index = MenuIndex.of(menuFixture());

      expect(
        index.rootCategories.map((MenuCategory c) => c.name),
        <String>['Drinks', 'Hot dishes'],
      );
      expect(
        index.childrenOf(categoryHot).map((MenuCategory c) => c.name),
        <String>['Soups'],
      );
      expect(index.childrenOf(categoryDrinks), isEmpty);
    });

    test('promotes a category whose parent is not in the publication', () {
      // Dropping it instead would hide a whole section of the menu because one
      // identifier did not resolve, and the customer would never know a section
      // existed.
      final StorefrontMenu menu = StorefrontMenu.fromJson(<String, Object?>{
        'publicationId': publicationId,
        'locale': 'en',
        'products': <Object?>[],
        'modifierGroups': <Object?>[],
        'categories': <Object?>[
          <String, Object?>{
            'categoryId': categorySoups,
            'code': 'SOUPS',
            'name': 'Soups',
            'parentCategoryId': categoryHot,
            'sortOrder': 1,
          },
        ],
      });

      expect(MenuIndex.of(menu).rootCategories, hasLength(1));
    });
  });

  group('category membership', () {
    test('is read off the menu the server sends', () {
      final MenuIndex index = MenuIndex.of(menuFixture());

      expect(index.categoriesAreNavigable, isTrue);
      // Through the subcategory: Hot holds no products of its own and Soups,
      // its child, holds the plov.
      expect(
        index.productsIn(categoryHot).map((MenuProduct p) => p.name),
        <String>['Plov'],
      );
      expect(
        index.productsIn(categoryDrinks).map((MenuProduct p) => p.name),
        <String>['Green tea'],
      );
    });

    test('is absent from a publication written before it was carried, so '
        'browsing is switched off', () {
      // Publications are immutable and still served, so this is a live path.
      // Rendering headings that lead nowhere is worse than not offering them.
      final MenuIndex index = MenuIndex.of(
        menuFixture(carriesMembership: false),
      );

      expect(index.menu.categories, isNotEmpty);
      expect(index.productsIn(categoryHot), isEmpty);
      expect(index.categoriesAreNavigable, isFalse);
    });

    test('turns the browse on the moment the server carries it', () {
      final MenuIndex index = MenuIndex.of(
        menuFixture(),
        productIdsByCategory: <String, List<String>>{
          categorySoups: <String>[productPlov],
          categoryDrinks: <String>[productTea],
        },
      );

      expect(index.categoriesAreNavigable, isTrue);
      // Through the subcategory: a parent lists what its children hold.
      expect(
        index.productsIn(categoryHot).map((MenuProduct p) => p.name),
        <String>['Plov'],
      );
      expect(
        index.productsIn(categoryDrinks).map((MenuProduct p) => p.name),
        <String>['Green tea'],
      );
    });

    test('lists a product once when two categories both hold it', () {
      final MenuIndex index = MenuIndex.of(
        menuFixture(),
        productIdsByCategory: <String, List<String>>{
          categoryHot: <String>[productPlov],
          categorySoups: <String>[productPlov],
        },
      );

      expect(index.productsIn(categoryHot), hasLength(1));
    });
  });

  group('modifier groups', () {
    test('are indexed by identifier', () {
      final MenuIndex index = MenuIndex.of(menuFixture());
      expect(index.modifierGroupById(groupSpice)?.name, 'Spice level');
      expect(index.modifierGroupById('nothing'), isNull);
    });

    test('attach to the product that offers them and to no other', () {
      final MenuIndex index = MenuIndex.of(menuFixture());

      // The link is per product. Showing every brand-wide group on every
      // product would ask a customer to pick a spice level for a cup of tea.
      expect(
        index.modifierGroupsFor(productPlov).map((MenuModifierGroup g) => g.code),
        <String>['SPICE', 'EXTRAS'],
      );
      expect(index.modifierGroupsFor(productTea), isEmpty);
    });

    test('attach to nothing on a publication written before the link was '
        'carried', () {
      final MenuIndex index = MenuIndex.of(
        menuFixture(carriesMembership: false),
      );

      expect(index.menu.modifierGroups, hasLength(2));
      expect(index.modifierGroupsFor(productPlov), isEmpty);
      expect(index.modifierGroupsFor(productTea), isEmpty);
    });
  });

  test('products are reachable by identifier', () {
    final MenuIndex index = MenuIndex.of(menuFixture());
    expect(index.productById(productTea)?.name, 'Green tea');
    expect(index.productById('nothing'), isNull);
  });
}
