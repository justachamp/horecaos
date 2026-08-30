import 'package:flutter_test/flutter_test.dart';
import 'package:qoida_mobile/src/features/catalogue/data/menu.dart';

import 'menu_fixture.dart';

/// Decoding, against the shape the platform actually serves.
void main() {
  group('a published menu', () {
    test('decodes the whole response', () {
      final StorefrontMenu menu = menuFixture();

      expect(menu.publicationId, publicationId);
      expect(menu.locale, 'en');
      expect(menu.categories, hasLength(3));
      expect(menu.products, hasLength(2));
      expect(menu.modifierGroups, hasLength(2));
    });

    test('keeps the order the server sent the products in', () {
      // The publication's order is a merchandising decision made in the
      // console. A client that sorted alphabetically would overrule it and
      // nobody would know where the change came from.
      expect(
        menuFixture().products.map((MenuProduct p) => p.name),
        <String>['Plov', 'Green tea'],
      );
    });

    test('reads an absent sku as null, never as the string "null"', () {
      final MenuVariant tea = menuFixture().products
          .firstWhere((MenuProduct p) => p.productId == productTea)
          .variants
          .single;

      expect(tea.sku, isNull);
      expect(tea.unitCode, 'CUP');
    });

    test('carries no price anywhere, because the server sends none', () {
      // Stated as a test and not only as a comment. If an amount ever appears
      // on this endpoint, somebody has to come back here and decide what to do
      // with it, rather than a screen quietly starting to show one.
      final RegExp money = RegExp('price|amount|minor|money|currency|cost');
      final Map<String, Object?> json = menuJson();

      void assertNoMoney(Map<String, Object?> node, String where) {
        for (final String key in node.keys) {
          expect(
            money.hasMatch(key.toLowerCase()),
            isFalse,
            reason: 'the menu carries no money, and $where has "$key"',
          );
        }
      }

      for (final Object? product in json['products']! as List<Object?>) {
        final Map<String, Object?> p = product! as Map<String, Object?>;
        assertNoMoney(p, 'a product');
        for (final Object? variant in p['variants']! as List<Object?>) {
          assertNoMoney(variant! as Map<String, Object?>, 'a variant');
        }
      }
      for (final Object? group in json['modifierGroups']! as List<Object?>) {
        final Map<String, Object?> g = group! as Map<String, Object?>;
        assertNoMoney(g, 'a modifier group');
        for (final Object? option in g['options']! as List<Object?>) {
          assertNoMoney(option! as Map<String, Object?>, 'a modifier option');
        }
      }
    });
  });

  group('availability', () {
    test('is the server\'s orderable flag and nothing else', () {
      final MenuProduct plov = menuFixture().products.first;

      expect(plov.variants, hasLength(2));
      expect(plov.variants.first.orderable, isTrue);
      // Present and stopped. Not hidden: the customer sees the item and sees
      // that it is unavailable.
      expect(plov.variants.last.orderable, isFalse);
      expect(plov.isOrderable, isTrue);
    });

    test('makes a product unorderable when no variant is orderable', () {
      final MenuProduct plov = menuFixture(plovOrderable: false).products.first;

      expect(plov.variants.every((MenuVariant v) => !v.orderable), isTrue);
      expect(plov.isOrderable, isFalse);
    });
  });

  group('the preferred variant', () {
    test('is the authored default when the branch is serving it', () {
      final MenuProduct plov = menuFixture().products.first;
      expect(plov.preferredVariant?.variantId, variantPlovRegular);
    });

    test('falls to an orderable variant when the default is stopped', () {
      final StorefrontMenu menu = StorefrontMenu.fromJson(<String, Object?>{
        'publicationId': publicationId,
        'locale': 'en',
        'categories': <Object?>[],
        'modifierGroups': <Object?>[],
        'products': <Object?>[
          <String, Object?>{
            'productId': productPlov,
            'code': 'PLOV',
            'name': 'Plov',
            'mediaAssetIds': <String>[],
            'variants': <Object?>[
              <String, Object?>{
                'variantId': variantPlovRegular,
                'isDefault': true,
                'orderable': false,
              },
              <String, Object?>{
                'variantId': variantPlovLarge,
                'isDefault': false,
                'orderable': true,
              },
            ],
          },
        ],
      });

      expect(menu.products.single.preferredVariant?.variantId, variantPlovLarge);
    });

    test('still names a variant when the branch has stopped every one', () {
      // The screen has to show something selected, and the alternative — a
      // product detail with no variant chosen — makes the sold-out state look
      // like a rendering failure.
      final MenuProduct plov = menuFixture(plovOrderable: false).products.first;
      expect(plov.preferredVariant?.variantId, variantPlovRegular);
    });
  });

  group('a malformed response', () {
    test('throws rather than rendering a product with no name', () {
      expect(
        () => MenuProduct.fromJson(<String, Object?>{
          'productId': productPlov,
          'mediaAssetIds': <String>[],
          'variants': <Object?>[],
        }),
        throwsFormatException,
      );
    });

    test('throws rather than rendering a variant with no identifier', () {
      expect(
        () => MenuVariant.fromJson(<String, Object?>{'orderable': true}),
        throwsFormatException,
      );
    });

    test('treats a missing orderable flag as not orderable', () {
      // The safe direction. An item wrongly shown as sold out annoys somebody;
      // an item wrongly shown as orderable takes their money and then fails at
      // checkout.
      final MenuVariant variant = MenuVariant.fromJson(<String, Object?>{
        'variantId': variantTea,
      });
      expect(variant.orderable, isFalse);
    });
  });
}
