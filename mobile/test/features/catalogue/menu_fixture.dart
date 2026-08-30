import 'package:horecaos_mobile/src/features/catalogue/data/menu.dart';

/// A menu shaped exactly like the platform's own response.
///
/// Copied from the field names on `StorefrontCatalogQuery.StorefrontMenu` and
/// the records nested in it, so a rename on the server breaks these tests
/// rather than sliding through as an empty screen. Nothing here is invented:
/// there is no price anywhere, because the server sends none.
///
/// [carriesMembership] false reproduces a publication written before the
/// snapshot carried category membership and the product-to-group link. Those
/// publications are immutable and still served, so the empty case is a live
/// path and not merely history.
Map<String, Object?> menuJson({
  bool plovOrderable = true,
  bool includeStoppedVariant = true,
  bool carriesMembership = true,
}) => <String, Object?>{
  'publicationId': publicationId,
  'locale': 'en',
  'categories': <Object?>[
    <String, Object?>{
      'categoryId': categoryHot,
      'code': 'HOT',
      'name': 'Hot dishes',
      'parentCategoryId': null,
      'sortOrder': 1,
      if (carriesMembership) 'productIds': <String>[],
    },
    <String, Object?>{
      'categoryId': categorySoups,
      'code': 'SOUPS',
      'name': 'Soups',
      'parentCategoryId': categoryHot,
      'sortOrder': 2,
      if (carriesMembership) 'productIds': <String>[productPlov],
    },
    <String, Object?>{
      'categoryId': categoryDrinks,
      'code': 'DRINKS',
      'name': 'Drinks',
      'parentCategoryId': null,
      'sortOrder': 0,
      if (carriesMembership) 'productIds': <String>[productTea],
    },
  ],
  'products': <Object?>[
    <String, Object?>{
      'productId': productPlov,
      'code': 'PLOV',
      'name': 'Plov',
      'description': 'Rice, lamb, carrot.',
      'mediaAssetIds': <String>['0192d4b2-0000-7000-8000-0000000000m1'],
      if (carriesMembership)
        'modifierGroupIds': <String>[groupSpice, groupExtras],
      'variants': <Object?>[
        <String, Object?>{
          'variantId': variantPlovRegular,
          // The server omits `sku` when a variant has none, and this variant
          // has one. The absent case is covered by the tea below.
          'sku': 'PLOV-REG',
          'unitCode': 'PORTION',
          'isDefault': true,
          'orderable': plovOrderable,
        },
        if (includeStoppedVariant)
          <String, Object?>{
            'variantId': variantPlovLarge,
            'sku': 'PLOV-LRG',
            'unitCode': 'PORTION',
            'isDefault': false,
            // Present and not orderable: the branch has stopped it. It is not
            // absent, which is what "not offered here at all" looks like.
            'orderable': false,
          },
      ],
    },
    <String, Object?>{
      'productId': productTea,
      'code': 'TEA',
      'name': 'Green tea',
      'description': null,
      'mediaAssetIds': <String>[],
      if (carriesMembership) 'modifierGroupIds': <String>[],
      'variants': <Object?>[
        <String, Object?>{
          'variantId': variantTea,
          // No `sku` key at all — the server omits it rather than writing the
          // string "null", and the decoder must produce null.
          'unitCode': 'CUP',
          'isDefault': true,
          'orderable': true,
        },
      ],
    },
  ],
  'modifierGroups': <Object?>[
    <String, Object?>{
      'modifierGroupId': groupSpice,
      'code': 'SPICE',
      'name': 'Spice level',
      'allowSameOptionMultipleTimes': false,
      'required': true,
      'minimumSelections': 1,
      'maximumSelections': 1,
      'options': <Object?>[
        <String, Object?>{
          'optionId': optionMild,
          'code': 'MILD',
          'maximumQuantity': 1,
        },
        <String, Object?>{
          'optionId': optionHot,
          'code': 'HOT',
          'maximumQuantity': 1,
        },
      ],
    },
    <String, Object?>{
      'modifierGroupId': groupExtras,
      'allowSameOptionMultipleTimes': true,
      'code': 'EXTRAS',
      'name': 'Extras',
      'required': false,
      'minimumSelections': 0,
      'maximumSelections': 2,
      'options': <Object?>[
        <String, Object?>{
          'optionId': optionSalad,
          'code': 'SALAD',
          'maximumQuantity': 1,
        },
        <String, Object?>{
          'optionId': optionBread,
          'code': 'BREAD',
          'maximumQuantity': 3,
        },
        <String, Object?>{
          'optionId': optionSauce,
          'code': 'SAUCE',
          'maximumQuantity': 1,
        },
      ],
    },
  ],
};

StorefrontMenu menuFixture({
  bool plovOrderable = true,
  bool includeStoppedVariant = true,
  bool carriesMembership = true,
}) => StorefrontMenu.fromJson(
  menuJson(
    plovOrderable: plovOrderable,
    includeStoppedVariant: includeStoppedVariant,
    carriesMembership: carriesMembership,
  ),
);

MenuModifierGroup groupFixture({
  required bool required,
  required int minimumSelections,
  required int maximumSelections,
  int optionCount = 3,
  bool allowSameOptionMultipleTimes = false,
}) => MenuModifierGroup(
  modifierGroupId: groupExtras,
  code: 'TEST',
  name: 'Test group',
  required: required,
  minimumSelections: minimumSelections,
  maximumSelections: maximumSelections,
  allowSameOptionMultipleTimes: allowSameOptionMultipleTimes,
  options: <MenuModifierOption>[
    for (int i = 0; i < optionCount; i++)
      MenuModifierOption(
        optionId: 'option-$i',
        code: 'OPTION_$i',
        maximumQuantity: 1,
      ),
  ],
);

const String publicationId = '0192d4b2-0000-7000-8000-0000000000b1';

const String categoryHot = '0192d4b2-0000-7000-8000-0000000000c1';
const String categorySoups = '0192d4b2-0000-7000-8000-0000000000c2';
const String categoryDrinks = '0192d4b2-0000-7000-8000-0000000000c3';

const String productPlov = '0192d4b2-0000-7000-8000-0000000000p1';
const String productTea = '0192d4b2-0000-7000-8000-0000000000p2';

const String variantPlovRegular = '0192d4b2-0000-7000-8000-0000000000v1';
const String variantPlovLarge = '0192d4b2-0000-7000-8000-0000000000v2';
const String variantTea = '0192d4b2-0000-7000-8000-0000000000v3';

const String groupSpice = '0192d4b2-0000-7000-8000-0000000000g1';
const String groupExtras = '0192d4b2-0000-7000-8000-0000000000g2';

const String optionMild = '0192d4b2-0000-7000-8000-0000000000o1';
const String optionHot = '0192d4b2-0000-7000-8000-0000000000o2';
const String optionSalad = '0192d4b2-0000-7000-8000-0000000000o3';
const String optionBread = '0192d4b2-0000-7000-8000-0000000000o4';
const String optionSauce = '0192d4b2-0000-7000-8000-0000000000o5';
